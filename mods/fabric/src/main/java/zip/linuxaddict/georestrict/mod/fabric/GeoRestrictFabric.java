package zip.linuxaddict.georestrict.mod.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import zip.linuxaddict.georestrict.mod.ServerRuntime;

public final class GeoRestrictFabric implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerRuntime.setLoader("Fabric");
        ServerRuntime.setConfigDirectory(FabricLoader.getInstance().getConfigDir());
    }
}
