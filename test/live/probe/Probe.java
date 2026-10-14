package georestrict.live;

import com.google.gson.Gson;
import zip.linuxaddict.georestrict.api.GeoRestrictAPI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


public final class Probe {
    public static void run(String platform, boolean serviceRegistered) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("platform", platform);
        results.put("available", GeoRestrictAPI.isAvailable());
        results.put("version", GeoRestrictAPI.getVersion());
        results.put("serviceRegistered", serviceRegistered);
        results.put("floodgateAvailable", zip.linuxaddict.georestrict.floodgate.FloodgateHandler.isAvailable());
        Map<String, CompletableFuture<?>> queries = new LinkedHashMap<>();
        queries.put("countryCode", GeoRestrictAPI.getCountryCode("8.8.8.8"));
        queries.put("countryName", GeoRestrictAPI.getCountryName("8.8.8.8"));
        queries.put("asn", GeoRestrictAPI.getAsn("8.8.8.8"));
        queries.put("isp", GeoRestrictAPI.getIsp("8.8.8.8"));
        queries.put("asnName", GeoRestrictAPI.getAsnName("8.8.8.8"));
        queries.put("vpn", GeoRestrictAPI.isVpn("8.8.8.8"));
        queries.put("hosting", GeoRestrictAPI.isHosting("8.8.8.8"));
        queries.put("proxy", GeoRestrictAPI.isProxy("8.8.8.8"));
        queries.put("mobile", GeoRestrictAPI.isMobile("8.8.8.8"));
        queries.put("allowed", GeoRestrictAPI.isAllowed("8.8.8.8", "ApiProbe"));
        queries.put("blocked", GeoRestrictAPI.isBlocked("8.8.8.8", "ApiProbe"));
        queries.put("lookup", GeoRestrictAPI.lookup("8.8.8.8"));
        queries.put("batch", GeoRestrictAPI.lookupAll(List.of("8.8.8.8", "1.1.1.1", "127.0.0.1")));
        queries.put("check", GeoRestrictAPI.checkIp("8.8.8.8", "ApiProbe"));
        queries.put("localBypass", GeoRestrictAPI.checkIp("127.0.0.1", "ApiProbe", true));
        CompletableFuture.allOf(queries.values().toArray(new CompletableFuture<?>[0]))
            .whenComplete((unused, error) -> {
                if (error != null) results.put("error", error.toString());
                else {
                    queries.forEach((name, future) -> results.put(name, future.join()));
                    results.put("cachedCountry", GeoRestrictAPI.getCachedCountryCode("8.8.8.8"));
                    results.put("cachedResponse", GeoRestrictAPI.getCachedResponse("8.8.8.8") != null);
                    results.put("cacheSize", GeoRestrictAPI.getCacheSize());
                    GeoRestrictAPI.purgeCache();
                    results.put("purgedCacheSize", GeoRestrictAPI.getCacheSize());
                }
                System.out.println("GEO_LIVE_API " + new Gson().toJson(results));
            });
    }
}
