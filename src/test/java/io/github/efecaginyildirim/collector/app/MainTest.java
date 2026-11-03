package io.github.efecaginyildirim.collector.app;

import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    record Result(int exitCode, String output) { }

    static Result exec(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = Main.execute(args, out, Map.of());
        return new Result(code, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndUsageErrors() {
        assertEquals(0, exec("help").exitCode());
        assertTrue(exec().output().contains("Usage:"), "no command shows usage");
        assertEquals(0, exec("--help").exitCode());
        assertEquals(Main.EXIT_USAGE, exec("frobnicate").exitCode());
        assertEquals(Main.EXIT_USAGE, exec("demo", "--days").exitCode());
        assertEquals(Main.EXIT_USAGE, exec("demo", "--days", "many").exitCode());
        assertEquals(Main.EXIT_USAGE, exec("run", "extra").exitCode());
    }

    @Test
    void configurationProblemsUseExitCode78(@TempDir Path dir) throws Exception {
        Result missing = exec("run", "--config", dir.resolve("nope.properties").toString());
        assertEquals(Main.EXIT_CONFIG, missing.exitCode());
        assertTrue(missing.output().contains("nope.properties"));

        Path config = writeConfig(dir, "http://127.0.0.1:1/api/v1");
        Result noDb = exec("run", "--config", config.toString());
        assertEquals(Main.EXIT_CONFIG, noDb.exitCode());
        assertTrue(noDb.output().contains("db.url is required"), noDb.output());
    }

    @Test
    void runCollectsOnceAgainstAnApi(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        SyntheticFleet fleet = SyntheticFleet.create(1, List.of(1001, 1002), SyntheticFleet.Options.defaults(1, now));
        try (FakeMonitoringServer server = FakeMonitoringServer.start(fleet, Clock.systemUTC(), "demo", "demo")) {
            Path config = writeConfig(dir, server.baseUri().toString());

            Result r = exec("run", "--config", config.toString(), "--in-memory");

            assertEquals(0, r.exitCode(), r.output());
            assertTrue(r.output().contains("SUCCESS"), r.output());
            assertTrue(r.output().contains("monitor 1002"), r.output());
        }
    }

    @Test
    void runReportsPartialFailureThroughTheExitCode(@TempDir Path dir) throws Exception {
        SyntheticFleet fleet = SyntheticFleet.create(1, List.of(1001), SyntheticFleet.Options.defaults(1, Instant.now()));
        try (FakeMonitoringServer server = FakeMonitoringServer.start(fleet, Clock.systemUTC(), "demo", "demo")) {
            Path config = writeConfig(dir, server.baseUri().toString()); // asks for 1001 and 1002; 1002 does not exist

            Result r = exec("run", "--config", config.toString(), "--in-memory");

            assertEquals(2, r.exitCode(), r.output());
            assertTrue(r.output().contains("monitor 1002 not found"), r.output());
        }
    }

    @Test
    void demoRunsBackfillAndIncrementalCollection(@TempDir Path dir) {
        Result r = exec("demo", "--days", "20", "--monitors", "3", "--snapshot-dir", dir.toString());

        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("== Run 1"), r.output());
        assertTrue(r.output().contains("== Run 2"), r.output());
        assertTrue(r.output().contains("Fleet health"), r.output());
        assertTrue(r.output().contains("Run history: 2 runs stored"), r.output());
        assertEquals(3, r.output().lines().filter(l -> l.contains("COLLECTED")).count() / 2, r.output());
        assertEquals(2, dir.toFile().list().length, "one snapshot per run");
    }

    @Test
    void benchmarkPrintsOneRowPerConcurrencyLevel() {
        Result r = exec("benchmark", "--monitors", "2", "--days", "40", "--latency-ms", "5", "--concurrency", "1,4");

        assertEquals(0, r.exitCode(), r.output());
        List<String> rows = r.output().lines().filter(l -> l.matches("^[14]\\s+\\d+\\.\\d+ s.*")).toList();
        assertEquals(2, rows.size(), r.output());
    }

    static Path writeConfig(Path dir, String baseUrl) throws Exception {
        Path file = dir.resolve("collector.properties");
        Files.writeString(file, String.join("\n",
            "api.base-url=" + baseUrl,
            "api.username=demo",
            "api.password=demo",
            "api.max-attempts=1",
            "collection.plant-id=1",
            "collection.monitor-ids=1001,1002",
            "collection.initial-start-date=" + LocalDate.now(ZoneOffset.UTC).minusDays(5)), StandardCharsets.UTF_8);
        return file;
    }
}
