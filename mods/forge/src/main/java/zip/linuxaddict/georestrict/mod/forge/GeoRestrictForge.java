package zip.linuxaddict.georestrict.mod.forge;

import net.minecraftforge.fml.common.Mod;
import zip.linuxaddict.georestrict.mod.ServerRuntime;

@Mod("georestrict")
public final class GeoRestrictForge {
    public GeoRestrictForge() {
        ServerRuntime.setLoader("Forge");
    }
}
