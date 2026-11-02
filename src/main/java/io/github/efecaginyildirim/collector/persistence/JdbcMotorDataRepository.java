package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.config.AppConfig.DatabaseConfig;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.MonitorInfo.Reading;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link MotorDataRepository} for Microsoft SQL Server over plain JDBC.
 *
 * <h2>Write strategy</h2>
 * A save runs in one transaction:
 * <ol>
 *   <li>upsert the monitor row ({@code MERGE ... WITH (HOLDLOCK)}, single row);</li>
 *   <li>insert the status snapshot;</li>
 *   <li>{@code DELETE} the samples inside the window (a primary-key range seek) and batch-insert
 *       the new ones;</li>
 *   <li>advance the watermark, never moving it backwards.</li>
 * </ol>
 * Replacing the window instead of merging row by row keeps the statements simple, index-friendly
 * and idempotent. With {@code useBulkCopyForBatchInsert=true} in the JDBC URL, the driver turns
 * the batched inserts into bulk-copy operations.
 *
 * <h2>Connections</h2>
 * The collector writes from a single thread, a handful of times per run, so it opens one
 * connection per operation instead of maintaining a pool. A pool (e.g. HikariCP) can be plugged
 * in through {@link ConnectionFactory} if the access pattern changes.
 *
 * <p>All timestamps are stored as UTC {@code DATETIME2}.
 */
public final class JdbcMotorDataRepository implements MotorDataRepository {

    /** Supplies JDBC connections; each call returns a new connection owned by the caller. */
    @FunctionalInterface
    public interface ConnectionFactory {
        /**
         * @return an open connection
         * @throws SQLException if connecting fails
         */
        Connection open() throws SQLException;
    }

    static final String SCHEMA_RESOURCE = "/db/schema.sql";

    static final String UPSERT_MONITOR = """
        MERGE dbo.monitor WITH (HOLDLOCK) AS t
        USING (SELECT ? AS monitor_id, ? AS plant_id, ? AS area_id, ? AS area_name, ? AS machine_group_id,
                      ? AS machine_group_name, ? AS machine_id, ? AS machine_name, ? AS monitor_name,
                      ? AS device_identifier, ? AS orientation_code, ? AS updated_at) AS s
           ON t.monitor_id = s.monitor_id
        WHEN MATCHED THEN UPDATE SET
             plant_id = s.plant_id, area_id = s.area_id, area_name = s.area_name,
             machine_group_id = s.machine_group_id, machine_group_name = s.machine_group_name,
             machine_id = s.machine_id, machine_name = s.machine_name, monitor_name = s.monitor_name,
             device_identifier = s.device_identifier, orientation_code = s.orientation_code,
             updated_at = s.updated_at
        WHEN NOT MATCHED THEN INSERT
             (monitor_id, plant_id, area_id, area_name, machine_group_id, machine_group_name,
              machine_id, machine_name, monitor_name, device_identifier, orientation_code, updated_at)
             VALUES (s.monitor_id, s.plant_id, s.area_id, s.area_name, s.machine_group_id, s.machine_group_name,
              s.machine_id, s.machine_name, s.monitor_name, s.device_identifier, s.orientation_code, s.updated_at);""";

