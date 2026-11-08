package io.github.efecaginyildirim.collector.app;

import io.github.efecaginyildirim.collector.collect.RunReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Console and log output must look the same on every machine: a French or Turkish system
 * locale must not turn "0.07 s" into "0,07 s".
 */
class ConsoleLocaleTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr-FR", "tr-TR", "en-US"})
    void benchmarkUsesADecimalPointWhateverTheSystemLocale(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        MainTest.Result r = MainTest.exec("benchmark", "--monitors", "2", "--days", "40", "--latency-ms", "5", "--concurrency", "1,4");

        assertEquals(0, r.exitCode(), r.output());
        List<String> rows = r.output().lines().filter(l -> l.matches("^[14]\\s+\\d+\\.\\d+ s\\s+\\d+\\s+\\d+\\.\\dx.*")).toList();
        assertEquals(2, rows.size(), r.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr-FR", "tr-TR"})
    void runSummaryUsesADecimalPointWhateverTheSystemLocale(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        RunReport report = new RunReport("r1", Instant.parse("2025-06-01T10:00:00Z"), Duration.ofMillis(1500),
            RunReport.Status.SUCCESS, Optional.empty(), List.of(), List.of());

        assertTrue(report.summary().startsWith("Run r1 SUCCESS in 1.5 s"), report.summary());
    }
}
