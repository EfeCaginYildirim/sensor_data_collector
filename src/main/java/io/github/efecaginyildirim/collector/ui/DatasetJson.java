package io.github.efecaginyildirim.collector.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.efecaginyildirim.collector.dataset.ChangeFilter;
import io.github.efecaginyildirim.collector.dataset.CsvWriter;
import io.github.efecaginyildirim.collector.dataset.DatasetPipeline;
import io.github.efecaginyildirim.collector.dataset.DatasetSpec;
import io.github.efecaginyildirim.collector.dataset.ExcludeRule;
import io.github.efecaginyildirim.collector.dataset.Sampling;
import io.github.efecaginyildirim.collector.model.ComputedFeature;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * JSON form of {@link DatasetSpec}, shared by the HTTP API and the saved presets.
 *
 * <pre>{@code
 * {"monitorIds": [1001, 1004], "from": "2025-09-01", "to": null,
 *  "features": ["KURTACCLX", "BPFO"],
 *  "rules": [{"feature": "IDLE", "op": ">", "value": 0.5}],
 *  "dropIncomplete": false, "sampling": "daily",
 *  "changeFilter": {"mode": "percent", "threshold": 2}, "applyManualExclusions": true}
 * }</pre>
 * Parsing failures are reported as {@link IllegalArgumentException}s naming the offending field.
 */
final class DatasetJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DatasetJson() { }

    static DatasetSpec spec(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("spec must be a JSON object");
        }
        List<Integer> monitors = new ArrayList<>();
        for (JsonNode m : array(n, "monitorIds")) {
            if (!m.canConvertToInt()) {
                throw new IllegalArgumentException("monitorIds must contain integers");
            }
            monitors.add(m.asInt());
        }
        List<ComputedFeature> features = features(n.get("features"), "features");
        List<ExcludeRule> rules = new ArrayList<>();
        for (JsonNode r : array(n, "rules")) {
            JsonNode value = r.get("value");
            if (value == null || !value.isNumber()) {
                throw new IllegalArgumentException("each rule needs a numeric value");
            }
            rules.add(new ExcludeRule(feature(r.path("feature").asText()),
                ExcludeRule.Comparison.fromSymbol(r.path("op").asText()), value.asDouble()));
        }
        Optional<ChangeFilter> change = Optional.empty();
        JsonNode cf = n.get("changeFilter");
        if (cf != null && !cf.isNull()) {
            JsonNode threshold = cf.get("threshold");
            if (threshold == null || !threshold.isNumber()) {
                throw new IllegalArgumentException("changeFilter needs a numeric threshold");
            }
            change = Optional.of(new ChangeFilter(enumValue(ChangeFilter.Mode.class, cf.path("mode").asText("absolute"), "changeFilter.mode"),
                threshold.asDouble()));
        }
        return new DatasetSpec(monitors, date(n, "from"), date(n, "to"), features, rules,
            n.path("dropIncomplete").asBoolean(false),
            enumValue(Sampling.class, n.path("sampling").asText("raw"), "sampling"),
            change,
            n.path("applyManualExclusions").asBoolean(true));
    }

    static ObjectNode toJson(DatasetSpec s) {
        ObjectNode n = JSON.createObjectNode();
        ArrayNode monitors = n.putArray("monitorIds");
        s.monitorIds().forEach(monitors::add);
        n.put("from", s.from().map(LocalDate::toString).orElse(null));
        n.put("to", s.to().map(LocalDate::toString).orElse(null));
        ArrayNode features = n.putArray("features");
        s.features().forEach(f -> features.add(f.apiName()));
        ArrayNode rules = n.putArray("rules");
        s.rules().forEach(r -> rules.addObject().put("feature", r.feature().apiName()).put("op", r.op().symbol()).put("value", r.value()));
        n.put("dropIncomplete", s.dropIncomplete());
        n.put("sampling", s.sampling().name().toLowerCase(Locale.ROOT));
        if (s.changeFilter().isPresent()) {
            n.putObject("changeFilter").put("mode", s.changeFilter().get().mode().name().toLowerCase(Locale.ROOT))
                .put("threshold", s.changeFilter().get().threshold());
        } else {
            n.putNull("changeFilter");
        }
        n.put("applyManualExclusions", s.applyManualExclusions());
        return n;
    }

    static ObjectNode stats(DatasetPipeline.Stats s) {
        return JSON.createObjectNode()
            .put("read", s.read())
            .put("excludedManually", s.excludedManually())
            .put("excludedByRules", s.excludedByRules())
            .put("afterSampling", s.afterSampling())
            .put("droppedIncomplete", s.droppedIncomplete())
            .put("droppedUnchanged", s.droppedUnchanged())
            .put("written", s.written());
    }

    static CsvWriter.Format format(JsonNode n) {
        if (n == null || n.isNull()) {
            return CsvWriter.Format.STANDARD;
        }
        String separator = n.path("separator").asText(",");
        String decimal = n.path("decimal").asText(".");
        char sep = switch (separator) {
            case ",", "comma" -> ',';
            case ";", "semicolon" -> ';';
            case "\t", "tab" -> '\t';
            default -> throw new IllegalArgumentException("separator must be comma, semicolon or tab");
        };
        if (decimal.length() != 1) {
            throw new IllegalArgumentException("decimal mark must be '.' or ','");
        }
        return new CsvWriter.Format(sep, decimal.charAt(0));
    }

    /**
     * @param a     a JSON array of column names, or null
     * @param field field name for messages
     * @return the columns, in order
     */
    static List<ComputedFeature> features(JsonNode a, String field) {
        List<ComputedFeature> out = new ArrayList<>();
        for (JsonNode f : elements(a, field)) {
            out.add(feature(f.asText()));
        }
        return out;
    }

    private static Iterable<JsonNode> array(JsonNode n, String field) {
        return elements(n == null ? null : n.get(field), field);
    }

    private static Iterable<JsonNode> elements(JsonNode a, String field) {
        if (a == null || a.isNull()) {
            return List.of();
        }
        if (!a.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return a;
    }

    private static ComputedFeature feature(String name) {
        return ComputedFeature.fromApiName(name.toUpperCase(Locale.ROOT))
            .orElseThrow(() -> new IllegalArgumentException("unknown column " + name));
    }

    private static Optional<LocalDate> date(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(v.asText()));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(field + " must be a date like 2025-09-01");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " has an unknown value '" + value + "'");
        }
    }
}
