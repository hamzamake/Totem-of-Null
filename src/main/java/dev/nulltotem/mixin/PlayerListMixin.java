package dev.nulltotem.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.nulltotem.FakeSoldier;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No "<name> joined the game" for each of the up to 20 soldiers a summon brings in. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapWithCondition(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private boolean nulltotem$quietSoldierJoin(PlayerList list, Component message, boolean overlay,
                                               Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        return !(player instanceof FakeSoldier);
    }
}
