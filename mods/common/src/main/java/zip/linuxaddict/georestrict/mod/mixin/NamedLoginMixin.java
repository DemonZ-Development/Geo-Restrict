package zip.linuxaddict.georestrict.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zip.linuxaddict.georestrict.mod.LoginGate;

@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl", remap = false)
public abstract class NamedLoginMixin {
    @Unique private final LoginGate georestrict$gate = new LoginGate();

    @Inject(method = {"tick", "m_9933_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void georestrict$admit(CallbackInfo callback) {
        if (georestrict$gate.poll(this)) callback.cancel();
    }
}
