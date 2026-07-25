/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.floodgate;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection-based fail-safe helper for optional Geyser / Floodgate (Bedrock player) integration.
 */
public final class FloodgateHandler {
    private static Boolean available = null;

    private FloodgateHandler() {}

    /**
     * Checks if Floodgate API is present on the server classpath.
     */
    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * Checks if a player UUID corresponds to a Floodgate Bedrock player.
     *
     * @param uuid player UUID
     * @return true if player is a Bedrock player via Floodgate
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null || !isAvailable()) return false;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object apiInstance = getInstance.invoke(null);
            if (apiInstance == null) return false;

            Method isPlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            Object res = isPlayer.invoke(apiInstance, uuid);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Obtains Bedrock player info (XUID and Device OS) for a given player UUID if available.
     *
     * @param uuid player UUID
     * @return BedrockPlayerInfo object or null
     */
    public static BedrockPlayerInfo getBedrockInfo(UUID uuid) {
        if (uuid == null || !isAvailable()) return null;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object apiInstance = getInstance.invoke(null);
            if (apiInstance == null) return null;

            Method isPlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            Object res = isPlayer.invoke(apiInstance, uuid);
            if (!(res instanceof Boolean) || !Boolean.TRUE.equals(res)) return null;

            Method getPlayer = apiClass.getMethod("getPlayer", UUID.class);
            Object playerInstance = getPlayer.invoke(apiInstance, uuid);
            if (playerInstance == null) return null;

            Method getXuid = playerInstance.getClass().getMethod("getXuid");
            Object xuidObj = getXuid.invoke(playerInstance);
            String xuid = xuidObj != null ? xuidObj.toString() : "N/A";

            String deviceOs = "UNKNOWN";
            try {
                Method getDeviceOs = playerInstance.getClass().getMethod("getDeviceOs");
                Object osObj = getDeviceOs.invoke(playerInstance);
                if (osObj != null) {
                    deviceOs = osObj.toString();
                }
            } catch (Throwable ignored) {}

            return new BedrockPlayerInfo(xuid, deviceOs);
        } catch (Throwable t) {
            return null;
        }
    }

    public static class BedrockPlayerInfo {
        public final String xuid;
        public final String deviceOs;

        public BedrockPlayerInfo(String xuid, String deviceOs) {
            this.xuid = xuid;
            this.deviceOs = deviceOs;
        }
    }
}
