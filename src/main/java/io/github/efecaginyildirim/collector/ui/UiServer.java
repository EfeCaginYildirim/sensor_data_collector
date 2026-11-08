package io.github.efecaginyildirim.collector.ui;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.efecaginyildirim.collector.dataset.CsvWriter;
import io.github.efecaginyildirim.collector.dataset.DatasetPipeline;
import io.github.efecaginyildirim.collector.dataset.DatasetSpec;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.MonitorInfo.Reading;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.MonitorSummary;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.StatusSnapshot;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;
import io.github.efecaginyildirim.collector.persistence.RunHistory.RunRecord;
import io.github.efecaginyildirim.collector.persistence.SampleExclusion;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import io.github.efecaginyildirim.collector.time.Timestamps;
import io.github.efecaginyildirim.collector.ui.CollectorService.ConnectionCheck;
import io.github.efecaginyildirim.collector.ui.CollectorService.StartResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The local web server of the desktop app: static UI files plus a small JSON API over
 * {@link CollectorService}, built on the JDK's HTTP server.
 *
 * <h2>Security model</h2>
 * The server is only meant for the person who launched it, so it defends against other local
 * processes and against web pages open in the same browser:
 * <ul>
 *   <li>binds to {@code 127.0.0.1} only;</li>
 *   <li>a random 256-bit token is generated per launch and passed in the URL the app opens; the
 *       first request exchanges it for an {@code HttpOnly}, {@code SameSite=Strict} session cookie
 *       and redirects to a clean URL; every API call requires that cookie;</li>
 *   <li>requests whose {@code Host} header is not this server are refused (DNS rebinding);</li>
 *   <li>state-changing requests must be JSON and, when an {@code Origin} is sent, same-origin —
 *       a cross-site form cannot produce such a request without a CORS preflight, which this
 *       server never grants;</li>
 *   <li>a strict Content-Security-Policy allows only the app's own scripts and styles.</li>
 * </ul>
 */
