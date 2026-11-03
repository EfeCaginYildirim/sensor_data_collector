package io.github.efecaginyildirim.collector.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "1752580800,                    2025-07-15T12:00:00Z",   // epoch seconds
        "1752580800000,                 2025-07-15T12:00:00Z",   // epoch millis
        "1752580800123,                 2025-07-15T12:00:00.123Z",
        "0,                             1970-01-01T00:00:00Z",
        "2025-07-15T12:00:00Z,          2025-07-15T12:00:00Z",
        "2025-07-15T12:00:00.000Z,      2025-07-15T12:00:00Z",
        "2025-07-15T15:00:00+03:00,     2025-07-15T12:00:00Z",   // offset (failed on Java 11 Instant.parse)
        "2025-07-15T12:00:00.5-01:30,   2025-07-15T13:30:00.5Z",
        "2025-07-15T12:00:00,           2025-07-15T12:00:00Z",   // zone-less → UTC
        "2025-07-15 12:00:00,           2025-07-15T12:00:00Z",   // space separator
        "2025-07-15 12:00:00.250,       2025-07-15T12:00:00.250Z",
        "'  2025-07-15T12:00:00Z  ',    2025-07-15T12:00:00Z",   // surrounding whitespace
    })
    void parsesSupportedFormats(String raw, String expected) {
        assertEquals(Instant.parse(expected), Timestamps.parse(raw).orElseThrow());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "null", "NULL", "not-a-timestamp", "2025-13-01T00:00:00Z",
                            "2025-07-15", "12:00:00", "-5", "1234567890123456789012", "١٢٣"})
    void rejectsEverythingElseWithoutThrowing(String raw) {
        assertTrue(Timestamps.parse(raw).isEmpty(), () -> "expected empty for '" + raw + "'");
    }

    @Test
    void epochUnitIsDecidedByMagnitudeNotDigitCount() {
        // 11 digits: the old code treated anything that was not exactly 10 digits as milliseconds
        assertEquals(Instant.ofEpochSecond(99_999_999_999L), Timestamps.parse("99999999999").orElseThrow());
        assertEquals(Instant.ofEpochMilli(100_000_000_000L), Timestamps.parse("100000000000").orElseThrow());
    }

    @Test
    void formatsForApiInUtcWithoutFraction() {
        assertEquals("2025-07-15T12:00:00Z", Timestamps.formatForApi(Instant.parse("2025-07-15T12:00:00.987Z")));
    }
}
