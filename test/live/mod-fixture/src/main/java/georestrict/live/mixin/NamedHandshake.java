package georestrict.live.mixin;
import georestrict.live.Fixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.server.network.ServerHandshakePacketListenerImpl",remap=false)
public abstract class NamedHandshake {
 @Inject(method={"handleIntention", "m_7322_"},at=@At("HEAD"),remap=false)
 private void liveForward(@Coerce Object packet,CallbackInfo ci) throws Exception {Fixture.forward(this,packet);}
}
