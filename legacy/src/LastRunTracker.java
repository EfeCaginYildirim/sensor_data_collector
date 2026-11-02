import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Helper class to track the last run time.
 * Used to detect the first run and calculate smart date ranges.
 */
public class LastRunTracker {
    
    private static final String LAST_RUN_FILE = "last_run_timestamp.txt";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * Check whether this is the first time the program has run.
     * @return true if this is the first run, false otherwise
     */
    public static boolean isFirstRun() {
        File lastRunFile = new File(LAST_RUN_FILE);
        return !lastRunFile.exists();
    }
    
    /**
     * Read the last run time from disk.
     * @return The last run time, or null if the file does not exist
     */
    public static LocalDateTime getLastRunTime() {
        File lastRunFile = new File(LAST_RUN_FILE);
        
        if (!lastRunFile.exists()) {
            return null;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(lastRunFile))) {
            String timestampStr = reader.readLine();
            if (timestampStr != null && !timestampStr.trim().isEmpty()) {
                return LocalDateTime.parse(timestampStr.trim(), TIMESTAMP_FORMAT);
            }
        } catch (IOException | DateTimeParseException e) {
            System.out.println("⚠️ Could not read last run time: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Save the current time as the last run time.
     */
    public static void updateLastRunTime() {
        updateLastRunTime(LocalDateTime.now());
    }
    
    /**
     * Save the given time as the last run time.
     * @param timestamp The timestamp to save
     */
    public static void updateLastRunTime(LocalDateTime timestamp) {
        if (!ConfigManager.isLastRunTrackingEnabled()) {
            return; // Tracking is disabled
        }
        
        try (FileWriter writer = new FileWriter(LAST_RUN_FILE)) {
            writer.write(timestamp.format(TIMESTAMP_FORMAT));
            System.out.println("📝 Last run time saved: " +
                timestamp.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        } catch (IOException e) {
            System.out.println("⚠️ Could not save last run time: " + e.getMessage());
        }
    }
    
    /**
     * Calculate a smart date range.
     * - First run: from the configured start date to today
     * - Subsequent runs: safe range (last N days)
     * @return Date range as [startDate, endDate]
     */
    public static String[] calculateSmartDateRange() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDate;
        LocalDateTime endDate = now.withHour(23).withMinute(59).withSecond(59).withNano(999_999_999);
        
        if (isFirstRun()) {
            // First run — fetch from initial start date to today
            String initialDateStr = ConfigManager.getInitialDataStartDate();
            try {
                startDate = LocalDateTime.parse(initialDateStr + "T00:00:00");
                System.out.println("🎯 FIRST RUN DETECTED!");
                System.out.println("📅 Data range: " + initialDateStr + " to today");
                
                // Warn if the range is very long
                long daysBetween = java.time.Duration.between(startDate, now).toDays();
                if (daysBetween > 365) {
                    System.out.println("⚠️ WARNING: Fetching " + daysBetween + " days of data — this may take a while.");
                } else if (daysBetween > 30) {
                    System.out.println("ℹ️ INFO: Fetching " + daysBetween + " days of data.");
                }
                
            } catch (DateTimeParseException e) {
                System.out.println("❌ Invalid start date: " + initialDateStr +
                    ". Falling back to safe range.");
                int safeRangeDays = ConfigManager.getSafeDataRangeDays();
                startDate = now.minusDays(safeRangeDays).withHour(0).withMinute(0).withSecond(0).withNano(0);
            }
        } else {
            // Subsequent runs — use the safe range
            int safeRangeDays = ConfigManager.getSafeDataRangeDays();
            startDate = now.minusDays(safeRangeDays).withHour(0).withMinute(0).withSecond(0).withNano(0);
            System.out.println("🔄 NORMAL RUN");
            System.out.println("📅 Safe range: last " + safeRangeDays + " days + today");
        }
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        
        String startDateStr = startDate.format(formatter).replace(":", "%3A");
        String endDateStr = endDate.format(formatter).replace(":", "%3A");
        
        System.out.println("📅 Exact data range: " +
            startDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")) + " - " +
            endDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")));
        
        return new String[]{startDateStr, endDateStr};
    }
    
    /**
     * Suggest whether very old data should be cleaned up.
     * @return true if data older than one month exists and cleanup is appropriate
     */
    public static boolean shouldCleanOldData() {
        if (isFirstRun()) {
            return false; // First run — no cleanup needed
        }
        
        LocalDateTime lastRun = getLastRunTime();
        if (lastRun == null) {
            return false;
        }
        
        // Suggest cleanup if the last run was more than 30 days ago
        LocalDateTime oneMonthAgo = LocalDateTime.now().minusDays(30);
        return lastRun.isBefore(oneMonthAgo);
    }
    
    /**
     * Print historical run statistics.
     */
    public static void printRunStatistics() {
        System.out.println("\n=== RUN STATISTICS ===");
        
        if (isFirstRun()) {
            System.out.println("🆕 This is the first run of the program");
            System.out.println("📊 Initial start date: " + ConfigManager.getInitialDataStartDate());
            System.out.println("🎯 Strategy: Fetch all historical data");
        } else {
            LocalDateTime lastRun = getLastRunTime();
            if (lastRun != null) {
                System.out.println("⏰ Last run: " +
                    lastRun.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
                
                long daysSinceLastRun = java.time.Duration.between(lastRun, LocalDateTime.now()).toDays();
                System.out.println("📊 Days since last run: " + daysSinceLastRun);
                System.out.println("🎯 Strategy: Safe range (" + ConfigManager.getSafeDataRangeDays() + " days)");
                
                if (daysSinceLastRun > 7) {
                    System.out.println("⚠️ WARNING: " + daysSinceLastRun + " days since last run");
                    System.out.println("🔄 Using safe range to prevent data loss");
                }
            } else {
                System.out.println("❓ Last run time is unknown");
                System.out.println("🎯 Strategy: Will use safe range");
            }
        }
        
        System.out.println("======================\n");
    }
    
    /**
     * Delete the tracking file (for testing / reset purposes).
     */
    public static void resetFirstRunFlag() {
        File lastRunFile = new File(LAST_RUN_FILE);
        if (lastRunFile.exists() && lastRunFile.delete()) {
            System.out.println("🔄 First-run flag has been reset");
        }
    }
}