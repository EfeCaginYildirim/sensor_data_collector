package io.github.efecaginyildirim.collector.dataset;

import io.github.efecaginyildirim.collector.dataset.ChangeFilter.Mode;
import io.github.efecaginyildirim.collector.dataset.DatasetPipeline.Stats;
import io.github.efecaginyildirim.collector.dataset.ExcludeRule.Comparison;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.DatasetQuery;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader;
import io.github.efecaginyildirim.collector.persistence.SampleExclusion;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.github.efecaginyildirim.collector.model.ComputedFeature.IDLE;
import static io.github.efecaginyildirim.collector.model.ComputedFeature.RPM;
import static io.github.efecaginyildirim.collector.model.ComputedFeature.TA;
import static org.junit.jupiter.api.Assertions.*;

class DatasetPipelineTest {

    static final Instant T0 = Instant.parse("2025-03-01T00:00:00Z");

    /** A reader over fixed rows with columns RPM, TA, IDLE; it honours the query's columns, window and monitors. */
    static final class FixedReader implements MotorDataReader {
        final List<DatasetRow> rows = new ArrayList<>();
        DatasetQuery lastQuery;

        void add(int monitor, Duration offset, Double rpm, Double ta, Double idle) {
            rows.add(new DatasetRow(monitor, T0.plus(offset), Arrays.asList(rpm, ta, idle)));
        }

        @Override
        public long query(DatasetQuery q, Consumer<DatasetRow> sink) {
            lastQuery = q;
            List<ComputedFeature> source = List.of(RPM, TA, IDLE);
            long n = 0;
            for (DatasetRow r : rows) {
                if (q.monitorIds().contains(r.monitorId()) && q.window().contains(r.time())) {
                    List<Double> v = new ArrayList<>();
                    q.features().forEach(f -> v.add(r.values().get(source.indexOf(f))));
                    sink.accept(new DatasetRow(r.monitorId(), r.time(), v));
                    n++;
                }
            }
            return n;
        }

        @Override public List<MonitorSummary> monitors() { return List.of(); }
        @Override public List<StatusSnapshot> statusHistory(int monitorId, int limit) { return List.of(); }
        @Override public List<BasicFeature> basicFeatures(int monitorId, TimeWindow window) { return List.of(); }
    }

    static DatasetSpec spec(List<ComputedFeature> features) {
        return new DatasetSpec(List.of(1, 2), Optional.empty(), Optional.empty(), features, List.of(), false,
            Sampling.RAW, Optional.empty(), true);
    }

    static DatasetSpec with(DatasetSpec s, List<ExcludeRule> rules, boolean dropIncomplete, Sampling sampling,
                            Optional<ChangeFilter> change, boolean manual) {
        return new DatasetSpec(s.monitorIds(), s.from(), s.to(), s.features(), rules, dropIncomplete, sampling, change, manual);
    }

    List<DatasetRow> out = new ArrayList<>();

    Stats run(FixedReader reader, DatasetSpec spec, List<SampleExclusion> exclusions) {
        out = new ArrayList<>();
        Stats stats = DatasetPipeline.run(reader, spec, exclusions, out::add);
        assertEquals(stats.afterSampling() - stats.droppedIncomplete() - stats.droppedUnchanged(), stats.written(),
            "every row after sampling is either dropped once or written: " + stats);
        assertEquals(stats.written(), out.size());
        return stats;
    }

    @Test
    void rulesMayUseColumnsThatAreNotExported() {
        FixedReader r = new FixedReader();
        r.add(1, Duration.ZERO, 1480.0, 40.0, 0.1);
        r.add(1, Duration.ofHours(6), 0.0, 35.0, 0.9);    // idle: excluded
        r.add(1, Duration.ofHours(12), 1479.0, null, null); // unknown IDLE: rule does not match
        DatasetSpec s = with(spec(List.of(RPM, TA)), List.of(new ExcludeRule(IDLE, Comparison.GT, 0.5)), false,
            Sampling.RAW, Optional.empty(), true);

        Stats stats = run(r, s, List.of());

        assertEquals(List.of(RPM, TA, IDLE), r.lastQuery.features(), "IDLE is read for the rule");
        assertEquals(2, out.size());
        assertEquals(2, out.get(0).values().size(), "only exported columns are written");
        assertEquals(1, stats.excludedByRules());
        assertEquals(3, stats.read());
    }

