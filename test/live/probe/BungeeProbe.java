package georestrict.live;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

public final class BungeeProbe extends Plugin {
    @Override public void onEnable() {
        getProxy().getPluginManager().registerCommand(this, new Command("geoprobe") {
            @Override public void execute(CommandSender sender, String[] args) {
                Probe.run("Bungee", true);
            }
        });
    }
}
