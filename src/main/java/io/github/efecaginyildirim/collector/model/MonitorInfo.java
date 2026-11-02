package io.github.efecaginyildirim.collector.model;

/**
 * Static description and current status of one sensor ("monitor").
 *
 * <p>Status values are a snapshot taken when the plant was queried; they are stored with the
 * collection timestamp instead of being copied onto historical samples.
 *
 * @param id               vendor monitor id
 * @param name             display name
 * @param deviceIdentifier hardware identifier, may be {@code null}
 * @param orientationCode  mounting orientation, may be {@code null}
 * @param status           current health snapshot
 */
public record MonitorInfo(int id, String name, String deviceIdentifier, String orientationCode, MonitorStatus status) {

    /**
     * Health snapshot of a monitor. Every field may be {@code null} when the API omits it.
     *
     * @param healthScore vendor health score (higher is healthier)
     * @param status      vendor status code
     * @param trend       vendor trend code
     * @param alarms      number of active alarms
     * @param speed       shaft speed
     * @param velocityX   RMS velocity and thresholds, X axis
     * @param velocityY   RMS velocity and thresholds, Y axis
     * @param velocityZ   RMS velocity and thresholds, Z axis
     * @param temperature temperature and thresholds
     */
    public record MonitorStatus(
            Integer healthScore,
            Integer status,
            Integer trend,
            Integer alarms,
            Double speed,
            Reading velocityX,
            Reading velocityY,
            Reading velocityZ,
            Reading temperature) {

        /** A status with every field unknown. */
        public static final MonitorStatus UNKNOWN = new MonitorStatus(
            null, null, null, null, null, Reading.UNKNOWN, Reading.UNKNOWN, Reading.UNKNOWN, Reading.UNKNOWN);
    }

    /**
     * A measured value together with its alarm thresholds; any field may be {@code null}.
     *
     * @param value       current value
     * @param operational upper bound of normal operation
     * @param caution     caution threshold
     * @param warning     warning threshold
     */
    public record Reading(Double value, Double operational, Double caution, Double warning) {
        /** A reading with every field unknown. */
        public static final Reading UNKNOWN = new Reading(null, null, null, null);
    }
}
