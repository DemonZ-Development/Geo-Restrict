package zip.linuxaddict.georestrict.mod.neoforge;

import net.neoforged.fml.common.Mod;
import zip.linuxaddict.georestrict.mod.ServerRuntime;

@Mod("georestrict")
public final class GeoRestrictNeoForge {
    public GeoRestrictNeoForge() {
        ServerRuntime.setLoader("NeoForge");
    }
}
