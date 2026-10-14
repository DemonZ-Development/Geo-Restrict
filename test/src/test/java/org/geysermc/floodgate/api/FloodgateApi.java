package org.geysermc.floodgate.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class FloodgateApi {
    private static final FloodgateApi INSTANCE = new FloodgateApi();
    private final Set<UUID> players = ConcurrentHashMap.newKeySet();

    public static FloodgateApi getInstance() {
        return INSTANCE;
    }

    public boolean isFloodgatePlayer(UUID uuid) {
        return players.contains(uuid);
    }

    public Object getPlayer(UUID uuid) {
        return null;
    }

    public void addPlayer(UUID uuid) {
        players.add(uuid);
    }

    public void removePlayer(UUID uuid) {
        players.remove(uuid);
    }
}
