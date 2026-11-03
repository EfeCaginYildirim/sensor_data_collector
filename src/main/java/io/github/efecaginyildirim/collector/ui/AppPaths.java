package io.github.efecaginyildirim.collector.ui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where the desktop app keeps its settings and database, following each platform's convention:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\SensorDataCollector}</li>
 *   <li>macOS: {@code ~/Library/Application Support/SensorDataCollector}</li>
 *   <li>Linux and others: {@code $XDG_DATA_HOME/sensor-data-collector}, default {@code ~/.local/share}</li>
 * </ul>
 */
public final class AppPaths {

    private AppPaths() { }

    /** @return the data directory for the current user and platform */
    public static Path defaultDataDirectory() {
        return dataDirectory(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home"));
    }

    /**
     * Resolves the data directory; separated from {@link #defaultDataDirectory()} for testing.
     *
     * @param osName   value of {@code os.name}
     * @param env      environment variables
     * @param userHome value of {@code user.home}
     * @return the data directory (not created)
     */
    static Path dataDirectory(String osName, Map<String, String> env, String userHome) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String appData = env.get("APPDATA");
            Path base = appData != null && !appData.isBlank() ? Path.of(appData) : Path.of(userHome, "AppData", "Roaming");
            return base.resolve("SensorDataCollector");
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return Path.of(userHome, "Library", "Application Support", "SensorDataCollector");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(userHome, ".local", "share");
        return base.resolve("sensor-data-collector");
    }
}
