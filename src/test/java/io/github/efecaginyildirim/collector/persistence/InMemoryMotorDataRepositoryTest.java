package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository.SaveResult;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.github.efecaginyildirim.collector.persistence.Batches.T0;
import static io.github.efecaginyildirim.collector.persistence.Batches.batch;
import static io.github.efecaginyildirim.collector.persistence.Batches.placement;
import static org.junit.jupiter.api.Assertions.*;

/** Repository semantics, verified on the in-memory reference implementation. */
class InMemoryMotorDataRepositoryTest {

    final InMemoryMotorDataRepository repo = new InMemoryMotorDataRepository();

    @Test
    void watermarkIsEmptyUntilTheFirstSaveAndThenTheWindowEnd() {
        assertTrue(repo.watermark(101).isEmpty());
        repo.save(batch(101, T0, 3));
        assertEquals(T0.plus(Duration.ofDays(3)), repo.watermark(101).orElseThrow());
        assertTrue(repo.watermark(102).isEmpty(), "watermarks are per monitor");
    }

    @Test
    void watermarkNeverMovesBackwards() {
        repo.save(batch(101, T0.plus(Duration.ofDays(5)), 2));
        repo.save(batch(101, T0, 2));
        assertEquals(T0.plus(Duration.ofDays(7)), repo.watermark(101).orElseThrow());
    }

    @Test
    void savingTheSameBatchTwiceIsIdempotent() {
        SaveResult first = repo.save(batch(101, T0, 3));
        SaveResult second = repo.save(batch(101, T0, 3));

        assertEquals(3, repo.basicFeatures(101).size());
        assertEquals(12, repo.computedFeatures(101).size());
        assertEquals(15, first.newRows());
        assertEquals(0, second.newRows());
        assertEquals(new SaveResult(3, 3, 12, 12), second);
    }

    @Test
    void overlappingSaveReplacesOnlyTheOverlap() {
        repo.save(batch(101, T0, 5));                                 // days 0..4
        Instant day3 = T0.plus(Duration.ofDays(3));
        SaveResult r = repo.save(batch(101, day3, 4));                // days 3..6

        assertEquals(7, repo.basicFeatures(101).size());
        assertEquals(new SaveResult(4, 2, 16, 8), r);
        assertEquals(T0.plus(Duration.ofDays(7)), repo.watermark(101).orElseThrow());
    }

    @Test
    void samplesThatDisappearedFromTheVendorAreRemovedInsideTheWindow() {
        repo.save(batch(101, T0, 3));
        TimeWindow window = new TimeWindow(T0, T0.plus(Duration.ofDays(3)));
        BasicFeature only = new BasicFeature(T0, "corrected", "{}", "{}", "{}");
        repo.save(new MonitorBatch(placement(101), window.end(), window, List.of(only), List.<ComputedFeatureSample>of()));

        assertEquals(List.of(only), repo.basicFeatures(101));
        assertTrue(repo.computedFeatures(101).isEmpty());
    }

    @Test
    void everySaveRecordsAStatusSnapshotAndTheMonitor() {
        repo.save(batch(101, T0, 1));
        repo.save(batch(101, T0.plus(Duration.ofDays(1)), 1));
        assertEquals(2, repo.statusSnapshots(101).size());
        assertEquals(87, repo.statusSnapshots(101).firstEntry().getValue().healthScore());
        assertEquals("Motor M-01", repo.monitor(101).orElseThrow().machine().name());
    }

    @Test
    void injectedFailuresChangeNothing() {
        repo.save(batch(101, T0, 1));
        repo.failSavesWhen(b -> true);
        assertThrows(PersistenceException.class, () -> repo.save(batch(101, T0.plus(Duration.ofDays(1)), 1)));
        assertEquals(T0.plus(Duration.ofDays(1)), repo.watermark(101).orElseThrow());
        assertEquals(1, repo.basicFeatures(101).size());
    }

    @Test
    void batchesRejectSamplesOutsideTheirWindowOrDuplicated() {
        TimeWindow w = new TimeWindow(T0, T0.plus(Duration.ofDays(1)));
        BasicFeature outside = new BasicFeature(w.end(), null, null, null, null);
        BasicFeature inside = new BasicFeature(T0, null, null, null, null);
        assertThrows(IllegalArgumentException.class,
            () -> new MonitorBatch(placement(1), T0, w, List.of(outside), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new MonitorBatch(placement(1), T0, w, List.of(inside, inside), List.of()));
    }
}
