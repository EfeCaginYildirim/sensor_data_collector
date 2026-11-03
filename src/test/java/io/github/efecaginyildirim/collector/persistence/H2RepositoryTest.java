package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.dataset.DatasetPipeline;
import io.github.efecaginyildirim.collector.dataset.DatasetSpec;
import io.github.efecaginyildirim.collector.dataset.Sampling;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.MonitorSummary;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.StatusSnapshot;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository.SaveResult;
import io.github.efecaginyildirim.collector.persistence.RunHistory.RunRecord;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static io.github.efecaginyildirim.collector.persistence.Batches.T0;
import static io.github.efecaginyildirim.collector.persistence.Batches.batch;
import static io.github.efecaginyildirim.collector.persistence.Batches.placement;
import static org.junit.jupiter.api.Assertions.*;

/** Repository semantics verified against a real (embedded H2) database. */
class H2RepositoryTest {

    static final TimeWindow ALL = new TimeWindow(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"));

    final JdbcMotorDataRepository repo = H2Database.inMemory();

    @AfterEach
    void close() {
        repo.close();
    }

    // ─── Writes and watermarks ───────────────────────────────────────────────────

    @Test
    void schemaIsIdempotent() {
        repo.initialize();
        repo.initialize();
        assertEquals(SqlDialect.H2, repo.dialect());
    }

    @Test
    void watermarkIsEmptyUntilTheFirstSaveAndThenTheWindowEnd() {
        assertTrue(repo.watermark(101).isEmpty());
        repo.save(batch(101, T0, 3));
        assertEquals(T0.plus(Duration.ofDays(3)), repo.watermark(101).orElseThrow());
        assertTrue(repo.watermark(102).isEmpty(), "watermarks are per monitor");
    }

    @Test
    void watermarkNeverMovesBackwards() {
        repo.save(batch(101, T0.plus(Duration.ofDays(5)), 2));
        repo.save(batch(101, T0, 2));
        assertEquals(T0.plus(Duration.ofDays(7)), repo.watermark(101).orElseThrow());
    }

    @Test
    void savingTheSameBatchTwiceIsIdempotent() {
        SaveResult first = repo.save(batch(101, T0, 3));
        SaveResult second = repo.save(batch(101, T0, 3));

        assertEquals(3, repo.basicFeatures(101, ALL).size());
        assertEquals(12, computed(101).size());
        assertEquals(15, first.newRows());
        assertEquals(new SaveResult(3, 3, 12, 12), second);
        assertEquals(0, second.newRows());
    }

    @Test
    void twoSavesAtTheSameInstantDoNotCollide() {
        // Found by this real-database test: two runs within one second used the same snapshot key.
        repo.save(batch(101, T0, 1));
        assertDoesNotThrow(() -> repo.save(batch(101, T0, 1)));
        assertEquals(1, repo.statusHistory(101, 10).size());
    }

    @Test
    void overlappingSaveReplacesOnlyTheOverlap() {
        repo.save(batch(101, T0, 5));
        SaveResult r = repo.save(batch(101, T0.plus(Duration.ofDays(3)), 4));

        assertEquals(7, repo.basicFeatures(101, ALL).size());
        assertEquals(new SaveResult(4, 2, 16, 8), r);
        assertEquals(T0.plus(Duration.ofDays(7)), repo.watermark(101).orElseThrow());
    }

    @Test
    void samplesThatDisappearedFromTheVendorAreRemovedInsideTheWindow() {
        repo.save(batch(101, T0, 3));
        TimeWindow window = new TimeWindow(T0, T0.plus(Duration.ofDays(3)));
        BasicFeature only = new BasicFeature(T0, "corrected", "{\"v\":1}", "{}", "{}");
        repo.save(new MonitorBatch(placement(101), window.end(), window, List.of(only), List.of()));

        assertEquals(List.of(only), repo.basicFeatures(101, ALL));
        assertTrue(computed(101).isEmpty());
    }

    @Test
    void valuesRoundTripExactlyIncludingNullsAndUnicode() {
        Map<ComputedFeature, Double> values = new EnumMap<>(ComputedFeature.class);
        values.put(ComputedFeature.RPM, 1479.123456789);
        values.put(ComputedFeature.LOAD, 0.0);
        ComputedFeatureSample sample = new ComputedFeatureSample(T0.plusMillis(250), values);
        BasicFeature basic = new BasicFeature(T0, "çağın-ü", "{\"note\":\"titreşim\"}", "{}", "{}");
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(60));

        repo.save(new MonitorBatch(placement(7), w.end(), w, List.of(basic), List.of(sample)));

        assertEquals(List.of(sample), computed(7), "millisecond time, exact double, 0.0 kept, missing stays missing");
        assertEquals(List.of(basic), repo.basicFeatures(7, ALL));
    }

