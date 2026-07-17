/** Utility class to fetch trend-history data from the external API. */
public class GetTrendHistory {
    public static String getTrendHistory(String accessToken, int monitorId, String fromDate,
            String toDate, int interval, String intervalUnit) throws Exception {
        String query = String.format("/trend-history?monitorId=%d&from=%s&to=%s&interval=%d&intervalUnit=%s",
            monitorId, fromDate, toDate, interval, intervalUnit);
        return HttpConnectionPool.sendGetRequest(query, accessToken);
    }
}
