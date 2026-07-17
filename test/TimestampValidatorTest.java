import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimestampValidator}.
 */
class TimestampValidatorTest {

    @BeforeEach
    void clearCache() {
        TimestampValidator.clearTimestampCache();
    }

    // ─── parseTimestamp ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseTimestamp: parses ISO-8601 with Z suffix")
    void parse_iso8601WithZ() {
        LocalDateTime result = TimestampValidator.parseTimestamp("2025-07-15T12:00:00Z");
        assertNotNull(result);
        assertEquals(2025, result.getYear());
        assertEquals(7,    result.getMonthValue());
        assertEquals(15,   result.getDayOfMonth());
        assertEquals(12,   result.getHour());
    }

    @Test
    @DisplayName("parseTimestamp: parses 10-digit Unix epoch (seconds)")
    void parse_unixEpochSeconds() {
        // 2025-07-15 12:00:00 UTC = 1752580800
        LocalDateTime result = TimestampValidator.parseTimestamp("1752580800");
        assertNotNull(result, "10-digit epoch should be parseable");
        assertEquals(2025, result.getYear());
    }

    @Test
    @DisplayName("parseTimestamp: parses 13-digit Unix epoch (milliseconds)")
    void parse_unixEpochMillis() {
        LocalDateTime result = TimestampValidator.parseTimestamp("1752580800000");
        assertNotNull(result, "13-digit epoch should be parseable");
        assertEquals(2025, result.getYear());
    }

    @Test
    @DisplayName("parseTimestamp: returns null for null input")
    void parse_nullInput() {
        assertNull(TimestampValidator.parseTimestamp(null));
    }

    @Test
    @DisplayName("parseTimestamp: returns null for empty string")
    void parse_emptyInput() {
        assertNull(TimestampValidator.parseTimestamp("  "));
    }

    @Test
    @DisplayName("parseTimestamp: returns null for unparseable string")
    void parse_gibberish() {
        assertNull(TimestampValidator.parseTimestamp("not-a-timestamp"));
    }

    @Test
    @DisplayName("parseTimestamp: parses space-separated datetime format")
    void parse_spaceSeparated() {
        LocalDateTime result = TimestampValidator.parseTimestamp("2025-07-15 12:00:00");
        assertNotNull(result);
        assertEquals(2025, result.getYear());
        assertEquals(7,    result.getMonthValue());
    }

    // ─── isValidTimestamp ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("isValidTimestamp: accepts a recent past timestamp")
    void valid_recentPast() {
        LocalDateTime recent = LocalDateTime.now().minusDays(7);
        assertTrue(TimestampValidator.isValidTimestamp(recent));
    }

    @Test
    @DisplayName("isValidTimestamp: rejects a timestamp in the far future (>1 day)")
    void valid_farFuture() {
        LocalDateTime future = LocalDateTime.now().plusDays(5);
        assertFalse(TimestampValidator.isValidTimestamp(future),
            "Timestamps more than 1 day in the future should be rejected");
    }

    @Test
    @DisplayName("isValidTimestamp: rejects a timestamp older than 365 days")
    void valid_staleData() {
        LocalDateTime stale = LocalDateTime.now().minusDays(400);
        assertFalse(TimestampValidator.isValidTimestamp(stale),
            "Timestamps older than 365 days should be rejected");
    }

    @Test
    @DisplayName("isValidTimestamp: returns false for null")
    void valid_null() {
        assertFalse(TimestampValidator.isValidTimestamp(null));
    }

    // ─── isDuplicateTimestamp ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isDuplicateTimestamp: first occurrence is not a duplicate")
    void duplicate_firstOccurrence() {
        LocalDateTime ts = LocalDateTime.now().minusDays(1);
        assertFalse(TimestampValidator.isDuplicateTimestamp(1, ts),
            "First occurrence should not be flagged as duplicate");
    }

    @Test
    @DisplayName("isDuplicateTimestamp: same timestamp within 1 minute is a duplicate")
    void duplicate_sameTimestamp() {
        LocalDateTime ts = LocalDateTime.now().minusDays(1);
        TimestampValidator.isDuplicateTimestamp(2, ts);          // first — registers it
        assertTrue(TimestampValidator.isDuplicateTimestamp(2, ts), // second — same ts
            "Same timestamp for the same monitor within 1 minute should be a duplicate");
    }

    @Test
    @DisplayName("isDuplicateTimestamp: different monitors share no duplicate state")
    void duplicate_differentMonitors() {
        LocalDateTime ts = LocalDateTime.now().minusDays(1);
        TimestampValidator.isDuplicateTimestamp(10, ts);
        assertFalse(TimestampValidator.isDuplicateTimestamp(20, ts),
            "Same timestamp for a different monitor should NOT be a duplicate");
    }

    @Test
    @DisplayName("isDuplicateTimestamp: returns false for null timestamp")
    void duplicate_nullTimestamp() {
        assertFalse(TimestampValidator.isDuplicateTimestamp(1, null));
    }

    // ─── clearTimestampCache ──────────────────────────────────────────────────────

    @Test
    @DisplayName("clearTimestampCache: clears registered timestamps so next call is fresh")
    void clearCache_allowsReuse() {
        LocalDateTime ts = LocalDateTime.now().minusDays(1);
        TimestampValidator.isDuplicateTimestamp(5, ts); // register
        TimestampValidator.clearTimestampCache();       // clear
        assertFalse(TimestampValidator.isDuplicateTimestamp(5, ts),
            "After clearing the cache, the same timestamp should not be a duplicate");
    }
}
