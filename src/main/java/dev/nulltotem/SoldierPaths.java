package dev.nulltotem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Walking routes for soldiers, so they go around walls and trees instead of into them (they never teleport within a
 * dimension). Players have no pathfinder, so a zombie that's never added to the world plans the route from the
 * soldier's spot (same size and step height as a player), and the soldier walks it node by node.
 */
final class SoldierPaths {
    private static final int REPLAN = 40; // ticks; also replans when the goal moves more than 2 blocks

    private record Route(Path path, BlockPos goal, int madeAt) {
    }

    private static final Map<UUID, Route> routes = new HashMap<>();

    /** The next point to walk at on the way to {@code goal}, or null when there's no route (then walk straight). */
    @Nullable
    static Vec3 next(ServerPlayer bot, Vec3 goal, int now) {
        BlockPos goalPos = BlockPos.containing(goal);
        Route route = routes.get(bot.getUUID());
        if (route == null || now - route.madeAt() > REPLAN || route.goal().distSqr(goalPos) > 4 || route.path().isDone()) {
            Path path = plan(bot, goalPos);
            route = path == null ? null : new Route(path, goalPos, now);
            if (route == null) {
                routes.remove(bot.getUUID());
                return null;
            }
            routes.put(bot.getUUID(), route);
        }
        Path path = route.path();
        while (!path.isDone()) {
            Vec3 node = Vec3.atBottomCenterOf(path.getNextNodePos());
            if (Math.hypot(node.x - bot.getX(), node.z - bot.getZ()) < 0.6 && Math.abs(node.y - bot.getY()) < 1.1) {
                path.advance(); // reached this node
            } else {
                return node;
            }
        }
        return null;
    }

    static void forget(UUID soldier) {
        routes.remove(soldier);
    }

    static void reset() {
        routes.clear();
    }

    @Nullable
    private static Path plan(ServerPlayer bot, BlockPos goal) {
        ServerLevel level = bot.serverLevel();
        Zombie scout = EntityType.ZOMBIE.create(level);
        if (scout == null) {
            return null;
        }
        scout.setPos(bot.position());
        scout.setOnGround(true); // the navigator only plans for a mob standing on something
        return scout.getNavigation().createPath(goal, 1);
    }
}
