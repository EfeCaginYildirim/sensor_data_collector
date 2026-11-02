import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigManager}.
 *
 * <p>These tests verify default values that are returned when no
 * {@code scheduler_config.properties} file is present on the classpath.
 * They reset the {@link ConfigManager} loaded-state reflectively before each
 * test so that a real properties file on disk does not interfere.
 */
class ConfigManagerTest {

    /**
     * Reset ConfigManager's internal state so each test starts fresh without
     * an on-disk properties file affecting results.
     */
    @BeforeEach
    void resetConfigManager() throws Exception {
        System.setProperty("config.path", "test-missing-config.properties");
        // Access the private `loaded` field and reset it to false
        java.lang.reflect.Field loadedField = ConfigManager.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        loadedField.set(null, false);

        // Clear the properties object
        java.lang.reflect.Field propsField = ConfigManager.class.getDeclaredField("properties");
        propsField.setAccessible(true);
        java.util.Properties props = (java.util.Properties) propsField.get(null);
        props.clear();
    }

    @AfterEach
    void clearConfigPath() {
        System.clearProperty("config.path");
    }

    // ─── String defaults ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDailyRunTime: returns '09:00' as default")
    void defaultDailyRunTime() {
        assertEquals("09:00", ConfigManager.getDailyRunTime());
    }

    @Test
    @DisplayName("getOutputDirectory: returns './' as default")
    void defaultOutputDirectory() {
        assertEquals("./", ConfigManager.getOutputDirectory());
    }

    @Test
    @DisplayName("getJsonFilePrefix: returns 'combined_data_' as default")
    void defaultJsonFilePrefix() {
        assertEquals("combined_data_", ConfigManager.getJsonFilePrefix());
    }

    // ─── Numeric defaults ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getRepeatIntervalHours: returns 24 as default")
    void defaultRepeatIntervalHours() {
        assertEquals(24, ConfigManager.getRepeatIntervalHours());
    }

    @Test
    @DisplayName("getRepeatIntervalMinutes: returns 1440 as default (24 * 60)")
    void defaultRepeatIntervalMinutes() {
        assertEquals(24 * 60, ConfigManager.getRepeatIntervalMinutes());
    }

    @Test
    @DisplayName("getInitialDelayHours: returns 0 as default")
    void defaultInitialDelayHours() {
        assertEquals(0, ConfigManager.getInitialDelayHours());
    }

    @Test
    @DisplayName("getInitialDelayMinutes: returns 0 as default")
    void defaultInitialDelayMinutes() {
        assertEquals(0, ConfigManager.getInitialDelayMinutes());
    }

    @Test
    @DisplayName("getPlantId: returns 0 as default")
    void defaultPlantId() {
        assertEquals(0, ConfigManager.getPlantId());
    }

    @Test
    @DisplayName("getSafeDataRangeDays: returns 3 as default")
    void defaultSafeDataRangeDays() {
        assertEquals(3, ConfigManager.getSafeDataRangeDays());
    }

    @Test
    @DisplayName("getDuplicateWindowMinutes: returns 5 as default")
    void defaultDuplicateWindowMinutes() {
        assertEquals(5, ConfigManager.getDuplicateWindowMinutes());
    }

    @Test
    @DisplayName("getMinimumDataAgeDays: returns 365 as default")
    void defaultMinimumDataAgeDays() {
        assertEquals(365, ConfigManager.getMinimumDataAgeDays());
    }

    @Test
    @DisplayName("getMaximumFutureDays: returns 1 as default")
    void defaultMaximumFutureDays() {
        assertEquals(1, ConfigManager.getMaximumFutureDays());
    }

    // ─── Boolean defaults ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("createTimestampedFiles: returns true as default")
    void defaultCreateTimestampedFiles() {
        assertTrue(ConfigManager.createTimestampedFiles());
    }

    @Test
    @DisplayName("isDetailedLoggingEnabled: returns true as default")
    void defaultDetailedLogging() {
        assertTrue(ConfigManager.isDetailedLoggingEnabled());
    }

    @Test
    @DisplayName("isLastRunTrackingEnabled: returns true as default")
    void defaultLastRunTracking() {
        assertTrue(ConfigManager.isLastRunTrackingEnabled());
    }

    @Test
    @DisplayName("isTimestampValidationEnabled: returns true as default")
    void defaultTimestampValidation() {
        assertTrue(ConfigManager.isTimestampValidationEnabled());
    }

    // ─── Array defaults ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMonitorIds: returns empty array when no IDs are configured")
    void defaultMonitorIds() {
        int[] ids = ConfigManager.getMonitorIds();
        assertNotNull(ids);
        assertEquals(0, ids.length,
            "Monitor IDs should default to an empty array when not configured");
    }

    // ─── Other defaults ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getInitialDataStartDate: returns '2024-01-01' as default")
    void defaultInitialDataStartDate() {
        assertEquals("2024-01-01", ConfigManager.getInitialDataStartDate());
    }
}
