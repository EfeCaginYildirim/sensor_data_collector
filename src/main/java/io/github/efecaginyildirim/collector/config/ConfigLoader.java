package io.github.efecaginyildirim.collector.config;

import io.github.efecaginyildirim.collector.config.AppConfig.ApiConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.CollectionConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.DatabaseConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.OutputConfig;
import io.github.efecaginyildirim.collector.config.AppConfig.ScheduleConfig;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds an {@link AppConfig} from a properties file plus environment-variable overrides.
 *
 * <p>Every key can be overridden by an environment variable named {@code SDC_} followed by the
 * key in upper case with {@code .} and {@code -} replaced by {@code _}; for example
 * {@code api.password} becomes {@code SDC_API_PASSWORD}. This keeps secrets out of files in
 * container deployments.
 *
 * <p>Durations use ISO-8601 notation ({@code PT30S}, {@code PT10M}, {@code P3D}).
 *
 * <p>Validation is exhaustive: all problems are collected and thrown together in a single
 * {@link ConfigException}.
 */
public final class ConfigLoader {

    /** Prefix of environment variables that override properties. */
    public static final String ENV_PREFIX = "SDC_";

    private final Properties properties;
    private final Map<String, String> environment;
    private final List<String> problems = new ArrayList<>();

    private ConfigLoader(Properties properties, Map<String, String> environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Loads configuration from a UTF-8 properties file and the given environment.
     *
     * @param file        properties file to read
     * @param environment environment variables, usually {@link System#getenv()}
     * @return validated configuration
     * @throws ConfigException if the file cannot be read or any value is missing or invalid
     */
    public static AppConfig load(Path file, Map<String, String> environment) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigException("Cannot read configuration file " + file.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return fromProperties(properties, environment);
    }

    /**
     * Builds configuration from already-loaded properties and the given environment.
     *
     * @param properties  configuration properties
     * @param environment environment variables that override properties
     * @return validated configuration
     * @throws ConfigException if any value is missing or invalid
     */
    public static AppConfig fromProperties(Properties properties, Map<String, String> environment) {
        return new ConfigLoader(properties, environment).build();
    }

    /**
     * Returns the environment variable that overrides a property key.
     *
     * @param key property key, e.g. {@code api.password}
     * @return variable name, e.g. {@code SDC_API_PASSWORD}
     */
    public static String environmentName(String key) {
        return ENV_PREFIX + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private AppConfig build() {
        ApiConfig api = new ApiConfig(
            required("api.base-url", ConfigLoader::parseHttpUri),
            required("api.username", Function.identity()),
            required("api.password", Function.identity()),
            optional("api.connect-timeout", ConfigLoader::parsePositiveDuration, Duration.ofSeconds(10)),
            optional("api.request-timeout", ConfigLoader::parsePositiveDuration, Duration.ofSeconds(60)),
            optional("api.max-attempts", ConfigLoader::parsePositiveInt, 3),
            optional("api.max-concurrent-requests", ConfigLoader::parsePositiveInt, 8));

        CollectionConfig collection = new CollectionConfig(
            required("collection.plant-id", ConfigLoader::parsePositiveInt, 0),
            required("collection.monitor-ids", ConfigLoader::parseMonitorIds, List.of()),
            required("collection.initial-start-date", LocalDate::parse, LocalDate.EPOCH),
            optional("collection.overlap", ConfigLoader::parseNonNegativeDuration, Duration.ofDays(3)),
            optional("collection.chunk", ConfigLoader::parsePositiveDuration, Duration.ofDays(30)),
            optional("collection.run-deadline", ConfigLoader::parsePositiveDuration, Duration.ofMinutes(30)));

        Optional<DatabaseConfig> database = value("db.url").map(url -> new DatabaseConfig(
            url,
            required("db.user", Function.identity()),
            required("db.password", Function.identity())));

        OutputConfig output = new OutputConfig(
            value("output.snapshot-dir").map(Path::of),
            value("output.log-dir").map(Path::of));

        ScheduleConfig schedule = new ScheduleConfig(
            optional("schedule.daily-time", LocalTime::parse, LocalTime.of(9, 0)),
            optional("schedule.zone", ZoneId::of, ZoneId.of("UTC")));

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new AppConfig(api, collection, database, output, schedule);
    }

    // ─── Lookup helpers ──────────────────────────────────────────────────────────

    private Optional<String> value(String key) {
        String fromEnv = environment.get(environmentName(key));
        String raw = fromEnv != null ? fromEnv : properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(raw.strip());
    }

    private <T> T required(String key, Function<String, T> parser) {
        return required(key, parser, null);
    }

    /** Parses a required key; on failure records a problem and returns {@code placeholder}. */
    private <T> T required(String key, Function<String, T> parser, T placeholder) {
        Optional<String> raw = value(key);
        if (raw.isEmpty()) {
            problems.add(key + " is required (or set " + environmentName(key) + ")");
            return placeholder;
        }
        return parse(key, raw.get(), parser, placeholder);
    }

    private <T> T optional(String key, Function<String, T> parser, T defaultValue) {
        return value(key).map(raw -> parse(key, raw, parser, defaultValue)).orElse(defaultValue);
    }

    private <T> T parse(String key, String raw, Function<String, T> parser, T fallback) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            problems.add(key + "='" + raw + "' is invalid: " + describe(e));
            return fallback;
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && !(e instanceof NumberFormatException)) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
    }

    // ─── Parsers ─────────────────────────────────────────────────────────────────

    static URI parseHttpUri(String raw) {
        URI uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
        if (!uri.isAbsolute() || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("must be an absolute http(s) URL");
        }
        return uri;
    }

    static int parsePositiveInt(String raw) {
        int value = Integer.parseInt(raw);
        if (value <= 0) {
            throw new IllegalArgumentException("must be a positive integer");
        }
        return value;
    }

    static Duration parsePositiveDuration(String raw) {
        Duration value = Duration.parse(raw);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("must be a positive ISO-8601 duration such as PT30S");
        }
        return value;
    }

    static Duration parseNonNegativeDuration(String raw) {
        Duration value = Duration.parse(raw);
        if (value.isNegative()) {
            throw new IllegalArgumentException("must not be negative");
        }
        return value;
    }

    /** Parses a comma-separated list, naming every bad token instead of silently returning nothing. */
    static List<Integer> parseMonitorIds(String raw) {
        Set<Integer> ids = new LinkedHashSet<>();
        List<String> bad = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (String token : raw.split(",")) {
            String t = token.strip();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(t);
                if (id <= 0) {
                    bad.add(t);
                } else if (!ids.add(id)) {
                    duplicates.add(t);
                }
            } catch (NumberFormatException e) {
                bad.add(t);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("not positive integers: " + bad);
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("duplicate ids: " + duplicates);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("at least one monitor id is required");
        }
        return List.copyOf(ids);
    }
}
