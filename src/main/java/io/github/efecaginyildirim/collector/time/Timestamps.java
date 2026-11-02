package io.github.efecaginyildirim.collector.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Optional;

/**
 * Parses the timestamp formats seen in vendor API responses into {@link Instant}s.
 *
 * <p>Supported inputs, tried in this order:
 * <ol>
 *   <li>Unix epoch as digits — values below 10<sup>11</sup> are seconds, larger values
 *       milliseconds (decided by magnitude, not by digit count);</li>
 *   <li>ISO-8601 with {@code Z} or a numeric offset, e.g. {@code 2025-07-15T12:00:00.000+03:00};</li>
 *   <li>ISO-8601 local date-time with {@code T} or a space separator, optional fraction —
 *       interpreted as UTC, which is what the vendor documents for zone-less values.</li>
 * </ol>
 * Anything else yields {@link Optional#empty()}; parsing never throws.
 */
public final class Timestamps {

    /** Epoch values at or above this are milliseconds; below are seconds (≈ year 5138 as seconds). */
    static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private Timestamps() { }

    /**
     * Parses a timestamp.
     *
     * @param raw text from the API; may be {@code null}
     * @return the instant, or empty when {@code raw} is {@code null}, blank, {@code "null"} or unparseable
     */
    public static Optional<Instant> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return Optional.empty();
        }
        if (isDigits(s)) {
            return parseEpoch(s);
        }
        try {
            return Optional.of(OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (DateTimeParseException ignored) {
            // not an offset date-time; try local
        }
        try {
            String iso = s.length() > 10 && s.charAt(10) == ' ' ? s.substring(0, 10) + 'T' + s.substring(11) : s;
            return Optional.of(LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Formats an instant the way the vendor API expects query parameters:
     * {@code yyyy-MM-dd'T'HH:mm:ss'Z'} in UTC, without fractional seconds.
     *
     * @param instant instant to format
     * @return formatted text
     */
    public static String formatForApi(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.with(ChronoField.NANO_OF_SECOND, 0));
    }

    private static Optional<Instant> parseEpoch(String digits) {
        if (digits.length() > 18) {
            return Optional.empty();
        }
        long value = Long.parseLong(digits);
        return Optional.of(value < MILLIS_THRESHOLD ? Instant.ofEpochSecond(value) : Instant.ofEpochMilli(value));
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
