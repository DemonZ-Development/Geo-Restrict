/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.UpdateChecker;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

@Plugin(id = "georestrict", name = "GeoRestrict", version = "2.0.0", authors = {"Demonz Development"}, description = "Geographic restriction plugin for Minecraft servers")
public class GeoRestrictVelocityPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private UpdateChecker updateChecker;

    @Inject
    public GeoRestrictVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        // Startup banner
        logger.info("╔═══════════════════════════════════════╗");
        logger.info("║         GeoRestrict v2.0.0           ║");
        logger.info("║      By Demonz Development           ║");
        logger.info("║  https://demonzdevelopment.online/    ║");
        logger.info("╚═══════════════════════════════════════╝");

        // bStats metrics
        metricsFactory.make(this, 28563);

        File configFile = dataDirectory.resolve("config.yml").toFile();
        config = ConfigLoader.load(configFile);

        // Create cache
        File cacheFile = dataDirectory.resolve("geo_cache.json").toFile();
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), logger);
        cache.load();

        service = new GeoRestrictService(config, logger, cache);

        // Config watcher using Velocity scheduler
        server.getScheduler().buildTask(this, new Runnable() {
            private long lastModified = configFile.lastModified();

            @Override
            public void run() {
                if (configFile.lastModified() > lastModified) {
                    lastModified = configFile.lastModified();
                    logger.info("Config change detected, reloading...");
                    config = ConfigLoader.load(configFile);
                    service.setConfig(config);
                    logger.info("Config reloaded.");
                }
            }
        }).repeat(Duration.ofSeconds(5)).schedule();

        // Update checker
        if (config.updateCheck) {
            updateChecker = new UpdateChecker("2.0.0", "georestrict", logger);
            // Check immediately then every 6 hours
            server.getScheduler().buildTask(this, () -> {
                updateChecker.checkForUpdate();
            }).repeat(Duration.ofHours(6)).schedule();
        }

        // Command handler
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("georestrict").build(),
            new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                    CommandSource source = invocation.source();
                    String[] args = invocation.arguments();

                    if (!source.hasPermission("georestrict.admin")) {
                        source.sendMessage(Component.text("Â§cYou do not have permission to use this command."));
                        return;
                    }

                    if (args.length == 0) {
                        // No args — show version + credits
                        source.sendMessage(Component.text("§b§lGeoRestrict §7v2.0.0"));
                        source.sendMessage(Component.text("§7By §bDemonz Development"));
                        source.sendMessage(Component.text("§7https://demonzdevelopment.online/"));
                        return;
                    }


                    String subCommand = args[0].toLowerCase();

                    switch (subCommand) {
                        case "check": {
                            if (args.length < 2) {
                                source.sendMessage(Component.text("Â§cUsage: /georestrict check <ip|player>"));
                                return;
                            }

                            String target = args[1];
                            Optional<Player> targetPlayer = server.getPlayer(target);
                            String ipToCheck = targetPlayer.map(player -> player.getRemoteAddress().getAddress().getHostAddress()).orElse(target);

                            source.sendMessage(Component.text("Â§7Checking IP: " + ipToCheck + "..."));
                            service.checkIp(ipToCheck, target).thenAccept(result -> {
                                source.sendMessage(Component.text("Â§7Result for " + ipToCheck + ":"));
                                source.sendMessage(Component.text("Â§7Allowed: " + (result.allowed ? "Â§aYes" : "Â§cNo")));
                                if (!result.allowed) {
                                    source.sendMessage(Component.text("Â§7Reason: " + result.reason));
                                }

                                if (result.info != null) {
                                    source.sendMessage(Component.text("Â§7Country: " + result.info.countryCode));
                                    source.sendMessage(Component.text("Â§7ASN: " + result.info.asn));
                                    source.sendMessage(Component.text("Â§7ISP: " + result.info.asName));
                                }
                            });
                            break;
                        }

                        case "purgecache": {
                            cache.purgeAll();
                            source.sendMessage(Component.text("Â§aGeoRestrict cache purged successfully."));
                            break;
                        }

                        case "cachestats": {
                            GeoCache.CacheStats stats = cache.getStats();
                            source.sendMessage(Component.text("Â§bÂ§lGeoRestrict Cache Stats"));
                            source.sendMessage(Component.text("Â§7Entries: Â§f" + stats.entryCount));
                            source.sendMessage(Component.text("Â§7File size: Â§f" + String.format("%.2f", stats.fileSizeBytes / 1024.0) + " KB"));
                            if (stats.oldestEntryTimestamp > 0) {
                                long ageMs = System.currentTimeMillis() - stats.oldestEntryTimestamp;
                                long ageDays = ageMs / (1000L * 60 * 60 * 24);
                                long ageHours = (ageMs / (1000L * 60 * 60)) % 24;
                                source.sendMessage(Component.text("Â§7Oldest entry: Â§f" + ageDays + "d " + ageHours + "h ago"));
                            } else {
                                source.sendMessage(Component.text("Â§7Oldest entry: Â§fN/A"));
                            }
                            break;
                        }

                        case "reload": {
                            config = ConfigLoader.load(configFile);
                            service.setConfig(config);
                            source.sendMessage(Component.text("Â§aGeoRestrict config reloaded."));
                            break;
                        }

                        default:
                            source.sendMessage(Component.text("Â§cUsage: /georestrict <check|purgecache|cachestats|reload>"));
                            break;
                    }
                }
            }
        );

        logger.info("GeoRestrict initialized");
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        return EventTask.withContinuation(continuation -> {
            String ip = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
            String name = event.getPlayer().getUsername();
            boolean bypass = event.getPlayer().hasPermission("georestrict.bypass");

            service.checkIp(ip, name, bypass).thenAccept(result -> {
                if (!result.allowed) {
                    event.setResult(LoginEvent.ComponentResult.denied(Component.text(result.reason)));
                }
                continuation.resume();
            });
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        // Notify admins of available updates
        if (updateChecker != null && updateChecker.isUpdateAvailable()) {
            Player player = event.getPlayer();
            if (player.hasPermission("georestrict.admin")) {
                player.sendMessage(Component.text("Â§7[Â§bGeoRestrictÂ§7] A new version is available: Â§b" + updateChecker.getLatestVersion()));
            }
        }
    }
}

