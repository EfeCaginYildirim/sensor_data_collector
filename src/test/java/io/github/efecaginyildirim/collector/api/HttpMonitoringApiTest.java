package io.github.efecaginyildirim.collector.api;

import io.github.efecaginyildirim.collector.api.MonitoringApi.ApiPage;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer.Fault;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet.Options;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class HttpMonitoringApiTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00Z");
    static final TimeWindow LAST_3_DAYS = new TimeWindow(NOW.minus(Duration.ofDays(3)), NOW);

    FakeMonitoringServer server;
    final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    FakeMonitoringServer start(Options options) throws Exception {
        server = FakeMonitoringServer.start(SyntheticFleet.create(1, List.of(101, 102, 103), options),
            Clock.fixed(NOW, ZoneOffset.UTC), "demo", "pw");
        return server;
    }

    HttpMonitoringApi client(URI base, String password, Duration requestTimeout) {
        return new HttpMonitoringApi(base, "demo", password, HttpClient.newHttpClient(), requestTimeout,
            RetryPolicy.defaults(3), sleeps::add, RandomGenerator.of("L64X128MixRandom"));
    }

    HttpMonitoringApi client() {
        return client(server.baseUri(), "pw", Duration.ofSeconds(5));
    }

    // ─── Happy path ──────────────────────────────────────────────────────────────

    @Test
    void fetchesThePlantHierarchy() throws Exception {
        start(Options.defaults(1, NOW));
        Plant plant = client().fetchPlant(1);

        assertEquals(1, plant.id());
        assertEquals("Demo Plant", plant.name());
        MonitorInfo m = plant.locate(102).orElseThrow().monitor();
        assertEquals("Bearing NDE", m.name());
        assertEquals(100, m.status().healthScore());
        assertEquals(2.8, m.status().velocityX().operational());
        assertNotNull(m.status().velocityX().value());
        assertEquals(1, server.requestCount("/login"));
    }

    @Test
    void sendsWindowAsUtcSecondsAndRoundsTheEndUp() throws Exception {
        start(Options.defaults(1, NOW));
        TimeWindow w = new TimeWindow(Instant.parse("2025-05-30T00:00:00.400Z"), Instant.parse("2025-05-31T00:00:00.400Z"));
        client().fetchBasicFeatures(101, w);

        FakeMonitoringServer.RecordedRequest r = server.requests().get(server.requests().size() - 1);
        assertEquals("/trend-history", r.path());
        assertEquals("101", r.query().get("monitorId"));
        assertEquals("2025-05-30T00:00:00Z", r.query().get("from"));
        assertEquals("2025-05-31T00:00:01Z", r.query().get("to"));
        assertEquals("day", r.query().get("intervalUnit"));
        assertEquals("Bearing DE", client().fetchPlant(1).locate(101).orElseThrow().monitor().name());
    }

    @Test
    void parsesBothTrendHistoryShapesIdentically() throws Exception {
        start(Options.defaults(1, NOW));
        HttpMonitoringApi api = client();
        ApiPage<BasicFeature> current = api.fetchBasicFeatures(101, LAST_3_DAYS);
        server.useLegacyTrendHistoryShape(true);
        ApiPage<BasicFeature> legacy = api.fetchBasicFeatures(101, LAST_3_DAYS);

        assertEquals(3, current.items().size());
        assertEquals(current, legacy);
        assertTrue(current.items().get(0).jsonAvg().contains("velocityX"));
    }

    @Test
    void filtersToTheHalfOpenWindowAndRemovesDuplicates() throws Exception {
        start(new Options(1, NOW, Duration.ofHours(6), 0.0, 1.0)); // every sample sent twice
        Instant start = Instant.parse("2025-05-31T00:00:00Z");
        TimeWindow w = new TimeWindow(start, start.plus(Duration.ofHours(12))); // grid: 00, 06, 12 (12 excluded)

        ApiPage<ComputedFeatureSample> page = client().fetchComputedFeatures(101, w);

        assertEquals(List.of(start, start.plus(Duration.ofHours(6))),
            page.items().stream().map(ComputedFeatureSample::time).toList());
        assertEquals(2, page.duplicates(), "the in-window duplicates are counted; the boundary sample is just out of window");
        assertEquals(0, page.rejected());
    }

    @Test
    void missingValuesStayMissingInsteadOfBecomingZero() throws Exception {
        start(new Options(1, NOW, Duration.ofHours(6), 0.5, 0.0));
        List<ComputedFeatureSample> samples = client().fetchComputedFeatures(101, LAST_3_DAYS).items();

        assertTrue(samples.stream().anyMatch(s -> s.value(ComputedFeature.RPM).isEmpty()));
        assertTrue(samples.stream().allMatch(s -> s.value(ComputedFeature.RPM).isEmpty()
            || s.value(ComputedFeature.RPM).getAsDouble() > 1000));
    }

    // ─── Retry semantics ─────────────────────────────────────────────────────────

    @Test
    void clientErrorsAreNotRetried() throws Exception {
        start(Options.defaults(1, NOW));
        HttpMonitoringApi api = client();
        api.fetchPlant(1);
        server.resetRecording();

        ApiException e = assertThrows(ApiException.class, () -> api.fetchPlant(2)); // 404
        assertEquals(404, e.statusCode());
        assertFalse(e.retryable());
        assertEquals(1, e.attempts());
        assertEquals(1, server.requestCount("/plants/2"), "regression: the legacy client sent this 3 times");
        assertTrue(sleeps.isEmpty());

        server.inject("/trend-history", Fault.status(400), 1);
        assertEquals(400, assertThrows(ApiException.class, () -> api.fetchBasicFeatures(101, LAST_3_DAYS)).statusCode());
        assertEquals(1, server.requestCount("/trend-history"));
    }

    @Test
    void serverErrorsAreRetriedWithGrowingBackoff() throws Exception {
        start(Options.defaults(1, NOW));
        HttpMonitoringApi api = client();
        api.fetchPlant(1);
        server.resetRecording();
        server.inject("/plants/1", Fault.status(503), 2);

        assertEquals(1, api.fetchPlant(1).id());
        assertEquals(3, server.requestCount("/plants/1"));
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0).compareTo(Duration.ofMillis(250)) <= 0, "first backoff ceiling is 250 ms: " + sleeps);
        assertTrue(sleeps.get(1).compareTo(Duration.ofMillis(500)) <= 0, "second backoff ceiling is 500 ms: " + sleeps);
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        start(Options.defaults(1, NOW));
        server.inject("/trend/computed-features", Fault.status(502), -1);

        ApiException e = assertThrows(ApiException.class, () -> client().fetchComputedFeatures(101, LAST_3_DAYS));
        assertEquals(502, e.statusCode());
        assertTrue(e.retryable());
        assertEquals(3, e.attempts());
        assertEquals(3, server.requestCount("/trend/computed-features"));
    }

    @Test
    void tooManyRequestsWaitsForRetryAfterButNotForever() throws Exception {
        start(Options.defaults(1, NOW));
        server.inject("/plants/1", Fault.tooManyRequests(7), 1);
        server.inject("/plants/1", Fault.tooManyRequests(3600), 1);

        client().fetchPlant(1);
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(60)), sleeps);
    }

    @Test
    void transportFailuresAreRetried() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HttpMonitoringApi api = client(URI.create("http://127.0.0.1:" + closedPort + "/api/v1"), "pw", Duration.ofSeconds(2));

        ApiException e = assertThrows(ApiException.class, () -> api.fetchPlant(1));
        assertEquals(0, e.statusCode());
        assertTrue(e.retryable());
        assertEquals(3, e.attempts());
        assertEquals(2, sleeps.size());
    }

    @Test
    void slowResponsesTimeOutAndAreRetried() throws Exception {
        start(Options.defaults(1, NOW));
        server.inject("/plants/1", Fault.delay(Duration.ofSeconds(2)), 1);
        HttpMonitoringApi api = client(server.baseUri(), "pw", Duration.ofMillis(300));

        assertEquals(1, api.fetchPlant(1).id());
        assertEquals(2, server.requestCount("/plants/1"));
    }

    @Test
    void malformedBodiesFailFastWithAClearMessage() throws Exception {
        start(Options.defaults(1, NOW));
        server.inject("/plants/1", Fault.malformedBody(), 1);

        ApiException e = assertThrows(ApiException.class, () -> client().fetchPlant(1));
        assertEquals(0, e.statusCode());
        assertFalse(e.retryable());
        assertTrue(e.getMessage().contains("not valid JSON"), e.getMessage());
        assertEquals(1, server.requestCount("/plants/1"));
    }

    // ─── Authentication ──────────────────────────────────────────────────────────

    @Test
    void expiredTokenTriggersExactlyOneReLogin() throws Exception {
        start(Options.defaults(1, NOW));
        HttpMonitoringApi api = client();
        api.fetchPlant(1);
        server.expireTokens();

        assertEquals(1, api.fetchPlant(1).id());
        assertEquals(2, server.requestCount("/login"));
        assertTrue(sleeps.isEmpty(), "re-login is not a retry and must not back off");
    }

    @Test
    void persistent401FailsAfterOneReLogin() throws Exception {
        start(Options.defaults(1, NOW));
        server.inject("/plants/1", Fault.status(401), -1);

        ApiException e = assertThrows(ApiException.class, () -> client().fetchPlant(1));
        assertEquals(401, e.statusCode());
        assertFalse(e.retryable());
        assertEquals(2, server.requestCount("/login"));
        assertEquals(2, server.requestCount("/plants/1"));
    }

    @Test
    void badCredentialsFailWithoutRetrying() throws Exception {
        start(Options.defaults(1, NOW));
        ApiException e = assertThrows(ApiException.class,
            () -> client(server.baseUri(), "wrong", Duration.ofSeconds(5)).fetchPlant(1));
        assertEquals("/login", e.endpoint());
        assertEquals(401, e.statusCode());
        assertEquals(1, server.requestCount("/login"));
        assertEquals(0, server.requestCount("/plants/1"));
    }

    @Test
    void concurrentCallersShareOneLogin() throws Exception {
        start(Options.defaults(1, NOW));
        server.setLatency(Duration.ofMillis(50));
        HttpMonitoringApi api = client();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Plant>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> api.fetchPlant(1)));
            }
            for (Future<Plant> f : results) {
                assertEquals(1, f.get().id());
            }
            server.expireTokens();
            results.clear();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> api.fetchPlant(1)));
            }
            for (Future<Plant> f : results) {
                assertEquals(1, f.get().id());
            }
        }
        assertEquals(2, server.requestCount("/login"), "one initial login and one re-login for 40 concurrent calls");
    }
}
