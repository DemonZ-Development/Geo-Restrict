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
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigLoader {

    /** Current config schema version. Bump this when adding new fields. */
    private static final int CURRENT_CONFIG_VERSION = 2;

    public static GeoConfig load(File file) {
        if (!file.exists()) {
            saveDefault(file);
        }

        try (FileInputStream inputStream = new FileInputStream(file)) {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(GeoConfig.class, options));
            yaml.setBeanAccess(BeanAccess.FIELD);
            GeoConfig config = yaml.load(inputStream);
            if (config == null) {
                config = new GeoConfig();
            }

            // Migrate config if needed
            if (config.configVersion < CURRENT_CONFIG_VERSION) {
                migrateConfig(file, config);
            }

            return config;
        } catch (IOException e) {
            e.printStackTrace();
            return new GeoConfig(); 
        }
    }

    /**
     * Appends any missing fields introduced in newer config versions
     * and bumps the configVersion marker in the YAML file.
     */
    private static void migrateConfig(File file, GeoConfig config) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            StringBuilder additions = new StringBuilder();

            // Fields added in config version 2
            if (config.configVersion < 2) {
                additions.append("\n# --- Added by GeoRestrict auto-migration ---\n");
                if (!content.contains("configVersion:")) {
                    additions.append("configVersion: ").append(CURRENT_CONFIG_VERSION).append("\n");
                }
                if (!content.contains("cacheTtlDays:")) {
                    additions.append("cacheTtlDays: 30\n");
                }
                if (!content.contains("updateCheck:")) {
                    additions.append("updateCheck: true\n");
                }
                if (!content.contains("connectionTimeoutMs:")) {
                    additions.append("connectionTimeoutMs: 3000\n");
                }
            }

            if (additions.length() > 0) {
                // Update in-memory version
                config.configVersion = CURRENT_CONFIG_VERSION;

                // If configVersion line already exists, update it; otherwise it was appended above
                if (content.contains("configVersion:")) {
                    content = content.replaceFirst(
                        "configVersion:\\s*\\d+",
                        "configVersion: " + CURRENT_CONFIG_VERSION
                    );
                }

                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(content);
                    writer.write(additions.toString());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveDefault(File file) {
        if (file.exists()) return;
        
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        
        String yamlContent = 
            "# ============================================\n" +
            "#        GeoRestrict Configuration\n" +
            "#    Developed by Demonz Development\n" +
            "#  https://demonzdevelopment.online/\n" +
            "# ============================================\n" +
            "\n" +
            "# Config version â€” do not edit manually\n" +
            "configVersion: 2\n" +
            "\n" +
            "# Modes: ALLOWLIST, BLOCKLIST, DISABLED\n" +
            "\n" +
            "# Country Restriction Settings\n" +
            "# If mode is ALLOWLIST, only countries in the list are allowed.\n" +
            "# If mode is BLOCKLIST, countries in the list are blocked.\n" +
            "countryMode: BLOCKLIST\n" +
            "countries:\n" +
            "  - CN\n" +
            "  - RU\n" +
            "  - KP\n" +
            "\n" +
            "# ASN Restriction Settings\n" +
            "# Same logic as countries.\n" +
            "asnMode: DISABLED\n" +
            "asns:\n" +
            "  - AS12345\n" +
            "\n" +
            "# VPN / Proxy Detection Settings\n" +
            "vpnCheckEnabled: true\n" +
            "vpnKeywords:\n" +
            "  - vpn\n" +
            "  - proxy\n" +
            "  - tunnel\n" +
            "  - socks\n" +
            "  - shadowsocks\n" +
            "  - wireguard\n" +
            "  - openvpn\n" +
            "  - hosting\n" +
            "  - datacenter\n" +
            "  - \"data center\"\n" +
            "  - colocation\n" +
            "  - colo\n" +
            "  - cloud\n" +
            "  - vps\n" +
            "  - \"virtual private server\"\n" +
            "  - \"dedicated server\"\n" +
            "  - amazon\n" +
            "  - aws\n" +
            "  - azure\n" +
            "  - \"google cloud\"\n" +
            "  - gcp\n" +
            "  - digitalocean\n" +
            "  - linode\n" +
            "  - vultr\n" +
            "  - hetzner\n" +
            "  - ovh\n" +
            "  - scaleway\n" +
            "  - contabo\n" +
            "  - hostinger\n" +
            "  - godaddy\n" +
            "  - namecheap\n" +
            "  - bluehost\n" +
            "  - dreamhost\n" +
            "  - hostgator\n" +
            "  - nordvpn\n" +
            "  - expressvpn\n" +
            "  - surfshark\n" +
            "  - protonvpn\n" +
            "  - mullvad\n" +
            "  - privateinternetaccess\n" +
            "  - pia\n" +
            "  - cyberghost\n" +
            "  - relay\n" +
            "  - tor\n" +
            "  - \"exit node\"\n" +
            "  - \"residential proxy\"\n" +
            "  - \"mobile proxy\"\n" +
            "  - rackspace\n" +
            "  - cloudflare\n" +
            "  - fastly\n" +
            "  - akamai\n" +
            "  - ionos\n" +
            "  - liquidweb\n" +
            "  - interserver\n" +
            "  - a2hosting\n" +
            "  - siteground\n" +
            "  - inmotion\n" +
            "  - nexcess\n" +
            "  - wpengine\n" +
            "  - kinsta\n" +
            "  - serverspace\n" +
            "  - kamatera\n" +
            "  - \"atlantic.net\"\n" +
            "  - phoenixnap\n" +
            "  - \"cherry servers\"\n" +
            "  - serverhub\n" +
            "  - quadranet\n" +
            "  - choopa\n" +
            "  - ramnode\n" +
            "  - buyvm\n" +
            "  - frantech\n" +
            "  - incognet\n" +
            "  - m247\n" +
            "  - psychz\n" +
            "  - coresite\n" +
            "  - equinix\n" +
            "  - \"ip address\"\n" +
            "  - anonymous\n" +
            "  - \"vpn server\"\n" +
            "  - \"proxy server\"\n" +
            "  - \"hide ip\"\n" +
            "  - \"mask ip\"\n" +
            "  - \"vpn tunnel\"\n" +
            "  - \"encrypted connection\"\n" +
            "  - \"no-log\"\n" +
            "  - \"private network\"\n" +
            "  - \"secure connection\"\n" +
            "  - unblock\n" +
            "  - bypass\n" +
            "  - geolocation\n" +
            "  - \"ip masking\"\n" +
            "  - anonymizer\n" +
            "  - hidemyass\n" +
            "  - windscribe\n" +
            "  - ipvanish\n" +
            "  - vyprvpn\n" +
            "  - \"hotspot shield\"\n" +
            "  - tunnelbear\n" +
            "  - zenmate\n" +
            "  - \"hide.me\"\n" +
            "  - \"perfect privacy\"\n" +
            "  - airvpn\n" +
            "  - ivpn\n" +
            "  - \"trust.zone\"\n" +
            "  - purevpn\n" +
            "  - safervpn\n" +
            "  - astrill\n" +
            "  - \"goose vpn\"\n" +
            "\n" +
            "# Messages\n" +
            "kickMessageCountry: \"Your country is not allowed.\"\n" +
            "kickMessageASN: \"Your ISP/ASN is not allowed.\"\n" +
            "kickMessageVPN: \"VPN or Proxy detected.\"\n" +
            "\n" +
            "# Cache Settings\n" +
            "# How many days to keep cached geo lookups before re-querying\n" +
            "cacheTtlDays: 30\n" +
            "\n" +
            "# Update Checker\n" +
            "# Set to false to disable automatic update checks on startup\n" +
            "updateCheck: true\n" +
            "\n" +
            "# Connection timeout in milliseconds for geo API requests\n" +
            "connectionTimeoutMs: 3000\n" +
            "\n" +
            "# Discord Webhook Settings\n" +
            "discord:\n" +
            "  webhook: \"\"\n" +
            "  maskIp: true\n" +
            "  title: \"GeoRestrict\"\n" +
            "  colorAllowed: 65280\n" +
            "  colorDenied: 16711680\n" +
            "  fields:\n" +
            "    - name: \"Player\"\n" +
            "      value: \"%player%\"\n" +
            "      inline: true\n" +
            "    - name: \"Status\"\n" +
            "      value: \"%status%\"\n" +
            "      inline: true\n" +
            "    - name: \"IP\"\n" +
            "      value: \"%ip%\"\n" +
            "      inline: true\n" +
            "    - name: \"Country\"\n" +
            "      value: \"%country%\"\n" +
            "      inline: true\n" +
            "    - name: \"ASN\"\n" +
            "      value: \"%asn%\"\n" +
            "      inline: true\n" +
            "    - name: \"ISP\"\n" +
            "      value: \"%isp%\"\n" +
            "      inline: false\n" +
            "    - name: \"Reason\"\n" +
            "      value: \"%reason%\"\n" +
            "      inline: false\n";

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(yamlContent);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

