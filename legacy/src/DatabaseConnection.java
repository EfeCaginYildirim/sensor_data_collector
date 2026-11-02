import java.sql.*;

/**
 * Manages a single shared database connection (legacy singleton — used by
 * {@code DatabaseConnectionPool} for table creation only; all active queries
 * go through the pool).
 *
 * <p><strong>Note:</strong> This class holds a static {@link Connection} and is
 * therefore not thread-safe for concurrent queries. All read/write operations
 * in the data pipeline use {@link DatabaseConnectionPool} instead.
 */
public class DatabaseConnection {
    private static Connection connection = null;

    /**
     * Returns the shared connection, creating it if necessary.
     * Prefers SQL Server; falls back to H2 if the JDBC driver is unavailable.
     */
    public static Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                String dbUrl      = ConfigManager.getDatabaseUrl();
                String dbUser     = ConfigManager.getDatabaseUser();
                String dbPassword = ConfigManager.getDatabasePassword();

                try {
                    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                    connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    System.out.println("SQL Server connection established!");

                    createTableIfNotExists(connection);
                    return connection;

                } catch (ClassNotFoundException e) {
                    System.out.println("SQL Server JDBC driver not found. Trying H2 Database...");

                    try {
                        Class.forName("org.h2.Driver");
                        String h2Url = "jdbc:h2:./motor_data_db;AUTO_SERVER=TRUE";
                        connection = DriverManager.getConnection(h2Url, "sa", "");
                        System.out.println("H2 Database connection established!");

                        createTableIfNotExists(connection);
                        return connection;

                    } catch (ClassNotFoundException e2) {
                        System.out.println("H2 Database driver not found either.");
                        System.out.println("To fix:");
                        System.out.println("  1. For SQL Server: add mssql-jdbc-12.4.0.jre17.jar to the classpath");
                        System.out.println("  2. Or add the dependency via Maven/Gradle");
                        return null;
                    }
                }
            }
            return connection;
        } catch (Exception e) {
            System.out.println("Database connection error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates the {@code dbo.Motor_Data} table if it does not already exist.
     * Also commits the DDL so that other connections can see the new table.
     */
    public static void createTableIfNotExists(Connection conn) {
        try {
            String createTableSQL =
                "IF NOT EXISTS (SELECT * FROM sys.tables WHERE name='Motor_Data' AND schema_id = SCHEMA_ID('dbo')) " +
                "CREATE TABLE dbo.Motor_Data (" +
                "plant_id INT," +
                "plant_name NVARCHAR(255)," +
                "plant_organizationName NVARCHAR(255)," +
                "plant_odrEnabled BIT," +
                "area_id INT," +
                "area_name VARCHAR(255)," +
                "area_status INT," +
                "machineGroup_id INT," +
                "machineGroup_name VARCHAR(255)," +
                "machineGroup_status INT," +
                "machineGroup_hasComments BIT," +
                "machineGroup_drsTag VARCHAR(255)," +
                "machineGroup_serviceRequestStatus VARCHAR(255)," +
                "machineGroup_synPriority INT," +
                "machineGroup_superscript VARCHAR(255)," +
                "machineGroup_superscriptMessage VARCHAR(255)," +
                "machine_id INT," +
                "machine_name VARCHAR(255)," +
                "machine_monitorCount INT," +
                "machine_observation VARCHAR(255)," +
                "machine_diagnostic VARCHAR(255)," +
                "machine_recommendation VARCHAR(255)," +
                "machine_healthScore INT," +
                "machine_trend INT," +
                "machine_status INT," +
                "machine_isDrsReportOpen BIT," +
                "machine_mountingOrientation VARCHAR(255)," +
                "machine_superscript VARCHAR(255)," +
                "machine_superscriptMessage VARCHAR(255)," +
                "machine_synPriority INT," +
                "monitor_id INT," +
                "monitor_name VARCHAR(255)," +
                "monitor_deviceIdentifier VARCHAR(255)," +
                "monitor_orientationCode VARCHAR(255)," +
                "monitor_subscriptionStart DATE," +
                "monitor_subscriptionEnd DATE," +
                "monitor_alarms INT," +
                "monitor_observation VARCHAR(255)," +
                "monitor_diagnostic VARCHAR(255)," +
                "monitor_recommendation VARCHAR(255)," +
                "monitor_error VARCHAR(255)," +
                "monitor_healthScore INT," +
                "monitor_speed FLOAT," +
                "monitor_synPriority INT," +
                "monitor_velocityX FLOAT," +
                "monitor_velocityXOperationalThreshold FLOAT," +
                "monitor_velocityXCautionThreshold FLOAT," +
                "monitor_velocityXWarningThreshold FLOAT," +
                "monitor_velocityY FLOAT," +
                "monitor_velocityYOperationalThreshold FLOAT," +
                "monitor_velocityYCautionThreshold FLOAT," +
                "monitor_velocityYWarningThreshold FLOAT," +
                "monitor_velocityZ FLOAT," +
                "monitor_velocityZOperationalThreshold FLOAT," +
                "monitor_velocityZCautionThreshold FLOAT," +
                "monitor_velocityZWarningThreshold FLOAT," +
                "monitor_temperature FLOAT," +
                "monitor_temperatureOperationalThreshold FLOAT," +
                "monitor_temperatureCautionThreshold FLOAT," +
                "monitor_temperatureWarningThreshold FLOAT," +
                "monitor_status INT," +
                "monitor_trend INT," +
                "monitor_healthTrend INT," +
                "monitor_superscript VARCHAR(255)," +
                "monitor_superscriptMessage VARCHAR(255)," +
                "basicFeature_id VARCHAR(255)," +
                "basicFeature_time DATETIME2," +
                "basicFeature_monitorId INT," +
                "basicFeature_jsonAvg NVARCHAR(MAX)," +
                "basicFeature_jsonMin NVARCHAR(MAX)," +
                "basicFeature_jsonMax NVARCHAR(MAX)," +
                "computedFeature_timestamp VARCHAR(255)," +
                "computedFeature_KURTACCLX FLOAT," +
                "computedFeature_KURTACCLY FLOAT," +
                "computedFeature_KURTACCLZ FLOAT," +
                "computedFeature_PPACCLX FLOAT," +
                "computedFeature_PPACCLY FLOAT," +
                "computedFeature_PPACCLZ FLOAT," +
                "computedFeature_CRESTACCLX FLOAT," +
                "computedFeature_CRESTACCLY FLOAT," +
                "computedFeature_CRESTACCLZ FLOAT," +
                "computedFeature_GRMSACCLX FLOAT," +
                "computedFeature_GRMSACCLY FLOAT," +
                "computedFeature_GRMSACCLZ FLOAT," +
                "computedFeature_PPSWX FLOAT," +
                "computedFeature_PPSWY FLOAT," +
                "computedFeature_PPSWZ FLOAT," +
                "computedFeature_SWRMSX FLOAT," +
                "computedFeature_SWRMSY FLOAT," +
                "computedFeature_SWRMSZ FLOAT," +
                "computedFeature_IDLE FLOAT," +
                "computedFeature_RPM FLOAT," +
                "computedFeature_BPFI FLOAT," +
                "computedFeature_BPFO FLOAT," +
                "computedFeature_BSF FLOAT," +
                "computedFeature_FTF FLOAT," +
                "computedFeature_LOAD FLOAT," +
                "computedFeature_VRMSX FLOAT," +
                "computedFeature_VRMSY FLOAT," +
                "computedFeature_VRMSZ FLOAT," +
                "computedFeature_TA FLOAT" +
                ")";

            Statement stmt = conn.createStatement();
            stmt.execute(createTableSQL);
            stmt.execute("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_Motor_Data_Dedup' "
                + "AND object_id = OBJECT_ID('dbo.Motor_Data')) "
                + "CREATE UNIQUE INDEX UX_Motor_Data_Dedup ON dbo.Motor_Data "
                + "(monitor_id, basicFeature_time, computedFeature_timestamp)");
            // Commit DDL so other connections can see the table immediately
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
            System.out.println("Motor_Data table created (or already exists).");

        } catch (SQLException e) {
            System.out.println("Table creation error: " + e.getMessage());
        }
    }

    /** Close the shared connection. */
    public static void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Database connection closed.");
            }
        } catch (SQLException e) {
            System.out.println("Connection close error: " + e.getMessage());
        }
    }

    /** Quick connectivity test. */
    public static boolean testConnection() {
        try {
            Connection conn = getConnection();
            if (conn != null) {
                System.out.println("Database connection test successful!");
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println("Database connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update connection credentials at runtime (closes the existing connection
     * so the next call to {@link #getConnection()} uses the new credentials).
     */
    public static void updateConnectionInfo(String url, String username, String password) {
        System.out.println("Connection credentials updated.");
        closeConnection();
    }
}
