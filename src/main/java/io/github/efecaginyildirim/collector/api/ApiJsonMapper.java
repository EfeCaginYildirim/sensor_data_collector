package io.github.efecaginyildirim.collector.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.efecaginyildirim.collector.api.MonitoringApi.ApiPage;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.MonitorInfo.MonitorStatus;
import io.github.efecaginyildirim.collector.model.MonitorInfo.Reading;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import io.github.efecaginyildirim.collector.time.Timestamps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Converts vendor JSON into domain records.
 *
 * <p>The mapper is lenient about optional data and strict about identity: a missing or
 * non-numeric optional field becomes {@code null} (never {@code 0}), whereas a missing id makes
 * the response unusable. Samples are filtered to the requested half-open window and
 * de-duplicated by timestamp (the last occurrence wins), because the API's bounds are inclusive.
 */
final class ApiJsonMapper {

    private ApiJsonMapper() { }

    /**
     * Maps a {@code GET /plants/{id}} response; accepts the plant either under {@code data} or at the root.
     *
     * @param root parsed response body
     * @return the plant
     * @throws IllegalArgumentException if required ids are missing
     */
    static Plant plant(JsonNode root) {
        JsonNode p = root.path("data").isObject() ? root.get("data") : root;
        List<Plant.Area> areas = new ArrayList<>();
        for (JsonNode a : array(p, "areas")) {
            List<Plant.MachineGroup> groups = new ArrayList<>();
            for (JsonNode g : array(a, "machineGroups")) {
                List<Plant.Machine> machines = new ArrayList<>();
                for (JsonNode m : array(g, "machines")) {
                    List<MonitorInfo> monitors = new ArrayList<>();
                    for (JsonNode mon : array(m, "monitors")) {
                        monitors.add(monitor(mon));
                    }
                    machines.add(new Plant.Machine(requiredId(m, "machine"), text(m, "name"), monitors));
                }
                groups.add(new Plant.MachineGroup(requiredId(g, "machine group"), text(g, "name"), machines));
            }
            areas.add(new Plant.Area(requiredId(a, "area"), text(a, "name"), groups));
        }
        return new Plant(requiredId(p, "plant"), text(p, "name"), text(p, "organizationName"), areas);
    }

    private static MonitorInfo monitor(JsonNode n) {
        MonitorStatus status = new MonitorStatus(
            integer(n, "healthScore"), integer(n, "status"), integer(n, "trend"), integer(n, "alarms"),
            decimal(n, "speed"),
            reading(n, "velocityX"), reading(n, "velocityY"), reading(n, "velocityZ"), reading(n, "temperature"));
        return new MonitorInfo(requiredId(n, "monitor"), text(n, "name"), text(n, "deviceIdentifier"),
            text(n, "orientationCode"), status);
    }

    private static Reading reading(JsonNode n, String prefix) {
        return new Reading(decimal(n, prefix), decimal(n, prefix + "OperationalThreshold"),
            decimal(n, prefix + "CautionThreshold"), decimal(n, prefix + "WarningThreshold"));
    }

    /**
     * Maps a {@code GET /trend-history} response in either the current
     * ({@code data.basicFeatures}) or the legacy ({@code data} is the array) shape.
     *
     * @param root   parsed response body
     * @param window requested window; samples outside it are dropped
     * @return samples in the window
     */
    static ApiPage<BasicFeature> basicFeatures(JsonNode root, TimeWindow window) {
        JsonNode data = root.path("data");
        JsonNode items = data.isArray() ? data : data.path("basicFeatures");
        Collector<BasicFeature> c = new Collector<>(window);
        for (JsonNode item : iterable(items)) {
            c.add(Timestamps.parse(text(item, "time")), t -> new BasicFeature(t, text(item, "id"),
                embeddedJson(item, "jsonAvg"), embeddedJson(item, "jsonMin"), embeddedJson(item, "jsonMax")));
        }
        return c.page();
    }

    /**
     * Maps a {@code GET /trend/computed-features} response.
     *
     * @param root   parsed response body
     * @param window requested window; samples outside it are dropped
     * @return samples in the window
     */
    static ApiPage<ComputedFeatureSample> computedFeatures(JsonNode root, TimeWindow window) {
        Collector<ComputedFeatureSample> c = new Collector<>(window);
        for (JsonNode item : iterable(root.path("data"))) {
            c.add(Timestamps.parse(text(item, "timestamp")), t -> {
                Map<ComputedFeature, Double> values = new EnumMap<>(ComputedFeature.class);
                for (ComputedFeature f : ComputedFeature.values()) {
                    Double v = decimal(item, f.apiName());
                    if (v != null) {
                        values.put(f, v);
                    }
                }
                return new ComputedFeatureSample(t, values);
            });
        }
        return c.page();
    }

    /** Filters to the window, counts rejects and keeps the last item per timestamp. */
    private static final class Collector<T> {
        private final TimeWindow window;
        private final TreeMap<Instant, T> byTime = new TreeMap<>();
        private int rejected;
        private int duplicates;

        Collector(TimeWindow window) {
            this.window = window;
        }

        void add(Optional<Instant> time, Function<Instant, T> factory) {
            if (time.isEmpty()) {
                rejected++;
                return;
            }
            if (!window.contains(time.get())) {
                return;
            }
            if (byTime.put(time.get(), factory.apply(time.get())) != null) {
                duplicates++;
            }
        }

        ApiPage<T> page() {
            return new ApiPage<>(new ArrayList<>(byTime.values()), rejected, duplicates);
        }
    }

    // ─── Field helpers ───────────────────────────────────────────────────────────

    private static Iterable<JsonNode> array(JsonNode parent, String field) {
        return iterable(parent.path(field));
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node.isArray() ? node : List.of();
    }

    private static int requiredId(JsonNode n, String what) {
        JsonNode id = n.get("id");
        if (id != null && id.isIntegralNumber() && id.canConvertToInt()) {
            return id.intValue();
        }
        if (id != null && id.isTextual()) {
            try {
                return Integer.parseInt(id.asText().strip());
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
        }
        throw new IllegalArgumentException(what + " without a numeric id: " + abbreviate(n.toString()));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** The vendor sends aggregates as JSON-in-a-string; accept a nested object too. */
    private static String embeddedJson(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.asText() : v.toString();
    }

    static Double decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isTextual()) {
            try {
                double d = Double.parseDouble(v.asText().strip());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Integer integer(JsonNode n, String field) {
        Double d = decimal(n, field);
        return d == null || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE ? null : d.intValue();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
