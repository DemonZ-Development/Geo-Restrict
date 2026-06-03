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

/**
 * Unified geolocation response model.
 * Replaces the old IpInfoResponse with a richer, provider-agnostic structure.
 */
public class GeoResponse {
    public String ip;
    public String countryCode;
    public String countryName;
    public String asn;
    public String asName;
    public boolean isVpn;
    public boolean isHosting;
    public boolean isProxy;
    public boolean isMobile;

    public GeoResponse() {}

    /**
     * Backward-compatibility helper: maps old IpInfoResponse field names
     * to this unified model so existing evaluate() logic continues to work.
     *
     * Old field â†’ New field:
     *   country_code â†’ countryCode
     *   country      â†’ countryName
     *   as_name      â†’ asName
     *   asn          â†’ asn
     *   ip           â†’ ip
     */
}

