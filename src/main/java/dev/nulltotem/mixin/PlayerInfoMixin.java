package dev.nulltotem.mixin;

import dev.nulltotem.NullTotemMod;
import dev.nulltotem.Soldiers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Null Soldiers are pure black. They have no Mojang account, so no skin is served for them: the client swaps in the
 * mod's own texture for anyone on the soldiers' team (covers the player model and the tab-list head).
 */
@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
    @Unique
    private static final PlayerSkin NULL_SOLDIER_SKIN = new PlayerSkin(
            ResourceLocation.fromNamespaceAndPath(NullTotemMod.MOD_ID, "textures/entity/null_soldier.png"),
            null, null, null, PlayerSkin.Model.WIDE, false);

    @Shadow
    public abstract PlayerTeam getTeam();

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void nulltotem$blackSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerTeam team = getTeam();
        if (team != null && team.getName().equals(Soldiers.TEAM)) {
            cir.setReturnValue(NULL_SOLDIER_SKIN);
        }
    }
}