    @Test
    void aFailedSaveLeavesNoTrace() throws Exception {
        repo.save(batch(101, T0, 1));
        // Make the last statement of the next transaction fail by renaming the table it writes to.
        try (Connection c = repoConnection(repo); PreparedStatement ps =
                 c.prepareStatement("ALTER TABLE dbo.collection_watermark RENAME TO collection_watermark_gone")) {
            ps.execute();
        }

        assertThrows(PersistenceException.class, () -> repo.save(batch(101, T0.plus(Duration.ofDays(1)), 1)));

        assertEquals(1, repo.basicFeatures(101, ALL).size(), "the new day's samples were rolled back");
        assertEquals(4, computed(101).size());
        assertEquals(1, repo.statusHistory(101, 10).size(), "the status snapshot was rolled back too");
    }

    // ─── Reads ───────────────────────────────────────────────────────────────────

    @Test
    void monitorSummariesShowHierarchyWatermarkLatestStatusAndCounts() {
        repo.save(batch(102, T0, 2));
        repo.save(batch(101, T0, 1));
        repo.save(batch(101, T0.plus(Duration.ofDays(1)), 1));

        List<MonitorSummary> monitors = repo.monitors();

        assertEquals(List.of(101, 102), monitors.stream().map(MonitorSummary::monitorId).toList());
        MonitorSummary m = monitors.get(0);
        assertEquals("Motor M-01", m.machineName());
        assertEquals("Hall A", m.areaName());
        assertEquals(Optional.of(T0.plus(Duration.ofDays(2))), m.collectedUntil());
        assertEquals(T0.plus(Duration.ofDays(2)), m.latestStatus().orElseThrow().collectedAt(), "latest of two snapshots");
        assertEquals(87, m.latestStatus().orElseThrow().status().healthScore());
        assertNull(m.latestStatus().orElseThrow().status().alarms(), "NULL stays null when read back");
        assertEquals(2, m.basicSamples());
        assertEquals(8, m.computedSamples());
    }

    @Test
    void emptyStoreHasNoMonitors() {
        assertTrue(repo.monitors().isEmpty());
        assertTrue(repo.recentRuns(5).isEmpty());
    }

    @Test
    void statusHistoryIsNewestFirstAndLimited() {
        for (int d = 0; d < 4; d++) {
            repo.save(batch(101, T0.plus(Duration.ofDays(d)), 1));
        }
        List<StatusSnapshot> history = repo.statusHistory(101, 3);
        assertEquals(3, history.size());
        assertEquals(T0.plus(Duration.ofDays(4)), history.get(0).collectedAt());
        assertTrue(history.get(0).collectedAt().isAfter(history.get(1).collectedAt()));
    }

    @Test
    void datasetQuerySelectsColumnsInRequestedOrderAndFiltersTheWindow() {
        repo.save(batch(101, T0, 3));
        repo.save(batch(102, T0, 3));
        TimeWindow day2 = new TimeWindow(T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(2)));
        DatasetQuery q = new DatasetQuery(List.of(102, 101), day2,
            List.of(ComputedFeature.RPM, ComputedFeature.TA, ComputedFeature.KURTACCLX), OptionalInt.empty());

        List<DatasetRow> rows = new ArrayList<>();
        long n = repo.query(q, rows::add);

