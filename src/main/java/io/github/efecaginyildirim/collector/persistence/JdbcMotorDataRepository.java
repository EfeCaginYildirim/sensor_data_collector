package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.config.AppConfig.DatabaseConfig;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.MonitorInfo.Reading;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;
import io.github.efecaginyildirim.collector.time.TimeWindow;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@link MotorDataRepository}, {@link MotorDataReader} and {@link RunHistory} over plain JDBC,
 * for Microsoft SQL Server and embedded H2 (see {@link SqlDialect}).
 *
 * <h2>Write strategy</h2>
 * A save runs in one transaction:
 * <ol>
 *   <li>upsert the monitor row;</li>
 *   <li>replace the status snapshot taken at the batch's collection time;</li>
 *   <li>{@code DELETE} the samples inside the window (a primary-key range seek) and batch-insert
 *       the new ones;</li>
 *   <li>advance the watermark, never moving it backwards.</li>
 * </ol>
 * Replacing the window instead of merging row by row keeps the statements simple, index-friendly
 * and idempotent. On SQL Server, {@code useBulkCopyForBatchInsert=true} in the JDBC URL turns the
 * batched inserts into bulk-copy operations.
 *
 * <h2>Connections</h2>
 * The collector writes from a single thread, a handful of times per run, so it opens one
 * connection per operation instead of maintaining a pool. For H2 the database is kept open
 * between connections and shut down by {@link #close()}.
 *
 * <p>All timestamps are stored as UTC.
 */
public final class JdbcMotorDataRepository implements MotorDataRepository, MotorDataReader, RunHistory, ExclusionStore,
    AutoCloseable {

    /** Supplies JDBC connections; each call returns a new connection owned by the caller. */
    @FunctionalInterface
    public interface ConnectionFactory {
        /**
         * @return an open connection
         * @throws SQLException if connecting fails
         */
        Connection open() throws SQLException;
    }

    static final String STATUS_COLUMNS = "health_score, status, trend, alarms, speed, "
        + "velocity_x, velocity_x_operational, velocity_x_caution, velocity_x_warning, "
        + "velocity_y, velocity_y_operational, velocity_y_caution, velocity_y_warning, "
        + "velocity_z, velocity_z_operational, velocity_z_caution, velocity_z_warning, "
        + "temperature, temperature_operational, temperature_caution, temperature_warning";

    static final String INSERT_STATUS = "INSERT INTO dbo.monitor_status (monitor_id, collected_at, " + STATUS_COLUMNS
        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Replacing a snapshot taken at the same instant keeps two runs within one second from colliding. */
    static final String DELETE_STATUS = "DELETE FROM dbo.monitor_status WHERE monitor_id = ? AND collected_at = ?";

    static final String DELETE_BASIC =
        "DELETE FROM dbo.basic_feature WHERE monitor_id = ? AND sample_time >= ? AND sample_time < ?";

    static final String INSERT_BASIC =
        "INSERT INTO dbo.basic_feature (monitor_id, sample_time, source_id, json_avg, json_min, json_max) VALUES (?, ?, ?, ?, ?, ?)";

    static final String DELETE_COMPUTED =
        "DELETE FROM dbo.computed_feature WHERE monitor_id = ? AND sample_time >= ? AND sample_time < ?";

    /** Generated from {@link ComputedFeature} so the column list cannot drift from the enum. */
    static final String INSERT_COMPUTED = "INSERT INTO dbo.computed_feature (monitor_id, sample_time, "
        + columns(Arrays.asList(ComputedFeature.values()))
        + ") VALUES (?, ?" + ", ?".repeat(ComputedFeature.values().length) + ")";

    static final String SELECT_WATERMARK =
        "SELECT collected_until FROM dbo.collection_watermark WHERE monitor_id = ?";

    static final String SELECT_MONITORS = "SELECT m.monitor_id, m.plant_id, m.area_name, m.machine_group_name, "
        + "m.machine_name, m.monitor_name, w.collected_until, "
        + "(SELECT COUNT(*) FROM dbo.basic_feature b WHERE b.monitor_id = m.monitor_id), "
        + "(SELECT COUNT(*) FROM dbo.computed_feature c WHERE c.monitor_id = m.monitor_id), "
        + "s.collected_at, " + prefixed("s.", STATUS_COLUMNS) + " "
        + "FROM dbo.monitor m "
        + "LEFT JOIN dbo.collection_watermark w ON w.monitor_id = m.monitor_id "
        + "LEFT JOIN dbo.monitor_status s ON s.monitor_id = m.monitor_id AND s.collected_at = "
        + "(SELECT MAX(x.collected_at) FROM dbo.monitor_status x WHERE x.monitor_id = m.monitor_id) "
        + "ORDER BY m.monitor_id";

    static final String SELECT_STATUS_HISTORY = "SELECT collected_at, " + STATUS_COLUMNS
        + " FROM dbo.monitor_status WHERE monitor_id = ? ORDER BY collected_at DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";

    static final String SELECT_BASIC = "SELECT sample_time, source_id, json_avg, json_min, json_max FROM dbo.basic_feature "
        + "WHERE monitor_id = ? AND sample_time >= ? AND sample_time < ? ORDER BY sample_time";

    static final String INSERT_RUN = "INSERT INTO dbo.collection_run (run_id, started_at, elapsed_ms, status, failure, "
        + "monitors_total, monitors_failed, new_rows, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SELECT_RUNS = "SELECT run_id, started_at, elapsed_ms, status, failure, monitors_total, "
        + "monitors_failed, new_rows, summary FROM dbo.collection_run ORDER BY started_at DESC, run_id "
        + "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";

    static final String INSERT_EXCLUSION = "INSERT INTO dbo.sample_exclusion (monitor_id, from_time, to_time, reason, created_at) "
        + "VALUES (?, ?, ?, ?, ?)";

    static final String DELETE_EXCLUSION = "DELETE FROM dbo.sample_exclusion WHERE exclusion_id = ?";

    static final String SELECT_EXCLUSIONS = "SELECT exclusion_id, monitor_id, from_time, to_time, reason, created_at "
        + "FROM dbo.sample_exclusion";

    private final ConnectionFactory connections;
    private final SqlDialect dialect;
    private final int batchSize;
    private final Runnable onClose;

    /**
     * @param connections source of connections
     * @param dialect     SQL dialect of the database behind {@code connections}
     * @param batchSize   rows per JDBC batch; must be positive
     */
    public JdbcMotorDataRepository(ConnectionFactory connections, SqlDialect dialect, int batchSize) {
        this(connections, dialect, batchSize, () -> { });
    }

    JdbcMotorDataRepository(ConnectionFactory connections, SqlDialect dialect, int batchSize, Runnable onClose) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    /**
     * Creates a repository from configuration; the dialect follows the JDBC URL.
     * H2 URLs are completed with the settings the shared SQL needs (see {@link H2Database}).
     *
     * @param config database settings
     * @return the repository; call {@link #initialize()} before use
     */
    public static JdbcMotorDataRepository create(DatabaseConfig config) {
        SqlDialect dialect = SqlDialect.fromJdbcUrl(config.jdbcUrl());
        if (dialect == SqlDialect.H2) {
            return H2Database.open(config.jdbcUrl(), config.user(), config.password());
        }
        return new JdbcMotorDataRepository(
            () -> DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password()), dialect, 1_000);
    }

    /** @return the dialect in use */
    public SqlDialect dialect() {
        return dialect;
    }

    // ─── Schema ──────────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        try (Connection c = connections.open(); Statement s = c.createStatement()) {
            c.setAutoCommit(true);
            for (String batch : schemaBatches(dialect.schemaResource())) {
                s.execute(batch);
            }
        } catch (SQLException e) {
            throw new PersistenceException("creating the schema failed", e);
        }
    }

    // ─── Writes ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<Instant> watermark(int monitorId) {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_WATERMARK)) {
            ps.setInt(1, monitorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(instant(rs, 1)) : Optional.empty();
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

        try (PreparedStatement ps = c.prepareStatement(dialect.upsertWatermark())) {
            ps.setInt(1, id);
            ps.setObject(2, utc(batch.window().end()));
            ps.setObject(3, utc(batch.collectedAt()));
            ps.executeUpdate();
        }
        return new SaveResult(batch.basic().size(), basicReplaced, batch.computed().size(), computedReplaced);
    }

    private void upsertMonitor(Connection c, MonitorPlacement p, Instant now) throws SQLException {
        MonitorInfo m = p.monitor();
        try (PreparedStatement ps = c.prepareStatement(dialect.upsertMonitor())) {
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
        try (PreparedStatement ps = c.prepareStatement(DELETE_STATUS)) {
            ps.setInt(1, monitorId);
            ps.setObject(2, utc(collectedAt));
            ps.executeUpdate();
        }
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

    // ─── Reads ───────────────────────────────────────────────────────────────────

    @Override
    public List<MonitorSummary> monitors() {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_MONITORS);
             ResultSet rs = ps.executeQuery()) {
            List<MonitorSummary> out = new ArrayList<>();
            while (rs.next()) {
                Instant statusTime = instant(rs, 10);
                Optional<StatusSnapshot> latest = statusTime == null ? Optional.empty()
                    : Optional.of(new StatusSnapshot(statusTime, readStatus(rs, 11)));
                out.add(new MonitorSummary(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), Optional.ofNullable(instant(rs, 7)), latest,
                    rs.getLong(8), rs.getLong(9)));
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("listing monitors failed", e);
        }
    }

    @Override
    public List<StatusSnapshot> statusHistory(int monitorId, int limit) {
        requirePositive(limit);
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_STATUS_HISTORY)) {
            ps.setInt(1, monitorId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<StatusSnapshot> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new StatusSnapshot(instant(rs, 1), readStatus(rs, 2)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("reading the status history of monitor " + monitorId + " failed", e);
        }
    }

    @Override
    public List<BasicFeature> basicFeatures(int monitorId, TimeWindow window) {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_BASIC)) {
            ps.setInt(1, monitorId);
            ps.setObject(2, utc(window.start()));
            ps.setObject(3, utc(window.end()));
            try (ResultSet rs = ps.executeQuery()) {
                List<BasicFeature> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new BasicFeature(instant(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("reading basic features of monitor " + monitorId + " failed", e);
        }
    }

    @Override
    public long query(DatasetQuery query, Consumer<DatasetRow> sink) {
        String sql = datasetSql(query);
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (int id : query.monitorIds()) {
                ps.setInt(i++, id);
            }
            ps.setObject(i++, utc(query.window().start()));
            ps.setObject(i++, utc(query.window().end()));
            if (query.limit().isPresent()) {
                ps.setInt(i, query.limit().getAsInt());
            }
            ps.setFetchSize(1_000);
            long rows = 0;
            int width = query.features().size();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Double> values = new ArrayList<>(width);
                    for (int col = 0; col < width; col++) {
                        values.add(rs.getObject(3 + col, Double.class));
                    }
                    sink.accept(new DatasetRow(rs.getInt(1), instant(rs, 2), values));
                    rows++;
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new PersistenceException("dataset query failed", e);
        }
    }

    /**
     * Builds the dataset SELECT. Column names come only from {@link ComputedFeature}; every value
     * is a bind parameter.
     */
    static String datasetSql(DatasetQuery query) {
        return "SELECT monitor_id, sample_time, " + columns(query.features())
            + " FROM dbo.computed_feature WHERE monitor_id IN ("
            + String.join(", ", Collections.nCopies(query.monitorIds().size(), "?"))
            + ") AND sample_time >= ? AND sample_time < ? ORDER BY monitor_id, sample_time"
            + (query.limit().isPresent() ? " OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY" : "");
    }

    // ─── Run history ─────────────────────────────────────────────────────────────

    @Override
    public void record(RunRecord run) {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(INSERT_RUN)) {
            c.setAutoCommit(true);
            ps.setString(1, run.runId());
            ps.setObject(2, utc(run.startedAt()));
            ps.setLong(3, run.elapsed().toMillis());
            ps.setString(4, run.status());
            ps.setString(5, run.failure().map(f -> f.length() > 2000 ? f.substring(0, 2000) : f).orElse(null));
            ps.setInt(6, run.monitorsTotal());
            ps.setInt(7, run.monitorsFailed());
            ps.setInt(8, run.newRows());
            ps.setString(9, run.summary());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("recording run " + run.runId() + " failed", e);
        }
    }

    @Override
    public List<RunRecord> recentRuns(int limit) {
        requirePositive(limit);
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(SELECT_RUNS)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new RunRecord(rs.getString(1), instant(rs, 2), Duration.ofMillis(rs.getLong(3)),
                        rs.getString(4), Optional.ofNullable(rs.getString(5)), rs.getInt(6), rs.getInt(7),
                        rs.getInt(8), rs.getString(9)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("reading the run history failed", e);
        }
    }

    // ─── Manual exclusions ───────────────────────────────────────────────────────

    @Override
    public SampleExclusion addExclusion(int monitorId, TimeWindow window, String reason, Instant now) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > 500) {
            throw new IllegalArgumentException("give a reason of 1 to 500 characters");
        }
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(INSERT_EXCLUSION, Statement.RETURN_GENERATED_KEYS)) {
            c.setAutoCommit(true);
            ps.setInt(1, monitorId);
            ps.setObject(2, utc(window.start()));
            ps.setObject(3, utc(window.end()));
            ps.setString(4, text);
            ps.setObject(5, utc(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no generated key returned");
                }
                return new SampleExclusion(keys.getLong(1), monitorId, window, text, now);
            }
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new IllegalArgumentException("monitor " + monitorId + " has no collected data", e);
            }
            throw new PersistenceException("adding an exclusion for monitor " + monitorId + " failed", e);
        }
    }

    @Override
    public boolean removeExclusion(long id) {
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(DELETE_EXCLUSION)) {
            c.setAutoCommit(true);
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("removing exclusion " + id + " failed", e);
        }
    }

    @Override
    public List<SampleExclusion> exclusions(List<Integer> monitorIds) {
        String sql = SELECT_EXCLUSIONS
            + (monitorIds.isEmpty() ? "" : " WHERE monitor_id IN (" + String.join(", ", Collections.nCopies(monitorIds.size(), "?")) + ")")
            + " ORDER BY monitor_id, from_time, exclusion_id";
        try (Connection c = connections.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < monitorIds.size(); i++) {
                ps.setInt(i + 1, monitorIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<SampleExclusion> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SampleExclusion(rs.getLong(1), rs.getInt(2), new TimeWindow(instant(rs, 3), instant(rs, 4)),
                        rs.getString(5), instant(rs, 6)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException("listing exclusions failed", e);
        }
    }

    /** Releases the database; for embedded H2 this shuts it down. */
    @Override
    public void close() {
        onClose.run();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static MonitorStatus readStatus(ResultSet rs, int first) throws SQLException {
        int i = first;
        Integer health = rs.getObject(i++, Integer.class);
        Integer status = rs.getObject(i++, Integer.class);
        Integer trend = rs.getObject(i++, Integer.class);
        Integer alarms = rs.getObject(i++, Integer.class);
        Double speed = rs.getObject(i++, Double.class);
        Reading[] readings = new Reading[4];
        for (int r = 0; r < 4; r++) {
            readings[r] = new Reading(rs.getObject(i++, Double.class), rs.getObject(i++, Double.class),
                rs.getObject(i++, Double.class), rs.getObject(i++, Double.class));
        }
        return new MonitorStatus(health, status, trend, alarms, speed, readings[0], readings[1], readings[2], readings[3]);
    }

    private static String columns(List<ComputedFeature> features) {
        return features.stream().map(f -> "[" + f.column() + "]").collect(Collectors.joining(", "));
    }

    private static String prefixed(String prefix, String columnList) {
        return Arrays.stream(columnList.split(",\\s*")).map(c -> prefix + c).collect(Collectors.joining(", "));
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

    private static Instant instant(ResultSet rs, int index) throws SQLException {
        LocalDateTime value = rs.getObject(index, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void requirePositive(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void rollbackQuietly(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /**
     * Splits a schema script into batches at lines consisting of {@code GO}.
     *
     * @param resource classpath resource of the script
     * @return non-empty SQL batches in file order
     */
    static List<String> schemaBatches(String resource) {
        String script;
        try (InputStream in = JdbcMotorDataRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
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
