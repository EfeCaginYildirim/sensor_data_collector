package io.github.efecaginyildirim.collector.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * When and how long to wait before repeating a failed HTTP request.
 *
 * <p>Retries use exponential backoff with "full jitter": before attempt {@code n + 1} the client
 * waits a random time between 0 and {@code min(maxDelay, baseDelay * 2^(n-1))}. Jitter spreads
 * out retries from parallel workers so they do not hit a recovering server in lock-step.
 *
 * @param maxAttempts   total attempts including the first one; {@code 1} disables retries
 * @param baseDelay     backoff ceiling after the first failure
 * @param maxDelay      upper bound for the backoff ceiling
 * @param maxRetryAfter upper bound for honouring a server's {@code Retry-After}
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration maxRetryAfter) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(maxRetryAfter, "maxRetryAfter");
    }

    /**
     * Sensible defaults: 250 ms base, 10 s cap, {@code Retry-After} honoured up to 60 s.
     *
     * @param maxAttempts total attempts
     * @return the policy
     */
    public static RetryPolicy defaults(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(250), Duration.ofSeconds(10), Duration.ofSeconds(60));
    }

    /**
     * Whether an HTTP status is worth retrying: request timeout, rate limiting and server errors.
     * Other 4xx responses mean the request itself is wrong and will fail again.
     *
     * @param status HTTP status code
     * @return {@code true} for 408, 429 and 5xx
     */
    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /**
     * Upper bound of the backoff before the next attempt.
     *
     * @param failedAttempt number of the attempt that just failed, starting at 1
     * @return the ceiling, never above {@link #maxDelay()}
     */
    public Duration backoffCeiling(int failedAttempt) {
        int shift = Math.min(failedAttempt - 1, 30);
        Duration ceiling = baseDelay.multipliedBy(1L << shift);
        return ceiling.compareTo(maxDelay) > 0 ? maxDelay : ceiling;
    }

    private static final RandomGenerator JITTER = () -> ThreadLocalRandom.current().nextLong();

    /**
     * A random source for {@link #backoff} that is safe to share between threads and needs nothing
     * beyond {@code java.base}: every call draws from the calling thread's {@link ThreadLocalRandom}.
     * {@link RandomGenerator#getDefault()} would return one unsynchronized instance shared by all
     * concurrent requests, and loads its algorithm from the {@code jdk.random} module, which the
     * trimmed runtime of the desktop app does not contain.
     *
     * @return the shared jitter source
     */
    public static RandomGenerator jitter() {
        return JITTER;
    }

    /**
     * Randomized delay before the next attempt.
     *
     * @param failedAttempt number of the attempt that just failed, starting at 1
     * @param random        random source
     * @return a delay in {@code [0, backoffCeiling(failedAttempt)]}
     */
    public Duration backoff(int failedAttempt, RandomGenerator random) {
        long ceilingMillis = backoffCeiling(failedAttempt).toMillis();
        return Duration.ofMillis(ceilingMillis == 0 ? 0 : random.nextLong(ceilingMillis + 1));
    }

    /**
     * Delay requested by a {@code Retry-After} header, capped at {@link #maxRetryAfter()}.
     * Only the delta-seconds form is supported; HTTP dates fall back to normal backoff.
     *
     * @param headerValue raw header value, may be {@code null}
     * @return the delay, or empty if absent or not a number of seconds
     */
    public Optional<Duration> retryAfter(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(headerValue.strip());
            if (seconds < 0) {
                return Optional.empty();
            }
            Duration d = Duration.ofSeconds(seconds);
            return Optional.of(d.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : d);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
