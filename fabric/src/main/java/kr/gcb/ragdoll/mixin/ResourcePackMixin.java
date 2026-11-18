package kr.gcb.ragdoll.mixin;

import kr.gcb.ragdoll.RemoteRagdollFabric;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A client that has just finished loading the pack has to be sent the bodies again - it drew them without their models. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ResourcePackMixin {
    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void remoteragdoll$packLoaded(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        if (packet.action() == ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                && (Object) this instanceof ServerGamePacketListenerImpl game) {
            RemoteRagdollFabric.onPackLoaded(game.player);
        }
    }
}
