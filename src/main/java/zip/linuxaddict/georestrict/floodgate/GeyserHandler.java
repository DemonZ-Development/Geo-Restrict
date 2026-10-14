package zip.linuxaddict.georestrict.floodgate;

import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.util.UUID;

public final class GeyserHandler {
    private static final String API = "org.geysermc.geyser.api.GeyserApi";
    private GeyserHandler() {}

    private static Object connection(UUID uuid) {
        if (uuid == null) return null;
        try {
            Class<?> api = Class.forName(API);
            Object instance = api.getMethod("api").invoke(null);
            return instance == null ? null : api.getMethod("connectionByUuid", UUID.class).invoke(instance, uuid);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean isBedrockPlayer(UUID uuid) { return connection(uuid) != null; }

    public static String playerIp(UUID uuid, String fallback) {
        Object session = connection(uuid);
        if (session == null) return fallback;
        try {
            Method addressMethod = session.getClass().getMethod("getSocketAddress");
            Object address = addressMethod.invoke(session);
            if (address instanceof InetSocketAddress socket && socket.getAddress() != null) {
                return socket.getAddress().getHostAddress();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return fallback;
    }

    public static FloodgateHandler.BedrockPlayerInfo getBedrockInfo(UUID uuid) {
        Object session = connection(uuid);
        if (session == null) return null;
        try {
            Class<?> type = Class.forName("org.geysermc.geyser.api.connection.GeyserConnection");
            Object xuid = type.getMethod("xuid").invoke(session);
            Object platform = type.getMethod("platform").invoke(session);
            return new FloodgateHandler.BedrockPlayerInfo(xuid == null ? "N/A" : xuid.toString(),
                platform == null ? "UNKNOWN" : platform.toString());
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
