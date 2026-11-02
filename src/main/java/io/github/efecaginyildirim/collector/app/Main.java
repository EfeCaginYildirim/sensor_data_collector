package io.github.efecaginyildirim.collector.app;

import io.github.efecaginyildirim.collector.api.HttpMonitoringApi;
import io.github.efecaginyildirim.collector.api.MonitoringApi;
import io.github.efecaginyildirim.collector.api.RetryPolicy;
import io.github.efecaginyildirim.collector.collect.CollectionJob;
import io.github.efecaginyildirim.collector.collect.RunReport;
import io.github.efecaginyildirim.collector.collect.SnapshotWriter;
import io.github.efecaginyildirim.collector.config.AppConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.config.ConfigException;
import io.github.efecaginyildirim.collector.config.ConfigLoader;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.persistence.InMemoryMotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.JdbcMotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Command-line entry point.
 *
 * <pre>
 *   run        collect once and exit (for cron / Windows Task Scheduler)   exit 0 / 2 partial / 1 failed
 *   daemon     stay running and collect every day at schedule.daily-time
 *   demo       full backfill + incremental run against the built-in fake API, no setup needed
 *   benchmark  compare request concurrency levels against the fake API with artificial latency
 *   fake-api   serve the fake vendor API on a port, e.g. for trying "run" by hand
 * </pre>
 */
public final class Main {

    static final int EXIT_USAGE = 64;
    static final int EXIT_CONFIG = 78;
    static final String DEFAULT_CONFIG = "config/collector.properties";

    private Main() { }

