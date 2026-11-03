package io.github.efecaginyildirim.collector.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.efecaginyildirim.collector.api.HttpMonitoringApi;
import io.github.efecaginyildirim.collector.api.RetryPolicy;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Outcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Status;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer.Fault;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CollectionJobTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00Z");
    static final Instant INITIAL = Instant.parse("2025-03-01T00:00:00Z");
    static final List<Integer> MONITORS = List.of(101, 102, 103, 104, 105);

    final MutableClock clock = new MutableClock(NOW);
    final TestStore repo = new TestStore();
    FakeMonitoringServer server;
    HttpMonitoringApi api;

    @BeforeEach
    void start() throws Exception {
        SyntheticFleet fleet = SyntheticFleet.create(1, MONITORS,
            new SyntheticFleet.Options(11, NOW, Duration.ofHours(6), 0.02, 0.01));
        server = FakeMonitoringServer.start(fleet, clock, "demo", "pw");
        api = new HttpMonitoringApi(server.baseUri(), "demo", "pw", HttpClient.newHttpClient(),
            Duration.ofSeconds(5), RetryPolicy.defaults(2), d -> { }, RandomGenerator.of("L64X128MixRandom"));
    }

    @AfterEach
    void stop() {
        server.close();
        repo.close();
    }

    static CollectionConfig config(List<Integer> monitors, Duration overlap, Duration deadline) {
        return new CollectionConfig(1, monitors, LocalDate.of(2025, 3, 1), overlap, Duration.ofDays(30), deadline);
    }

    CollectionJob job(CollectionConfig config, int concurrency) {
        return new CollectionJob(api, repo, config, concurrency, clock, Optional.empty());
    }

    CollectionJob job() {
        return job(config(MONITORS, Duration.ofDays(3), Duration.ofSeconds(30)), 4);
    }

    // ─── Happy path ──────────────────────────────────────────────────────────────

    @Test
    void firstRunCollectsTheFullHistoryExactlyOnce() {
        RunReport report = job().run();

        assertEquals(Status.SUCCESS, report.status(), report::summary);
        assertEquals(0, report.status().exitCode());
        for (int id : MONITORS) {
            MonitorOutcome o = outcome(report, id);
            assertEquals(Outcome.COLLECTED, o.outcome());
            assertEquals(NOW, o.collectedUntil().orElseThrow());
            assertEquals(NOW, repo.watermark(id).orElseThrow());

            // 2025-03-01 .. 2025-06-01 (inclusive): 93 days, and 6-hourly samples until 06:00 on the last day
            assertEquals(93, repo.basicFeatures(id).size());
            assertEquals(92 * 4 + 2, repo.computedFeatures(id).size());
            assertEquals(o.basicSaved() + o.computedSaved(), o.newRows());
        }
        // 4 chunks (30+30+30+2.4 days) × 2 endpoints × 5 monitors
        assertEquals(20, server.requestCount("/trend-history"));
        assertEquals(20, server.requestCount("/trend/computed-features"));
    }

    @Test
    void chunkedParallelCollectionStoresExactlyWhatOneBigRequestReturns() {
        job().run();

        TimeWindow all = new TimeWindow(INITIAL, NOW);
        for (int id : MONITORS) {
            List<BasicFeature> reference = api.fetchBasicFeatures(id, all).items();
            List<ComputedFeatureSample> referenceComputed = api.fetchComputedFeatures(id, all).items();
            assertEquals(reference, repo.basicFeatures(id), "basic features of " + id);
            assertEquals(referenceComputed, repo.computedFeatures(id), "computed features of " + id);
        }
    }

    @Test
    void nextRunOnlyFetchesFromTheWatermarkMinusOverlap() {
        job().run();
        server.resetRecording();
        clock.advance(Duration.ofDays(1));

        RunReport second = job().run();

        assertEquals(Status.SUCCESS, second.status());
        for (FakeMonitoringServer.RecordedRequest r : server.requests()) {
            if (r.path().startsWith("/trend")) {
                assertEquals("2025-05-29T10:00:00Z", r.query().get("from"));
                assertEquals("2025-06-02T10:00:00Z", r.query().get("to"));
            }
        }
        for (int id : MONITORS) {
            assertEquals(94, repo.basicFeatures(id).size());
            assertEquals(93 * 4 + 2, repo.computedFeatures(id).size());
            assertEquals(5, outcome(second, id).newRows(), "one day: 1 basic + 4 computed");
            assertEquals(2, repo.statusSnapshots(id).size());
        }
    }

    @Test
    void tenDaysOfDowntimeLeaveNoGap() {
        job().run();
        clock.advance(Duration.ofDays(10));
        job().run();

        List<Instant> days = repo.basicFeatures(101).stream().map(BasicFeature::time).toList();
        for (int i = 1; i < days.size(); i++) {
            assertEquals(Duration.ofDays(1), Duration.between(days.get(i - 1), days.get(i)), "gap before " + days.get(i));
        }
        assertEquals(Instant.parse("2025-06-11T00:00:00Z"), days.get(days.size() - 1));
    }

    @Test
    void upToDateMonitorsAreSkippedWithoutRequests() {
        CollectionConfig noOverlap = config(MONITORS, Duration.ZERO, Duration.ofSeconds(30));
        job(noOverlap, 4).run();
        server.resetRecording();

        RunReport again = job(noOverlap, 4).run();

        assertEquals(Status.SUCCESS, again.status());
        assertTrue(again.monitors().stream().allMatch(m -> m.outcome() == Outcome.UP_TO_DATE));
        assertEquals(0, server.requestCount("/trend-history") + server.requestCount("/trend/computed-features"));
    }

    // ─── Failure isolation ───────────────────────────────────────────────────────

    @Test
    void oneFailingMonitorDoesNotBlockTheOthers() {
        server.inject(r -> r.monitorId().orElse(0) == 102, Fault.status(500), -1);

        RunReport report = job().run();

        assertEquals(Status.PARTIAL, report.status());
        assertEquals(2, report.status().exitCode());
        MonitorOutcome failed = outcome(report, 102);
        assertEquals(Outcome.FAILED, failed.outcome());
        assertTrue(failed.error().orElseThrow().contains("HTTP 500"), failed.error().orElseThrow());
        assertTrue(repo.watermark(102).isEmpty());
        assertTrue(repo.basicFeatures(102).isEmpty());
        for (int id : List.of(101, 103, 104, 105)) {
            assertEquals(Outcome.COLLECTED, outcome(report, id).outcome());
        }
    }

    @Test
    void aFailedChunkKeepsOnlyTheGapFreePrefixAndTheNextRunResumes() {
        // chunks start 03-01, 03-31, 04-30, 05-30; break the third one for monitor 101
        server.inject(r -> r.monitorId().orElse(0) == 101 && "2025-04-30T00:00:00Z".equals(r.query().get("from")),
            Fault.status(503), -1);

        RunReport first = job().run();

        MonitorOutcome o = outcome(first, 101);
        assertEquals(Outcome.PARTIAL, o.outcome());
        assertEquals(Instant.parse("2025-04-30T00:00:00Z"), repo.watermark(101).orElseThrow());
        assertEquals(Instant.parse("2025-04-29T00:00:00Z"),
            repo.basicFeatures(101).get(repo.basicFeatures(101).size() - 1).time(), "nothing after the failed chunk is kept");
        assertTrue(o.error().orElseThrow().contains("saved up to 2025-04-30T00:00:00Z"), o.error().orElseThrow());

        server.clearFaults();
        server.resetRecording();
        RunReport second = job().run();

        assertEquals(Outcome.COLLECTED, outcome(second, 101).outcome());
        assertEquals(93, repo.basicFeatures(101).size());
        assertTrue(server.requests().stream()
            .filter(r -> r.path().startsWith("/trend") && r.monitorId().orElse(0) == 101)
            .anyMatch(r -> "2025-04-27T00:00:00Z".equals(r.query().get("from"))), "resumes at watermark - overlap");
    }

    @Test
    void storageFailureIsReportedAsFailureNotAsSuccess() {
        // Regression: the legacy code returned 0 rows on SQL errors and treated that as "all duplicates".
        repo.failSavesWhen(b -> b.monitorId() == 103);

        RunReport report = job().run();

        MonitorOutcome o = outcome(report, 103);
        assertEquals(Outcome.FAILED, o.outcome());
        assertTrue(o.error().orElseThrow().startsWith("saving failed"), o.error().orElseThrow());
        assertTrue(repo.watermark(103).isEmpty());
        assertEquals(Status.PARTIAL, report.status());
    }

    @Test
    void unavailablePlantFailsTheWholeRunWithoutDataRequests() {
        server.inject("/plants/1", Fault.status(404), -1);

        RunReport report = job().run();

        assertEquals(Status.FAILED, report.status());
        assertEquals(1, report.status().exitCode());
        assertTrue(report.failure().orElseThrow().contains("HTTP 404"));
        assertTrue(report.monitors().stream().allMatch(m -> m.outcome() == Outcome.FAILED));
        assertEquals(0, server.requestCount("/trend-history"));
    }

    @Test
    void monitorsMissingFromThePlantAreReported() {
        RunReport report = job(config(List.of(101, 999), Duration.ofDays(3), Duration.ofSeconds(30)), 4).run();

        assertEquals(Outcome.COLLECTED, outcome(report, 101).outcome());
        assertEquals("monitor 999 not found in plant 1", outcome(report, 999).error().orElseThrow());
        assertEquals(Status.PARTIAL, report.status());
        assertEquals(List.of(101, 999), report.monitors().stream().map(MonitorOutcome::monitorId).toList(), "config order");
    }

    // ─── Concurrency ─────────────────────────────────────────────────────────────

    @Test
    void inFlightRequestsNeverExceedTheConfiguredLimit() {
        server.setLatency(Duration.ofMillis(40));
        job(config(MONITORS, Duration.ofDays(3), Duration.ofSeconds(30)), 3).run();

        assertTrue(server.maxConcurrentRequests() <= 3, "max in flight: " + server.maxConcurrentRequests());
        assertTrue(server.maxConcurrentRequests() >= 2, "requests should overlap: " + server.maxConcurrentRequests());
    }

    @Test
    @Timeout(10)
    void deadlineStopsSlowMonitorsAndSavesNothingForThem() {
        server.setLatency(Duration.ofSeconds(3));
        long start = System.nanoTime();

        RunReport report = job(config(MONITORS, Duration.ofDays(3), Duration.ofMillis(700)), 4).run();

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(Status.FAILED, report.status());
        assertTrue(report.monitors().stream().allMatch(m -> m.outcome() == Outcome.FAILED
            && m.error().orElseThrow().contains("deadline")), report::summary);
        assertTrue(MONITORS.stream().allMatch(id -> repo.watermark(id).isEmpty()));
        assertTrue(elapsed.compareTo(Duration.ofSeconds(6)) < 0, "interrupted requests must not hold the run: " + elapsed);
    }

    @Test
    void concurrentRunsOfTheSameJobAreRejected() throws Exception {
        server.setLatency(Duration.ofMillis(300));
        CollectionJob job = job();
        CompletableFuture<RunReport> first = CompletableFuture.supplyAsync(job::run);
        Thread.sleep(100);

        assertThrows(IllegalStateException.class, job::run);
        assertEquals(Status.SUCCESS, first.get().status());
        assertDoesNotThrow(() -> job.run(), "the job is reusable after a run");
    }

    // ─── Snapshots ───────────────────────────────────────────────────────────────

    @Test
    void writesAUtf8SnapshotOfTheSavedData(@TempDir Path dir) throws Exception {
        CollectionJob job = new CollectionJob(api, repo, config(List.of(101, 102), Duration.ofDays(3), Duration.ofSeconds(30)),
            4, clock, Optional.of(new SnapshotWriter(dir)));

        RunReport report = job.run();

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.toList();
        }
        assertEquals(1, files.size(), "no temporary files are left behind: " + files);
        assertTrue(files.get(0).getFileName().toString().matches("run-20250601T100000Z-" + report.runId() + "\\.json"));
        JsonNode json = new ObjectMapper().readTree(Files.readString(files.get(0), StandardCharsets.UTF_8));
        assertEquals("SUCCESS", json.get("status").asText());
        assertEquals(2, json.get("monitors").size());
        assertEquals(93, json.at("/monitors/0/basicFeatures").size());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void snapshotProblemsAreWarningsNotFailures(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("file"), "x");
        CollectionJob job = new CollectionJob(api, repo, config(List.of(101), Duration.ofDays(3), Duration.ofSeconds(30)),
            4, clock, Optional.of(new SnapshotWriter(notADirectory)));

        RunReport report = job.run();

        assertEquals(Status.SUCCESS, report.status());
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).startsWith("snapshot could not be written"));
    }

    static MonitorOutcome outcome(RunReport r, int monitorId) {
        return r.monitors().stream().filter(m -> m.monitorId() == monitorId).findFirst().orElseThrow();
    }
}
