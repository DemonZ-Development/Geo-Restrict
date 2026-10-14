package zip.linuxaddict.georestrict.mod.neoforge;

import net.minecraftforge.fml.common.Mod;
import zip.linuxaddict.georestrict.mod.ServerRuntime;

@Mod("georestrict")
public final class GeoRestrictLegacyNeoForge {
    public GeoRestrictLegacyNeoForge() {
        ServerRuntime.setLoader("NeoForge");
    }
}
