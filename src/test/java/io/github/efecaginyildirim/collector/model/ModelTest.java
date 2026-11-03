package io.github.efecaginyildirim.collector.model;

import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.Plant.Area;
import io.github.efecaginyildirim.collector.model.Plant.Machine;
import io.github.efecaginyildirim.collector.model.Plant.MachineGroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    @Test
    void computedFeatureListMatchesTheVendorContract() {
        List<String> expected = List.of(
            "KURTACCLX", "KURTACCLY", "KURTACCLZ", "PPACCLX", "PPACCLY", "PPACCLZ",
            "CRESTACCLX", "CRESTACCLY", "CRESTACCLZ", "GRMSACCLX", "GRMSACCLY", "GRMSACCLZ",
            "PPSWX", "PPSWY", "PPSWZ", "SWRMSX", "SWRMSY", "SWRMSZ",
            "IDLE", "RPM", "BPFI", "BPFO", "BSF", "FTF", "LOAD", "VRMSX", "VRMSY", "VRMSZ", "TA");
        assertEquals(expected, Arrays.stream(ComputedFeature.values()).map(ComputedFeature::apiName).toList());

        Set<String> columns = Arrays.stream(ComputedFeature.values()).map(ComputedFeature::column).collect(Collectors.toSet());
        assertEquals(29, columns.size());
        assertEquals(ComputedFeature.RPM, ComputedFeature.fromApiName("RPM").orElseThrow());
        assertTrue(ComputedFeature.fromApiName("rpm").isEmpty());
    }

    @Test
    void computedSampleDropsNullsAndIsImmutable() {
        Map<ComputedFeature, Double> values = new HashMap<>();
        values.put(ComputedFeature.RPM, 1480.0);
        values.put(ComputedFeature.TA, null);
        ComputedFeatureSample s = new ComputedFeatureSample(Instant.EPOCH, values);

        assertEquals(1480.0, s.value(ComputedFeature.RPM).orElseThrow());
        assertTrue(s.value(ComputedFeature.TA).isEmpty());
        assertFalse(s.values().containsKey(ComputedFeature.TA));
        assertThrows(UnsupportedOperationException.class, () -> s.values().put(ComputedFeature.IDLE, 0.0));

        values.put(ComputedFeature.LOAD, 50.0);
        assertTrue(s.value(ComputedFeature.LOAD).isEmpty(), "defensive copy");
    }

    @Test
    void basicFeatureDefaultsMissingJsonToEmptyObject() {
        BasicFeature b = new BasicFeature(Instant.EPOCH, null, null, "{\"a\":1}", null);
        assertEquals("{}", b.jsonAvg());
        assertEquals("{\"a\":1}", b.jsonMin());
        assertEquals("{}", b.jsonMax());
    }

    @Test
    void plantLocatesMonitorsInTheHierarchy() {
        MonitorInfo m1 = new MonitorInfo(101, "Pump DE", null, null, MonitorStatus.UNKNOWN);
        MonitorInfo m2 = new MonitorInfo(102, "Pump NDE", null, null, MonitorStatus.UNKNOWN);
        Plant plant = new Plant(1, "Plant", "Org", List.of(
            new Area(10, "Hall A", List.of(
                new MachineGroup(20, "Cooling", List.of(
                    new Machine(30, "Pump 1", List.of(m1)),
                    new Machine(31, "Pump 2", List.of(m2))))))));

        Plant.MonitorPlacement p = plant.locate(102).orElseThrow();
        assertEquals(1, p.plantId());
        assertEquals(10, p.area().id());
        assertEquals(20, p.machineGroup().id());
        assertEquals(31, p.machine().id());
        assertSame(m2, p.monitor());
        assertTrue(plant.locate(999).isEmpty());
    }
}
