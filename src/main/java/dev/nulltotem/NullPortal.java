package dev.nulltotem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** The nether portal soldiers step out of. A real portal (obsidian + portal blocks) that stays after they've come through. */
final class NullPortal {
    private static final int REUSE_RADIUS = 16;

    /** Bottom middle of a nether portal near the master, building one if none is close; null when there's no room. */
    @Nullable
    static Vec3 exit(ServerPlayer master) {
        ServerLevel level = master.serverLevel();
        Optional<BlockPos> near = level.getPoiManager().findClosest(type -> type.is(PoiTypes.NETHER_PORTAL),
                master.blockPosition(), REUSE_RADIUS, PoiManager.Occupancy.ANY);
        if (near.isPresent()) {
            BlockPos bottom = near.get();
            while (level.getBlockState(bottom.below()).is(Blocks.NETHER_PORTAL)) {
                bottom = bottom.below();
            }
            return Vec3.atBottomCenterOf(bottom);
        }
        // a few blocks away, facing the master, so the soldiers visibly walk out of it towards them
        Direction facing = master.getDirection();
        for (Direction away : List.of(facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite())) {
            for (int distance : new int[]{6, 7, 5, 8}) {
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos base = master.blockPosition().relative(away, distance).above(dy);
                    Direction across = away.getClockWise();
                    if (fits(level, base, across)) {
                        build(level, base, across);
                        return Vec3.atBottomCenterOf(base.above()).add(across.getStepX() * 0.5, 0, across.getStepZ() * 0.5);
                    }
                }
            }
        }
        return null;
    }

    // Frame cells are w -1..2 across, h 0..4 up from base (the bottom frame block); the 2x3 inside is w 0..1, h 1..3.
    private static boolean fits(ServerLevel level, BlockPos base, Direction across) {
        for (int w = -1; w <= 2; w++) {
            for (int h = 0; h <= 4; h++) {
                BlockPos cell = base.relative(across, w).above(h);
                if (!level.getBlockState(cell).canBeReplaced() || !level.getFluidState(cell).isEmpty()) {
                    return false; // never replace real blocks or fluids
                }
                if (h == 0 && !level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) {
                    return false; // don't float it in the air
                }
            }
        }
        return level.getEntities((Entity) null, new AABB(base.relative(across, -1)).minmax(new AABB(base.relative(across, 2).above(4)))).isEmpty();
    }

    private static void build(ServerLevel level, BlockPos base, Direction across) {
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, across.getAxis());
        for (int w = -1; w <= 2; w++) {
            for (int h = 0; h <= 4; h++) {
                if (w == -1 || w == 2 || h == 0 || h == 4) {
                    level.setBlockAndUpdate(base.relative(across, w).above(h), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        // frame first, so each portal block is valid the moment it's placed (same flags vanilla's PortalShape uses)
        for (int w = 0; w <= 1; w++) {
            for (int h = 1; h <= 3; h++) {
                level.setBlock(base.relative(across, w).above(h), portal, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
