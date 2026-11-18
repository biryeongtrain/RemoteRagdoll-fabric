package kr.gcb.ragdoll.mixin;

import kr.gcb.ragdoll.RemoteRagdollFabric;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A swing of the arm (Fabric API has no event for one): that is what kicks a body. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PunchMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At("TAIL"))
    private void remoteragdoll$swing(ServerboundPunchPacket packet, CallbackInfo ci) {
        // TAIL is only reached on the server thread: off it, the handler leaves by exception to reschedule itself.
        RemoteRagdollFabric.onSwing(player);
    }
}
