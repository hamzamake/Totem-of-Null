package dev.nulltotem.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
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
 * Sneak + left-click with the totem in the main hand: the closest soldier builds a 1-wide netherrack walkway from the
 * master's feet to the spot they look at (up to 64 blocks), walking out along it, crouching at the edge, and placing a
 * block every 4 ticks. Only empty space, liquids and plants get filled, never a real block or a spot someone stands in.
 */
final class Bridges {
    private static final int RANGE = 64;
    private static final int PLACE_INTERVAL = 4;
    private static final int STUCK = 200;
    private static final double REACH = 4;

    /** The cells to fill, and the solid ground where the bridge starts (where the builder stands for the first block). */
    private record Plan(List<Block> blocks, Location start) {
    }

    private static final class Job {
        final World world;
        final List<Block> blocks;
        final Location start;
        int builder;
        int next;
        int placed;
        int lastProgress;

        Job(World world, List<Block> blocks, Location start, int builder, int now) {
            this.world = world;
            this.blocks = blocks;
            this.start = start;
            this.builder = builder;
            this.lastProgress = now;
        }
    }

    private final NullTotemPlugin plugin;
    private final Map<UUID, Job> jobs = new HashMap<>();

    Bridges(NullTotemPlugin plugin) {
        this.plugin = plugin;
    }

    /** Plans the bridge and hands it to the closest soldier: the number of blocks, 0 = nothing to bridge, -1 = no soldiers. */
    int start(Player master) {
        Soldiers.Soldier builder = plugin.soldiers().soldiersOf(master.getUniqueId()).stream()
                .filter(s -> s.body().getWorld() == master.getWorld())
                .min(Comparator.comparingDouble(s -> s.body().getLocation().distanceSquared(master.getLocation()))).orElse(null);
        if (builder == null) {
            return -1;
        }
        Plan plan = plan(master);
        List<Block> blocks = plan.blocks();
        if (blocks.isEmpty()) {
            return 0;
        }
        jobs.put(master.getUniqueId(), new Job(master.getWorld(), blocks, plan.start(), builder.npc.getId(), Bukkit.getCurrentTick()));
        for (Block block : blocks) {
            master.getWorld().spawnParticle(Particle.REVERSE_PORTAL, block.getLocation().add(0.5, 1, 0.5), 3, 0.2, 0.1, 0.2, 0);
        }
        return blocks.size();
    }

    void cancel(UUID master) {
        jobs.remove(master);
    }

    void reset() {
        jobs.clear();
    }

    @Nullable
    String status(UUID master) {
        Job job = jobs.get(master);
        return job == null ? null : "building a bridge (" + job.placed + " of " + job.blocks.size() + " blocks)";
    }

    private static Plan plan(Player player) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, look, RANGE, FluidCollisionMode.NEVER, true);
        Vector end = hit != null ? hit.getHitPosition() : eye.toVector().add(look.clone().multiply(RANGE)).subtract(new Vector(0, player.getEyeHeight(), 0));
        Vector start = player.getLocation().toVector();
        double length = start.distance(end);
        Set<Block> cells = new LinkedHashSet<>();
        Location ground = player.getLocation();
        for (double t = 0; length >= 1 && t <= length; t += 0.25) {
            Vector point = start.clone().add(end.clone().subtract(start).multiply(t / length));
            Block block = player.getWorld().getBlockAt(point.getBlockX(), (int) Math.floor(point.getY() - 0.5), point.getBlockZ());
            if (block.isReplaceable() && clear(block)) {
                cells.add(block);
            } else if (cells.isEmpty() && block.getType().isSolid()) {
                ground = block.getLocation().add(0.5, 1, 0.5); // the last solid ground before the gap
            }
        }
        return new Plan(new ArrayList<>(cells), ground);
    }

    private static boolean clear(Block block) {
        return block.getWorld().getNearbyEntities(BoundingBox.of(block), e -> e instanceof LivingEntity).isEmpty();
    }

    /** One tick of the builder's work; false when this soldier isn't building a bridge right now. */
    boolean work(Soldiers.Soldier s, @Nullable Player master, int now) {
        Job job = jobs.get(s.master);
        Player body = s.body();
        if (job == null || body == null) {
            return false;
        }
        if (plugin.soldiers().soldiersOf(s.master).stream().noneMatch(o -> o.npc.getId() == job.builder)) {
            job.builder = s.npc.getId(); // the builder is gone: this one takes over
        }
        if (job.builder != s.npc.getId()) {
            return false;
        }
        if (body.getWorld() != job.world) {
            jobs.remove(s.master);
            return false;
        }
        if (job.next < job.blocks.size() && now - job.lastProgress > STUCK) {
            job.next++;
            job.lastProgress = now;
        }
        while (job.next < job.blocks.size() && !job.blocks.get(job.next).isReplaceable()) {
            job.next++;
        }
        Soldiers soldiers = plugin.soldiers();
        if (job.next >= job.blocks.size()) {
            jobs.remove(s.master);
            body.setSneaking(false);
            if (master != null) {
                master.sendActionBar(Component.text("Your Null Soldier finished the bridge: " + job.placed + " block(s) of netherrack", NamedTextColor.DARK_PURPLE));
            }
            return false;
        }
        Block next = job.blocks.get(job.next);
        if (body.getLocation().getY() < next.getY() && soldiers.climb(s, body, next.getLocation().add(0.5, 1, 0.5), now)) {
            return true; // fell below the bridge: climb back up first
        }
        soldiers.hold(s, new ItemStack(Material.NETHERRACK, 64));
        Location center = next.getLocation().add(0.5, 0.5, 0.5);
        double reach = body.getEyeLocation().distance(center);
        body.setSneaking(reach <= REACH + 1); // crouched at the edge, like a player bridging
        if (reach <= REACH) {
            soldiers.stop(s);
            s.npc.faceLocation(center);
            if (now - job.lastProgress >= PLACE_INTERVAL && clear(next)) {
                Soldiers.place(body, next);
                job.next++;
                job.placed++;
                job.lastProgress = now;
            }
        } else {
            // walk out to the end of what's built so far, or to where the bridge starts
            Block last = job.next > 0 ? job.blocks.get(job.next - 1) : null;
            Location goal = last != null && last.getType() == Material.NETHERRACK ? last.getLocation().add(0.5, 1, 0.5) : job.start;
            if (body.getLocation().distance(goal) < 1.5) {
                soldiers.step(s, body, goal); // the navigator won't take a last step this short
            } else {
                soldiers.navigate(s, goal);
            }
        }
        return true;
    }
}
