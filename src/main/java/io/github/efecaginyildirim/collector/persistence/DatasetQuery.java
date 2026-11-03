package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A selection of computed-feature data: which monitors, which time window and which columns.
 *
 * <p>Columns can only be chosen from {@link ComputedFeature}. That whitelist is what makes it safe
 * to build the column list of the SQL statement dynamically: no user-supplied text ever reaches
 * the query, only enum constants.
 *
 * @param monitorIds monitors to include, at least one, no duplicates
 * @param window     time window; rows with {@code window.contains(sample_time)}
 * @param features   columns to return, in this order; at least one, no duplicates
 * @param limit      maximum number of rows, e.g. for a preview; empty for all rows
 */
public record DatasetQuery(List<Integer> monitorIds, TimeWindow window, List<ComputedFeature> features, OptionalInt limit) {

    public DatasetQuery {
        monitorIds = List.copyOf(monitorIds);
        features = List.copyOf(features);
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(limit, "limit");
        if (monitorIds.isEmpty()) {
            throw new IllegalArgumentException("at least one monitor is required");
        }
        if (monitorIds.stream().distinct().count() != monitorIds.size()) {
            throw new IllegalArgumentException("duplicate monitor ids: " + monitorIds);
        }
        if (features.isEmpty()) {
            throw new IllegalArgumentException("at least one feature is required");
        }
        if (EnumSet.copyOf(features).size() != features.size()) {
            throw new IllegalArgumentException("duplicate features: " + features);
        }
        if (limit.isPresent() && limit.getAsInt() <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /**
     * A query for every feature, without a row limit.
     *
     * @param monitorIds monitors to include
     * @param window     time window
     * @return the query
     */
    public static DatasetQuery allFeatures(List<Integer> monitorIds, TimeWindow window) {
        return new DatasetQuery(monitorIds, window, Arrays.asList(ComputedFeature.values()), OptionalInt.empty());
    }

    /**
     * @param maxRows maximum number of rows
     * @return a copy of this query with a row limit
     */
    public DatasetQuery withLimit(int maxRows) {
        return new DatasetQuery(monitorIds, window, features, OptionalInt.of(maxRows));
    }
}
