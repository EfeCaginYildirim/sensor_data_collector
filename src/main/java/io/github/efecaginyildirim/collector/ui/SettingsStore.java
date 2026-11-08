package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.config.AppConfig;
import io.github.efecaginyildirim.collector.config.ConfigException;
import io.github.efecaginyildirim.collector.config.ConfigLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The desktop app's settings, stored as a UTF-8 properties file in the user's data directory.
 *
 * <p>Keys deliberately match the CLI configuration ({@code api.base-url}, {@code collection.monitor-ids}
 * …), so validation is shared with {@link ConfigLoader} and the same names appear in error
 * messages, the JSON API and the HTML form. A few keys exist only here: {@code ui.mode},
 * {@code ui.language}, {@code demo.*} and {@code schedule.enabled}.
 *
 * <p>The API password is saved, as decided for this app, in plain text — but the file is written
 * with owner-only permissions on POSIX systems, and {@code %APPDATA%} is private to the user on
 * Windows. The password is never sent back to the browser.
 */
public final class SettingsStore {

    /** Operating modes. */
    public enum Mode {
        /** Collect from the built-in fake API with synthetic motors. */
        DEMO,
        /** Collect from the configured vendor API. */
        API
    }

    /** Every key the store accepts, in display order. */
    public static final List<String> KEYS = List.of(
        "ui.mode", "ui.language",
        "demo.monitors", "demo.history-days", "demo.seed", "demo.reference-time",
        "api.base-url", "api.username", "api.password", "api.max-concurrent-requests",
        "collection.plant-id", "collection.monitor-ids", "collection.initial-start-date",
        "collection.overlap", "collection.chunk",
        "schedule.enabled", "schedule.daily-time", "schedule.zone");

    /** Languages the browser UI is translated into; the first is the default. */
    public static final List<String> LANGUAGES = List.of("en", "fr");

    /** Keys whose values are never returned to clients. */
    public static final Set<String> SECRET_KEYS = Set.of("api.password");

    /** Keys managed by the app itself, not editable through {@link #update(Map)}. */
    static final Set<String> INTERNAL_KEYS = Set.of("demo.reference-time");

    private final Path file;
    private final ZoneId defaultZone;

    /**
     * @param file        settings file; created on first save
     * @param defaultZone time zone proposed for the schedule until the user picks one
     */
    public SettingsStore(Path file, ZoneId defaultZone) {
        this.file = file;
        this.defaultZone = defaultZone;
    }

    /** @return the settings file */
    public Path file() {
        return file;
    }

