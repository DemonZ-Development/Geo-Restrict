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

public class GeoResponse {
    public String ip;
    public String countryCode;
    public String countryName;
    public String asn;
    public String asName;
    public String provider;
    public boolean isVpn;
    public boolean isHosting;
    public boolean isProxy;
    public boolean isMobile;

    public GeoResponse() {}
}
