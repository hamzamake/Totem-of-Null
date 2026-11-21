package dev.nulltotem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashSet;
import java.util.Set;

/**
 * Sneak + left-click with the totem in the main hand: a 1-wide netherrack walkway from your feet to the spot you look
 * at (up to 64 blocks; looking at the sky, it runs level ahead of you). It only fills empty space, liquids and plants,
 * never a real block, and never a spot an entity is standing in.
 */
final class NullBridge {
    private static final int RANGE = 64;

    static int build(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        HitResult hit = player.pick(RANGE, 0, false);
        Vec3 end = hit.getType() == HitResult.Type.MISS
                ? eye.add(player.getViewVector(1).scale(RANGE)).subtract(0, player.getEyeHeight(), 0)
                : hit.getLocation();
        Vec3 start = player.position();
        double length = start.distanceTo(end);
        if (length < 1) {
            return 0;
        }
        BlockState netherrack = Blocks.NETHERRACK.defaultBlockState();
        Set<BlockPos> seen = new HashSet<>();
        int placed = 0;
        for (double t = 0; t <= length; t += 0.25) {
            Vec3 point = start.add(end.subtract(start).scale(t / length));
            BlockPos pos = BlockPos.containing(point.x, point.y - 0.5, point.z); // the block you'd walk on
            if (seen.add(pos) && level.getBlockState(pos).canBeReplaced()
                    && level.isUnobstructed(netherrack, pos, CollisionContext.empty())) {
                level.setBlockAndUpdate(pos, netherrack);
                placed++;
            }
        }
        if (placed > 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.NETHERRACK_PLACE, SoundSource.BLOCKS, 1, 0.8f);
        }
        return placed;
    }
}
