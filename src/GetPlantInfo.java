/** Utility class to fetch plant information from the external API. */
public class GetPlantInfo {
    public static String getPlantInfo(int plantId, String accessToken) throws Exception {
        return HttpConnectionPool.sendGetRequest("/plants/" + plantId, accessToken);
    }
}
