package zip.linuxaddict.georestrict.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zip.linuxaddict.georestrict.mod.LoginGate;

@Mixin(targets = "net.minecraft.class_3248", remap = false)
public abstract class IntermediaryLoginMixin {
    @Unique private final LoginGate georestrict$gate = new LoginGate();

    @Inject(method = "method_18784", at = @At("HEAD"), cancellable = true, remap = false)
    private void georestrict$admit(CallbackInfo callback) {
        if (georestrict$gate.poll(this)) callback.cancel();
    }
}
