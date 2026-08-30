/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.floodgate;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FloodgateHandlerTest {

    @Test
    void isAvailableReturnsBooleanWithoutCrashing() {
        assertDoesNotThrow(FloodgateHandler::isAvailable);
    }

    @Test
    void isBedrockPlayerReturnsFalseForNullUuid() {
        assertFalse(FloodgateHandler.isBedrockPlayer(null));
    }

    @Test
    void getBedrockInfoReturnsNullForNullUuid() {
        assertNull(FloodgateHandler.getBedrockInfo(null));
    }

    @Test
    void isBedrockPlayerReturnsFalseWhenFloodgateNotPresent() {
        UUID randomUuid = UUID.randomUUID();
        assertFalse(FloodgateHandler.isBedrockPlayer(randomUuid));
    }

    @Test
    void getBedrockInfoReturnsNullWhenFloodgateNotPresent() {
        UUID randomUuid = UUID.randomUUID();
        assertNull(FloodgateHandler.getBedrockInfo(randomUuid));
    }
}