    @Test
    void manualExclusionsAreHalfOpenPerMonitorAndOptional() {
        FixedReader r = new FixedReader();
        for (int h = 0; h < 24; h += 6) {
            r.add(1, Duration.ofHours(h), 1.0, 1.0, 0.0);
            r.add(2, Duration.ofHours(h), 1.0, 1.0, 0.0);
        }
        SampleExclusion maintenance = new SampleExclusion(7, 1,
            new TimeWindow(T0.plus(Duration.ofHours(6)), T0.plus(Duration.ofHours(18))), "maintenance", T0);

        Stats stats = run(r, spec(List.of(RPM)), List.of(maintenance));
        assertEquals(2, stats.excludedManually(), "06:00 and 12:00 of monitor 1; 18:00 is outside the half-open range");
        assertEquals(6, out.size());

        Stats ignored = run(r, with(spec(List.of(RPM)), List.of(), false, Sampling.RAW, Optional.empty(), false), List.of(maintenance));
        assertEquals(0, ignored.excludedManually());
        assertEquals(8, out.size());
    }

    @Test
    void samplingAveragesPerMonitorAndBucketIgnoringMissingValues() {
        FixedReader r = new FixedReader();
        r.add(1, Duration.ofHours(1), 10.0, null, 0.0);
        r.add(1, Duration.ofHours(7), 20.0, 30.0, 0.0);
        r.add(1, Duration.ofHours(25), 40.0, null, 0.0);
        r.add(2, Duration.ofHours(2), 100.0, 1.0, 0.0);

        Stats stats = run(r, with(spec(List.of(RPM, TA)), List.of(), false, Sampling.DAILY, Optional.empty(), true), List.of());

        assertEquals(3, stats.afterSampling());
        assertEquals(new DatasetRow(1, T0, Arrays.asList(15.0, 30.0)), out.get(0));
        assertEquals(new DatasetRow(1, T0.plus(Duration.ofDays(1)), Arrays.asList(40.0, null)), out.get(1), "all-missing stays missing");
        assertEquals(new DatasetRow(2, T0, Arrays.asList(100.0, 1.0)), out.get(2), "buckets never mix monitors");

        run(r, with(spec(List.of(RPM)), List.of(), false, Sampling.HOURLY, Optional.empty(), true), List.of());
        assertEquals(T0.plus(Duration.ofHours(1)), out.get(0).time());
    }

    @Test
    void incompleteRowsCanBeDropped() {
        FixedReader r = new FixedReader();
        r.add(1, Duration.ZERO, 1.0, null, 0.0);
        r.add(1, Duration.ofHours(6), 1.0, 2.0, 0.0);
        Stats stats = run(r, with(spec(List.of(RPM, TA)), List.of(), true, Sampling.RAW, Optional.empty(), true), List.of());
        assertEquals(1, stats.droppedIncomplete());
        assertEquals(1, out.size());
    }

    @Test
    void changeFilterKeepsChangesAndTheFirstAndLastRowOfEachMonitor() {
        FixedReader r = new FixedReader();
        double[] rpm = {1480, 1480, 1480, 1481, 1490, 1490, 1490};
        for (int i = 0; i < rpm.length; i++) {
            r.add(1, Duration.ofHours(6L * i), rpm[i], 1.0, 0.0);
        }
        r.add(2, Duration.ZERO, 5.0, 1.0, 0.0);
        r.add(2, Duration.ofHours(6), 5.0, 1.0, 0.0);

        Stats exact = run(r, with(spec(List.of(RPM)), List.of(), false, Sampling.RAW, Optional.of(new ChangeFilter(Mode.ABSOLUTE, 0)), true), List.of());
        assertEquals(List.of(1480.0, 1481.0, 1490.0, 1490.0, 5.0, 5.0), values(out),
            "exact repeats removed; last row of each monitor kept");
        assertEquals(3, exact.droppedUnchanged());

        run(r, with(spec(List.of(RPM)), List.of(), false, Sampling.RAW, Optional.of(new ChangeFilter(Mode.ABSOLUTE, 5)), true), List.of());
        assertEquals(List.of(1480.0, 1490.0, 1490.0, 5.0, 5.0), values(out), "1481 is within the 5 rpm deadband");

        run(r, with(spec(List.of(RPM)), List.of(), false, Sampling.RAW, Optional.of(new ChangeFilter(Mode.PERCENT, 1)), true), List.of());
        assertEquals(List.of(1480.0, 1490.0, 5.0, 5.0), values(out),
            "a 10 rpm step is below 1 % of 1480 rpm, so only the first and last rows remain");
    }

