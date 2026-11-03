package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.collect.RunReport;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Outcome;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SeriesAndProgressTest {

    static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    static final List<ComputedFeature> TWO = List.of(ComputedFeature.RPM, ComputedFeature.TA);

    static DatasetRow row(int i, Double rpm, Double ta) {
        return new DatasetRow(1, T0.plusSeconds(60L * i), Arrays.asList(rpm, ta));
    }

    @Test
    void smallSeriesAreKeptAsIs() {
        List<DatasetRow> rows = List.of(row(0, 1.0, null), row(1, 2.0, 5.0));
        Series s = Series.downsample(TWO, rows, 10);
        assertEquals(List.of(T0.getEpochSecond(), T0.getEpochSecond() + 60), s.times());
        assertEquals(Arrays.asList(1.0, 2.0), s.values().get(0));
        assertEquals(Arrays.asList(null, 5.0), s.values().get(1), "gaps stay gaps");
        assertEquals(2, s.sourceRows());
    }

    @Test
    void largeSeriesAreAveragedIntoAtMostMaxPoints() {
        List<DatasetRow> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(row(i, (double) i, i % 2 == 0 ? null : 10.0));
        }
        Series s = Series.downsample(TWO, rows, 100);

        assertEquals(100, s.times().size());
        assertEquals(4.5, s.values().get(0).get(0), 1e-9, "mean of 0..9");
        assertEquals(10.0, s.values().get(1).get(0), 1e-9, "nulls are ignored, not counted as zero");
        assertEquals(T0.getEpochSecond() + 270, s.times().get(0), "mean time of the group");
        assertEquals(1000, s.sourceRows());
    }

    @Test
    void emptyInputGivesAnEmptySeries() {
        Series s = Series.downsample(TWO, List.of(), 10);
        assertTrue(s.times().isEmpty());
        assertEquals(2, s.values().size());
        assertThrows(IllegalArgumentException.class, () -> Series.downsample(TWO, List.of(), 0));
    }

    @Test
    void progressFollowsTheRunLifecycle() {
        ProgressTracker t = new ProgressTracker();
        assertFalse(t.snapshot().running());

        t.runStarted("r1", T0, List.of(1, 2));
        TimeWindow w = new TimeWindow(T0, T0.plus(Duration.ofDays(60)));
        t.monitorPlanned(1, w, 2);
        t.chunkFinished(1, w, true);
        t.chunkFinished(1, w, false);
        ProgressTracker.Progress mid = t.snapshot();
        assertTrue(mid.running());
        assertEquals(2, mid.chunksTotal());
        assertEquals(2, mid.chunksDone());
        assertEquals("fetching", mid.monitors().get(0).state());
        assertEquals(1, mid.monitors().get(0).chunksFailed());
        assertEquals("waiting", mid.monitors().get(1).state());

        t.monitorFinished(new MonitorOutcome(1, Outcome.PARTIAL, Optional.of(w), Optional.empty(), 0, 0, 0, 0, 0, Optional.of("x")));
        t.monitorFinished(MonitorOutcomeFixtures.collected(2));
        t.runFinished(new RunReport("r1", T0, Duration.ofSeconds(1), RunReport.Status.PARTIAL, Optional.empty(), List.of(), List.of()));

        ProgressTracker.Progress done = t.snapshot();
        assertFalse(done.running());
        assertEquals(Optional.of("PARTIAL"), done.lastStatus());
        assertEquals(Optional.of("x"), done.monitors().get(0).error());
        assertEquals(Optional.of("COLLECTED"), done.monitors().get(1).outcome());

        t.runStarted("r2", T0, List.of(3));
        assertEquals(List.of(3), t.snapshot().monitors().stream().map(ProgressTracker.MonitorProgress::monitorId).toList(),
            "a new run starts from a clean slate");
    }

    /** Small factory to keep the test readable. */
    static final class MonitorOutcomeFixtures {
        static MonitorOutcome collected(int id) {
            return new MonitorOutcome(id, Outcome.COLLECTED, Optional.empty(), Optional.empty(), 1, 1, 2, 0, 0, Optional.empty());
        }
    }
}
