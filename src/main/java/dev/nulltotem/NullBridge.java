package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sneak + left-click with the totem in the main hand: a soldier builds a 1-wide netherrack walkway from your feet to the
 * spot you look at (up to 64 blocks; looking at the sky, it runs level ahead of you). The closest soldier is the
 * builder: it walks out along its own bridge crouching (so it can't walk off the edge) and places one block at a time.
 * Only empty space, liquids and plants get filled, never a real block, and never a spot an entity is standing in.
 */
final class NullBridge {
    private static final int RANGE = 64;
    private static final int PLACE_INTERVAL = 4; // ticks between blocks: 5 a second, like a quick player
    private static final int STUCK = 200;        // ticks without placing before the builder skips that block
    private static final double REACH = 4;

    private static final class Job {
        final ResourceKey<Level> dimension;
        final List<BlockPos> blocks;
        UUID builder;
        int next;
        int placed;
        int lastProgress;

        Job(ResourceKey<Level> dimension, List<BlockPos> blocks, UUID builder, int now) {
            this.dimension = dimension;
            this.blocks = blocks;
            this.builder = builder;
            this.lastProgress = now;
        }
    }

    private static final Map<UUID, Job> jobs = new HashMap<>(); // master -> bridge being built

    /** Plans the bridge and hands it to the master's closest soldier; returns how many blocks it'll be (0 = nothing to do). */
    static int start(ServerPlayer master) {
        MinecraftServer server = master.getServer();
        ServerPlayer builder = Soldiers.soldiersOf(server, master.getUUID()).stream()
                .filter(s -> s.level() == master.level())
                .min(Comparator.comparingDouble(master::distanceToSqr)).orElse(null);
        if (builder == null) {
            return -1;
        }
        List<BlockPos> blocks = plan(master);
        if (blocks.isEmpty()) {
            return 0;
        }
        jobs.put(master.getUUID(), new Job(master.level().dimension(), blocks, builder.getUUID(), server.getTickCount()));
        for (BlockPos pos : blocks) { // show where it'll go
            master.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0);
        }
        return blocks.size();
    }

    static void cancel(UUID master) {
        jobs.remove(master);
    }

    static void reset() {
        jobs.clear();
    }

    @Nullable
    static String status(UUID master) {
        Job job = jobs.get(master);
        return job == null ? null : "building a bridge (" + job.placed + " of " + job.blocks.size() + " blocks)";
    }

    /** The cells of the walkway, from your feet outwards, that are empty right now. */
    private static List<BlockPos> plan(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        HitResult hit = player.pick(RANGE, 0, false);
        Vec3 end = hit.getType() == HitResult.Type.MISS
                ? eye.add(player.getViewVector(1).scale(RANGE)).subtract(0, player.getEyeHeight(), 0)
                : hit.getLocation();
        Vec3 start = player.position();
        double length = start.distanceTo(end);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (double t = 0; length >= 1 && t <= length; t += 0.25) {
            Vec3 point = start.add(end.subtract(start).scale(t / length));
            BlockPos pos = BlockPos.containing(point.x, point.y - 0.5, point.z); // the block you'd walk on
            if (placeable(level, pos)) {
                cells.add(pos);
            }
        }
        return new ArrayList<>(cells);
    }

    private static boolean placeable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced()
                && level.isUnobstructed(Blocks.NETHERRACK.defaultBlockState(), pos, CollisionContext.empty());
    }

    /** Gives the job a new builder if its one is gone (died, dismissed). */
    static void check(MinecraftServer server) {
        jobs.forEach((masterId, job) -> {
            if (server.getPlayerList().getPlayer(job.builder) == null) {
                Soldiers.soldiersOf(server, masterId).stream().findFirst().ifPresent(s -> job.builder = s.getUUID());
            }
        });
    }

    /** One tick of the builder's work. False when this soldier isn't building a bridge right now. */
    static boolean work(ServerPlayer bot, UUID masterId, @Nullable ServerPlayer master, int now) {
        Job job = jobs.get(masterId);
        if (job == null || !bot.getUUID().equals(job.builder)) {
            return false;
        }
        FakeSoldier actions = (FakeSoldier) bot;
        ServerLevel level = bot.serverLevel();
        if (level.dimension() != job.dimension) {
            jobs.remove(masterId);
            return false;
        }
        if (job.next < job.blocks.size() && now - job.lastProgress > STUCK) {
            job.next++; // couldn't get to this one: go on with the next
            job.lastProgress = now;
        }
        while (job.next < job.blocks.size() && !level.getBlockState(job.blocks.get(job.next)).canBeReplaced()) {
            job.next++; // filled in some other way already
        }
        if (job.next >= job.blocks.size()) {
            jobs.remove(masterId);
            actions.setSneaking(false);
            if (master != null) {
                master.displayClientMessage(Component.literal("Your Null Soldier finished the bridge: " + job.placed + " block(s) of netherrack")
                        .withStyle(ChatFormatting.DARK_PURPLE), true);
            }
            return false;
        }
        BlockPos next = job.blocks.get(job.next);
        Soldiers.selectSlot(bot, SoldierGear.NETHERRACK_SLOT);
        actions.setSprinting(false);
        Vec3 center = Vec3.atCenterOf(next);
        // like a player bridging: walks the finished part, crouches at the edge so it can't step off it
        actions.setSneaking(bot.getEyePosition().distanceTo(center) <= REACH + 1);
        if (bot.getEyePosition().distanceTo(center) <= REACH) {
            actions.setForward(0);
            actions.setStrafing(0);
            actions.lookAt(center);
            // someone in the way (another soldier, a mob) just makes it wait; STUCK skips the block if they never move
            if (now - job.lastProgress >= PLACE_INTERVAL && level.isUnobstructed(Blocks.NETHERRACK.defaultBlockState(), next, CollisionContext.empty())) {
                BlockState netherrack = Blocks.NETHERRACK.defaultBlockState();
                level.setBlockAndUpdate(next, netherrack);
                level.playSound(null, next, SoundEvents.NETHERRACK_PLACE, SoundSource.BLOCKS, 1, 0.8f);
                bot.swing(InteractionHand.MAIN_HAND);
                job.next++;
                job.placed++;
                job.lastProgress = now;
            }
        } else {
            // walk out to the end of what's built so far (or to the first block's spot)
            BlockPos last = job.next > 0 ? job.blocks.get(job.next - 1) : null;
            Vec3 goal = last != null && level.getBlockState(last).is(Blocks.NETHERRACK) ? Vec3.atBottomCenterOf(last.above()) : center;
            Soldiers.walkTo(bot, goal, 0.5);
            actions.setSprinting(false);
        }
        return true;
    }
}
