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

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void writesDefaultConfigOnFirstRun() throws Exception {
        File tmp = File.createTempFile("geo-config", ".yml");
        tmp.delete();
        GeoConfig config = ConfigLoader.load(tmp);
        assertNotNull(config);
        assertTrue(tmp.exists());
        assertTrue(tmp.length() > 0);
        assertEquals(GeoConfigConstants.CURRENT_VERSION, config.configVersion);
        tmp.delete();
    }

    @Test
    void clampsOutOfRangeValues() throws Exception {
        File tmp = File.createTempFile("geo-config-clamp", ".yml");
        tmp.delete();
        GeoConfig config = ConfigLoader.load(tmp);
        Method clamp = ConfigLoader.class.getDeclaredMethod("clamp", int.class, int.class, int.class);
        clamp.setAccessible(true);
        // Cached values are clamped; verify the helper directly.
        assertEquals(1, clamp.invoke(null, -5, 1, 365));
        assertEquals(365, clamp.invoke(null, 9999, 1, 365));
        assertEquals(10, clamp.invoke(null, 10, 1, 365));
        tmp.delete();
    }

    @Test
    void normalizesHttpsGatewayUrl() throws Exception {
        File tmp = File.createTempFile("geo-config-https", ".yml");
        tmp.delete();
        GeoConfig config = ConfigLoader.load(tmp);
        // Default URL must be https.
        assertTrue(config.gatewayUrl.startsWith("https://"));
        tmp.delete();
    }
}
