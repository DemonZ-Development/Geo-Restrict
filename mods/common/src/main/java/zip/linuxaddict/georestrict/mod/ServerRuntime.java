package zip.linuxaddict.georestrict.mod;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

public final class ServerRuntime {
    private static final Map<Object, ServerRuntime> SERVERS = new ConcurrentHashMap<>();
    private static volatile String loader = "server mod";
    private static volatile Path configDirectory = Path.of("config");
    private final Object server;
    private final Logger logger = LoggerFactory.getLogger("GeoRestrict");
    private final File configFile;
    private final GeoCache cache;
    private final GeoRestrictService service;
    private final CommandHandler commands;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean permissionFailureLogged = new AtomicBoolean();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "GeoRestrict-ModMaintenance");
        thread.setDaemon(true);
        return thread;
    });
    private Object registeredDispatcher;
    private long modified;
    private final Thread shutdownHook;

    private ServerRuntime(Object server) {
        this.server = server;
        if (!(server instanceof Executor)) throw new IllegalStateException("Minecraft server must provide its main-thread executor");
        Path directory = configDirectory.resolve("georestrict");
        try { Files.createDirectories(directory); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot create GeoRestrict config directory", error); }
        configFile = directory.resolve("config.yml").toFile();
        GeoConfig config = ConfigLoader.load(configFile);
        modified = configFile.lastModified();
        cache = new GeoCache(directory.resolve("geo_cache.json").toFile(), new Gson(), logger);
        cache.load();
        service = new GeoRestrictService(config, logger, cache);
        commands = new CommandHandler(service, cache, configFile, (fresh, reply) -> {
            applyConfig(fresh);
            ((Executor) server).execute(reply);
        });
        GeoRestrictAPI.register(service, cache);
        shutdownHook = new Thread(this::close, "GeoRestrict-ModShutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        maintenance.scheduleAtFixedRate(this::maintain, 1, 2, TimeUnit.SECONDS);
        maintenance.scheduleAtFixedRate(() -> cache.purgeExpired(config().cacheTtlDays), 5, 600, TimeUnit.SECONDS);
        if (config.updateCheck) {
            UpdateChecker checker = new UpdateChecker(PluginInfo.VERSION, PluginInfo.MODRINTH_PROJECT);
            maintenance.scheduleAtFixedRate(() -> checker.checkForUpdate().thenAccept(latest -> {
                if (latest != null) logger.info("Update available: {}", latest);
            }), 5, 21_600, TimeUnit.SECONDS);
        }
        logger.info("GeoRestrict mod enabled: {} v{}; config {}", loader, PluginInfo.VERSION, configFile);
    }

    public static void setLoader(String name) {
        loader = name;
        for (String className : new String[]{"net.neoforged.fml.loading.FMLPaths", "net.minecraftforge.fml.loading.FMLPaths"}) {
            try {
                Class<?> paths = Class.forName(className);
                Object value = paths.getField("CONFIGDIR").get(null);
                configDirectory = (Path) paths.getMethod("get").invoke(value);
                break;
            } catch (ClassNotFoundException ignored) {
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot read loader config directory", error);
            }
        }
    }

    public static void setConfigDirectory(Path directory) { configDirectory = directory; }
    public static void start(Object server) { SERVERS.computeIfAbsent(server, ServerRuntime::new); }
    public static ServerRuntime get(Object server) { return SERVERS.get(server); }
    public static void stop(Object server) {
        ServerRuntime runtime = SERVERS.remove(server);
        if (runtime != null) runtime.close();
    }

    public GeoConfig config() { return service.getConfig(); }
    public GeoRestrictService service() { return service; }
    public Logger logger() { return logger; }
    public CommandHandler commands() { return commands; }

    public boolean canAdminister(Object source) {
        try { return GameAccess.isAdmin(server, source); }
        catch (RuntimeException error) {
            if (permissionFailureLogged.compareAndSet(false, true)) {
                logger.error("GeoRestrict cannot resolve native command permissions; denying command access", error);
            }
            return false;
        }
    }

    private synchronized void applyConfig(GeoConfig fresh) {
        service.setConfig(fresh);
        modified = configFile.lastModified();
    }

    private void maintain() {
        if (closed.get()) return;
        try {
            if (configFile.lastModified() != modified) {
                applyConfig(ConfigLoader.load(configFile));
                logger.info("GeoRestrict config changed, reloaded.");
            }
            ((Executor) server).execute(() -> {
                if (closed.get()) return;
                Object dispatcher = GameAccess.commandDispatcher(server);
                if (dispatcher != registeredDispatcher) {
                    NativeCommands.register(server, this, dispatcher);
                    registeredDispatcher = dispatcher;
                    logger.info("GeoRestrict native commands registered.");
                }
            });
        } catch (RuntimeException error) {
            logger.error("GeoRestrict maintenance failed", error);
        }
    }

    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        maintenance.shutdownNow();
        service.shutdown();
        cache.shutdown();
        GeoRestrictAPI.unregister();
        SERVERS.remove(server, this);
        if (Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) {}
        }
        logger.info("GeoRestrict mod disabled; cache flushed.");
    }
}
