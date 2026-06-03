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

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.CommandHandler;
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;
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
    private CommandHandler command;
    private Logger log;

    @Override
    public void onEnable() {
        log = LoggerFactory.getLogger("GeoRestrict");
        log.info("GeoRestrict v{} starting...", PluginInfo.VERSION);

        scheduler = createScheduler();

        File configFile = new File(getDataFolder(), "config.yml");
        config = ConfigLoader.load(configFile);

        File cacheFile = new File(getDataFolder(), "geo_cache.json");
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), log);
        cache.load();

        service = new GeoRestrictService(config, log, cache);
        getServer().getPluginManager().registerEvents(this, this);

        new Metrics(this, PluginInfo.BSTATS_BUKKIT);

        command = new CommandHandler(service, cache, configFile, this::applyConfig, () -> {});
        registerCommand();
        startConfigWatcher(configFile);
        startUpdateChecker();
        startCacheMaintenance();
        log.info("GeoRestrict enabled.");
    }

    private void applyConfig(GeoConfig fresh, Runnable done) {
        this.config = fresh;
        service.setConfig(fresh);
        done.run();
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("georestrict");
        if (cmd == null) {
            log.warn("Command 'georestrict' is missing from plugin.yml");
            return;
        }
        cmd.setExecutor((sender, bukkitCmd, label, args) -> {
            String[] resolved = args == null ? new String[0] : args;
            Player player = sender instanceof Player ? (Player) sender : null;
            return command.execute(
                new CommandHandler.Sender() {
                    @Override public boolean hasPermission(String p) { return sender.hasPermission(p); }
                    @Override public void sendMessage(String m) { sender.sendMessage(CommandHandler.legacy(m)); }
                },
                name -> {
                    if (player != null && name.equalsIgnoreCase(player.getName())) {
                        return player.getAddress().getAddress().getHostAddress();
                    }
                    Player online = getServer().getPlayer(name);
                    return online == null ? null : online.getAddress().getAddress().getHostAddress();
                },
                resolved
            );
        });
        cmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                return java.util.Arrays.asList("check", "purgecache", "cachestats", "reload");
            }
            return java.util.Collections.emptyList();
        });
    }

    private void startConfigWatcher(File configFile) {
        scheduler.runTimerAsync(this, new Runnable() {
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
        }, 100L, 100L);
    }

    private void startUpdateChecker() {
        if (!config.updateCheck) return;
        updateChecker = new UpdateChecker(PluginInfo.VERSION, PluginInfo.MODRINTH_PROJECT, log);
        scheduler.runTimerAsync(this, () ->
            updateChecker.checkForUpdate().thenAccept(latest -> {
                if (latest != null) log.info("Update available: {}", latest);
            }), 100L, 432000L);
    }

    private void startCacheMaintenance() {
        scheduler.runTimerAsync(this, () -> cache.purgeExpired(config.cacheTtlDays), 6000L, 216000L);
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.cancelAll(this);
        if (service != null) service.shutdown();
        if (cache != null) cache.save();
        if (log != null) log.info("GeoRestrict disabled.");
    }

    @EventHandler
    public void onAsyncLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        String name = event.getName();
        try {
            // Pre-login permission lookups are unreliable across server
            // implementations, so OP is the only universally safe bypass here.
            // Permission-based bypass is enforced in PlayerJoinEvent below.
            boolean bypass = getServer().getOfflinePlayer(event.getUniqueId()).isOp();
            GeoRestrictService.CheckResult result = service.checkIp(ip, name, bypass).join();
            if (!result.allowed) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    result.reason == null ? "Connection rejected." : result.reason);
            }
        } catch (Exception e) {
            log.error("Lookup failed during login for {}", name, e);
            if (config.blockOnLookupFailure) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, config.kickMessageLookupFailure);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (updateChecker != null && updateChecker.isUpdateAvailable()
            && event.getPlayer().hasPermission("georestrict.admin")) {
            event.getPlayer().sendMessage(CommandHandler.legacy(
                "&7[&bGeoRestrict&7] Update available: &b" + updateChecker.getLatestVersion()));
        }
    }

    public static TaskScheduler createScheduler() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return new FoliaTaskScheduler();
        } catch (ClassNotFoundException e) {
            return new BukkitTaskScheduler();
        }
    }
}
