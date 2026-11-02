import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared HTTP client for the external monitoring API. Java's HttpClient manages persistent
 * connections internally, so a single client is safer than pooling one-shot
 * HttpURLConnection instances.
 */
public final class HttpConnectionPool {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 3;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private HttpConnectionPool() { }

    public static String sendPostRequest(String endpoint, String jsonData) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonData))
            .build();
        return send(request);
    }

    public static String sendGetRequest(String endpoint, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return send(request);
    }

    private static URI apiUri(String endpoint) {
        return URI.create(ConfigManager.getApiBaseUrl() + endpoint);
    }

    private static String send(HttpRequest request) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                if (status < 500 || attempt == MAX_ATTEMPTS) {
                    throw new IOException("HTTP " + status + ": " + abbreviate(response.body()));
                }
                lastFailure = new IOException("HTTP " + status + ": " + abbreviate(response.body()));
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) break;
            }
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        throw lastFailure == null ? new IOException("HTTP request failed") : lastFailure;
    }

    private static String abbreviate(String body) {
        if (body == null) return "";
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }

    /** Retained for existing diagnostics; HttpClient owns its connections. */
    public static void printPoolStatus() {
        System.out.println("HTTP client: shared persistent client active");
    }

    public static void shutdown() { }
}
