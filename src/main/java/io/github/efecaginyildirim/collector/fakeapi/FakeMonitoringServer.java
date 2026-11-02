package io.github.efecaginyildirim.collector.fakeapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.efecaginyildirim.collector.time.Timestamps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * An in-process stand-in for the vendor monitoring API, built on the JDK's HTTP server.
 *
 * <p>It serves the endpoints described in {@code docs/API_CONTRACT.md} from a
 * {@link SyntheticFleet}, never returns samples later than its clock's "now", and lets tests
 * inject faults (status codes, {@code Retry-After}, malformed bodies, latency, token expiry)
 * that cannot be provoked on a real service. It is used both by the test suite and by the
 * application's {@code demo} mode.
 *
 * <p>Requests are handled on virtual threads, so artificial latency does not limit concurrency.
 */
public final class FakeMonitoringServer implements AutoCloseable {

    /** Path prefix under which the API is mounted, to exercise base-path handling in clients. */
    public static final String BASE_PATH = "/api/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A request as received by the server.
     *
     * @param method        HTTP method
     * @param path          path relative to {@link #BASE_PATH}, e.g. {@code /trend-history}
     * @param query         decoded query parameters
     * @param authorization value of the {@code Authorization} header, or {@code null}
     */
    public record RecordedRequest(String method, String path, Map<String, String> query, String authorization) {
        /**
         * @return the {@code monitorId} query parameter, if present and numeric
         */
        public Optional<Integer> monitorId() {
            try {
                return Optional.ofNullable(query.get("monitorId")).map(Integer::parseInt);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * A canned failure.
     *
     * @param status  HTTP status to return, or {@code 0} to answer normally after {@code delay}
     * @param headers extra response headers
     * @param body    response body
     * @param delay   delay before responding
     */
    public record Fault(int status, Map<String, String> headers, String body, Duration delay) {

        /**
         * @param status HTTP status
         * @return a fault returning {@code status} with a small JSON error body
         */
        public static Fault status(int status) {
            return new Fault(status, Map.of(), "{\"status\":false,\"message\":\"injected " + status + "\"}", Duration.ZERO);
        }

        /**
         * @param retryAfterSeconds value of the {@code Retry-After} header
         * @return a {@code 429 Too Many Requests} fault
         */
        public static Fault tooManyRequests(int retryAfterSeconds) {
            return new Fault(429, Map.of("Retry-After", String.valueOf(retryAfterSeconds)), "{\"status\":false}", Duration.ZERO);
        }

        /** @return a {@code 200} response whose body is not JSON */
        public static Fault malformedBody() {
            return new Fault(200, Map.of(), "<html>gateway error</html>", Duration.ZERO);
        }

        /**
         * @param delay how long to wait before answering normally
         * @return a fault that only adds latency
         */
        public static Fault delay(Duration delay) {
            return new Fault(0, Map.of(), "", delay);
        }
    }

    private record FaultRule(Predicate<RecordedRequest> matcher, Fault fault, AtomicInteger remaining) { }

    private final HttpServer server;
    private final ExecutorService executor;
    private final SyntheticFleet fleet;
    private final Clock clock;
    private final String username;
    private final String password;
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<FaultRule> faults = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Duration latency = Duration.ZERO;
    private volatile boolean legacyTrendHistoryShape;

    private FakeMonitoringServer(SyntheticFleet fleet, Clock clock, String username, String password,
                                 String host, int port) throws IOException {
        this.fleet = Objects.requireNonNull(fleet);
        this.clock = Objects.requireNonNull(clock);
        this.username = username;
        this.password = password;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(BASE_PATH, this::handle);
    }

    /**
     * Starts a server on a free port.
     *
     * @param fleet    data to serve
     * @param clock    the server's notion of "now"; no sample after it is ever served
     * @param username accepted API username
     * @param password accepted API password
     * @return the running server
     * @throws IOException if the socket cannot be bound
     */
    public static FakeMonitoringServer start(SyntheticFleet fleet, Clock clock, String username, String password) throws IOException {
        return start(fleet, clock, username, password, 0);
    }

    /**
     * Starts a server on a given port.
     *
     * @param fleet    data to serve
     * @param clock    the server's notion of "now"
     * @param username accepted API username
     * @param password accepted API password
     * @param port     TCP port, {@code 0} for any free port
     * @return the running server
     * @throws IOException if the socket cannot be bound
     */
    public static FakeMonitoringServer start(SyntheticFleet fleet, Clock clock, String username, String password, int port) throws IOException {
        return start(fleet, clock, username, password, "127.0.0.1", port);
    }

    /**
     * Starts a server on a given interface and port.
     *
     * @param fleet    data to serve
     * @param clock    the server's notion of "now"
     * @param username accepted API username
     * @param password accepted API password
     * @param host     interface to bind, e.g. {@code 0.0.0.0} inside a container
     * @param port     TCP port, {@code 0} for any free port
     * @return the running server
     * @throws IOException if the socket cannot be bound
     */
    public static FakeMonitoringServer start(SyntheticFleet fleet, Clock clock, String username, String password,
                                             String host, int port) throws IOException {
        FakeMonitoringServer s = new FakeMonitoringServer(fleet, clock, username, password, host, port);
        s.server.start();
        return s;
    }

    /** @return base URI to configure clients with, e.g. {@code http://127.0.0.1:54321/api/v1} */
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + BASE_PATH);
    }

    /** @return the served fleet */
    public SyntheticFleet fleet() {
        return fleet;
    }

    // ─── Test controls ───────────────────────────────────────────────────────────

    /**
     * Makes the next {@code times} requests matching {@code matcher} fail with {@code fault}.
     *
     * @param matcher which requests are affected
     * @param fault   the failure to return
     * @param times   how many matching requests to affect; {@code -1} for all of them
     * @return this server
     */
    public FakeMonitoringServer inject(Predicate<RecordedRequest> matcher, Fault fault, int times) {
        faults.add(new FaultRule(matcher, fault, new AtomicInteger(times)));
        return this;
    }

    /**
     * Shorthand for faults on one endpoint.
     *
     * @param path  endpoint path such as {@code /trend-history}
     * @param fault the failure
     * @param times number of requests to affect, {@code -1} for all
     * @return this server
     */
    public FakeMonitoringServer inject(String path, Fault fault, int times) {
        return inject(r -> r.path().equals(path), fault, times);
    }

    /** Removes all injected faults. */
    public void clearFaults() {
        faults.clear();
    }

    /**
     * Adds latency to every data request (not to {@code /login}).
     *
     * @param latency delay per request
     */
    public void setLatency(Duration latency) {
        this.latency = Objects.requireNonNull(latency);
    }

    /**
     * Serves {@code /trend-history} in the older shape where {@code data} is the array itself.
     *
     * @param legacy {@code true} for the legacy shape
     */
    public void useLegacyTrendHistoryShape(boolean legacy) {
        this.legacyTrendHistoryShape = legacy;
    }

    /** Invalidates every issued token, as if they had expired. */
    public void expireTokens() {
        validTokens.clear();
    }

    /** @return every request received so far, in arrival order */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * @param path endpoint path
     * @return number of requests received for that path
     */
    public long requestCount(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    /** @return the highest number of data requests that were being processed at the same time */
    public int maxConcurrentRequests() {
        return maxInFlight.get();
    }

    /** Forgets recorded requests and the concurrency high-water mark. */
    public void resetRecording() {
        requests.clear();
        maxInFlight.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    // ─── Request handling ────────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath().substring(BASE_PATH.length());
            RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(), path,
                parseQuery(exchange.getRequestURI().getRawQuery()),
                exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(request);

            Optional<Fault> fault = takeFault(request);
            if (fault.isPresent() && !fault.get().delay().isZero()) {
                sleep(fault.get().delay());
            }
            if (fault.isPresent() && fault.get().status() != 0) {
                Fault f = fault.get();
                f.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                send(exchange, f.status(), f.body());
                return;
            }

            if (path.equals("/login") && request.method().equals("POST")) {
                handleLogin(exchange);
                return;
            }
            if (!request.method().equals("GET")) {
                send(exchange, 405, "{\"status\":false}");
                return;
            }
            if (!isAuthorized(request.authorization())) {
                send(exchange, 401, "{\"status\":false,\"message\":\"invalid or expired token\"}");
                return;
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                if (!latency.isZero()) {
                    sleep(latency);
                }
                route(exchange, request);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private void route(HttpExchange exchange, RecordedRequest request) throws IOException {
        String path = request.path();
        if (path.equals("/plants/" + fleet.plantId())) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("status", true);
            root.set("data", fleet.plantJson(clock.instant()));
            send(exchange, 200, root.toString());
        } else if (path.startsWith("/plants/")) {
            send(exchange, 404, "{\"status\":false,\"message\":\"plant not found\"}");
        } else if (path.equals("/trend-history") || path.equals("/trend/computed-features")) {
            Optional<Integer> monitorId = request.monitorId();
            Optional<Instant> from = Timestamps.parse(request.query().get("from"));
            Optional<Instant> to = Timestamps.parse(request.query().get("to"));
            if (monitorId.isEmpty() || from.isEmpty() || to.isEmpty()) {
                send(exchange, 400, "{\"status\":false,\"message\":\"monitorId, from and to are required\"}");
                return;
            }
            if (!fleet.hasMonitor(monitorId.get())) {
                send(exchange, 404, "{\"status\":false,\"message\":\"monitor not found\"}");
                return;
            }
            Instant now = clock.instant();
            Instant end = to.get().isAfter(now) ? now : to.get();
            List<ObjectNode> items = end.isBefore(from.get()) ? List.of()
                : path.equals("/trend-history")
                    ? fleet.basicFeatures(monitorId.get(), from.get(), end)
                    : fleet.computedFeatures(monitorId.get(), from.get(), end);
            send(exchange, 200, envelope(path, items).toString());
        } else {
            send(exchange, 404, "{\"status\":false,\"message\":\"no such endpoint\"}");
        }
    }

    private JsonNode envelope(String path, List<ObjectNode> items) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", true);
        ArrayNode array = MAPPER.createArrayNode();
        items.forEach(array::add);
        if (path.equals("/trend-history") && !legacyTrendHistoryShape) {
            ObjectNode data = root.putObject("data");
            data.putArray("fingerprintData");
            data.set("basicFeatures", array);
        } else {
            root.set("data", array);
        }
        return root;
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
            body = MAPPER.readTree(in);
        } catch (IOException e) {
            send(exchange, 400, "{\"status\":false,\"message\":\"invalid JSON\"}");
            return;
        }
        if (body != null && username.equals(body.path("username").asText(null))
                && password.equals(body.path("password").asText(null))) {
            String token = "token-" + tokenCounter.incrementAndGet();
            validTokens.add(token);
            send(exchange, 200, "{\"accessToken\":\"" + token + "\"}");
        } else {
            send(exchange, 401, "{\"status\":false,\"message\":\"bad credentials\"}");
        }
    }

    private boolean isAuthorized(String header) {
        return header != null && header.startsWith("Bearer ") && validTokens.contains(header.substring(7));
    }

    private Optional<Fault> takeFault(RecordedRequest request) {
        for (FaultRule rule : faults) {
            if (rule.matcher().test(request)) {
                int left = rule.remaining().getAndUpdate(n -> n > 0 ? n - 1 : n);
                if (left != 0) {
                    return Optional.of(rule.fault());
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.put(key, value);
        }
        return query;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
