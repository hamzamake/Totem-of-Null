package dev.nulltotem.mixin;

import dev.nulltotem.FakeSoldier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** No "<name> joined the game" for each of the up to 20 soldiers a summon brings in. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void nulltotem$quietSoldierJoin(PlayerList list, Component message, boolean overlay) {
        if (!FakeSoldier.joining) {
            list.broadcastSystemMessage(message, overlay);
        }
    }
}
