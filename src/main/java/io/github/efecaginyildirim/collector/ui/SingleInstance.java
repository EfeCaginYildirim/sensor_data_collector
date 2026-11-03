package io.github.efecaginyildirim.collector.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Ensures only one app instance uses a data directory, and lets a second launch find the first.
 *
 * <p>The guard is an operating-system file lock on {@code instance.lock}: it is released
 * automatically when the process exits, even after a crash, so a stale lock can never block the
 * next start. The running instance writes its launch URL (including the session token) to
 * {@code instance.url}, readable only by the user; a second launch opens that URL instead of
 * starting another server on the same database.
 */
public final class SingleInstance implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;
    private final Path urlFile;

    private SingleInstance(FileChannel channel, FileLock lock, Path urlFile) {
        this.channel = channel;
        this.lock = lock;
        this.urlFile = urlFile;
    }

    /** Result of {@link #acquire(Path)}. */
    public sealed interface Acquisition permits Acquired, AlreadyRunning { }

    /**
     * This process now owns the data directory.
     *
     * @param instance the held lock; close it on exit
     */
    public record Acquired(SingleInstance instance) implements Acquisition { }

    /**
     * Another process owns the data directory.
     *
     * @param launchUrl the URL it published, if it has started its server
     */
    public record AlreadyRunning(Optional<URI> launchUrl) implements Acquisition { }

    /**
     * Tries to become the instance for {@code dataDirectory}.
     *
     * @param dataDirectory the app's data directory; created if missing
     * @return {@link Acquired} or {@link AlreadyRunning}
     * @throws IOException if the lock file cannot be opened
     */
    public static Acquisition acquire(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path urlFile = dataDirectory.resolve("instance.url");
        FileChannel channel = FileChannel.open(dataDirectory.resolve("instance.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // held by this JVM already
        }
        if (lock == null) {
            channel.close();
            return new AlreadyRunning(readUrl(urlFile));
        }
        Files.deleteIfExists(urlFile);
        return new Acquired(new SingleInstance(channel, lock, urlFile));
    }

    /**
     * Publishes the launch URL for later launches.
     *
     * @param launchUrl URL including the session token
     * @throws IOException if the file cannot be written
     */
    public void publish(URI launchUrl) throws IOException {
        Path temp = Files.createTempFile(urlFile.getParent(), ".instance-", ".tmp");
        SettingsStore.restrictToOwner(temp);
        Files.writeString(temp, launchUrl.toString(), StandardCharsets.UTF_8);
        Files.move(temp, urlFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Optional<URI> readUrl(Path urlFile) {
        try {
            return Files.exists(urlFile) ? Optional.of(URI.create(Files.readString(urlFile, StandardCharsets.UTF_8).strip()))
                : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Removes the published URL and releases the lock. */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(urlFile);
        } catch (IOException ignored) {
            // best effort
        }
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // released by the OS on exit anyway
        }
    }
}