    static final String INSERT_STATUS = """
        INSERT INTO dbo.monitor_status
          (monitor_id, collected_at, health_score, status, trend, alarms, speed,
           velocity_x, velocity_x_operational, velocity_x_caution, velocity_x_warning,
           velocity_y, velocity_y_operational, velocity_y_caution, velocity_y_warning,
           velocity_z, velocity_z_operational, velocity_z_caution, velocity_z_warning,
           temperature, temperature_operational, temperature_caution, temperature_warning)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    static final String DELETE_BASIC =
        "DELETE FROM dbo.basic_feature WHERE monitor_id = ? AND sample_time >= ? AND sample_time < ?";

    static final String INSERT_BASIC =
        "INSERT INTO dbo.basic_feature (monitor_id, sample_time, source_id, json_avg, json_min, json_max) VALUES (?, ?, ?, ?, ?, ?)";

    static final String DELETE_COMPUTED =
        "DELETE FROM dbo.computed_feature WHERE monitor_id = ? AND sample_time >= ? AND sample_time < ?";

    /** Generated from {@link ComputedFeature} so the column list cannot drift from the enum. */
    static final String INSERT_COMPUTED = "INSERT INTO dbo.computed_feature (monitor_id, sample_time, "
        + Arrays.stream(ComputedFeature.values()).map(f -> "[" + f.column() + "]").collect(Collectors.joining(", "))
        + ") VALUES (?, ?" + ", ?".repeat(ComputedFeature.values().length) + ")";

    static final String UPSERT_WATERMARK = """
        MERGE dbo.collection_watermark WITH (HOLDLOCK) AS t
        USING (SELECT ? AS monitor_id, ? AS collected_until, ? AS updated_at) AS s
           ON t.monitor_id = s.monitor_id
        WHEN MATCHED THEN UPDATE SET
             collected_until = CASE WHEN s.collected_until > t.collected_until THEN s.collected_until ELSE t.collected_until END,
             updated_at = s.updated_at
        WHEN NOT MATCHED THEN INSERT (monitor_id, collected_until, updated_at)
             VALUES (s.monitor_id, s.collected_until, s.updated_at);""";

    static final String SELECT_WATERMARK =
        "SELECT collected_until FROM dbo.collection_watermark WHERE monitor_id = ?";

    private final ConnectionFactory connections;
    private final int batchSize;

    /**
     * @param connections source of connections
     * @param batchSize   rows per JDBC batch; must be positive
     */
    public JdbcMotorDataRepository(ConnectionFactory connections, int batchSize) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /**
     * Creates a repository that connects through {@link DriverManager}.
     *
     * @param config database settings
     * @return the repository
     */
    public static JdbcMotorDataRepository create(DatabaseConfig config) {
        return new JdbcMotorDataRepository(
            () -> DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password()), 1_000);
    }

    @Override
    public void initialize() {
        try (Connection c = connections.open(); Statement s = c.createStatement()) {
            c.setAutoCommit(true);
            for (String batch : schemaBatches()) {
                s.execute(batch);
            }
        } catch (SQLException e) {
            throw new PersistenceException("creating the schema failed", e);
        }
    }

    @Override
    public Optional<Instant> watermark(int monitorId) {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_WATERMARK)) {
            ps.setInt(1, monitorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                LocalDateTime value = rs.getObject(1, LocalDateTime.class);
                return Optional.ofNullable(value).map(v -> v.toInstant(ZoneOffset.UTC));
            }
        } catch (SQLException e) {
            throw new PersistenceException("reading the watermark of monitor " + monitorId + " failed", e);
        }
    }

    @Override
    public SaveResult save(MonitorBatch batch) {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                SaveResult result = saveInTransaction(c, batch);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("saving monitor " + batch.monitorId() + " " + batch.window() + " failed", e);
        }
    }

    private SaveResult saveInTransaction(Connection c, MonitorBatch batch) throws SQLException {
        int id = batch.monitorId();
        upsertMonitor(c, batch.placement(), batch.collectedAt());
        insertStatus(c, id, batch.collectedAt(), batch.placement().monitor().status());

        int basicReplaced = deleteWindow(c, DELETE_BASIC, batch);
        try (PreparedStatement ps = c.prepareStatement(INSERT_BASIC)) {
            int pending = 0;
            for (BasicFeature b : batch.basic()) {
                ps.setInt(1, id);
                ps.setObject(2, utc(b.time()));
                ps.setString(3, b.sourceId());
                ps.setString(4, b.jsonAvg());
                ps.setString(5, b.jsonMin());
                ps.setString(6, b.jsonMax());
                ps.addBatch();
                pending = flushIfFull(ps, pending + 1);
            }
            flush(ps, pending);
        }

        int computedReplaced = deleteWindow(c, DELETE_COMPUTED, batch);
        try (PreparedStatement ps = c.prepareStatement(INSERT_COMPUTED)) {
            int pending = 0;
            for (ComputedFeatureSample s : batch.computed()) {
                ps.setInt(1, id);
                ps.setObject(2, utc(s.time()));
                int i = 3;
                for (ComputedFeature f : ComputedFeature.values()) {
                    setDouble(ps, i++, s.values().get(f));
                }
                ps.addBatch();
                pending = flushIfFull(ps, pending + 1);
            }
            flush(ps, pending);
        }

        try (PreparedStatement ps = c.prepareStatement(UPSERT_WATERMARK)) {
            ps.setInt(1, id);
            ps.setObject(2, utc(batch.window().end()));
            ps.setObject(3, utc(batch.collectedAt()));
            ps.executeUpdate();
        }
        return new SaveResult(batch.basic().size(), basicReplaced, batch.computed().size(), computedReplaced);
    }

    private static void upsertMonitor(Connection c, MonitorPlacement p, Instant now) throws SQLException {
        MonitorInfo m = p.monitor();
        try (PreparedStatement ps = c.prepareStatement(UPSERT_MONITOR)) {
            ps.setInt(1, m.id());
            ps.setInt(2, p.plantId());
            ps.setInt(3, p.area().id());
            ps.setString(4, p.area().name());
            ps.setInt(5, p.machineGroup().id());
            ps.setString(6, p.machineGroup().name());
            ps.setInt(7, p.machine().id());
            ps.setString(8, p.machine().name());
            ps.setString(9, m.name());
            ps.setString(10, m.deviceIdentifier());
            ps.setString(11, m.orientationCode());
            ps.setObject(12, utc(now));
            ps.executeUpdate();
        }
    }

    private static void insertStatus(Connection c, int monitorId, Instant collectedAt, MonitorStatus s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_STATUS)) {
            int i = 1;
            ps.setInt(i++, monitorId);
            ps.setObject(i++, utc(collectedAt));
            setInt(ps, i++, s.healthScore());
            setInt(ps, i++, s.status());
            setInt(ps, i++, s.trend());
            setInt(ps, i++, s.alarms());
            setDouble(ps, i++, s.speed());
            for (Reading r : List.of(s.velocityX(), s.velocityY(), s.velocityZ(), s.temperature())) {
                setDouble(ps, i++, r.value());
                setDouble(ps, i++, r.operational());
                setDouble(ps, i++, r.caution());
                setDouble(ps, i++, r.warning());
            }
            ps.executeUpdate();
        }
    }

    private static int deleteWindow(Connection c, String sql, MonitorBatch batch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, batch.monitorId());
            ps.setObject(2, utc(batch.window().start()));
            ps.setObject(3, utc(batch.window().end()));
            return ps.executeUpdate();
        }
    }

    private int flushIfFull(PreparedStatement ps, int pending) throws SQLException {
        if (pending >= batchSize) {
            ps.executeBatch();
            return 0;
        }
        return pending;
    }

    private static void flush(PreparedStatement ps, int pending) throws SQLException {
        if (pending > 0) {
            ps.executeBatch();
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollbackQuietly(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /**
     * Splits the schema script into batches at lines consisting of {@code GO}.
     *
     * @return non-empty SQL batches in file order
     */
    static List<String> schemaBatches() {
        String script;
        try (InputStream in = JdbcMotorDataRepository.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + SCHEMA_RESOURCE);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SCHEMA_RESOURCE, e);
        }
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.strip().equalsIgnoreCase("GO")) {
                addIfNotBlank(batches, current);
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        addIfNotBlank(batches, current);
        return batches;
    }

    private static void addIfNotBlank(List<String> batches, StringBuilder sql) {
        String withoutComments = sql.toString().lines()
            .filter(l -> !l.strip().startsWith("--"))
            .collect(Collectors.joining("\n"));
        if (!withoutComments.isBlank()) {
            batches.add(sql.toString().strip());
        }
    }
}
