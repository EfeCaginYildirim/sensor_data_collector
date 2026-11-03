package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chart data for one monitor: time stamps and one value list per feature, in the column layout
 * the uPlot charting library expects.
 *
 * @param features   plotted features
 * @param times      sample times in epoch seconds
 * @param values     one list per feature, aligned with {@code times}; {@code null} for gaps
 * @param sourceRows rows the series was computed from (before downsampling)
 */
public record Series(List<ComputedFeature> features, List<Long> times, List<List<Double>> values, int sourceRows) {

    public Series {
        features = List.copyOf(features);
        times = List.copyOf(times);
        List<List<Double>> copy = new ArrayList<>();
        values.forEach(v -> copy.add(Collections.unmodifiableList(new ArrayList<>(v))));
        values = List.copyOf(copy);
    }

    /**
     * Reduces rows to at most {@code maxPoints} points by averaging consecutive groups of rows.
     *
     * <p>Each point is the mean time and the mean of the non-null values of its group; a group
     * whose values are all null stays a gap ({@code null}). Rows are expected in time order, as
     * returned by a dataset query for one monitor.
     *
     * @param features  features of the rows' value columns
     * @param rows      rows in time order
     * @param maxPoints upper bound on points; at least 1
     * @return the series
     */
    public static Series downsample(List<ComputedFeature> features, List<DatasetRow> rows, int maxPoints) {
        if (maxPoints < 1) {
            throw new IllegalArgumentException("maxPoints must be positive");
        }
        int group = Math.max(1, (rows.size() + maxPoints - 1) / maxPoints);
        List<Long> times = new ArrayList<>();
        List<List<Double>> values = new ArrayList<>();
        for (int f = 0; f < features.size(); f++) {
            values.add(new ArrayList<>());
        }
        for (int start = 0; start < rows.size(); start += group) {
            List<DatasetRow> chunk = rows.subList(start, Math.min(rows.size(), start + group));
            times.add(Math.round(chunk.stream().mapToLong(r -> r.time().getEpochSecond()).average().orElseThrow()));
            for (int f = 0; f < features.size(); f++) {
                int column = f;
                double[] present = chunk.stream().map(r -> r.values().get(column)).filter(v -> v != null)
                    .mapToDouble(Double::doubleValue).toArray();
                values.get(f).add(present.length == 0 ? null : java.util.Arrays.stream(present).average().orElseThrow());
            }
        }
        return new Series(features, times, values, rows.size());
    }
}
