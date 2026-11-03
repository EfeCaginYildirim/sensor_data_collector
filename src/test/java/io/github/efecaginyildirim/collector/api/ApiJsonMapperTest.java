package io.github.efecaginyildirim.collector.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.efecaginyildirim.collector.api.MonitoringApi.ApiPage;
import io.github.efecaginyildirim.collector.model.BasicFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.model.ComputedFeatureSample;
import io.github.efecaginyildirim.collector.model.MonitorInfo;
import io.github.efecaginyildirim.collector.model.Plant;
import io.github.efecaginyildirim.collector.time.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ApiJsonMapperTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final TimeWindow JULY = new TimeWindow(Instant.parse("2025-07-01T00:00:00Z"), Instant.parse("2025-08-01T00:00:00Z"));

    static JsonNode json(String s) throws Exception {
        return JSON.readTree(s.replace('\'', '"'));
    }

    @Test
    void plantMayBeWrappedInDataOrAtTheRoot() throws Exception {
        String plant = "{'id':9,'name':'P','organizationName':'O','areas':[{'id':1,'name':'A','machineGroups':"
            + "[{'id':2,'name':'G','machines':[{'id':3,'name':'M','monitors':[{'id':'42','name':'S'}]}]}]}]}";
        Plant wrapped = ApiJsonMapper.plant(json("{'status':true,'data':" + plant + "}"));
        Plant bare = ApiJsonMapper.plant(json(plant));

        assertEquals(wrapped, bare);
        assertEquals(42, wrapped.locate(42).orElseThrow().monitor().id(), "textual ids are accepted");
    }

    @Test
    void missingOptionalFieldsAreNullNotZero() throws Exception {
        Plant p = ApiJsonMapper.plant(json("{'id':1,'areas':[{'id':1,'machineGroups':[{'id':1,'machines':"
            + "[{'id':1,'monitors':[{'id':5,'healthScore':null,'speed':'1480.5','velocityX':'n/a','alarms':2.5}]}]}]}]}"));
        MonitorInfo.MonitorStatus s = p.locate(5).orElseThrow().monitor().status();

        assertNull(s.healthScore());
        assertEquals(1480.5, s.speed(), "numeric strings are accepted");
        assertNull(s.velocityX().value(), "non-numeric strings become null");
        assertNull(s.alarms(), "non-integral values are not truncated into integers");
        assertNull(s.temperature().warning());
        assertNull(p.name());
    }

    @Test
    void missingIdsMakeTheResponseUnusable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ApiJsonMapper.plant(json("{'id':1,'areas':[{'name':'no id'}]}")));
        assertTrue(e.getMessage().startsWith("area without a numeric id"), e.getMessage());
    }

    @Test
    void absentAreasMeanAnEmptyPlant() throws Exception {
        assertTrue(ApiJsonMapper.plant(json("{'data':{'id':1}}")).areas().isEmpty());
    }

    @Test
    void basicFeaturesAcceptNestedObjectsAndCountUnparseableTimes() throws Exception {
        ApiPage<BasicFeature> page = ApiJsonMapper.basicFeatures(json("{'data':{'basicFeatures':["
            + "{'id':'a','time':'2025-07-02T00:00:00.000Z','jsonAvg':'{\\'v\\':1}','jsonMin':{'v':0},'jsonMax':null},"
            + "{'id':'b','time':'garbage'},"
            + "{'id':'c'},"
            + "{'id':'d','time':'2025-06-30T00:00:00Z'}"           // outside the window: silently dropped
            + "]}}"), JULY);

        assertEquals(1, page.items().size());
        BasicFeature b = page.items().get(0);
        assertEquals("a", b.sourceId());
        assertEquals("{\"v\":1}", b.jsonAvg());
        assertEquals("{\"v\":0}", b.jsonMin());
        assertEquals("{}", b.jsonMax());
        assertEquals(2, page.rejected());
    }

    @Test
    void computedFeaturesKeepTheLastDuplicateAndSortByTime() throws Exception {
        ApiPage<ComputedFeatureSample> page = ApiJsonMapper.computedFeatures(json("{'data':["
            + "{'timestamp':'2025-07-03T00:00:00Z','RPM':1},"
            + "{'timestamp':'1751414400000','RPM':2},"              // 2025-07-02 as epoch millis
            + "{'timestamp':'2025-07-03T03:00:00+03:00','RPM':3,'UNKNOWN_FEATURE':9}"  // same instant as the first
            + "]}"), JULY);

        assertEquals(2, page.items().size());
        assertEquals(1, page.duplicates());
        assertEquals(Instant.parse("2025-07-02T00:00:00Z"), page.items().get(0).time());
        assertEquals(3.0, page.items().get(1).value(ComputedFeature.RPM).orElseThrow());
        assertEquals(1, page.items().get(1).values().size(), "unknown fields are ignored");
    }

    @Test
    void nonArrayDataYieldsAnEmptyPage() throws Exception {
        assertTrue(ApiJsonMapper.computedFeatures(json("{'data':null}"), JULY).items().isEmpty());
        assertTrue(ApiJsonMapper.basicFeatures(json("{'status':true}"), JULY).items().isEmpty());
    }
}
