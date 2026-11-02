package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything collected for one monitor over one gap-free window, saved atomically.
 *
 * <p>Saving a batch means: "the vendor's data for {@code window} is exactly these samples".
 * Repositories therefore replace whatever they hold inside the window, which makes saves
 * idempotent and lets late corrections from the vendor flow in through the overlap.
 *
 * @param placement   the monitor and its position in the plant hierarchy
 * @param collectedAt when the status snapshot was taken
 * @param window      the window the samples cover; becomes the new watermark's end
 * @param basic       daily aggregates, each inside {@code window}, unique by time
 * @param computed    computed features, each inside {@code window}, unique by time
 */
public record MonitorBatch(MonitorPlacement placement, Instant collectedAt, TimeWindow window,
                           List<BasicFeature> basic, List<ComputedFeatureSample> computed) {

    public MonitorBatch {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(window, "window");
        basic = List.copyOf(basic);
        computed = List.copyOf(computed);
        Set<Instant> seen = new HashSet<>();
        for (BasicFeature b : basic) {
            requireInside(window, b.time());
            if (!seen.add(b.time())) {
                throw new IllegalArgumentException("duplicate basic feature at " + b.time());
            }
        }
        seen.clear();
        for (ComputedFeatureSample c : computed) {
            requireInside(window, c.time());
            if (!seen.add(c.time())) {
                throw new IllegalArgumentException("duplicate computed feature at " + c.time());
            }
        }
    }

    /** @return the monitor id */
    public int monitorId() {
        return placement.monitor().id();
    }

    private static void requireInside(TimeWindow window, Instant t) {
        if (!window.contains(t)) {
            throw new IllegalArgumentException("sample at " + t + " is outside " + window);
        }
    }
}
