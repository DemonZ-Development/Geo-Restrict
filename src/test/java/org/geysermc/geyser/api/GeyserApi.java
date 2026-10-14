package org.geysermc.geyser.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.geysermc.geyser.api.connection.GeyserConnection;

public final class GeyserApi {
    private static final GeyserApi INSTANCE = new GeyserApi();
    public static final Map<UUID, GeyserConnection> CONNECTIONS = new ConcurrentHashMap<>();
    public static GeyserApi api() { return INSTANCE; }
    public GeyserConnection connectionByUuid(UUID uuid) { return CONNECTIONS.get(uuid); }
}
