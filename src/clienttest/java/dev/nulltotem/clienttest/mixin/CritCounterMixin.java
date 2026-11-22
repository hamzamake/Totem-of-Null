package dev.nulltotem.clienttest.mixin;

import dev.nulltotem.clienttest.Harness;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts critical hits (the server's CRITICAL_HIT animation; only reached once, on the render thread). */
@Mixin(ClientPacketListener.class)
abstract class CritCounterMixin {
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void nulltotemtest$countCrit(ClientboundAnimatePacket packet, CallbackInfo ci) {
        if (packet.getAction() == ClientboundAnimatePacket.CRITICAL_HIT) {
            Harness.crits++;
        }
    }
}
