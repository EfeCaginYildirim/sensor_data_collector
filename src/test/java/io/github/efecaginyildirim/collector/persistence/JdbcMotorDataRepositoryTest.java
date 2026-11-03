package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository.SaveResult;
import io.github.efecaginyildirim.collector.persistence.RecordingJdbc.Execution;
import io.github.efecaginyildirim.collector.persistence.RecordingJdbc.SqlNull;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.github.efecaginyildirim.collector.persistence.Batches.T0;
import static io.github.efecaginyildirim.collector.persistence.Batches.batch;
import static org.junit.jupiter.api.Assertions.*;

class JdbcMotorDataRepositoryTest {

    final RecordingJdbc jdbc = new RecordingJdbc();

    JdbcMotorDataRepository repository(int batchSize) {
        return new JdbcMotorDataRepository(jdbc.factory(), SqlDialect.SQL_SERVER, batchSize);
    }

    @Test
    void saveRunsAllStatementsInOneTransactionInTheRightOrder() {
        repository(1000).save(batch(101, T0, 2));

        assertEquals(List.of(
            "autoCommit=false",
            "prepare MERGE dbo.monitor WITH", "executeUpdate MERGE dbo.monitor WITH",
            "prepare DELETE FROM dbo.monitor_status", "executeUpdate DELETE FROM dbo.monitor_status",
            "prepare INSERT INTO dbo.monitor_status", "executeUpdate INSERT INTO dbo.monitor_status",
            "prepare DELETE FROM dbo.basic_feature", "executeUpdate DELETE FROM dbo.basic_feature",
            "prepare INSERT INTO dbo.basic_feature", "executeBatch 2",
            "prepare DELETE FROM dbo.computed_feature", "executeUpdate DELETE FROM dbo.computed_feature",
            "prepare INSERT INTO dbo.computed_feature", "executeBatch 8",
            "prepare MERGE dbo.collection_watermark WITH", "executeUpdate MERGE dbo.collection_watermark WITH",
            "commit", "close"), jdbc.log);
        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void everyPlaceholderIsBoundExactlyOnce() {
        repository(1000).save(batch(101, T0, 3));

        for (Execution e : jdbc.executions) {
            List<Integer> expected = IntStream.rangeClosed(1, (int) e.placeholders()).boxed().toList();
            assertEquals(expected, List.copyOf(e.params().keySet()), () -> "unbound or extra parameters in: " + e.sql());
        }
    }

    @Test
    void timesAreBoundAsUtcLocalDateTimesAndWindowIsHalfOpen() {
        repository(1000).save(batch(101, T0, 2));

        Execution delete = jdbc.executions("update").stream()
            .filter(e -> e.sql().startsWith("DELETE FROM dbo.basic_feature")).findFirst().orElseThrow();
        assertTrue(delete.sql().contains("sample_time >= ? AND sample_time < ?"));
        assertEquals(LocalDateTime.parse("2025-05-01T00:00:00"), delete.params().get(2));
        assertEquals(LocalDateTime.parse("2025-05-03T00:00:00"), delete.params().get(3));

        Execution watermark = jdbc.executions("update").stream()
            .filter(e -> e.sql().contains("collection_watermark")).findFirst().orElseThrow();
        assertEquals(LocalDateTime.parse("2025-05-03T00:00:00"), watermark.params().get(2));
        assertTrue(watermark.sql().contains("CASE WHEN s.collected_until > t.collected_until"), "watermark never moves backwards");
    }

    @Test
    void unknownValuesAreBoundAsTypedNullsNotZeros() {
        repository(1000).save(batch(101, T0, 1));

        Execution computed = jdbc.executions("batchRow").stream()
            .filter(e -> e.sql().startsWith("INSERT INTO dbo.computed_feature")).findFirst().orElseThrow();
        int rpmIndex = 3 + ComputedFeature.RPM.ordinal();
        int taIndex = 3 + ComputedFeature.TA.ordinal();
        assertEquals(1480.0, computed.params().get(rpmIndex));
        assertEquals(new SqlNull(Types.DOUBLE), computed.params().get(taIndex));

        Execution status = jdbc.executions("update").stream()
            .filter(e -> e.sql().startsWith("INSERT INTO dbo.monitor_status")).findFirst().orElseThrow();
        assertEquals(87, status.params().get(3), "health_score");
        assertEquals(new SqlNull(Types.INTEGER), status.params().get(6), "alarms unknown");
        assertEquals(new SqlNull(Types.DOUBLE), status.params().get(12), "velocity_y unknown");
    }

    @Test
    void largeBatchesAreFlushedInChunks() {
        repository(10).save(batch(101, T0, 6)); // 6 basic rows, 24 computed rows

        assertTrue(jdbc.log.contains("executeBatch 6"));
        assertEquals(List.of("executeBatch 10", "executeBatch 10", "executeBatch 4"),
            jdbc.log.stream().filter(l -> l.startsWith("executeBatch") && !l.equals("executeBatch 6")).toList());
    }

    @Test
    void failureRollsBackAndClosesTheConnection() {
        jdbc.failOn = sql -> sql.startsWith("INSERT INTO dbo.computed_feature");

        PersistenceException e = assertThrows(PersistenceException.class, () -> repository(1000).save(batch(101, T0, 1)));
        assertTrue(e.getMessage().contains("monitor 101"), e.getMessage());
        assertFalse(jdbc.log.contains("commit"));
        assertEquals(List.of("rollback", "close"), jdbc.log.subList(jdbc.log.size() - 2, jdbc.log.size()));
        assertEquals(0, jdbc.openConnections);
    }

    @Test
    void reportsReplacedRowsFromDeleteCounts() {
        jdbc.updateCount = sql -> sql.startsWith("DELETE FROM dbo.basic_feature") ? 1
            : sql.startsWith("DELETE FROM dbo.computed_feature") ? 4 : 1;
        SaveResult r = repository(1000).save(batch(101, T0, 3));

        assertEquals(new SaveResult(3, 1, 12, 4), r);
        assertEquals(10, r.newRows());
    }

    @Test
    void readsWatermarkAsUtcInstant() {
        JdbcMotorDataRepository repo = repository(1000);
        assertTrue(repo.watermark(101).isEmpty());

        jdbc.queryRows = List.<Object[]>of(new Object[]{LocalDateTime.parse("2025-05-03T00:00:00")});
        assertEquals(Instant.parse("2025-05-03T00:00:00Z"), repo.watermark(101).orElseThrow());
        assertEquals(101, jdbc.executions("query").get(0).params().get(1));
    }

    @Test
    void initializeRunsEverySchemaBatchWithAutoCommit() {
        repository(1000).initialize();

        List<String> batches = JdbcMotorDataRepository.schemaBatches(SqlDialect.SQL_SERVER.schemaResource());
        assertEquals(8, batches.size(), "7 tables + 1 view");
        assertEquals(batches, jdbc.executions("execute").stream().map(Execution::sql).toList());
        assertTrue(batches.stream().noneMatch(b -> b.lines().anyMatch(l -> l.strip().equalsIgnoreCase("GO"))));
        assertTrue(batches.get(batches.size() - 1).startsWith("-- Convenience view"));
    }

    @Test
    void schemaColumnsMatchTheComputedFeatureEnum() {
        for (SqlDialect dialect : SqlDialect.values()) {
            assertComputedColumnsMatchEnum(dialect);
        }
    }

    private static void assertComputedColumnsMatchEnum(SqlDialect dialect) {
        String table = JdbcMotorDataRepository.schemaBatches(dialect.schemaResource()).stream()
            .filter(b -> b.contains("CREATE TABLE") && b.contains("dbo.computed_feature (")).findFirst().orElseThrow();
        Matcher m = Pattern.compile("^\\s+\\[?([a-z]+)]?\\s+(FLOAT NULL|DOUBLE PRECISION)", Pattern.MULTILINE).matcher(table);
        List<String> columns = new java.util.ArrayList<>();
        while (m.find()) {
            columns.add(m.group(1));
        }
        assertEquals(Arrays.stream(ComputedFeature.values()).map(ComputedFeature::column).toList(), columns, dialect.name());
        assertTrue(table.contains("[load]"), "LOAD is a reserved word in T-SQL and must be quoted");
    }

    @Test
    void generatedInsertQuotesEveryFeatureColumn() {
        for (ComputedFeature f : ComputedFeature.values()) {
            assertTrue(JdbcMotorDataRepository.INSERT_COMPUTED.contains("[" + f.column().toLowerCase(Locale.ROOT) + "]"));
        }
        assertEquals(2 + ComputedFeature.values().length,
            JdbcMotorDataRepository.INSERT_COMPUTED.chars().filter(c -> c == '?').count());
    }

    @Test
    void savingTheSameBatchTwiceIssuesIdenticalStatements() {
        // The DELETE removes what the first save wrote, so a re-run converges to the same state.
        JdbcMotorDataRepository repo = repository(1000);
        repo.save(batch(101, T0, 1));
        List<Execution> first = List.copyOf(jdbc.executions);
        jdbc.executions.clear();
        repo.save(batch(101, T0, 1));
        assertEquals(first, jdbc.executions);
    }
}
