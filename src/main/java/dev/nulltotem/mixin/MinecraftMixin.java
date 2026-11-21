package dev.nulltotem.mixin;

import dev.nulltotem.ModItems;
import dev.nulltotem.TotemClickPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sneak + left-click while holding the Totem of Null is a soldier order, not an attack: the click goes to the server
 * as a {@link TotemClickPayload} and nothing is hit or mined until the button is let go.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow
    @Nullable
    public LocalPlayer player;

    @Unique
    private boolean nulltotem$clickHeld;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void nulltotem$totemClick(CallbackInfoReturnable<Boolean> cir) {
        if (player == null || !player.isShiftKeyDown() || !ClientPlayNetworking.canSend(TotemClickPayload.TYPE)) {
            return;
        }
        InteractionHand hand = player.getMainHandItem().is(ModItems.TOTEM_OF_NULL) ? InteractionHand.MAIN_HAND
                : player.getOffhandItem().is(ModItems.TOTEM_OF_NULL) ? InteractionHand.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        ClientPlayNetworking.send(new TotemClickPayload());
        player.swing(hand); // visible cue that the click registered
        nulltotem$clickHeld = true;
        cir.setReturnValue(false);
    }

    // holding the button after the click would otherwise start mining the looked-at block (instantly, in creative)
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void nulltotem$ignoreHeldClick(boolean leftClick, CallbackInfo ci) {
        if (nulltotem$clickHeld) {
            if (leftClick) {
                ci.cancel();
            } else {
                nulltotem$clickHeld = false;
            }
        }
    }
}
