package io.github.efecaginyildirim.collector.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.efecaginyildirim.collector.dataset.DatasetSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named dataset selections, saved as {@code presets.json} in the data directory so that the same
 * dataset can be produced again later.
 */
final class PresetStore {

    /**
     * A saved selection.
     *
     * @param name    unique name
     * @param spec    the selection
     * @param savedAt when it was last saved
     */
    record Preset(String name, DatasetSpec spec, Instant savedAt) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    PresetStore(Path file) {
        this.file = file;
    }

    synchronized List<Preset> list() {
        return new ArrayList<>(read().values());
    }

    /**
     * Creates or replaces a preset.
     *
     * @param name 1 to 60 printable characters
     * @param spec the selection
     * @param now  save time
     * @return the saved preset
     */
    synchronized Preset save(String name, DatasetSpec spec, Instant now) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60 || n.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a preset name needs 1 to 60 printable characters");
        }
        Map<String, Preset> presets = read();
        Preset preset = new Preset(n, spec, now);
        presets.put(n, preset);
        write(presets);
        return preset;
    }

    synchronized boolean delete(String name) {
        Map<String, Preset> presets = read();
        if (presets.remove(name) == null) {
            return false;
        }
        write(presets);
        return true;
    }

    private Map<String, Preset> read() {
        Map<String, Preset> presets = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return presets;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode p : root.path("presets")) {
                try {
                    presets.put(p.get("name").asText(),
                        new Preset(p.get("name").asText(), DatasetJson.spec(p.get("spec")), Instant.parse(p.get("savedAt").asText())));
                } catch (RuntimeException e) {
                    // a preset that no longer validates (e.g. edited by hand) is skipped, not fatal
                }
            }
            return presets;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private void write(Map<String, Preset> presets) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode list = root.putArray("presets");
        presets.values().forEach(p -> list.addObject().put("name", p.name()).put("savedAt", p.savedAt().toString())
            .set("spec", DatasetJson.toJson(p.spec())));
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, ".presets-", ".tmp");
            try {
                Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
