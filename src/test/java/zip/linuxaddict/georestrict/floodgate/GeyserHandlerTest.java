package zip.linuxaddict.georestrict.floodgate;

import java.net.InetSocketAddress;
import java.util.UUID;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GeyserHandlerTest {
    public static final class Session implements GeyserConnection {
        public InetSocketAddress getSocketAddress() { return new InetSocketAddress("8.8.8.8", 19132); }
        public String joinAddress() { return "127.0.0.1"; }
        public String xuid() { return "123456789"; }
        public Object platform() { return "ANDROID"; }
    }

    @AfterEach void cleanup() { GeyserApi.CONNECTIONS.clear(); }

    @Test void authenticatedGeyserSessionUsesItsSocketRatherThanClientJoinAddress() {
        UUID uuid = UUID.randomUUID();
        GeyserApi.CONNECTIONS.put(uuid, new Session());
        assertTrue(GeyserHandler.isBedrockPlayer(uuid));
        assertEquals("8.8.8.8", GeyserHandler.playerIp(uuid, "127.0.0.1"));
        assertEquals("123456789", GeyserHandler.getBedrockInfo(uuid).xuid);
        assertEquals("ANDROID", GeyserHandler.getBedrockInfo(uuid).deviceOs);
    }

    @Test void unavailableSessionKeepsThePlatformAddressAndDoesNotClassifyJavaPlayers() {
        UUID uuid = UUID.randomUUID();
        assertFalse(GeyserHandler.isBedrockPlayer(uuid));
        assertFalse(GeyserHandler.isBedrockPlayer(null));
        assertEquals("192.168.1.1", GeyserHandler.playerIp(uuid, "192.168.1.1"));
        assertNull(GeyserHandler.getBedrockInfo(uuid));
    }

    @Test void unknownGeyserSocketHookKeepsForwardedAddress() {
        UUID uuid = UUID.randomUUID();
        GeyserApi.CONNECTIONS.put(uuid, new GeyserConnection() {
            public String xuid() { return "1"; }
            public Object platform() { return "UNKNOWN"; }
        });
        assertEquals("1.1.1.1", GeyserHandler.playerIp(uuid, "1.1.1.1"));
    }
}
