package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.MonitorSummary;
import io.github.efecaginyildirim.collector.ui.CollectorService.ConnectionCheck;
import io.github.efecaginyildirim.collector.ui.CollectorService.StartResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CollectorServiceTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path dir;
    CollectorService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    static ProgressTracker.Progress awaitIdle(CollectorService s) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ProgressTracker.Progress p = s.progress();
            if (!p.running() && p.lastStatus().isPresent() && !s.status().running()) {
                return p;
            }
            Thread.sleep(20);
        }
        fail("run did not finish");
        return null;
    }

    @Test
    void demoModeWorksOutOfTheBox() throws Exception {
        service = new CollectorService(dir, CLOCK);
        assertEquals(SettingsStore.Mode.DEMO, service.status().mode());
        assertTrue(service.monitors().isEmpty());

        assertEquals(StartResult.STARTED, service.startRun());
        assertEquals(StartResult.ALREADY_RUNNING, service.startRun(), "one run at a time");
        ProgressTracker.Progress p = awaitIdle(service);

        assertEquals("SUCCESS", p.lastStatus().orElseThrow(), p.lastSummary().orElse(""));
        assertEquals(6, p.monitors().size());
        assertTrue(p.monitors().stream().allMatch(m -> m.state().equals("done") && m.outcome().orElseThrow().equals("COLLECTED")));
        assertEquals(p.chunksTotal(), p.chunksDone());
        assertEquals(24, p.chunksTotal(), "6 monitors x 4 chunks for 90 days of history");

        List<MonitorSummary> monitors = service.monitors();
        assertEquals(6, monitors.size());
        assertEquals(NOW, monitors.get(0).collectedUntil().orElseThrow());
        assertEquals(1, service.runs(10).size());
        assertTrue(Files.exists(dir.resolve("data/demo.mv.db")));
    }

    @Test
    void seriesAreDownsampledForCharts() throws Exception {
        service = new CollectorService(dir, CLOCK);
        service.startRun();
        awaitIdle(service);

        Series s = service.series(1001, List.of(ComputedFeature.KURTACCLX, ComputedFeature.BPFO), 50);
        assertTrue(s.times().size() <= 50 && s.times().size() > 40, "at most 50 points: " + s.times().size());
        assertEquals(90 * 4 + 2, s.sourceRows(), "2025-03-03 .. 2025-06-01 06:00, every 6 hours");
        assertTrue(s.values().get(0).stream().allMatch(v -> v != null && v > 1));
    }

    @Test
    void demoDataAndScenarioSurviveARestart() throws Exception {
        service = new CollectorService(dir, CLOCK);
        service.startRun();
        awaitIdle(service);
        String reference = service.settingsForDisplay().get("demo.reference-time");
        service.close();

        service = new CollectorService(dir, Clock.offset(CLOCK, Duration.ofDays(1)));
        assertEquals(reference, service.settingsForDisplay().get("demo.reference-time"), "same scenario after restart");
        assertEquals(6, service.monitors().size(), "data is persisted");
        service.startRun();
        ProgressTracker.Progress p = awaitIdle(service);
        assertEquals("SUCCESS", p.lastStatus().orElseThrow());
        assertEquals(NOW.plus(Duration.ofDays(1)), service.monitors().get(0).collectedUntil().orElseThrow());
        assertEquals(2, service.runs(10).size());
    }

    @Test
    void passwordIsSavedButNeverDisplayedAndModesUseSeparateDatabases() throws Exception {
        service = new CollectorService(dir, CLOCK);
        service.startRun();
        awaitIdle(service);

        List<String> problems = service.updateSettings(Map.of(
            "ui.mode", "api", "api.base-url", "http://127.0.0.1:1/api/v1", "api.username", "efe", "api.password", "pw",
            "collection.plant-id", "1", "collection.monitor-ids", "5", "collection.initial-start-date", "2025-01-01"));
        assertEquals(List.of(), problems);

        Map<String, String> shown = service.settingsForDisplay();
        assertEquals("", shown.get("api.password"));
        assertEquals("true", shown.get("api.password-set"));
        assertTrue(Files.readString(dir.resolve("settings.properties"), StandardCharsets.UTF_8).contains("api.password=pw"));
        assertTrue(service.monitors().isEmpty(), "API mode has its own database");

        service.updateSettings(Map.of("ui.mode", "demo"));
        assertEquals(6, service.monitors().size(), "demo data is still there");
    }

    @Test
    void invalidSettingsOnDiskAreReportedInsteadOfRunning() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.properties"), "ui.mode=api\n", StandardCharsets.UTF_8);
        service = new CollectorService(dir, CLOCK);

        assertEquals(StartResult.INVALID_SETTINGS, service.startRun());
        ProgressTracker.Progress p = service.progress();
        assertEquals("INVALID_SETTINGS", p.lastStatus().orElseThrow());
        assertTrue(p.lastSummary().orElseThrow().contains("api.base-url"), p.lastSummary().orElseThrow());
    }

    @Test
    void connectionCheckReportsSuccessAndFailure() throws Exception {
        service = new CollectorService(dir, CLOCK);
        ConnectionCheck demo = service.testConnection(Map.of());
        assertTrue(demo.ok(), demo.message());
        assertEquals(6, demo.found().size());

        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        ConnectionCheck unreachable = service.testConnection(Map.of("ui.mode", "api",
            "api.base-url", "http://127.0.0.1:" + closedPort + "/api/v1", "api.username", "u", "api.password", "p",
            "collection.plant-id", "1", "collection.monitor-ids", "5", "collection.initial-start-date", "2025-01-01"));
        assertFalse(unreachable.ok());
        assertTrue(unreachable.message().contains("/login"), unreachable.message());
        assertEquals(SettingsStore.Mode.DEMO, service.status().mode(), "testing does not save anything");

        ConnectionCheck incomplete = service.testConnection(Map.of("ui.mode", "api"));
        assertFalse(incomplete.ok());
        assertTrue(incomplete.message().contains("api.base-url is required"), incomplete.message());
    }

    @Test
    void scheduleIsAppliedWhenEnabled() {
        service = new CollectorService(dir, Clock.systemUTC());
        assertTrue(service.status().nextRun().isEmpty());

        assertEquals(List.of(), service.updateSettings(Map.of("schedule.enabled", "true", "schedule.daily-time", "06:30",
            "schedule.zone", "Europe/Istanbul")));
        var next = service.status().nextRun().orElseThrow();
        assertEquals("06:30", next.toLocalTime().toString());
        assertEquals("Europe/Istanbul", next.getZone().getId());

        service.updateSettings(Map.of("schedule.enabled", "false"));
        assertTrue(service.status().nextRun().isEmpty());
    }
}
