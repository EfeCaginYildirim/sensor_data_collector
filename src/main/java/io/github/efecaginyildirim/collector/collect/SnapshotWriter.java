package io.github.efecaginyildirim.collector.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.efecaginyildirim.collector.collect.RunReport.MonitorOutcome;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.persistence.MonitorBatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Writes a JSON archive of the data saved by one run.
 *
 * <p>Files are named {@code run-yyyyMMdd'T'HHmmss'Z'-<runId>.json}, encoded as UTF-8 regardless of
 * the platform default, and written to a temporary file first and then moved into place, so a
 * crash never leaves a truncated snapshot behind.
 */
public final class SnapshotWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path directory;

    /**
     * @param directory target directory; created if missing
     */
    public SnapshotWriter(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Writes one snapshot.
     *
     * @param report  the run's report (status and per-monitor outcomes are included)
     * @param batches the batches that were saved
     * @return the written file
     * @throws IOException if the file cannot be written
     */
    public Path write(RunReport report, List<MonitorBatch> batches) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("run-" + FILE_TIME.format(report.startedAt()) + "-" + report.runId() + ".json");
        Path temp = Files.createTempFile(directory, ".run-", ".json.tmp");
        try {
            Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(report, batches)),
                StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static ObjectNode toJson(RunReport report, List<MonitorBatch> batches) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", report.runId());
        root.put("startedAt", report.startedAt().toString());
        root.put("status", report.status().name());
        ArrayNode outcomes = root.putArray("outcomes");
        for (MonitorOutcome o : report.monitors()) {
            ObjectNode n = outcomes.addObject();
            n.put("monitorId", o.monitorId());
            n.put("outcome", o.outcome().name());
            o.collectedUntil().ifPresent(t -> n.put("collectedUntil", t.toString()));
            o.error().ifPresent(e -> n.put("error", e));
        }
        ArrayNode monitors = root.putArray("monitors");
        for (MonitorBatch b : batches) {
            ObjectNode m = monitors.addObject();
            m.put("monitorId", b.monitorId());
            m.put("machine", b.placement().machine().name());
            m.put("monitor", b.placement().monitor().name());
            m.put("from", b.window().start().toString());
            m.put("to", b.window().end().toString());
            ArrayNode basic = m.putArray("basicFeatures");
            for (BasicFeature f : b.basic()) {
                basic.addObject()
                    .put("time", f.time().toString())
                    .put("sourceId", f.sourceId())
                    .put("jsonAvg", f.jsonAvg())
                    .put("jsonMin", f.jsonMin())
                    .put("jsonMax", f.jsonMax());
            }
            ArrayNode computed = m.putArray("computedFeatures");
            for (ComputedFeatureSample s : b.computed()) {
                ObjectNode n = computed.addObject();
                n.put("time", s.time().toString());
                s.values().forEach((feature, value) -> n.put(feature.apiName(), value));
            }
        }
        return root;
    }
}
