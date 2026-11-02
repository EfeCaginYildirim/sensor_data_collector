import java.io.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import com.fasterxml.jackson.databind.*;

/**
 * Optimized data insertion for large data sets.
 * <ul>
 *   <li>Duplicate-safe MERGE operations keyed on (monitor_id, basicFeature_time, computedFeature_timestamp)</li>
 *   <li>High throughput via JDBC batch processing</li>
 *   <li>Two inner context objects reduce method-parameter count and improve readability</li>
 * </ul>
 */
public class OptimizedDataInsertion {

    // Batch size — tuned for performance
    private static final int BATCH_SIZE = 5000;

    // Duplicate-detection key columns
    @SuppressWarnings("unused")
    private static final String UNIQUE_KEY_FIELDS = "monitor_id, basicFeature_time, computedFeature_timestamp";

    // MERGE SQL built once at class-load time
    private static final String MERGE_SQL = buildMergeSQL();

    // ─── Public entry point ──────────────────────────────────────────────────────

    /**
     * Insert (or skip duplicate) motor data from a combined JSON document.
     *
     * @param jsonContent full combined JSON string
     * @return number of rows actually inserted (duplicates excluded)
     */
    public static int insertMotorDataOptimized(String jsonContent) {
        int totalInserted = 0;
        int duplicateCount = 0;

        try {
            System.out.println("🚀 Starting optimized data insertion...");

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(jsonContent);

            Connection conn = DatabaseConnectionPool.getConnection();
            try {
                PreparedStatement mergePS = conn.prepareStatement(MERGE_SQL);
                System.out.println("📊 Parsing JSON...");

                // Plant-level context
                JsonNode dataNode = rootNode.get("data");
                PlantContext plant = PlantContext.fromNode(dataNode);

                // Walk: area → machineGroup → machine → monitor
                JsonNode areasNode = dataNode.get("areas");
                for (JsonNode areaNode : areasNode) {
                    AreaContext area = AreaContext.fromNode(areaNode);

                    for (JsonNode mgNode : areaNode.get("machineGroups")) {
                        MachineGroupContext mg = MachineGroupContext.fromNode(mgNode);

                        for (JsonNode machineNode : mgNode.get("machines")) {
                            MachineContext machine = MachineContext.fromNode(machineNode);

                            for (JsonNode monitorNode : machineNode.get("monitors")) {
                                MonitorContext monitor = MonitorContext.fromNode(monitorNode);

                                JsonNode basicFeaturesNode    = monitorNode.get("basicFeatures");
                                JsonNode computedFeaturesNode = monitorNode.get("computedFeatureDataFields");

                                int[] counts = processMonitorData(
                                    mergePS, plant, area, mg, machine, monitor,
                                    basicFeaturesNode, computedFeaturesNode,
                                    totalInserted, duplicateCount);
                                totalInserted  = counts[0];
                                duplicateCount = counts[1];
                            }
                        }
                    }
                }

                // Flush remaining batch
                if (totalInserted % BATCH_SIZE != 0) {
                    int[] results = mergePS.executeBatch();
                    duplicateCount += countDuplicates(results);
                    System.out.println("📦 Final batch flushed: " + totalInserted + " records");
                }

                conn.commit();
                mergePS.close();

                System.out.println("✅ Optimized insertion complete!");
                System.out.println("📊 Total processed: " + totalInserted);
                System.out.println("🔄 Duplicates skipped: " + duplicateCount);
                System.out.println("✨ New rows inserted: " + (totalInserted - duplicateCount));

            } catch (SQLException e) {
                System.out.println("❌ Database error: " + e.getMessage());
                e.printStackTrace();
                return 0;
            } finally {
                DatabaseConnectionPool.releaseConnection(conn);
            }

        } catch (Exception e) {
            System.out.println("❌ General error: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }

        return totalInserted - duplicateCount;
    }

    // ─── Inner context records ────────────────────────────────────────────────────

    /** Holds the plant-level fields extracted from the JSON. */
    static final class PlantContext {
        final int     id;
        final String  name;
        final String  organizationName;
        final boolean odrEnabled;

        private PlantContext(int id, String name, String organizationName, boolean odrEnabled) {
            this.id              = id;
            this.name            = name;
            this.organizationName = organizationName;
            this.odrEnabled      = odrEnabled;
        }

        static PlantContext fromNode(JsonNode n) {
            return new PlantContext(
                n.get("id").asInt(),
                n.get("name").asText(),
                n.get("organizationName").asText(),
                n.get("odrEnabled").asBoolean()
            );
        }
    }

    /** Holds area-level fields. */
    static final class AreaContext {
        final int    id;
        final String name;
        final int    status;

        private AreaContext(int id, String name, int status) {
            this.id = id; this.name = name; this.status = status;
        }

        static AreaContext fromNode(JsonNode n) {
            return new AreaContext(
                n.get("id").asInt(),
                n.get("name").asText(),
                n.has("status") ? n.get("status").asInt() : 0
            );
        }
    }

    /** Holds machine-group-level fields. */
    static final class MachineGroupContext {
        final int     id, status, synPriority;
        final String  name, drsTag, serviceRequestStatus, superscript, superscriptMessage;
        final boolean hasComments;

        private MachineGroupContext(int id, String name, int status, boolean hasComments,
                String drsTag, String serviceRequestStatus, int synPriority,
                String superscript, String superscriptMessage) {
            this.id = id; this.name = name; this.status = status;
            this.hasComments = hasComments; this.drsTag = drsTag;
            this.serviceRequestStatus = serviceRequestStatus; this.synPriority = synPriority;
            this.superscript = superscript; this.superscriptMessage = superscriptMessage;
        }

        static MachineGroupContext fromNode(JsonNode n) {
            return new MachineGroupContext(
                n.get("id").asInt(), n.get("name").asText(),
                n.has("status")               ? n.get("status").asInt()                        : 0,
                n.has("hasComments")          ? n.get("hasComments").asBoolean()               : false,
                n.has("drsTag")               ? n.get("drsTag").asText()                       : null,
                n.has("serviceRequestStatus") ? n.get("serviceRequestStatus").asText()         : null,
                n.has("synPriority")          ? n.get("synPriority").asInt()                   : 0,
                n.has("superscript")          ? n.get("superscript").asText()                  : null,
                n.has("superscriptMessage")   ? n.get("superscriptMessage").asText()           : null
            );
        }
    }

    /** Holds machine-level fields. */
    static final class MachineContext {
        final int     id, monitorCount, healthScore, trend, status, synPriority;
        final String  name, observation, diagnostic, recommendation,
                      mountingOrientation, superscript, superscriptMessage;
        final boolean isDrsReportOpen;

        private MachineContext(int id, String name, int monitorCount, String observation,
                String diagnostic, String recommendation, int healthScore, int trend, int status,
                boolean isDrsReportOpen, String mountingOrientation,
                String superscript, String superscriptMessage, int synPriority) {
            this.id = id; this.name = name; this.monitorCount = monitorCount;
            this.observation = observation; this.diagnostic = diagnostic;
            this.recommendation = recommendation; this.healthScore = healthScore;
            this.trend = trend; this.status = status; this.isDrsReportOpen = isDrsReportOpen;
            this.mountingOrientation = mountingOrientation;
            this.superscript = superscript; this.superscriptMessage = superscriptMessage;
            this.synPriority = synPriority;
        }

        static MachineContext fromNode(JsonNode n) {
            return new MachineContext(
                n.get("id").asInt(), n.get("name").asText(),
                n.has("monitorCount")         ? n.get("monitorCount").asInt()            : 0,
                n.has("observation")          ? n.get("observation").asText()            : null,
                n.has("diagnostic")           ? n.get("diagnostic").asText()             : null,
                n.has("recommendation")       ? n.get("recommendation").asText()         : null,
                n.has("healthScore")          ? n.get("healthScore").asInt()             : 0,
                n.has("trend")                ? n.get("trend").asInt()                   : 0,
                n.has("status")               ? n.get("status").asInt()                  : 0,
                n.has("isDrsReportOpen")      ? n.get("isDrsReportOpen").asBoolean()     : false,
                n.has("mountingOrientation")  ? n.get("mountingOrientation").asText()    : null,
                n.has("superscript")          ? n.get("superscript").asText()            : null,
                n.has("superscriptMessage")   ? n.get("superscriptMessage").asText()     : null,
                n.has("synPriority")          ? n.get("synPriority").asInt()             : 0
            );
        }
    }

    /** Holds monitor-level fields. */
    static final class MonitorContext {
        final int    id, alarms, healthScore, synPriority, status, trend, healthTrend;
        final double speed, velocityX, velocityXOp, velocityXCaution, velocityXWarning,
                     velocityY, velocityYOp, velocityYCaution, velocityYWarning,
                     velocityZ, velocityZOp, velocityZCaution, velocityZWarning,
                     temperature, temperatureOp, temperatureCaution, temperatureWarning;
        final String name, deviceIdentifier, orientationCode,
                     subscriptionStart, subscriptionEnd,
                     observation, diagnostic, recommendation, error,
                     superscript, superscriptMessage;

        private MonitorContext(int id, String name, String deviceIdentifier, String orientationCode,
                String subscriptionStart, String subscriptionEnd, int alarms, String observation,
                String diagnostic, String recommendation, String error, int healthScore,
                double speed, int synPriority,
                double velocityX, double velocityXOp, double velocityXCaution, double velocityXWarning,
                double velocityY, double velocityYOp, double velocityYCaution, double velocityYWarning,
                double velocityZ, double velocityZOp, double velocityZCaution, double velocityZWarning,
                double temperature, double temperatureOp, double temperatureCaution, double temperatureWarning,
                int status, int trend, int healthTrend, String superscript, String superscriptMessage) {
            this.id = id; this.name = name; this.deviceIdentifier = deviceIdentifier;
            this.orientationCode = orientationCode; this.subscriptionStart = subscriptionStart;
            this.subscriptionEnd = subscriptionEnd; this.alarms = alarms; this.observation = observation;
            this.diagnostic = diagnostic; this.recommendation = recommendation; this.error = error;
            this.healthScore = healthScore; this.speed = speed; this.synPriority = synPriority;
            this.velocityX = velocityX; this.velocityXOp = velocityXOp;
            this.velocityXCaution = velocityXCaution; this.velocityXWarning = velocityXWarning;
            this.velocityY = velocityY; this.velocityYOp = velocityYOp;
            this.velocityYCaution = velocityYCaution; this.velocityYWarning = velocityYWarning;
            this.velocityZ = velocityZ; this.velocityZOp = velocityZOp;
            this.velocityZCaution = velocityZCaution; this.velocityZWarning = velocityZWarning;
            this.temperature = temperature; this.temperatureOp = temperatureOp;
            this.temperatureCaution = temperatureCaution; this.temperatureWarning = temperatureWarning;
            this.status = status; this.trend = trend; this.healthTrend = healthTrend;
            this.superscript = superscript; this.superscriptMessage = superscriptMessage;
        }

        static MonitorContext fromNode(JsonNode n) {
            return new MonitorContext(
                n.get("id").asInt(), n.get("name").asText(),
                n.has("deviceIdentifier")                  ? n.get("deviceIdentifier").asText()                  : null,
                n.has("orientationCode")                   ? n.get("orientationCode").asText()                   : null,
                n.has("subscriptionStart")                 ? n.get("subscriptionStart").asText()                 : null,
                n.has("subscriptionEnd")                   ? n.get("subscriptionEnd").asText()                   : null,
                n.has("alarms")                            ? n.get("alarms").asInt()                             : 0,
                n.has("observation")                       ? n.get("observation").asText()                       : null,
                n.has("diagnostic")                        ? n.get("diagnostic").asText()                        : null,
                n.has("recommendation")                    ? n.get("recommendation").asText()                    : null,
                n.has("error")                             ? n.get("error").asText()                             : null,
                n.has("healthScore")                       ? n.get("healthScore").asInt()                        : 0,
                n.has("speed")                             ? n.get("speed").asDouble()                           : 0.0,
                n.has("synPriority")                       ? n.get("synPriority").asInt()                        : 0,
                n.has("velocityX")                         ? n.get("velocityX").asDouble()                       : 0.0,
                n.has("velocityXOperationalThreshold")     ? n.get("velocityXOperationalThreshold").asDouble()   : 0.0,
                n.has("velocityXCautionThreshold")         ? n.get("velocityXCautionThreshold").asDouble()       : 0.0,
                n.has("velocityXWarningThreshold")         ? n.get("velocityXWarningThreshold").asDouble()       : 0.0,
                n.has("velocityY")                         ? n.get("velocityY").asDouble()                       : 0.0,
                n.has("velocityYOperationalThreshold")     ? n.get("velocityYOperationalThreshold").asDouble()   : 0.0,
                n.has("velocityYCautionThreshold")         ? n.get("velocityYCautionThreshold").asDouble()       : 0.0,
                n.has("velocityYWarningThreshold")         ? n.get("velocityYWarningThreshold").asDouble()       : 0.0,
                n.has("velocityZ")                         ? n.get("velocityZ").asDouble()                       : 0.0,
                n.has("velocityZOperationalThreshold")     ? n.get("velocityZOperationalThreshold").asDouble()   : 0.0,
                n.has("velocityZCautionThreshold")         ? n.get("velocityZCautionThreshold").asDouble()       : 0.0,
                n.has("velocityZWarningThreshold")         ? n.get("velocityZWarningThreshold").asDouble()       : 0.0,
                n.has("temperature")                       ? n.get("temperature").asDouble()                     : 0.0,
                n.has("temperatureOperationalThreshold")   ? n.get("temperatureOperationalThreshold").asDouble() : 0.0,
                n.has("temperatureCautionThreshold")       ? n.get("temperatureCautionThreshold").asDouble()     : 0.0,
                n.has("temperatureWarningThreshold")       ? n.get("temperatureWarningThreshold").asDouble()     : 0.0,
                n.has("status")                            ? n.get("status").asInt()                             : 0,
                n.has("trend")                             ? n.get("trend").asInt()                              : 0,
                n.has("healthTrend")                       ? n.get("healthTrend").asInt()                        : 0,
                n.has("superscript")                       ? n.get("superscript").asText()                       : null,
                n.has("superscriptMessage")                ? n.get("superscriptMessage").asText()                : null
            );
        }
    }

    // ─── Batch processing ────────────────────────────────────────────────────────

    /**
     * Process all feature combinations for one monitor and add them to the batch.
     * Scenario 1: basic features only
     * Scenario 2: computed features only
     * Scenario 3: both — cross-join (one row per basic × computed)
     * Scenario 4: neither — skip
     *
     * @return [updatedTotalInserted, updatedDuplicateCount]
     */
    private static int[] processMonitorData(
            PreparedStatement mergePS,
            PlantContext plant, AreaContext area, MachineGroupContext mg,
            MachineContext machine, MonitorContext monitor,
            JsonNode basicFeaturesNode, JsonNode computedFeaturesNode,
            int totalInserted, int duplicateCount) throws SQLException {

        boolean hasBasicFeatures    = basicFeaturesNode    != null && basicFeaturesNode.size()    > 0;
        boolean hasComputedFeatures = computedFeaturesNode != null && computedFeaturesNode.size() > 0;

        if (!hasBasicFeatures && !hasComputedFeatures) {
            return new int[]{totalInserted, duplicateCount};
        }

        if (hasBasicFeatures && !hasComputedFeatures) {
            // Scenario 1: basic features only
            for (JsonNode bf : basicFeaturesNode) {
                BasicFeatureRow row = BasicFeatureRow.fromNode(bf, monitor.id);
                if (row.hasData()) {
                    bindAndBatch(mergePS, plant, area, mg, machine, monitor, row, null);
                    totalInserted++;
                    if (totalInserted % BATCH_SIZE == 0) {
                        duplicateCount += countDuplicates(mergePS.executeBatch());
                        System.out.println("📦 Batch flushed: " + totalInserted + " records");
                    }
                }
            }
        } else if (!hasBasicFeatures) {
            // Scenario 2: computed features only
            for (JsonNode cf : computedFeaturesNode) {
                bindAndBatch(mergePS, plant, area, mg, machine, monitor, null, cf);
                totalInserted++;
                if (totalInserted % BATCH_SIZE == 0) {
                    duplicateCount += countDuplicates(mergePS.executeBatch());
                    System.out.println("📦 Batch flushed: " + totalInserted + " records");
                }
            }
        } else {
            // Scenario 3: cross-join
            for (JsonNode bf : basicFeaturesNode) {
                BasicFeatureRow row = BasicFeatureRow.fromNode(bf, monitor.id);
                if (!row.hasData()) continue;
                for (JsonNode cf : computedFeaturesNode) {
                    bindAndBatch(mergePS, plant, area, mg, machine, monitor, row, cf);
                    totalInserted++;
                    if (totalInserted % BATCH_SIZE == 0) {
                        duplicateCount += countDuplicates(mergePS.executeBatch());
                        System.out.println("📦 Batch flushed: " + totalInserted + " records");
                    }
                }
            }
        }

        return new int[]{totalInserted, duplicateCount};
    }

    /** Lightweight holder for one basic-feature record. */
    private static final class BasicFeatureRow {
        final String id, timeSql, jsonAvg, jsonMin, jsonMax;
        final int monitorId;

        private BasicFeatureRow(String id, String timeSql, int monitorId,
                String jsonAvg, String jsonMin, String jsonMax) {
            this.id = id; this.timeSql = timeSql; this.monitorId = monitorId;
            this.jsonAvg = jsonAvg; this.jsonMin = jsonMin; this.jsonMax = jsonMax;
        }

        static BasicFeatureRow fromNode(JsonNode n, int fallbackMonitorId) {
            String rawTime = n.has("time") && !n.get("time").isNull() ? n.get("time").asText() : null;
            return new BasicFeatureRow(
                n.has("id")        && !n.get("id").isNull()        ? n.get("id").asText()        : null,
                normalizeSqlDateTime(rawTime),
                n.has("monitorId")                                  ? n.get("monitorId").asInt()  : fallbackMonitorId,
                n.has("jsonAvg")   && !n.get("jsonAvg").isNull()   ? n.get("jsonAvg").asText()   : "{}",
                n.has("jsonMin")   && !n.get("jsonMin").isNull()   ? n.get("jsonMin").asText()   : "{}",
                n.has("jsonMax")   && !n.get("jsonMax").isNull()   ? n.get("jsonMax").asText()   : "{}"
            );
        }

        /** Returns true if this record carries any meaningful data. */
        boolean hasData() {
            return timeSql != null || !"{}".equals(jsonAvg) || !"{}".equals(jsonMin) || !"{}".equals(jsonMax);
        }
    }

    // ─── Parameter binding ───────────────────────────────────────────────────────

    /**
     * Bind all parameters for one MERGE row and add it to the batch.
     * @param bf  null when only computed features are present
     * @param cf  null when only basic features are present
     */
    private static void bindAndBatch(PreparedStatement ps,
            PlantContext plant, AreaContext area, MachineGroupContext mg,
            MachineContext machine, MonitorContext monitor,
            BasicFeatureRow bf, JsonNode cf) throws SQLException {

        int i = 1;
        // Plant
        ps.setInt   (i++, plant.id);
        ps.setString(i++, plant.name);
        ps.setString(i++, plant.organizationName);
        ps.setBoolean(i++, plant.odrEnabled);
        // Area
        ps.setInt   (i++, area.id);
        ps.setString(i++, area.name);
        ps.setInt   (i++, area.status);
        // Machine group
        ps.setInt    (i++, mg.id);
        ps.setString (i++, mg.name);
        ps.setInt    (i++, mg.status);
        ps.setBoolean(i++, mg.hasComments);
        ps.setString (i++, mg.drsTag);
        ps.setString (i++, mg.serviceRequestStatus);
        ps.setInt    (i++, mg.synPriority);
        ps.setString (i++, mg.superscript);
        ps.setString (i++, mg.superscriptMessage);
        // Machine
        ps.setInt    (i++, machine.id);
        ps.setString (i++, machine.name);
        ps.setInt    (i++, machine.monitorCount);
        ps.setString (i++, machine.observation);
        ps.setString (i++, machine.diagnostic);
        ps.setString (i++, machine.recommendation);
        ps.setInt    (i++, machine.healthScore);
        ps.setInt    (i++, machine.trend);
        ps.setInt    (i++, machine.status);
        ps.setBoolean(i++, machine.isDrsReportOpen);
        ps.setString (i++, machine.mountingOrientation);
        ps.setString (i++, machine.superscript);
        ps.setString (i++, machine.superscriptMessage);
        ps.setInt    (i++, machine.synPriority);
        // Monitor
        ps.setInt   (i++, monitor.id);
        ps.setString(i++, monitor.name);
        ps.setString(i++, monitor.deviceIdentifier);
        ps.setString(i++, monitor.orientationCode);
        ps.setString(i++, monitor.subscriptionStart);
        ps.setString(i++, monitor.subscriptionEnd);
        ps.setInt   (i++, monitor.alarms);
        ps.setString(i++, monitor.observation);
        ps.setString(i++, monitor.diagnostic);
        ps.setString(i++, monitor.recommendation);
        ps.setString(i++, monitor.error);
        ps.setInt   (i++, monitor.healthScore);
        ps.setDouble(i++, monitor.speed);
        ps.setInt   (i++, monitor.synPriority);
        ps.setDouble(i++, monitor.velocityX);
        ps.setDouble(i++, monitor.velocityXOp);
        ps.setDouble(i++, monitor.velocityXCaution);
        ps.setDouble(i++, monitor.velocityXWarning);
        ps.setDouble(i++, monitor.velocityY);
        ps.setDouble(i++, monitor.velocityYOp);
        ps.setDouble(i++, monitor.velocityYCaution);
        ps.setDouble(i++, monitor.velocityYWarning);
        ps.setDouble(i++, monitor.velocityZ);
        ps.setDouble(i++, monitor.velocityZOp);
        ps.setDouble(i++, monitor.velocityZCaution);
        ps.setDouble(i++, monitor.velocityZWarning);
        ps.setDouble(i++, monitor.temperature);
        ps.setDouble(i++, monitor.temperatureOp);
        ps.setDouble(i++, monitor.temperatureCaution);
        ps.setDouble(i++, monitor.temperatureWarning);
        ps.setInt   (i++, monitor.status);
        ps.setInt   (i++, monitor.trend);
        ps.setInt   (i++, monitor.healthTrend);
        ps.setString(i++, monitor.superscript);
        ps.setString(i++, monitor.superscriptMessage);
        // Basic feature
        if (bf != null) {
            ps.setString(i++, bf.id);
            ps.setString(i++, bf.timeSql);
            ps.setInt   (i++, bf.monitorId);
            ps.setString(i++, bf.jsonAvg);
            ps.setString(i++, bf.jsonMin);
            ps.setString(i++, bf.jsonMax);
        } else {
            ps.setNull  (i++, Types.VARCHAR);
            ps.setNull  (i++, Types.VARCHAR);
            ps.setInt   (i++, monitor.id);
            ps.setString(i++, "{}");
            ps.setString(i++, "{}");
            ps.setString(i++, "{}");
        }
        // Computed feature
        i = bindComputedFeature(ps, cf, i);

        ps.addBatch();
    }

    /** Bind the 30 computed-feature parameters (or set all to null). */
    private static int bindComputedFeature(PreparedStatement ps, JsonNode cf, int i) throws SQLException {
        if (cf != null) {
            ps.setString(i++, getString(cf, "timestamp"));
            String[] feats = {"KURTACCLX","KURTACCLY","KURTACCLZ","PPACCLX","PPACCLY","PPACCLZ",
                              "CRESTACCLX","CRESTACCLY","CRESTACCLZ","GRMSACCLX","GRMSACCLY","GRMSACCLZ",
                              "PPSWX","PPSWY","PPSWZ","SWRMSX","SWRMSY","SWRMSZ",
                              "IDLE","RPM","BPFI","BPFO","BSF","FTF","LOAD",
                              "VRMSX","VRMSY","VRMSZ","TA"};
            for (String feat : feats) {
                ps.setDouble(i++, getDouble(cf, feat));
            }
        } else {
            for (int j = 0; j < 30; j++) {
                ps.setObject(i++, null);
            }
        }
        return i;
    }

    // ─── SQL builder ─────────────────────────────────────────────────────────────

    private static String buildMergeSQL() {
        return "MERGE dbo.Motor_Data AS target\n" +
            "USING (SELECT \n" +
            "  ? as plant_id, ? as plant_name, ? as plant_organizationName, ? as plant_odrEnabled,\n" +
            "  ? as area_id, ? as area_name, ? as area_status,\n" +
            "  ? as machineGroup_id, ? as machineGroup_name, ? as machineGroup_status, ? as machineGroup_hasComments,\n" +
            "  ? as machineGroup_drsTag, ? as machineGroup_serviceRequestStatus, ? as machineGroup_synPriority,\n" +
            "  ? as machineGroup_superscript, ? as machineGroup_superscriptMessage,\n" +
            "  ? as machine_id, ? as machine_name, ? as machine_monitorCount, ? as machine_observation,\n" +
            "  ? as machine_diagnostic, ? as machine_recommendation, ? as machine_healthScore, ? as machine_trend,\n" +
            "  ? as machine_status, ? as machine_isDrsReportOpen, ? as machine_mountingOrientation,\n" +
            "  ? as machine_superscript, ? as machine_superscriptMessage, ? as machine_synPriority,\n" +
            "  ? as monitor_id, ? as monitor_name, ? as monitor_deviceIdentifier, ? as monitor_orientationCode,\n" +
            "  ? as monitor_subscriptionStart, ? as monitor_subscriptionEnd, ? as monitor_alarms, ? as monitor_observation,\n" +
            "  ? as monitor_diagnostic, ? as monitor_recommendation, ? as monitor_error, ? as monitor_healthScore,\n" +
            "  ? as monitor_speed, ? as monitor_synPriority, ? as monitor_velocityX, ? as monitor_velocityXOperationalThreshold,\n" +
            "  ? as monitor_velocityXCautionThreshold, ? as monitor_velocityXWarningThreshold, ? as monitor_velocityY,\n" +
            "  ? as monitor_velocityYOperationalThreshold, ? as monitor_velocityYCautionThreshold, ? as monitor_velocityYWarningThreshold,\n" +
            "  ? as monitor_velocityZ, ? as monitor_velocityZOperationalThreshold, ? as monitor_velocityZCautionThreshold,\n" +
            "  ? as monitor_velocityZWarningThreshold, ? as monitor_temperature, ? as monitor_temperatureOperationalThreshold,\n" +
            "  ? as monitor_temperatureCautionThreshold, ? as monitor_temperatureWarningThreshold, ? as monitor_status,\n" +
            "  ? as monitor_trend, ? as monitor_healthTrend, ? as monitor_superscript, ? as monitor_superscriptMessage,\n" +
            "  ? as basicFeature_id, ? as basicFeature_time, ? as basicFeature_monitorId, ? as basicFeature_jsonAvg,\n" +
            "  ? as basicFeature_jsonMin, ? as basicFeature_jsonMax,\n" +
            "  ? as computedFeature_timestamp, ? as computedFeature_KURTACCLX, ? as computedFeature_KURTACCLY, ? as computedFeature_KURTACCLZ,\n" +
            "  ? as computedFeature_PPACCLX, ? as computedFeature_PPACCLY, ? as computedFeature_PPACCLZ, ? as computedFeature_CRESTACCLX,\n" +
            "  ? as computedFeature_CRESTACCLY, ? as computedFeature_CRESTACCLZ, ? as computedFeature_GRMSACCLX, ? as computedFeature_GRMSACCLY,\n" +
            "  ? as computedFeature_GRMSACCLZ, ? as computedFeature_PPSWX, ? as computedFeature_PPSWY, ? as computedFeature_PPSWZ,\n" +
            "  ? as computedFeature_SWRMSX, ? as computedFeature_SWRMSY, ? as computedFeature_SWRMSZ, ? as computedFeature_IDLE,\n" +
            "  ? as computedFeature_RPM, ? as computedFeature_BPFI, ? as computedFeature_BPFO, ? as computedFeature_BSF,\n" +
            "  ? as computedFeature_FTF, ? as computedFeature_LOAD, ? as computedFeature_VRMSX, ? as computedFeature_VRMSY,\n" +
            "  ? as computedFeature_VRMSZ, ? as computedFeature_TA\n" +
            ") AS source\n" +
            "ON (target.monitor_id = source.monitor_id AND \n" +
            "    ISNULL(target.basicFeature_time, '') = ISNULL(source.basicFeature_time, '') AND \n" +
            "    ISNULL(target.computedFeature_timestamp, '') = ISNULL(source.computedFeature_timestamp, ''))\n" +
            "WHEN NOT MATCHED THEN\n" +
            "INSERT (plant_id, plant_name, plant_organizationName, plant_odrEnabled,\n" +
            "        area_id, area_name, area_status,\n" +
            "        machineGroup_id, machineGroup_name, machineGroup_status, machineGroup_hasComments,\n" +
            "        machineGroup_drsTag, machineGroup_serviceRequestStatus, machineGroup_synPriority,\n" +
            "        machineGroup_superscript, machineGroup_superscriptMessage,\n" +
            "        machine_id, machine_name, machine_monitorCount, machine_observation,\n" +
            "        machine_diagnostic, machine_recommendation, machine_healthScore, machine_trend,\n" +
            "        machine_status, machine_isDrsReportOpen, machine_mountingOrientation,\n" +
            "        machine_superscript, machine_superscriptMessage, machine_synPriority,\n" +
            "        monitor_id, monitor_name, monitor_deviceIdentifier, monitor_orientationCode,\n" +
            "        monitor_subscriptionStart, monitor_subscriptionEnd, monitor_alarms, monitor_observation,\n" +
            "        monitor_diagnostic, monitor_recommendation, monitor_error, monitor_healthScore,\n" +
            "        monitor_speed, monitor_synPriority, monitor_velocityX, monitor_velocityXOperationalThreshold,\n" +
            "        monitor_velocityXCautionThreshold, monitor_velocityXWarningThreshold, monitor_velocityY,\n" +
            "        monitor_velocityYOperationalThreshold, monitor_velocityYCautionThreshold, monitor_velocityYWarningThreshold,\n" +
            "        monitor_velocityZ, monitor_velocityZOperationalThreshold, monitor_velocityZCautionThreshold,\n" +
            "        monitor_velocityZWarningThreshold, monitor_temperature, monitor_temperatureOperationalThreshold,\n" +
            "        monitor_temperatureCautionThreshold, monitor_temperatureWarningThreshold, monitor_status,\n" +
            "        monitor_trend, monitor_healthTrend, monitor_superscript, monitor_superscriptMessage,\n" +
            "        basicFeature_id, basicFeature_time, basicFeature_monitorId, basicFeature_jsonAvg,\n" +
            "        basicFeature_jsonMin, basicFeature_jsonMax,\n" +
            "        computedFeature_timestamp, computedFeature_KURTACCLX, computedFeature_KURTACCLY, computedFeature_KURTACCLZ,\n" +
            "        computedFeature_PPACCLX, computedFeature_PPACCLY, computedFeature_PPACCLZ, computedFeature_CRESTACCLX,\n" +
            "        computedFeature_CRESTACCLY, computedFeature_CRESTACCLZ, computedFeature_GRMSACCLX, computedFeature_GRMSACCLY,\n" +
            "        computedFeature_GRMSACCLZ, computedFeature_PPSWX, computedFeature_PPSWY, computedFeature_PPSWZ,\n" +
            "        computedFeature_SWRMSX, computedFeature_SWRMSY, computedFeature_SWRMSZ, computedFeature_IDLE,\n" +
            "        computedFeature_RPM, computedFeature_BPFI, computedFeature_BPFO, computedFeature_BSF,\n" +
            "        computedFeature_FTF, computedFeature_LOAD, computedFeature_VRMSX, computedFeature_VRMSY,\n" +
            "        computedFeature_VRMSZ, computedFeature_TA)\n" +
            "VALUES (source.plant_id, source.plant_name, source.plant_organizationName, source.plant_odrEnabled,\n" +
            "        source.area_id, source.area_name, source.area_status,\n" +
            "        source.machineGroup_id, source.machineGroup_name, source.machineGroup_status, source.machineGroup_hasComments,\n" +
            "        source.machineGroup_drsTag, source.machineGroup_serviceRequestStatus, source.machineGroup_synPriority,\n" +
            "        source.machineGroup_superscript, source.machineGroup_superscriptMessage,\n" +
            "        source.machine_id, source.machine_name, source.machine_monitorCount, source.machine_observation,\n" +
            "        source.machine_diagnostic, source.machine_recommendation, source.machine_healthScore, source.machine_trend,\n" +
            "        source.machine_status, source.machine_isDrsReportOpen, source.machine_mountingOrientation,\n" +
            "        source.machine_superscript, source.machine_superscriptMessage, source.machine_synPriority,\n" +
            "        source.monitor_id, source.monitor_name, source.monitor_deviceIdentifier, source.monitor_orientationCode,\n" +
            "        source.monitor_subscriptionStart, source.monitor_subscriptionEnd, source.monitor_alarms, source.monitor_observation,\n" +
            "        source.monitor_diagnostic, source.monitor_recommendation, source.monitor_error, source.monitor_healthScore,\n" +
            "        source.monitor_speed, source.monitor_synPriority, source.monitor_velocityX, source.monitor_velocityXOperationalThreshold,\n" +
            "        source.monitor_velocityXCautionThreshold, source.monitor_velocityXWarningThreshold, source.monitor_velocityY,\n" +
            "        source.monitor_velocityYOperationalThreshold, source.monitor_velocityYCautionThreshold, source.monitor_velocityYWarningThreshold,\n" +
            "        source.monitor_velocityZ, source.monitor_velocityZOperationalThreshold, source.monitor_velocityZCautionThreshold,\n" +
            "        source.monitor_velocityZWarningThreshold, source.monitor_temperature, source.monitor_temperatureOperationalThreshold,\n" +
            "        source.monitor_temperatureCautionThreshold, source.monitor_temperatureWarningThreshold, source.monitor_status,\n" +
            "        source.monitor_trend, source.monitor_healthTrend, source.monitor_superscript, source.monitor_superscriptMessage,\n" +
            "        source.basicFeature_id, source.basicFeature_time, source.basicFeature_monitorId, source.basicFeature_jsonAvg,\n" +
            "        source.basicFeature_jsonMin, source.basicFeature_jsonMax,\n" +
            "        source.computedFeature_timestamp, source.computedFeature_KURTACCLX, source.computedFeature_KURTACCLY, source.computedFeature_KURTACCLZ,\n" +
            "        source.computedFeature_PPACCLX, source.computedFeature_PPACCLY, source.computedFeature_PPACCLZ, source.computedFeature_CRESTACCLX,\n" +
            "        source.computedFeature_CRESTACCLY, source.computedFeature_CRESTACCLZ, source.computedFeature_GRMSACCLX, source.computedFeature_GRMSACCLY,\n" +
            "        source.computedFeature_GRMSACCLZ, source.computedFeature_PPSWX, source.computedFeature_PPSWY, source.computedFeature_PPSWZ,\n" +
            "        source.computedFeature_SWRMSX, source.computedFeature_SWRMSY, source.computedFeature_SWRMSZ, source.computedFeature_IDLE,\n" +
            "        source.computedFeature_RPM, source.computedFeature_BPFI, source.computedFeature_BPFO, source.computedFeature_BSF,\n" +
            "        source.computedFeature_FTF, source.computedFeature_LOAD, source.computedFeature_VRMSX, source.computedFeature_VRMSY,\n" +
            "        source.computedFeature_VRMSZ, source.computedFeature_TA);";
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────

    /**
     * Normalize a timestamp string to the canonical SQL format {@code yyyy-MM-dd HH:mm:ss} (UTC).
     * Supports Unix epoch (10-digit seconds, 13-digit milliseconds), ISO-8601, and common patterns.
     * Falls back to the raw value if no format matches, letting SQL Server attempt the conversion.
     */
    static String normalizeSqlDateTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;

        // Unix epoch
        try {
            long num = Long.parseLong(s);
            Instant instant = (s.length() == 10) ? Instant.ofEpochSecond(num) : Instant.ofEpochMilli(num);
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (NumberFormatException ignore) { /* not numeric */ }

        // ISO-8601 with Z or offset
        try {
            return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignore) { /* not ISO instant */ }

        // Common text patterns
        for (String p : new String[]{
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"}) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p))
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException ignore) { /* try next */ }
        }

        return s; // last resort: let SQL Server attempt the conversion
    }

    private static String getString(JsonNode node, String key) {
        if (!node.has(key)) return null;
        JsonNode v = node.get(key);
        return v.isNull() ? null : v.asText();
    }

    private static double getDouble(JsonNode node, String key) {
        return node.has(key) ? node.get(key).asDouble() : 0.0;
    }

    private static int countDuplicates(int[] batchResults) {
        int count = 0;
        for (int r : batchResults) {
            if (r == 0) count++;
        }
        return count;
    }
}