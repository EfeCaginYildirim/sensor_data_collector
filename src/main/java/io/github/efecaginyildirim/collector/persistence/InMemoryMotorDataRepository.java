package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Thread-safe, non-durable {@link MotorDataRepository} with the same semantics as the SQL Server
 * implementation. Used by the demo mode and as the reference implementation in tests.
 */
public final class InMemoryMotorDataRepository implements MotorDataRepository {

    private final Map<Integer, MonitorPlacement> monitors = new HashMap<>();
    private final Map<Integer, NavigableMap<Instant, MonitorStatus>> statuses = new HashMap<>();
    private final Map<Integer, NavigableMap<Instant, BasicFeature>> basic = new HashMap<>();
    private final Map<Integer, NavigableMap<Instant, ComputedFeatureSample>> computed = new HashMap<>();
    private final Map<Integer, Instant> watermarks = new HashMap<>();
    private volatile Predicate<MonitorBatch> failWhen = b -> false;

    @Override
    public void initialize() {
        // nothing to create
    }

    @Override
    public synchronized Optional<Instant> watermark(int monitorId) {
        return Optional.ofNullable(watermarks.get(monitorId));
    }

    @Override
    public synchronized SaveResult save(MonitorBatch batch) {
        if (failWhen.test(batch)) {
            throw new PersistenceException("injected failure for monitor " + batch.monitorId(), null);
        }
        int id = batch.monitorId();
        monitors.put(id, batch.placement());
        statuses.computeIfAbsent(id, k -> new TreeMap<>()).put(batch.collectedAt(), batch.placement().monitor().status());

        NavigableMap<Instant, BasicFeature> b = basic.computeIfAbsent(id, k -> new TreeMap<>());
        NavigableMap<Instant, BasicFeature> basicInWindow = b.subMap(batch.window().start(), true, batch.window().end(), false);
        int basicReplaced = basicInWindow.size();
        basicInWindow.clear();
        batch.basic().forEach(f -> b.put(f.time(), f));

        NavigableMap<Instant, ComputedFeatureSample> c = computed.computeIfAbsent(id, k -> new TreeMap<>());
        NavigableMap<Instant, ComputedFeatureSample> computedInWindow = c.subMap(batch.window().start(), true, batch.window().end(), false);
        int computedReplaced = computedInWindow.size();
        computedInWindow.clear();
        batch.computed().forEach(s -> c.put(s.time(), s));

        watermarks.merge(id, batch.window().end(), (old, neu) -> neu.isAfter(old) ? neu : old);
        return new SaveResult(batch.basic().size(), basicReplaced, batch.computed().size(), computedReplaced);
    }

    /**
     * Makes future saves fail, e.g. to test how the collector reacts to a database outage.
     *
     * @param condition batches for which {@link #save(MonitorBatch)} throws
     */
    public void failSavesWhen(Predicate<MonitorBatch> condition) {
        this.failWhen = condition;
    }

    /**
     * @param monitorId monitor id
     * @return stored basic features in time order
     */
    public synchronized List<BasicFeature> basicFeatures(int monitorId) {
        return new ArrayList<>(basic.getOrDefault(monitorId, new TreeMap<>()).values());
    }

    /**
     * @param monitorId monitor id
     * @return stored computed features in time order
     */
    public synchronized List<ComputedFeatureSample> computedFeatures(int monitorId) {
        return new ArrayList<>(computed.getOrDefault(monitorId, new TreeMap<>()).values());
    }

    /**
     * @param monitorId monitor id
     * @return stored status snapshots keyed by collection time
     */
    public synchronized NavigableMap<Instant, MonitorStatus> statusSnapshots(int monitorId) {
        return new TreeMap<>(statuses.getOrDefault(monitorId, new TreeMap<>()));
    }

    /**
     * @param monitorId monitor id
     * @return the stored placement, if the monitor was ever saved
     */
    public synchronized Optional<MonitorPlacement> monitor(int monitorId) {
        return Optional.ofNullable(monitors.get(monitorId));
    }
}
