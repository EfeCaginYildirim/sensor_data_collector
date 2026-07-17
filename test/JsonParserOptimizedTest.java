import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonParserOptimized}.
 */
class JsonParserOptimizedTest {

    // ─── Trend features ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseTrendFeaturesOptimized: parses a well-formed response")
    void parseTrendFeatures_validInput() {
        String json = "{\"status\":true,\"data\":["
            + "{\"timestamp\":\"2025-07-15T12:00:00.000Z\","
            + "\"KURTACCLX\":3.2,\"KURTACCLY\":2.8,\"KURTACCLZ\":3.5,"
            + "\"PPACCLX\":15.2,\"PPACCLY\":12.8,\"PPACCLZ\":18.5,"
            + "\"CRESTACCLX\":2.1,\"CRESTACCLY\":1.9,\"CRESTACCLZ\":2.3,"
            + "\"GRMSACCLX\":8.5,\"GRMSACCLY\":7.2,\"GRMSACCLZ\":9.8,"
            + "\"PPSWX\":0.85,\"PPSWY\":0.78,\"PPSWZ\":0.92,"
            + "\"SWRMSX\":6.2,\"SWRMSY\":5.8,\"SWRMSZ\":7.1,"
            + "\"IDLE\":0.15,\"RPM\":1500,\"BPFI\":125.5,\"BPFO\":98.2,"
            + "\"BSF\":45.8,\"FTF\":12.3,\"LOAD\":75.5,"
            + "\"VRMSX\":4.2,\"VRMSY\":3.8,\"VRMSZ\":5.1,\"TA\":0.92}]}";

        String result = JsonParserOptimized.parseTrendFeaturesOptimized(json);

        assertNotNull(result);
        assertTrue(result.contains("\"timestamp\": \"2025-07-15T12:00:00.000Z\""),
            "Result should contain the timestamp");
        assertTrue(result.contains("\"KURTACCLX\": 3.2"),
            "Result should contain KURTACCLX value");
        assertTrue(result.contains("\"RPM\": 1500"),
            "Result should contain RPM value");
        assertTrue(result.contains("\"TA\": 0.92"),
            "Result should contain TA value");
    }

    @Test
    @DisplayName("parseTrendFeaturesOptimized: returns empty placeholder for null data array")
    void parseTrendFeatures_nullDataArray() {
        String json = "{\"status\":true,\"data\":null}";
        String result = JsonParserOptimized.parseTrendFeaturesOptimized(json);
        assertNotNull(result);
        assertTrue(result.contains("\"timestamp\": null"),
            "Result should be the empty placeholder with null timestamp");
    }

    @Test
    @DisplayName("parseTrendFeaturesOptimized: handles empty data array gracefully")
    void parseTrendFeatures_emptyDataArray() {
        String json = "{\"status\":true,\"data\":[]}";
        String result = JsonParserOptimized.parseTrendFeaturesOptimized(json);
        // Empty array produces empty string (no items to append)
        assertNotNull(result);
        assertEquals("", result.trim(),
            "Empty data array should produce an empty result string");
    }

    @Test
    @DisplayName("parseTrendFeaturesOptimized: parses multiple timestamps")
    void parseTrendFeatures_multipleTimestamps() {
        String json = "{\"status\":true,\"data\":["
            + "{\"timestamp\":\"2025-07-15T12:00:00.000Z\",\"KURTACCLX\":1.0,\"KURTACCLY\":1.0,\"KURTACCLZ\":1.0,"
            + "\"PPACCLX\":1.0,\"PPACCLY\":1.0,\"PPACCLZ\":1.0,"
            + "\"CRESTACCLX\":1.0,\"CRESTACCLY\":1.0,\"CRESTACCLZ\":1.0,"
            + "\"GRMSACCLX\":1.0,\"GRMSACCLY\":1.0,\"GRMSACCLZ\":1.0,"
            + "\"PPSWX\":1.0,\"PPSWY\":1.0,\"PPSWZ\":1.0,"
            + "\"SWRMSX\":1.0,\"SWRMSY\":1.0,\"SWRMSZ\":1.0,"
            + "\"IDLE\":0.1,\"RPM\":1000,\"BPFI\":100.0,\"BPFO\":80.0,"
            + "\"BSF\":40.0,\"FTF\":10.0,\"LOAD\":60.0,"
            + "\"VRMSX\":3.0,\"VRMSY\":3.0,\"VRMSZ\":3.0,\"TA\":0.9},"
            + "{\"timestamp\":\"2025-07-16T12:00:00.000Z\",\"KURTACCLX\":2.0,\"KURTACCLY\":2.0,\"KURTACCLZ\":2.0,"
            + "\"PPACCLX\":2.0,\"PPACCLY\":2.0,\"PPACCLZ\":2.0,"
            + "\"CRESTACCLX\":2.0,\"CRESTACCLY\":2.0,\"CRESTACCLZ\":2.0,"
            + "\"GRMSACCLX\":2.0,\"GRMSACCLY\":2.0,\"GRMSACCLZ\":2.0,"
            + "\"PPSWX\":2.0,\"PPSWY\":2.0,\"PPSWZ\":2.0,"
            + "\"SWRMSX\":2.0,\"SWRMSY\":2.0,\"SWRMSZ\":2.0,"
            + "\"IDLE\":0.2,\"RPM\":2000,\"BPFI\":200.0,\"BPFO\":160.0,"
            + "\"BSF\":80.0,\"FTF\":20.0,\"LOAD\":70.0,"
            + "\"VRMSX\":4.0,\"VRMSY\":4.0,\"VRMSZ\":4.0,\"TA\":0.95}]}";

        String result = JsonParserOptimized.parseTrendFeaturesOptimized(json);
        assertTrue(result.contains("2025-07-15"), "Should contain first timestamp");
        assertTrue(result.contains("2025-07-16"), "Should contain second timestamp");
    }

