package io.github.efecaginyildirim.collector.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A half-open interval of time {@code [start, end)} in UTC.
 *
 * @param start inclusive start
 * @param end   exclusive end; strictly after {@code start}
 */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start " + start + " must be before end " + end);
        }
    }

    /**
     * Checks whether an instant lies inside this window.
     *
     * @param instant the instant to test
     * @return {@code true} if {@code start <= instant < end}
     */
    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /** @return the length of the window */
    public Duration length() {
        return Duration.between(start, end);
    }

    /**
     * Splits the window into consecutive chunks of at most {@code maxLength}.
     *
     * <p>The chunks are contiguous and in chronological order: the first starts at
     * {@link #start()}, each next one starts where the previous ended, and the last ends at
     * {@link #end()}. Only the last chunk may be shorter than {@code maxLength}.
     *
     * @param maxLength maximum chunk length, must be positive
     * @return at least one chunk
     */
    public List<TimeWindow> split(Duration maxLength) {
        if (maxLength.isNegative() || maxLength.isZero()) {
            throw new IllegalArgumentException("maxLength must be positive: " + maxLength);
        }
        List<TimeWindow> chunks = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(maxLength);
            Instant chunkEnd = next.isBefore(end) ? next : end;
            chunks.add(new TimeWindow(cursor, chunkEnd));
            cursor = chunkEnd;
        }
        return List.copyOf(chunks);
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
