package io.github.efecaginyildirim.collector.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, validated application configuration.
 *
 * <p>Instances are created by {@link ConfigLoader}; every value has already been parsed and
 * checked, so consumers never need to handle malformed input. {@code toString()} of the nested
 * records masks secrets so a configuration can be logged safely.
 *
 * @param api        connection settings for the vendor monitoring API
 * @param collection what to collect and how far back
 * @param database   SQL Server settings; empty when running without a database (e.g. demo mode)
 * @param output     optional output locations
 * @param schedule   when the daemon mode runs
 */
public record AppConfig(
        ApiConfig api,
        CollectionConfig collection,
        Optional<DatabaseConfig> database,
        OutputConfig output,
        ScheduleConfig schedule) {

    public AppConfig {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(schedule, "schedule");
    }

    /**
     * Vendor API settings.
     *
     * @param baseUri               absolute base URI, e.g. {@code https://api.example.com/v1}
     * @param username              API account name
     * @param password              API account password (masked in {@link #toString()})
     * @param connectTimeout        TCP connect timeout
     * @param requestTimeout        timeout for a single HTTP request
     * @param maxAttempts           attempts per request including the first one (at least 1)
     * @param maxConcurrentRequests upper bound on in-flight requests, protecting the vendor's rate limit
     */
    public record ApiConfig(
            URI baseUri,
            String username,
            String password,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxAttempts,
            int maxConcurrentRequests) {

        @Override
        public String toString() {
            return "ApiConfig[baseUri=" + baseUri + ", username=" + username + ", password=***"
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout
                + ", maxAttempts=" + maxAttempts + ", maxConcurrentRequests=" + maxConcurrentRequests + "]";
        }
    }

    /**
     * Collection settings.
     *
     * @param plantId          plant whose hierarchy is collected
     * @param monitorIds       monitors to collect, in configuration order, without duplicates
     * @param initialStartDate first day (UTC) collected for a monitor that has no watermark yet
     * @param overlap          how far before the watermark each run re-fetches, to pick up late data
     * @param chunk            maximum length of a single API request window
     * @param runDeadline      wall-clock budget for one run; unfinished monitors are reported as failed
     * @param maxFutureSkew    samples later than {@code now + maxFutureSkew} are rejected
     */
    public record CollectionConfig(
            int plantId,
            List<Integer> monitorIds,
            LocalDate initialStartDate,
            Duration overlap,
            Duration chunk,
            Duration runDeadline,
            Duration maxFutureSkew) {

        public CollectionConfig {
            monitorIds = List.copyOf(monitorIds);
        }
    }

    /**
     * SQL Server settings.
     *
     * @param jdbcUrl  JDBC URL, e.g. {@code jdbc:sqlserver://localhost:1433;databaseName=Sensors;encrypt=true}
     * @param user     database user
     * @param password database password (masked in {@link #toString()})
     */
    public record DatabaseConfig(String jdbcUrl, String user, String password) {
        @Override
        public String toString() {
            return "DatabaseConfig[jdbcUrl=" + jdbcUrl + ", user=" + user + ", password=***]";
        }
    }

    /**
     * Output locations.
     *
     * @param snapshotDirectory where a JSON archive of each run is written; empty disables it
     * @param logDirectory      where rotating log files are written; empty logs to the console only
     */
    public record OutputConfig(Optional<Path> snapshotDirectory, Optional<Path> logDirectory) { }

    /**
     * Daemon schedule.
     *
     * @param dailyRunTime local wall-clock time of the daily run
     * @param zone         time zone used to interpret {@code dailyRunTime}, including DST rules
     */
    public record ScheduleConfig(LocalTime dailyRunTime, ZoneId zone) { }
}
