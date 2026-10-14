package georestrict.live;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.command.SimpleCommand;

@Plugin(id="georestrict-liveprobe", name="GeoRestrictLiveProbe", version="1",
    dependencies={@Dependency(id="georestrict")})
public final class VelocityProbe {
    @Inject private ProxyServer server;
    @Subscribe public void init(ProxyInitializeEvent event) {
        server.getCommandManager().register("geoprobe", (SimpleCommand) invocation -> Probe.run("Velocity", true));
    }
}
