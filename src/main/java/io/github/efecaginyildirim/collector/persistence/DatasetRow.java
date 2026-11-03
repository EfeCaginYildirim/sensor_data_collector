package io.github.efecaginyildirim.collector.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One row of a {@link DatasetQuery} result.
 *
 * @param monitorId monitor the sample belongs to
 * @param time      sample time (UTC)
 * @param values    one value per {@link DatasetQuery#features() requested feature}, in the same
 *                  order; an element is {@code null} when the value is unknown
 */
public record DatasetRow(int monitorId, Instant time, List<Double> values) {

    public DatasetRow {
        Objects.requireNonNull(time, "time");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * @param index column index within the requested features
     * @return the value, or empty if unknown
     */
    public OptionalDouble value(int index) {
        Double v = values.get(index);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }
}
