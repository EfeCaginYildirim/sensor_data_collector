import java.io.*;
import java.util.Properties;

/**
 * Manages configuration files.
 * Reads settings from the scheduler_config.properties file.
 */
public class ConfigManager {
    private static Properties properties = new Properties();
    private static boolean loaded = false;
    
    // Default values
    private static final String DEFAULT_RUN_TIME = "09:00";
    private static final int DEFAULT_REPEAT_INTERVAL = 24;
    private static final int DEFAULT_INITIAL_DELAY = 0;
    private static final String DEFAULT_OUTPUT_DIR = "./";
    private static final String DEFAULT_FILE_PREFIX = "combined_data_";
    private static final boolean DEFAULT_TIMESTAMPED_FILES = true;
    
    /**
     * Load the configuration file.
     */
    public static void loadConfig() {
        if (loaded) return;
        
        try {
            String configPath = System.getProperty("config.path", "scheduler_config.properties");
            FileInputStream input = new FileInputStream(configPath);
            properties.load(input);
            input.close();
            loaded = true;
            System.out.println("✅ Configuration file loaded: " + configPath);
        } catch (IOException e) {
            System.out.println("⚠️ Could not read configuration file, using default values: " + e.getMessage());
            loaded = true; // Use default values
        }
    }
    
    /**
     * Get the daily run time.
     */
    public static String getDailyRunTime() {
        loadConfig();
        return properties.getProperty("daily_run_time", DEFAULT_RUN_TIME);
    }
    
