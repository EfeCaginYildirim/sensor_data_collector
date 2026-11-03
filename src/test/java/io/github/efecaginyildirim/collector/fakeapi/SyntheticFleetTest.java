package io.github.efecaginyildirim.collector.fakeapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.efecaginyildirim.collector.fakeapi.SyntheticFleet.Options;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticFleetTest {

    static final Instant REF = Instant.parse("2025-06-01T00:00:00Z");
    static final List<Integer> IDS = List.of(101, 102, 103, 104, 105, 106, 107);

    static SyntheticFleet fleet(Options o) {
        return SyntheticFleet.create(1, IDS, o);
    }

    @Test
    void sameSeedProducesIdenticalData() {
        SyntheticFleet a = fleet(Options.defaults(42, REF));
        SyntheticFleet b = fleet(Options.defaults(42, REF));
        assertEquals(a.plantJson(REF), b.plantJson(REF));
        assertEquals(a.computedFeatures(101, REF.minus(Duration.ofDays(3)), REF),
                     b.computedFeatures(101, REF.minus(Duration.ofDays(3)), REF));
        assertNotEquals(a.computedFeatures(101, REF.minus(Duration.ofDays(3)), REF),
                        fleet(Options.defaults(43, REF)).computedFeatures(101, REF.minus(Duration.ofDays(3)), REF));
    }

    @Test
    void samplesDoNotDependOnHowTheRangeIsSplit() {
        SyntheticFleet f = fleet(Options.defaults(7, REF));
        Instant a = REF.minus(Duration.ofDays(20));
        Instant b = REF.minus(Duration.ofDays(9)).plusSeconds(1); // off-grid split point
        Instant c = REF;

        List<ObjectNode> whole = f.computedFeatures(103, a, c);
        List<ObjectNode> parts = new ArrayList<>(f.computedFeatures(103, a, b));
        parts.addAll(f.computedFeatures(103, b, c));
        assertEquals(whole, parts);

        List<ObjectNode> basicWhole = f.basicFeatures(103, a, c);
        List<ObjectNode> basicParts = new ArrayList<>(f.basicFeatures(103, a, b));
        basicParts.addAll(f.basicFeatures(103, b, c));
        assertEquals(basicWhole, basicParts);
    }

    @Test
    void boundsAreInclusiveAndOnTheGrid() {
        SyntheticFleet f = fleet(Options.defaults(1, REF));
        List<ObjectNode> s = f.computedFeatures(101, REF, REF.plus(Duration.ofHours(12)));
        assertEquals(List.of("2025-06-01T00:00:00.000Z", "2025-06-01T06:00:00.000Z", "2025-06-01T12:00:00.000Z"),
            s.stream().map(n -> n.get("timestamp").asText()).toList());

        List<ObjectNode> days = f.basicFeatures(101, REF.minusSeconds(1), REF.plus(Duration.ofDays(2)));
        assertEquals(List.of("2025-06-01T00:00:00.000Z", "2025-06-02T00:00:00.000Z", "2025-06-03T00:00:00.000Z"),
            days.stream().map(n -> n.get("time").asText()).toList());
    }

    @Test
    void basicFeatureAggregatesAreJsonEncodedStrings() throws Exception {
        ObjectNode bf = fleet(Options.defaults(1, REF)).basicFeatures(102, REF, REF).get(0);
        assertTrue(bf.get("jsonAvg").isTextual());
        JsonNode avg = new ObjectMapper().readTree(bf.get("jsonAvg").asText());
        JsonNode max = new ObjectMapper().readTree(bf.get("jsonMax").asText());
        assertTrue(avg.get("velocityX").asDouble() > 0);
        assertTrue(max.get("velocityX").asDouble() > avg.get("velocityX").asDouble());
    }

    @Test
    void everyThirdMotorDegradesAndHealthyOnesDoNot() {
        SyntheticFleet f = fleet(Options.defaults(5, REF));
        SyntheticFleet.MotorProfile degrading = f.profile(101);
        SyntheticFleet.MotorProfile healthy = f.profile(102);

        assertNotNull(degrading.degradationOnset());
        assertTrue(degrading.degradationOnset().isBefore(REF));
        assertNull(healthy.degradationOnset());
        assertNotNull(f.profile(104).degradationOnset());

        Instant before = degrading.degradationOnset().minus(Duration.ofDays(10));
        double kurtBefore = mean(f.computedFeatures(101, before, before.plus(Duration.ofDays(3))), "KURTACCLX");
        double kurtAfter = mean(f.computedFeatures(101, REF.minus(Duration.ofDays(3)), REF), "KURTACCLX");
        double bpfoBefore = mean(f.computedFeatures(101, before, before.plus(Duration.ofDays(3))), "BPFO");
        double bpfoAfter = mean(f.computedFeatures(101, REF.minus(Duration.ofDays(3)), REF), "BPFO");
        assertTrue(kurtAfter > kurtBefore, kurtBefore + " -> " + kurtAfter);
        assertTrue(bpfoAfter > 2 * bpfoBefore, bpfoBefore + " -> " + bpfoAfter);

        JsonNode monitors = f.plantJson(REF).at("/areas/0/machineGroups/0/machines/0/monitors");
        int degradingScore = monitors.get(0).get("healthScore").asInt();
        int healthyScore = monitors.get(1).get("healthScore").asInt();
        assertEquals(100, healthyScore);
        assertTrue(degradingScore < 100);
    }

    @Test
    void valuesStayInPlausibleRanges() {
        SyntheticFleet f = fleet(new Options(9, REF, Duration.ofHours(6), 0.0, 0.0));
        for (int id : IDS) {
            for (ObjectNode s : f.computedFeatures(id, REF.minus(Duration.ofDays(60)), REF)) {
                assertBetween(s, "RPM", 1400, 3000);
                assertBetween(s, "LOAD", 0, 100);
                assertBetween(s, "IDLE", 0, 1);
                assertBetween(s, "KURTACCLX", 1.5, 12);
                assertBetween(s, "VRMSX", 0, 25);
                assertBetween(s, "TA", 20, 70);
            }
        }
    }

    @Test
    void missingAndDuplicateRatesAreHonoured() {
        Instant from = REF.minus(Duration.ofDays(10));
        List<ObjectNode> clean = fleet(new Options(3, REF, Duration.ofHours(6), 0.0, 0.0)).computedFeatures(101, from, REF);
        assertTrue(clean.stream().noneMatch(s -> StreamSupport.stream(s.spliterator(), false).anyMatch(JsonNode::isNull)));

        List<ObjectNode> holes = fleet(new Options(3, REF, Duration.ofHours(6), 0.5, 0.0)).computedFeatures(101, from, REF);
        assertTrue(holes.stream().anyMatch(s -> s.get("RPM").isNull()));

        List<ObjectNode> dupes = fleet(new Options(3, REF, Duration.ofHours(6), 0.0, 1.0)).computedFeatures(101, from, REF);
        assertEquals(2 * clean.size(), dupes.size());
    }

    @Test
    void plantHierarchyGroupsTwoMonitorsPerMachineAndSixPerGroup() {
        JsonNode plant = fleet(Options.defaults(1, REF)).plantJson(REF);
        JsonNode groups = plant.at("/areas/0/machineGroups");
        assertEquals(2, groups.size());
        assertEquals(3, groups.get(0).get("machines").size());
        assertEquals(1, groups.get(1).get("machines").size());
        List<Integer> ids = new ArrayList<>();
        groups.forEach(g -> g.get("machines").forEach(m -> m.get("monitors").forEach(mon -> ids.add(mon.get("id").asInt()))));
        assertEquals(IDS, ids);
        assertEquals(2.8, groups.at("/0/machines/0/monitors/0/velocityXOperationalThreshold").asDouble());
    }

    private static double mean(List<ObjectNode> samples, String field) {
        return samples.stream().mapToDouble(s -> s.get(field).asDouble()).average().orElseThrow();
    }

    private static void assertBetween(ObjectNode s, String field, double lo, double hi) {
        double v = s.get(field).asDouble();
        assertTrue(v >= lo && v <= hi, field + "=" + v + " at " + s.get("timestamp"));
    }
}
