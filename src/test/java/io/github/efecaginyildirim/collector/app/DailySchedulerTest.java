package io.github.efecaginyildirim.collector.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DailySchedulerTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final LocalTime NINE = LocalTime.of(9, 0);

    @Test
    void nextRunIsTodayIfStillAheadOtherwiseTomorrow() {
        ZonedDateTime morning = ZonedDateTime.of(2025, 6, 1, 8, 59, 0, 0, PARIS);
        assertEquals(ZonedDateTime.of(2025, 6, 1, 9, 0, 0, 0, PARIS), DailyScheduler.nextRunAfter(morning, NINE));

        ZonedDateTime exactly = ZonedDateTime.of(2025, 6, 1, 9, 0, 0, 0, PARIS);
        assertEquals(ZonedDateTime.of(2025, 6, 2, 9, 0, 0, 0, PARIS), DailyScheduler.nextRunAfter(exactly, NINE));

        ZonedDateTime evening = ZonedDateTime.of(2025, 6, 1, 21, 0, 0, 0, PARIS);
        assertEquals(ZonedDateTime.of(2025, 6, 2, 9, 0, 0, 0, PARIS), DailyScheduler.nextRunAfter(evening, NINE));
    }

    @Test
    void staysAtLocalTimeAcrossDstChanges() {
        // The night of 29→30 March 2025 is 23 hours long in Paris; a fixed 24 h period would drift to 10:00.
        ZonedDateTime afterRun = ZonedDateTime.of(2025, 3, 29, 9, 0, 1, 0, PARIS);
        ZonedDateTime next = DailyScheduler.nextRunAfter(afterRun, NINE);
        assertEquals(LocalTime.of(9, 0), next.toLocalTime());
        assertEquals(ZoneOffset.ofHours(2), next.getOffset());
        assertEquals(Duration.ofHours(23).minusSeconds(1), Duration.between(afterRun, next));
    }

    @Test
    void timesInsideADstGapMoveForwardAndOverlapsUseTheEarlierInstant() {
        LocalTime halfPastTwo = LocalTime.of(2, 30);
        ZonedDateTime gap = DailyScheduler.nextRunAfter(ZonedDateTime.of(2025, 3, 30, 0, 0, 0, 0, PARIS), halfPastTwo);
        assertEquals(ZonedDateTime.of(2025, 3, 30, 3, 30, 0, 0, PARIS), gap);

        ZonedDateTime overlap = DailyScheduler.nextRunAfter(ZonedDateTime.of(2025, 10, 26, 0, 0, 0, 0, PARIS), halfPastTwo);
        assertEquals(ZoneOffset.ofHours(2), overlap.getOffset());
    }

    @Test
    @Timeout(5)
    void scheduleSurvivesExceptionsAndErrorsFromTheTask() throws Exception {
        // A clock frozen 100 ms before 09:00 UTC makes the scheduler fire again and again.
        Clock clock = Clock.fixed(Instant.parse("2025-06-01T08:59:59.900Z"), ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch thirdCall = new CountDownLatch(3);
        try (DailyScheduler s = new DailyScheduler(NINE, ZoneOffset.UTC, clock, () -> {
            int n = calls.incrementAndGet();
            thirdCall.countDown();
            if (n == 1) {
                throw new IllegalStateException("boom");
            }
            if (n == 2) {
                throw new AssertionError("an Error must not cancel the schedule either");
            }
        })) {
            s.start();
            assertTrue(thirdCall.await(3, TimeUnit.SECONDS), "runs after failures: " + calls.get());
            assertEquals(ZonedDateTime.of(2025, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC), s.nextRunTime().orElseThrow());
        }
    }

    @Test
    @Timeout(5)
    void manualAndScheduledRunsNeverOverlap() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        try (DailyScheduler s = new DailyScheduler(NINE, PARIS, Clock.systemUTC(), () -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
        })) {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                runs.add(s.runNow());
            }
            for (Future<?> f : runs) {
                f.get();
            }
        }
        assertEquals(1, maxActive.get());
    }

    @Test
    @Timeout(5)
    void closeDoesNotWaitForTomorrowsRun() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        DailyScheduler s = new DailyScheduler(LocalTime.now(ZoneOffset.UTC).minusMinutes(1), ZoneOffset.UTC,
            Clock.systemUTC(), calls::incrementAndGet);
        s.start();
        s.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        assertEquals(0, calls.get());
    }
}
