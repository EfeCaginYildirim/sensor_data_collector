package io.github.efecaginyildirim.collector.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregated basic measurements of one monitor for one trend interval (typically one day).
 *
 * <p>The vendor delivers the averages, minima and maxima as JSON documents embedded in string
 * fields; they are kept verbatim so no information is lost.
 *
 * @param time     start of the aggregation interval (UTC)
 * @param sourceId identifier assigned by the vendor, may be {@code null}
 * @param jsonAvg  JSON object with average values, never {@code null} ({@code "{}"} if absent)
 * @param jsonMin  JSON object with minimum values, never {@code null}
 * @param jsonMax  JSON object with maximum values, never {@code null}
 */
public record BasicFeature(Instant time, String sourceId, String jsonAvg, String jsonMin, String jsonMax) {

    public BasicFeature {
        Objects.requireNonNull(time, "time");
        jsonAvg = jsonAvg == null ? "{}" : jsonAvg;
        jsonMin = jsonMin == null ? "{}" : jsonMin;
        jsonMax = jsonMax == null ? "{}" : jsonMax;
    }
}
