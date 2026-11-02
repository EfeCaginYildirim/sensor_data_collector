package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What one collection run did, per monitor and overall.
 *
 * @param runId     random id, also used in log lines and snapshot file names
 * @param startedAt the run's reference "now"
 * @param elapsed   wall-clock duration
 * @param status    overall result
 * @param failure   reason the whole run failed (e.g. plant hierarchy unavailable), if it did
 * @param monitors  one outcome per configured monitor, in configuration order
 * @param warnings  non-fatal problems such as a snapshot that could not be written
 */
public record RunReport(String runId, Instant startedAt, Duration elapsed, Status status,
                        Optional<String> failure, List<MonitorOutcome> monitors, List<String> warnings) {

    public RunReport {
        monitors = List.copyOf(monitors);
        warnings = List.copyOf(warnings);
    }

    /** Overall result of a run. */
    public enum Status {
        /** Every monitor was collected or already up to date. */
        SUCCESS,
        /** Some monitors failed or were only partly collected; the rest were saved. */
        PARTIAL,
        /** Nothing could be collected. */
        FAILED;

        /** @return process exit code: 0 success, 2 partial, 1 failed */
        public int exitCode() {
            return switch (this) {
                case SUCCESS -> 0;
                case PARTIAL -> 2;
                case FAILED -> 1;
            };
        }
    }

    /** Result for one monitor. */
    public enum Outcome {
        /** The whole planned window was collected and saved. */
        COLLECTED,
        /** A gap-free prefix of the window was saved; the rest failed and will be retried next run. */
        PARTIAL,
        /** The watermark is already at "now"; nothing to do. */
        UP_TO_DATE,
        /** Nothing was saved for this monitor. */
        FAILED
    }

    /**
     * Result for one monitor.
     *
     * @param monitorId      vendor monitor id
     * @param outcome        what happened
     * @param planned        the window that was planned, if any
     * @param collectedUntil the watermark after this run, if any data exists
     * @param basicSaved     basic-feature rows written in this run
     * @param computedSaved  computed-feature rows written in this run
     * @param newRows        rows that did not exist before this run
     * @param rejected       samples dropped because of unparseable timestamps
     * @param duplicates     samples dropped because of repeated timestamps
     * @param error          why the monitor failed or was partial
     */
    public record MonitorOutcome(int monitorId, Outcome outcome, Optional<TimeWindow> planned,
                                 Optional<Instant> collectedUntil, int basicSaved, int computedSaved,
                                 int newRows, int rejected, int duplicates, Optional<String> error) {

        static MonitorOutcome failed(int monitorId, Optional<TimeWindow> planned, Optional<Instant> watermark, String error) {
            return new MonitorOutcome(monitorId, Outcome.FAILED, planned, watermark, 0, 0, 0, 0, 0, Optional.of(error));
        }
    }

    /**
     * Derives the overall status from per-monitor outcomes.
     *
     * @param monitors outcomes
     * @return {@code SUCCESS} if none failed or was partial, {@code FAILED} if all failed, otherwise {@code PARTIAL}
     */
    static Status statusOf(List<MonitorOutcome> monitors) {
        boolean anyProblem = monitors.stream().anyMatch(m -> m.outcome() == Outcome.FAILED || m.outcome() == Outcome.PARTIAL);
        boolean allFailed = !monitors.isEmpty() && monitors.stream().allMatch(m -> m.outcome() == Outcome.FAILED);
        return allFailed ? Status.FAILED : anyProblem ? Status.PARTIAL : Status.SUCCESS;
    }

    /** @return a multi-line, human-readable summary for logs and the console */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Run %s %s in %.1f s (started %s)%n", runId, status, elapsed.toMillis() / 1000.0, startedAt));
        failure.ifPresent(f -> sb.append("  failure: ").append(f).append('\n'));
        for (MonitorOutcome m : monitors) {
            sb.append(String.format("  monitor %-6d %-10s basic=%-5d computed=%-6d new=%-6d until=%s%s%n",
                m.monitorId(), m.outcome(), m.basicSaved(), m.computedSaved(), m.newRows(),
                m.collectedUntil().map(Instant::toString).orElse("-"),
                m.error().map(e -> "  error: " + e).orElse("")));
        }
        warnings.forEach(w -> sb.append("  warning: ").append(w).append('\n'));
        return sb.toString();
    }
}
