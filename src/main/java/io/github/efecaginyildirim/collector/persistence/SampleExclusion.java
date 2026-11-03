package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.Objects;

/**
 * A time range of one monitor that the user marked as not to be used, e.g. a sensor fault or a
 * maintenance stop. The samples themselves stay in the database; exclusions are applied when a
 * dataset is read.
 *
 * @param id        storage id
 * @param monitorId monitor id
 * @param window    excluded half-open range
 * @param reason    why the range is excluded, as entered by the user
 * @param createdAt when the exclusion was created
 */
public record SampleExclusion(long id, int monitorId, TimeWindow window, String reason, Instant createdAt) {

    public SampleExclusion {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * @param monitor monitor of a sample
     * @param time    time of a sample
     * @return whether the sample falls into this exclusion
     */
    public boolean covers(int monitor, Instant time) {
        return monitor == monitorId && window.contains(time);
    }
}
