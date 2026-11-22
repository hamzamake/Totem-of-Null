package dev.nulltotem.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.nulltotem.FakeSoldier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** No "<name> left the game" for each soldier that's dismissed, killed or erased by a totem pop. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @WrapWithCondition(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private boolean nulltotem$quietSoldierLeave(PlayerList list, Component message, boolean overlay) {
        return !(player instanceof FakeSoldier);
    }
}
