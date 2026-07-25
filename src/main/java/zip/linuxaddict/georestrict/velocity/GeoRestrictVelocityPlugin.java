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
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;
import zip.linuxaddict.georestrict.CommandHandler;
import zip.linuxaddict.georestrict.ConfigLoader;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.PluginInfo;
import zip.linuxaddict.georestrict.UpdateChecker;
import zip.linuxaddict.georestrict.api.GeoRestrictAPI;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

@Plugin(id = "georestrict", name = "GeoRestrict", version = PluginInfo.VERSION,
        authors = {"Demonz Development"},
        description = "Geographic restriction plugin for Minecraft servers")
public class GeoRestrictVelocityPlugin {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Metrics.Factory metricsFactory;
    private GeoRestrictService service;
    private GeoConfig config;
    private GeoCache cache;
    private UpdateChecker updateChecker;
    private CommandHandler command;

    @Inject
    public GeoRestrictVelocityPlugin(ProxyServer server, Logger logger,
                                     @DataDirectory Path dataDirectory,
                                     Metrics.Factory metricsFactory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        logger.info("GeoRestrict v{} starting...", PluginInfo.VERSION);
        metricsFactory.make(this, PluginInfo.BSTATS_VELOCITY);

        File configFile = dataDirectory.resolve("config.yml").toFile();
        config = ConfigLoader.load(configFile);

        File cacheFile = dataDirectory.resolve("geo_cache.json").toFile();
        cache = new GeoCache(cacheFile, new com.google.gson.Gson(), logger);
        cache.load();

        service = new GeoRestrictService(config, logger, cache);
        GeoRestrictAPI.register(service, cache);
        command = new CommandHandler(service, cache, configFile, this::applyConfig);

        registerCommand();
        startConfigWatcher(configFile);
        startUpdateChecker();
        startCacheMaintenance();
        logger.info("GeoRestrict enabled.");
        logger.info(PluginInfo.COMMUNITY_MESSAGE);
        logger.info(PluginInfo.FEEDBACK_MESSAGE);
    }

    private void applyConfig(GeoConfig fresh, Runnable done) {
        this.config = fresh;
        service.setConfig(fresh);
        done.run();
    }

    private void registerCommand() {
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("georestrict").build(),
            new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                    CommandSource source = invocation.source();
                    String[] args = invocation.arguments();
                    command.execute(
                        new CommandHandler.Sender() {
                            @Override public boolean hasPermission(String p) { return source.hasPermission(p); }
                            @Override public void sendMessage(String m) {
                                source.sendMessage(LEGACY.deserialize(CommandHandler.legacy(m)));
                            }
                        },
                        name -> {
                            Optional<Player> p = server.getPlayer(name);
                            return p.map(player -> player.getRemoteAddress().getAddress().getHostAddress()).orElse(null);
                        },
                        args == null ? new String[0] : args
                    );
                }

                @Override
                public boolean hasPermission(Invocation invocation) {
                    return invocation.source().hasPermission("georestrict.admin");
                }
            }
        );
    }

    private void startConfigWatcher(File configFile) {
        server.getScheduler().buildTask(this, new Runnable() {
            private long lastModified = configFile.lastModified();
            @Override public void run() {
                long now = configFile.lastModified();
                if (now > lastModified) {
                    lastModified = now;
                    logger.info("Config changed, reloading...");
                    GeoConfig fresh = ConfigLoader.load(configFile);
                    applyConfig(fresh, () -> logger.info("Config reloaded."));
                }
            }
        }).repeat(Duration.ofSeconds(5)).schedule();
    }

    private void startUpdateChecker() {
        if (!config.updateCheck) return;
        updateChecker = new UpdateChecker(PluginInfo.VERSION, PluginInfo.MODRINTH_PROJECT);
        server.getScheduler().buildTask(this, () ->
            updateChecker.checkForUpdate().thenAccept(latest -> {
                if (latest != null) logger.info("Update available: {}", latest);
            })).repeat(Duration.ofHours(6)).schedule();
    }

    private void startCacheMaintenance() {
        server.getScheduler().buildTask(this,
            () -> cache.purgeExpired(config.cacheTtlDays))
            .repeat(Duration.ofHours(6))
            .delay(Duration.ofMinutes(5))
            .schedule();
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        return EventTask.withContinuation(continuation -> {
            String ip = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
            String name = event.getPlayer().getUsername();
            java.util.UUID uuid = event.getPlayer().getUniqueId();
            service.checkIp(ip, name, uuid, false).whenComplete((result, error) -> {
                try {
                    if (error != null) {
                        logger.error("Lookup error for {}: {}", name, error.getMessage());
                        if (config.blockOnLookupFailure) {
                            event.setResult(LoginEvent.ComponentResult.denied(
                                LEGACY.deserialize(CommandHandler.legacy(config.kickMessageLookupFailure))));
                        }
                        return;
                    }
                    if (!result.allowed) {
                        String reason = result.reason == null ? "Connection rejected." : result.reason;
                        event.setResult(LoginEvent.ComponentResult.denied(LEGACY.deserialize(CommandHandler.legacy(reason))));
                    }
                } finally {
                    continuation.resume();
                }
            });
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (updateChecker != null && updateChecker.isUpdateAvailable()
            && event.getPlayer().hasPermission("georestrict.admin")) {
            event.getPlayer().sendMessage(LEGACY.deserialize(CommandHandler.legacy(
                "&7[&bGeoRestrict&7] Update available: &b" + updateChecker.getLatestVersion())));
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        GeoRestrictAPI.unregister();
        if (service != null) service.shutdown();
        if (cache != null) {
            cache.shutdown();
            cache.save();
        }
        logger.info("GeoRestrict disabled.");
    }

    public GeoRestrictService getService() {
        return service;
    }

    public GeoCache getCache() {
        return cache;
    }
}
