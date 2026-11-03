package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Read access to collected data, for the user interface and for exports.
 *
 * <p>Implementations are safe to use while a collection run is writing.
 */
public interface MotorDataReader {

    /**
     * Lists every monitor that has been saved at least once.
     *
     * @return summaries ordered by monitor id
     * @throws PersistenceException on storage errors
     */
    List<MonitorSummary> monitors();

    /**
     * Returns the most recent status snapshots of a monitor.
     *
     * @param monitorId monitor id
     * @param limit     maximum number of snapshots
     * @return snapshots, newest first
     * @throws PersistenceException on storage errors
     */
    List<StatusSnapshot> statusHistory(int monitorId, int limit);

    /**
     * Returns the daily aggregates of a monitor.
     *
     * @param monitorId monitor id
     * @param window    time window
     * @return aggregates in time order
     * @throws PersistenceException on storage errors
     */
    List<BasicFeature> basicFeatures(int monitorId, TimeWindow window);

    /**
     * Streams the rows selected by a query to {@code sink}, ordered by monitor and time. Rows are
     * read incrementally, so arbitrarily large selections can be exported without loading them
     * into memory.
     *
     * @param query the selection
     * @param sink  receives each row, on the calling thread
     * @return number of rows delivered
     * @throws PersistenceException on storage errors
     */
    long query(DatasetQuery query, Consumer<DatasetRow> sink);

    /**
     * A monitor with its position in the hierarchy and the state of its data.
     *
     * @param monitorId        monitor id
     * @param plantId          plant id
     * @param areaName         area name
     * @param machineGroupName machine-group name
     * @param machineName      machine name
     * @param monitorName      monitor name
     * @param collectedUntil   watermark, if any
     * @param latestStatus     most recent status snapshot, if any
     * @param basicSamples     stored daily aggregates
     * @param computedSamples  stored computed-feature samples
     */
    record MonitorSummary(int monitorId, int plantId, String areaName, String machineGroupName, String machineName,
                          String monitorName, Optional<Instant> collectedUntil, Optional<StatusSnapshot> latestStatus,
                          long basicSamples, long computedSamples) { }

    /**
     * A monitor's status at one collection time.
     *
     * @param collectedAt when the snapshot was taken
     * @param status      the snapshot
     */
    record StatusSnapshot(Instant collectedAt, MonitorStatus status) { }
}
