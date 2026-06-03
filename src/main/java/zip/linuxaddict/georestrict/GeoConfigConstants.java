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

import java.util.Arrays;
import java.util.List;

public final class GeoConfigConstants {

    public static final int CURRENT_VERSION = 4;
    public static final String DEFAULT_GATEWAY_URL = "https://geoprotect.demonzdev.workers.dev/";
    public static final String DEFAULT_GATEWAY_TOKEN_ENV = "GEO_TOKEN";

    public static final List<String> DEFAULT_VPN_KEYWORDS = Arrays.asList(
        "vpn", "virtual private network",
        "proxy", "proxies", "proxy server",
        "tor ", "tor exit", "exit node",
        "shadowsocks", "wireguard", "openvpn", "ipsec", "softether",
        "socks", "socks5",
        "anonymizer", "anonymouse", "anonymous proxy", "anonymous vpn",
        "nordvpn", "expressvpn", "surfshark", "protonvpn", "proton vpn",
        "mullvad", "private internet access", "privateinternetaccess",
        "pia network", "cyberghost", "windscribe", "ipvanish", "vyprvpn",
        "hotspot shield", "tunnelbear", "zenmate", "hide.me", "perfect privacy",
        "airvpn", "ivpn", "trust.zone", "purevpn", "safervpn", "astrill",
        "goose vpn", "hidemyass", "tunnelbear", "cryptostorm", "vpngate",
        "amazon aws", "amazon web services", "google cloud", "google cloud platform",
        "microsoft azure", "digitalocean", "linode", "vultr", "hetzner",
        "ovh sas", "scaleway", "contabo", "hostinger", "godaddy", "namecheap",
        "bluehost", "dreamhost", "hostgator", "ionos", "liquidweb", "interserver",
        "a2hosting", "siteground", "inmotion", "nexcess", "wpengine", "kinsta",
        "kamatera", "phoenixnap", "cherry servers", "frantech", "incognet",
        "psychz networks", "psychz", "buyvm", "choopa", "ramnode", "serverhub",
        "quadranet", "m247 ltd", "rackspace", "cloudflare", "fastly", "akamai",
        "google llc", "amazon.com", "microsoft corp"
    );

    public static final List<String> DEFAULT_COUNTRIES = Arrays.asList("CN", "RU", "KP", "IR");

    public static final String DEFAULT_CONFIG_YAML =
        "# ============================================\n" +
        "#        GeoRestrict Configuration\n" +
        "#     (c) Demonz Development 2026\n" +
        "#   https://demonzdevelopment.online/\n" +
        "# ============================================\n" +
        "# Bump configVersion only when this file format changes.\n" +
        "configVersion: " + CURRENT_VERSION + "\n" +
        "\n" +
        "# ----- Lookup gateway -----\n" +
        "# The plugin sends public IPs to this Worker, which queries the\n" +
        "# configured geolocation providers. Set GATEWAY_TOKENS in the\n" +
        "# Worker to require a shared secret; mirror it in gatewayToken.\n" +
        "gatewayUrl: \"" + DEFAULT_GATEWAY_URL + "\"\n" +
        "gatewayToken: \"\"\n" +
        "# When the Worker is unreachable, fall back to a direct provider.\n" +
        "directFallbackEnabled: false\n" +
        "# If every lookup fails, block the player. Recommended: true.\n" +
        "blockOnLookupFailure: true\n" +
        "# Background threads for HTTP lookups and webhook delivery.\n" +
        "lookupThreads: 4\n" +
        "# Hard cap on cache size; oldest entries are dropped past this.\n" +
        "maxCacheEntries: 100000\n" +
        "\n" +
        "# ----- Country -----\n" +
        "countryMode: BLOCKLIST    # ALLOWLIST | BLOCKLIST | DISABLED\n" +
        "countries:\n" +
        "  - CN\n" +
        "  - RU\n" +
        "  - KP\n" +
        "  - IR\n" +
        "\n" +
        "# ----- ASN / ISP -----\n" +
        "asnMode: DISABLED         # ALLOWLIST | BLOCKLIST | DISABLED\n" +
        "asns: []\n" +
        "\n" +
        "# ----- VPN / Hosting / Proxy -----\n" +
        "vpnCheckEnabled: true\n" +
        "vpnKeywords:\n" +
        keywordListAsYaml() +
        "\n" +
        "# ----- Kick messages -----\n" +
        "kickMessageCountry: \"Your country is not allowed on this server.\"\n" +
        "kickMessageAsn: \"Your ISP/ASN is not allowed on this server.\"\n" +
        "kickMessageVpn: \"VPN or proxy connections are not allowed.\"\n" +
        "kickMessageLookupFailure: \"Geo verification is temporarily unavailable. Please try again later.\"\n" +
        "\n" +
        "# ----- Cache & network -----\n" +
        "cacheTtlDays: 30\n" +
        "updateCheck: true\n" +
        "connectionTimeoutMs: 3000\n" +
        "\n" +
        "# ----- Discord webhook -----\n" +
        "discord:\n" +
        "  webhook: \"\"\n" +
        "  maskIp: true\n" +
        "  logAllowed: false\n" +
        "  logDenied: true\n" +
        "  title: \"GeoRestrict\"\n" +
        "  colorAllowed: 65280\n" +
        "  colorDenied: 16711680\n" +
        "  fields:\n" +
        "    - { name: \"Player\",  value: \"%player%\",  inline: true }\n" +
        "    - { name: \"Status\",  value: \"%status%\",  inline: true }\n" +
        "    - { name: \"IP\",      value: \"%ip%\",      inline: true }\n" +
        "    - { name: \"Country\", value: \"%country%\", inline: true }\n" +
        "    - { name: \"ASN\",     value: \"%asn%\",     inline: true }\n" +
        "    - { name: \"ISP\",     value: \"%isp%\",     inline: false }\n" +
        "    - { name: \"Reason\",  value: \"%reason%\",  inline: false }\n";

    private static String keywordListAsYaml() {
        StringBuilder sb = new StringBuilder();
        for (String k : DEFAULT_VPN_KEYWORDS) {
            sb.append("  - \"").append(k).append("\"\n");
        }
        return sb.toString();
    }

    private GeoConfigConstants() {}
}
