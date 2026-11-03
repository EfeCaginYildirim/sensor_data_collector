package io.github.efecaginyildirim.collector.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Vibration features computed by the sensor vendor for every measurement.
 *
 * <p>This enum is the single source of truth for feature names: the JSON field names, the
 * database column names and the generated SQL are all derived from it, so adding a feature is
 * a one-line change.
 */
public enum ComputedFeature {
    /** Kurtosis of acceleration, X axis. Rises with impulsive faults such as bearing damage. */
    KURTACCLX,
    /** Kurtosis of acceleration, Y axis. */
    KURTACCLY,
    /** Kurtosis of acceleration, Z axis. */
    KURTACCLZ,
    /** Peak-to-peak acceleration, X axis. */
    PPACCLX,
    /** Peak-to-peak acceleration, Y axis. */
    PPACCLY,
    /** Peak-to-peak acceleration, Z axis. */
    PPACCLZ,
    /** Crest factor of acceleration, X axis. */
    CRESTACCLX,
    /** Crest factor of acceleration, Y axis. */
    CRESTACCLY,
    /** Crest factor of acceleration, Z axis. */
    CRESTACCLZ,
    /** RMS acceleration in g, X axis. */
    GRMSACCLX,
    /** RMS acceleration in g, Y axis. */
    GRMSACCLY,
    /** RMS acceleration in g, Z axis. */
    GRMSACCLZ,
    /** Peak-to-peak of the shock-wave envelope, X axis. */
    PPSWX,
    /** Peak-to-peak of the shock-wave envelope, Y axis. */
    PPSWY,
    /** Peak-to-peak of the shock-wave envelope, Z axis. */
    PPSWZ,
    /** RMS of the shock-wave envelope, X axis. */
    SWRMSX,
    /** RMS of the shock-wave envelope, Y axis. */
    SWRMSY,
    /** RMS of the shock-wave envelope, Z axis. */
    SWRMSZ,
    /** Fraction of the measurement window in which the machine was idle (0–1). */
    IDLE,
    /** Shaft speed in revolutions per minute. */
    RPM,
    /** Ball-pass frequency, inner race — amplitude at the bearing defect frequency. */
    BPFI,
    /** Ball-pass frequency, outer race. */
    BPFO,
    /** Ball spin frequency. */
    BSF,
    /** Fundamental train (cage) frequency. */
    FTF,
    /** Estimated load in percent. */
    LOAD,
    /** RMS velocity in mm/s, X axis. */
    VRMSX,
    /** RMS velocity in mm/s, Y axis. */
    VRMSY,
    /** RMS velocity in mm/s, Z axis. */
    VRMSZ,
    /** Ambient / surface temperature reported with the measurement. */
    TA;

    /** @return the field name used by the vendor API, e.g. {@code KURTACCLX} */
    public String apiName() {
        return name();
    }

    /** @return the database column name, e.g. {@code kurtacclx} */
    public String column() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks up a feature by its API field name.
     *
     * @param apiName field name as sent by the API
     * @return the feature, or empty for unknown names
     */
    public static Optional<ComputedFeature> fromApiName(String apiName) {
        for (ComputedFeature f : values()) {
            if (f.apiName().equals(apiName)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