    /**
     * Reads the settings, filling in defaults for missing keys.
     *
     * @return all {@link #KEYS} in display order
     */
    public synchronized Map<String, String> load() {
        Map<String, String> values = defaults();
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
            for (String key : KEYS) {
                String v = p.getProperty(key);
                if (v != null) {
                    values.put(key, v.strip());
                }
            }
        }
        return values;
    }

    /**
     * Validates and saves user changes. An empty {@code api.password} keeps the saved one, so
     * forms can leave the password field blank.
     *
     * @param changes keys and new values; unknown and internal keys are rejected
     * @return problems found; empty if the settings were saved
     */
    public synchronized List<String> update(Map<String, String> changes) {
        List<String> problems = new ArrayList<>();
        Map<String, String> merged = load();
        changes.forEach((key, value) -> {
            if (!KEYS.contains(key) || INTERNAL_KEYS.contains(key)) {
                problems.add(key + " is not a setting");
            } else if (SECRET_KEYS.contains(key) && (value == null || value.isEmpty())) {
                // keep the stored secret
            } else {
                merged.put(key, value == null ? "" : value.strip());
            }
        });
        if (problems.isEmpty()) {
            problems.addAll(validate(merged));
        }
        if (problems.isEmpty()) {
            save(merged);
        }
        return problems;
    }

    /**
     * Saves the UI language on its own, so it can be switched even while other settings are
     * incomplete (for example API mode chosen but not yet configured).
     *
     * @param language one of {@link #LANGUAGES}
     * @return problems found; empty if the language was saved
     */
    public synchronized List<String> setLanguage(String language) {
        List<String> problems = checkLanguage(language);
        if (problems.isEmpty()) {
            put("ui.language", language);
        }
        return problems;
    }

    /**
     * Sets an internal value such as the demo reference time, without validation.
     *
     * @param key   one of {@link #KEYS}
     * @param value the value
     */
    synchronized void put(String key, String value) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown key " + key);
        }
        Map<String, String> merged = load();
        merged.put(key, value);
        save(merged);
    }

    /**
     * @param settings loaded settings
     * @return the mode they select
     */
    public static Mode mode(Map<String, String> settings) {
        return "api".equalsIgnoreCase(settings.get("ui.mode")) ? Mode.API : Mode.DEMO;
    }

    /**
     * Builds the collector configuration for API mode.
     *
     * @param settings loaded settings
     * @return the validated configuration
     * @throws ConfigException if the API settings are incomplete or invalid
     */
    public static AppConfig apiConfig(Map<String, String> settings) {
        Properties p = new Properties();
        settings.forEach((k, v) -> {
            if (!v.isEmpty() && (k.startsWith("api.") || k.startsWith("collection.") || k.startsWith("schedule."))) {
                p.setProperty(k, v);
            }
        });
        return ConfigLoader.fromProperties(p, Map.of());
    }

    /**
     * Checks a complete set of settings.
     *
     * @param s all settings
     * @return problems, each starting with the offending key
     */
    static List<String> validate(Map<String, String> s) {
        List<String> problems = new ArrayList<>();
        String mode = s.getOrDefault("ui.mode", "");
        if (!mode.equals("demo") && !mode.equals("api")) {
            problems.add("ui.mode must be 'demo' or 'api'");
        }
        problems.addAll(checkLanguage(s.getOrDefault("ui.language", "")));
        checkInt(s, "demo.monitors", 1, 100, problems);
        checkInt(s, "demo.history-days", 1, 3650, problems);
        try {
            Long.parseLong(s.getOrDefault("demo.seed", ""));
        } catch (NumberFormatException e) {
            problems.add("demo.seed must be an integer");
        }
        String enabled = s.getOrDefault("schedule.enabled", "");
        if (!enabled.equals("true") && !enabled.equals("false")) {
            problems.add("schedule.enabled must be true or false");
        }
        try {
            LocalTime.parse(s.getOrDefault("schedule.daily-time", ""));
        } catch (DateTimeException e) {
            problems.add("schedule.daily-time must look like 09:00");
        }
        try {
            ZoneId.of(s.getOrDefault("schedule.zone", ""));
        } catch (DateTimeException e) {
            problems.add("schedule.zone is not a known time zone");
        }
        if (mode.equals("api")) {
            try {
                apiConfig(s);
            } catch (ConfigException e) {
                problems.addAll(e.problems());
            }
        }
        return problems;
    }

    private static List<String> checkLanguage(String language) {
        return LANGUAGES.contains(language) ? List.of() : List.of("ui.language must be 'en' or 'fr'");
    }

    private static void checkInt(Map<String, String> s, String key, int min, int max, List<String> problems) {
        try {
            int v = Integer.parseInt(s.getOrDefault(key, ""));
            if (v < min || v > max) {
                problems.add(key + " must be between " + min + " and " + max);
            }
        } catch (NumberFormatException e) {
            problems.add(key + " must be an integer");
        }
    }

    private Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        for (String key : KEYS) {
            d.put(key, "");
        }
        d.put("ui.mode", "demo");
        d.put("ui.language", LANGUAGES.get(0));
        d.put("demo.monitors", "6");
        d.put("demo.history-days", "90");
        d.put("demo.seed", "42");
        d.put("api.max-concurrent-requests", "8");
        d.put("collection.overlap", "P3D");
        d.put("collection.chunk", "P30D");
        d.put("schedule.enabled", "false");
        d.put("schedule.daily-time", "09:00");
        d.put("schedule.zone", defaultZone.getId());
        return d;
    }

    private void save(Map<String, String> values) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, ".settings-", ".tmp");
            try {
                restrictToOwner(temp);
                Properties p = new Properties();
                values.forEach(p::setProperty);
                try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    p.store(w, "Sensor Data Collector settings - contains the API password, keep private");
                }
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

    /** Owner read/write only, where the file system supports POSIX permissions. */
    static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        }
    }
}
