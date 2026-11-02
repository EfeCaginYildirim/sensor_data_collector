package io.github.efecaginyildirim.collector.persistence;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable storage of collected monitor data.
 *
 * <p>The repository is also the source of truth for <em>how far</em> each monitor has been
 * collected (its watermark). Keeping the watermark next to the data — and updating both in one
 * transaction — removes the class of bugs where a state file claims more than the database holds.
 */
public interface MotorDataRepository {

    /**
     * Creates the schema if it does not exist yet. Safe to call on every start.
     *
     * @throws PersistenceException if the schema cannot be created
     */
    void initialize();

    /**
     * Returns the exclusive end of the data collected without gaps.
     *
     * @param monitorId vendor monitor id
     * @return the watermark, or empty if nothing has been collected for this monitor
     * @throws PersistenceException on storage errors
     */
    Optional<Instant> watermark(int monitorId);

    /**
     * Atomically stores a batch: upserts the monitor, records its status snapshot, replaces all
     * samples inside {@code batch.window()} with the batch's samples and advances the watermark to
     * {@code batch.window().end()} (it never moves backwards). On failure nothing is changed.
     *
     * @param batch data to store
     * @return what was written
     * @throws PersistenceException on storage errors
     */
    SaveResult save(MonitorBatch batch);

    /**
     * Outcome of {@link #save(MonitorBatch)}.
     *
     * @param basicWritten     basic-feature rows inserted
     * @param basicReplaced    basic-feature rows that existed in the window and were replaced
     * @param computedWritten  computed-feature rows inserted
     * @param computedReplaced computed-feature rows that existed in the window and were replaced
     */
    record SaveResult(int basicWritten, int basicReplaced, int computedWritten, int computedReplaced) {
        /** @return rows that did not exist before this save, across both sample kinds */
        public int newRows() {
            return Math.max(0, basicWritten - basicReplaced) + Math.max(0, computedWritten - computedReplaced);
        }
    }
}
