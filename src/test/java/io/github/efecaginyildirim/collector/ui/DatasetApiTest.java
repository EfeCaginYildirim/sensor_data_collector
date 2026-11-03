package io.github.efecaginyildirim.collector.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Data tab's API: previews, chart series, CSV export, manual exclusions and presets. */
class DatasetApiTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-01T10:00:00Z"), ZoneOffset.UTC);
    static final String DAILY = "{\"monitorIds\":[1001,1004],\"features\":[\"KURTACCLX\",\"BPFO\"],\"sampling\":\"daily\"}";

    @TempDir
    Path dir;
    CollectorService service;
    UiServer server;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    String cookie;

    @BeforeEach
    void start() throws Exception {
        service = new CollectorService(dir, CLOCK);
        server = UiServer.start(service, 0, () -> { });
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(server.launchUri()).build(), HttpResponse.BodyHandlers.ofString());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        assertEquals(CollectorService.StartResult.STARTED, service.startRun());
        CollectorServiceTest.awaitIdle(service);
    }

    @AfterEach
    void stop() {
        server.close();
        service.close();
    }

    @Test
    void previewReturnsFirstRowsAndCountsForEveryStep() throws Exception {
        HttpResponse<String> r = post("/api/dataset/preview", "{\"spec\":" + DAILY + ",\"limit\":50}");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());

        assertEquals(List.of("time_utc", "monitor_id", "KURTACCLX", "BPFO"), texts(body.get("columns")));
        assertEquals(50, body.get("rows").size());
        assertTrue(body.get("truncated").asBoolean());
        JsonNode stats = body.get("stats");
        assertEquals(2 * 362, stats.get("read").asLong(), "two monitors, 90 days of 6-hourly samples");
        assertEquals(2 * 91, stats.get("afterSampling").asLong(), "one row per monitor and day");
        assertEquals(182, stats.get("written").asLong());
        assertEquals("2025-03-03T00:00:00Z", body.at("/rows/0/0").asText());
        assertEquals(1001, body.at("/rows/0/1").asInt());
    }

    @Test
    void rulesAndTheChangeFilterShowUpInTheCounts() throws Exception {
        String spec = "{\"monitorIds\":[1001],\"features\":[\"BPFO\"],\"rules\":[{\"feature\":\"KURTACCLX\",\"op\":\">\",\"value\":8}],"
            + "\"changeFilter\":{\"mode\":\"percent\",\"threshold\":10}}";
        JsonNode stats = JSON.readTree(post("/api/dataset/preview", "{\"spec\":" + spec + "}").body()).get("stats");

        assertTrue(stats.get("excludedByRules").asLong() > 0, stats.toString());
        assertTrue(stats.get("droppedUnchanged").asLong() > 0, stats.toString());
        assertEquals(stats.get("afterSampling").asLong() - stats.get("droppedUnchanged").asLong(), stats.get("written").asLong());
    }

    @Test
    void exportStreamsCsvInTheChosenFormatMatchingThePreview() throws Exception {
        long written = JSON.readTree(post("/api/dataset/preview", "{\"spec\":" + DAILY + "}").body()).at("/stats/written").asLong();

        HttpResponse<String> csv = post("/api/dataset/export",
            "{\"spec\":" + DAILY + ",\"format\":{\"separator\":\"semicolon\",\"decimal\":\",\"}}");

        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
        assertTrue(csv.headers().firstValue("Content-Disposition").orElseThrow().matches("attachment; filename=\"dataset-\\d{8}-\\d{4}\\.csv\""));
        List<String> lines = csv.body().lines().toList();
        assertEquals("time_utc;monitor_id;KURTACCLX;BPFO", lines.get(0));
        assertEquals(written + 1, lines.size());
        assertTrue(lines.get(1).matches("2025-03-03 00:00:00;1001;\\d+,\\d+;\\d+,\\d+"), lines.get(1));

        HttpResponse<String> standard = post("/api/dataset/export", "{\"spec\":" + DAILY + "}");
        assertTrue(standard.body().lines().skip(1).findFirst().orElseThrow().matches("2025-03-03 00:00:00,1001,\\d+\\.\\d+,\\d+\\.\\d+"));
        assertEquals(400, post("/api/dataset/export", "{\"spec\":" + DAILY + ",\"format\":{\"separator\":\",\",\"decimal\":\",\"}}").statusCode());
    }

    @Test
    void seriesFollowsTheFiltersForOneSelectedMonitor() throws Exception {
        JsonNode s = JSON.readTree(post("/api/dataset/series", "{\"spec\":" + DAILY + ",\"monitorId\":1004,\"points\":40}").body());
        assertEquals(List.of("KURTACCLX", "BPFO"), texts(s.get("features")));
        assertEquals(91, s.get("sourceRows").asInt(), "daily rows, before downsampling");
        assertTrue(s.get("times").size() <= 40);
        assertEquals(400, post("/api/dataset/series", "{\"spec\":" + DAILY + ",\"monitorId\":1002}").statusCode(), "not selected");
    }

    @Test
    void theChartKeepsExactlyTheRowsTheExportKeeps() throws Exception {
        // the change filter looks at all four columns, the chart plots only one of them
        String spec = "{\"monitorIds\":[1001],\"features\":[\"KURTACCLX\",\"BPFO\",\"RPM\",\"LOAD\"],"
            + "\"changeFilter\":{\"mode\":\"percent\",\"threshold\":15}}";
        long exported = post("/api/dataset/export", "{\"spec\":" + spec + "}").body().lines().count() - 1;

        JsonNode s = JSON.readTree(post("/api/dataset/series", "{\"spec\":" + spec + ",\"monitorId\":1001,\"plot\":[\"LOAD\"],\"points\":5000}").body());

        assertEquals(List.of("LOAD"), texts(s.get("features")));
        assertEquals(exported, s.get("sourceRows").asLong());
        assertEquals(400, post("/api/dataset/series", "{\"spec\":" + spec + ",\"monitorId\":1001,\"plot\":[\"VRMSX\"]}").statusCode());
    }

    @Test
    void manualExclusionsAreCreatedAppliedAndRemoved() throws Exception {
        HttpResponse<String> created = post("/api/exclusions",
            "{\"monitorId\":1001,\"from\":\"2025-04-01T00:00:00Z\",\"to\":\"2025-04-08T00:00:00Z\",\"reason\":\"Bearing replaced\"}");
        assertEquals(201, created.statusCode(), created.body());
        long id = JSON.readTree(created.body()).get("id").asLong();

        assertEquals(1, JSON.readTree(get("/api/exclusions?monitors=1001").body()).size());
        assertEquals(0, JSON.readTree(get("/api/exclusions?monitors=1004").body()).size());
        JsonNode stats = JSON.readTree(post("/api/dataset/preview", "{\"spec\":" + DAILY + "}").body()).get("stats");
        assertEquals(7 * 4, stats.get("excludedManually").asLong(), "seven days of 6-hourly samples");
        String ignoring = DAILY.replace("}", ",\"applyManualExclusions\":false}");
        assertEquals(0, JSON.readTree(post("/api/dataset/preview", "{\"spec\":" + ignoring + "}").body()).at("/stats/excludedManually").asLong());

        assertEquals(200, delete("/api/exclusions/" + id).statusCode());
        assertEquals(404, delete("/api/exclusions/" + id).statusCode());
    }

    @Test
    void invalidExclusionsAreRejected() throws Exception {
        assertEquals(400, post("/api/exclusions", "{\"monitorId\":1001,\"from\":\"2025-04-08T00:00:00Z\",\"to\":\"2025-04-01T00:00:00Z\",\"reason\":\"x\"}").statusCode());
        assertEquals(400, post("/api/exclusions", "{\"monitorId\":1001,\"from\":\"2025-04-01T00:00:00Z\",\"to\":\"2025-04-02T00:00:00Z\",\"reason\":\" \"}").statusCode());
        HttpResponse<String> unknown = post("/api/exclusions", "{\"monitorId\":999,\"from\":\"2025-04-01T00:00:00Z\",\"to\":\"2025-04-02T00:00:00Z\",\"reason\":\"x\"}");
        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("999"));
    }

    @Test
    void presetsRoundTripTheWholeSpec() throws Exception {
        String spec = "{\"monitorIds\":[1004],\"from\":\"2025-04-01\",\"to\":\"2025-05-01\",\"features\":[\"RPM\",\"LOAD\"],"
            + "\"rules\":[{\"feature\":\"IDLE\",\"op\":\">=\",\"value\":0.5}],\"dropIncomplete\":true,\"sampling\":\"hourly\","
            + "\"changeFilter\":{\"mode\":\"absolute\",\"threshold\":2.5},\"applyManualExclusions\":false}";
        assertEquals(200, post("/api/presets", "{\"name\":\"Pump 2 for MATLAB\",\"spec\":" + spec + "}").statusCode());

        JsonNode presets = JSON.readTree(get("/api/presets").body());
        assertEquals(1, presets.size());
        assertEquals("Pump 2 for MATLAB", presets.get(0).get("name").asText());
        assertEquals(JSON.readTree(spec), presets.get(0).get("spec"), "nothing is lost on the way");

        assertEquals(200, delete("/api/presets?name=Pump%202%20for%20MATLAB").statusCode());
        assertEquals(404, delete("/api/presets?name=Pump%202%20for%20MATLAB").statusCode());
        assertEquals(400, post("/api/presets", "{\"name\":\"\",\"spec\":" + spec + "}").statusCode());
    }

    @Test
    void invalidSpecsGetClearMessages() throws Exception {
        assertError("{\"spec\":{\"monitorIds\":[],\"features\":[\"RPM\"]}}", "select at least one monitor");
        assertError("{\"spec\":{\"monitorIds\":[1001],\"features\":[\"NOPE\"]}}", "unknown column NOPE");
        assertError("{\"spec\":{\"monitorIds\":[1001],\"features\":[\"RPM\"],\"from\":\"yesterday\"}}", "from must be a date");
        assertError("{\"spec\":{\"monitorIds\":[1001],\"features\":[\"RPM\"],\"sampling\":\"weekly\"}}", "sampling has an unknown value");
        assertError("{\"spec\":{\"monitorIds\":[1001],\"features\":[\"RPM\"],\"rules\":[{\"feature\":\"RPM\",\"op\":\"==\",\"value\":1}]}}",
            "unknown comparison");
        assertError("{}", "spec must be a JSON object");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    void assertError(String body, String fragment) throws Exception {
        HttpResponse<String> r = post("/api/dataset/preview", body);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains(fragment), r.body());
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(server.baseUri().toString().replaceAll("/$", "") + path))
            .header("Cookie", cookie).timeout(Duration.ofSeconds(30));
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete(String path) throws Exception {
        return http.send(request(path).header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
