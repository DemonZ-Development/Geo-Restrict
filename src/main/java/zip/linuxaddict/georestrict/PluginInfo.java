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

public final class PluginInfo {
    public static final String VERSION = "2.1.0";
    public static final String MODRINTH_PROJECT = "georestrict";
    public static final String USER_AGENT = "GeoRestrict/" + VERSION;

    public static final int BSTATS_BUKKIT = 28563;
    public static final int BSTATS_BUNGEE = 28564;
    public static final int BSTATS_VELOCITY = 28565;

    public static final String DEFAULT_GATEWAY_URL = "https://geoprotect.demonzdev.workers.dev/";

    private PluginInfo() {}
}
