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

public class GeoConfig {

    public int configVersion = 2;
    public int cacheTtlDays = 30;
    public boolean updateCheck = true;
    public int connectionTimeoutMs = 3000;


    public RestrictionMode countryMode = RestrictionMode.BLOCKLIST;
    public List<String> countries = Arrays.asList("CN", "RU", "KP");

    public RestrictionMode asnMode = RestrictionMode.DISABLED;
    public List<String> asns = Arrays.asList("AS12345");

    public boolean vpnCheckEnabled = true;
    public List<String> vpnKeywords = Arrays.asList(
        "vpn", "proxy", "tunnel", "socks", "shadowsocks", "wireguard", "openvpn", "hosting", "datacenter", "data center", 
        "colocation", "colo", "cloud", "vps", "virtual private server", "dedicated server", "amazon", "aws", "azure", 
        "google cloud", "gcp", "digitalocean", "linode", "vultr", "hetzner", "ovh", "scaleway", "contabo", "hostinger", 
        "godaddy", "namecheap", "bluehost", "dreamhost", "hostgator", "nordvpn", "expressvpn", "surfshark", "protonvpn", 
        "mullvad", "privateinternetaccess", "pia", "cyberghost", "relay", "tor", "exit node", "residential proxy", 
        "mobile proxy", "rackspace", "cloudflare", "fastly", "akamai", "ionos", "liquidweb", "interserver", "a2hosting", 
        "siteground", "inmotion", "nexcess", "wpengine", "kinsta", "serverspace", "kamatera", "atlantic.net", "phoenixnap", 
        "cherry servers", "serverhub", "quadranet", "choopa", "ramnode", "buyvm", "frantech", "incognet", "m247", "psychz", 
        "coresite", "equinix", "ip address", "anonymous", "vpn server", "proxy server", "hide ip", "mask ip", "vpn tunnel", 
        "encrypted connection", "no-log", "private network", "secure connection", "unblock", "bypass", "geolocation", 
        "ip masking", "anonymizer", "hidemyass", "windscribe", "ipvanish", "vyprvpn", "hotspot shield", "tunnelbear", 
        "zenmate", "hide.me", "perfect privacy", "airvpn", "ivpn", "trust.zone", "purevpn", "safervpn", "astrill", "goose vpn"
    );

    public String kickMessageCountry = "Your country is not allowed.";
    public String kickMessageASN = "Your ISP/ASN is not allowed.";
    public String kickMessageVPN = "VPN or Proxy detected.";
    
    public DiscordSettings discord = new DiscordSettings();

    public static class DiscordSettings {
        public String webhook = "";
        public boolean maskIp = true;
        public String title = "GeoRestrict";
        public int colorAllowed = 65280; // Green
        public int colorDenied = 16711680; // Red
        
        public List<EmbedField> fields = Arrays.asList(
            new EmbedField("Player", "%player%", true),
            new EmbedField("Status", "%status%", true),
            new EmbedField("IP", "%ip%", true),
            new EmbedField("Country", "%country%", true),
            new EmbedField("ASN", "%asn%", true),
            new EmbedField("ISP", "%isp%", false),
            new EmbedField("Reason", "%reason%", false)
        );
    }
    
    public static class EmbedField {
        public String name;
        public String value;
        public boolean inline;

        public EmbedField() {}
        public EmbedField(String name, String value, boolean inline) {
            this.name = name;
            this.value = value;
            this.inline = inline;
        }
    }

    public enum RestrictionMode {
        ALLOWLIST,
        BLOCKLIST,
        DISABLED
    }
}


