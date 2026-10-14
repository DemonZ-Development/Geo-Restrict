package georestrict.live;

import org.bukkit.plugin.java.JavaPlugin;
import zip.linuxaddict.georestrict.GeoRestrictService;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import java.net.InetAddress;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;


public final class BukkitProbe extends JavaPlugin implements Listener {
    private final ConcurrentHashMap<String, InetAddress> addresses = new ConcurrentHashMap<>();
    private final java.util.Set<String> bypasses = ConcurrentHashMap.newKeySet();
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("geoprobe").setExecutor((sender, command, label, args) -> {
            if (args.length == 2 && args[0].equals("bypass")) {
                bypasses.add(args[1]); sender.sendMessage("GEO_LIVE_BYPASS " + args[1]); return true;
            }
            if (args.length == 3 && args[0].equals("address")) {
                try {
                    if (!args[2].matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Literal IP required");
                    addresses.put(args[1], InetAddress.getByName(args[2]));
                    sender.sendMessage("GEO_LIVE_ADDRESS " + args[1] + " " + args[2]);
                } catch (Exception error) { throw new IllegalArgumentException("Cannot prepare test address", error); }
                return true;
            }
            Probe.run("Bukkit", getServer().getServicesManager().load(GeoRestrictService.class) != null);
            return true;
        });
    }

    private void rewrite(Object event, String name, InetAddress real) {
        InetAddress address = addresses.get(name);
        if (address == null) return;
        if (!real.isLoopbackAddress()) throw new IllegalStateException("Address fixture accepts loopback clients only");
        try {
            int count = 0;
            for (Field field : event.getClass().getDeclaredFields()) {
                if (field.getType() == InetAddress.class) {
                    field.setAccessible(true); field.set(event, address); count++;
                }
            }
            if (count == 0) throw new IllegalStateException("No event address field");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot apply test address", error); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void asyncAddress(AsyncPlayerPreLoginEvent event) {
        getLogger().info("GEO_LIVE_ASYNC " + event.getName() + " " + event.getUniqueId());
        rewrite(event, event.getName(), event.getAddress());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void loginAddress(PlayerLoginEvent event) {
        if (bypasses.contains(event.getPlayer().getName())) {
            event.getPlayer().addAttachment(this, "georestrict.bypass", true);
        }
        getLogger().info("GEO_LIVE_LOGIN " + event.getPlayer().getName() + " " + event.getPlayer().getUniqueId()
            + " op=" + event.getPlayer().isOp() + " bypass=" + event.getPlayer().hasPermission("georestrict.bypass"));
        rewrite(event, event.getPlayer().getName(), event.getAddress());
    }
}
