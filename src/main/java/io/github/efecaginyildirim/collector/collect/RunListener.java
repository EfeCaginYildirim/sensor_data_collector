package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.List;

/**
 * Receives progress events of a {@link CollectionJob} run, e.g. to drive a progress bar.
 *
 * <p>Event order for one run: {@link #runStarted}, then for each monitor {@link #monitorPlanned}
 * (unless it fails during planning or is up to date), any number of {@link #chunkFinished} and one
 * {@link #monitorFinished}; finally {@link #runFinished}. A monitor that fails before any
 * request only gets {@link #monitorFinished}.
 *
 * <p>{@link #chunkFinished} is called from worker threads, possibly concurrently; all other events
 * come from the thread that called {@link CollectionJob#run(RunListener)}. A listener that throws
 * is logged and otherwise ignored — it can never break a run.
 */
public interface RunListener {

    /** A listener that ignores every event. */
    RunListener NONE = new RunListener() { };

    /**
     * @param runId      id of the run
     * @param startedAt  the run's reference time
     * @param monitorIds configured monitors, in order
     */
    default void runStarted(String runId, Instant startedAt, List<Integer> monitorIds) { }

    /**
     * @param monitorId monitor id
     * @param window    window that will be collected
     * @param chunks    number of chunks; {@code 2 * chunks} requests will be made
     */
    default void monitorPlanned(int monitorId, TimeWindow window, int chunks) { }

    /**
     * @param monitorId monitor id
     * @param chunk     the chunk
     * @param ok        whether both requests of the chunk succeeded
     */
    default void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) { }

    /** @param outcome final outcome of one monitor */
    default void monitorFinished(MonitorOutcome outcome) { }

    /** @param report the complete report */
    default void runFinished(RunReport report) { }

    /**
     * Combines listeners; events are delivered to each in order.
     *
     * @param listeners listeners to combine
     * @return a listener forwarding to all of them
     */
    static RunListener all(RunListener... listeners) {
        List<RunListener> list = List.of(listeners);
        return new RunListener() {
            @Override
            public void runStarted(String runId, Instant startedAt, List<Integer> monitorIds) {
                list.forEach(l -> l.runStarted(runId, startedAt, monitorIds));
            }

            @Override
            public void monitorPlanned(int monitorId, TimeWindow window, int chunks) {
                list.forEach(l -> l.monitorPlanned(monitorId, window, chunks));
            }

            @Override
            public void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) {
                list.forEach(l -> l.chunkFinished(monitorId, chunk, ok));
            }

            @Override
            public void monitorFinished(MonitorOutcome outcome) {
                list.forEach(l -> l.monitorFinished(outcome));
            }

            @Override
            public void runFinished(RunReport report) {
                list.forEach(l -> l.runFinished(report));
            }
        };
    }
}
