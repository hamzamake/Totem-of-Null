package dev.nulltotem;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;

import java.util.UUID;

public final class TotemHandler {
    static final double VOID_Y = -100;

    static void init() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            // like a vanilla totem, it can't save from /kill or the void
            if (!(entity instanceof ServerPlayer player) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                return true;
            }
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack held = player.getItemInHand(hand);
                if (ModItems.isCurrent(held, player.level().getServer())) {
                    pop(player, held);
                    return false;
                }
            }
            return true;
        });
    }

    private static void pop(ServerPlayer player, ItemStack held) {
        ItemStack used = held.copy();
        held.shrink(1);
        // the rest is what vanilla's LivingEntity.checkTotemDeathProtection does for a Totem of Undying
        player.awardStat(Stats.ITEM_USED.get(ModItems.TOTEM_OF_NULL));
        CriteriaTriggers.USED_TOTEM.trigger(player, used);
        player.setHealth(1);
        DeathProtection.TOTEM_OF_UNDYING.applyEffects(used, player);
        player.level().broadcastEntityEvent(player, EntityEvent.PROTECTED_FROM_DEATH);
        int erased = erase(player);
        int soldiers = SoldierHandler.dismissAll(player.level().getServer());
        player.sendSystemMessage(Component.literal("NULL: " + erased + " tagged player(s) erased, " + soldiers
                + " Null Soldier(s) returned to the void").withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** The NULL effect: every tagged player online, in any dimension, is dropped into that dimension's void and killed. */
    private static int erase(ServerPlayer saved) {
        MinecraftServer server = saved.level().getServer();
        int count = 0;
        for (UUID id : NullServerState.get(server).tagged) {
            ServerPlayer victim = server.getPlayerList().getPlayer(id);
            if (victim == null || victim == saved || !victim.isAlive()) {
                continue;
            }
            victim.stopRiding();
            victim.teleportTo(victim.getX(), VOID_Y, victim.getZ());
            victim.hurtServer(victim.level(), victim.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            count++;
        }
        return count;
    }
}
