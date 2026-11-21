package dev.nulltotem;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Every touch of Carpet's internals lives here, so a Carpet update only breaks this file. */
public final class CarpetIntegration {
    /** Same as {@code /player <name> spawn at <pos>}. Asynchronous: the player joins a tick or more later. */
    public static boolean spawn(String name, ServerPlayer near, Vec3 pos) {
        return EntityPlayerMPFake.createFake(name, near.level().getServer(), pos, near.getYRot(), 0,
                near.level().dimension(), GameType.SURVIVAL, false);
    }

    public static boolean isSpawning(String name) {
        return EntityPlayerMPFake.isSpawningPlayer(name);
    }

    public static boolean isFake(ServerPlayer player) {
        return player instanceof EntityPlayerMPFake;
    }

    /** Same as {@code /player <name> kill}: disconnects the bot (no death, no drops). */
    public static void remove(ServerPlayer player, Component reason) {
        if (player instanceof EntityPlayerMPFake fake) {
            fake.kill(reason);
        }
    }

    public static EntityPlayerActionPack actions(ServerPlayer player) {
        return ((ServerPlayerInterface) player).getActionPack();
    }

    /** Same as {@code /player <name> attack once}: one swing at whatever the bot is looking at. */
    public static void attackOnce(ServerPlayer player) {
        actions(player).start(EntityPlayerActionPack.ActionType.ATTACK, EntityPlayerActionPack.Action.once());
    }

    /** Ends a running attack/dig (starting an action type with null only stops the old one). */
    public static void stopAttack(ServerPlayer player) {
        actions(player).start(EntityPlayerActionPack.ActionType.ATTACK, null);
    }

    /** Same as {@code /player <name> use continuous}: holds right-click, e.g. to eat what's in the main hand. */
    public static void holdUse(ServerPlayer player) {
        actions(player).start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.continuous());
    }

    public static void stopUse(ServerPlayer player) {
        actions(player).start(EntityPlayerActionPack.ActionType.USE, null);
    }

    public static void jump(ServerPlayer player) {
        actions(player).start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
    }
}
