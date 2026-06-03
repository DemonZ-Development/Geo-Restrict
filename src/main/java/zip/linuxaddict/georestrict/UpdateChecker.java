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
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * Silent Modrinth update checker.
 * Queries the Modrinth API for the latest featured version of the project
 * and compares it against the currently running version.
 *
 * On ANY failure (network, parse, etc.) the checker stays completely silent â€”
 * no errors are logged and the future resolves to null.
 */
public class UpdateChecker {

    private static final String MODRINTH_API =
            "https://api.modrinth.com/v2/project/%s/version?loaders=[\"paper\",\"bukkit\",\"spigot\"]&featured=true";

    private final String currentVersion;
    private final String projectSlug;
    private final Logger logger;
    private final Gson gson = new Gson();

    private volatile String latestVersion;

    public UpdateChecker(String currentVersion, String projectSlug, Logger logger) {
        this.currentVersion = currentVersion;
        this.projectSlug = projectSlug;
        this.logger = logger;
    }

    /**
     * Asynchronously checks Modrinth for a newer version.
     *
     * @return a future that resolves to the latest version string if an update
     *         is available, or null if the current version is up-to-date or
     *         if the check failed for any reason.
     */
    public CompletableFuture<String> checkForUpdate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = String.format(MODRINTH_API, projectSlug);
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "GeoRestrict/" + currentVersion);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    return null;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JsonArray versions = gson.fromJson(sb.toString(), JsonArray.class);
                if (versions == null || versions.isEmpty()) {
                    return null;
                }

                JsonObject first = versions.get(0).getAsJsonObject();
                JsonElement versionElement = first.get("version_number");
                if (versionElement == null) {
                    return null;
                }

                String remote = versionElement.getAsString();
                latestVersion = remote;

                if (isNewer(remote, currentVersion)) {
                    return remote;
                }
                return null;
            } catch (Exception e) {
                // Completely silent â€” never log update-check failures
                return null;
            }
        });
    }

    /**
     * Returns the latest version string from the last successful check,
     * or null if no check has been performed or it failed.
     */
    public String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Returns true if a newer version is available based on the last check.
     */
    public boolean isUpdateAvailable() {
        return latestVersion != null && isNewer(latestVersion, currentVersion);
    }

    // ------------------------------------------------------------- Helpers

    /**
     * Simple semantic-version comparison.
     * Returns true if {@code remote} is strictly newer than {@code current}.
     * Falls back to lexicographic comparison if versions don't parse cleanly.
     */
    private static boolean isNewer(String remote, String current) {
        try {
            int[] r = parseVersion(remote);
            int[] c = parseVersion(current);
            for (int i = 0; i < Math.max(r.length, c.length); i++) {
                int rv = i < r.length ? r[i] : 0;
                int cv = i < c.length ? c[i] : 0;
                if (rv > cv) return true;
                if (rv < cv) return false;
            }
            return false; // Equal
        } catch (NumberFormatException e) {
            // Fallback: lexicographic
            return remote.compareTo(current) > 0;
        }
    }

    private static int[] parseVersion(String version) {
        // Strip leading 'v' if present
        String v = version.startsWith("v") ? version.substring(1) : version;
        // Strip any pre-release suffix (e.g. "-beta.1")
        int dash = v.indexOf('-');
        if (dash != -1) {
            v = v.substring(0, dash);
        }
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            nums[i] = Integer.parseInt(parts[i]);
        }
        return nums;
    }
}