    // ─── Basic features ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseBasicFeaturesOptimized: parses old-shape response (data is array)")
    void parseBasicFeatures_oldShape() {
        String json = "{\"status\":true,\"data\":["
            + "{\"id\":\"basic_001\","
            + "\"time\":\"2025-07-15T12:00:00.000Z\","
            + "\"monitorId\":42,"
            + "\"jsonAvg\":\"{\\\"velocityX\\\": 2.5}\","
            + "\"jsonMin\":\"{\\\"velocityX\\\": 2.0}\","
            + "\"jsonMax\":\"{\\\"velocityX\\\": 3.0}\"}]}";

        String result = JsonParserOptimized.parseBasicFeaturesOptimized(json);

        assertNotNull(result);
        assertTrue(result.contains("\"id\": \"basic_001\""),    "Should contain ID");
        assertTrue(result.contains("\"monitorId\": 42"),         "Should contain monitorId");
        assertTrue(result.contains("2025-07-15"),                "Should contain time");
    }

    @Test
    @DisplayName("parseBasicFeaturesOptimized: parses new-shape response (data.basicFeatures)")
    void parseBasicFeatures_newShape() {
        String json = "{\"status\":true,\"data\":{"
            + "\"fingerprintData\":[],"
            + "\"basicFeatures\":["
            + "{\"id\":\"bf_1\","
            + "\"time\":\"2025-08-01T08:00:00.000Z\","
            + "\"monitorId\":99,"
            + "\"jsonAvg\":\"{}\","
            + "\"jsonMin\":\"{}\","
            + "\"jsonMax\":\"{}\"}]}}";

        String result = JsonParserOptimized.parseBasicFeaturesOptimized(json);
        assertNotNull(result);
        assertTrue(result.contains("\"id\": \"bf_1\""),  "Should contain ID from new shape");
        assertTrue(result.contains("\"monitorId\": 99"), "Should contain monitorId from new shape");
    }

    @Test
    @DisplayName("parseBasicFeaturesOptimized: returns empty placeholder for missing data")
    void parseBasicFeatures_missingData() {
        String json = "{\"status\":true}";
        String result = JsonParserOptimized.parseBasicFeaturesOptimized(json);
        assertNotNull(result);
        assertTrue(result.contains("\"monitorId\": 0"), "Empty placeholder should have monitorId 0");
    }

    // ─── Plant info ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("parsePlantInfo: overrides name and organizationName with placeholders")
    void parsePlantInfo_overridesIdentifiers() {
        String json = "{\"data\":{\"id\":9999,\"name\":\"Real Plant Name\","
            + "\"organizationName\":\"RealCorp\",\"odrEnabled\":true}}";

        com.fasterxml.jackson.databind.JsonNode result = JsonParserOptimized.parsePlantInfo(json);

        assertNotNull(result);
        assertEquals("Example Manufacturing Plant", result.path("name").asText(),
            "Plant name should be replaced with generic placeholder");
        assertEquals("EXAMPLE_CORP", result.path("organizationName").asText(),
            "Organization name should be replaced with generic placeholder");
        assertEquals(9999, result.path("id").asInt(),
            "Plant ID should be preserved from the API response");
    }

    @Test
    @DisplayName("parsePlantInfo: returns empty ObjectNode for invalid JSON")
    void parsePlantInfo_invalidJson() {
        com.fasterxml.jackson.databind.JsonNode result = JsonParserOptimized.parsePlantInfo("NOT_JSON");
        assertNotNull(result);
        assertTrue(result.isObject(), "Should return an empty ObjectNode for invalid input");
    }
}