    /**
     * Get the repeat interval in hours (legacy compatibility).
     */
    public static int getRepeatIntervalHours() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("repeat_interval_hours", String.valueOf(DEFAULT_REPEAT_INTERVAL)));
        } catch (NumberFormatException e) {
            return DEFAULT_REPEAT_INTERVAL;
        }
    }
    
    /**
     * Get the repeat interval in minutes.
     */
    public static int getRepeatIntervalMinutes() {
        loadConfig();
        try {
            // Check for minutes setting first
            String minutesStr = properties.getProperty("repeat_interval_minutes");
            if (minutesStr != null) {
                return Integer.parseInt(minutesStr);
            }
            // Fall back to converting hours to minutes
            return getRepeatIntervalHours() * 60;
        } catch (NumberFormatException e) {
            return DEFAULT_REPEAT_INTERVAL * 60; // 24 hours = 1440 minutes
        }
    }
    
    /**
     * Get the initial delay in hours (legacy compatibility).
     */
    public static int getInitialDelayHours() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("initial_delay_hours", String.valueOf(DEFAULT_INITIAL_DELAY)));
        } catch (NumberFormatException e) {
            return DEFAULT_INITIAL_DELAY;
        }
    }
    
    /**
     * Get the initial delay in minutes.
     */
    public static int getInitialDelayMinutes() {
        loadConfig();
        try {
            // Check for minutes setting first
            String minutesStr = properties.getProperty("initial_delay_minutes");
            if (minutesStr != null) {
                return Integer.parseInt(minutesStr);
            }
            // Fall back to converting hours to minutes
            return getInitialDelayHours() * 60;
        } catch (NumberFormatException e) {
            return DEFAULT_INITIAL_DELAY * 60;
        }
    }
    
    /**
     * Get the database URL.
     */
    public static String getDatabaseUrl() {
        loadConfig();
        return properties.getProperty("db_url", "jdbc:sqlserver://localhost:1433;databaseName=SensorData_DB;encrypt=true;trustServerCertificate=true;");
    }
    
    /**
     * Get the database username.
     */
    public static String getDatabaseUser() {
        loadConfig();
        return properties.getProperty("db_user", "sa");
    }
    
    /**
     * Get the database password.
     */
    public static String getDatabasePassword() {
        loadConfig();
        return properties.getProperty("db_password", "");
    }
    
    /**
     * Get the API base URL.
     */
    public static String getApiBaseUrl() {
        loadConfig();
        return properties.getProperty("api_base_url", "");
    }
    
    /**
     * Get the API username.
     */
    public static String getApiUsername() {
        loadConfig();
        return properties.getProperty("api_username", "");
    }
    
    /**
     * Get the API password.
     */
    public static String getApiPassword() {
        loadConfig();
        return properties.getProperty("api_password", "");
    }
    
    /**
     * Get the plant ID.
     */
    public static int getPlantId() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("plant_id", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * Get the monitor IDs.
     */
    public static int[] getMonitorIds() {
        loadConfig();
        String monitorIdsStr = properties.getProperty("monitor_ids", "");
        
        if (monitorIdsStr == null || monitorIdsStr.trim().isEmpty()) {
            return new int[]{};
        }
        
        try {
            String[] parts = monitorIdsStr.split(",");
            int[] monitorIds = new int[parts.length];
            
            for (int i = 0; i < parts.length; i++) {
                monitorIds[i] = Integer.parseInt(parts[i].trim());
            }
            
            return monitorIds;
        } catch (NumberFormatException e) {
            // monitor_ids must be configured in scheduler_config.properties
            return new int[]{};
        }
    }
    
    /**
     * Get the output directory.
     */
    public static String getOutputDirectory() {
        loadConfig();
        return properties.getProperty("output_directory", DEFAULT_OUTPUT_DIR);
    }
    
    /**
     * Get the JSON file prefix.
     */
    public static String getJsonFilePrefix() {
        loadConfig();
        return properties.getProperty("json_file_prefix", DEFAULT_FILE_PREFIX);
    }
    
    /**
     * Get whether timestamped files should be created.
     */
    public static boolean createTimestampedFiles() {
        loadConfig();
        return Boolean.parseBoolean(properties.getProperty("create_timestamped_files", String.valueOf(DEFAULT_TIMESTAMPED_FILES)));
    }
    
    /**
     * Get whether detailed logging is enabled.
     */
    public static boolean isDetailedLoggingEnabled() {
        loadConfig();
        return Boolean.parseBoolean(properties.getProperty("enable_detailed_logging", "true"));
    }
    
    /**
     * Get whether file logging is enabled.
     */
    public static boolean isLogToFileEnabled() {
        loadConfig();
        return Boolean.parseBoolean(properties.getProperty("log_to_file", "true"));
    }
    
    /**
     * Get the log file directory.
     */
    public static String getLogFileDirectory() {
        loadConfig();
        return properties.getProperty("log_file_directory", "logs/");
    }
    
    /**
     * Get the safe data range in days.
     */
    public static int getSafeDataRangeDays() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("safe_data_range_days", "3"));
        } catch (NumberFormatException e) {
            return 3; // Default: 3 days
        }
    }
    
    /**
     * Get the initial data start date for the first run.
     */
    public static String getInitialDataStartDate() {
        loadConfig();
        return properties.getProperty("initial_data_start_date", "2024-01-01");
    }
    
    /**
     * Check whether last-run tracking is enabled.
     */
    public static boolean isLastRunTrackingEnabled() {
        loadConfig();
        return Boolean.parseBoolean(properties.getProperty("enable_last_run_tracking", "true"));
    }
    
    /**
     * Check whether timestamp validation is enabled.
     */
    public static boolean isTimestampValidationEnabled() {
        loadConfig();
        return Boolean.parseBoolean(properties.getProperty("enable_timestamp_validation", "true"));
    }
    
    /**
     * Get the duplicate detection window in minutes.
     */
    public static int getDuplicateWindowMinutes() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("duplicate_window_minutes", "5"));
        } catch (NumberFormatException e) {
            return 5; // Default: 5 minutes
        }
    }
    
    /**
     * Get the minimum data age in days.
     */
    public static int getMinimumDataAgeDays() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("minimum_data_age_days", "365"));
        } catch (NumberFormatException e) {
            return 365; // Default: 1 year
        }
    }
    
    /**
     * Get the maximum number of future days allowed.
     */
    public static int getMaximumFutureDays() {
        loadConfig();
        try {
            return Integer.parseInt(properties.getProperty("maximum_future_days", "1"));
        } catch (NumberFormatException e) {
            return 1; // Default: 1 day
        }
    }
    
    /**
     * Print all configuration values.
     */
    public static void printConfiguration() {
        loadConfig();
        System.out.println("\n=== CONFIGURATION ===");
        System.out.println("Daily run time: " + getDailyRunTime());
        System.out.println("Repeat interval: " + getRepeatIntervalMinutes() + " minutes (" + getRepeatIntervalHours() + " hours)");
        System.out.println("Initial delay: " + getInitialDelayMinutes() + " minutes (" + getInitialDelayHours() + " hours)");
        System.out.println("Plant ID: " + getPlantId());
        System.out.print("Monitor IDs: ");
        int[] monitorIds = getMonitorIds();
        for (int i = 0; i < monitorIds.length; i++) {
            System.out.print(monitorIds[i]);
            if (i < monitorIds.length - 1) System.out.print(", ");
        }
        System.out.println();
        System.out.println("Safe data range: " + getSafeDataRangeDays() + " days");
        System.out.println("Initial data start date: " + getInitialDataStartDate());
        System.out.println("Last-run tracking: " + (isLastRunTrackingEnabled() ? "Enabled" : "Disabled"));
        System.out.println("Timestamp validation: " + (isTimestampValidationEnabled() ? "Enabled" : "Disabled"));
        System.out.println("Duplicate window: " + getDuplicateWindowMinutes() + " minutes");
        System.out.println("Minimum data age: " + getMinimumDataAgeDays() + " days");
        System.out.println("Maximum future days: " + getMaximumFutureDays() + " days");
        System.out.println("Output directory: " + getOutputDirectory());
        System.out.println("File prefix: " + getJsonFilePrefix());
        System.out.println("Timestamped files: " + (createTimestampedFiles() ? "Yes" : "No"));
        System.out.println("Detailed logging: " + (isDetailedLoggingEnabled() ? "Yes" : "No"));
        System.out.println("=====================\n");
    }
}
