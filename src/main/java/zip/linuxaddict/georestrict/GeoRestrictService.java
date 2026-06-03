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
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class GeoRestrictService {
    // Placeholder â€” will be updated when the Cloudflare Worker is deployed
    private static final String GATEWAY_URL = "https://PLACEHOLDER.workers.dev/lookup";
    private static final String FALLBACK_URL = "http://ip-api.com/json/%s?fields=status,message,query,country,countryCode,isp,org,as,proxy,hosting,mobile";

    private GeoConfig config;
    private final Logger logger;
    private final GeoCache cache;
    private final Gson gson = new Gson();

    public GeoRestrictService(GeoConfig config, Logger logger, GeoCache cache) {
        this.config = config;
        this.logger = logger;
        this.cache = cache;
    }

    public void setConfig(GeoConfig config) {
        this.config = config;
    }

    /**
     * Check an IP with no bypass (backward compat).
     */
    public CompletableFuture<CheckResult> checkIp(String ip, String playerName) {
        return checkIp(ip, playerName, false);
    }

    /**
     * Check an IP with optional bypass flag.
     * @param bypass If true, the player has georestrict.bypass permission and is always allowed.
     */
    public CompletableFuture<CheckResult> checkIp(String ip, String playerName, boolean bypass) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 0. Bypass permission check
                if (bypass) {
                    logger.info("Bypassed geo check for {} ({})", playerName, ip);
                    return new CheckResult(true, null, null);
                }

                // 1. Private IP check
                if (NetworkUtils.isPrivateIp(ip)) {
                    return new CheckResult(true, null, null);
                }

                // 2. Cache check
                GeoResponse cached = cache.get(ip, config.cacheTtlDays);
                if (cached != null) {
                    CheckResult result = evaluate(cached);
                    if (result.allowed) {
                        logToDiscord(cached, "Allowed", "Connection allowed.", playerName);
                    } else {
                        logToDiscord(cached, "Blocked", result.reason, playerName);
                    }
                    return result;
                }

                // 3. Try gateway URL first
                GeoResponse response = fetchFromGateway(ip);

                // 4. If gateway fails, try fallback URL directly
                if (response == null) {
                    response = fetchFromFallback(ip);
                }

                if (response == null) {
                    logger.warn("Failed to fetch IP info for {} from both gateway and fallback", ip);
                    return new CheckResult(true, null, null); // Fail open
                }

                // 5. Store in cache
                cache.put(ip, response);

                // 6. Evaluate and return
                CheckResult result = evaluate(response);
                if (result.allowed) {
                    logToDiscord(response, "Allowed", "Connection allowed.", playerName);
                } else {
                    logToDiscord(response, "Blocked", result.reason, playerName);
                }
                return result;
            } catch (Exception e) {
                logger.error("Error checking IP " + ip, e);
                return new CheckResult(true, null, null); // Fail open
            }
        });
    }

    /**
     * Fetch geo information from the primary gateway URL.
     */
    private GeoResponse fetchFromGateway(String ip) {
        try {
            String urlString = GATEWAY_URL + "?ip=" + ip;
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.connectionTimeoutMs);
            conn.setReadTimeout(config.connectionTimeoutMs);

            if (conn.getResponseCode() != 200) {
                logger.warn("Gateway returned HTTP {} for IP {}", conn.getResponseCode(), ip);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            GeoResponse response = gson.fromJson(reader, GeoResponse.class);
            reader.close();
            return response;
        } catch (Exception e) {
            logger.warn("Gateway request failed for IP {}: {}", ip, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch geo information from the fallback ip-api.com URL.
     * Maps the ip-api.com response fields to GeoResponse.
     */
    private GeoResponse fetchFromFallback(String ip) {
        try {
            String urlString = String.format(FALLBACK_URL, ip);
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.connectionTimeoutMs);
            conn.setReadTimeout(config.connectionTimeoutMs);

            if (conn.getResponseCode() != 200) {
                logger.warn("Fallback API returned HTTP {} for IP {}", conn.getResponseCode(), ip);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            reader.close();

            // Check if ip-api returned an error
            if (json.has("status") && "fail".equals(json.get("status").getAsString())) {
                String message = json.has("message") ? json.get("message").getAsString() : "Unknown error";
                logger.warn("Fallback API returned failure for IP {}: {}", ip, message);
                return null;
            }

            // Map ip-api.com fields to GeoResponse
            GeoResponse response = new GeoResponse();
            response.ip = json.has("query") ? json.get("query").getAsString() : ip;
            response.countryCode = json.has("countryCode") ? json.get("countryCode").getAsString() : null;
            response.countryName = json.has("country") ? json.get("country").getAsString() : null;

            // Parse ASN from the "as" field (format: "AS12345 Organization Name")
            if (json.has("as") && !json.get("as").isJsonNull()) {
                String asField = json.get("as").getAsString();
                String[] asParts = asField.split(" ", 2);
                response.asn = asParts[0]; // e.g. "AS12345"
                response.asName = asParts.length > 1 ? asParts[1] : (json.has("isp") ? json.get("isp").getAsString() : null);
            } else {
                response.asName = json.has("isp") ? json.get("isp").getAsString() : null;
            }

            // ip-api.com provides proxy, hosting, mobile as booleans
            response.isProxy = json.has("proxy") && json.get("proxy").getAsBoolean();
            response.isHosting = json.has("hosting") && json.get("hosting").getAsBoolean();
            response.isMobile = json.has("mobile") && json.get("mobile").getAsBoolean();
            // ip-api.com doesn't distinguish VPN separately; proxy covers it
            response.isVpn = response.isProxy;

            return response;
        } catch (Exception e) {
            logger.warn("Fallback request failed for IP {}: {}", ip, e.getMessage());
            return null;
        }
    }

    private CheckResult evaluate(GeoResponse info) {
        if (info == null) return new CheckResult(true, null, null);

        // 1. Country Check
        if (config.countryMode != GeoConfig.RestrictionMode.DISABLED) {
            boolean listed = config.countries.contains(info.countryCode);

            if (config.countryMode == GeoConfig.RestrictionMode.ALLOWLIST) {
                if (!listed) {
                    logger.info("Blocked {} (Country: {}) - Not in allowed countries", info.ip, info.countryCode);
                    return new CheckResult(false, cleanMessage(config.kickMessageCountry), info);
                }
            } else if (config.countryMode == GeoConfig.RestrictionMode.BLOCKLIST) {
                if (listed) {
                    logger.info("Blocked {} (Country: {}) - In blocked countries", info.ip, info.countryCode);
                    return new CheckResult(false, cleanMessage(config.kickMessageCountry), info);
                }
            }
        }

        // 2. ASN Check
        if (config.asnMode != GeoConfig.RestrictionMode.DISABLED) {
            boolean listed = config.asns.contains(info.asn);

            if (config.asnMode == GeoConfig.RestrictionMode.ALLOWLIST) {
                if (!listed) {
                    logger.info("Blocked {} (ASN: {}) - Not in allowed ASNs", info.ip, info.asn);
                    return new CheckResult(false, cleanMessage(config.kickMessageASN), info);
                }
            } else if (config.asnMode == GeoConfig.RestrictionMode.BLOCKLIST) {
                if (listed) {
                    logger.info("Blocked {} (ASN: {}) - In blocked ASNs", info.ip, info.asn);
                    return new CheckResult(false, cleanMessage(config.kickMessageASN), info);
                }
            }
        }

        // 3. VPN / Proxy / Hosting Check
        if (config.vpnCheckEnabled) {
            // Primary check: boolean flags from GeoResponse
            if (info.isVpn || info.isHosting || info.isProxy) {
                logger.info("Blocked {} (VPN/Hosting/Proxy detected via flags)", info.ip);
                return new CheckResult(false, cleanMessage(config.kickMessageVPN), info);
            }

            // Secondary check: keyword matching on asName (for when fallback was used and booleans may be false)
            if (info.asName != null) {
                String ispLower = info.asName.toLowerCase(Locale.ROOT);
                for (String badWord : config.vpnKeywords) {
                    if (ispLower.contains(badWord.toLowerCase(Locale.ROOT))) {
                        logger.info("Blocked {} (ISP: {}) - Detected VPN/Hosting keyword: {}", info.ip, info.asName, badWord);
                        return new CheckResult(false, cleanMessage(config.kickMessageVPN), info);
                    }
                }
            }
        }

        return new CheckResult(true, null, info);
    }

    private String cleanMessage(String message) {
        if (message == null) return "";
        return message.replace("Connection rejected:", "").replace("Connection rejected", "").trim();
    }

    /**
     * Send a Discord webhook notification using Gson JsonObject to build the JSON payload,
     * preventing injection bugs with special characters in player names/ISP names.
     */
    private void logToDiscord(GeoResponse info, String status, String reason, String playerName) {
        if (config.discord.webhook == null || config.discord.webhook.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String ip = config.discord.maskIp ? maskIp(info.ip) : info.ip;

                // Build JSON using Gson JsonObject to prevent injection
                JsonArray fieldsArray = new JsonArray();
                for (GeoConfig.EmbedField field : config.discord.fields) {
                    String value = field.value
                        .replace("%player%", playerName != null ? playerName : "Unknown")
                        .replace("%status%", status)
                        .replace("%ip%", ip != null ? ip : "Unknown")
                        .replace("%country%", info.countryCode != null ? info.countryCode : "Unknown")
                        .replace("%asn%", info.asn != null ? info.asn : "Unknown")
                        .replace("%isp%", info.asName != null ? info.asName : "Unknown")
                        .replace("%reason%", reason != null ? reason : "");

                    JsonObject fieldObj = new JsonObject();
                    fieldObj.addProperty("name", field.name);
                    fieldObj.addProperty("value", value);
                    fieldObj.addProperty("inline", field.inline);
                    fieldsArray.add(fieldObj);
                }

                JsonObject embed = new JsonObject();
                embed.addProperty("title", config.discord.title);
                embed.addProperty("color", status.equals("Allowed") ? config.discord.colorAllowed : config.discord.colorDenied);
                embed.add("fields", fieldsArray);

                JsonArray embedsArray = new JsonArray();
                embedsArray.add(embed);

                JsonObject payload = new JsonObject();
                payload.add("embeds", embedsArray);

                String json = gson.toJson(payload);

                URL url = new URL(config.discord.webhook);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes("UTF-8"));
                }
                conn.getInputStream().close();
            } catch (Exception e) {
                logger.error("Failed to send Discord webhook", e);
            }
        });
    }

    private String maskIp(String ip) {
        if (ip == null) return "Unknown";
        if (ip.contains(".")) { // IPv4
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + ".x.x";
            }
        } else if (ip.contains(":")) { // IPv6
            // Simple mask for IPv6
            return ip.substring(0, Math.min(ip.length(), 9)) + "...";
        }
        return "Masked";
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

