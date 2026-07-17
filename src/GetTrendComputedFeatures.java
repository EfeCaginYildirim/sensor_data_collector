/** Utility class to fetch computed-feature data from the external API. */
public class GetTrendComputedFeatures {
    public static String getTrendComputedFeatures(String accessToken, int monitorId,
            String fromDate, String toDate) throws Exception {
        String query = String.format("/trend/computed-features?monitorId=%d&from=%s&to=%s",
            monitorId, fromDate, toDate);
        return HttpConnectionPool.sendGetRequest(query, accessToken);
    }
}
