package io.github.efecaginyildirim.collector.ui;

import io.github.efecaginyildirim.collector.config.AppConfig;
import io.github.efecaginyildirim.collector.ui.SingleInstance.Acquired;
import io.github.efecaginyildirim.collector.ui.SingleInstance.AlreadyRunning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsAndPathsTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    // ─── AppPaths ────────────────────────────────────────────────────────────────

    @Test
    void dataDirectoryFollowsPlatformConventions() {
        assertEquals(Path.of("C:\\Users\\efe\\AppData\\Roaming", "SensorDataCollector"),
            AppPaths.dataDirectory("Windows 11", Map.of("APPDATA", "C:\\Users\\efe\\AppData\\Roaming"), "C:\\Users\\efe"));
        assertEquals(Path.of("/home/efe", "AppData", "Roaming", "SensorDataCollector"),
            AppPaths.dataDirectory("Windows 10", Map.of(), "/home/efe"), "fallback without APPDATA");
        assertEquals(Path.of("/Users/efe/Library/Application Support/SensorDataCollector"),
            AppPaths.dataDirectory("Mac OS X", Map.of(), "/Users/efe"));
        assertEquals(Path.of("/home/efe/.local/share/sensor-data-collector"),
            AppPaths.dataDirectory("Linux", Map.of(), "/home/efe"));
        assertEquals(Path.of("/data/sensor-data-collector"),
            AppPaths.dataDirectory("Linux", Map.of("XDG_DATA_HOME", "/data"), "/home/efe"));
    }

    @Test
    void browserIsOpenedWithThePlatformOpenerAndNeverThroughAShell() {
        String url = "http://127.0.0.1:5000/?token=a-b_c";
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", url), Browser.command("Windows 11", url));
        assertEquals(List.of("open", url), Browser.command("Mac OS X", url));
        assertEquals(List.of("xdg-open", url), Browser.command("Linux", url));
        assertThrows(IllegalArgumentException.class, () -> Browser.open(URI.create("file:///etc/passwd")));
    }

    // ─── SettingsStore ───────────────────────────────────────────────────────────

    @Test
    void defaultsStartInDemoModeWithTheSystemZone(@TempDir Path dir) {
        Map<String, String> s = new SettingsStore(dir.resolve("settings.properties"), PARIS).load();
        assertEquals(SettingsStore.KEYS, List.copyOf(s.keySet()));
        assertEquals(SettingsStore.Mode.DEMO, SettingsStore.mode(s));
        assertEquals("Europe/Paris", s.get("schedule.zone"));
        assertEquals("false", s.get("schedule.enabled"));
        assertTrue(SettingsStore.validate(s).isEmpty(), "defaults are valid");
    }

    @Test
    void validChangesAreSavedAndSurviveARestart(@TempDir Path dir) {
        Path file = dir.resolve("nested/settings.properties");
        SettingsStore store = new SettingsStore(file, PARIS);

        assertEquals(List.of(), store.update(apiSettings("s3cret-žłΩ")));

        Map<String, String> reloaded = new SettingsStore(file, PARIS).load();
        assertEquals("api", reloaded.get("ui.mode"));
        assertEquals("s3cret-žłΩ", reloaded.get("api.password"), "password is saved, UTF-8 intact");
        AppConfig config = SettingsStore.apiConfig(reloaded);
        assertEquals(List.of(1001, 1002), config.collection().monitorIds());
        assertEquals(ZoneId.of("Europe/Istanbul"), config.schedule().zone());
    }

    @Test
    void anEmptyPasswordKeepsTheSavedOne(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(dir.resolve("s.properties"), PARIS);
        store.update(apiSettings("first"));

        assertEquals(List.of(), store.update(Map.of("api.password", "", "api.username", "other")));

        assertEquals("first", store.load().get("api.password"));
        assertEquals("other", store.load().get("api.username"));
    }

    @Test
    void invalidChangesAreRejectedAndNothingIsSaved(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(dir.resolve("s.properties"), PARIS);
        Map<String, String> bad = new LinkedHashMap<>(apiSettings("pw"));
        bad.put("api.base-url", "not a url");
        bad.put("collection.monitor-ids", "1,x");
        bad.put("schedule.zone", "Mars/Olympus");
        bad.put("demo.monitors", "0");

        List<String> problems = store.update(bad);

        assertTrue(problems.stream().anyMatch(p -> p.startsWith("api.base-url")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("collection.monitor-ids")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("schedule.zone")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("demo.monitors")), problems::toString);
        assertFalse(Files.exists(dir.resolve("s.properties")));
    }

    @Test
    void unknownAndInternalKeysCannotBeSet(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(dir.resolve("s.properties"), PARIS);
        assertEquals(List.of("db.url is not a setting"), store.update(Map.of("db.url", "jdbc:h2:mem:x")));
        assertEquals(List.of("demo.reference-time is not a setting"), store.update(Map.of("demo.reference-time", "x")));
    }

    @Test
    void demoModeDoesNotRequireApiSettings(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(dir.resolve("s.properties"), PARIS);
        assertEquals(List.of(), store.update(Map.of("ui.mode", "demo", "demo.monitors", "3")));
        assertEquals(List.of("ui.mode must be 'demo' or 'api'"), store.update(Map.of("ui.mode", "cloud")));
    }

    @Test
    void settingsFileIsPrivateToTheUser(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.properties");
        new SettingsStore(file, PARIS).update(apiSettings("pw"));
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()));
        }
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary files left behind");
        }
    }

    // ─── SingleInstance ──────────────────────────────────────────────────────────

    @Test
    void aSecondLaunchFindsTheFirstInstance(@TempDir Path dir) throws Exception {
        SingleInstance.Acquisition first = SingleInstance.acquire(dir);
        Acquired acquired = assertInstanceOf(Acquired.class, first);

        AlreadyRunning beforePublish = assertInstanceOf(AlreadyRunning.class, SingleInstance.acquire(dir));
        assertTrue(beforePublish.launchUrl().isEmpty());

        URI url = URI.create("http://127.0.0.1:51234/?token=abc");
        acquired.instance().publish(url);
        AlreadyRunning running = assertInstanceOf(AlreadyRunning.class, SingleInstance.acquire(dir));
        assertEquals(url, running.launchUrl().orElseThrow());
        assertEquals(url.toString(), Files.readString(dir.resolve("instance.url"), StandardCharsets.UTF_8));

        acquired.instance().close();
        assertFalse(Files.exists(dir.resolve("instance.url")));
        Acquired again = assertInstanceOf(Acquired.class, SingleInstance.acquire(dir), "the lock is released on close");
        again.instance().close();
    }

    static Map<String, String> apiSettings(String password) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ui.mode", "api");
        m.put("api.base-url", "https://api.example.com/v1");
        m.put("api.username", "efe");
        m.put("api.password", password);
        m.put("collection.plant-id", "1");
        m.put("collection.monitor-ids", "1001, 1002");
        m.put("collection.initial-start-date", "2025-01-01");
        m.put("schedule.zone", "Europe/Istanbul");
        return m;
    }
}
