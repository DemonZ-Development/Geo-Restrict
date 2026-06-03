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
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.UpdateChecker;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class GeoRestrictBungeePlugin extends Plugin implements Listener {
    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private UpdateChecker updateChecker;
    private Logger log;

    @Override
    public void onEnable() {
        log = LoggerFactory.getLogger("GeoRestrict");

        // Startup banner
        log.info("╔═══════════════════════════════════════╗");
        log.info("║         GeoRestrict v2.0.0           ║");
        log.info("║      By Demonz Development           ║");
        log.info("║  https://demonzdevelopment.online/    ║");
        log.info("╚═══════════════════════════════════════╝");

        // bStats metrics
        new Metrics(this, 28563);

        File configFile = new File(getDataFolder(), "config.yml");
        config = ConfigLoader.load(configFile);

        // Create cache
        File cacheFile = new File(getDataFolder(), "geo_cache.json");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        service = new GeoRestrictService(config, log, cache);
        getProxy().getPluginManager().registerListener(this, this);

        // Config watcher using BungeeCord scheduler
        getProxy().getScheduler().schedule(this, new Runnable() {
            private long lastModified = configFile.lastModified();

            @Override
            public void run() {
                if (configFile.lastModified() > lastModified) {
                    lastModified = configFile.lastModified();
                    log.info("Config change detected, reloading...");
                    config = ConfigLoader.load(configFile);
                    service.setConfig(config);
                    log.info("Config reloaded.");
                }
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Update checker
        if (config.updateCheck) {
            updateChecker = new UpdateChecker("2.0.0", "georestrict", log);

            // Check immediately then every 6 hours
            getProxy().getScheduler().schedule(this, () -> {
                updateChecker.checkForUpdate();
            }, 5, 6 * 60 * 60, TimeUnit.SECONDS);
        }

        // Command handler
        getProxy().getPluginManager().registerCommand(this, new Command("georestrict", "georestrict.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 0) {
                    // No args — show version + credits
                    sender.sendMessage(TextComponent.fromLegacyText("§b§lGeoRestrict §7v2.0.0"));
                    sender.sendMessage(TextComponent.fromLegacyText("§7By §bDemonz Development"));
                    sender.sendMessage(TextComponent.fromLegacyText("§7https://demonzdevelopment.online/"));
                    return;
                }


                String subCommand = args[0].toLowerCase();

                switch (subCommand) {
                    case "check": {
                        if (args.length < 2) {
                            sender.sendMessage(TextComponent.fromLegacyText("Â§cUsage: /georestrict check <ip|player>"));
                            return;
                        }

                        String target = args[1];
                        ProxiedPlayer targetPlayer = getProxy().getPlayer(target);
                        String ipToCheck = (targetPlayer != null) ? targetPlayer.getAddress().getAddress().getHostAddress() : target;

                        sender.sendMessage(TextComponent.fromLegacyText("Â§7Checking IP: " + ipToCheck + "..."));
                        service.checkIp(ipToCheck, target).thenAccept(result -> {
                            sender.sendMessage(TextComponent.fromLegacyText("Â§7Result for " + ipToCheck + ":"));
                            sender.sendMessage(TextComponent.fromLegacyText("Â§7Allowed: " + (result.allowed ? "Â§aYes" : "Â§cNo")));
                            if (!result.allowed) {
                                sender.sendMessage(TextComponent.fromLegacyText("Â§7Reason: " + result.reason));
                            }

                            if (result.info != null) {
                                sender.sendMessage(TextComponent.fromLegacyText("Â§7Country: " + result.info.countryCode));
                                sender.sendMessage(TextComponent.fromLegacyText("Â§7ASN: " + result.info.asn));
                                sender.sendMessage(TextComponent.fromLegacyText("Â§7ISP: " + result.info.asName));
                            }
                        });
                        break;
                    }

                    case "purgecache": {
                        cache.purgeAll();
                        sender.sendMessage(TextComponent.fromLegacyText("Â§aGeoRestrict cache purged successfully."));
                        break;
                    }

                    case "cachestats": {
                        GeoCache.CacheStats stats = cache.getStats();
                        sender.sendMessage(TextComponent.fromLegacyText("Â§bÂ§lGeoRestrict Cache Stats"));
                        sender.sendMessage(TextComponent.fromLegacyText("Â§7Entries: Â§f" + stats.entryCount));
                        sender.sendMessage(TextComponent.fromLegacyText("Â§7File size: Â§f" + String.format("%.2f", stats.fileSizeBytes / 1024.0) + " KB"));
                        if (stats.oldestEntryTimestamp > 0) {
                            long ageMs = System.currentTimeMillis() - stats.oldestEntryTimestamp;
                            long ageDays = ageMs / (1000L * 60 * 60 * 24);
                            long ageHours = (ageMs / (1000L * 60 * 60)) % 24;
                            sender.sendMessage(TextComponent.fromLegacyText("Â§7Oldest entry: Â§f" + ageDays + "d " + ageHours + "h ago"));
                        } else {
                            sender.sendMessage(TextComponent.fromLegacyText("Â§7Oldest entry: Â§fN/A"));
                        }
                        break;
                    }

                    case "reload": {
                        config = ConfigLoader.load(configFile);
                        service.setConfig(config);
                        sender.sendMessage(TextComponent.fromLegacyText("Â§aGeoRestrict config reloaded."));
                        break;
                    }

                    default:
                        sender.sendMessage(TextComponent.fromLegacyText("Â§cUsage: /georestrict <check|purgecache|cachestats|reload>"));
                        break;
                }
            }
        });

        log.info("GeoRestrict enabled");
    }

    @Override
    public void onDisable() {
        if (cache != null) {
            cache.save();
        }
        log.info("GeoRestrict disabled");
    }

    @EventHandler
    public void onLogin(LoginEvent event) {
        event.registerIntent(this);
        String ip = event.getConnection().getAddress().getAddress().getHostAddress();
        String name = event.getConnection().getName();
        // Note: On BungeeCord LoginEvent, the player object doesn't exist yet,
        // so we can't check georestrict.bypass permission here.
        // Bypass is handled at PostLogin for re-check if needed.
        service.checkIp(ip, name, false).thenAccept(result -> {
            if (!result.allowed) {
                event.setCancelled(true);
                event.setCancelReason(TextComponent.fromLegacyText(result.reason));
            }
            event.completeIntent(this);
        });
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        // Notify admins of available updates
        if (updateChecker != null && updateChecker.isUpdateAvailable()) {
            ProxiedPlayer player = event.getPlayer();
            if (player.hasPermission("georestrict.admin")) {
                player.sendMessage(TextComponent.fromLegacyText("Â§7[Â§bGeoRestrictÂ§7] A new version is available: Â§b" + updateChecker.getLatestVersion()));
            }
        }
    }
}

