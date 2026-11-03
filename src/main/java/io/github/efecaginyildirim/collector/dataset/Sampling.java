package io.github.efecaginyildirim.collector.dataset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Time resolution of a dataset: the raw samples or their hourly / daily means (UTC buckets). */
public enum Sampling {
    /** Every stored sample. */
    RAW,
    /** Mean per monitor and UTC hour; the row time is the start of the hour. */
    HOURLY,
    /** Mean per monitor and UTC day; the row time is midnight UTC. */
    DAILY;

    /**
     * @param time sample time
     * @return start of the bucket the sample belongs to ({@code time} itself for {@link #RAW})
     */
    public Instant bucket(Instant time) {
        return switch (this) {
            case RAW -> time;
            case HOURLY -> time.truncatedTo(ChronoUnit.HOURS);
            case DAILY -> time.truncatedTo(ChronoUnit.DAYS);
        };
    }
}
