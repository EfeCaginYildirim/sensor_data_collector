package io.github.efecaginyildirim.collector.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable log of collection runs, shown in the user interface. */
public interface RunHistory {

    /**
     * Stores one run.
     *
     * @param run the run to store
     * @throws PersistenceException on storage errors
     */
    void record(RunRecord run);

    /**
     * @param limit maximum number of runs
     * @return the most recent runs, newest first
     * @throws PersistenceException on storage errors
     */
    List<RunRecord> recentRuns(int limit);

    /**
     * One stored run.
     *
     * @param runId          run id
     * @param startedAt      the run's reference time
     * @param elapsed        duration
     * @param status         {@code SUCCESS}, {@code PARTIAL} or {@code FAILED}
     * @param failure        reason the whole run failed, if it did
     * @param monitorsTotal  configured monitors
     * @param monitorsFailed monitors with outcome {@code FAILED} or {@code PARTIAL}
     * @param newRows        rows that did not exist before the run
     * @param summary        human-readable report
     */
    record RunRecord(String runId, Instant startedAt, Duration elapsed, String status, Optional<String> failure,
                     int monitorsTotal, int monitorsFailed, int newRows, String summary) {
        public RunRecord {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(elapsed, "elapsed");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
