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
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
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
import zip.linuxaddict.georestrict.scheduler.BukkitTaskScheduler;
import zip.linuxaddict.georestrict.scheduler.FoliaTaskScheduler;
import zip.linuxaddict.georestrict.scheduler.TaskScheduler;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class GeoRestrictBukkitPlugin extends JavaPlugin implements Listener {
    private static final long PENDING_CHECK_TTL_MS = 300_000L;

    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private TaskScheduler scheduler;
    private UpdateChecker updateChecker;
    private CommandHandler command;
    private Logger log;
    private final Map<UUID, PendingCheck> pendingChecks = new ConcurrentHashMap<>();
    private final AtomicLong connectionCount = new AtomicLong();

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
        GeoRestrictAPI.register(service, cache);
        getServer().getServicesManager().register(GeoRestrictService.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        Metrics metrics = new Metrics(this, PluginInfo.BSTATS_BUKKIT);
        metrics.addCustomChart(new SingleLineChart("connections", () -> (int) connectionCount.getAndSet(0)));
        metrics.addCustomChart(new SimplePie("country_mode", () -> config.countryMode.name().toLowerCase()));
        metrics.addCustomChart(new SimplePie("vpn_check", () -> config.vpnCheckEnabled ? "enabled" : "disabled"));

        command = new CommandHandler(service, cache, configFile, this::applyConfig);
        registerCommand();
        startConfigWatcher(configFile);
        startUpdateChecker();
        startCacheMaintenance();
        startPendingCheckPruning();
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
        updateChecker = new UpdateChecker(PluginInfo.VERSION, PluginInfo.MODRINTH_PROJECT);
        scheduler.runTimerAsync(this, () ->
            updateChecker.checkForUpdate().thenAccept(latest -> {
                if (latest != null) log.info("Update available: {}", latest);
            }), 100L, 432000L);
    }

    private void startCacheMaintenance() {
        scheduler.runTimerAsync(this, () -> cache.purgeExpired(config.cacheTtlDays), 6000L, 216000L);
    }

    private void startPendingCheckPruning() {
        scheduler.runTimerAsync(this, this::prunePendingChecks, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        GeoRestrictAPI.unregister();
        if (getServer() != null && getServer().getServicesManager() != null && service != null) {
            getServer().getServicesManager().unregister(service);
        }
        pendingChecks.clear();
        if (scheduler != null) scheduler.cancelAll(this);
        if (service != null) service.shutdown();
        if (cache != null) {
            cache.shutdown();
            cache.save();
        }
        if (log != null) log.info("GeoRestrict disabled.");
    }

    @EventHandler
    public void onAsyncLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        String name = event.getName();
        UUID uuid = event.getUniqueId();

        try {
            connectionCount.incrementAndGet();
            GeoRestrictService.CheckResult result = service.checkIp(ip, name, false).join();
            if (!result.allowed) {
                pendingChecks.put(uuid, new PendingCheck(result, System.currentTimeMillis()));
            } else {
                pendingChecks.remove(uuid);
            }
        } catch (Exception e) {
            log.error("Lookup failed during login for {}", name, e);
            if (config.blockOnLookupFailure) {
                GeoRestrictService.CheckResult result = new GeoRestrictService.CheckResult(
                    false, config.kickMessageLookupFailure, null);
                pendingChecks.put(uuid, new PendingCheck(result, System.currentTimeMillis()));
            }
        }
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        PendingCheck pending = pendingChecks.remove(event.getPlayer().getUniqueId());
        if (pending == null || event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        if (event.getPlayer().isOp() || event.getPlayer().hasPermission("georestrict.bypass")) return;

        String reason = pending.result.reason == null ? "Connection rejected." : pending.result.reason;
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, reason);
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
        } catch (NoClassDefFoundError e) {
            return new BukkitTaskScheduler();
        }
    }

    private void prunePendingChecks() {
        long cutoff = System.currentTimeMillis() - PENDING_CHECK_TTL_MS;
        pendingChecks.entrySet().removeIf(entry -> entry.getValue().createdAt < cutoff);
    }

    private static final class PendingCheck {
        private final GeoRestrictService.CheckResult result;
        private final long createdAt;

        private PendingCheck(GeoRestrictService.CheckResult result, long createdAt) {
            this.result = result;
            this.createdAt = createdAt;
        }
    }

    public GeoRestrictService getService() {
        return service;
    }

    public GeoCache getCache() {
        return cache;
    }
}
