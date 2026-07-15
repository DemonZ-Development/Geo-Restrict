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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    private static final String MODRINTH_API =
        "https://api.modrinth.com/v2/project/%s/version?loaders=[\"paper\",\"bukkit\",\"spigot\"]";

    private final String currentVersion;
    private final String projectSlug;
    private final Gson gson = new Gson();

    private volatile String latestVersion;

    public UpdateChecker(String currentVersion, String projectSlug) {
        this.currentVersion = currentVersion;
        this.projectSlug = projectSlug;
    }

    public CompletableFuture<String> checkForUpdate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = String.format(MODRINTH_API, projectSlug);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", PluginInfo.USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() != 200) return null;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                }

                JsonArray versions = gson.fromJson(sb.toString(), JsonArray.class);
                if (versions == null || versions.isEmpty()) return null;

                String remote = pickNewest(versions);
                if (remote == null) return null;
                latestVersion = remote;
                return isNewer(remote, currentVersion) ? remote : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    private String pickNewest(JsonArray versions) {
        String newest = null;
        int[] newestNums = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            if (!v.has("version_number") || v.get("version_number").isJsonNull()) continue;
            String num = v.get("version_number").getAsString();
            int[] nums = parseVersion(num);
            if (nums == null) continue;
            if (newest == null || compare(nums, newestNums) > 0) {
                newest = num;
                newestNums = nums;
            }
        }
        return newest;
    }

    public String getLatestVersion() { return latestVersion; }

    public boolean isUpdateAvailable() {
        return latestVersion != null && isNewer(latestVersion, currentVersion);
    }

    private static boolean isNewer(String remote, String current) {
        int[] r = parseVersion(remote);
        int[] c = parseVersion(current);
        if (r != null && c != null) return compare(r, c) > 0;
        return numericAwareCompare(remote, current) > 0;
    }

    private static int compare(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }


    private static int[] parseVersion(String version) {
        if (version == null) return null;
        String v = version.trim();
        if (v.isEmpty()) return null;
        if (v.charAt(0) == 'v' || v.charAt(0) == 'V') v = v.substring(1);
        boolean preRelease = false;
        int dash = v.indexOf('-');
        if (dash != -1) {
            preRelease = true;
            v = v.substring(0, dash);
        }
        if (v.isEmpty()) return null;
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length + (preRelease ? 1 : 0)];
        for (int i = 0; i < parts.length; i++) {
            try {
                nums[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (preRelease) nums[nums.length - 1] = -1;
        return nums;
    }

    private static int numericAwareCompare(String a, String b) {
        String[] ap = a.split("\\.");
        String[] bp = b.split("\\.");
        int len = Math.max(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            String as = i < ap.length ? ap[i] : "0";
            String bs = i < bp.length ? bp[i] : "0";
            Integer ai = tryParse(as);
            Integer bi = tryParse(bs);
            if (ai != null && bi != null) {
                if (!ai.equals(bi)) return ai.compareTo(bi);
            } else {
                int c = as.compareTo(bs);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    private static Integer tryParse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
