package dev.nulltotem.paper;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The nether portal soldiers step out of: a real portal (obsidian + portal blocks) that stays after they've come through. */
final class Portal {
    private static final int REUSE_RADIUS = 16;
    private static final List<BlockFace> AROUND = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    /** Bottom middle of a nether portal near the master, building one if none is close; null when there's no room. */
    @Nullable
    static Location exit(Player master) {
        World world = master.getWorld();
        Block feet = master.getLocation().getBlock();
        Block nearest = null;
        double best = Double.MAX_VALUE;
        for (int x = -REUSE_RADIUS; x <= REUSE_RADIUS; x++) {
            for (int y = -REUSE_RADIUS; y <= REUSE_RADIUS; y++) {
                for (int z = -REUSE_RADIUS; z <= REUSE_RADIUS; z++) {
                    Block block = feet.getRelative(x, y, z);
                    double d = x * x + y * y + z * z;
                    if (d < best && block.getType() == Material.NETHER_PORTAL) {
                        nearest = block;
                        best = d;
                    }
                }
            }
        }
        if (nearest != null) {
            while (nearest.getRelative(BlockFace.DOWN).getType() == Material.NETHER_PORTAL) {
                nearest = nearest.getRelative(BlockFace.DOWN);
            }
            return nearest.getLocation().add(0.5, 0, 0.5);
        }
        // a few blocks away, facing the master, so the soldiers visibly walk out of it towards them
        BlockFace facing = master.getFacing();
        int start = AROUND.indexOf(facing);
        for (int turn : new int[]{0, 1, 3, 2}) {
            BlockFace away = AROUND.get((start + turn) % 4);
            BlockFace across = AROUND.get((AROUND.indexOf(away) + 1) % 4);
            for (int distance : new int[]{6, 7, 5, 8}) {
                for (int dy : new int[]{0, 1, -1}) {
                    Block base = feet.getRelative(away, distance).getRelative(0, dy, 0);
                    if (fits(world, base, across)) {
                        build(base, across);
                        return base.getRelative(BlockFace.UP).getLocation().add(0.5 + across.getModX() * 0.5, 0, 0.5 + across.getModZ() * 0.5);
                    }
                }
            }
        }
        return null;
    }

    // Frame cells are w -1..2 across, h 0..4 up from base (the bottom frame block); the 2x3 inside is w 0..1, h 1..3.
    private static boolean fits(World world, Block base, BlockFace across) {
        for (int w = -1; w <= 2; w++) {
            for (int h = 0; h <= 4; h++) {
                Block cell = base.getRelative(across, w).getRelative(0, h, 0);
                if (!cell.isReplaceable() || cell.isLiquid()) {
                    return false; // never replace real blocks or fluids
                }
                if (h == 0 && !cell.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    return false; // don't float it in the air
                }
            }
        }
        Block a = base.getRelative(across, -1);
        Block b = base.getRelative(across, 2).getRelative(0, 4, 0);
        return world.getNearbyEntities(BoundingBox.of(a, b)).isEmpty();
    }

    private static void build(Block base, BlockFace across) {
        for (int w = -1; w <= 2; w++) {
            for (int h = 0; h <= 4; h++) {
                if (w == -1 || w == 2 || h == 0 || h == 4) {
                    base.getRelative(across, w).getRelative(0, h, 0).setType(Material.OBSIDIAN);
                }
            }
        }
        Axis axis = across.getModX() != 0 ? Axis.X : Axis.Z;
        // frame first, so each portal block is valid when placed; no physics, so it can't be "invalid" mid-build
        for (int w = 0; w <= 1; w++) {
            for (int h = 1; h <= 3; h++) {
                Block cell = base.getRelative(across, w).getRelative(0, h, 0);
                Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
                portal.setAxis(axis);
                cell.setBlockData(portal, false);
            }
        }
    }
}
