package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.api.HttpMonitoringApi;
import io.github.efecaginyildirim.collector.api.RetryPolicy;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.collect.RunReport.Status;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer;
import io.github.efecaginyildirim.collector.fakeapi.FakeMonitoringServer.Fault;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet;
import io.github.efecaginyildirim.collector.persistence.RunHistory.RunRecord;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RunListenerTest {

    static final Instant NOW = Instant.parse("2025-06-01T10:00:00Z");

    final MutableClock clock = new MutableClock(NOW);
    final TestStore store = new TestStore();
    FakeMonitoringServer server;
    CollectionJob job;

    /** Records events as strings such as {@code chunk 101 [..) ok}. */
    static final class Recorder implements RunListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> planned = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void runStarted(String runId, Instant startedAt, List<Integer> monitorIds) {
            events.add("start " + monitorIds);
        }

        @Override
        public void monitorPlanned(int monitorId, TimeWindow window, int chunks) {
            planned.add(chunks);
            events.add("planned " + monitorId + " " + chunks);
        }

        @Override
        public void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) {
            events.add("chunk " + monitorId + " " + (ok ? "ok" : "failed"));
        }

        @Override
        public void monitorFinished(MonitorOutcome outcome) {
            events.add("finished " + outcome.monitorId() + " " + outcome.outcome());
        }

        @Override
        public void runFinished(RunReport report) {
            events.add("end " + report.status());
        }

        long count(String prefix) {
            return events.stream().filter(e -> e.startsWith(prefix)).count();
        }

        int indexOf(String event) {
            return events.indexOf(event);
        }
    }

    @BeforeEach
    void start() throws Exception {
        SyntheticFleet fleet = SyntheticFleet.create(1, List.of(101, 102, 103),
            SyntheticFleet.Options.defaults(3, NOW));
        server = FakeMonitoringServer.start(fleet, clock, "demo", "pw");
        HttpMonitoringApi api = new HttpMonitoringApi(server.baseUri(), "demo", "pw", HttpClient.newHttpClient(),
            Duration.ofSeconds(5), RetryPolicy.defaults(1), d -> { }, RandomGenerator.of("L64X128MixRandom"));
        CollectionConfig config = new CollectionConfig(1, List.of(101, 102, 103, 999), LocalDate.of(2025, 3, 1),
            Duration.ofDays(3), Duration.ofDays(30), Duration.ofSeconds(30));
        job = new CollectionJob(api, store, config, 4, clock, Optional.empty());
    }

    @AfterEach
    void stop() {
        server.close();
        store.close();
    }

    @Test
    void eventsArriveInOrderAndAddUp() {
        Recorder r = new Recorder();
        RunReport report = job.run(r);

        assertEquals("start [101, 102, 103, 999]", r.events.get(0));
        assertEquals("end PARTIAL", r.events.get(r.events.size() - 1));
        assertEquals(4, r.count("finished "), "one per configured monitor");
        assertEquals(3, r.count("planned "), "999 is not in the plant, so it is never planned");
        assertTrue(r.events.contains("finished 999 FAILED"), "planning failures are reported too");
        assertTrue(r.events.stream().noneMatch(e -> e.startsWith("planned 999")));
        assertEquals(r.planned.stream().mapToInt(Integer::intValue).sum(), r.count("chunk "), "every chunk reports once");
        for (int id : List.of(101, 102, 103)) {
            int planned = r.indexOf("planned " + id + " 4");
            int finished = r.indexOf("finished " + id + " COLLECTED");
            assertTrue(planned >= 0 && finished > planned, r.events.toString());
            for (int i = 0; i < r.events.size(); i++) {
                if (r.events.get(i).startsWith("chunk " + id + " ")) {
                    assertTrue(i > planned && i < finished, "chunk events of " + id + " lie between plan and finish");
                }
            }
        }
        assertEquals(Status.PARTIAL, report.status());
    }

    @Test
    void failedChunksAreReportedAsSuch() {
        server.inject(req -> req.monitorId().orElse(0) == 102, Fault.status(500), -1);
        Recorder r = new Recorder();
        job.run(r);

        assertEquals(4, r.events.stream().filter(e -> e.equals("chunk 102 failed")).count());
        assertTrue(r.events.contains("finished 102 FAILED"));
        assertTrue(r.events.stream().filter(e -> e.startsWith("chunk 101 ")).allMatch(e -> e.endsWith("ok")));
    }

    @Test
    void aThrowingListenerCannotBreakTheRun() {
        RunListener hostile = new RunListener() {
            @Override
            public void runStarted(String runId, Instant startedAt, List<Integer> monitorIds) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void chunkFinished(int monitorId, TimeWindow chunk, boolean ok) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void monitorFinished(MonitorOutcome outcome) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void runFinished(RunReport report) {
                throw new IllegalStateException("boom");
            }
        };
        Recorder after = new Recorder();

        RunReport report = job.run(RunListener.all(hostile, after));

        assertEquals(Status.PARTIAL, report.status(), "only 999 fails, as without listeners");
        assertTrue(store.watermark(101).isPresent());
        assertEquals(3, after.count("planned "), "events that the hostile listener does not override still arrive");
    }

    @Test
    void historyListenerStoresEveryRun() {
        RunReport first = job.run(new RunHistoryListener(store.delegate));
        clock.advance(Duration.ofDays(1));
        RunReport second = job.run(new RunHistoryListener(store.delegate));

        List<RunRecord> runs = store.delegate.recentRuns(10);
        assertEquals(List.of(second.runId(), first.runId()), runs.stream().map(RunRecord::runId).toList());
        RunRecord r = runs.get(1);
        assertEquals("PARTIAL", r.status());
        assertEquals(4, r.monitorsTotal());
        assertEquals(1, r.monitorsFailed());
        assertEquals(first.monitors().stream().mapToInt(MonitorOutcome::newRows).sum(), r.newRows());
        assertEquals(first.summary(), r.summary());
        assertEquals(NOW, r.startedAt());
    }

    @Test
    void isRunningReflectsAnActiveRun() throws Exception {
        server.setLatency(Duration.ofMillis(200));
        assertFalse(job.isRunning());
        CompletableFuture<RunReport> run = CompletableFuture.supplyAsync(job::run);
        Thread.sleep(100);
        assertTrue(job.isRunning());
        run.get();
        assertFalse(job.isRunning());
    }
}
