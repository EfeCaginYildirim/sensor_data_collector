import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart timestamp validation.
 * - Duplicate detection
 * - Date range validation
 * - Future date detection
 * - Stale data detection
 */
public class TimestampValidator {
    
    private static final ConcurrentHashMap<String, LocalDateTime> lastProcessedTimestamps = new ConcurrentHashMap<>();
    private static final DateTimeFormatter[] TIMESTAMP_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ISO_INSTANT
    };
    
    // Minimum and maximum date bounds
    private static final int MIN_DAYS_OLD = 365;       // Records older than 1 year
    private static final int MAX_FUTURE_DAYS = 1;      // Records more than 1 day in the future
    private static final int DUPLICATE_WINDOW_MINUTES = 1; // Records within 1 minute = duplicate
    
    /**
     * Parse a timestamp string into a LocalDateTime.
     */
    public static LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.trim().isEmpty()) {
            return null;
        }
        
        String cleanTimestamp = timestampStr.trim();
        
        // Check for Unix epoch (numeric string)
        try {
            long epochMillis = Long.parseLong(cleanTimestamp);
            
            // 10-digit timestamp (seconds)
            if (cleanTimestamp.length() == 10) {
                Instant instant = Instant.ofEpochSecond(epochMillis);
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            // 13-digit timestamp (milliseconds)
            else if (cleanTimestamp.length() == 13) {
                Instant instant = Instant.ofEpochMilli(epochMillis);
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
        } catch (NumberFormatException e) {
            // Not a Unix epoch — try other formats
        }
        
        // Try each known format
        for (DateTimeFormatter formatter : TIMESTAMP_FORMATTERS) {
            try {
                return LocalDateTime.parse(cleanTimestamp, formatter);
            } catch (DateTimeParseException e) {
                // This format didn't match — try the next one
            }
        }
        
        // Try ISO Instant
        try {
            Instant instant = Instant.parse(cleanTimestamp);
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // This didn't work either
        }
        
        // Only warn for non-null, non-empty, non-"null" strings
        if (timestampStr != null && !timestampStr.trim().isEmpty() && !"null".equals(timestampStr.trim())) {
            System.out.println("⚠️ Could not parse timestamp: " + timestampStr);
        }
        return null;
    }
    
    /**
     * Check whether a timestamp is valid (not future, not stale).
     */
    public static boolean isValidTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return false;
        }
        
        LocalDateTime now = LocalDateTime.now();
        
        // Future date check
        if (timestamp.isAfter(now.plusDays(MAX_FUTURE_DAYS))) {
            System.out.println("⚠️ Future date detected: " + timestamp);
            return false;
        }
        
        // Stale data check
        if (timestamp.isBefore(now.minusDays(MIN_DAYS_OLD))) {
            System.out.println("⚠️ Stale data detected: " + timestamp);
            return false;
        }
        
        return true;
    }
    
    /**
     * Check whether a timestamp is a duplicate for the given monitor.
     */
    public static boolean isDuplicateTimestamp(int monitorId, LocalDateTime timestamp) {
        if (timestamp == null) {
            return false;
        }
        
        String key = monitorId + "_" + timestamp.toLocalDate();
        LocalDateTime lastProcessed = lastProcessedTimestamps.get(key);
        
        if (lastProcessed != null) {
            // Records within DUPLICATE_WINDOW_MINUTES of each other on the same day = duplicate
            Duration difference = Duration.between(lastProcessed, timestamp);
            if (Math.abs(difference.toMinutes()) <= DUPLICATE_WINDOW_MINUTES) {
                return true;
            }
        }
        
        // Update the last-processed timestamp
        lastProcessedTimestamps.put(key, timestamp);
        return false;
    }
    
    /**
     * Smart duplicate check — uses monitor_id + timestamp combination.
     */
    public static boolean isSmartDuplicate(int monitorId, String basicFeatureTime, String computedFeatureTimestamp) {
        // If both timestamps are null, do not flag as duplicate
        if (basicFeatureTime == null && computedFeatureTimestamp == null) {
            return false;
        }
        
        LocalDateTime basicTime = parseTimestamp(basicFeatureTime);
        LocalDateTime computedTime = parseTimestamp(computedFeatureTimestamp);
        
        // Reject invalid timestamps as duplicates
        if (basicTime != null && !isValidTimestamp(basicTime)) {
            return true; // Invalid timestamp = duplicate
        }
        
        if (computedTime != null && !isValidTimestamp(computedTime)) {
            return true; // Invalid timestamp = duplicate
        }
        
        // Check for duplicate timestamps
        if (basicTime != null && isDuplicateTimestamp(monitorId, basicTime)) {
            return true;
        }
        
        if (computedTime != null && isDuplicateTimestamp(monitorId, computedTime)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check whether a timestamp falls within a valid date range.
     */
    public static boolean isInValidDateRange(LocalDateTime timestamp, LocalDateTime startDate, LocalDateTime endDate) {
        if (timestamp == null || startDate == null || endDate == null) {
            return false;
        }
        
        return !timestamp.isBefore(startDate) && !timestamp.isAfter(endDate);
    }
    
    /**
     * Print timestamp statistics.
     */
    public static void printTimestampStats() {
        System.out.println("📊 TIMESTAMP STATISTICS:");
        System.out.println("  Last-processed timestamp count: " + lastProcessedTimestamps.size());
        System.out.println("  Duplicate window: " + DUPLICATE_WINDOW_MINUTES + " minute(s)");
        System.out.println("  Minimum age: " + MIN_DAYS_OLD + " days");
        System.out.println("  Maximum future: " + MAX_FUTURE_DAYS + " day(s)");
        System.out.println("  Supported formats:");
        System.out.println("    - Unix epoch (10-digit seconds)");
        System.out.println("    - Unix epoch (13-digit milliseconds)");
        System.out.println("    - ISO 8601 variants");
        System.out.println("    - YYYY-MM-DD HH:mm:ss");
        System.out.println("    - YYYY-MM-DD'T'HH:mm:ss");
        
        // Show most-recent processed timestamps
        if (!lastProcessedTimestamps.isEmpty()) {
            System.out.println("  Most recent processed timestamps:");
            lastProcessedTimestamps.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(5)
                .forEach(entry -> {
                    System.out.println("    " + entry.getKey() + ": " +
                        entry.getValue().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
                });
        }
    }
    
    /**
     * Clear the entire timestamp cache.
     */
    public static void clearTimestampCache() {
        lastProcessedTimestamps.clear();
        System.out.println("🧹 Timestamp cache cleared");
    }
    
    /**
     * Clear the timestamp cache for a specific monitor.
     */
    public static void clearMonitorTimestampCache(int monitorId) {
        lastProcessedTimestamps.entrySet().removeIf(entry ->
            entry.getKey().startsWith(monitorId + "_"));
        System.out.println("🧹 Monitor " + monitorId + " timestamp cache cleared");
    }
    
    /**
     * Generate a validation report for a given record.
     */
    public static void generateValidationReport(String basicFeatureTime, String computedFeatureTimestamp, int monitorId) {
        System.out.println("🔍 TIMESTAMP VALIDATION REPORT:");
        System.out.println("  Monitor ID: " + monitorId);
        
        LocalDateTime basicTime = parseTimestamp(basicFeatureTime);
        LocalDateTime computedTime = parseTimestamp(computedFeatureTimestamp);
        
        System.out.println("  Basic Feature Time: " + (basicFeatureTime != null ? basicFeatureTime : "null"));
        System.out.println("    → Parsed: " + (basicTime != null ? basicTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")) : "null"));
        System.out.println("    → Valid: " + (basicTime != null ? isValidTimestamp(basicTime) : "N/A"));
        
        System.out.println("  Computed Feature Timestamp: " + (computedFeatureTimestamp != null ? computedFeatureTimestamp : "null"));
        System.out.println("    → Parsed: " + (computedTime != null ? computedTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")) : "null"));
        System.out.println("    → Valid: " + (computedTime != null ? isValidTimestamp(computedTime) : "N/A"));
        
        boolean isDuplicate = isSmartDuplicate(monitorId, basicFeatureTime, computedFeatureTimestamp);
        System.out.println("  Duplicate: " + isDuplicate);
        
        if (isDuplicate) {
            System.out.println("  ⚠️ This record has been flagged as a duplicate!");
        }
    }
}
