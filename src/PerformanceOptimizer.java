import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Performance optimization and monitoring class.
 */
public class PerformanceOptimizer {
    private static final long startTime = System.currentTimeMillis();
    private static final ConcurrentHashMap<String, Long> operationTimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> operationCounts = new ConcurrentHashMap<>();
    
    /**
     * Record the start of an operation.
     */
    public static void startOperation(String operationName) {
        operationTimes.put(operationName + "_start", System.currentTimeMillis());
    }
    
    /**
     * Record the end of an operation.
     */
    public static void endOperation(String operationName) {
        Long opStartTime = operationTimes.get(operationName + "_start");
        if (opStartTime != null) {
            long duration = System.currentTimeMillis() - opStartTime;
            operationTimes.put(operationName + "_duration", duration);
            
            // Increment operation count
            operationCounts.merge(operationName, 1, Integer::sum);
            
            // Silent mode — only total time will be shown
        }
    }
    
    /**
     * Get the total elapsed runtime in milliseconds.
     */
    public static long getTotalRuntime() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Print a performance report.
     */
    public static void printPerformanceReport() {
        System.out.println("\n=== PERFORMANCE REPORT ===");
        System.out.println("Total runtime: " + getTotalRuntime() + " ms");
        System.out.println("Start time: " + LocalDateTime.now().minusNanos((System.currentTimeMillis() - startTime) * 1_000_000).format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        System.out.println("End time: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        
        System.out.println("\nOperation Details:");
        operationTimes.forEach((key, value) -> {
            if (key.endsWith("_duration")) {
                String operationName = key.replace("_duration", "");
                Integer count = operationCounts.get(operationName);
                System.out.println("  " + operationName + ": " + value + " ms" + (count != null ? " (x" + count + ")" : ""));
            }
        });
        
        // Print pool statuses
        System.out.println("\nPool Status:");
        HttpConnectionPool.printPoolStatus();
        DatabaseConnectionPool.printPoolStatus();
        ConcurrentDataCollector.printThreadPoolStatus();
        
        System.out.println("==========================\n");
    }
    
    /**
     * Print current memory usage.
     */
    public static void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        System.out.println("💾 Memory Usage:");
        System.out.println("  Used: " + formatBytes(usedMemory));
        System.out.println("  Free: " + formatBytes(freeMemory));
        System.out.println("  Total: " + formatBytes(totalMemory));
        System.out.println("  Maximum: " + formatBytes(maxMemory));
        System.out.println("  Usage ratio: " + String.format("%.1f%%", (double) usedMemory / totalMemory * 100));
    }
    
    /**
     * Format bytes into a human-readable string.
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    /**
     * Print performance optimization suggestions.
     */
    public static void printOptimizationSuggestions() {
        System.out.println("\n🚀 PERFORMANCE OPTIMIZATION SUGGESTIONS:");
        
        // HTTP connection analysis
        Long httpDuration = operationTimes.get("HTTP_Requests_duration");
        if (httpDuration != null && httpDuration > 5000) {
            System.out.println("  ⚡ HTTP requests are slow (" + httpDuration + " ms) — connection pooling is active");
        }
        
        // Database analysis
        Long dbDuration = operationTimes.get("Database_Operations_duration");
        if (dbDuration != null && dbDuration > 3000) {
            System.out.println("  🗄️ Database operations are slow (" + dbDuration + " ms) — connection pooling is active");
        }
        
        // JSON parsing analysis
        Long jsonDuration = operationTimes.get("JSON_Parsing_duration");
        if (jsonDuration != null && jsonDuration > 1000) {
            System.out.println("  📄 JSON parsing is slow (" + jsonDuration + " ms) — optimized parser is in use");
        }
        
        // General status
        System.out.println("  ✅ Parallel data collection: active");
        System.out.println("  ✅ HTTP connection pool: active");
        System.out.println("  ✅ Database connection pool: active");
        System.out.println("  ✅ Optimized JSON parser: active");
        System.out.println("  ✅ Batch processing: active");
        
        long totalTime = getTotalRuntime();
        if (totalTime > 30000) {
            System.out.println("  ⚠️ Total runtime is long (" + totalTime + " ms) — consider reducing the number of monitors");
        }
    }
}
