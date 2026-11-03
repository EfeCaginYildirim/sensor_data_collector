package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WindowPlannerTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00.750Z");
    final WindowPlanner planner = new WindowPlanner(LocalDate.of(2025, 3, 1), Duration.ofDays(3));

    @Test
    void withoutWatermarkStartsAtTheInitialDateAndEndsNowInWholeSeconds() {
        TimeWindow w = planner.plan(Optional.empty(), NOW).orElseThrow();
        assertEquals(Instant.parse("2025-03-01T00:00:00Z"), w.start());
        assertEquals(Instant.parse("2025-06-01T10:00:00Z"), w.end());
    }

    @Test
    void withWatermarkRestartsOneOverlapBeforeIt() {
        TimeWindow w = planner.plan(Optional.of(Instant.parse("2025-05-31T10:00:00Z")), NOW).orElseThrow();
        assertEquals(Instant.parse("2025-05-28T10:00:00Z"), w.start());
    }

    @Test
    void longDowntimeIsCaughtUpWithoutAGap() {
        // Regression for the legacy "last N days" range: 10 days offline must not lose 7 of them.
        Instant lastCollected = NOW.minus(Duration.ofDays(10));
        TimeWindow w = planner.plan(Optional.of(lastCollected), NOW).orElseThrow();
        assertFalse(w.start().isAfter(lastCollected));
    }

    @Test
    void neverStartsBeforeTheInitialDate() {
        TimeWindow w = planner.plan(Optional.of(Instant.parse("2025-03-02T00:00:00Z")), NOW).orElseThrow();
        assertEquals(Instant.parse("2025-03-01T00:00:00Z"), w.start());
    }

    @Test
    void nothingToDoWhenUpToDateWithoutOverlap() {
        WindowPlanner noOverlap = new WindowPlanner(LocalDate.of(2025, 3, 1), Duration.ZERO);
        assertTrue(noOverlap.plan(Optional.of(Instant.parse("2025-06-01T10:00:00Z")), NOW).isEmpty());
        assertTrue(noOverlap.plan(Optional.empty(), Instant.parse("2025-02-01T00:00:00Z")).isEmpty(), "initial date in the future");
    }

    @Test
    void rejectsNegativeOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new WindowPlanner(LocalDate.of(2025, 1, 1), Duration.ofSeconds(-1)));
    }
}
