package io.github.efecaginyildirim.collector.api;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.util.List;

/**
 * The vendor condition-monitoring API, as seen by the collector.
 *
 * <p>Implementations handle authentication, retries and response parsing. They are
 * thread-safe: one instance is shared by all collection workers.
 */
public interface MonitoringApi {

    /**
     * Fetches the plant hierarchy with the current status of every monitor.
     *
     * @param plantId vendor plant id
     * @return the plant
     * @throws ApiException if the request fails or the response cannot be parsed
     */
    Plant fetchPlant(int plantId);

    /**
     * Fetches daily basic-feature aggregates.
     *
     * @param monitorId vendor monitor id
     * @param window    half-open time window
     * @return samples with {@code window.contains(time)}, sorted by time, one per timestamp
     * @throws ApiException if the request fails or the response cannot be parsed
     */
    ApiPage<BasicFeature> fetchBasicFeatures(int monitorId, TimeWindow window);

    /**
     * Fetches computed vibration features.
     *
     * @param monitorId vendor monitor id
     * @param window    half-open time window
     * @return samples with {@code window.contains(time)}, sorted by time, one per timestamp
     * @throws ApiException if the request fails or the response cannot be parsed
     */
    ApiPage<ComputedFeatureSample> fetchComputedFeatures(int monitorId, TimeWindow window);

    /**
     * The usable items of one response plus counts of what had to be discarded.
     *
     * @param items      valid samples
     * @param rejected   items dropped because their timestamp was missing or unparseable
     * @param duplicates items dropped because another item had the same timestamp
     * @param <T>        sample type
     */
    record ApiPage<T>(List<T> items, int rejected, int duplicates) {
        public ApiPage {
            items = List.copyOf(items);
        }
    }
}
