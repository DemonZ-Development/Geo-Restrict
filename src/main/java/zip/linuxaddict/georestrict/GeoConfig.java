/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
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

    public int configVersion = GeoConfigConstants.CURRENT_VERSION;
    public int cacheTtlDays = 30;
    public boolean updateCheck = true;
    public int connectionTimeoutMs = 3000;
    public int lookupThreads = 4;
    public String gatewayUrl = GeoConfigConstants.DEFAULT_GATEWAY_URL;
    public String gatewayToken = "";
    /** Retired key retained only so older YAML loads. The plugin never calls a provider directly. */
    public boolean blockOnLookupFailure = true;
    public int maxCacheEntries = 100_000;

    public RestrictionMode countryMode = RestrictionMode.BLOCKLIST;
    public List<String> countries = Arrays.asList("CN", "RU", "KP", "IR");

    public RestrictionMode asnMode = RestrictionMode.DISABLED;
    public List<String> asns = Arrays.asList();

    public boolean vpnCheckEnabled = true;
    public List<String> vpnKeywords = GeoConfigConstants.DEFAULT_VPN_KEYWORDS;

    public String kickMessageCountry = "Your country is not allowed on this server.";
    public String kickMessageAsn = "Your ISP/ASN is not allowed on this server.";
    public String kickMessageVpn = "VPN or proxy connections are not allowed.";
    public String kickMessageLookupFailure = "Geo verification is temporarily unavailable. Please try again later.";

    public FloodgateSettings floodgate = new FloodgateSettings();
    public DiscordSettings discord = new DiscordSettings();

    public static class FloodgateSettings {
        public boolean enabled = true;
        public boolean bypassGeorestrict = false;
        public boolean bypassVpnCheck = false;
    }

    public static class DiscordSettings {
        public String webhook = "";
        public boolean maskIp = true;
        public boolean logAllowed = false;
        public boolean logDenied = true;
        public String title = "GeoRestrict";
        public int colorAllowed = 65280;
        public int colorDenied = 16711680;
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
        ALLOWLIST, BLOCKLIST, DISABLED
    }
}
