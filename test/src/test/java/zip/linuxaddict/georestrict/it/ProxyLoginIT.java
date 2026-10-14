package zip.linuxaddict.georestrict.it;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.proxy.Player;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.GeoCache;
import zip.linuxaddict.georestrict.GeoConfig;
import zip.linuxaddict.georestrict.GeoRestrictService;
import zip.linuxaddict.georestrict.bungee.GeoRestrictBungeePlugin;
import zip.linuxaddict.georestrict.velocity.GeoRestrictVelocityPlugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyLoginIT {
    @TempDir Path directory;
    private final Logger logger = LoggerFactory.getLogger("ProxyLoginIT");
    private MockGateway gateway;
    private GeoConfig config;
    private GeoCache cache;
    private GeoRestrictService service;
    private GeoRestrictBungeePlugin bungee;
    private GeoRestrictVelocityPlugin velocity;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new MockGateway()
            .with("1.2.3.4", "US", "7922", "Comcast", false, false, false)
            .with("5.6.7.8", "RU", "12389", "Rostelecom", false, false, false);
        config = new GeoConfig();
        config.gatewayUrl = gateway.baseUrl();
        config.blockLocalConnections = true;
        config.vpnCheckEnabled = false;
        config.countries = List.of("RU");
        cache = new GeoCache(directory.resolve("cache.json").toFile(), new com.google.gson.Gson(), logger);
        service = new GeoRestrictService(config, logger, cache);
        bungee = mock(GeoRestrictBungeePlugin.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        setField(bungee, "service", service);
        setField(bungee, "config", config);
        setField(bungee, "log", logger);
        velocity = new GeoRestrictVelocityPlugin(null, logger, directory, null);
        setField(velocity, "service", service);
        setField(velocity, "config", config);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
        if (cache != null) cache.shutdown();
        if (gateway != null) gateway.close();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void checkBungee(String ip, boolean blocked) throws Exception {
        PendingConnection connection = mock(PendingConnection.class);
        when(connection.getAddress()).thenReturn(new InetSocketAddress(ip, 25565));
        when(connection.getName()).thenReturn("tester");
        when(connection.getUniqueId()).thenReturn(UUID.randomUUID());
        LoginEvent event = mock(LoginEvent.class);
        when(event.getConnection()).thenReturn(connection);
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(invocation -> { completed.countDown(); return null; }).when(event).completeIntent(bungee);
        bungee.onLogin(event);
        assertTrue(completed.await(3, TimeUnit.SECONDS), "Bungee login intent must complete");
        verify(event).registerIntent(bungee);
        verify(event).completeIntent(bungee);
        if (blocked) verify(event).setCancelled(true);
        else verify(event, never()).setCancelled(true);
    }

    private void checkVelocity(String ip, boolean blocked) throws Exception {
        Player player = mock(Player.class);
        when(player.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 25565));
        when(player.getUsername()).thenReturn("tester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        com.velocitypowered.api.event.connection.LoginEvent event =
            mock(com.velocitypowered.api.event.connection.LoginEvent.class);
        when(event.getPlayer()).thenReturn(player);
        CountDownLatch completed = new CountDownLatch(1);
        Continuation continuation = mock(Continuation.class);
        doAnswer(invocation -> { completed.countDown(); return null; }).when(continuation).resume();
        velocity.onLogin(event).execute(continuation);
        assertTrue(completed.await(3, TimeUnit.SECONDS), "Velocity login continuation must resume");
        verify(continuation).resume();
        verify(continuation, never()).resumeWithException(any());
        if (blocked) verify(event).setResult(any());
        else verify(event, never()).setResult(any());
    }

    @Test
    void bungeeRejectsLocalAndPreservesPublicJoins() throws Exception {
        checkBungee("127.0.0.1", true);
        checkBungee("1.2.3.4", false);
        checkBungee("5.6.7.8", true);
    }

    @Test
    void velocityRejectsLocalAndPreservesPublicJoins() throws Exception {
        checkVelocity("::1", true);
        checkVelocity("1.2.3.4", false);
        checkVelocity("5.6.7.8", true);
    }

    @Test
    void proxiesRetainLocalAccessWhenBlockingIsDisabled() throws Exception {
        config.blockLocalConnections = false;
        checkBungee("192.168.1.1", false);
        checkVelocity("fd00::1", false);
    }

    @Test
    void localCountryEntryWorksOnBothProxies() throws Exception {
        config.blockLocalConnections = false;
        config.countries = List.of("LOCAL", "RU");
        checkBungee("127.0.0.1", true);
        checkVelocity("::1", true);
    }
}
