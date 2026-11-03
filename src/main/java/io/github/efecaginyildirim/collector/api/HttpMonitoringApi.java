package io.github.efecaginyildirim.collector.api;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.efecaginyildirim.collector.config.AppConfig.ApiConfig;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import io.github.efecaginyildirim.collector.time.Timestamps;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * {@link MonitoringApi} over HTTP using the JDK {@link HttpClient}.
 *
 * <h2>Failure handling</h2>
 * <ul>
 *   <li>Transport errors, timeouts, {@code 408}, {@code 429} and {@code 5xx} are retried
 *       according to the {@link RetryPolicy}; {@code 429} waits for {@code Retry-After} when given.</li>
 *   <li>Other {@code 4xx} responses fail immediately — repeating a bad request cannot help.</li>
 *   <li>A {@code 401} on a data request is treated as an expired token: the client logs in again
 *       once and repeats the request. This does not consume a retry attempt.</li>
 *   <li>A body that is not valid JSON fails immediately with status {@code 0}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Instances are safe for concurrent use. Logins are serialized so that many workers hitting an
 * expired token at once trigger a single re-login.
 */
public final class HttpMonitoringApi implements MonitoringApi {

    /** Pauses the calling thread; replaced in tests to avoid real waiting. */
    @FunctionalInterface
    public interface Sleeper {
        /**
         * @param duration how long to pause
         * @throws InterruptedException if interrupted while waiting
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final System.Logger LOG = System.getLogger(HttpMonitoringApi.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI baseUri;
    private final String username;
    private final String password;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final ReentrantLock loginLock = new ReentrantLock();
    private volatile String token;

    /**
     * Creates a client.
     *
     * @param baseUri        API base URI without trailing slash
     * @param username       API username
     * @param password       API password
     * @param http           HTTP client to use (shared, thread-safe)
     * @param requestTimeout timeout of one HTTP request
     * @param retryPolicy    retry behaviour
     * @param sleeper        how to wait between attempts
     * @param random         jitter source
     */
    public HttpMonitoringApi(URI baseUri, String username, String password, HttpClient http,
                             Duration requestTimeout, RetryPolicy retryPolicy, Sleeper sleeper, RandomGenerator random) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.http = Objects.requireNonNull(http, "http");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Creates a production client from configuration.
     *
     * @param config API configuration
     * @return the client
     */
    public static HttpMonitoringApi create(ApiConfig config) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return new HttpMonitoringApi(config.baseUri(), config.username(), config.password(), http,
            config.requestTimeout(), RetryPolicy.defaults(config.maxAttempts()), Thread::sleep,
            RetryPolicy.jitter());
    }

    @Override
    public Plant fetchPlant(int plantId) {
        String path = "/plants/" + plantId;
        return parse(path, getAuthenticated(path, path), ApiJsonMapper::plant);
    }

    @Override
    public ApiPage<BasicFeature> fetchBasicFeatures(int monitorId, TimeWindow window) {
        String endpoint = "/trend-history";
        String body = getAuthenticated(endpoint, endpoint + windowQuery(monitorId, window) + "&interval=1&intervalUnit=day");
        return parse(endpoint, body, root -> ApiJsonMapper.basicFeatures(root, window));
    }

    @Override
    public ApiPage<ComputedFeatureSample> fetchComputedFeatures(int monitorId, TimeWindow window) {
        String endpoint = "/trend/computed-features";
        String body = getAuthenticated(endpoint, endpoint + windowQuery(monitorId, window));
        return parse(endpoint, body, root -> ApiJsonMapper.computedFeatures(root, window));
    }

    /**
     * Query string for a window. The API's bounds are inclusive and second-precision, so the end
     * is rounded up; anything past the window's exclusive end is filtered out after parsing.
     */
    static String windowQuery(int monitorId, TimeWindow window) {
        Instant end = window.end();
        Instant roundedEnd = end.getNano() == 0 ? end : end.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        return "?monitorId=" + monitorId
            + "&from=" + URLEncoder.encode(Timestamps.formatForApi(window.start()), StandardCharsets.UTF_8)
            + "&to=" + URLEncoder.encode(Timestamps.formatForApi(roundedEnd), StandardCharsets.UTF_8);
    }

