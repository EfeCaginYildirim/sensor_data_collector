import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Main scheduler that collects sensor data on a configurable schedule,
 * builds a combined JSON document, saves it to disk, and inserts it into the database.
 */
public class ScheduledDataCollector {

    /** Scheduler for timed data collection runs. */
    private static ScheduledExecutorService scheduler;

    /** Holds the most recently collected per-monitor data. */
    private static Map<Integer, MonitorData> monitorData = new HashMap<>();

    /** Holds raw API responses for a single monitor. */
    static class MonitorData {
        String trendFeatures;
        String trendHistory;

        public MonitorData(String trendFeatures, String trendHistory) {
            this.trendFeatures = trendFeatures;
            this.trendHistory  = trendHistory;
        }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("🚀 Data Collection Application Starting...");

        ConfigManager.loadConfig();
        ApplicationLogger.start();

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🔄 Application shutting down, cleaning up...");
            stopScheduler();
            ConcurrentDataCollector.shutdown();
            DatabaseConnectionPool.shutdown();
            ApplicationLogger.stop();
            System.out.println("✅ Cleanup complete!");
        }));

        startScheduler();

        System.out.println("\nType 'run' for a manual collection, 'status' for scheduler info, 'reset' to clear history, or 'exit' to quit:");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            String input = scanner.nextLine().trim().toLowerCase();

            switch (input) {
                case "exit":
                    System.out.println("Shutting down...");
                    stopScheduler();
                    ConcurrentDataCollector.shutdown();
                    DatabaseConnectionPool.shutdown();
                    ApplicationLogger.stop();
                    scanner.close();
                    return;
                case "run":
                    System.out.println("Starting manual data collection...");
                    runDataCollection();
                    break;
                case "status":
                    showStatus();
                    break;
                case "reset":
                    System.out.println("🔄 Resetting run history...");
                    LastRunTracker.resetFirstRunFlag();
                    System.out.println("✅ History cleared. All historical data will be fetched on the next run.");
                    break;
                default:
                    System.out.println("Unknown command. Available commands: 'run', 'status', 'reset', 'exit'");
            }
        }
    }

    // ─── Scheduler lifecycle ─────────────────────────────────────────────────────

    private static void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(1);

        long initialDelay = calculateInitialDelay();
        int  repeatIntervalMinutes = ConfigManager.getRepeatIntervalMinutes();

        scheduler.scheduleAtFixedRate(
            ScheduledDataCollector::runDataCollection,
            initialDelay,
            repeatIntervalMinutes * 60L,
            TimeUnit.SECONDS
        );

        System.out.println("✅ Scheduler started!");
        System.out.println("⏰ First run: " +
            LocalDateTime.now().plusSeconds(initialDelay).format(DateTimeFormatter.ofPattern("dd-MM HH:mm")));
        System.out.println("🔄 Repeat interval: " + repeatIntervalMinutes + " minutes");
    }

    private static void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                System.out.println("✅ Scheduler stopped!");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Uses an explicit first-run delay when configured, otherwise the daily time. */
    private static long calculateInitialDelay() {
        int configuredDelayMinutes = ConfigManager.getInitialDelayMinutes();
        if (configuredDelayMinutes > 0) {
            return TimeUnit.MINUTES.toSeconds(configuredDelayMinutes);
        }
        LocalDateTime now = LocalDateTime.now();
        String[] parts    = ConfigManager.getDailyRunTime().split(":");
        int targetHour    = Integer.parseInt(parts[0]);
        int targetMinute  = Integer.parseInt(parts[1]);

        LocalDateTime todayTarget = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);
        LocalDateTime nextRun     = now.isBefore(todayTarget) ? todayTarget : todayTarget.plusDays(1);

        return java.time.Duration.between(now, nextRun).getSeconds();
    }

    private static void showStatus() {
        System.out.println("\n=== STATUS ===");
        System.out.println("Current time    : " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        System.out.println("Scheduler       : " + (scheduler != null && !scheduler.isShutdown() ? "Active" : "Stopped"));
        System.out.println("Target run time : " + ConfigManager.getDailyRunTime());
        System.out.println("Repeat interval : " + ConfigManager.getRepeatIntervalMinutes() + " minutes");

        if (scheduler != null && !scheduler.isShutdown()) {
            long nextDelay = calculateInitialDelay();
            System.out.println("Next run        : " +
                LocalDateTime.now().plusSeconds(nextDelay).format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")));
        }
        System.out.println("==============\n");
    }

    // ─── Data collection pipeline ─────────────────────────────────────────────────

    /** Main data collection pipeline: login → plant info → parallel fetch → merge JSON → DB insert. */
    public static void runDataCollection() {
        PerformanceOptimizer.startOperation("Total_Data_Collection");
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM HH:mm"));
            System.out.println("\n🔄 DATA COLLECTION STARTED (" + ts + ")");

            // 1. Login
            System.out.println("🔐 Logging in...");
            PerformanceOptimizer.startOperation("Login");
            String accessToken = performLogin();
            PerformanceOptimizer.endOperation("Login");

            if (accessToken == null) {
                System.out.println("❌ Login failed! Data collection aborted.");
                return;
            }
            System.out.println("✅ Login successful!");

            // 2. Fetch plant info
            System.out.println("📊 Fetching plant info...");
            PerformanceOptimizer.startOperation("Plant_Info");
            String plantInfoRaw = GetPlantInfo.getPlantInfo(ConfigManager.getPlantId(), accessToken);
            PerformanceOptimizer.endOperation("Plant_Info");

            // 3. Parallel data fetch for all monitors
            int[]    monitorIds = ConfigManager.getMonitorIds();
            if (monitorIds.length == 0) {
                throw new IllegalStateException("No monitor IDs are configured");
            }
            String[] dateRange  = LastRunTracker.calculateSmartDateRange();
            String   startDate  = dateRange[0];
            String   endDate    = dateRange[1];

            System.out.println("📅 Date range: " + startDate + " – " + endDate);

            PerformanceOptimizer.startOperation("Parallel_Data_Collection");
            monitorData = ConcurrentDataCollector.collectDataConcurrently(
                accessToken, monitorIds, startDate, endDate);
            PerformanceOptimizer.endOperation("Parallel_Data_Collection");

            if (monitorData.size() != monitorIds.length) {
                throw new IllegalStateException("Collection incomplete: received " + monitorData.size()
                    + " of " + monitorIds.length + " configured monitors");
            }

            // 4. Build combined JSON and write to file
            System.out.println("📝 Merging data...");
            PerformanceOptimizer.startOperation("JSON_Processing");
            String combinedData = createCombinedJson(plantInfoRaw, monitorData);
            PerformanceOptimizer.endOperation("JSON_Processing");

            boolean fileWritten = false;
            if (combinedData != null) {
                String fileName = ConfigManager.getJsonFilePrefix()
                    + (ConfigManager.createTimestampedFiles()
                        ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                        : "latest") + ".json";
                String outputDir = ConfigManager.getOutputDirectory();

                File dir = new File(outputDir);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Could not create output directory: " + outputDir);
                }

                fileWritten = writeToFile(new File(dir, fileName), combinedData);
                System.out.println("💾 File saved: " + fileName);
            }

            // 5. Insert into database
            System.out.println("🗄️ Saving to database...");
            PerformanceOptimizer.startOperation("Database_Operations");
            boolean sqlSuccess = insertAllDataToSQL(combinedData);
            PerformanceOptimizer.endOperation("Database_Operations");

            if (!sqlSuccess || !fileWritten) {
                throw new IllegalStateException("Collection was not fully persisted; last-run timestamp was not updated");
            }
            System.out.println("✅ Database insert successful!");

            // Update only after every configured monitor and both durable outputs succeed.
            LastRunTracker.updateLastRunTime();

            PerformanceOptimizer.endOperation("Total_Data_Collection");
            System.out.println("🎉 DATA COLLECTION COMPLETE (" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")) + ")");
            System.out.println("⏱️ Total elapsed: " + PerformanceOptimizer.getTotalRuntime() + " ms\n");

        } catch (Exception e) {
            System.out.println("❌ Data collection error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ─── Login ───────────────────────────────────────────────────────────────────

    private static String performLogin() {
        try {
            ObjectMapper mapper = JsonParserOptimized.getMapper();
            ObjectNode credentials = mapper.createObjectNode();
            credentials.put("username", ConfigManager.getApiUsername());
            credentials.put("password", ConfigManager.getApiPassword());
            String jsonData = mapper.writeValueAsString(credentials);
            String response = HttpConnectionPool.sendPostRequest("/login", jsonData);
            return extractAccessToken(response);
        } catch (Exception e) {
            System.out.println("Login error: " + e.getMessage());
            return null;
        }
    }

    /** Extract the accessToken field from a JSON login response using Jackson. */
    private static String extractAccessToken(String jsonResponse) {
        try {
            ObjectMapper mapper = JsonParserOptimized.getMapper();
            JsonNode root = mapper.readTree(jsonResponse);
            JsonNode tokenNode = root.path("accessToken");
            return tokenNode.isMissingNode() || tokenNode.isNull() ? null : tokenNode.asText();
        } catch (Exception e) {
            System.out.println("Token extraction error: " + e.getMessage());
            return null;
        }
    }

    // ─── Combined JSON construction ───────────────────────────────────────────────

    /**
     * Merge plant info (from the API) with per-monitor sensor data collected in parallel.
     *
     * <p>The plant name and organization name are overridden with generic placeholders.
     * For each monitor ID in {@code monitorData}, the method locates the matching monitor
     * node inside the plant JSON tree and injects {@code basicFeatures} and
     * {@code computedFeatureDataFields} arrays parsed from the raw API responses.
     *
     * @param plantInfoRaw raw JSON string returned by the plant-info API endpoint
     * @param monitorData  map of monitorId → raw API responses
     * @return pretty-printed combined JSON string, or {@code null} on parse failure
     */
    static String createCombinedJson(String plantInfoRaw, Map<Integer, MonitorData> monitorData) {
        try {
            ObjectMapper mapper = JsonParserOptimized.getMapper();

            // Parse plant info and anonymize identifying fields
            JsonNode plantNode = JsonParserOptimized.parsePlantInfo(plantInfoRaw);

            // Build the root envelope
            ObjectNode root = mapper.createObjectNode();
            root.put("status", true);

            // Clone plant node so we can inject monitor data into its tree
            ObjectNode data = (ObjectNode) plantNode.deepCopy();
            root.set("data", data);

            // Walk area → machineGroup → machine → monitor and inject sensor arrays
            JsonNode areasNode = data.get("areas");
            if (areasNode != null && areasNode.isArray()) {
                for (JsonNode areaNode : areasNode) {
                    JsonNode mgNodes = areaNode.get("machineGroups");
                    if (mgNodes == null || !mgNodes.isArray()) continue;

                    for (JsonNode mgNode : mgNodes) {
                        JsonNode machineNodes = mgNode.get("machines");
                        if (machineNodes == null || !machineNodes.isArray()) continue;

                        for (JsonNode machineNode : machineNodes) {
                            JsonNode monitorNodes = machineNode.get("monitors");
                            if (monitorNodes == null || !monitorNodes.isArray()) continue;

                            for (JsonNode monitorNode : monitorNodes) {
                                int monitorId = monitorNode.path("id").asInt(0);
                                injectMonitorData((ObjectNode) monitorNode, monitorId, monitorData, mapper);
                            }
                        }
                    }
                }
            }

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        } catch (Exception e) {
            System.out.println("JSON merge error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Inject {@code basicFeatures} and {@code computedFeatureDataFields} arrays
     * into the given monitor {@link ObjectNode} from the collected monitor data.
     */
    private static void injectMonitorData(ObjectNode monitorNode, int monitorId,
            Map<Integer, MonitorData> monitorData, ObjectMapper mapper) {
        MonitorData data = monitorData.get(monitorId);

        // Basic features
        ArrayNode basicArray = mapper.createArrayNode();
        if (data != null && data.trendHistory != null && !data.trendHistory.isEmpty()) {
            try {
                JsonNode historyRoot = mapper.readTree(data.trendHistory);
                JsonNode histData = historyRoot.path("data");

                JsonNode bfArray = null;
                if (histData.isArray()) {
                    bfArray = histData;
                } else if (histData.isObject()) {
                    JsonNode bf = histData.get("basicFeatures");
                    if (bf != null && bf.isArray()) bfArray = bf;
                }

                if (bfArray != null) {
                    for (JsonNode item : bfArray) {
                        basicArray.add(item);
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠️ Could not parse basic features for monitor " + monitorId + ": " + e.getMessage());
            }
        }
        monitorNode.set("basicFeatures", basicArray);

        // Computed feature data fields
        ArrayNode computedArray = mapper.createArrayNode();
        if (data != null && data.trendFeatures != null && !data.trendFeatures.isEmpty()) {
            try {
                JsonNode featRoot  = mapper.readTree(data.trendFeatures);
                JsonNode featData  = featRoot.path("data");
                if (featData.isArray()) {
                    for (JsonNode item : featData) {
                        computedArray.add(item);
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠️ Could not parse computed features for monitor " + monitorId + ": " + e.getMessage());
            }
        }
        monitorNode.set("computedFeatureDataFields", computedArray);
    }

    // ─── Database insertion ───────────────────────────────────────────────────────

    private static boolean insertAllDataToSQL(String jsonData) {
        try {
            if (!DatabaseConnectionPool.testConnection()) {
                System.out.println("❌ Database connection failed!");
                return false;
            }

            System.out.println("🔄 Starting optimized insertion...");
            int insertedCount = OptimizedDataInsertion.insertMotorDataOptimized(jsonData);

            if (insertedCount > 0) {
                System.out.println("✅ " + insertedCount + " new records inserted!");
                return true;
            } else {
                System.out.println("ℹ️ All records already exist (duplicates) — nothing new inserted.");
                return true; // This is normal
            }

        } catch (Exception e) {
            System.out.println("❌ SQL insertion error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ─── File I/O ─────────────────────────────────────────────────────────────────

    private static boolean writeToFile(File file, String content) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
            System.out.println("File written successfully: " + file);
            return true;
        } catch (IOException e) {
            System.out.println("File write error (" + file + "): " + e.getMessage());
            return false;
        }
    }
}
