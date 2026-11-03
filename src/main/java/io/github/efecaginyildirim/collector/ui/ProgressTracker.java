package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.collect.RunListener;
import io.github.efecaginyildirim.collector.collect.RunReport;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns {@link RunListener} events into a snapshot the user interface can poll.
 *
 * <p>Thread-safe: events arrive from the run thread and from fetch workers, snapshots are read by
 * HTTP handler threads.
 */
public final class ProgressTracker implements RunListener {

    /**
     * Progress of one monitor.
     *
     * @param monitorId    monitor id
     * @param state        {@code waiting}, {@code fetching} or {@code done}
     * @param chunksTotal  planned chunks (0 until planned)
     * @param chunksDone   finished chunks, successful or not
     * @param chunksFailed failed chunks
     * @param outcome      final outcome once done
     * @param error        error message of a failed or partial monitor
     */
    public record MonitorProgress(int monitorId, String state, int chunksTotal, int chunksDone, int chunksFailed,
                                  Optional<String> outcome, Optional<String> error) { }

    /**
     * A consistent snapshot.
     *
     * @param running     whether a run is in progress
     * @param runId       id of the current or last run
     * @param startedAt   start of the current or last run
     * @param monitors    per-monitor progress of the current or last run
     * @param lastStatus  status of the last finished run
     * @param lastSummary summary of the last finished run
     */
    public record Progress(boolean running, Optional<String> runId, Optional<Instant> startedAt,
                           List<MonitorProgress> monitors, Optional<String> lastStatus, Optional<String> lastSummary) {

        /** @return chunks planned so far over all monitors */
        public int chunksTotal() {
            return monitors.stream().mapToInt(MonitorProgress::chunksTotal).sum();
        }

        /** @return chunks finished so far over all monitors */
        public int chunksDone() {
            return monitors.stream().mapToInt(MonitorProgress::chunksDone).sum();
        }
    }

    private static final class MonitorState {
        String state = "waiting";
        int total;
        int done;
        int failed;
        String outcome;
        String error;
    }

    private boolean running;
    private String runId;
    private Instant startedAt;
    private final Map<Integer, MonitorState> monitors = new LinkedHashMap<>();
    private String lastStatus;
    private String lastSummary;

    @Override
    public synchronized void runStarted(String id, Instant start, List<Integer> monitorIds) {
        running = true;
        runId = id;
        startedAt = start;
        monitors.clear();
        monitorIds.forEach(m -> monitors.put(m, new MonitorState()));
    }

    @Override
    public synchronized void monitorPlanned(int monitorId, TimeWindow window, int chunks) {
        MonitorState s = monitors.computeIfAbsent(monitorId, k -> new MonitorState());
        s.state = "fetching";
        s.total = chunks;
    }

    @Override
    public synchronized void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) {
        MonitorState s = monitors.computeIfAbsent(monitorId, k -> new MonitorState());
        s.done++;
        if (!ok) {
            s.failed++;
        }
    }

    @Override
    public synchronized void monitorFinished(MonitorOutcome outcome) {
        MonitorState s = monitors.computeIfAbsent(outcome.monitorId(), k -> new MonitorState());
        s.state = "done";
        s.outcome = outcome.outcome().name();
        s.error = outcome.error().orElse(null);
    }

    @Override
    public synchronized void runFinished(RunReport report) {
        running = false;
        lastStatus = report.status().name();
        lastSummary = report.summary();
    }

    /**
     * Records that a run could not start or ended abnormally.
     *
     * @param status  status to show, e.g. {@code FAILED}
     * @param summary explanation
     */
    public synchronized void runAborted(String status, String summary) {
        running = false;
        lastStatus = status;
        lastSummary = summary;
    }

    /** @return the current snapshot */
    public synchronized Progress snapshot() {
        List<MonitorProgress> list = new ArrayList<>();
        monitors.forEach((id, s) -> list.add(new MonitorProgress(id, s.state, s.total, s.done, s.failed,
            Optional.ofNullable(s.outcome), Optional.ofNullable(s.error))));
        return new Progress(running, Optional.ofNullable(runId), Optional.ofNullable(startedAt), List.copyOf(list),
            Optional.ofNullable(lastStatus), Optional.ofNullable(lastSummary));
    }
}
