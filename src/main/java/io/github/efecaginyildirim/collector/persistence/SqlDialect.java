package io.github.efecaginyildirim.collector.persistence;

import java.util.Locale;

/**
 * The few SQL statements that differ between the supported databases.
 *
 * <p>Everything else — inserts, deletes, selects with {@code [quoted]} identifiers and
 * {@code OFFSET ... FETCH} paging — is shared, because H2 runs in its MSSQLServer compatibility
 * mode. Keeping the differences in one small enum makes them easy to review and test.
 *
 * <p>Both upserts bind their parameters in the same order, so the binding code is shared too.
 */
public enum SqlDialect {

    /** Microsoft SQL Server 2016 SP1 or newer. */
    SQL_SERVER("/db/sqlserver/schema.sql",
        """
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
              s.machine_id, s.machine_name, s.monitor_name, s.device_identifier, s.orientation_code, s.updated_at);""",
        """
        MERGE dbo.collection_watermark WITH (HOLDLOCK) AS t
        USING (SELECT ? AS monitor_id, ? AS collected_until, ? AS updated_at) AS s
           ON t.monitor_id = s.monitor_id
        WHEN MATCHED THEN UPDATE SET
             collected_until = CASE WHEN s.collected_until > t.collected_until THEN s.collected_until ELSE t.collected_until END,
             updated_at = s.updated_at
        WHEN NOT MATCHED THEN INSERT (monitor_id, collected_until, updated_at)
             VALUES (s.monitor_id, s.collected_until, s.updated_at);"""),

    /** H2 2.x, embedded (file or in-memory), in MSSQLServer compatibility mode. */
    H2("/db/h2/schema.sql",
        """
        MERGE INTO dbo.monitor
          (monitor_id, plant_id, area_id, area_name, machine_group_id, machine_group_name,
           machine_id, machine_name, monitor_name, device_identifier, orientation_code, updated_at)
        KEY (monitor_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        """
        MERGE INTO dbo.collection_watermark AS t
        USING (VALUES (CAST(? AS INT), CAST(? AS TIMESTAMP(3)), CAST(? AS TIMESTAMP(3))))
              AS s (monitor_id, collected_until, updated_at)
           ON t.monitor_id = s.monitor_id
        WHEN MATCHED THEN UPDATE SET
             collected_until = GREATEST(t.collected_until, s.collected_until),
             updated_at = s.updated_at
        WHEN NOT MATCHED THEN INSERT (monitor_id, collected_until, updated_at)
             VALUES (s.monitor_id, s.collected_until, s.updated_at)""");

    private final String schemaResource;
    private final String upsertMonitor;
    private final String upsertWatermark;

    SqlDialect(String schemaResource, String upsertMonitor, String upsertWatermark) {
        this.schemaResource = schemaResource;
        this.upsertMonitor = upsertMonitor;
        this.upsertWatermark = upsertWatermark;
    }

    /** @return classpath resource with the idempotent schema script */
    public String schemaResource() {
        return schemaResource;
    }

    /** @return upsert of one monitor row; 12 parameters, see {@link JdbcMotorDataRepository} */
    public String upsertMonitor() {
        return upsertMonitor;
    }

    /** @return upsert of one watermark that never moves it backwards; 3 parameters */
    public String upsertWatermark() {
        return upsertWatermark;
    }

    /**
     * Picks the dialect from a JDBC URL.
     *
     * @param jdbcUrl e.g. {@code jdbc:sqlserver://...} or {@code jdbc:h2:file:...}
     * @return the dialect
     * @throws IllegalArgumentException for unsupported databases
     */
    public static SqlDialect fromJdbcUrl(String jdbcUrl) {
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:sqlserver:")) {
            return SQL_SERVER;
        }
        if (url.startsWith("jdbc:h2:")) {
            return H2;
        }
        throw new IllegalArgumentException("unsupported database URL " + jdbcUrl + " (expected jdbc:sqlserver: or jdbc:h2:)");
    }
}
