package zip.linuxaddict.georestrict.sponge;

import com.google.gson.Gson;
import com.google.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;
import zip.linuxaddict.georestrict.*;
import zip.linuxaddict.georestrict.api.GeoRestrictAPI;

@Plugin("georestrict")
public final class GeoRestrictSponge {
    private final PluginContainer plugin;
    private final Path config;
    private final Logger logger = LoggerFactory.getLogger("GeoRestrict");
    private final GeoCache cache;
    private final GeoRestrictService service;
    private final CommandHandler commands;
    private final ScheduledExecutorService watcher;
    private volatile long modified;

    @Inject public GeoRestrictSponge(PluginContainer plugin, @ConfigDir(sharedRoot = false) Path directory) {
        this.plugin = plugin;
        try { Files.createDirectories(directory); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot create GeoRestrict configuration", error); }
        config = directory.resolve("config.yml");
        GeoConfig settings = ConfigLoader.load(config.toFile());
        modified = config.toFile().lastModified();
        cache = new GeoCache(directory.resolve("geo_cache.json").toFile(), new Gson(), logger);
        cache.load();
        service = new GeoRestrictService(settings, logger, cache);
        GeoRestrictAPI.register(service, cache);
        commands = new CommandHandler(service, cache, config.toFile(), (fresh, reply) -> {
            service.setConfig(fresh);
            modified = config.toFile().lastModified();
            onServerThread(reply);
        });
        watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "GeoRestrict-SpongeConfig");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleAtFixedRate(this::maintain, 2, 2, TimeUnit.SECONDS);
        logger.info("GeoRestrict Sponge enabled v{}; config {}", PluginInfo.VERSION, config);
    }

    private void onServerThread(Runnable action) {
        Sponge.server().scheduler().submit(Task.builder().plugin(plugin).execute(action).build());
    }

    private void maintain() {
        try {
            if (config.toFile().lastModified() != modified) {
                service.setConfig(ConfigLoader.load(config.toFile()));
                modified = config.toFile().lastModified();
                logger.info("GeoRestrict config changed, reloaded.");
            }
            cache.purgeExpired(service.getConfig().cacheTtlDays);
        } catch (RuntimeException error) { logger.error("GeoRestrict maintenance failed", error); }
    }

    @Listener(order = Order.LAST) public void authenticate(ServerSideConnectionEvent.Auth event) {
        if (event.isCancelled()) return;
        String ip = event.connection().address().getAddress().getHostAddress();
        try {
            boolean bypass = Sponge.server().userManager().load(event.profile().uniqueId()).join()
                .map(user -> user.hasPermission("georestrict.bypass")).orElse(false);
            GeoRestrictService.CheckResult result = service.checkIp(ip,
                event.profile().name().orElse("Unknown"), event.profile().uniqueId(), bypass)
                .orTimeout(Math.min(25_000L, Math.max(5_000L,
                    service.getConfig().connectionTimeoutMs * 2L + 2_000L)), TimeUnit.MILLISECONDS).join();
            if (!result.allowed) {
                event.setCancelled(true);
                event.setMessage(Component.text(result.reason == null ? "Connection denied." : result.reason));
            }
        } catch (RuntimeException error) {
            logger.warn("GeoRestrict login lookup failed", error);
            if (service.getConfig().blockOnLookupFailure) {
                event.setCancelled(true);
                event.setMessage(Component.text(service.getConfig().kickMessageLookupFailure));
            }
        }
    }

    @Listener public void register(RegisterCommandEvent<Command.Parameterized> event) {
        Parameter.Value<String> arguments = Parameter.remainingJoinedStrings().key("arguments").optional().build();
        event.register(plugin, Command.builder().permission("georestrict.admin").addParameter(arguments)
            .executor(context -> {
                commands.execute(new CommandHandler.Sender() {
                    public boolean hasPermission(String permission) { return context.cause().hasPermission(permission); }
                    public void sendMessage(String message) {
                        onServerThread(() -> context.cause().sendMessage(Component.text(message.replaceAll("\u00a7.", ""))));
                    }
                }, name -> Sponge.server().player(name).map(player ->
                    player.connection().address().getAddress().getHostAddress()).orElse(null),
                    context.one(arguments).map(value -> value.trim().split("\\s+")).orElseGet(() -> new String[0]));
                return CommandResult.success();
            }).build(), "georestrict", "gr");
    }

    @Listener public void stop(StoppingEngineEvent<Server> event) {
        watcher.shutdownNow();
        service.shutdown();
        cache.shutdown();
        GeoRestrictAPI.unregister();
        logger.info("GeoRestrict Sponge disabled; cache flushed.");
    }
}
