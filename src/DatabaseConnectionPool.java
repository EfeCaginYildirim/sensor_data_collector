import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Database connection pool — optimized for performance.
 */
public class DatabaseConnectionPool {
    private static final int MAX_CONNECTIONS = 2;
    private static final int CONNECTION_TIMEOUT = 30; // 30 seconds
    private static final int IDLE_TIMEOUT = 300; // 5 minutes
    
    private static final BlockingQueue<Connection> connectionPool = new LinkedBlockingQueue<>(MAX_CONNECTIONS);
    private static final java.util.concurrent.atomic.AtomicBoolean tableCreated = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    
    static {
        initializePool();
        startCleanupTask();
    }
    
    /**
     * Initialize the connection pool.
     */
    private static void initializePool() {
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            try {
                Connection conn = createConnection();
                connectionPool.offer(conn);
            } catch (Exception e) {
                System.out.println("Database connection pool initialization error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Create a new database connection.
     */
    private static Connection createConnection() throws SQLException {
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Connection conn = DriverManager.getConnection(
                ConfigManager.getDatabaseUrl(),
                ConfigManager.getDatabaseUser(),
                ConfigManager.getDatabasePassword()
            );
            
            // Connection settings
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            
            // Ensure the table exists (only once)
            if (tableCreated.compareAndSet(false, true)) {
                DatabaseConnection.createTableIfNotExists(conn);
            }
            
            return conn;
        } catch (ClassNotFoundException e) {
            // H2 Database fallback
            try {
                Class.forName("org.h2.Driver");
                String h2Url = "jdbc:h2:./motor_data_db;AUTO_SERVER=TRUE";
                Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                conn.setAutoCommit(false);
                if (tableCreated.compareAndSet(false, true)) {
                    DatabaseConnection.createTableIfNotExists(conn);
                }
                return conn;
            } catch (ClassNotFoundException e2) {
                throw new SQLException("JDBC Driver not found: " + e.getMessage());
            }
        }
    }
    
    /**
     * Acquire a connection from the pool.
     */
    public static Connection getConnection() throws SQLException {
        try {
            Connection conn = connectionPool.poll(CONNECTION_TIMEOUT, TimeUnit.SECONDS);
            if (conn == null) {
                // Pool is empty — create a new connection
                conn = createConnection();
            }
            
            // Verify the connection is still alive
            if (conn.isClosed()) {
                conn = createConnection();
            }
            
            activeConnections.incrementAndGet();
            return conn;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Connection acquisition timed out: " + e.getMessage());
        }
    }
    
    /**
     * Return a connection to the pool.
     */
    public static void releaseConnection(Connection conn) {
        if (conn != null) {
            try {
                // Roll back any uncommitted transaction
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                }
                
                // Return the connection to the pool
                if (!conn.isClosed()) {
                    connectionPool.offer(conn);
                }
            } catch (SQLException e) {
                System.out.println("Connection release error: " + e.getMessage());
                // Close the faulty connection
                try {
                    conn.close();
                } catch (SQLException ex) {
                    // Ignore
                }
            } finally {
                activeConnections.decrementAndGet();
            }
        }
    }
    
    /**
     * Start the idle-connection cleanup task.
     */
    private static void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupIdleConnections();
            } catch (Exception e) {
                System.out.println("Connection cleanup error: " + e.getMessage());
            }
        }, IDLE_TIMEOUT, IDLE_TIMEOUT, TimeUnit.SECONDS);
    }
    
    /**
     * Close idle connections that exceed the pool half-capacity.
     */
    private static void cleanupIdleConnections() {
        int poolSize = connectionPool.size();
        if (poolSize > MAX_CONNECTIONS / 2) {
            // Pool is more than half full — trim excess connections
            Connection conn;
            int cleaned = 0;
            while ((conn = connectionPool.poll()) != null && cleaned < poolSize / 2) {
                try {
                    conn.close();
                    cleaned++;
                } catch (SQLException e) {
                    // Ignore
                }
            }
            
            if (cleaned > 0) {
                System.out.println("🧹 " + cleaned + " idle connections closed");
            }
        }
    }
    
    /**
     * Shut down the connection pool.
     */
    public static void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
        }
        
        // Close all connections
        Connection conn;
        while ((conn = connectionPool.poll()) != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Print pool status.
     */
    public static void printPoolStatus() {
        System.out.println("Database Pool Status:");
        System.out.println("  Active connections: " + activeConnections.get());
        System.out.println("  Pooled connections: " + connectionPool.size());
        System.out.println("  Maximum connections: " + MAX_CONNECTIONS);
    }
    
    /**
     * Test the database connection.
     */
    public static boolean testConnection() {
        try {
            Connection conn = getConnection();
            if (conn != null) {
                releaseConnection(conn);
                System.out.println("✅ Database connection test successful!");
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println("❌ Database connection test failed: " + e.getMessage());
            return false;
        }
    }
}
