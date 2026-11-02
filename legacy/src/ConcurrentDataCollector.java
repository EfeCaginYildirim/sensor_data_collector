import java.util.concurrent.*;
import java.util.*;

/**
 * Parallel data collection class — optimized for performance.
 */
public class ConcurrentDataCollector {
    private static final int THREAD_POOL_SIZE = 5;
    private static ExecutorService executorService;
    
    // Initialize executor service via lazy initialization
    private static synchronized ExecutorService getExecutorService() {
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        }
        return executorService;
    }
    
    /**
     * Collect data for all monitors in parallel.
     */
    public static Map<Integer, ScheduledDataCollector.MonitorData> collectDataConcurrently(
            String accessToken, int[] monitorIds, String startDate, String endDate) {
        
        Map<Integer, ScheduledDataCollector.MonitorData> monitorData = new ConcurrentHashMap<>();
        List<Future<Void>> futures = new ArrayList<>();
        
        System.out.println("🚀 Starting parallel data collection...");
        System.out.println("📊 Using " + THREAD_POOL_SIZE + " threads for " + monitorIds.length + " monitors");
        
        // Create a separate task for each monitor
        for (int monitorId : monitorIds) {
            Future<Void> future = getExecutorService().submit(() -> {
                try {
                    collectMonitorData(monitorId, accessToken, startDate, endDate, monitorData);
                } catch (Exception e) {
                    System.out.println("❌ Monitor " + monitorId + " data collection error: " + e.getMessage());
                }
                return null;
            });
            futures.add(future);
        }
        
        // Wait for all tasks to complete
        for (Future<Void> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS); // 60-second timeout
            } catch (TimeoutException e) {
                System.out.println("⚠️ Data collection timed out — some monitors could not complete");
            } catch (Exception e) {
                System.out.println("❌ Parallel task error: " + e.getMessage());
            }
        }
        
        System.out.println("✅ Parallel data collection complete! Collected data for " + monitorData.size() + " monitors");
        return monitorData;
    }
    
    /**
     * Collect data for a single monitor.
     */
    private static void collectMonitorData(int monitorId, String accessToken,
            String startDate, String endDate, Map<Integer, ScheduledDataCollector.MonitorData> monitorData) {
        
        try {
            System.out.println("   📡 Fetching data for monitor " + monitorId + "...");
            
            // Trend computed features
            String trendFeatures = GetTrendComputedFeatures.getTrendComputedFeatures(
                accessToken, monitorId, startDate, endDate);
            
            // Trend history
            String trendHistory = GetTrendHistory.getTrendHistory(
                accessToken, monitorId, startDate, endDate, 1, "day");
            
            // Store the results
            monitorData.put(monitorId, new ScheduledDataCollector.MonitorData(trendFeatures, trendHistory));
            
            System.out.println("   ✅ Monitor " + monitorId + " data fetched successfully");
            
        } catch (Exception e) {
            System.out.println("   ❌ Monitor " + monitorId + " fetch error: " + e.getMessage());
        }
    }
    
    /**
     * Shut down the executor service.
     */
    public static void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Print thread pool status.
     */
    public static void printThreadPoolStatus() {
        if (executorService != null && !executorService.isShutdown()) {
            System.out.println("Thread Pool Status:");
            System.out.println("  Active threads: " + ((ThreadPoolExecutor) executorService).getActiveCount());
            System.out.println("  Total threads: " + ((ThreadPoolExecutor) executorService).getPoolSize());
            System.out.println("  Maximum threads: " + ((ThreadPoolExecutor) executorService).getMaximumPoolSize());
            System.out.println("  Queue size: " + ((ThreadPoolExecutor) executorService).getQueue().size());
        } else {
            System.out.println("Thread Pool Status: Shut down or not started");
        }
    }
}
