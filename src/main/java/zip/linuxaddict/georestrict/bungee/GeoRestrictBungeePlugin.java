/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.bstats.bungeecord.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.CommandHandler;
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;
import zip.linuxaddict.georestrict.UpdateChecker;
import zip.linuxaddict.georestrict.api.GeoRestrictAPI;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class GeoRestrictBungeePlugin extends Plugin implements Listener {
    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private UpdateChecker updateChecker;
    private CommandHandler command;
    private Logger log;

    @Override
    public void onEnable() {
        log = LoggerFactory.getLogger("GeoRestrict");
        log.info("GeoRestrict v{} starting...", PluginInfo.VERSION);

        new Metrics(this, PluginInfo.BSTATS_BUNGEE);

        File configFile = new File(getDataFolder(), "config.yml");
        config = ConfigLoader.load(configFile);

        File cacheFile = new File(getDataFolder(), "geo_cache.json");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        service = new GeoRestrictService(config, log, cache);
        GeoRestrictAPI.register(service, cache);
        getProxy().getPluginManager().registerListener(this, this);

        command = new CommandHandler(service, cache, configFile, this::applyConfig);
        registerCommand();
        startConfigWatcher(configFile);
        startUpdateChecker();
        startCacheMaintenance();
        log.info("GeoRestrict enabled.");
        log.info(PluginInfo.COMMUNITY_MESSAGE);
        log.info(PluginInfo.FEEDBACK_MESSAGE);
    }

    private void applyConfig(GeoConfig fresh, Runnable done) {
        this.config = fresh;
        service.setConfig(fresh);
        done.run();
    }

    private void registerCommand() {
        getProxy().getPluginManager().registerCommand(this, new Command("georestrict", "georestrict.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                String[] a = args == null ? new String[0] : args;
                command.execute(
                    new CommandHandler.Sender() {
                        @Override public boolean hasPermission(String p) { return sender.hasPermission(p); }
                        @Override public void sendMessage(String m) {
                            sender.sendMessage(TextComponent.fromLegacyText(CommandHandler.legacy(m)));
                        }
                    },
                    name -> {
                        ProxiedPlayer online = getProxy().getPlayer(name);
                        return online == null ? null : online.getAddress().getAddress().getHostAddress();
                    },
                    a
                );
            }
        });
    }

    private void startConfigWatcher(File configFile) {
        getProxy().getScheduler().schedule(this, new Runnable() {
            private long lastModified = configFile.lastModified();
            @Override public void run() {
                long now = configFile.lastModified();
                if (now > lastModified) {
                    lastModified = now;
                    log.info("Config changed, reloading...");
                    GeoConfig fresh = ConfigLoader.load(configFile);
                    applyConfig(fresh, () -> log.info("Config reloaded."));
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void startUpdateChecker() {
        if (!config.updateCheck) return;
        updateChecker = new UpdateChecker(PluginInfo.VERSION, PluginInfo.MODRINTH_PROJECT);
        getProxy().getScheduler().schedule(this, () ->
            updateChecker.checkForUpdate().thenAccept(latest -> {
                if (latest != null) log.info("Update available: {}", latest);
            }), 5, 6 * 60 * 60, TimeUnit.SECONDS);
    }

    private void startCacheMaintenance() {
        getProxy().getScheduler().schedule(this,
            () -> cache.purgeExpired(config.cacheTtlDays),
            5 * 60, 6 * 60 * 60, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        GeoRestrictAPI.unregister();
        getProxy().getScheduler().cancel(this);
        if (service != null) service.shutdown();
        if (cache != null) {
            cache.shutdown();
            cache.save();
        }
        if (log != null) log.info("GeoRestrict disabled.");
    }

    @EventHandler
    public void onLogin(LoginEvent event) {
        event.registerIntent(this);
        String ip = event.getConnection().getAddress().getAddress().getHostAddress();
        String name = event.getConnection().getName();
        try {
            service.checkIp(ip, name, false).whenComplete((result, error) -> {
                try {
                    if (error != null) {
                        log.error("Lookup error for {}: {}", name, error.getMessage());
                        if (config.blockOnLookupFailure) {
                            event.setCancelled(true);
                            event.setCancelReason(TextComponent.fromLegacyText(
                                CommandHandler.legacy(config.kickMessageLookupFailure)));
                        }
                        return;
                    }
                    if (!result.allowed) {
                        event.setCancelled(true);
                        event.setCancelReason(TextComponent.fromLegacyText(
                            CommandHandler.legacy(result.reason == null ? "Connection rejected." : result.reason)));
                    }
                } finally {
                    event.completeIntent(this);
                }
            });
        } catch (Exception e) {
            log.error("Lookup failed for {}", name, e);
            event.completeIntent(this);
        }
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        if (updateChecker != null && updateChecker.isUpdateAvailable()
            && event.getPlayer().hasPermission("georestrict.admin")) {
            event.getPlayer().sendMessage(TextComponent.fromLegacyText(CommandHandler.legacy(
                "&7[&bGeoRestrict&7] Update available: &b" + updateChecker.getLatestVersion())));
        }
    }

    public GeoRestrictService getService() {
        return service;
    }

    public GeoCache getCache() {
        return cache;
    }
}
