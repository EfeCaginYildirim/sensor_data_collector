package io.github.efecaginyildirim.collector.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs a task every day at a fixed local wall-clock time.
 *
 * <p>Unlike {@code scheduleAtFixedRate(task, delay, 24h)}, the next run is recomputed from the
 * calendar after every run, so the task stays at the configured local time across daylight-saving
 * changes (a DST day is 23 or 25 hours long) and never drifts.
 *
 * <p>All runs — scheduled and {@link #runNow() manual} — execute on one thread, so they can never
 * overlap. A run that throws anything, including an {@link Error}, is logged and the schedule
 * continues; with {@code scheduleAtFixedRate} a single exception silently cancels all future runs.
 */
public final class DailyScheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DailyScheduler.class.getName());

    private final LocalTime at;
    private final ZoneId zone;
    private final Clock clock;
    private final Runnable task;
    private final ScheduledExecutorService executor;
    private volatile ZonedDateTime nextRun;
    private volatile boolean closed;

    /**
     * @param at    local time of day to run
     * @param zone  zone whose wall clock {@code at} refers to
     * @param clock source of "now"
     * @param task  work to run
     */
    public DailyScheduler(LocalTime at, ZoneId zone, Clock clock, Runnable task) {
        this.at = Objects.requireNonNull(at, "at");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.task = Objects.requireNonNull(task, "task");
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "daily-scheduler"));
        stpe.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // don't keep the JVM alive until tomorrow
        this.executor = stpe;
    }

    /**
     * Next occurrence of {@code at} strictly after {@code now}.
     *
     * <p>If {@code at} falls into a DST gap (e.g. 02:30 on a spring-forward night) the run moves
     * forward by the length of the gap; in an overlap the earlier of the two instants is used.
     *
     * @param now current time in the target zone
     * @param at  local time of day
     * @return the next run time
     */
    static ZonedDateTime nextRunAfter(ZonedDateTime now, LocalTime at) {
        ZonedDateTime today = ZonedDateTime.of(now.toLocalDate(), at, now.getZone());
        return today.isAfter(now) ? today : ZonedDateTime.of(now.toLocalDate().plusDays(1), at, now.getZone());
    }

    /** Schedules the first run. Call once. */
    public synchronized void start() {
        scheduleNext();
    }

    /**
     * Queues an immediate run; it waits for a run in progress to finish.
     *
     * @return a future completing when the manual run has finished
     */
    public Future<?> runNow() {
        return executor.submit(this::runSafely);
    }

    /** @return the time of the next scheduled run, if scheduled */
    public Optional<ZonedDateTime> nextRunTime() {
        return Optional.ofNullable(nextRun);
    }

    private synchronized void scheduleNext() {
        if (closed) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        ZonedDateTime next = nextRunAfter(now, at);
        nextRun = next;
        long delayNanos = Math.max(0, Duration.between(now, next).toNanos());
        executor.schedule(() -> {
            try {
                runSafely();
            } finally {
                scheduleNext();
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
        LOG.log(System.Logger.Level.INFO, "Next run at {0}", next);
    }

    private void runSafely() {
        try {
            task.run();
        } catch (Throwable t) { // a daemon must survive anything a single run throws
            LOG.log(System.Logger.Level.ERROR, "Scheduled run failed; the schedule continues", t);
        }
    }

    /** Stops scheduling and waits up to 30 s for a run in progress. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
