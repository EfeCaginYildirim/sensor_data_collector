package io.github.efecaginyildirim.collector.dataset;

import java.util.List;
import java.util.Objects;

/**
 * Keeps only rows that differ meaningfully from the last kept row of the same monitor — the
 * "exception reporting" or deadband compression used by industrial data historians.
 *
 * <p>A row is kept if, for at least one exported column, the value appeared, disappeared, or moved
 * by more than the threshold. The first and the last row of every monitor are always kept, so the
 * reduced series still spans the selected period. A threshold of {@code 0} keeps every change and
 * drops only exact repeats.
 *
 * <p>The result is an irregularly sampled series: each kept value is valid until the next kept
 * row. Account for that before feeding it to a model that expects a fixed sample rate.
 *
 * @param mode      how the threshold is measured
 * @param threshold minimum change; {@code >= 0}, at most 1000 for {@link Mode#PERCENT}
 */
public record ChangeFilter(Mode mode, double threshold) {

    /** How a change is measured. */
    public enum Mode {
        /** In the column's own unit. */
        ABSOLUTE,
        /** Relative to the last kept value, in percent. */
        PERCENT
    }

    public ChangeFilter {
        Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(threshold) || threshold < 0) {
            throw new IllegalArgumentException("change threshold must be zero or positive");
        }
        if (mode == Mode.PERCENT && threshold > 1000) {
            throw new IllegalArgumentException("percentage threshold must be at most 1000");
        }
    }

    /**
     * @param last      values of the last kept row
     * @param candidate values of the row under test, same columns
     * @return whether {@code candidate} changed enough to be kept
     */
    public boolean changed(List<Double> last, List<Double> candidate) {
        for (int i = 0; i < candidate.size(); i++) {
            Double a = last.get(i);
            Double b = candidate.get(i);
            if (a == null || b == null) {
                if (a != b) {
                    return true;
                }
                continue;
            }
            double delta = Math.abs(b - a);
            double limit = mode == Mode.ABSOLUTE ? threshold : Math.abs(a) * threshold / 100.0;
            if (delta > limit) {
                return true;
            }
        }
        return false;
    }
}
