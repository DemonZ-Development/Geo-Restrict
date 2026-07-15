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

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String generated = Files.readString(tmp.toPath(), StandardCharsets.UTF_8);
        assertTrue(generated.contains("# change gatewayUrl, gatewayToken"));
        assertTrue(!generated.contains("directFallbackEnabled:"));
        assertTrue(generated.contains(PluginInfo.DOCS_URL + "configuration"));
        assertTrue(generated.contains(PluginInfo.SUPPORT_URL));
        tmp.delete();
    }

    @Test
    void clampsOutOfRangeValues() throws Exception {
        File tmp = File.createTempFile("geo-config-clamp", ".yml");
        tmp.delete();
        ConfigLoader.load(tmp);
        Method clamp = ConfigLoader.class.getDeclaredMethod("clamp", int.class, int.class, int.class);
        clamp.setAccessible(true);
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
        assertTrue(config.gatewayUrl.startsWith("https://"));
        tmp.delete();
    }

    @Test
    void ignoresRetiredKeysFromOlderConfigs() throws Exception {
        File tmp = File.createTempFile("geo-config-legacy", ".yml");
        Files.writeString(tmp.toPath(),
            "configVersion: " + GeoConfigConstants.CURRENT_VERSION + "\n"
                + "gatewayUrl: https://example.test/\n"
                + "directFallbackEnabled: true\n",
            StandardCharsets.UTF_8);

        GeoConfig config = ConfigLoader.load(tmp);

        assertEquals("https://example.test/", config.gatewayUrl);
        String cleaned = Files.readString(tmp.toPath(), StandardCharsets.UTF_8);
        assertFalse(cleaned.contains("directFallbackEnabled"));
        assertTrue(cleaned.contains("gatewayUrl: https://example.test/"));
        tmp.delete();
    }

    @Test
    void unrelatedUnknownKeysStillFailClosedToDefaults() throws Exception {
        File tmp = File.createTempFile("geo-config-unknown", ".yml");
        Files.writeString(tmp.toPath(),
            "configVersion: " + GeoConfigConstants.CURRENT_VERSION + "\n"
                + "gatewayUrl: https://example.test/\n"
                + "unknownSetting: true\n",
            StandardCharsets.UTF_8);

        GeoConfig config = ConfigLoader.load(tmp);

        assertEquals(GeoConfigConstants.DEFAULT_GATEWAY_URL, config.gatewayUrl);
        tmp.delete();
    }
}