    /**
     * @param args command and options
     */
    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.getenv()));
    }

    /**
     * Runs a command; separated from {@link #main} so it can be tested without exiting the JVM.
     *
     * @param args command and options
     * @param out  where human-readable output goes
     * @param env  environment variables (for {@code SDC_*} overrides)
     * @return process exit code
     */
    static int execute(String[] args, PrintStream out, Map<String, String> env) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            usage(out);
            return EXIT_USAGE;
        }
        try {
            return switch (options.command()) {
                case "run" -> run(options, out, env);
                case "daemon" -> daemon(options, out, env);
                case "demo" -> demo(options, out);
                case "benchmark" -> benchmark(options, out);
                case "fake-api" -> fakeApi(options, out);
                case "help" -> {
                    usage(out);
                    yield 0;
                }
                default -> {
                    out.println("error: unknown command '" + options.command() + "'");
                    usage(out);
                    yield EXIT_USAGE;
                }
            };
        } catch (ConfigException e) {
            out.println(e.getMessage());
            return EXIT_CONFIG;
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            return EXIT_USAGE;
        } catch (RuntimeException e) {
            out.println("error: " + e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (IOException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ─── Commands ────────────────────────────────────────────────────────────────

    private static int run(Options o, PrintStream out, Map<String, String> env) {
        AppConfig config = loadConfig(o, env);
        Logging.configure(o.flag("verbose") ? Level.INFO : Level.WARNING, config.output().logDirectory());
        CollectionJob job = productionJob(config, repository(config, o));
        RunReport report = job.run();
        out.print(report.summary());
        return report.status().exitCode();
    }

    private static int daemon(Options o, PrintStream out, Map<String, String> env) throws InterruptedException {
        AppConfig config = loadConfig(o, env);
        Logging.configure(Level.INFO, config.output().logDirectory());
        CollectionJob job = productionJob(config, repository(config, o));
        DailyScheduler scheduler = new DailyScheduler(config.schedule().dailyRunTime(), config.schedule().zone(),
            Clock.systemUTC(), () -> out.print(job.run().summary()));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.close();
            stopped.countDown();
        }, "shutdown"));
        scheduler.start();
        if (o.flag("run-now")) {
            scheduler.runNow();
        }
        out.println("Daemon started; next scheduled run at " + scheduler.nextRunTime().orElseThrow() + ". Stop with Ctrl+C / SIGTERM.");
        stopped.await();
        return 0;
    }

    private static int demo(Options o, PrintStream out) throws IOException {
        int days = o.intOption("days", 60);
        int monitorCount = o.intOption("monitors", 6);
        long seed = o.intOption("seed", 42);
        Logging.configure(o.flag("verbose") ? Level.INFO : Level.SEVERE, Optional.empty());

        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ShiftableClock clock = new ShiftableClock(start);
        List<Integer> ids = monitorIds(monitorCount);
        SyntheticFleet fleet = SyntheticFleet.create(1, ids, SyntheticFleet.Options.defaults(seed, start));

        try (FakeMonitoringServer server = FakeMonitoringServer.start(fleet, clock, "demo", "demo")) {
            InMemoryMotorDataRepository repo = new InMemoryMotorDataRepository();
            CollectionConfig cc = new CollectionConfig(1, ids,
                LocalDate.ofInstant(start.minus(Duration.ofDays(days)), ZoneOffset.UTC),
                Duration.ofDays(3), Duration.ofDays(30), Duration.ofMinutes(5));
            CollectionJob job = new CollectionJob(demoApi(server), repo, cc, 8, clock,
                o.value("snapshot-dir").map(Path::of).map(SnapshotWriter::new));

            out.println("Fake vendor API at " + server.baseUri() + " serving " + monitorCount + " synthetic motors.");
            out.println();
            out.println("== Run 1: initial backfill of " + days + " days ==");
            out.print(job.run().summary());
            long backfillRequests = server.requests().size();

            clock.advance(Duration.ofDays(1));
            server.resetRecording();
            out.println();
            out.println("== Run 2: one day later - only watermark minus 3 days overlap is fetched ==");
            RunReport second = job.run();
            out.print(second.summary());
            out.printf("HTTP requests: %d for the backfill, %d for the incremental run%n%n", backfillRequests, server.requests().size());

            out.print(healthTable(repo, ids));
            return second.status().exitCode();
        }
    }

    private static int benchmark(Options o, PrintStream out) throws IOException {
        int monitorCount = o.intOption("monitors", 10);
        int days = o.intOption("days", 180);
        int latencyMs = o.intOption("latency-ms", 50);
        List<Integer> levels = o.value("concurrency").map(Main::parseInts).orElse(List.of(1, 2, 4, 8, 16));
        Logging.configure(Level.SEVERE, Optional.empty());

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Integer> ids = monitorIds(monitorCount);
        SyntheticFleet fleet = SyntheticFleet.create(1, ids, SyntheticFleet.Options.defaults(7, now));
        out.printf("Benchmark: %d monitors, %d days of history, %d ms latency per request%n", monitorCount, days, latencyMs);
        out.printf("%-12s %-10s %-10s %-9s %s%n", "concurrency", "elapsed", "requests", "speedup", "max in flight");

        try (FakeMonitoringServer server = FakeMonitoringServer.start(fleet, Clock.fixed(now, ZoneOffset.UTC), "demo", "demo")) {
            server.setLatency(Duration.ofMillis(latencyMs));
            CollectionConfig cc = new CollectionConfig(1, ids, LocalDate.ofInstant(now.minus(Duration.ofDays(days)), ZoneOffset.UTC),
                Duration.ofDays(3), Duration.ofDays(30), Duration.ofMinutes(10));
            double baseline = 0;
            for (int level : levels) {
                server.resetRecording();
                CollectionJob job = new CollectionJob(demoApi(server), new InMemoryMotorDataRepository(), cc, level,
                    Clock.fixed(now, ZoneOffset.UTC), Optional.empty());
                long t0 = System.nanoTime();
                RunReport report = job.run();
                double seconds = (System.nanoTime() - t0) / 1e9;
                if (report.status() != RunReport.Status.SUCCESS) {
                    out.print(report.summary());
                    return 1;
                }
                baseline = baseline == 0 ? seconds : baseline;
                out.printf("%-12d %-10s %-10d %-9s %d%n", level, String.format("%.2f s", seconds),
                    server.requests().size(), String.format("%.1fx", baseline / seconds), server.maxConcurrentRequests());
            }
        }
        return 0;
    }

    private static int fakeApi(Options o, PrintStream out) throws IOException, InterruptedException {
        int port = o.intOption("port", 8080);
        List<Integer> ids = monitorIds(o.intOption("monitors", 6));
        Instant now = Instant.now();
        SyntheticFleet fleet = SyntheticFleet.create(1, ids, SyntheticFleet.Options.defaults(o.intOption("seed", 42), now));
        Logging.configure(Level.INFO, Optional.empty());
        CountDownLatch stopped = new CountDownLatch(1);
        FakeMonitoringServer server = FakeMonitoringServer.start(fleet, Clock.systemUTC(), "demo", "demo", port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "shutdown"));
        out.println("Fake vendor API listening at " + server.baseUri());
        out.println("  username/password: demo / demo, plant id: 1, monitor ids: " + ids);
        out.println("  try: java -jar sensor-data-collector.jar run --config config/collector.example.properties --in-memory");
        stopped.await();
        return 0;
    }

    // ─── Wiring ──────────────────────────────────────────────────────────────────

    private static AppConfig loadConfig(Options o, Map<String, String> env) {
        return ConfigLoader.load(Path.of(o.value("config").orElse(DEFAULT_CONFIG)), env);
    }

    private static MotorDataRepository repository(AppConfig config, Options o) {
        if (o.flag("in-memory")) {
            return new InMemoryMotorDataRepository();
        }
        MotorDataRepository repo = JdbcMotorDataRepository.create(config.database().orElseThrow(() -> new ConfigException(List.of(
            "db.url is required for this command (or pass --in-memory to try without a database)"))));
        repo.initialize();
        return repo;
    }

    private static CollectionJob productionJob(AppConfig config, MotorDataRepository repo) {
        return new CollectionJob(HttpMonitoringApi.create(config.api()), repo, config.collection(),
            config.api().maxConcurrentRequests(), Clock.systemUTC(),
            config.output().snapshotDirectory().map(SnapshotWriter::new));
    }

    private static MonitoringApi demoApi(FakeMonitoringServer server) {
        return new HttpMonitoringApi(server.baseUri(), "demo", "demo", HttpClient.newHttpClient(), Duration.ofSeconds(30),
            RetryPolicy.defaults(3), Thread::sleep, RandomGenerator.getDefault());
    }

    private static List<Integer> monitorIds(int count) {
        if (count < 1 || count > 1000) {
            throw new IllegalArgumentException("--monitors must be between 1 and 1000");
        }
        return IntStream.rangeClosed(1001, 1000 + count).boxed().toList();
    }

    private static List<Integer> parseInts(String csv) {
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            out.add(Integer.parseInt(s.strip()));
        }
        return out;
    }

    /** Compares the first and last week of key bearing indicators per monitor. */
    static String healthTable(InMemoryMotorDataRepository repo, List<Integer> ids) {
        StringBuilder sb = new StringBuilder("Fleet health (first week -> last week of collected data)\n");
        sb.append(String.format("  %-6s %-24s %-7s %-17s %-17s%n", "id", "monitor", "health", "kurtosis X", "BPFO amplitude"));
        for (int id : ids) {
            List<ComputedFeatureSample> samples = repo.computedFeatures(id);
            if (samples.isEmpty()) {
                continue;
            }
            Instant first = samples.get(0).time();
            Instant last = samples.get(samples.size() - 1).time();
            Integer health = Optional.ofNullable(repo.statusSnapshots(id).lastEntry()).map(Entry::getValue)
                .map(MonitorStatus::healthScore).orElse(null);
            String name = repo.monitor(id).map(p -> p.machine().name() + " " + p.monitor().name()).orElse("?");
            sb.append(String.format("  %-6d %-24s %-7s %-17s %-17s%n", id, name, health == null ? "-" : health,
                trend(samples, ComputedFeature.KURTACCLX, first, last), trend(samples, ComputedFeature.BPFO, first, last)));
        }
        return sb.toString();
    }

    private static String trend(List<ComputedFeatureSample> samples, ComputedFeature f, Instant first, Instant last) {
        double early = mean(samples, f, first, first.plus(Duration.ofDays(7)));
        double late = mean(samples, f, last.minus(Duration.ofDays(7)), last.plusSeconds(1));
        String arrow = late > early * 1.25 ? "  RISING" : late < early * 0.8 ? "  FALLING" : "";
        return String.format("%.2f -> %.2f%s", early, late, arrow);
    }

    private static double mean(List<ComputedFeatureSample> samples, ComputedFeature f, Instant from, Instant to) {
        return samples.stream()
            .filter(s -> !s.time().isBefore(from) && s.time().isBefore(to))
            .map(s -> s.value(f))
            .filter(v -> v.isPresent())
            .mapToDouble(v -> v.getAsDouble())
            .average().orElse(Double.NaN);
    }

    private static void usage(PrintStream out) {
        out.println("""
            Usage: java -jar sensor-data-collector.jar <command> [options]

            Commands:
              run        Collect once and exit. Exit code 0 = success, 2 = partial, 1 = failed.
              daemon     Keep running and collect daily at schedule.daily-time (schedule.zone).
              demo       Backfill + incremental run against a built-in fake API. No setup needed.
              benchmark  Compare request concurrency levels against the fake API.
              fake-api   Serve the fake vendor API (default port 8080).
              help       Show this text.

            Options:
              --config <file>        configuration file (default: config/collector.properties)
              --in-memory            run/daemon: keep data in memory instead of SQL Server
              --run-now              daemon: also run immediately at start
              --verbose              more log output
              --days <n>             demo/benchmark: days of history (60 / 180)
              --monitors <n>         demo/benchmark/fake-api: number of synthetic motors (6 / 10 / 6)
              --seed <n>             demo/fake-api: random seed of the synthetic data
              --snapshot-dir <dir>   demo: also write JSON snapshots
              --latency-ms <n>       benchmark: artificial latency per request (50)
              --concurrency <list>   benchmark: levels to compare (1,2,4,8,16)
              --port <n>             fake-api: TCP port (8080)

            Every configuration key can be overridden by an environment variable,
            e.g. api.password -> SDC_API_PASSWORD.""");
    }

    /** Minimal option parser: one command followed by {@code --name value} and {@code --flag} options. */
    record Options(String command, Map<String, String> values) {

        private static final List<String> FLAGS = List.of("in-memory", "run-now", "verbose");

        static Options parse(String[] args) {
            String command = null;
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("-h") || a.equals("--help")) {
                    return new Options("help", values);
                }
                if (a.startsWith("--")) {
                    String name = a.substring(2);
                    if (FLAGS.contains(name)) {
                        values.put(name, "true");
                    } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        values.put(name, args[++i]);
                    } else {
                        throw new IllegalArgumentException("option --" + name + " needs a value");
                    }
                } else if (command == null) {
                    command = a;
                } else {
                    throw new IllegalArgumentException("unexpected argument '" + a + "'");
                }
            }
            return new Options(command == null ? "help" : command, values);
        }

        Optional<String> value(String name) {
            return Optional.ofNullable(values.get(name));
        }

        boolean flag(String name) {
            return values.containsKey(name);
        }

        int intOption(String name, int defaultValue) {
            String v = values.get(name);
            if (v == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be an integer, got '" + v + "'");
            }
        }
    }
}
