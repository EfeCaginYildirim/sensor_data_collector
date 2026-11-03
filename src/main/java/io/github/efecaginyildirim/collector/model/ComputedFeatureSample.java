package io.github.efecaginyildirim.collector.model;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One set of {@link ComputedFeature} values measured at a single instant.
 *
 * <p>Features the API did not report are simply absent from {@link #values()}; they are stored
 * as SQL {@code NULL}, never as {@code 0.0}, because "RPM = 0" (machine stopped) and
 * "RPM unknown" mean very different things to a predictive-maintenance model.
 *
 * @param time   measurement instant (UTC)
 * @param values reported features; an unmodifiable copy is kept
 */
public record ComputedFeatureSample(Instant time, Map<ComputedFeature, Double> values) {

    public ComputedFeatureSample {
        Objects.requireNonNull(time, "time");
        EnumMap<ComputedFeature, Double> copy = new EnumMap<>(ComputedFeature.class);
        values.forEach((feature, value) -> {
            if (value != null) {
                copy.put(feature, value);
            }
        });
        values = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns one feature value.
     *
     * @param feature the feature to read
     * @return the value, or empty if the API did not report it
     */
    public OptionalDouble value(ComputedFeature feature) {
        Double v = values.get(feature);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }
}