    // ─── Authentication ──────────────────────────────────────────────────────────

    private String currentToken() {
        String t = token;
        return t != null ? t : login(null);
    }

    /**
     * Logs in unless another thread already replaced {@code staleToken}.
     *
     * @param staleToken the token that was rejected, or {@code null} if there was none
     * @return a fresh token
     */
    private String login(String staleToken) {
        loginLock.lock();
        try {
            String t = token;
            if (t != null && !t.equals(staleToken)) {
                return t;
            }
            String body = JSON.createObjectNode().put("username", username).put("password", password).toString();
            String response = execute("/login", () -> HttpRequest.newBuilder(uri("/login"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), false);
            String fresh = parse("/login", response, root -> {
                JsonNode tokenNode = root.get("accessToken");
                if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asText().isBlank()) {
                    throw new IllegalArgumentException("login response has no accessToken");
                }
                return tokenNode.asText();
            });
            token = fresh;
            return fresh;
        } finally {
            loginLock.unlock();
        }
    }

    private String getAuthenticated(String endpoint, String pathAndQuery) {
        return execute(endpoint, () -> HttpRequest.newBuilder(uri(pathAndQuery))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + currentToken())
            .GET()
            .build(), true);
    }

    // ─── Retry loop ──────────────────────────────────────────────────────────────

    /** Builds a fresh request per attempt so a re-login is picked up. */
    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest build();
    }

    private String execute(String endpoint, RequestFactory factory, boolean authenticated) {
        boolean reauthenticated = false;
        int attempt = 1;
        while (true) {
            HttpRequest request = factory.build();
            Duration wait;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status == 401 && authenticated && !reauthenticated) {
                    reauthenticated = true;
                    String rejected = request.headers().firstValue("Authorization").map(h -> h.substring(7)).orElse(null);
                    LOG.log(System.Logger.Level.INFO, "{0}: token rejected, logging in again", endpoint);
                    login(rejected);
                    continue;
                }
                boolean retryable = RetryPolicy.isRetryableStatus(status);
                if (!retryable || attempt >= retryPolicy.maxAttempts()) {
                    throw new ApiException(endpoint, status, retryable, attempt,
                        "HTTP " + status + " " + abbreviate(response.body()), null);
                }
                Duration delay = status == 429
                    ? retryPolicy.retryAfter(response.headers().firstValue("Retry-After").orElse(null))
                        .orElseGet(() -> retryPolicy.backoff(1, random))
                    : retryPolicy.backoff(attempt, random);
                String context = endpoint + ": HTTP " + status + " on attempt " + attemptText(attempt);
                LOG.log(System.Logger.Level.WARNING, () -> context + ", retrying in " + delay.toMillis() + " ms");
                wait = delay;
            } catch (IOException e) {
                if (attempt >= retryPolicy.maxAttempts()) {
                    throw new ApiException(endpoint, 0, true, attempt, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
                Duration delay = retryPolicy.backoff(attempt, random);
                String context = endpoint + ": " + e.getClass().getSimpleName() + " on attempt " + attemptText(attempt);
                LOG.log(System.Logger.Level.WARNING, () -> context + ", retrying in " + delay.toMillis() + " ms");
                wait = delay;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(endpoint, 0, false, attempt, "interrupted", e);
            }
            pause(endpoint, attempt, wait);
            attempt++;
        }
    }

    private String attemptText(int attempt) {
        return attempt + "/" + retryPolicy.maxAttempts();
    }

    private void pause(String endpoint, int attempt, Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(endpoint, 0, false, attempt, "interrupted while waiting to retry", e);
        }
    }

    private static <T> T parse(String endpoint, String body, Function<JsonNode, T> mapper) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || root.isMissingNode()) {
                throw new ApiException(endpoint, 0, false, 1, "empty response body", null);
            }
            return mapper.apply(root);
        } catch (JacksonException e) {
            throw new ApiException(endpoint, 0, false, 1, "response is not valid JSON: " + abbreviate(body), e);
        } catch (IllegalArgumentException e) {
            throw new ApiException(endpoint, 0, false, 1, "unexpected response: " + e.getMessage(), e);
        }
    }

    private URI uri(String pathAndQuery) {
        return URI.create(baseUri + pathAndQuery);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
