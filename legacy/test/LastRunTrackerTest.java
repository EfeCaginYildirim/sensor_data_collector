import org.junit.jupiter.api.*;
import java.io.File;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LastRunTracker}.
 */
class LastRunTrackerTest {

    /** Delete the tracking file before and after each test so they are isolated. */
    @BeforeEach
    @AfterEach
    void cleanTrackingFile() {
        File f = new File("last_run_timestamp.txt");
        if (f.exists()) f.delete();
    }

    // ─── isFirstRun ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isFirstRun: returns true when tracking file does not exist")
    void isFirstRun_whenFileAbsent() {
        assertTrue(LastRunTracker.isFirstRun(),
            "Should report first run when no tracking file is present");
    }

    @Test
    @DisplayName("isFirstRun: returns false after updateLastRunTime is called")
    void isFirstRun_afterUpdate() {
        LastRunTracker.updateLastRunTime(LocalDateTime.now().minusHours(1));
        assertFalse(LastRunTracker.isFirstRun(),
            "Should not report first run after the tracking file is written");
    }

    // ─── getLastRunTime ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getLastRunTime: returns null when file does not exist")
    void getLastRunTime_fileAbsent() {
        assertNull(LastRunTracker.getLastRunTime(),
            "Should return null when no tracking file is present");
    }

    @Test
    @DisplayName("getLastRunTime: round-trips a saved timestamp correctly")
    void getLastRunTime_roundTrip() {
        LocalDateTime original = LocalDateTime.of(2025, 7, 15, 12, 0, 0);
        LastRunTracker.updateLastRunTime(original);

        LocalDateTime read = LastRunTracker.getLastRunTime();
        assertNotNull(read);
        // Compare only second-level precision (the format is yyyy-MM-dd HH:mm:ss)
        assertEquals(original.withNano(0), read,
            "Read timestamp should equal the saved timestamp (up to second precision)");
    }

    // ─── calculateSmartDateRange ──────────────────────────────────────────────────

    @Test
    @DisplayName("calculateSmartDateRange: returns two non-null, non-empty date strings")
    void smartDateRange_returnsTwoStrings() {
        String[] range = LastRunTracker.calculateSmartDateRange();
        assertNotNull(range);
        assertEquals(2, range.length, "Date range should always have exactly 2 elements");
        assertNotNull(range[0], "Start date should not be null");
        assertNotNull(range[1], "End date should not be null");
        assertFalse(range[0].isEmpty(), "Start date should not be empty");
        assertFalse(range[1].isEmpty(), "End date should not be empty");
    }

    @Test
    @DisplayName("calculateSmartDateRange: start date is before end date")
    void smartDateRange_startBeforeEnd() {
        String[] range = LastRunTracker.calculateSmartDateRange();
        // Decode percent-encoded colons for comparison
        String start = range[0].replace("%3A", ":");
        String end   = range[1].replace("%3A", ":");

        // Simple lexicographic comparison works for ISO-8601 strings
        assertTrue(start.compareTo(end) < 0,
            "Start date should be lexicographically before end date");
    }

    // ─── resetFirstRunFlag ────────────────────────────────────────────────────────

    @Test
    @DisplayName("resetFirstRunFlag: deletes the tracking file")
    void resetFirstRunFlag_deletesFile() {
        LastRunTracker.updateLastRunTime(LocalDateTime.now());
        assertFalse(LastRunTracker.isFirstRun(), "Precondition: file should exist");

        LastRunTracker.resetFirstRunFlag();

        assertTrue(LastRunTracker.isFirstRun(), "After reset, should report first run again");
    }
}
