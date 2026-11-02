package io.github.efecaginyildirim.collector.fakeapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer.Fault;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class FakeMonitoringServerTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    FakeMonitoringServer server;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        SyntheticFleet fleet = SyntheticFleet.create(1, List.of(101, 102), Options.defaults(1, NOW));
        server = FakeMonitoringServer.start(fleet, Clock.fixed(NOW, ZoneOffset.UTC), "demo", "demo-pw");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void loginIssuesTokensOnlyForValidCredentials() throws Exception {
        assertEquals(401, login("demo", "wrong").statusCode());
        HttpResponse<String> ok = login("demo", "demo-pw");
        assertEquals(200, ok.statusCode());
        assertTrue(JSON.readTree(ok.body()).get("accessToken").asText().startsWith("token-"));
    }

    @Test
    void dataEndpointsRequireAValidToken() throws Exception {
        assertEquals(401, get("/plants/1", null).statusCode());
        assertEquals(401, get("/plants/1", "made-up").statusCode());
        String token = token();
        assertEquals(200, get("/plants/1", token).statusCode());

        server.expireTokens();
        assertEquals(401, get("/plants/1", token).statusCode());
    }

    @Test
    void servesBothTrendHistoryShapes() throws Exception {
        String token = token();
        String q = query(101, NOW.minus(Duration.ofDays(2)), NOW);
        JsonNode current = JSON.readTree(get("/trend-history" + q, token).body());
        assertTrue(current.at("/data/basicFeatures").isArray());
        assertEquals(2, current.at("/data/basicFeatures").size());

        server.useLegacyTrendHistoryShape(true);
        JsonNode legacy = JSON.readTree(get("/trend-history" + q, token).body());
        assertTrue(legacy.get("data").isArray());
        assertEquals(current.at("/data/basicFeatures"), legacy.get("data"));
    }

    @Test
    void neverServesSamplesFromTheFuture() throws Exception {
        JsonNode body = JSON.readTree(get("/trend/computed-features"
            + query(101, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5))), token()).body());
        JsonNode data = body.get("data");
        String last = data.get(data.size() - 1).get("timestamp").asText();
        assertFalse(Instant.parse(last).isAfter(NOW), last);
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String token = token();
        assertEquals(400, get("/trend-history?monitorId=101", token).statusCode());
        assertEquals(404, get("/trend-history" + query(999, NOW.minusSeconds(60), NOW), token).statusCode());
        assertEquals(404, get("/plants/2", token).statusCode());
        assertEquals(404, get("/nope", token).statusCode());
    }

    @Test
    void injectedFaultsAreAppliedTheRequestedNumberOfTimes() throws Exception {
        String token = token();
        server.inject("/plants/1", Fault.status(503), 2);
        assertEquals(503, get("/plants/1", token).statusCode());
        assertEquals(503, get("/plants/1", token).statusCode());
        assertEquals(200, get("/plants/1", token).statusCode());

        server.inject("/plants/1", Fault.tooManyRequests(7), 1);
        HttpResponse<String> limited = get("/plants/1", token);
        assertEquals(429, limited.statusCode());
        assertEquals("7", limited.headers().firstValue("Retry-After").orElseThrow());

        server.inject(r -> r.monitorId().orElse(0) == 102, Fault.status(500), -1);
        for (int i = 0; i < 3; i++) {
            assertEquals(500, get("/trend-history" + query(102, NOW.minusSeconds(60), NOW), token).statusCode());
        }
        assertEquals(200, get("/trend-history" + query(101, NOW.minusSeconds(60), NOW), token).statusCode());
        assertEquals(4, server.requestCount("/trend-history"));
    }

    @Test
    void malformedBodyFault() throws Exception {
        server.inject("/plants/1", Fault.malformedBody(), 1);
        HttpResponse<String> r = get("/plants/1", token());
        assertEquals(200, r.statusCode());
        assertThrows(Exception.class, () -> JSON.readTree(r.body()));
    }

    @Test
    void latencyDoesNotSerializeConcurrentRequests() throws Exception {
        String token = token();
        server.setLatency(Duration.ofMillis(300));
        long start = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> calls = java.util.stream.IntStream.range(0, 6)
            .mapToObj(i -> http.sendAsync(request("/plants/1", token).build(), HttpResponse.BodyHandlers.ofString()))
            .toList();
        calls.forEach(CompletableFuture::join);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(server.maxConcurrentRequests() >= 2, "max in flight " + server.maxConcurrentRequests());
        assertTrue(elapsed.compareTo(Duration.ofMillis(6 * 300)) < 0, "elapsed " + elapsed);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private String token() throws Exception {
        return JSON.readTree(login("demo", "demo-pw").body()).get("accessToken").asText();
    }

    private HttpResponse<String> login(String user, String password) throws Exception {
        String body = JSON.createObjectNode().put("username", user).put("password", password).toString();
        HttpRequest req = HttpRequest.newBuilder(URI.create(server.baseUri() + "/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pathAndQuery, String token) throws Exception {
        return http.send(request(pathAndQuery, token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String pathAndQuery, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUri() + pathAndQuery)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private static String query(int monitorId, Instant from, Instant to) {
        return "?monitorId=" + monitorId
            + "&from=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
            + "&to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
    }
}
