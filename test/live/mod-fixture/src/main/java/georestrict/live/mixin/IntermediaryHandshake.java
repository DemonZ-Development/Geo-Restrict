package georestrict.live.mixin;
import georestrict.live.Fixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.class_3246",remap=false)
public abstract class IntermediaryHandshake {
 @Inject(method={"method_12576"},at=@At("HEAD"),remap=false)
 private void liveForward(@Coerce Object packet,CallbackInfo ci) throws Exception {Fixture.forward(this,packet);}
}
