package io.github.efecaginyildirim.collector.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configures {@code java.util.logging}, the backend of {@link System.Logger}, without extra
 * dependencies: one-line console output and, optionally, size-rotated UTF-8 log files.
 *
 * <p>Unlike the legacy approach of replacing {@code System.out}, this captures stack traces and
 * writes through buffered handlers instead of one system call per byte.
 */
final class Logging {

    static final String FORMAT = "%1$tF %1$tT.%1$tL %4$-7s %3$s - %5$s%6$s%n";

    private Logging() { }

    /**
     * @param consoleLevel minimum level printed to the console
     * @param logDirectory directory for rotating files ({@code collector-0.log} … {@code collector-4.log}, 10 MB each)
     */
    static void configure(Level consoleLevel, Optional<Path> logDirectory) {
        System.setProperty("java.util.logging.SimpleFormatter.format", FORMAT);
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.setLevel(Level.INFO);

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(consoleLevel);
        console.setFormatter(new SimpleFormatter());
        root.addHandler(console);

        logDirectory.ifPresent(dir -> {
            try {
                Files.createDirectories(dir);
                FileHandler file = new FileHandler(dir.resolve("collector-%g.log").toString(), 10_000_000, 5, true);
                file.setEncoding("UTF-8");
                file.setFormatter(new SimpleFormatter());
                file.setLevel(Level.INFO);
                root.addHandler(file);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open log directory " + dir, e);
            }
        });
        Logger.getLogger("jdk.internal.httpclient").setLevel(Level.WARNING);
    }
}
