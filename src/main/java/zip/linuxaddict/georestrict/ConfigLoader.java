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

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.BeanAccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConfigLoader {

    private static final Pattern RETIRED_DIRECT_FALLBACK = Pattern.compile(
        "(?m)^directFallbackEnabled[ \\t]*:[^\\r\\n]*(?:\\r?\\n|$)");

    private ConfigLoader() {}

    public static GeoConfig load(File file) {
        if (!file.exists()) {
            saveDefault(file);
        }

        GeoConfig config;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String sanitized = RETIRED_DIRECT_FALLBACK.matcher(content).replaceAll("");
            if (!content.equals(sanitized)) {
                writeAtomically(file, sanitized);
                System.out.println("[GeoRestrict] Removed retired directFallbackEnabled setting.");
            }
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(2_000_000);
            Yaml yaml = new Yaml(new Constructor(GeoConfig.class, options));
            yaml.setBeanAccess(BeanAccess.FIELD);
            config = yaml.load(sanitized);
        } catch (Exception e) {
            System.err.println("[GeoRestrict] Failed to read " + file
                + " (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            System.err.println("[GeoRestrict] Falling back to defaults. Check the file for syntax errors.");
            config = new GeoConfig();
        }
        if (config == null) {
            config = new GeoConfig();
        }

        normalize(config);
        ensureMigrated(file, config);
        return config;
    }

    private static void normalize(GeoConfig c) {
        if (c.configVersion <= 0) c.configVersion = GeoConfigConstants.CURRENT_VERSION;
        c.cacheTtlDays = clamp(c.cacheTtlDays, 1, 365);
        c.connectionTimeoutMs = clamp(c.connectionTimeoutMs, 500, 30_000);
        c.lookupThreads = clamp(c.lookupThreads, 1, 32);
        c.maxCacheEntries = clamp(c.maxCacheEntries, 0, 10_000_000);

        c.gatewayUrl = isBlank(c.gatewayUrl) ? GeoConfigConstants.DEFAULT_GATEWAY_URL : c.gatewayUrl.trim();
        if (!c.gatewayUrl.startsWith("https://") && !c.gatewayUrl.startsWith("http://")) {
            c.gatewayUrl = "https://" + c.gatewayUrl;
        }
        c.gatewayToken = c.gatewayToken == null ? "" : c.gatewayToken.trim();

        if (c.countryMode == null) c.countryMode = GeoConfig.RestrictionMode.BLOCKLIST;
        if (c.asnMode == null) c.asnMode = GeoConfig.RestrictionMode.DISABLED;
        if (c.asnMode != GeoConfig.RestrictionMode.DISABLED && (c.asns == null || c.asns.isEmpty())) {
            c.asnMode = GeoConfig.RestrictionMode.DISABLED;
        }

        c.countries = normalizeList(c.countries, true);
        c.asns = normalizeList(c.asns, true);
        c.vpnKeywords = normalizeList(c.vpnKeywords, false);

        if (isBlank(c.kickMessageCountry)) c.kickMessageCountry = "Your country is not allowed on this server.";
        if (isBlank(c.kickMessageAsn)) c.kickMessageAsn = "Your ISP/ASN is not allowed on this server.";
        if (isBlank(c.kickMessageVpn)) c.kickMessageVpn = "VPN or proxy connections are not allowed.";
        if (isBlank(c.kickMessageLookupFailure)) c.kickMessageLookupFailure = "Geo verification is temporarily unavailable.";

        if (c.discord == null) c.discord = new GeoConfig.DiscordSettings();
        if (c.discord.webhook == null) c.discord.webhook = "";
        else c.discord.webhook = c.discord.webhook.trim();
        if (isBlank(c.discord.title)) c.discord.title = "GeoRestrict";
        if (c.discord.fields == null || c.discord.fields.isEmpty()) {
            c.discord.fields = new GeoConfig().discord.fields;
        } else {
            List<GeoConfig.EmbedField> cleaned = new ArrayList<>();
            for (GeoConfig.EmbedField f : c.discord.fields) {
                if (f == null || isBlank(f.name)) continue;
                if (f.value == null) f.value = "";
                cleaned.add(f);
            }
            if (cleaned.isEmpty()) cleaned = new GeoConfig().discord.fields;
            c.discord.fields = cleaned;
        }
    }

    private static void ensureMigrated(File file, GeoConfig config) {
        if (config.configVersion >= GeoConfigConstants.CURRENT_VERSION) {
            return;
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> yamlMap = new LinkedHashMap<>();
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(content);
            if (loaded instanceof Map) {
                yamlMap.putAll((Map<String, Object>) loaded);
            }
            Map<String, Object> defaults = yaml.load(GeoConfigConstants.DEFAULT_CONFIG_YAML);
            mergeDefaults(yamlMap, defaults);
            yamlMap.put("configVersion", GeoConfigConstants.CURRENT_VERSION);
            config.configVersion = GeoConfigConstants.CURRENT_VERSION;

            StringBuilder out = new StringBuilder("# GeoRestrict configuration (migrated to v")
                .append(GeoConfigConstants.CURRENT_VERSION).append(")\n");
            for (Map.Entry<String, Object> e : yamlMap.entrySet()) {
                dumpYaml(out, e.getKey(), e.getValue(), 0);
            }
            writeAtomically(file, out.toString());
            System.out.println("[GeoRestrict] Migrated config to v" + GeoConfigConstants.CURRENT_VERSION);
        } catch (IOException e) {
            System.err.println("[GeoRestrict] Migration failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeDefaults(Map<String, Object> target, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                target.put(e.getKey(), e.getValue());
            } else if (e.getValue() instanceof Map && target.get(e.getKey()) instanceof Map) {
                mergeDefaults((Map<String, Object>) target.get(e.getKey()), (Map<String, Object>) e.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void dumpYaml(StringBuilder sb, String key, Object value, int indent) {
        String pad = "  ".repeat(indent);
        if (value instanceof Map) {
            sb.append(pad).append(key).append(":\n");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                dumpYaml(sb, e.getKey(), e.getValue(), indent + 1);
            }
        } else if (value instanceof List) {
            sb.append(pad).append(key).append(":\n");
            for (Object item : (List<Object>) value) {
                sb.append(pad).append("  - ");
                if (item instanceof Map) {
                    sb.append("{ ");
                    boolean first = true;
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) item).entrySet()) {
                        if (!first) sb.append(", ");
                        sb.append(e.getKey()).append(": ").append(formatScalar(e.getValue()));
                        first = false;
                    }
                    sb.append(" }\n");
                } else {
                    sb.append(formatScalar(item)).append("\n");
                }
            }
        } else {
            sb.append(pad).append(key).append(": ").append(formatScalar(value)).append("\n");
        }
    }

    private static String formatScalar(Object v) {
        if (v == null) return "\"\"";
        String s = v.toString();
        if (s.matches("[A-Za-z0-9_./:-]+")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void saveDefault(File file) {
        if (file.exists()) return;
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try {
            writeAtomically(file, GeoConfigConstants.DEFAULT_CONFIG_YAML);
        } catch (IOException e) {
            System.err.println("[GeoRestrict] Failed to write default config: " + e.getMessage());
        }
    }

    private static void writeAtomically(File file, String content) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static List<String> normalizeList(List<String> values, boolean uppercase) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (t.isEmpty()) continue;
            out.add(uppercase ? t.toUpperCase(Locale.ROOT) : t);
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
