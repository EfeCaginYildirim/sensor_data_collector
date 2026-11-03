package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which time window to collect for a monitor.
 *
 * <p>The window is {@code [max(watermark - overlap, initialStart), now)}; without a watermark it
 * starts at {@code initialStart}. Because it is anchored on the <em>watermark</em> rather than on
 * "the last N days", any amount of downtime is caught up without gaps. The overlap re-fetches
 * recent data so late-arriving or corrected samples are picked up; saving is idempotent, so the
 * re-fetched part costs bandwidth but never creates duplicates.
 */
public final class WindowPlanner {

    private final Instant initialStart;
    private final Duration overlap;

    /**
     * @param initialStartDate first UTC day to collect for a monitor without a watermark
     * @param overlap          how far before the watermark to restart; not negative
     */
    public WindowPlanner(LocalDate initialStartDate, Duration overlap) {
        this.initialStart = initialStartDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        this.overlap = Objects.requireNonNull(overlap, "overlap");
        if (overlap.isNegative()) {
            throw new IllegalArgumentException("overlap must not be negative");
        }
    }

    /**
     * Plans the next window.
     *
     * @param watermark exclusive end of the data already collected without gaps, if any
     * @param now       current time; the window ends here (truncated to whole seconds)
     * @return the window, or empty if there is nothing to collect
     */
    public Optional<TimeWindow> plan(Optional<Instant> watermark, Instant now) {
        Instant end = now.truncatedTo(ChronoUnit.SECONDS);
        Instant start = watermark.map(w -> w.minus(overlap)).orElse(initialStart);
        if (start.isBefore(initialStart)) {
            start = initialStart;
        }
        return start.isBefore(end) ? Optional.of(new TimeWindow(start, end)) : Optional.empty();
    }
}
