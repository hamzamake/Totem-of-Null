package dev.nulltotem.mixin;

import dev.nulltotem.FakeSoldier;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Soldiers earn no advancements, so 20 "completed the challenge [Cover Me in Debris]" lines don't flood chat. */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void nulltotem$noSoldierAdvancements(Advancement advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof FakeSoldier) {
            cir.setReturnValue(false);
        }
    }
}
