import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;

/**
 * Optimized JSON parser — uses Jackson for performance.
 * Single parsing strategy for all API responses.
 */
public class JsonParserOptimized {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    static {
        // Jackson configuration
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
    
    /**
     * Parse trend computed-features data (optimized).
     */
    public static String parseTrendFeaturesOptimized(String trendFeaturesJson) {
        try {
            JsonNode rootNode = mapper.readTree(trendFeaturesJson);
            JsonNode dataNode = rootNode.get("data");
            
            if (dataNode == null || !dataNode.isArray()) {
                return createEmptyTrendFeatures();
            }
            
            StringBuilder result = new StringBuilder();
            
            for (int i = 0; i < dataNode.size(); i++) {
                JsonNode item = dataNode.get(i);
                result.append("                      {\n");
                
                // Timestamp
                String timestamp = getStringValue(item, "timestamp");
                result.append("                        \"timestamp\": ").append(timestamp != null ? "\"" + timestamp + "\"" : "null").append(",\n");
                
                // Computed features
                String[] features = {
                    "KURTACCLX", "KURTACCLY", "KURTACCLZ",
                    "PPACCLX", "PPACCLY", "PPACCLZ",
                    "CRESTACCLX", "CRESTACCLY", "CRESTACCLZ",
                    "GRMSACCLX", "GRMSACCLY", "GRMSACCLZ",
                    "PPSWX", "PPSWY", "PPSWZ",
                    "SWRMSX", "SWRMSY", "SWRMSZ",
                    "IDLE", "RPM", "BPFI", "BPFO", "BSF", "FTF", "LOAD",
                    "VRMSX", "VRMSY", "VRMSZ", "TA"
                };
                
                for (int j = 0; j < features.length; j++) {
                    Object value = getValue(item, features[j]);
                    result.append("                        \"").append(features[j]).append("\": ").append(value != null ? value : "null");
                    if (j < features.length - 1) {
                        result.append(",");
                    }
                    result.append("\n");
                }
                
                result.append("                      }");
                if (i < dataNode.size() - 1) {
                    result.append(",");
                }
                result.append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            System.out.println("Optimized trend features parse error: " + e.getMessage());
            return createEmptyTrendFeatures();
        }
    }
    
    /**
     * Parse basic-features data (optimized).
     * Supports both the old API shape ({ data: [...] }) and the new shape
     * ({ data: { basicFeatures: [...] } }).
     */
    public static String parseBasicFeaturesOptimized(String trendHistoryJson) {
        try {
            JsonNode rootNode = mapper.readTree(trendHistoryJson);
            JsonNode dataNode = rootNode.get("data");

            // New API shape: { status: true, data: { fingerprintData: [], basicFeatures: [...] } }
            // Old shape fallback: { status: true, data: [ ... ] }
            JsonNode basicFeaturesArray = null;
            if (dataNode != null) {
                if (dataNode.isArray()) {
                    basicFeaturesArray = dataNode; // old shape
                } else if (dataNode.isObject()) {
                    JsonNode bf = dataNode.get("basicFeatures");
                    if (bf != null && bf.isArray() && bf.size() > 0) {
                        basicFeaturesArray = bf; // new shape
                    }
                }
            }

            if (basicFeaturesArray == null || !basicFeaturesArray.isArray()) {
                return createEmptyBasicFeatures();
            }
            
            StringBuilder result = new StringBuilder();
            
            for (int i = 0; i < basicFeaturesArray.size(); i++) {
                JsonNode item = basicFeaturesArray.get(i);
                result.append("                      {\n");
                
                // ID
                String id = getStringValue(item, "id");
                result.append("                        \"id\": ").append(id != null ? "\"" + id + "\"" : "null").append(",\n");
                
                // Time
                String time = getStringValue(item, "time");
                result.append("                        \"time\": ").append(time != null ? "\"" + time + "\"" : "null").append(",\n");
                
                // Monitor ID
                int monitorId = getIntValue(item, "monitorId", 0);
                result.append("                        \"monitorId\": ").append(monitorId).append(",\n");
                
                // JSON metric values
                String jsonAvg = getStringValue(item, "jsonAvg");
                result.append("                        \"jsonAvg\": ")
                      .append(jsonAvg != null ? "\"" + escapeJsonString(jsonAvg) + "\"" : "\"{}\"")
                      .append(",\n");
                
                String jsonMin = getStringValue(item, "jsonMin");
                result.append("                        \"jsonMin\": ")
                      .append(jsonMin != null ? "\"" + escapeJsonString(jsonMin) + "\"" : "\"{}\"")
                      .append(",\n");
                
                String jsonMax = getStringValue(item, "jsonMax");
                result.append("                        \"jsonMax\": ")
                      .append(jsonMax != null ? "\"" + escapeJsonString(jsonMax) + "\"" : "\"{}\"")
                      .append("\n");
                
                result.append("                      }");
                if (i < basicFeaturesArray.size() - 1) {
                    result.append(",");
                }
                result.append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            System.out.println("Optimized basic features parse error: " + e.getMessage());
            return createEmptyBasicFeatures();
        }
    }

    /**
     * Parse plant info from raw API JSON and return a structured JsonNode.
     * Plant name and organization name are overridden with generic placeholders.
     */
    public static JsonNode parsePlantInfo(String plantInfoJson) {
        try {
            JsonNode root = mapper.readTree(plantInfoJson);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode()) {
                dataNode = root; // some responses wrap data at the root
            }
            // Override sensitive identifiers with anonymous placeholders
            com.fasterxml.jackson.databind.node.ObjectNode plant =
                (com.fasterxml.jackson.databind.node.ObjectNode) dataNode.deepCopy();
            plant.put("name", "Example Manufacturing Plant");
            plant.put("organizationName", "EXAMPLE_CORP");
            return plant;
        } catch (Exception e) {
            System.out.println("Plant info parse error: " + e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /**
     * Expose the shared ObjectMapper for use in ScheduledDataCollector.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    private static String getStringValue(JsonNode node, String key) {
        JsonNode valueNode = node.get(key);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        // If it is a plain string, return the text; otherwise serialize to JSON.
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        return valueNode.toString();
    }

    /**
     * Escape content for safe inclusion inside a JSON string value.
     */
    private static String escapeJsonString(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
    
    private static int getIntValue(JsonNode node, String key, int defaultValue) {
        JsonNode valueNode = node.get(key);
        return valueNode != null && !valueNode.isNull() ? valueNode.asInt() : defaultValue;
    }
    
    @SuppressWarnings("unused")
    private static double getDoubleValue(JsonNode node, String key) {
        JsonNode valueNode = node.get(key);
        return valueNode != null && !valueNode.isNull() ? valueNode.asDouble() : 0.0;
    }
    
    private static Object getValue(JsonNode node, String key) {
        JsonNode valueNode = node.get(key);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        } else if (valueNode.isNumber()) {
            return valueNode.isInt() ? valueNode.asInt() : valueNode.asDouble();
        } else if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        return valueNode.asText();
    }
    
    // ─── Empty-record factories ──────────────────────────────────────────────────

    private static String createEmptyTrendFeatures() {
        return "                      {\n" +
               "                        \"timestamp\": null,\n" +
               "                        \"KURTACCLX\": null,\n" +
               "                        \"KURTACCLY\": null,\n" +
               "                        \"KURTACCLZ\": null,\n" +
               "                        \"PPACCLX\": null,\n" +
               "                        \"PPACCLY\": null,\n" +
               "                        \"PPACCLZ\": null,\n" +
               "                        \"CRESTACCLX\": null,\n" +
               "                        \"CRESTACCLY\": null,\n" +
               "                        \"CRESTACCLZ\": null,\n" +
               "                        \"GRMSACCLX\": null,\n" +
               "                        \"GRMSACCLY\": null,\n" +
               "                        \"GRMSACCLZ\": null,\n" +
               "                        \"PPSWX\": null,\n" +
               "                        \"PPSWY\": null,\n" +
               "                        \"PPSWZ\": null,\n" +
               "                        \"SWRMSX\": null,\n" +
               "                        \"SWRMSY\": null,\n" +
               "                        \"SWRMSZ\": null,\n" +
               "                        \"IDLE\": null,\n" +
               "                        \"RPM\": null,\n" +
               "                        \"BPFI\": null,\n" +
               "                        \"BPFO\": null,\n" +
               "                        \"BSF\": null,\n" +
               "                        \"FTF\": null,\n" +
               "                        \"LOAD\": null,\n" +
               "                        \"VRMSX\": null,\n" +
               "                        \"VRMSY\": null,\n" +
               "                        \"VRMSZ\": null,\n" +
               "                        \"TA\": null\n" +
               "                      }\n";
    }
    
    private static String createEmptyBasicFeatures() {
        return "                      {\n" +
               "                        \"id\": null,\n" +
               "                        \"time\": null,\n" +
               "                        \"monitorId\": 0,\n" +
               "                        \"jsonAvg\": null,\n" +
               "                        \"jsonMin\": null,\n" +
               "                        \"jsonMax\": null\n" +
               "                      }\n";
    }

    // Overload kept for backward compatibility with OptimizedDataInsertion
    private static String createEmptyBasicFeatures(int monitorId) {
        return "                      {\n" +
               "                        \"id\": null,\n" +
               "                        \"time\": null,\n" +
               "                        \"monitorId\": " + monitorId + ",\n" +
               "                        \"jsonAvg\": null,\n" +
               "                        \"jsonMin\": null,\n" +
               "                        \"jsonMax\": null\n" +
               "                      }\n";
    }
}
