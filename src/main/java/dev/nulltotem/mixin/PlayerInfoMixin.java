package dev.nulltotem.mixin;

import dev.nulltotem.NullTotemMod;
import dev.nulltotem.Soldiers;
import net.minecraft.client.multiplayer.PlayerInfo;
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
 * mod's own texture (classic arms) for anyone on the soldiers' team (covers the player model and the tab-list head).
 */
@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
    @Unique
    private static final ResourceLocation NULL_SOLDIER_SKIN = new ResourceLocation(NullTotemMod.MOD_ID, "textures/entity/null_soldier.png");

    @Shadow
    public abstract PlayerTeam getTeam();

    @Unique
    private boolean nulltotem$isSoldier() {
        PlayerTeam team = getTeam();
        return team != null && team.getName().equals(Soldiers.TEAM);
    }

    @Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
    private void nulltotem$blackSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        if (nulltotem$isSoldier()) {
            cir.setReturnValue(NULL_SOLDIER_SKIN);
        }
    }

    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void nulltotem$classicModel(CallbackInfoReturnable<String> cir) {
        if (nulltotem$isSoldier()) {
            cir.setReturnValue("default");
        }
    }
}
