package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.api.ApiException;
import io.github.efecaginyildirim.collector.api.HttpMonitoringApi;
import io.github.efecaginyildirim.collector.api.MonitoringApi;
import io.github.efecaginyildirim.collector.api.RetryPolicy;
import io.github.efecaginyildirim.collector.collect.DailyScheduler;
import io.github.efecaginyildirim.collector.collect.CollectionJob;
import io.github.efecaginyildirim.collector.collect.RunHistoryListener;
import io.github.efecaginyildirim.collector.collect.RunListener;
import io.github.efecaginyildirim.collector.config.AppConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.config.ConfigException;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.persistence.DatasetQuery;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;
import io.github.efecaginyildirim.collector.persistence.H2Database;
import io.github.efecaginyildirim.collector.persistence.JdbcMotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.MonitorSummary;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.StatusSnapshot;
import io.github.efecaginyildirim.collector.persistence.RunHistory.RunRecord;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import io.github.efecaginyildirim.collector.ui.SettingsStore.Mode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Everything the desktop UI does, behind one thread-safe facade: settings, collection runs,
 * progress, schedule and read access to the collected data.
 *
 * <ul>
 *   <li><b>Storage.</b> Each mode has its own H2 file under {@code <data dir>/data/}, so demo data
 *       never mixes with real data.</li>
 *   <li><b>Runs.</b> One at a time, on a dedicated thread. A new run is refused while one is in
 *       progress; settings changes apply from the next run.</li>
 *   <li><b>Demo mode.</b> Starts the built-in fake vendor API on a free local port. The scenario's
 *       reference time is fixed on first use and saved, so the synthetic motors keep degrading
 *       consistently across restarts and each new day brings new samples.</li>
 * </ul>
 */
