package georestrict.live.mixin;
import georestrict.live.Fixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.server.MinecraftServer",remap=false)
public abstract class ServerFixtureMixin {
 @Inject(method={"runServer","m_130011_","method_29741"},at=@At("HEAD"),remap=false)
 private void liveStart(CallbackInfo ci) {Fixture.register(this);}
}