public final class UiServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UiServer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COOKIE = "sdc_session";
    private static final int MAX_BODY = 64 * 1024;
    private static final Pattern MONITOR_PATH = Pattern.compile("^/api/monitors/(\\d{1,9})/(history|series)$");
    private static final Pattern EXCLUSION_PATH = Pattern.compile("^/api/exclusions/(\\d{1,18})$");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
        "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final HttpServer server;
    private final ExecutorService executor;
    private final CollectorService service;
    private final Runnable onShutdownRequest;
    private final String token;
    private final String version;

    private UiServer(HttpServer server, CollectorService service, Runnable onShutdownRequest, String token, String version) {
        this.server = server;
        this.service = service;
        this.onShutdownRequest = onShutdownRequest;
        this.token = token;
        this.version = version;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /**
     * Starts the server on the loopback interface.
     *
     * @param service           backend
     * @param port              TCP port, {@code 0} for any free port
     * @param onShutdownRequest called (on a separate thread) when the UI asks the app to quit
     * @return the running server
     * @throws IOException if the port cannot be bound
     */
    public static UiServer start(CollectorService service, int port, Runnable onShutdownRequest) throws IOException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String version = Optional.ofNullable(UiServer.class.getPackage().getImplementationVersion()).orElse("dev");
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        UiServer ui = new UiServer(http, Objects.requireNonNull(service), Objects.requireNonNull(onShutdownRequest), token, version);
        http.start();
        return ui;
    }

    /** @return the server's base URI, e.g. {@code http://127.0.0.1:53817/} */
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** @return the URI to open in the browser; it carries the session token */
    public URI launchUri() {
        return URI.create(baseUri() + "?token=" + token);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    // ─── Request pipeline ────────────────────────────────────────────────────────

    /** An error that maps to an HTTP status. */
    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            try {
                checkHost(ex);
                String path = ex.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    handleApi(ex, path);
                } else {
                    handleStatic(ex, path);
                }
            } catch (HttpError e) {
                sendJson(ex, e.status, JSON.createObjectNode().put("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, JSON.createObjectNode().put("error", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "request failed: " + ex.getRequestURI(), e);
                sendJson(ex, 500, JSON.createObjectNode().put("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    private void checkHost(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        int port = server.getAddress().getPort();
        if (host == null || !(host.equalsIgnoreCase("127.0.0.1:" + port) || host.equalsIgnoreCase("localhost:" + port))) {
            throw new HttpError(403, "forbidden host");
        }
    }

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            throw new HttpError(405, "method not allowed");
        }
        if (path.equals("/")) {
            Optional<String> offered = queryParam(ex, "token");
            if (offered.isPresent()) {
                if (!constantTimeEquals(offered.get(), token)) {
                    throw new HttpError(403, "invalid or expired link - open the app again");
                }
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().set("Location", "/");
                ex.sendResponseHeaders(303, -1);
                return;
            }
            path = "/index.html";
        }
        if (path.contains("..") || !path.matches("^/[a-zA-Z0-9_./-]+$")) {
            throw new HttpError(404, "not found");
        }
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = CONTENT_TYPES.get(extension);
        byte[] body;
        try (InputStream in = UiServer.class.getResourceAsStream("/ui" + path)) {
            if (type == null || in == null) {
                throw new HttpError(404, "not found");
            }
            body = in.readAllBytes();
        }
        if (extension.equals("html")) {
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        send(ex, 200, type, body);
    }

    private void handleApi(HttpExchange ex, String path) throws IOException {
        if (!hasSession(ex)) {
            throw new HttpError(401, "not signed in - open the app from its launcher");
        }
        String method = ex.getRequestMethod();
        if (!method.equals("GET")) {
            checkStateChangingRequest(ex);
        }
        Matcher monitor = MONITOR_PATH.matcher(path);
        Matcher exclusion = EXCLUSION_PATH.matcher(path);
        String route = method + " " + (monitor.matches() ? "/api/monitors/{id}/" + monitor.group(2)
            : exclusion.matches() ? "/api/exclusions/{id}" : path);
        switch (route) {
            case "GET /api/status" -> sendJson(ex, 200, status());
            case "GET /api/features" -> sendJson(ex, 200, features());
            case "GET /api/monitors" -> sendJson(ex, 200, monitors());
            case "GET /api/monitors/{id}/history" -> sendJson(ex, 200,
                history(Integer.parseInt(monitor.group(1)), intParam(ex, "limit", 100, 1, 1000)));
            case "GET /api/monitors/{id}/series" -> sendJson(ex, 200,
                series(Integer.parseInt(monitor.group(1)), queryParam(ex, "features").orElse("KURTACCLX,BPFO"),
                    intParam(ex, "points", 400, 10, 5000)));
            case "GET /api/runs" -> sendJson(ex, 200, runs(intParam(ex, "limit", 20, 1, 500)));
            case "POST /api/runs" -> startRun(ex);
            case "GET /api/progress" -> sendJson(ex, 200, progress());
            case "GET /api/settings" -> sendJson(ex, 200, settings());
            case "PUT /api/settings" -> saveSettings(ex);
            case "PUT /api/language" -> saveLanguage(ex);
            case "POST /api/settings/test" -> sendJson(ex, 200, connectionCheck(service.testConnection(readStringMap(ex))));
            case "POST /api/dataset/preview" -> datasetPreview(ex);
            case "POST /api/dataset/series" -> datasetSeries(ex);
            case "POST /api/dataset/export" -> datasetExport(ex);
            case "GET /api/exclusions" -> sendJson(ex, 200, exclusions(queryParam(ex, "monitors").orElse("")));
            case "POST /api/exclusions" -> addExclusion(ex);
            case "DELETE /api/exclusions/{id}" -> {
                boolean removed = service.removeExclusion(Long.parseLong(exclusion.group(1)));
                sendJson(ex, removed ? 200 : 404, JSON.createObjectNode().put("removed", removed));
            }
            case "GET /api/presets" -> sendJson(ex, 200, presets());
            case "POST /api/presets" -> savePreset(ex);
            case "DELETE /api/presets" -> {
                boolean removed = service.deletePreset(queryParam(ex, "name").orElse(""));
                sendJson(ex, removed ? 200 : 404, JSON.createObjectNode().put("removed", removed));
            }
            case "POST /api/shutdown" -> {
                sendJson(ex, 202, JSON.createObjectNode().put("stopping", true));
                Thread.ofVirtual().start(onShutdownRequest);
            }
            default -> throw new HttpError(404, "no such endpoint: " + method + " " + path);
        }
    }

    private boolean hasSession(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return false;
        }
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String p = part.strip();
                if (p.startsWith(COOKIE + "=") && constantTimeEquals(p.substring(COOKIE.length() + 1), token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkStateChangingRequest(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        int port = server.getAddress().getPort();
        if (origin != null && !(origin.equalsIgnoreCase("http://127.0.0.1:" + port) || origin.equalsIgnoreCase("http://localhost:" + port))) {
            throw new HttpError(403, "cross-origin request rejected");
        }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new HttpError(415, "Content-Type must be application/json");
        }
    }

    // ─── Endpoints ───────────────────────────────────────────────────────────────

    private ObjectNode status() {
        CollectorService.Status s = service.status();
        ObjectNode n = JSON.createObjectNode();
        n.put("version", version);
        n.put("mode", s.mode().name().toLowerCase(Locale.ROOT));
        n.put("dataDirectory", s.dataDirectory().toAbsolutePath().toString());
        n.put("running", s.running());
        n.put("language", service.language());
        putOptional(n, "nextRun", s.nextRun().map(z -> z.toOffsetDateTime().toString()));
        putOptional(n, "nextRunZone", s.nextRun().map(z -> z.getZone().getId()));
        return n;
    }

    private static ArrayNode features() {
        ArrayNode a = JSON.createArrayNode();
        for (ComputedFeature f : ComputedFeature.values()) {
            a.add(f.apiName());
        }
        return a;
    }

    private ArrayNode monitors() {
        ArrayNode a = JSON.createArrayNode();
        for (MonitorSummary m : service.monitors()) {
            ObjectNode n = a.addObject();
            n.put("monitorId", m.monitorId());
            n.put("areaName", m.areaName());
            n.put("machineGroupName", m.machineGroupName());
            n.put("machineName", m.machineName());
            n.put("monitorName", m.monitorName());
            putOptional(n, "collectedUntil", m.collectedUntil().map(Instant::toString));
            n.put("basicSamples", m.basicSamples());
            n.put("computedSamples", m.computedSamples());
            m.latestStatus().ifPresent(s -> n.set("status", status(s)));
        }
        return a;
    }

    private static ObjectNode status(StatusSnapshot snapshot) {
        MonitorStatus s = snapshot.status();
        ObjectNode n = JSON.createObjectNode();
        n.put("collectedAt", snapshot.collectedAt().toString());
        n.put("healthScore", s.healthScore());
        n.put("status", s.status());
        n.put("alarms", s.alarms());
        n.put("speed", s.speed());
        n.set("velocityX", reading(s.velocityX()));
        n.set("velocityY", reading(s.velocityY()));
        n.set("velocityZ", reading(s.velocityZ()));
        n.set("temperature", reading(s.temperature()));
        return n;
    }

    private static ObjectNode reading(Reading r) {
        return JSON.createObjectNode().put("value", r.value()).put("operational", r.operational())
            .put("caution", r.caution()).put("warning", r.warning());
    }

    private ArrayNode history(int monitorId, int limit) {
        ArrayNode a = JSON.createArrayNode();
        for (StatusSnapshot s : service.statusHistory(monitorId, limit)) {
            a.addObject().put("collectedAt", s.collectedAt().toString()).put("healthScore", s.status().healthScore());
        }
        return a;
    }

    private ObjectNode series(int monitorId, String featureList, int points) {
        List<ComputedFeature> features = new ArrayList<>();
        for (String name : featureList.split(",")) {
            String trimmed = name.strip().toUpperCase(Locale.ROOT);
            ComputedFeature f = ComputedFeature.fromApiName(trimmed)
                .orElseThrow(() -> new IllegalArgumentException("unknown feature " + trimmed));
            if (!features.contains(f)) {
                features.add(f);
            }
        }
        if (features.isEmpty() || features.size() > 8) {
            throw new IllegalArgumentException("request between 1 and 8 features");
        }
        return seriesJson(service.series(monitorId, features, points));
    }

    private static ObjectNode seriesJson(Series s) {
        ObjectNode n = JSON.createObjectNode();
        ArrayNode names = n.putArray("features");
        s.features().forEach(f -> names.add(f.apiName()));
        ArrayNode times = n.putArray("times");
        s.times().forEach(times::add);
        ArrayNode values = n.putArray("values");
        for (List<Double> column : s.values()) {
            ArrayNode c = values.addArray();
            column.forEach(v -> {
                if (v == null) {
                    c.addNull();
                } else {
                    c.add(v);
                }
            });
        }
        n.put("sourceRows", s.sourceRows());
        return n;
    }

    private ArrayNode runs(int limit) {
        ArrayNode a = JSON.createArrayNode();
        for (RunRecord r : service.runs(limit)) {
            ObjectNode n = a.addObject();
            n.put("runId", r.runId());
            n.put("startedAt", r.startedAt().toString());
            n.put("elapsedMs", r.elapsed().toMillis());
            n.put("status", r.status());
            putOptional(n, "failure", r.failure());
            n.put("monitorsTotal", r.monitorsTotal());
            n.put("monitorsFailed", r.monitorsFailed());
            n.put("newRows", r.newRows());
            n.put("summary", r.summary());
        }
        return a;
    }

    private void startRun(HttpExchange ex) throws IOException {
        StartResult result = service.startRun();
        ObjectNode n = JSON.createObjectNode().put("result", result.name());
        switch (result) {
            case STARTED -> sendJson(ex, 202, n);
            case ALREADY_RUNNING -> sendJson(ex, 409, n.put("error", "a collection run is already in progress"));
            case INVALID_SETTINGS -> sendJson(ex, 422, n.put("error",
                service.progress().lastSummary().orElse("the settings are incomplete")));
            default -> throw new IllegalStateException(result.name());
        }
    }

    private ObjectNode progress() {
        ProgressTracker.Progress p = service.progress();
        ObjectNode n = JSON.createObjectNode();
        n.put("running", p.running());
        putOptional(n, "runId", p.runId());
        putOptional(n, "startedAt", p.startedAt().map(Instant::toString));
        n.put("chunksTotal", p.chunksTotal());
        n.put("chunksDone", p.chunksDone());
        putOptional(n, "lastStatus", p.lastStatus());
        putOptional(n, "lastSummary", p.lastSummary());
        ArrayNode monitors = n.putArray("monitors");
        for (ProgressTracker.MonitorProgress m : p.monitors()) {
            ObjectNode mn = monitors.addObject();
            mn.put("monitorId", m.monitorId());
            mn.put("state", m.state());
            mn.put("chunksTotal", m.chunksTotal());
            mn.put("chunksDone", m.chunksDone());
            mn.put("chunksFailed", m.chunksFailed());
            putOptional(mn, "outcome", m.outcome());
            putOptional(mn, "error", m.error());
        }
        return n;
    }

    private ObjectNode settings() {
        ObjectNode n = JSON.createObjectNode();
        service.settingsForDisplay().forEach(n::put);
        return n;
    }

    private void saveLanguage(HttpExchange ex) throws IOException {
        String language = readStringMap(ex).getOrDefault("language", "");
        List<String> problems = service.setLanguage(language);
        if (problems.isEmpty()) {
            sendJson(ex, 200, JSON.createObjectNode().put("saved", true).put("language", language));
        } else {
            ObjectNode n = JSON.createObjectNode().put("saved", false);
            problems.forEach(n.putArray("problems")::add);
            sendJson(ex, 400, n);
        }
    }

    private void saveSettings(HttpExchange ex) throws IOException {
        List<String> problems = service.updateSettings(readStringMap(ex));
        if (problems.isEmpty()) {
            ObjectNode n = settings();
            n.put("saved", true);
            sendJson(ex, 200, n);
        } else {
            ObjectNode n = JSON.createObjectNode().put("saved", false);
            ArrayNode list = n.putArray("problems");
            problems.forEach(list::add);
            sendJson(ex, 400, n);
        }
    }

    private static ObjectNode connectionCheck(ConnectionCheck c) {
        ObjectNode n = JSON.createObjectNode().put("ok", c.ok()).put("message", c.message());
        ArrayNode found = n.putArray("found");
        c.found().forEach(found::add);
        ArrayNode missing = n.putArray("missing");
        c.missing().forEach(missing::add);
        return n;
    }

    // ─── Datasets, exclusions, presets ──────────────────────────────────────────

    private void datasetPreview(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        DatasetSpec spec = DatasetJson.spec(body.get("spec"));
        int limit = Math.max(1, Math.min(1000, body.path("limit").asInt(100)));
        List<DatasetRow> rows = new ArrayList<>();
        DatasetPipeline.Stats stats = service.preview(spec, limit, rows);
        ObjectNode n = JSON.createObjectNode();
        ArrayNode columns = n.putArray("columns");
        columns.add("time_utc").add("monitor_id");
        spec.features().forEach(f -> columns.add(f.apiName()));
        ArrayNode data = n.putArray("rows");
        for (DatasetRow r : rows) {
            ArrayNode row = data.addArray().add(r.time().toString()).add(r.monitorId());
            r.values().forEach(v -> {
                if (v == null) {
                    row.addNull();
                } else {
                    row.add(v);
                }
            });
        }
        n.set("stats", DatasetJson.stats(stats));
        n.put("truncated", stats.written() > rows.size());
        sendJson(ex, 200, n);
    }

    private void datasetSeries(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        DatasetSpec spec = DatasetJson.spec(body.get("spec"));
        JsonNode monitorId = body.get("monitorId");
        if (monitorId == null || !monitorId.canConvertToInt()) {
            throw new IllegalArgumentException("monitorId is required");
        }
        int points = Math.max(10, Math.min(5000, body.path("points").asInt(600)));
        List<ComputedFeature> plot = body.has("plot")
            ? DatasetJson.features(body.get("plot"), "plot")
            : spec.features().subList(0, Math.min(2, spec.features().size()));
        sendJson(ex, 200, seriesJson(service.datasetSeries(spec, monitorId.asInt(), plot, points)));
    }

    private void datasetExport(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        DatasetSpec spec = DatasetJson.spec(body.get("spec"));
        CsvWriter.Format format = DatasetJson.format(body.get("format"));
        String file = "dataset-" + FILE_TIME.format(Instant.now()) + ".csv";
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file + "\"");
        ex.sendResponseHeaders(200, 0); // chunked: rows are streamed as they are produced
        try (Writer out = new BufferedWriter(new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8), 64 * 1024)) {
            service.export(spec, out, format);
        } catch (RuntimeException e) {
            // headers are already sent; the truncated download is the only possible signal
            LOG.log(System.Logger.Level.ERROR, "export failed after the response started", e);
        }
    }

    private ArrayNode exclusions(String monitorList) {
        List<Integer> ids = new ArrayList<>();
        for (String part : monitorList.split(",")) {
            if (!part.isBlank()) {
                ids.add(Integer.parseInt(part.strip()));
            }
        }
        ArrayNode a = JSON.createArrayNode();
        service.exclusions(ids).forEach(e -> a.add(exclusionJson(e)));
        return a;
    }

    private void addExclusion(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        JsonNode monitorId = body.get("monitorId");
        if (monitorId == null || !monitorId.canConvertToInt()) {
            throw new IllegalArgumentException("monitorId is required");
        }
        Instant from = Timestamps.parse(body.path("from").asText(null))
            .orElseThrow(() -> new IllegalArgumentException("from must be a timestamp"));
        Instant to = Timestamps.parse(body.path("to").asText(null))
            .orElseThrow(() -> new IllegalArgumentException("to must be a timestamp"));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("the range must end after it starts");
        }
        SampleExclusion e = service.addExclusion(monitorId.asInt(), new TimeWindow(from, to), body.path("reason").asText(""));
        sendJson(ex, 201, exclusionJson(e));
    }

    private static ObjectNode exclusionJson(SampleExclusion e) {
        return JSON.createObjectNode().put("id", e.id()).put("monitorId", e.monitorId())
            .put("from", e.window().start().toString()).put("to", e.window().end().toString())
            .put("reason", e.reason()).put("createdAt", e.createdAt().toString());
    }

    private ArrayNode presets() {
        ArrayNode a = JSON.createArrayNode();
        service.presets().forEach(p -> a.addObject().put("name", p.name()).put("savedAt", p.savedAt().toString())
            .set("spec", DatasetJson.toJson(p.spec())));
        return a;
    }

    private void savePreset(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        PresetStore.Preset p = service.savePreset(body.path("name").asText(""), DatasetJson.spec(body.get("spec")));
        sendJson(ex, 200, JSON.createObjectNode().put("name", p.name()).put("savedAt", p.savedAt().toString()));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Reads a JSON object of string values; numbers and booleans are accepted as their text. */
    private static Map<String, String> readStringMap(HttpExchange ex) throws IOException {
        JsonNode root = readJson(ex);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            JsonNode v = e.getValue();
            if (!v.isValueNode()) {
                throw new HttpError(400, e.getKey() + " must be a string, number or boolean");
            }
            out.put(e.getKey(), v.isNull() ? "" : v.asText());
        }
        return out;
    }

    /** Reads the request body as a JSON object (an empty body counts as {@code {}}). */
    private static JsonNode readJson(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            throw new HttpError(413, "request body too large");
        }
        if (body.length == 0) {
            return JSON.createObjectNode();
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new HttpError(400, "body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new HttpError(400, "body must be a JSON object");
        }
        return root;
    }

    private static Optional<String> queryParam(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return Optional.empty();
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return Optional.of(eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static int intParam(HttpExchange ex, String name, int defaultValue, int min, int max) {
        Optional<String> v = queryParam(ex, name);
        if (v.isEmpty()) {
            return defaultValue;
        }
        try {
            int i = Integer.parseInt(v.get());
            if (i < min || i > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static void putOptional(ObjectNode n, String field, Optional<String> value) {
        value.ifPresentOrElse(v -> n.put(field, v), () -> n.putNull(field));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange ex, int status, JsonNode body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
