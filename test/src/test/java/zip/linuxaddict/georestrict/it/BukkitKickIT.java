/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.it;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.bukkit.GeoRestrictBukkitPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BukkitKickIT {

    private static final Logger log = LoggerFactory.getLogger("BukkitKickIT");
    private static GeoRestrictBukkitPlugin plugin;
    private static MockGateway gw;

    @BeforeAll
    static void setUp() throws Exception {
        MockBukkit.mock();
        gw = new MockGateway();
        gw.with("5.6.7.8", "RU", "12389", "Rostelecom", false, false, false)
          .with("1.2.3.4", "US", "15169", "Google LLC", false, false, false);

        plugin = MockBukkit.load(GeoRestrictBukkitPlugin.class);
        assertNotNull(plugin, "plugin should load under MockBukkit");

        GeoConfig cfg = new GeoConfig();
        cfg.gatewayUrl = gw.baseUrl();
        cfg.countries = Arrays.asList("RU");
        cfg.countryMode = GeoConfig.RestrictionMode.BLOCKLIST;
        cfg.asnMode = GeoConfig.RestrictionMode.DISABLED;
        cfg.vpnCheckEnabled = false;
        cfg.blockOnLookupFailure = true;
        cfg.updateCheck = false;
        cfg.connectionTimeoutMs = 2000;
        cfg.lookupThreads = 2;
        cfg.kickMessageCountry = "Your country is not allowed on this server.";

        File cacheFile = File.createTempFile("gr-kick", ".json");
        GeoCache cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();
        GeoRestrictService svc = new GeoRestrictService(cfg, log, cache);

        setField(plugin, "service", svc);
        setField(plugin, "config", cfg);
    }

    @AfterAll
    static void tearDown() {
        try {
            MockBukkit.unmock();
        } catch (Exception ignored) {}
        if (gw != null) gw.close();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private AsyncPlayerPreLoginEvent mockPreLogin(String ip, UUID uuid) throws Exception {
        AsyncPlayerPreLoginEvent ev = mock(AsyncPlayerPreLoginEvent.class);
        when(ev.getAddress()).thenReturn(InetAddress.getByName(ip));
        when(ev.getName()).thenReturn("tester");
        when(ev.getUniqueId()).thenReturn(uuid);
        when(ev.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        return ev;
    }

    private PlayerLoginEvent mockLogin(UUID uuid, boolean bypass) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission("georestrict.bypass")).thenReturn(bypass);

        PlayerLoginEvent event = mock(PlayerLoginEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getResult()).thenReturn(PlayerLoginEvent.Result.ALLOWED);
        return event;
    }

    @Test
    void blockedCountryDisallows() throws Exception {
        UUID uuid = UUID.randomUUID();
        AsyncPlayerPreLoginEvent preLogin = mockPreLogin("5.6.7.8", uuid);
        PlayerLoginEvent login = mockLogin(uuid, false);

        plugin.onAsyncLogin(preLogin);
        plugin.onPlayerLogin(login);

        verify(preLogin, never()).disallow((AsyncPlayerPreLoginEvent.Result) any(), anyString());
        verify(login).disallow(
            (PlayerLoginEvent.Result) eq(PlayerLoginEvent.Result.KICK_OTHER),
            contains("not allowed"));
    }

    @Test
    void allowedCountryNotDisallowed() throws Exception {
        UUID uuid = UUID.randomUUID();
        AsyncPlayerPreLoginEvent preLogin = mockPreLogin("1.2.3.4", uuid);
        PlayerLoginEvent login = mockLogin(uuid, false);

        plugin.onAsyncLogin(preLogin);
        plugin.onPlayerLogin(login);

        verify(login, never()).disallow((PlayerLoginEvent.Result) any(), anyString());
    }

    @Test
    void privateIpNotDisallowed() throws Exception {
        UUID uuid = UUID.randomUUID();
        AsyncPlayerPreLoginEvent preLogin = mockPreLogin("127.0.0.1", uuid);
        PlayerLoginEvent login = mockLogin(uuid, false);

        plugin.onAsyncLogin(preLogin);
        plugin.onPlayerLogin(login);

        verify(login, never()).disallow((PlayerLoginEvent.Result) any(), anyString());
    }

    @Test
    void bypassPermissionAllowsBlockedCountry() throws Exception {
        UUID uuid = UUID.randomUUID();
        AsyncPlayerPreLoginEvent preLogin = mockPreLogin("5.6.7.8", uuid);
        PlayerLoginEvent login = mockLogin(uuid, true);

        plugin.onAsyncLogin(preLogin);
        plugin.onPlayerLogin(login);

        verify(login, never()).disallow((PlayerLoginEvent.Result) any(), anyString());
    }
}
