package zip.linuxaddict.georestrict.api;

import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoResponse;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GeoRestrictAPI {

    private static final int CACHE_TTL_DAYS = 30;

    private static volatile GeoRestrictService service;
    private static volatile GeoCache cache;

    private GeoRestrictAPI() {}

    public static void register(GeoRestrictService serviceInstance, GeoCache cacheInstance) {
        service = serviceInstance;
        cache = cacheInstance;
    }

    public static void unregister() {
        service = null;
        cache = null;
    }

    public static boolean isAvailable() {
        return service != null;
    }

    public static GeoRestrictService getService() {
        GeoRestrictService s = service;
        if (s == null) {
            throw new IllegalStateException("GeoRestrict API is not initialized. Is GeoRestrict enabled?");
        }
        return s;
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName) {
        return getService().checkIp(ip, playerName, false);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, boolean bypass) {
        return getService().checkIp(ip, playerName, null, bypass);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, UUID uuid) {
        return getService().checkIp(ip, playerName, uuid, false);
    }

    public static CompletableFuture<GeoRestrictService.CheckResult> checkIp(String ip, String playerName, UUID uuid, boolean bypass) {
        return getService().checkIp(ip, playerName, uuid, bypass);
    }

    public static CompletableFuture<Boolean> isAllowed(String ip, String playerName) {
        return checkIp(ip, playerName).thenApply(result -> result.allowed);
    }

    public static CompletableFuture<Boolean> isBlocked(String ip, String playerName) {
        return checkIp(ip, playerName).thenApply(result -> !result.allowed);
    }

    public static CompletableFuture<GeoResponse> lookup(String ip) {
        return getService().lookup(ip);
    }

    public static GeoResponse getCachedResponse(String ip) {
        GeoCache c = cache;
        if (c == null) return null;
        return c.get(ip, CACHE_TTL_DAYS);
    }

    public static CompletableFuture<String> getCountryCode(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.countryCode : null);
    }

    public static CompletableFuture<String> getCountryName(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.countryName : null);
    }

    public static CompletableFuture<String> getAsn(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.asn : null);
    }

    public static CompletableFuture<String> getIsp(String ip) {
        return lookup(ip).thenApply(res -> res != null ? (res.asName != null ? res.asName : res.provider) : null);
    }

    public static CompletableFuture<String> getAsnName(String ip) {
        return lookup(ip).thenApply(res -> res != null ? res.asName : null);
    }

    public static CompletableFuture<Boolean> isVpn(String ip) {
        return lookup(ip).thenApply(res -> getService().isVpn(res));
    }

    public static CompletableFuture<Boolean> isProxy(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isProxy);
    }

    public static CompletableFuture<Boolean> isHosting(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isHosting);
    }

    public static CompletableFuture<Boolean> isMobile(String ip) {
        return lookup(ip).thenApply(res -> res != null && res.isMobile);
    }

    public static CompletableFuture<Map<String, GeoResponse>> lookupAll(Collection<String> ips) {
        Map<String, CompletableFuture<GeoResponse>> pending = new LinkedHashMap<>();
        if (ips != null) {
            for (String ip : ips) {
                String normalized = ip == null ? "" : ip.trim();
                if (!normalized.isEmpty() && !pending.containsKey(normalized)) {
                    pending.put(normalized, lookup(normalized));
                }
            }
        }
        return CompletableFuture.allOf(pending.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, GeoResponse> results = new LinkedHashMap<>(pending.size());
                pending.forEach((address, future) -> results.put(address, future.join()));
                return results;
            });
    }

    public static String getCachedCountryCode(String ip) {
        GeoResponse cached = getCachedResponse(ip);
        return cached != null ? cached.countryCode : null;
    }

    public static int getCacheSize() {
        GeoCache c = cache;
        return c == null ? 0 : c.getStats().entryCount;
    }

    public static void purgeCache() {
        GeoCache c = cache;
        if (c != null) c.purgeAll();
    }

    public static String getVersion() {
        return PluginInfo.VERSION;
    }
}
