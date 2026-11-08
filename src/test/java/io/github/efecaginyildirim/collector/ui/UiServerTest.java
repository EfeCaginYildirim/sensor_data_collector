package io.github.efecaginyildirim.collector.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UiServerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-01T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;
    CollectorService service;
    UiServer server;
    final CountDownLatch shutdownRequested = new CountDownLatch(1);
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    String cookie;

    @BeforeEach
    void start() throws Exception {
        service = new CollectorService(dir, CLOCK);
        server = UiServer.start(service, 0, shutdownRequested::countDown);
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(server.launchUri()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(303, login.statusCode());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @AfterEach
    void stop() {
        server.close();
        service.close();
    }

    // ─── Session and security ────────────────────────────────────────────────────

    @Test
    void launchLinkIsExchangedForAStrictHttpOnlyCookie() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(server.launchUri()).build(), HttpResponse.BodyHandlers.ofString());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertEquals("/", r.headers().firstValue("Location").orElseThrow(), "the token does not stay in the address bar");
        assertTrue(server.launchUri().toString().startsWith("http://127.0.0.1:"));
        assertTrue(server.launchUri().getQuery().length() > 40, "256-bit token");
    }

    @Test
    void wrongTokensAndMissingSessionsAreRejected() throws Exception {
        assertEquals(403, get(URI.create(server.baseUri() + "?token=guess"), null).statusCode());
        assertEquals(401, get("/api/status", null).statusCode());
        assertEquals(401, get("/api/status", "sdc_session=guess").statusCode());
        assertEquals(200, get("/api/status", cookie).statusCode());
    }

    @Test
    void foreignHostHeadersAreRefusedToPreventDnsRebinding() throws Exception {
        assertEquals(403, rawStatus("GET /api/status HTTP/1.1", "evil.example:" + server.baseUri().getPort()));
        assertEquals(403, rawStatus("GET / HTTP/1.1", "evil.example"));
        assertEquals(200, rawStatus("GET / HTTP/1.1", "localhost:" + server.baseUri().getPort()));
    }

    @Test
    void stateChangingRequestsMustBeSameOriginJson() throws Exception {
        HttpRequest form = request("/api/runs", cookie).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("a=b")).build();
        assertEquals(415, http.send(form, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest crossSite = request("/api/settings", cookie).header("Content-Type", "application/json")
            .header("Origin", "https://evil.example").PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, http.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest sameSite = request("/api/settings", cookie).header("Content-Type", "application/json")
            .header("Origin", "http://127.0.0.1:" + server.baseUri().getPort()).PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(200, http.send(sameSite, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void staticFilesAreServedWithAStrictContentSecurityPolicy() throws Exception {
        HttpResponse<String> index = get(server.baseUri(), null);
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertEquals(UiServer.CSP, index.headers().firstValue("Content-Security-Policy").orElseThrow());
        assertEquals("nosniff", index.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(index.body().contains("Sensor Data Collector"));

        assertEquals(200, get("/app.js", null).statusCode());
        assertEquals(200, get("/i18n.js", null).statusCode());
        assertEquals(404, get("/nope.js", null).statusCode());
        assertEquals(404, get("/settings.properties", null).statusCode(), "only known file types are served");
        assertEquals(404, rawStatus("GET /vendor/../../../etc/passwd HTTP/1.1", "127.0.0.1:" + server.baseUri().getPort()));
    }

    // ─── API ─────────────────────────────────────────────────────────────────────

    @Test
    void statusDescribesTheApp() throws Exception {
        JsonNode s = json(get("/api/status", cookie));
        assertEquals("demo", s.get("mode").asText());
        assertEquals("dev", s.get("version").asText());
        assertFalse(s.get("running").asBoolean());
        assertTrue(s.get("nextRun").isNull());
        assertEquals("en", s.get("language").asText());
        assertEquals(29, json(get("/api/features", cookie)).size());
    }

    @Test
    void collectAndReadThroughTheApi() throws Exception {
        HttpResponse<String> started = post("/api/runs", "");
        assertEquals(202, started.statusCode(), started.body());
        assertEquals(409, post("/api/runs", "").statusCode(), "second run is refused while the first runs");

        JsonNode progress = awaitIdle();
        assertEquals("SUCCESS", progress.get("lastStatus").asText(), progress.toString());
        assertEquals(progress.get("chunksTotal").asInt(), progress.get("chunksDone").asInt());
        assertEquals(6, progress.get("monitors").size());

        JsonNode monitors = json(get("/api/monitors", cookie));
        assertEquals(6, monitors.size());
        JsonNode first = monitors.get(0);
        assertEquals(1001, first.get("monitorId").asInt());
        assertEquals("2025-06-01T10:00:00Z", first.get("collectedUntil").asText());
        assertTrue(first.at("/status/healthScore").isInt());
        assertEquals(2.8, first.at("/status/velocityX/operational").asDouble());

        JsonNode series = json(get("/api/monitors/1001/series?features=kurtacclx,BPFO&points=20", cookie));
        assertEquals("KURTACCLX", series.at("/features/0").asText());
        assertTrue(series.get("times").size() <= 20);
        assertEquals(series.get("times").size(), series.at("/values/1").size());
        assertEquals(400, get("/api/monitors/1001/series?features=NOPE", cookie).statusCode());
        assertEquals(400, get("/api/monitors/1001/series?points=1", cookie).statusCode());

        assertEquals(1, json(get("/api/monitors/1001/history", cookie)).size());
        JsonNode runs = json(get("/api/runs", cookie));
        assertEquals(1, runs.size());
        assertEquals("SUCCESS", runs.get(0).get("status").asText());
    }

    @Test
    void settingsNeverExposeThePassword() throws Exception {
        JsonNode s = json(get("/api/settings", cookie));
        assertEquals("demo", s.get("ui.mode").asText());
        assertEquals("false", s.get("api.password-set").asText());

        HttpResponse<String> bad = put("/api/settings", "{\"ui.mode\":\"api\"}");
        assertEquals(400, bad.statusCode());
        assertTrue(json(bad).get("problems").toString().contains("api.base-url is required"), bad.body());

        HttpResponse<String> ok = put("/api/settings", "{\"ui.mode\":\"api\",\"api.base-url\":\"http://127.0.0.1:9/api\","
            + "\"api.username\":\"u\",\"api.password\":\"top-secret\",\"collection.plant-id\":1,"
            + "\"collection.monitor-ids\":\"5,6\",\"collection.initial-start-date\":\"2025-01-01\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertFalse(ok.body().contains("top-secret"));
        assertEquals("true", json(ok).get("api.password-set").asText());
        assertFalse(get("/api/settings", cookie).body().contains("top-secret"));
    }

    @Test
    void theLanguageIsSavedAndReportedInTheStatus() throws Exception {
        HttpResponse<String> ok = put("/api/language", "{\"language\":\"fr\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("fr", json(ok).get("language").asText());
        assertEquals("fr", json(get("/api/status", cookie)).get("language").asText());

        HttpResponse<String> bad = put("/api/language", "{\"language\":\"de\"}");
        assertEquals(400, bad.statusCode());
        assertTrue(json(bad).get("problems").toString().contains("ui.language"), bad.body());
        assertEquals(400, put("/api/language", "{}").statusCode());
    }

    @Test
    void connectionCanBeTestedWithoutSaving() throws Exception {
        JsonNode r = json(post("/api/settings/test", "{}"));
        assertTrue(r.get("ok").asBoolean(), r.toString());
        assertEquals(6, r.get("found").size());
    }

    @Test
    void malformedRequestsGetClearErrors() throws Exception {
        assertEquals(404, get("/api/nope", cookie).statusCode());
        assertEquals(400, put("/api/settings", "{not json").statusCode());
        assertEquals(400, put("/api/settings", "[1,2]").statusCode());
        assertEquals(400, put("/api/settings", "{\"ui.mode\":{\"nested\":1}}").statusCode());
        assertEquals(413, put("/api/settings", "{\"x\":\"" + "a".repeat(70_000) + "\"}").statusCode());
        assertEquals(400, put("/api/settings", "{\"db.url\":\"x\"}").statusCode(), "unknown keys");
    }

    @Test
    void theUiCanAskTheAppToQuit() throws Exception {
        assertEquals(202, post("/api/shutdown", "").statusCode());
        assertTrue(shutdownRequested.await(2, TimeUnit.SECONDS));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    JsonNode awaitIdle() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode p = json(get("/api/progress", cookie));
            if (!p.get("running").asBoolean() && !p.get("lastStatus").isNull() && !json(get("/api/status", cookie)).get("running").asBoolean()) {
                return p;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run did not finish");
    }

    HttpRequest.Builder request(String path, String cookieHeader) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUri().toString().replaceAll("/$", "") + path));
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        return b;
    }

    HttpResponse<String> get(String path, String cookieHeader) throws Exception {
        return http.send(request(path, cookieHeader).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(URI uri, String cookieHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(request(path, cookie).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(request(path, cookie).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    /** Sends a request with an arbitrary Host header and path, which HttpClient would not allow. */
    int rawStatus(String requestLine, String host) throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.baseUri().getPort())) {
            OutputStream out = s.getOutputStream();
            out.write((requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }
}
