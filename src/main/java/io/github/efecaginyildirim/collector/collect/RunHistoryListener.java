package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Outcome;
import io.github.efecaginyildirim.collector.persistence.RunHistory;
import io.github.efecaginyildirim.collector.persistence.RunHistory.RunRecord;

import java.util.Objects;

/** Stores every finished run in a {@link RunHistory}. */
public final class RunHistoryListener implements RunListener {

    private final RunHistory history;

    /** @param history where runs are stored */
    public RunHistoryListener(RunHistory history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public void runFinished(RunReport report) {
        history.record(toRecord(report));
    }

    /**
     * Converts a report into the stored form.
     *
     * @param report the report
     * @return the record
     */
    static RunRecord toRecord(RunReport report) {
        int failed = (int) report.monitors().stream()
            .filter(m -> m.outcome() == Outcome.FAILED || m.outcome() == Outcome.PARTIAL).count();
        int newRows = report.monitors().stream().mapToInt(MonitorOutcome::newRows).sum();
        return new RunRecord(report.runId(), report.startedAt(), report.elapsed(), report.status().name(),
            report.failure(), report.monitors().size(), failed, newRows, report.summary());
    }
}
