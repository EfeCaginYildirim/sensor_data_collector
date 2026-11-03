package io.github.efecaginyildirim.collector.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * Opens embedded H2 databases — the storage of the desktop and demo modes.
 *
 * <p>H2 is a pure-Java database in a single 2.5 MB jar; a file database is one {@code .mv.db}
 * file. URLs are completed with the settings the shared SQL relies on:
 * <ul>
 *   <li>{@code MODE=MSSQLServer} — {@code [bracket]} identifiers and SQL Server-style syntax;</li>
 *   <li>{@code DATABASE_TO_LOWER=TRUE} — unquoted names fold to lower case like the schema;</li>
 *   <li>{@code DB_CLOSE_DELAY=-1} — keep the database open between the repository's short-lived
 *       connections instead of re-opening the file every time; {@link JdbcMotorDataRepository#close()}
 *       shuts it down.</li>
 * </ul>
 */
public final class H2Database {

    static final String SETTINGS = ";MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private H2Database() { }

    /**
     * A private in-memory database, gone after {@link JdbcMotorDataRepository#close()}.
     *
     * @return an initialized repository
     */
    public static JdbcMotorDataRepository inMemory() {
        JdbcMotorDataRepository repo = open("jdbc:h2:mem:sdc-" + UUID.randomUUID(), "sa", "");
        repo.initialize();
        return repo;
    }

    /**
     * A database stored in {@code file + ".mv.db"}; parent directories are created.
     *
     * @param file database path without extension, e.g. {@code ~/.sdc/collector}
     * @return an initialized repository
     */
    public static JdbcMotorDataRepository file(Path file) {
        Path absolute = file.toAbsolutePath();
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + absolute.getParent(), e);
        }
        JdbcMotorDataRepository repo = open("jdbc:h2:file:" + absolute, "sa", "");
        repo.initialize();
        return repo;
    }

    /**
     * Opens an H2 URL, adding missing compatibility settings.
     *
     * @param jdbcUrl  {@code jdbc:h2:...} URL
     * @param user     user name
     * @param password password
     * @return a repository; call {@link JdbcMotorDataRepository#initialize()} before use
     */
    static JdbcMotorDataRepository open(String jdbcUrl, String user, String password) {
        String url = withSettings(jdbcUrl);
        return new JdbcMotorDataRepository(() -> DriverManager.getConnection(url, user, password), SqlDialect.H2, 1_000,
            () -> shutdown(url, user, password));
    }

    /**
     * Appends every compatibility setting that the URL does not already contain.
     *
     * @param jdbcUrl H2 URL
     * @return the completed URL
     */
    static String withSettings(String jdbcUrl) {
        StringBuilder url = new StringBuilder(jdbcUrl);
        String upper = jdbcUrl.toUpperCase(Locale.ROOT);
        for (String setting : SETTINGS.substring(1).split(";")) {
            String key = setting.substring(0, setting.indexOf('=') + 1);
            if (!upper.contains(";" + key)) {
                url.append(';').append(setting);
            }
        }
        return url.toString();
    }

    private static void shutdown(String url, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (SQLException e) {
            // already closed or never opened: nothing to release
        }
    }
}
