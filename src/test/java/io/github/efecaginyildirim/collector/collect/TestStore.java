package io.github.efecaginyildirim.collector.collect;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.persistence.DatasetQuery;
import io.github.efecaginyildirim.collector.persistence.H2Database;
import io.github.efecaginyildirim.collector.persistence.JdbcMotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.MonitorBatch;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader.StatusSnapshot;
import io.github.efecaginyildirim.collector.persistence.MotorDataRepository;
import io.github.efecaginyildirim.collector.persistence.PersistenceException;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An in-memory H2 store for collection tests, with convenience readers and fault injection.
 * It decorates the real repository, so every test exercises the production SQL.
 */
final class TestStore implements MotorDataRepository, AutoCloseable {

    private static final TimeWindow ALL = new TimeWindow(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"));

    final JdbcMotorDataRepository delegate = H2Database.inMemory();
    private volatile Predicate<MonitorBatch> failWhen = b -> false;

    @Override
    public void initialize() {
        delegate.initialize();
    }

    @Override
    public Optional<Instant> watermark(int monitorId) {
        return delegate.watermark(monitorId);
    }

    @Override
    public SaveResult save(MonitorBatch batch) {
        if (failWhen.test(batch)) {
            throw new PersistenceException("injected failure for monitor " + batch.monitorId(), null);
        }
        return delegate.save(batch);
    }

    void failSavesWhen(Predicate<MonitorBatch> condition) {
        this.failWhen = condition;
    }

    List<BasicFeature> basicFeatures(int monitorId) {
        return delegate.basicFeatures(monitorId, ALL);
    }

    List<ComputedFeatureSample> computedFeatures(int monitorId) {
        List<ComputedFeatureSample> out = new ArrayList<>();
        ComputedFeature[] features = ComputedFeature.values();
        delegate.query(DatasetQuery.allFeatures(List.of(monitorId), ALL), row -> {
            Map<ComputedFeature, Double> v = new EnumMap<>(ComputedFeature.class);
            for (int i = 0; i < features.length; i++) {
                v.put(features[i], row.values().get(i));
            }
            out.add(new ComputedFeatureSample(row.time(), v));
        });
        return out;
    }

    List<StatusSnapshot> statusSnapshots(int monitorId) {
        return delegate.statusHistory(monitorId, 1_000);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