    @Test
    void changeFilterTreatsAppearingAndDisappearingValuesAsChanges() {
        ChangeFilter f = new ChangeFilter(Mode.PERCENT, 50);
        assertTrue(f.changed(Arrays.asList(1.0, null), Arrays.asList(1.0, 2.0)));
        assertTrue(f.changed(Arrays.asList(1.0, 2.0), Arrays.asList(1.0, null)));
        assertFalse(f.changed(Arrays.asList(null, 2.0), Arrays.asList(null, 2.9)));
        assertTrue(f.changed(Arrays.asList(0.0), Arrays.asList(0.001)), "any change away from zero counts in percent mode");
        assertThrows(IllegalArgumentException.class, () -> new ChangeFilter(Mode.ABSOLUTE, -1));
        assertThrows(IllegalArgumentException.class, () -> new ChangeFilter(Mode.PERCENT, 5000));
    }

    @Test
    void dateRangeIsInclusiveOfWholeUtcDays() {
        DatasetSpec s = new DatasetSpec(List.of(1), Optional.of(LocalDate.of(2025, 3, 1)), Optional.of(LocalDate.of(2025, 3, 2)),
            List.of(RPM), List.of(), false, Sampling.RAW, Optional.empty(), true);
        assertEquals(new TimeWindow(T0, T0.plus(Duration.ofDays(2))), s.window());
        assertEquals(Instant.EPOCH, spec(List.of(RPM)).window().start());
    }

    @Test
    void specRejectsInconsistentInput() {
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec(List.of(), Optional.empty(), Optional.empty(),
            List.of(RPM), List.of(), false, Sampling.RAW, Optional.empty(), true));
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec(List.of(1), Optional.empty(), Optional.empty(),
            List.of(), List.of(), false, Sampling.RAW, Optional.empty(), true));
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec(List.of(1), Optional.of(LocalDate.of(2025, 3, 2)),
            Optional.of(LocalDate.of(2025, 3, 1)), List.of(RPM), List.of(), false, Sampling.RAW, Optional.empty(), true));
        assertThrows(IllegalArgumentException.class, () -> new ExcludeRule(RPM, Comparison.GT, Double.NaN));
        assertEquals(Comparison.LE, Comparison.fromSymbol("<="));
        assertThrows(IllegalArgumentException.class, () -> Comparison.fromSymbol("=="));
    }

    @Test
    void csvUsesPlainNumbersUtcTimesAndTheChosenSeparators() {
        List<DatasetRow> rows = List.of(
            new DatasetRow(1001, T0.plusSeconds(90), Arrays.asList(1479.5, 0.00012, null)),
            new DatasetRow(1002, T0, Arrays.asList(-3.0, 12345678.9, 0.0)));

        StringWriter standard = new StringWriter();
        CsvWriter w = new CsvWriter(standard, CsvWriter.Format.STANDARD, List.of(RPM, TA, IDLE));
        rows.forEach(w::write);
        assertEquals("time_utc,monitor_id,RPM,TA,IDLE\r\n"
            + "2025-03-01 00:01:30,1001,1479.5,0.00012,\r\n"
            + "2025-03-01 00:00:00,1002,-3.0,12345678.9,0.0\r\n", standard.toString());

        StringWriter excel = new StringWriter();
        CsvWriter e = new CsvWriter(excel, CsvWriter.Format.EXCEL_EUROPE, List.of(RPM));
        e.write(new DatasetRow(1001, T0, List.of(1479.5)));
        assertEquals("time_utc;monitor_id;RPM\r\n2025-03-01 00:00:00;1001;1479,5\r\n", excel.toString());

        assertThrows(IllegalArgumentException.class, () -> new CsvWriter.Format(',', ','));
        assertThrows(IllegalArgumentException.class, () -> new CsvWriter.Format('|', '.'));
        assertDoesNotThrow(() -> new CsvWriter.Format('\t', ','));
    }

    static List<Double> values(List<DatasetRow> rows) {
        return rows.stream().map(r -> r.values().get(0)).toList();
    }
}
