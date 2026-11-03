package io.github.efecaginyildirim.collector.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void jitterIsSharedSafelyByConcurrentRequests() throws Exception {
        RetryPolicy policy = RetryPolicy.defaults(3);
        Duration ceiling = policy.backoffCeiling(3);
        List<Future<long[]>> results = new ArrayList<>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < 32; t++) {
                results.add(threads.submit(() -> {
                    long min = Long.MAX_VALUE;
                    long max = Long.MIN_VALUE;
                    for (int i = 0; i < 2_000; i++) {
                        long ms = policy.backoff(3, RetryPolicy.jitter()).toMillis();
                        min = Math.min(min, ms);
                        max = Math.max(max, ms);
                    }
                    return new long[] {min, max};
                }));
            }
        }
        for (Future<long[]> r : results) {
            long[] range = r.get();
            assertTrue(range[0] >= 0 && range[1] <= ceiling.toMillis(), "within [0, ceiling]");
            assertTrue(range[1] - range[0] > ceiling.toMillis() / 2, "spread over the range, not stuck");
        }
    }

    @Test
    void jitterNeedsOnlyJavaBase() {
        assertSame(Object.class.getModule(), java.util.concurrent.ThreadLocalRandom.class.getModule());
        assertDoesNotThrow(() -> RetryPolicy.jitter().nextDouble());
    }
}
