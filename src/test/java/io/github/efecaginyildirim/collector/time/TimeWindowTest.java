package io.github.efecaginyildirim.collector.time;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void rejectsEmptyOrReversedWindows() {
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(T0, T0));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(T0.plusSeconds(1), T0));
    }

    @Test
    void isHalfOpen() {
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(10));
        assertTrue(w.contains(T0));
        assertTrue(w.contains(T0.plusSeconds(9)));
        assertFalse(w.contains(T0.plusSeconds(10)));
        assertFalse(w.contains(T0.minusNanos(1)));
    }

    @Test
    void splitProducesContiguousChunksCoveringTheWholeWindow() {
        TimeWindow w = new TimeWindow(T0, T0.plus(Duration.ofDays(65)).plusSeconds(7));
        List<TimeWindow> chunks = w.split(Duration.ofDays(30));

        assertEquals(3, chunks.size());
        assertEquals(w.start(), chunks.get(0).start());
        assertEquals(w.end(), chunks.get(chunks.size() - 1).end());
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).end(), chunks.get(i).start(), "chunks must be contiguous");
        }
        assertTrue(chunks.stream().allMatch(c -> c.length().compareTo(Duration.ofDays(30)) <= 0));
    }

    @Test
    void splitOfShortWindowIsTheWindowItself() {
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(5));
        assertEquals(List.of(w), w.split(Duration.ofDays(1)));
    }

    @Test
    void splitRejectsNonPositiveLength() {
        TimeWindow w = new TimeWindow(T0, T0.plusSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> w.split(Duration.ZERO));
    }
}
