package zip.linuxaddict.georestrict.geyser;

import com.google.gson.Gson;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.connection.ConnectionRequestEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.*;

public final class GeoRestrictGeyser implements Extension {
    private final Logger log = LoggerFactory.getLogger("GeoRestrict-Geyser");
    private volatile GeoRestrictService service;
    private GeoCache cache;
    private ScheduledExecutorService watcher;
    private Path config;
    private volatile long modified;

    @Subscribe public void initialize(GeyserPostInitializeEvent event) {
        try { Files.createDirectories(dataFolder()); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot create Geyser GeoRestrict data folder", error); }
        config = dataFolder().resolve("config.yml");
        cache = new GeoCache(dataFolder().resolve("geo_cache.json").toFile(), new Gson(), log);
        cache.load();service = new GeoRestrictService(ConfigLoader.load(config.toFile()), log, cache);
        modified = config.toFile().lastModified();
        watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "GeoRestrict-GeyserConfig");thread.setDaemon(true);return thread;
        });
        watcher.scheduleAtFixedRate(() -> {
            try {
                if (config.toFile().lastModified() != modified) {
                    service.setConfig(ConfigLoader.load(config.toFile()));modified = config.toFile().lastModified();
                    log.info("GeoRestrict Geyser config changed, reloaded.");
                }
            } catch (RuntimeException error) { log.error("Geyser configuration reload failed", error); }
        }, 2, 2, TimeUnit.SECONDS);
        log.info("GeoRestrict Geyser local-admission guard enabled v{}", PluginInfo.VERSION);
    }

    @Subscribe public void request(ConnectionRequestEvent event) {
        if (event.isCancelled() || service == null) return;
        InetSocketAddress source = event.proxyIp() != null ? event.proxyIp() : event.inetSocketAddress();
        if (source.getAddress() == null) return;
        GeoConfig settings = service.getConfig();
        GeoRestrictService.CheckResult result = service.checkLocalIp(source.getAddress().getHostAddress(), "Bedrock connection", null,
            settings.floodgate.enabled && settings.floodgate.bypassGeorestrict);
        if (result != null && !result.allowed) event.setCancelled(true);
    }

    @Subscribe public void login(SessionLoginEvent event) {
        if (event.isCancelled() || service == null) return;
        GeoConfig settings = service.getConfig();
        try {
            Object address = event.connection().getClass().getMethod("getSocketAddress").invoke(event.connection());
            if (!(address instanceof InetSocketAddress source) || source.getAddress() == null) return;
            GeoRestrictService.CheckResult result = service.checkLocalIp(source.getAddress().getHostAddress(),
                event.connection().bedrockUsername(), event.connection().javaUuid(),
                settings.floodgate.enabled && settings.floodgate.bypassGeorestrict);
            if (result != null && !result.allowed) event.setCancelled(true, result.reason);
        } catch (ReflectiveOperationException error) {
            log.warn("Cannot resolve Geyser session socket; request-phase guard remains active", error);
        }
    }

    @Subscribe public void shutdown(GeyserShutdownEvent event) {
        if (watcher != null) watcher.shutdownNow();
        if (service != null) service.shutdown();
        if (cache != null) cache.shutdown();
        log.info("GeoRestrict Geyser guard disabled.");
    }
}
