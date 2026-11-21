package dev.nulltotem;

import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "mine" order: every block of the kind the master looked at, within 16 blocks of it, gets mined. Each soldier
 * claims the closest one, tunnels to it (a 2-high tunnel, or straight down when it's below), picks the best tool, and
 * what drops goes straight into the master's inventory. Tunnel blocks just drop where they are.
 */
final class SoldierMining {
    private static final int RADIUS = 16;
    private static final int MAX_BLOCKS = 128;
    private static final int GIVE_UP = 600; // ticks a soldier keeps trying one block before skipping it

    private record Claim(BlockPos pos, int since) {
    }

    private static final class Job {
        final Block block;
        final ResourceKey<Level> dimension;
        final List<BlockPos> blocks;
        final Map<UUID, Claim> claims = new HashMap<>(); // soldier -> the block it's working on
        int mined;

        Job(Block block, ResourceKey<Level> dimension, List<BlockPos> blocks) {
            this.block = block;
            this.dimension = dimension;
            this.blocks = blocks;
        }
    }

    /** A mined target block whose drops get handed to the master on the next tick (they don't exist yet on break). */
    private record Drop(ResourceKey<Level> dimension, BlockPos pos, UUID master) {
    }

    private static final Map<UUID, Job> jobs = new HashMap<>();  // master -> job
    private static final List<Drop> drops = new ArrayList<>();
    /** The block a soldier is breaking and how far along it is (0..1), like a player holding left-click on it. */
    private static final class Breaking {
        final BlockPos pos;
        float progress;

        Breaking(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final Map<UUID, Breaking> breaking = new HashMap<>(); // soldier -> block being broken
    private static final Set<UUID> dugIn = new HashSet<>();               // soldiers that dug down a shaft (see climbOut)

    /** Starts a job for every block like the one at {@code origin}; returns how many were found (0 = can't mine it). */
    static int start(ServerPlayer master, BlockPos origin) {
        ServerLevel level = master.level();
        BlockState state = level.getBlockState(origin);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, origin) < 0) {
            return 0;
        }
        Block block = state.getBlock();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-RADIUS, -RADIUS, -RADIUS), origin.offset(RADIUS, RADIUS, RADIUS))) {
            if (level.getBlockState(pos).is(block)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        List<BlockPos> blocks = new ArrayList<>(found.subList(0, Math.min(MAX_BLOCKS, found.size())));
        jobs.put(master.getUUID(), new Job(block, level.dimension(), blocks));
        return blocks.size();
    }

    static void cancel(UUID master) {
        jobs.remove(master);
    }

    static void reset() {
        jobs.clear();
        breaking.clear();
        dugIn.clear();
        drops.clear();
    }

    @Nullable
    static String status(UUID master) {
        Job job = jobs.get(master);
        return job == null ? null : "mining " + job.block.getName().getString() + " (" + job.mined + " mined, " + job.blocks.size() + " left)";
    }

    /** Stops digging for a moment (the soldier is fighting); its claim stays. */
    static void pause(ServerPlayer bot) {
        stop(bot);
    }

    /** Counts mined target blocks; their drops go to the master on the next tick (see {@link #deliver}). */
    static void onBreak(Player player, BlockPos pos, BlockState state) {
        if (!(player instanceof ServerPlayer bot)) {
            return;
        }
        UUID masterId = SoldierHandler.masterOf(bot);
        Job job = masterId == null ? null : jobs.get(masterId);
        if (job == null || !state.is(job.block)) {
            return;
        }
        job.mined++;
        // Fabric fires this before the block's drops spawn, so pick them up next tick (they can't be picked up for 10)
        drops.add(new Drop(bot.level().dimension(), pos.immutable(), masterId));
    }

    /** Moves the fresh drops of last tick's mined blocks into their masters' inventories (what doesn't fit stays). */
    static void deliver(MinecraftServer server) {
        for (Drop drop : drops) {
            ServerPlayer master = server.getPlayerList().getPlayer(drop.master());
            ServerLevel level = server.getLevel(drop.dimension());
            if (master == null || level == null) {
                continue; // master offline: the drops stay on the ground
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(drop.pos()).inflate(1), i -> i.getAge() <= 5)) {
                master.getInventory().add(item.getItem()); // shrinks the stack by what fits
                if (item.getItem().isEmpty()) {
                    item.discard();
                }
            }
        }
        drops.clear();
    }

    /** One tick of mining for this soldier. False when there's no job (or nothing left for it), so it follows instead. */
    static boolean work(ServerPlayer bot, UUID masterId, @Nullable ServerPlayer master, int now) {
        Job job = jobs.get(masterId);
        UUID id = bot.getUUID();
        if (job == null || bot.level().dimension() != job.dimension) {
            if (!dugIn.contains(id)) {
                stop(bot); // (a soldier climbing out of its shaft keeps its dig progress; see climbOut)
            }
            return false;
        }
        ServerLevel level = bot.level();
        Claim claim = job.claims.get(id);
        if (claim != null && (!level.getBlockState(claim.pos()).is(job.block) || now - claim.since() > GIVE_UP)) {
            job.blocks.remove(claim.pos()); // mined, or unreachable: either way it's off the list
            job.claims.remove(id);
            claim = null;
        }
        if (claim == null) {
            job.blocks.removeIf(pos -> !level.getBlockState(pos).is(job.block));
            Set<BlockPos> claimed = new HashSet<>();
            job.claims.values().forEach(c -> claimed.add(c.pos()));
            BlockPos next = job.blocks.stream().filter(pos -> !claimed.contains(pos))
                    .min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(bot.position()))).orElse(null);
            if (next == null) {
                if (job.claims.isEmpty()) {
                    finish(masterId, master, job);
                }
                stop(bot);
                return false;
            }
            claim = new Claim(next, now);
            job.claims.put(id, claim);
        }
        dig(bot, level, claim.pos(), now);
        return true;
    }

    private static void finish(UUID masterId, @Nullable ServerPlayer master, Job job) {
        jobs.remove(masterId);
        if (master != null) {
            master.sendSystemMessage(Component.literal("Your Null Soldiers finished mining: " + job.mined + " "
                    + job.block.getName().getString() + ". They're coming back to you.").withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    private static void stop(ServerPlayer bot) {
        Breaking was = breaking.remove(bot.getUUID());
        if (was != null) {
            bot.level().destroyBlockProgress(bot.getId(), was.pos, -1); // clear the crack
        }
    }

    /**
     * One tick of breaking {@code pos} at survival speed (tool, Efficiency, on-ground and underwater rules all come from
     * getDestroyProgress), with the crack animation and arm swings; the break itself is the game's own player break,
     * so Fortune, drops and the break event all work as for a real player.
     */
    private static void breakBlock(ServerPlayer bot, ServerLevel level, BlockPos pos, int now) {
        Breaking current = breaking.get(bot.getUUID());
        if (current == null || !current.pos.equals(pos)) {
            stop(bot);
            current = new Breaking(pos);
            breaking.put(bot.getUUID(), current);
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            stop(bot);
            return;
        }
        current.progress += state.getDestroyProgress(bot, level, pos);
        if (now % 4 == 0) {
            bot.swing(InteractionHand.MAIN_HAND);
        }
        if (current.progress >= 1) {
            stop(bot);
            bot.gameMode.destroyBlock(pos);
        } else {
            level.destroyBlockProgress(bot.getId(), pos, (int) (current.progress * 10));
        }
    }

    /** Mine the goal when it's in reach; otherwise clear the way to it (2-high tunnel, or the floor when it's below). */
    private static void dig(ServerPlayer bot, ServerLevel level, BlockPos goal, int now) {
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        Vec3 eye = bot.getEyePosition();
        Vec3 center = Vec3.atCenterOf(goal);
        BlockPos aim = null;
        Vec3 look = center;
        actions.setStrafing(0);
        actions.setSprinting(false);
        if (eye.distanceTo(center) <= bot.blockInteractionRange() - 0.5) {
            // in reach: whatever is in the way of it first, then the goal itself
            BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            aim = hit.getType() == HitResult.Type.BLOCK && !level.getBlockState(hit.getBlockPos()).isAir() ? hit.getBlockPos() : goal;
            actions.setForward(0);
        } else {
            BlockPos feet = bot.blockPosition();
            double dx = center.x - bot.getX();
            double dz = center.z - bot.getZ();
            if (goal.getY() < feet.getY() && dx * dx + dz * dz < 2.25) {
                // it's under us: dig a shaft straight down the goal's column, then step onto the hole so we drop in
                // (a 0.6-wide player usually stands on 2-4 blocks, so breaking just the one under its middle isn't enough)
                BlockPos column = new BlockPos(goal.getX(), feet.getY(), goal.getZ());
                if (blocks(level, column.below())) {
                    aim = column.below();
                    actions.setForward(0);
                    dugIn.add(bot.getUUID());
                } else {
                    Vec3 hole = Vec3.atBottomCenterOf(column);
                    double off = Math.hypot(hole.x - bot.getX(), hole.z - bot.getZ());
                    look = new Vec3(hole.x, eye.y, hole.z);
                    actions.setForward(off > 0.5 ? 1 : off > 0.15 ? 0.3f : 0);
                }
            } else {
                Direction toward = Direction.getApproximateNearest(dx, 0, dz);
                BlockPos front = feet.relative(toward);
                if (blocks(level, front.above())) {
                    aim = front.above();
                } else if (blocks(level, front)) {
                    aim = front;
                }
                look = aim != null ? Vec3.atCenterOf(aim) : new Vec3(front.getX() + 0.5, eye.y, front.getZ() + 0.5);
                actions.setForward(aim == null ? 1 : 0);
                if (aim == null) {
                    SoldierHandler.jumpIfBlocked(bot);
                }
            }
        }
        if (aim == null) {
            stop(bot);
            actions.lookAt(look);
            return;
        }
        actions.lookAt(Vec3.atCenterOf(aim));
        SoldierHandler.selectSlot(bot, SoldierGear.bestToolSlot(bot, level.getBlockState(aim)));
        breakBlock(bot, level, aim, now);
    }

    /**
     * A soldier that dug down a shaft digs a staircase back up towards {@code goal}: it clears its own headroom and the
     * step in front, then jumps up one block, until it's level with the goal. Only soldiers that dug themselves in do
     * this, so they never dig through floors to reach their master. True while it's handling the movement.
     */
    static boolean climbOut(ServerPlayer bot, Vec3 goal) {
        UUID id = bot.getUUID();
        if (!dugIn.contains(id)) {
            return false;
        }
        if (bot.getY() >= goal.y - 0.5) {
            dugIn.remove(id);
            stop(bot);
            return false;
        }
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        Direction toward = Direction.getApproximateNearest(goal.x - bot.getX(), 0, goal.z - bot.getZ());
        BlockPos front = feet.relative(toward);
        if (!blocks(level, front)) {
            stop(bot);
            return false; // nothing to climb here: walk on
        }
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        actions.setStrafing(0);
        actions.setSprinting(false);
        for (BlockPos inTheWay : new BlockPos[]{feet.above(2), front.above(2), front.above()}) {
            if (blocks(level, inTheWay)) {
                actions.setForward(0);
                actions.lookAt(Vec3.atCenterOf(inTheWay));
                SoldierHandler.selectSlot(bot, SoldierGear.bestToolSlot(bot, level.getBlockState(inTheWay)));
                breakBlock(bot, level, inTheWay, level.getServer().getTickCount());
                return true;
            }
        }
        stop(bot);
        actions.lookAt(new Vec3(front.getX() + 0.5, bot.getEyeY(), front.getZ() + 0.5));
        actions.setForward(1);
        if (bot.onGround()) {
            CarpetIntegration.jump(bot); // up onto the step
        }
        return true;
    }

    /** A solid block a soldier can break (bedrock and the like count as open, and the claim times out). */
    private static boolean blocks(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty() && state.getDestroySpeed(level, pos) >= 0;
    }
}
