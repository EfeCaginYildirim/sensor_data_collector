import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Mirrors console output to a run-specific log file when enabled in config. */
public final class ApplicationLogger {
    private static PrintStream originalOut;
    private static PrintStream fileOut;

    private ApplicationLogger() { }

    public static synchronized void start() {
        if (!ConfigManager.isLogToFileEnabled() || fileOut != null) return;
        try {
            File directory = new File(ConfigManager.getLogFileDirectory());
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create log directory: " + directory);
            }
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File logFile = new File(directory, "collector_" + timestamp + ".log");
            originalOut = System.out;
            fileOut = new PrintStream(new FileOutputStream(logFile, true), true, "UTF-8");
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileOut), true, "UTF-8"));
            System.out.println("📝 Logging to file: " + logFile.getPath());
        } catch (IOException e) {
            System.err.println("⚠️ File logging could not start: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (fileOut == null) return;
        System.setOut(originalOut);
        fileOut.close();
        fileOut = null;
        originalOut = null;
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;
        TeeOutputStream(OutputStream first, OutputStream second) { this.first = first; this.second = second; }
        @Override public void write(int value) throws IOException { first.write(value); second.write(value); }
        @Override public void flush() throws IOException { first.flush(); second.flush(); }
    }
}
