package io.github.efecaginyildirim.collector.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    static Properties minimal() {
        Properties p = new Properties();
        p.setProperty("api.base-url", "https://api.example.com/v1/");
        p.setProperty("api.username", "user");
        p.setProperty("api.password", "secret");
        p.setProperty("collection.plant-id", "7");
        p.setProperty("collection.monitor-ids", "11, 22,33");
        p.setProperty("collection.initial-start-date", "2024-01-01");
        return p;
    }

    @Test
    void appliesDefaultsForOptionalKeys() {
        AppConfig c = ConfigLoader.fromProperties(minimal(), Map.of());

        assertEquals("https://api.example.com/v1", c.api().baseUri().toString(), "trailing slash is removed");
        assertEquals(Duration.ofSeconds(60), c.api().requestTimeout());
        assertEquals(3, c.api().maxAttempts());
        assertEquals(8, c.api().maxConcurrentRequests());
        assertEquals(7, c.collection().plantId());
        assertEquals(List.of(11, 22, 33), c.collection().monitorIds());
        assertEquals(LocalDate.of(2024, 1, 1), c.collection().initialStartDate());
        assertEquals(Duration.ofDays(3), c.collection().overlap());
        assertEquals(Duration.ofDays(30), c.collection().chunk());
        assertTrue(c.database().isEmpty());
        assertTrue(c.output().snapshotDirectory().isEmpty());
        assertEquals(LocalTime.of(9, 0), c.schedule().dailyRunTime());
        assertEquals(ZoneId.of("UTC"), c.schedule().zone());
    }

    @Test
    void environmentOverridesProperties() {
        AppConfig c = ConfigLoader.fromProperties(minimal(), Map.of(
            "SDC_API_PASSWORD", "from-env",
            "SDC_COLLECTION_MONITOR_IDS", "5",
            "SDC_SCHEDULE_ZONE", "Europe/Istanbul"));

        assertEquals("from-env", c.api().password());
        assertEquals(List.of(5), c.collection().monitorIds());
        assertEquals(ZoneId.of("Europe/Istanbul"), c.schedule().zone());
    }

    @Test
    void environmentNameMapping() {
        assertEquals("SDC_API_MAX_CONCURRENT_REQUESTS", ConfigLoader.environmentName("api.max-concurrent-requests"));
    }

    @Test
    void reportsAllProblemsAtOnce() {
        Properties p = new Properties();
        p.setProperty("api.base-url", "/relative");
        p.setProperty("api.max-attempts", "0");
        p.setProperty("collection.monitor-ids", "11,abc,-4,11");
        p.setProperty("collection.overlap", "3 days");

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.fromProperties(p, Map.of()));

        List<String> problems = e.problems();
        assertAll(
            () -> assertTrue(contains(problems, "api.base-url", "absolute http(s)")),
            () -> assertTrue(contains(problems, "api.username", "required")),
            () -> assertTrue(contains(problems, "api.password", "SDC_API_PASSWORD")),
            () -> assertTrue(contains(problems, "api.max-attempts", "positive")),
            () -> assertTrue(contains(problems, "collection.plant-id", "required")),
            () -> assertTrue(contains(problems, "collection.monitor-ids", "[abc, -4]")),
            () -> assertTrue(contains(problems, "collection.initial-start-date", "required")),
            () -> assertTrue(contains(problems, "collection.overlap", "3 days")));
        assertEquals(8, problems.size(), () -> String.join("\n", problems));
    }

    @Test
    void duplicateMonitorIdsAreRejected() {
        Properties p = minimal();
        p.setProperty("collection.monitor-ids", "1,2,1");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.fromProperties(p, Map.of()));
        assertTrue(contains(e.problems(), "collection.monitor-ids", "duplicate"));
    }

    @Test
    void databaseRequiresUserAndPasswordOnceUrlIsSet() {
        Properties p = minimal();
        p.setProperty("db.url", "jdbc:sqlserver://localhost:1433;databaseName=Sensors");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.fromProperties(p, Map.of()));
        assertEquals(2, e.problems().size());

        p.setProperty("db.user", "collector");
        p.setProperty("db.password", "pw");
        assertTrue(ConfigLoader.fromProperties(p, Map.of()).database().isPresent());
    }

    @Test
    void secretsAreMaskedInToString() {
        Properties p = minimal();
        p.setProperty("db.url", "jdbc:sqlserver://h");
        p.setProperty("db.user", "u");
        p.setProperty("db.password", "db-secret");
        String text = ConfigLoader.fromProperties(p, Map.of()).toString();
        assertFalse(text.contains("secret"), text);
        assertTrue(text.contains("password=***"));
    }

    @Test
    void loadsUtf8FileAndReportsMissingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.properties");
        StringBuilder sb = new StringBuilder();
        minimal().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        sb.append("api.username=çağın\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        assertEquals("çağın", ConfigLoader.load(file, Map.of()).api().username());

        ConfigException e = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(dir.resolve("missing.properties"), Map.of()));
        assertTrue(e.getMessage().contains("missing.properties"));
    }

    private static boolean contains(List<String> problems, String key, String fragment) {
        return problems.stream().anyMatch(p -> p.startsWith(key) && p.contains(fragment));
    }
}
