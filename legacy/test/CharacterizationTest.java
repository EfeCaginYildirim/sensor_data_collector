import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests that pin down defects in the original implementation.
 *
 * <p>These tests are expected to FAIL against the legacy code. They document the
 * behaviour that the Java 21 rewrite fixes; equivalent regression tests live in the
 * new test suite.
 */
class CharacterizationTest {

    private HttpServer server;

    @BeforeEach
    void resetConfig() throws Exception {
        Field loaded = ConfigManager.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(null, false);
        Field props = ConfigManager.class.getDeclaredField("properties");
        props.setAccessible(true);
        ((Properties) props.get(null)).clear();
    }

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        System.clearProperty("config.path");
        new java.io.File("last_run_timestamp.txt").delete();
    }

    @Test
    @DisplayName("BUG: a 401 response is retried 3 times although 4xx should not be retried")
    void clientErrorsAreNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plants/1", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();

        Path config = Files.createTempFile("cfg", ".properties");
        try (Writer w = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
            w.write("api_base_url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
        }
        System.setProperty("config.path", config.toString());

        assertThrows(IOException.class, () -> HttpConnectionPool.sendGetRequest("/plants/1", "token"));
        assertEquals(1, hits.get(), "a 401 must be attempted exactly once");
    }

    @Test
    @DisplayName("BUG: after 10 days of downtime only the last 3 days are fetched (7-day data gap)")
    void downtimeLongerThanSafeRangeLeavesNoGap() throws Exception {
        System.setProperty("config.path", "does-not-exist.properties");
        LocalDateTime lastRun = LocalDateTime.now().minusDays(10);
        LastRunTracker.updateLastRunTime(lastRun);

        String start = LastRunTracker.calculateSmartDateRange()[0].replace("%3A", ":");
        LocalDateTime requestedStart = LocalDateTime.parse(start,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        assertFalse(requestedStart.isAfter(lastRun),
            "the next window must start at or before the last successful run, but started at " + requestedStart);
    }
}
