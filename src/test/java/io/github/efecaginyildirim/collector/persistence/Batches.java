package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.MonitorInfo.Reading;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.model.Plant.MonitorPlacement;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Test data builders for persistence tests. */
final class Batches {

    static final Instant T0 = Instant.parse("2025-05-01T00:00:00Z");

    private Batches() { }

    static MonitorPlacement placement(int monitorId) {
        MonitorStatus status = new MonitorStatus(87, 1, 0, null, 1485.0,
            new Reading(1.9, 2.8, 4.5, 7.1), Reading.UNKNOWN, new Reading(1.1, null, null, null), Reading.UNKNOWN);
        MonitorInfo monitor = new MonitorInfo(monitorId, "Bearing DE", "SN-1", "H", status);
        Plant.Machine machine = new Plant.Machine(30, "Motor M-01", List.of(monitor));
        Plant.MachineGroup group = new Plant.MachineGroup(20, "Line 1", List.of(machine));
        Plant.Area area = new Plant.Area(10, "Hall A", List.of(group));
        return new MonitorPlacement(1, area, group, machine, monitor);
    }

    /** Daily basic features and 6-hourly computed features covering {@code [from, from + days)}. */
    static MonitorBatch batch(int monitorId, Instant from, int days) {
        TimeWindow window = new TimeWindow(from, from.plus(Duration.ofDays(days)));
        List<BasicFeature> basic = new ArrayList<>();
        List<ComputedFeatureSample> computed = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            Instant day = from.plus(Duration.ofDays(d));
            basic.add(new BasicFeature(day, "bf-" + d, "{\"velocityX\":" + d + "}", "{}", "{}"));
            for (int h = 0; h < 24; h += 6) {
                Map<ComputedFeature, Double> v = new EnumMap<>(ComputedFeature.class);
                v.put(ComputedFeature.RPM, 1480.0 + d);
                v.put(ComputedFeature.KURTACCLX, 3.0);
                computed.add(new ComputedFeatureSample(day.plus(Duration.ofHours(h)), v));
            }
        }
        return new MonitorBatch(placement(monitorId), window.end(), window, basic, computed);
    }
}
