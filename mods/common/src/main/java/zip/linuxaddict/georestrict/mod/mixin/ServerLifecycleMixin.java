package zip.linuxaddict.georestrict.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zip.linuxaddict.georestrict.mod.ServerRuntime;
import zip.linuxaddict.georestrict.mod.GameAccess;

@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
public abstract class ServerLifecycleMixin {
    @Inject(method = {"runServer", "m_130011_", "method_29741"}, at = @At("HEAD"), remap = false)
    private void georestrict$start(CallbackInfo callback) {
        if (GameAccess.isDedicatedServer(this)) ServerRuntime.start(this);
    }

    @Inject(method = {"runServer", "m_130011_", "method_29741"}, at = @At("RETURN"), remap = false)
    private void georestrict$stop(CallbackInfo callback) {
        ServerRuntime.stop(this);
    }
}