        assertEquals(8, n);
        assertEquals(8, rows.size());
        assertEquals(101, rows.get(0).monitorId(), "ordered by monitor, then time");
        assertEquals(102, rows.get(7).monitorId());
        assertTrue(rows.stream().allMatch(r -> day2.contains(r.time())));
        DatasetRow first = rows.get(0);
        assertEquals(1481.0, first.value(0).orElseThrow(), "RPM of day index 1");
        assertTrue(first.value(1).isEmpty(), "TA was never reported");
        assertEquals(3.0, first.value(2).orElseThrow());
    }

    @Test
    void datasetQueryHonoursTheLimit() {
        repo.save(batch(101, T0, 10));
        List<DatasetRow> rows = new ArrayList<>();
        assertEquals(5, repo.query(DatasetQuery.allFeatures(List.of(101), ALL).withLimit(5), rows::add));
        assertEquals(T0, rows.get(0).time());
        assertEquals(29, rows.get(0).values().size());
    }

    @Test
    void datasetSqlUsesOnlyWhitelistedColumnsAndBindParameters() {
        DatasetQuery q = new DatasetQuery(List.of(1, 2, 3), ALL, List.of(ComputedFeature.LOAD, ComputedFeature.BPFO), OptionalInt.of(10));
        String sql = JdbcMotorDataRepository.datasetSql(q);
        assertTrue(sql.startsWith("SELECT monitor_id, sample_time, [load], [bpfo] FROM dbo.computed_feature"), sql);
        assertTrue(sql.contains("monitor_id IN (?, ?, ?)"));
        assertTrue(sql.endsWith("FETCH NEXT ? ROWS ONLY"));
    }

    @Test
    void datasetQueryValidatesItsInput() {
        assertThrows(IllegalArgumentException.class, () -> new DatasetQuery(List.of(), ALL, List.of(ComputedFeature.RPM), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DatasetQuery(List.of(1, 1), ALL, List.of(ComputedFeature.RPM), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DatasetQuery(List.of(1), ALL, List.of(), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new DatasetQuery(List.of(1), ALL, List.of(ComputedFeature.RPM, ComputedFeature.RPM), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DatasetQuery(List.of(1), ALL, List.of(ComputedFeature.RPM), OptionalInt.of(0)));
    }

    // ─── Run history ─────────────────────────────────────────────────────────────

    @Test
    void runHistoryIsNewestFirst() {
        repo.record(new RunRecord("a", T0, Duration.ofMillis(1200), "SUCCESS", Optional.empty(), 3, 0, 15, "summary a"));
        repo.record(new RunRecord("b", T0.plus(Duration.ofDays(1)), Duration.ofMillis(800), "PARTIAL",
            Optional.of("x".repeat(3000)), 3, 1, 5, "summary b — ç"));

        List<RunRecord> runs = repo.recentRuns(10);
        assertEquals(List.of("b", "a"), runs.stream().map(RunRecord::runId).toList());
        assertEquals(Duration.ofMillis(800), runs.get(0).elapsed());
        assertEquals(2000, runs.get(0).failure().orElseThrow().length(), "long failures are truncated");
        assertEquals("summary b — ç", runs.get(0).summary());
        assertEquals(1, repo.recentRuns(1).size());
    }

    // ─── Manual exclusions ───────────────────────────────────────────────────────

    @Test
    void exclusionsAreStoredListedAndRemoved() {
        repo.save(batch(101, T0, 3));
        repo.save(batch(102, T0, 3));
        TimeWindow day2 = new TimeWindow(T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(2)));

        SampleExclusion a = repo.addExclusion(101, day2, "  bearing replaced  ", T0);
        SampleExclusion b = repo.addExclusion(102, new TimeWindow(T0, T0.plusSeconds(3600)), "sensor unplugged — ç", T0);

        assertTrue(a.id() > 0 && b.id() > a.id());
        assertEquals("bearing replaced", a.reason(), "reasons are trimmed");
        assertEquals(List.of(a, b), repo.exclusions(List.of()));
        assertEquals(List.of(b), repo.exclusions(List.of(102)));
        assertTrue(repo.removeExclusion(a.id()));
        assertFalse(repo.removeExclusion(a.id()));
        assertEquals(List.of(b), repo.exclusions(List.of()));
    }

    @Test
    void exclusionsNeedAReasonAndAKnownMonitor() {
        repo.save(batch(101, T0, 1));
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> repo.addExclusion(101, w, "   ", T0));
        assertThrows(IllegalArgumentException.class, () -> repo.addExclusion(101, w, "x".repeat(501), T0));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> repo.addExclusion(999, w, "why", T0));
        assertTrue(unknown.getMessage().contains("999"));
    }

    @Test
    void datasetPipelineRunsAgainstTheRealDatabase() {
        repo.save(batch(101, T0, 3));
        SampleExclusion firstDay = repo.addExclusion(101, new TimeWindow(T0, T0.plus(Duration.ofDays(1))), "warm-up", T0);
        DatasetSpec spec = new DatasetSpec(List.of(101), Optional.empty(), Optional.empty(),
            List.of(ComputedFeature.RPM), List.of(), false, Sampling.DAILY, Optional.empty(), true);

        List<DatasetRow> rows = new ArrayList<>();
        DatasetPipeline.Stats stats = DatasetPipeline.run(repo, spec, repo.exclusions(List.of(101)), rows::add);

        assertEquals(12, stats.read());
        assertEquals(4, stats.excludedManually());
        assertEquals(List.of(1481.0, 1482.0), rows.stream().map(r -> r.values().get(0)).toList(), "daily mean of each remaining day");
        assertEquals(firstDay.window().end(), rows.get(0).time());
    }

    // ─── Files and URLs ──────────────────────────────────────────────────────────

    @Test
    void fileDatabaseSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("nested/collector");
        try (JdbcMotorDataRepository first = H2Database.file(file)) {
            first.save(batch(101, T0, 2));
        }
        assertTrue(Files.exists(dir.resolve("nested/collector.mv.db")));
        try (JdbcMotorDataRepository second = H2Database.file(file)) {
            assertEquals(T0.plus(Duration.ofDays(2)), second.watermark(101).orElseThrow());
            assertEquals(2, second.basicFeatures(101, ALL).size());
        }
    }

    @Test
    void configuredH2UrlsAreCompletedAndDialectsDetected() {
        assertEquals("jdbc:h2:file:./x;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            H2Database.withSettings("jdbc:h2:file:./x"));
        assertEquals("jdbc:h2:mem:a;db_close_delay=0;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE",
            H2Database.withSettings("jdbc:h2:mem:a;db_close_delay=0"), "explicit settings win");
        assertEquals(SqlDialect.SQL_SERVER, SqlDialect.fromJdbcUrl("jdbc:sqlserver://h:1433;databaseName=x"));
        assertEquals(SqlDialect.H2, SqlDialect.fromJdbcUrl("JDBC:H2:mem:x"));
        assertThrows(IllegalArgumentException.class, () -> SqlDialect.fromJdbcUrl("jdbc:postgresql://h/x"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private List<ComputedFeatureSample> computed(int monitorId) {
        List<ComputedFeatureSample> out = new ArrayList<>();
        repo.query(DatasetQuery.allFeatures(List.of(monitorId), ALL), row -> {
            Map<ComputedFeature, Double> v = new EnumMap<>(ComputedFeature.class);
            ComputedFeature[] f = ComputedFeature.values();
            for (int i = 0; i < f.length; i++) {
                v.put(f[i], row.values().get(i));
            }
            out.add(new ComputedFeatureSample(row.time(), v));
        });
        return out;
    }

    /** A connection to the repository's own database, reached through its connection factory. */
    private static Connection repoConnection(JdbcMotorDataRepository repo) throws Exception {
        java.lang.reflect.Field f = JdbcMotorDataRepository.class.getDeclaredField("connections");
        f.setAccessible(true);
        return ((JdbcMotorDataRepository.ConnectionFactory) f.get(repo)).open();
    }
}
