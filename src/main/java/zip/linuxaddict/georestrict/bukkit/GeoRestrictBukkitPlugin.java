/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.UpdateChecker;
import zip.linuxaddict.georestrict.scheduler.BukkitTaskScheduler;
import zip.linuxaddict.georestrict.scheduler.FoliaTaskScheduler;
import zip.linuxaddict.georestrict.scheduler.TaskScheduler;

import java.io.File;

public class GeoRestrictBukkitPlugin extends JavaPlugin implements Listener {
    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private TaskScheduler scheduler;
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

        // Detect and create scheduler adapter
        scheduler = createScheduler();

        File configFile = new File(getDataFolder(), "config.yml");
        config = ConfigLoader.load(configFile);

        // Create cache
        File cacheFile = new File(getDataFolder(), "geo_cache.json");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        service = new GeoRestrictService(config, log, cache);
        getServer().getPluginManager().registerEvents(this, this);

        // bStats metrics
        int pluginId = 28563;
        Metrics metrics = new Metrics(this, pluginId);

        // Config watcher using scheduler adapter
        startConfigWatcher(configFile);

        // Update checker
        if (config.updateCheck) {
            updateChecker = new UpdateChecker("2.0.0", "georestrict", log);
            // Check immediately and then every 6 hours (6 * 60 * 60 * 20 = 432000 ticks)
            scheduler.runTimerAsync(this, () -> {
                updateChecker.checkForUpdate();
            }, 100L, 432000L);
        }

        // Command executor
        getCommand("georestrict").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("georestrict.admin")) {
                sender.sendMessage("Â§cYou do not have permission to use this command.");
                return true;
            }

            if (args.length == 0) {
                // No args — show version + credits
                sender.sendMessage("§b§lGeoRestrict §7v2.0.0");
                sender.sendMessage("§7By §bDemonz Development");
                sender.sendMessage("§7https://demonzdevelopment.online/");
                return true;
            }


            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "check": {
                    if (args.length < 2) {
                        sender.sendMessage("Â§cUsage: /georestrict check <ip|player>");
                        return true;
                    }

                    String target = args[1];
                    Player targetPlayer = getServer().getPlayer(target);
                    String ipToCheck = (targetPlayer != null) ? targetPlayer.getAddress().getAddress().getHostAddress() : target;

                    sender.sendMessage("Â§7Checking IP: " + ipToCheck + "...");
                    service.checkIp(ipToCheck, target).thenAccept(result -> {
                        sender.sendMessage("Â§7Result for " + ipToCheck + ":");
                        sender.sendMessage("Â§7Allowed: " + (result.allowed ? "Â§aYes" : "Â§cNo"));
                        if (!result.allowed) {
                            sender.sendMessage("Â§7Reason: " + result.reason);
                        }

                        if (result.info != null) {
                            sender.sendMessage("Â§7Country: " + result.info.countryCode);
                            sender.sendMessage("Â§7ASN: " + result.info.asn);
                            sender.sendMessage("Â§7ISP: " + result.info.asName);
                        }
                    });
                    break;
                }

                case "purgecache": {
                    cache.purgeAll();
                    sender.sendMessage("Â§aGeoRestrict cache purged successfully.");
                    break;
                }

                case "cachestats": {
                    GeoCache.CacheStats stats = cache.getStats();
                    sender.sendMessage("Â§bÂ§lGeoRestrict Cache Stats");
                    sender.sendMessage("Â§7Entries: Â§f" + stats.entryCount);
                    sender.sendMessage("Â§7File size: Â§f" + String.format("%.2f", stats.fileSizeBytes / 1024.0) + " KB");
                    if (stats.oldestEntryTimestamp > 0) {
                        long ageMs = System.currentTimeMillis() - stats.oldestEntryTimestamp;
                        long ageDays = ageMs / (1000L * 60 * 60 * 24);
                        long ageHours = (ageMs / (1000L * 60 * 60)) % 24;
                        sender.sendMessage("Â§7Oldest entry: Â§f" + ageDays + "d " + ageHours + "h ago");
                    } else {
                        sender.sendMessage("Â§7Oldest entry: Â§fN/A");
                    }
                    break;
                }

                case "reload": {
                    config = ConfigLoader.load(configFile);
                    service.setConfig(config);
                    sender.sendMessage("Â§aGeoRestrict config reloaded.");
                    break;
                }

                default:
                    sender.sendMessage("Â§cUsage: /georestrict <check|purgecache|cachestats|reload>");
                    break;
            }

            return true;
        });

        log.info("GeoRestrict enabled");
    }

    /**
     * Config watcher using TaskScheduler adapter.
     * Polls the config file every 5 seconds (100 ticks) for changes.
     */
    private void startConfigWatcher(File configFile) {
        Runnable watcherTask = new Runnable() {
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
        };

        scheduler.runTimerAsync(this, watcherTask, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancelAll(this);
        }
        if (cache != null) {
            cache.save();
        }
        log.info("GeoRestrict disabled");
    }

    @EventHandler
    public void onAsyncLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        String name = event.getName();
        try {
            // Check bypass permission via OfflinePlayer (works with LuckPerms and most perm plugins)
            boolean bypass = getServer().getOfflinePlayer(event.getUniqueId()).isOp();
            try {
                org.bukkit.OfflinePlayer offlinePlayer = getServer().getOfflinePlayer(event.getUniqueId());
                if (offlinePlayer.isOp()) {
                    // OP players have all permissions including bypass, but check explicitly if possible
                    bypass = true;
                }
                // Try to check the actual permission via the player if they've joined before
                org.bukkit.entity.Player onlinePlayer = getServer().getPlayer(event.getUniqueId());
                if (onlinePlayer != null) {
                    bypass = onlinePlayer.hasPermission("georestrict.bypass");
                }
            } catch (Exception ignored) {}

            GeoRestrictService.CheckResult result = service.checkIp(ip, name, bypass).join();
            if (!result.allowed) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, result.reason);
            }
        } catch (Exception e) {
            log.error("Failed to check IP: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Notify admins of available updates
        if (updateChecker != null && updateChecker.isUpdateAvailable()) {
            Player player = event.getPlayer();
            if (player.hasPermission("georestrict.admin")) {
                player.sendMessage("Â§7[Â§bGeoRestrictÂ§7] A new version is available: Â§b" + updateChecker.getLatestVersion());
            }
        }
    }

    /**
     * Factory method to detect Folia and create the appropriate scheduler adapter.
     */
    public static TaskScheduler createScheduler() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return new FoliaTaskScheduler();
        } catch (ClassNotFoundException e) {
            return new BukkitTaskScheduler();
        }
    }
}

