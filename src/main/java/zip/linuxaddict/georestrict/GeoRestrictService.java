/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class GeoRestrictService {

    private static final String FALLBACK_URL =
        "https://ip-api.com/json/%s?fields=status,message,query,country,countryCode,isp,org,as,proxy,hosting,mobile";

    private volatile GeoConfig config;
    private final Logger logger;
    private final GeoCache cache;
    private final Gson gson = new Gson();
    private final ExecutorService executor;

    public GeoRestrictService(GeoConfig config, Logger logger, GeoCache cache) {
        this.config = config;
        this.logger = logger;
        this.cache = cache;
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, config.lookupThreads), new LookupThreadFactory());
    }

    public void setConfig(GeoConfig config) {
        this.config = config;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public CompletableFuture<CheckResult> checkIp(String ip, String playerName) {
        return checkIp(ip, playerName, false);
    }

    public CompletableFuture<CheckResult> checkIp(String ip, String playerName, boolean bypass) {
        return CompletableFuture.supplyAsync(() -> checkIpNow(ip, playerName, bypass), executor);
    }

    private CheckResult checkIpNow(String ip, String playerName, boolean bypass) {
        GeoConfig current = config;
        if (bypass) {
            logger.info("Bypass granted to {} ({})", playerName, ip);
            return new CheckResult(true, null, null);
        }
        if (!NetworkUtils.isValidPublicIp(ip)) {
            return new CheckResult(true, null, null);
        }

        GeoResponse cached = cache.get(ip, current.cacheTtlDays);
        if (cached != null) {
            CheckResult result = evaluate(cached);
            logResultToDiscord(cached, result, playerName);
            return result;
        }

        GeoResponse response = fetchFromGateway(ip);
        if (response == null && current.directFallbackEnabled) {
            response = fetchFromFallback(ip);
        }
        if (response == null) {
            logger.warn("Lookup failed for {} ({})", playerName, ip);
            return lookupFailureResult(current);
        }

        cache.put(ip, response, current.maxCacheEntries);
        CheckResult result = evaluate(response);
        logResultToDiscord(response, result, playerName);
        return result;
    }

    private CheckResult lookupFailureResult(GeoConfig current) {
        if (current.blockOnLookupFailure) {
            return new CheckResult(false, current.kickMessageLookupFailure, null);
        }
        return new CheckResult(true, null, null);
    }

    private GeoResponse fetchFromGateway(String ip) {
        GeoConfig current = config;
        try {
            String url = buildLookupUrl(current.gatewayUrl, ip);
            HttpURLConnection conn = openConnection(url, "GET", current);
            if (!current.gatewayToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + current.gatewayToken);
                conn.setRequestProperty("X-GeoRestrict-Token", current.gatewayToken);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                closeQuietly(conn.getErrorStream());
                logger.warn("Gateway HTTP {} for {}", code, ip);
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                JsonElement el = JsonParser.parseReader(r);
                if (!el.isJsonObject()) return null;
                JsonObject json = el.getAsJsonObject();
                if (json.has("error") && !json.get("error").isJsonNull()) {
                    logger.warn("Gateway error for {}: {}", ip, json.get("error").getAsString());
                    return null;
                }
                GeoResponse response = gson.fromJson(json, GeoResponse.class);
                normalizeResponse(response, ip);
                return response;
            }
        } catch (Exception e) {
            logger.warn("Gateway request failed for {}: {}", ip, e.getMessage());
            return null;
        }
    }

    private GeoResponse fetchFromFallback(String ip) {
        GeoConfig current = config;
        try {
            String url = String.format(FALLBACK_URL, URLEncoder.encode(ip, StandardCharsets.UTF_8));
            HttpURLConnection conn = openConnection(url, "GET", current);
            int code = conn.getResponseCode();
            if (code != 200) {
                closeQuietly(conn.getErrorStream());
                logger.warn("Fallback HTTP {} for {}", code, ip);
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                if ("fail".equals(optString(json, "status", null))) {
                    logger.warn("Fallback rejected {}: {}", ip, optString(json, "message", null));
                    return null;
                }
                GeoResponse response = new GeoResponse();
                response.ip = optString(json, "query", ip);
                response.countryCode = optString(json, "countryCode", null);
                response.countryName = optString(json, "country", null);
                if (json.has("as") && !json.get("as").isJsonNull()) {
                    String[] parts = json.get("as").getAsString().split(" ", 2);
                    response.asn = parts[0];
                    response.asName = parts.length > 1 ? parts[1] : optString(json, "isp", null);
                } else {
                    response.asName = optString(json, "isp", null);
                }
                response.isProxy = optBoolean(json, "proxy");
                response.isHosting = optBoolean(json, "hosting");
                response.isMobile = optBoolean(json, "mobile");
                response.isVpn = response.isProxy;
                normalizeResponse(response, ip);
                return response;
            }
        } catch (Exception e) {
            logger.warn("Fallback request failed for {}: {}", ip, e.getMessage());
            return null;
        }
    }

    private CheckResult evaluate(GeoResponse info) {
        GeoConfig current = config;
        if (info == null) return new CheckResult(true, null, null);

        if (current.countryMode != GeoConfig.RestrictionMode.DISABLED) {
            String country = normalizeCode(info.countryCode);
            boolean listed = current.countries.contains(country);
            if (current.countryMode == GeoConfig.RestrictionMode.ALLOWLIST && !listed) {
                logger.info("Blocked {} (country {} not allowlisted)", info.ip, country);
                return new CheckResult(false, current.kickMessageCountry, info);
            }
            if (current.countryMode == GeoConfig.RestrictionMode.BLOCKLIST && listed) {
                logger.info("Blocked {} (country {} on blocklist)", info.ip, country);
                return new CheckResult(false, current.kickMessageCountry, info);
            }
        }

        if (current.asnMode != GeoConfig.RestrictionMode.DISABLED && !current.asns.isEmpty()) {
            String asn = normalizeCode(info.asn);
            boolean listed = current.asns.contains(asn);
            if (current.asnMode == GeoConfig.RestrictionMode.ALLOWLIST && !listed) {
                logger.info("Blocked {} (ASN {} not allowlisted)", info.ip, asn);
                return new CheckResult(false, current.kickMessageAsn, info);
            }
            if (current.asnMode == GeoConfig.RestrictionMode.BLOCKLIST && listed) {
                logger.info("Blocked {} (ASN {} on blocklist)", info.ip, asn);
                return new CheckResult(false, current.kickMessageAsn, info);
            }
        }

        if (current.vpnCheckEnabled) {
            if (info.isVpn || info.isHosting || info.isProxy) {
                logger.info("Blocked {} (vpn/hosting/proxy flag)", info.ip);
                return new CheckResult(false, current.kickMessageVpn, info);
            }
            if (info.asName != null) {
                String asName = info.asName.toLowerCase(Locale.ROOT);
                for (String badWord : current.vpnKeywords) {
                    if (asName.contains(badWord.toLowerCase(Locale.ROOT))) {
                        logger.info("Blocked {} (ISP matched '{}')", info.ip, badWord);
                        return new CheckResult(false, current.kickMessageVpn, info);
                    }
                }
            }
        }

        return new CheckResult(true, null, info);
    }

    private HttpURLConnection openConnection(String urlString, String method, GeoConfig current) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(current.connectionTimeoutMs);
        conn.setReadTimeout(current.connectionTimeoutMs);
        conn.setRequestProperty("User-Agent", PluginInfo.USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private String buildLookupUrl(String baseUrl, String ip) {
        String trimmed = isBlank(baseUrl) ? GeoConfigConstants.DEFAULT_GATEWAY_URL : baseUrl.trim();
        char sep = trimmed.contains("?") ? (trimmed.endsWith("?") || trimmed.endsWith("&") ? ' ' : '&') : '?';
        return trimmed + sep + "ip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8);
    }

    private void normalizeResponse(GeoResponse r, String requestedIp) {
        if (r == null) return;
        if (isBlank(r.ip)) r.ip = requestedIp;
        r.countryCode = normalizeCode(r.countryCode);
        r.asn = normalizeCode(r.asn);
    }

    private String normalizeCode(String v) {
        return v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
    }

    private static String optString(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsString();
    }

    private static boolean optBoolean(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) return false;
        try { return json.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }

    private void logResultToDiscord(GeoResponse info, CheckResult result, String playerName) {
        GeoConfig current = config;
        if (result.allowed && !current.discord.logAllowed) return;
        if (!result.allowed && !current.discord.logDenied) return;
        logToDiscord(info, result.allowed ? "Allowed" : "Blocked",
            result.allowed ? "Connection allowed." : result.reason, playerName);
    }

    private void logToDiscord(GeoResponse info, String status, String reason, String playerName) {
        GeoConfig current = config;
        if (isBlank(current.discord.webhook)) return;

        executor.execute(() -> {
            try {
                String ip = current.discord.maskIp ? maskIp(info.ip) : info.ip;

                JsonArray fieldsArray = new JsonArray();
                for (GeoConfig.EmbedField f : current.discord.fields) {
                    String value = f.value
                        .replace("%player%", nullSafe(playerName, "Unknown"))
                        .replace("%status%", status)
                        .replace("%ip%", nullSafe(ip, "Unknown"))
                        .replace("%country%", nullSafe(info.countryCode, "Unknown"))
                        .replace("%asn%", nullSafe(info.asn, "Unknown"))
                        .replace("%isp%", nullSafe(info.asName, "Unknown"))
                        .replace("%reason%", reason == null ? "" : reason);

                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", f.name);
                    obj.addProperty("value", value);
                    obj.addProperty("inline", f.inline);
                    fieldsArray.add(obj);
                }

                JsonObject embed = new JsonObject();
                embed.addProperty("title", current.discord.title);
                embed.addProperty("color",
                    "Allowed".equals(status) ? current.discord.colorAllowed : current.discord.colorDenied);
                embed.add("fields", fieldsArray);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject payload = new JsonObject();
                payload.add("embeds", embeds);

                String body = gson.toJson(payload);
                HttpURLConnection conn = openConnection(current.discord.webhook, "POST", current);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                closeQuietly(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (code < 200 || code >= 300) {
                    logger.warn("Discord webhook returned HTTP {}", code);
                }
            } catch (Exception e) {
                logger.warn("Discord webhook delivery failed: {}", e.getMessage());
            }
        });
    }

    private void closeQuietly(InputStream s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }

    private String maskIp(String ip) {
        if (ip == null) return "Unknown";
        if (ip.contains(".") && ip.split("\\.").length == 4) {
            String[] p = ip.split("\\.");
            return p[0] + "." + p[1] + ".x.x";
        }
        if (ip.contains(":")) {
            return ip.length() <= 9 ? ip + "..." : ip.substring(0, 9) + "...";
        }
        return "Masked";
    }

    private static String nullSafe(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static class LookupThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "GeoRestrict-Lookup-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    public static class CheckResult {
        public final boolean allowed;
        public final String reason;
        public final GeoResponse info;

        public CheckResult(boolean allowed, String reason, GeoResponse info) {
            this.allowed = allowed;
            this.reason = reason;
            this.info = info;
        }
    }
}
