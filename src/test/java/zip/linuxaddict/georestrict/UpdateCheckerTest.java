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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void higherMajorIsNewer() {
        assertTrue(call("2.0.0", "3.0.0"));
        assertFalse(call("3.0.0", "2.0.0"));
    }

    @Test
    void higherMinorIsNewer() {
        assertTrue(call("1.9.0", "2.0.0"));
        assertTrue(call("2.0.9", "2.0.10"));
    }

    @Test
    void equalVersionsAreNotNewer() {
        assertFalse(call("2.0.0", "2.0.0"));
        assertFalse(call("2.0.0", "2.0"));
    }

    @Test
    void stripsLeadingVAndPreRelease() {
        assertTrue(call("2.0.0", "v3.0.0-rc.1"));
        assertTrue(call("2.0.0-beta.1", "2.0.0"));
    }

    private boolean call(String current, String remote) {
        return invoke(current, remote);
    }

    private static boolean invoke(String current, String remote) {
        try {
            var m = UpdateChecker.class.getDeclaredMethod("isNewer", String.class, String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, remote, current);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
