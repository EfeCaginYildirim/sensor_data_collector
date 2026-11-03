package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.api.MonitoringApi;
import io.github.efecaginyildirim.collector.api.MonitoringApi.ApiPage;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Outcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Status;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;
import io.github.efecaginyildirim.collector.persistence.MonitorBatch;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository.SaveResult;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One collection run: plan a window per monitor, fetch it in parallel, save what is gap-free.
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li>Every monitor's window is split into chunks; each chunk needs two requests (basic and
 *       computed features). All requests of all monitors run on virtual threads, and a fair
 *       {@link Semaphore} caps how many are in flight to protect the vendor's rate limit.
 *       Older chunks are submitted first, so they tend to finish first.</li>
 *   <li>The calling thread is the only writer. It saves monitors in the order they finish, so
 *       database work overlaps with the remaining downloads and no write contention arises.</li>
 *   <li>A run has a deadline. Monitors not finished by then are reported as failed and their
 *       requests are interrupted; nothing partial is written for them.</li>
 * </ul>
 *
 * <h2>Gap-free guarantee</h2>
 * If chunk {@code k} of a monitor fails, only chunks {@code 0..k-1} are saved, and the watermark
 * moves to the end of chunk {@code k-1}. Later chunks are discarded even if they succeeded, since
 * saving them would move the watermark past a hole. The next run resumes at the watermark.
 *
 * <p>Instances are reusable but not re-entrant: a second concurrent {@link #run()} is rejected.
 */
public final class CollectionJob {

    private static final System.Logger LOG = System.getLogger(CollectionJob.class.getName());

    private final MonitoringApi api;
    private final MotorDataRepository repository;
    private final CollectionConfig config;
    private final WindowPlanner planner;
    private final int maxConcurrentRequests;
    private final Clock clock;
    private final Optional<SnapshotWriter> snapshots;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * @param api                   vendor API client
     * @param repository            storage
     * @param config                what to collect
     * @param maxConcurrentRequests cap on in-flight API requests; at least 1
     * @param clock                 source of "now"
     * @param snapshots             optional JSON archive of each run
     */
    public CollectionJob(MonitoringApi api, MotorDataRepository repository, CollectionConfig config,
                         int maxConcurrentRequests, Clock clock, Optional<SnapshotWriter> snapshots) {
        this.api = Objects.requireNonNull(api, "api");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.config = Objects.requireNonNull(config, "config");
        this.planner = new WindowPlanner(config.initialStartDate(), config.overlap());
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be >= 1");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * Executes one run without progress reporting.
     *
     * @return the run report
     * @throws IllegalStateException if another run of this job is in progress
     * @see #run(RunListener)
     */
    public RunReport run() {
        return run(RunListener.NONE);
    }

    /**
     * Executes one run. Never throws for API or storage failures; they are reported per monitor.
     *
     * @param listener receives progress events; exceptions it throws are logged and ignored
     * @return the run report
     * @throws IllegalStateException if another run of this job is in progress
     */
    public RunReport run(RunListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("a collection run is already in progress");
        }
        try {
            return doRun(new SafeListener(Objects.requireNonNull(listener, "listener")));
        } finally {
            running.set(false);
        }
    }

    /** @return whether a run is in progress */
    public boolean isRunning() {
        return running.get();
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    private record MonitorPlan(MonitorPlacement placement, TimeWindow window, Optional<Instant> watermark,
                               List<TimeWindow> chunks) {
        int monitorId() {
            return placement.monitor().id();
        }
    }

    private record ChunkResult(TimeWindow window, ApiPage<BasicFeature> basic,
                               ApiPage<ComputedFeatureSample> computed, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    private record MonitorFetch(MonitorPlan plan, List<ChunkResult> chunks, Throwable error) { }

    private RunReport doRun(SafeListener listener) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long startNanos = System.nanoTime();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LOG.log(System.Logger.Level.INFO, () -> "Run " + runId + " started for " + config.monitorIds().size() + " monitors");
        listener.runStarted(runId, now, config.monitorIds());

        Plant plant;
        try {
            plant = api.fetchPlant(config.plantId());
        } catch (RuntimeException e) {
            String reason = "fetching plant " + config.plantId() + " failed: " + e.getMessage();
            List<MonitorOutcome> outcomes = config.monitorIds().stream()
                .map(id -> MonitorOutcome.failed(id, Optional.empty(), Optional.empty(), "plant hierarchy unavailable"))
                .toList();
            outcomes.forEach(listener::monitorFinished);
            return finish(runId, now, startNanos, Status.FAILED, Optional.of(reason), outcomes, List.of(), List.of(), listener);
        }

        Map<Integer, MonitorOutcome> outcomes = new LinkedHashMap<>();
        List<MonitorBatch> saved = new ArrayList<>();
        long deadline = startNanos + config.runDeadline().toNanos();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore permits = new Semaphore(maxConcurrentRequests, true);
            BlockingQueue<MonitorFetch> finished = new LinkedBlockingQueue<>();
            Map<Integer, MonitorPlan> pending = new LinkedHashMap<>();

            for (int id : config.monitorIds()) {
                planMonitor(id, plant, now, outcomes, listener).ifPresent(plan -> {
                    pending.put(id, plan);
                    listener.monitorPlanned(id, plan.window(), plan.chunks().size());
                    fetch(plan, executor, permits, listener).whenComplete((chunks, error) ->
                        finished.add(new MonitorFetch(plan, chunks, error)));
                });
            }

            String abortReason = null;
            while (!pending.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                MonitorFetch f;
                try {
                    f = remaining > 0 ? finished.poll(remaining, TimeUnit.NANOSECONDS) : null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abortReason = "run interrupted";
                    break;
                }
                if (f == null) {
                    abortReason = "run deadline of " + config.runDeadline() + " exceeded";
                    break;
                }
                pending.remove(f.plan().monitorId());
                MonitorOutcome outcome = persist(f, now, saved);
                outcomes.put(outcome.monitorId(), outcome);
                listener.monitorFinished(outcome);
                LOG.log(System.Logger.Level.INFO, () -> "Run " + runId + ": monitor " + outcome.monitorId() + " " + outcome.outcome());
            }
            if (!pending.isEmpty()) {
                executor.shutdownNow();
                for (MonitorPlan p : pending.values()) {
                    MonitorOutcome outcome = MonitorOutcome.failed(p.monitorId(), Optional.of(p.window()), p.watermark(), abortReason);
                    outcomes.put(p.monitorId(), outcome);
                    listener.monitorFinished(outcome);
                }
            }
        }

        List<MonitorOutcome> ordered = config.monitorIds().stream().map(outcomes::get).toList();
        return finish(runId, now, startNanos, RunReport.statusOf(ordered), Optional.empty(), ordered, saved, List.of(), listener);
    }

    private Optional<MonitorPlan> planMonitor(int id, Plant plant, Instant now, Map<Integer, MonitorOutcome> outcomes,
                                              SafeListener listener) {
        Optional<MonitorPlan> plan = plan(id, plant, now, outcomes);
        if (plan.isEmpty()) {
            listener.monitorFinished(outcomes.get(id));
        }
        return plan;
    }

    private Optional<MonitorPlan> plan(int id, Plant plant, Instant now, Map<Integer, MonitorOutcome> outcomes) {
        Optional<MonitorPlacement> placement = plant.locate(id);
        if (placement.isEmpty()) {
            outcomes.put(id, MonitorOutcome.failed(id, Optional.empty(), Optional.empty(),
                "monitor " + id + " not found in plant " + plant.id()));
            return Optional.empty();
        }
        Optional<Instant> watermark;
        try {
            watermark = repository.watermark(id);
        } catch (RuntimeException e) {
            outcomes.put(id, MonitorOutcome.failed(id, Optional.empty(), Optional.empty(), "reading watermark failed: " + message(e)));
            return Optional.empty();
        }
        Optional<TimeWindow> window = planner.plan(watermark, now);
        if (window.isEmpty()) {
            outcomes.put(id, new MonitorOutcome(id, Outcome.UP_TO_DATE, Optional.empty(), watermark,
                0, 0, 0, 0, 0, Optional.empty()));
            return Optional.empty();
        }
        return Optional.of(new MonitorPlan(placement.get(), window.get(), watermark, window.get().split(config.chunk())));
    }

    private CompletableFuture<List<ChunkResult>> fetch(MonitorPlan plan, ExecutorService executor, Semaphore permits,
                                                       SafeListener listener) {
        int id = plan.monitorId();
        List<CompletableFuture<ChunkResult>> chunks = plan.chunks().stream().map(chunk -> {
            CompletableFuture<ApiPage<BasicFeature>> basic = CompletableFuture.supplyAsync(
                () -> limited(permits, () -> api.fetchBasicFeatures(id, chunk)), executor);
            CompletableFuture<ApiPage<ComputedFeatureSample>> computed = CompletableFuture.supplyAsync(
                () -> limited(permits, () -> api.fetchComputedFeatures(id, chunk)), executor);
            return basic.thenCombine(computed, (b, c) -> new ChunkResult(chunk, b, c, null))
                .exceptionally(error -> new ChunkResult(chunk, null, null, unwrap(error)))
                .whenComplete((result, error) -> listener.chunkFinished(id, chunk, result != null && result.ok()));
        }).toList();
        return CompletableFuture.allOf(chunks.toArray(new CompletableFuture<?>[0]))
            .thenApply(done -> chunks.stream().map(CompletableFuture::join).toList());
    }

    private static <T> T limited(Semaphore permits, Supplier<T> call) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting for a request slot");
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }

    private MonitorOutcome persist(MonitorFetch f, Instant now, List<MonitorBatch> saved) {
        MonitorPlan plan = f.plan();
        int id = plan.monitorId();
        Optional<TimeWindow> planned = Optional.of(plan.window());
        if (f.error() != null) {
            return MonitorOutcome.failed(id, planned, plan.watermark(), message(unwrap(f.error())));
        }
        List<ChunkResult> chunks = f.chunks();
        int good = 0;
        while (good < chunks.size() && chunks.get(good).ok()) {
            good++;
        }
        if (good == 0) {
            return MonitorOutcome.failed(id, planned, plan.watermark(), message(chunks.get(0).error()));
        }

        List<BasicFeature> basic = new ArrayList<>();
        List<ComputedFeatureSample> computed = new ArrayList<>();
        int rejected = 0;
        int duplicates = 0;
        for (ChunkResult c : chunks.subList(0, good)) {
            basic.addAll(c.basic().items());
            computed.addAll(c.computed().items());
            rejected += c.basic().rejected() + c.computed().rejected();
            duplicates += c.basic().duplicates() + c.computed().duplicates();
        }
        TimeWindow covered = new TimeWindow(chunks.get(0).window().start(), chunks.get(good - 1).window().end());

        SaveResult result;
        try {
            MonitorBatch batch = new MonitorBatch(plan.placement(), now, covered, basic, computed);
            result = repository.save(batch);
            if (snapshots.isPresent()) {
                saved.add(batch);
            }
        } catch (RuntimeException e) {
            return MonitorOutcome.failed(id, planned, plan.watermark(), "saving failed: " + message(e));
        }

        Instant until = plan.watermark().filter(w -> w.isAfter(covered.end())).orElse(covered.end());
        boolean complete = good == chunks.size();
        Optional<String> error = complete ? Optional.empty() : Optional.of(
            "chunk " + chunks.get(good).window() + " failed: " + message(chunks.get(good).error())
                + "; saved up to " + covered.end());
        return new MonitorOutcome(id, complete ? Outcome.COLLECTED : Outcome.PARTIAL, planned, Optional.of(until),
            result.basicWritten(), result.computedWritten(), result.newRows(), rejected, duplicates, error);
    }

    private RunReport finish(String runId, Instant now, long startNanos, Status status, Optional<String> failure,
                             List<MonitorOutcome> outcomes, List<MonitorBatch> saved, List<String> warnings,
                             SafeListener listener) {
        List<String> allWarnings = new ArrayList<>(warnings);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        RunReport report = new RunReport(runId, now, elapsed, status, failure, outcomes, allWarnings);
        if (snapshots.isPresent() && !saved.isEmpty()) {
            try {
                Path file = snapshots.get().write(report, saved);
                LOG.log(System.Logger.Level.INFO, () -> "Run " + runId + ": snapshot written to " + file);
            } catch (IOException | RuntimeException e) {
                allWarnings.add("snapshot could not be written: " + e.getClass().getSimpleName() + ": " + message(e));
                report = new RunReport(runId, now, elapsed, status, failure, outcomes, allWarnings);
            }
        }
        LOG.log(status == Status.SUCCESS ? System.Logger.Level.INFO : System.Logger.Level.WARNING, report.summary());
        listener.runFinished(report);
        return report;
    }

    /** Shields the run from listener failures. */
    private static final class SafeListener implements RunListener {
        private final RunListener delegate;

        SafeListener(RunListener delegate) {
            this.delegate = delegate;
        }

        private void guard(String event, Runnable call) {
            try {
                call.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "RunListener." + event + " failed; ignored", e);
            }
        }

        @Override
        public void runStarted(String runId, Instant startedAt, List<Integer> monitorIds) {
            guard("runStarted", () -> delegate.runStarted(runId, startedAt, monitorIds));
        }

        @Override
        public void monitorPlanned(int monitorId, TimeWindow window, int chunks) {
            guard("monitorPlanned", () -> delegate.monitorPlanned(monitorId, window, chunks));
        }

        @Override
        public void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) {
            guard("chunkFinished", () -> delegate.chunkFinished(monitorId, chunk, ok));
        }

        @Override
        public void monitorFinished(MonitorOutcome outcome) {
            guard("monitorFinished", () -> delegate.monitorFinished(outcome));
        }

        @Override
        public void runFinished(RunReport report) {
            guard("runFinished", () -> delegate.runFinished(report));
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String message(Throwable t) {
        Throwable root = unwrap(t);
        String m = root.getMessage();
        if (root.getCause() != null && root.getCause() != root && root.getCause().getMessage() != null
                && (m == null || !m.contains(root.getCause().getMessage()))) {
            m = (m == null ? "" : m + ": ") + root.getCause().getMessage();
        }
        return m == null ? root.getClass().getSimpleName() : m;
    }
}