public final class CollectorService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CollectorService.class.getName());

    /** Result of {@link #startRun()}. */
    public enum StartResult {
        /** The run was queued. */
        STARTED,
        /** Another run is in progress. */
        ALREADY_RUNNING,
        /** The settings are incomplete or invalid; see {@link ProgressTracker.Progress#lastSummary()}. */
        INVALID_SETTINGS
    }

    /**
     * Outcome of a connection check.
     *
     * @param ok        whether login and the plant request succeeded and all monitors exist
     * @param message   human-readable result
     * @param found     configured monitors present in the plant
     * @param missing   configured monitors not present in the plant
     */
    public record ConnectionCheck(boolean ok, String message, List<Integer> found, List<Integer> missing) { }

    /**
     * General state for the status bar.
     *
     * @param mode          current mode
     * @param dataDirectory where settings and databases live
     * @param running       whether a run is in progress
     * @param nextRun       next scheduled run, if the schedule is enabled
     */
    public record Status(Mode mode, Path dataDirectory, boolean running, Optional<ZonedDateTime> nextRun) { }

    private final Path dataDirectory;
    private final SettingsStore settings;
    private final Clock clock;
    private final ProgressTracker progress = new ProgressTracker();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> new Thread(r, "collection-run"));
    private final Object lock = new Object();

    private boolean busy;
    private JdbcMotorDataRepository repository;
    private Mode repositoryMode;
    private FakeMonitoringServer demoServer;
    private String demoKey;
    private DailyScheduler scheduler;

    /**
     * @param dataDirectory where settings and databases are kept; created if missing
     * @param clock         source of "now" for collection and the demo scenario
     */
    public CollectorService(Path dataDirectory, Clock clock) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = new SettingsStore(dataDirectory.resolve("settings.properties"), ZoneId.systemDefault());
        synchronized (lock) {
            repositoryFor(SettingsStore.mode(settings.load()));
        }
        applySchedule();
    }

    // ─── Runs ────────────────────────────────────────────────────────────────────

    /**
     * Starts a collection run in the background with the current settings.
     *
     * @return whether the run was started
     */
    public StartResult startRun() {
        CollectionJob job;
        JdbcMotorDataRepository repo;
        synchronized (lock) {
            if (busy) {
                return StartResult.ALREADY_RUNNING;
            }
            Map<String, String> s = settings.load();
            try {
                repo = repositoryFor(SettingsStore.mode(s));
                job = buildJob(s, repo);
            } catch (ConfigException e) {
                progress.runAborted("INVALID_SETTINGS", e.getMessage());
                return StartResult.INVALID_SETTINGS;
            }
            busy = true;
        }
        runner.execute(() -> {
            try {
                job.run(RunListener.all(progress, new RunHistoryListener(repo)));
            } catch (RuntimeException | Error e) {
                LOG.log(System.Logger.Level.ERROR, "collection run failed unexpectedly", e);
                progress.runAborted("FAILED", "Run failed unexpectedly: " + e);
            } finally {
                synchronized (lock) {
                    busy = false;
                }
            }
        });
        return StartResult.STARTED;
    }

    /** @return current progress */
    public ProgressTracker.Progress progress() {
        return progress.snapshot();
    }

    /** @return mode, data directory, running flag and next scheduled run */
    public Status status() {
        synchronized (lock) {
            return new Status(SettingsStore.mode(settings.load()), dataDirectory, busy,
                scheduler == null ? Optional.empty() : scheduler.nextRunTime());
        }
    }

    // ─── Reads ───────────────────────────────────────────────────────────────────

    /** @return monitors of the current mode's database */
    public List<MonitorSummary> monitors() {
        return reader().monitors();
    }

    /**
     * @param monitorId monitor id
     * @param limit     maximum snapshots
     * @return status snapshots, newest first
     */
    public List<StatusSnapshot> statusHistory(int monitorId, int limit) {
        return reader().statusHistory(monitorId, limit);
    }

    /**
     * @param limit maximum runs
     * @return recent runs, newest first
     */
    public List<RunRecord> runs(int limit) {
        return reader().recentRuns(limit);
    }

    /**
     * Chart data for one monitor over all collected time.
     *
     * @param monitorId monitor id
     * @param features  features to plot
     * @param maxPoints upper bound on points
     * @return the downsampled series
     */
    public Series series(int monitorId, List<ComputedFeature> features, int maxPoints) {
        List<DatasetRow> rows = new ArrayList<>();
        TimeWindow all = new TimeWindow(Instant.EPOCH, clock.instant().plus(Duration.ofDays(1)));
        reader().query(new DatasetQuery(List.of(monitorId), all, features, OptionalInt.empty()), rows::add);
        return Series.downsample(features, rows, maxPoints);
    }

    // ─── Settings ────────────────────────────────────────────────────────────────

    /**
     * Settings for display; secrets are replaced by {@code ""} and an {@code <key>-set} flag.
     *
     * @return settings in display order
     */
    public Map<String, String> settingsForDisplay() {
        Map<String, String> s = new LinkedHashMap<>(settings.load());
        for (String key : SettingsStore.SECRET_KEYS) {
            boolean set = !s.getOrDefault(key, "").isEmpty();
            s.put(key, "");
            s.put(key + "-set", String.valueOf(set));
        }
        return s;
    }

    /**
     * Validates and saves settings; the schedule is updated immediately, the mode on the next read or run.
     *
     * @param changes keys and new values
     * @return problems; empty if saved
     */
    public List<String> updateSettings(Map<String, String> changes) {
        List<String> problems = settings.update(changes);
        if (problems.isEmpty()) {
            applySchedule();
        }
        return problems;
    }

    /**
     * Checks that the API is reachable with the given (unsaved) changes applied to the current settings.
     *
     * @param changes candidate changes, e.g. the form's current values
     * @return the result
     */
    public ConnectionCheck testConnection(Map<String, String> changes) {
        Map<String, String> s = new LinkedHashMap<>(settings.load());
        changes.forEach((k, v) -> {
            if (!(SettingsStore.SECRET_KEYS.contains(k) && (v == null || v.isEmpty()))) {
                s.put(k, v == null ? "" : v.strip());
            }
        });
        try {
            MonitoringApi api;
            CollectionConfig config;
            if (SettingsStore.mode(s) == Mode.DEMO) {
                synchronized (lock) {
                    api = demoApi(s);
                    config = demoConfig(s);
                }
            } else {
                AppConfig app = SettingsStore.apiConfig(s);
                api = HttpMonitoringApi.create(app.api());
                config = app.collection();
            }
            Plant plant = api.fetchPlant(config.plantId());
            List<Integer> found = config.monitorIds().stream().filter(id -> plant.locate(id).isPresent()).toList();
            List<Integer> missing = config.monitorIds().stream().filter(id -> plant.locate(id).isEmpty()).toList();
            String message = "Connected to plant " + plant.id() + (plant.name() == null ? "" : " (" + plant.name() + ")")
                + ": " + found.size() + " of " + config.monitorIds().size() + " monitors found"
                + (missing.isEmpty() ? "" : ", missing " + missing);
            return new ConnectionCheck(missing.isEmpty(), message, found, missing);
        } catch (ConfigException e) {
            return new ConnectionCheck(false, String.join("; ", e.problems()), List.of(), List.of());
        } catch (ApiException e) {
            return new ConnectionCheck(false, e.getMessage(), List.of(), List.of());
        }
    }

    // ─── Wiring ──────────────────────────────────────────────────────────────────

    private JdbcMotorDataRepository reader() {
        synchronized (lock) {
            return busy ? repository : repositoryFor(SettingsStore.mode(settings.load()));
        }
    }

    /** Opens the mode's database, closing the other one. Caller holds {@link #lock}. */
    private JdbcMotorDataRepository repositoryFor(Mode mode) {
        if (repository == null || repositoryMode != mode) {
            if (repository != null) {
                repository.close();
            }
            repository = H2Database.file(dataDirectory.resolve("data").resolve(mode == Mode.DEMO ? "demo" : "collector"));
            repositoryMode = mode;
        }
        return repository;
    }

    /** Caller holds {@link #lock}. */
    private CollectionJob buildJob(Map<String, String> s, JdbcMotorDataRepository repo) {
        if (SettingsStore.mode(s) == Mode.DEMO) {
            return new CollectionJob(demoApi(s), repo, demoConfig(s), 8, clock, Optional.empty());
        }
        AppConfig app = SettingsStore.apiConfig(s);
        return new CollectionJob(HttpMonitoringApi.create(app.api()), repo, app.collection(),
            app.api().maxConcurrentRequests(), clock, Optional.empty());
    }

    /** Starts or reuses the fake API for the current demo settings. Caller holds {@link #lock}. */
    private MonitoringApi demoApi(Map<String, String> s) {
        Instant reference = demoReferenceTime(s);
        int monitors = Integer.parseInt(s.get("demo.monitors"));
        long seed = Long.parseLong(s.get("demo.seed"));
        String key = monitors + "/" + seed + "/" + reference;
        if (demoServer == null || !key.equals(demoKey)) {
            if (demoServer != null) {
                demoServer.close();
            }
            SyntheticFleet fleet = SyntheticFleet.create(1, demoMonitorIds(monitors), SyntheticFleet.Options.defaults(seed, reference));
            try {
                demoServer = FakeMonitoringServer.start(fleet, clock, "demo", "demo");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start the demo API", e);
            }
            demoKey = key;
        }
        return new HttpMonitoringApi(demoServer.baseUri(), "demo", "demo", HttpClient.newHttpClient(),
            Duration.ofSeconds(30), RetryPolicy.defaults(3), Thread::sleep, RandomGenerator.getDefault());
    }

    private CollectionConfig demoConfig(Map<String, String> s) {
        Instant reference = demoReferenceTime(s);
        int days = Integer.parseInt(s.get("demo.history-days"));
        return new CollectionConfig(1, demoMonitorIds(Integer.parseInt(s.get("demo.monitors"))),
            LocalDate.ofInstant(reference.minus(Duration.ofDays(days)), ZoneOffset.UTC),
            Duration.ofDays(3), Duration.ofDays(30), Duration.ofMinutes(10));
    }

    private Instant demoReferenceTime(Map<String, String> s) {
        String saved = s.getOrDefault("demo.reference-time", "");
        if (!saved.isEmpty()) {
            return Instant.parse(saved);
        }
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        settings.put("demo.reference-time", now.toString());
        s.put("demo.reference-time", now.toString());
        return now;
    }

    static List<Integer> demoMonitorIds(int count) {
        return IntStream.rangeClosed(1001, 1000 + count).boxed().toList();
    }

    private void applySchedule() {
        Map<String, String> s = settings.load();
        synchronized (lock) {
            if (scheduler != null) {
                scheduler.close();
                scheduler = null;
            }
            if (Boolean.parseBoolean(s.get("schedule.enabled"))) {
                scheduler = new DailyScheduler(LocalTime.parse(s.get("schedule.daily-time")), ZoneId.of(s.get("schedule.zone")),
                    clock, this::startRun);
                scheduler.start();
            }
        }
    }

    /** Stops the schedule, waits up to 30 s for a running collection, then closes the database. */
    @Override
    public void close() {
        synchronized (lock) {
            if (scheduler != null) {
                scheduler.close();
            }
        }
        runner.shutdown();
        try {
            if (!runner.awaitTermination(30, TimeUnit.SECONDS)) {
                runner.shutdownNow();
            }
        } catch (InterruptedException e) {
            runner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            if (demoServer != null) {
                demoServer.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }
}
