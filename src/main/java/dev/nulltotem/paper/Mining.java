package dev.nulltotem.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Effect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "mine" order: every block like the one the master looked at, within 16 blocks of it (up to 128). Each soldier
 * claims the closest, tunnels to it (2-high, or a shaft straight down its column), breaks it at survival speed with
 * the right tool, and the drops go straight into the master's inventory.
 */
final class Mining {
    private static final int RADIUS = 16;
    private static final int MAX_BLOCKS = 128;
    private static final int GIVE_UP = 600;
    private static final double REACH = 4;

    private record Claim(Block block, int since) {
    }

    private static final class Job {
        final Material type;
        final World world;
        final List<Block> blocks;
        final Map<Integer, Claim> claims = new HashMap<>(); // NPC id -> claim
        int mined;

        Job(Material type, World world, List<Block> blocks) {
            this.type = type;
            this.world = world;
            this.blocks = blocks;
        }
    }

    private final NullTotemPlugin plugin;
    private final Map<UUID, Job> jobs = new HashMap<>();

    Mining(NullTotemPlugin plugin) {
        this.plugin = plugin;
    }

    int start(Player master, Block origin) {
        Material type = origin.getType();
        if (type.isAir() || origin.isLiquid() || type.getHardness() < 0) {
            return 0;
        }
        List<Block> found = new ArrayList<>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int y = -RADIUS; y <= RADIUS; y++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    Block block = origin.getRelative(x, y, z);
                    if (block.getType() == type) {
                        found.add(block);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(origin.getLocation())));
        List<Block> blocks = new ArrayList<>(found.subList(0, Math.min(MAX_BLOCKS, found.size())));
        jobs.put(master.getUniqueId(), new Job(type, origin.getWorld(), blocks));
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
        return job == null ? null : "mining " + niceName(job.type) + " (" + job.mined + " mined, " + job.blocks.size() + " left)";
    }

    static String niceName(Material type) {
        String raw = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    void pause(Soldiers.Soldier s) {
        clearCrack(s);
    }

    /** One tick of mining for this soldier; false when there's no job (or nothing left for it), so it follows instead. */
    boolean work(Soldiers.Soldier s, @Nullable Player master, int now) {
        Job job = jobs.get(s.master);
        Player body = s.body();
        if (job == null || body == null || body.getWorld() != job.world) {
            return false;
        }
        int id = s.npc.getId();
        Claim claim = job.claims.get(id);
        if (claim != null && (claim.block().getType() != job.type || now - claim.since() > GIVE_UP)) {
            job.blocks.remove(claim.block());
            job.claims.remove(id);
            claim = null;
        }
        if (claim == null) {
            job.blocks.removeIf(b -> b.getType() != job.type);
            Set<Block> claimed = new HashSet<>();
            job.claims.values().forEach(c -> claimed.add(c.block()));
            Block next = job.blocks.stream().filter(b -> !claimed.contains(b))
                    .min(Comparator.comparingDouble(b -> b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(body.getLocation()))).orElse(null);
            if (next == null) {
                if (job.claims.isEmpty()) {
                    jobs.remove(s.master);
                    if (master != null) {
                        master.sendMessage(Component.text("Your Null Soldiers finished mining: " + job.mined + " " + niceName(job.type)
                                + ". They're coming back to you.", NamedTextColor.DARK_PURPLE));
                    }
                }
                clearCrack(s);
                return false;
            }
            claim = new Claim(next, now);
            job.claims.put(id, claim);
        }
        dig(s, body, claim.block(), now);
        return true;
    }

    /** Mine the goal when it's in reach; otherwise clear the way to it (2-high tunnel, or a shaft when it's below). */
    private void dig(Soldiers.Soldier s, Player body, Block goal, int now) {
        Soldiers soldiers = plugin.soldiers();
        Location eye = body.getEyeLocation();
        Location center = goal.getLocation().add(0.5, 0.5, 0.5);
        Block feet = body.getLocation().getBlock();
        Block aim = null;
        if (eye.distance(center) <= REACH) {
            Vector direction = center.toVector().subtract(eye.toVector());
            RayTraceResult hit = body.getWorld().rayTraceBlocks(eye, direction, direction.length(), FluidCollisionMode.NEVER, true);
            aim = hit != null && hit.getHitBlock() != null ? hit.getHitBlock() : goal; // whatever is in the way first
            soldiers.stop(s);
        } else {
            double dx = center.getX() - body.getLocation().getX();
            double dz = center.getZ() - body.getLocation().getZ();
            if (goal.getY() < feet.getY() && dx * dx + dz * dz < 2.25) {
                // under us: a shaft down the goal's column, then step onto the hole to drop in
                Block column = body.getWorld().getBlockAt(goal.getX(), feet.getY(), goal.getZ());
                if (solid(column.getRelative(BlockFace.DOWN))) {
                    aim = column.getRelative(BlockFace.DOWN);
                    s.dugIn = true;
                    soldiers.stop(s);
                } else {
                    soldiers.step(s, body, column.getLocation().add(0.5, 0, 0.5)); // onto the hole, to drop in
                }
            } else {
                Block front = feet.getRelative(Soldiers.face(dx, dz));
                if (solid(front.getRelative(BlockFace.UP))) {
                    aim = front.getRelative(BlockFace.UP);
                } else if (solid(front)) {
                    aim = front;
                } else {
                    soldiers.step(s, body, front.getLocation().add(0.5, 0, 0.5));
                }
                if (aim != null) {
                    soldiers.stop(s);
                }
            }
        }
        if (aim == null) {
            clearCrack(s);
            return;
        }
        breakBlock(s, body, aim, now);
    }

    /** One tick of breaking at survival speed (getBreakSpeed counts the tool, Efficiency, ground and water), with the crack. */
    void breakBlock(Soldiers.Soldier s, Player body, Block block, int now) {
        Soldiers soldiers = plugin.soldiers();
        if (!block.equals(s.breaking)) {
            clearCrack(s);
            s.breaking = block;
            s.breakProgress = 0;
        }
        if (block.getType().isAir()) {
            clearCrack(s);
            return;
        }
        ItemStack tool = soldiers.bestTool(s, block);
        soldiers.hold(s, tool);
        s.npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
        s.breakProgress += block.getBreakSpeed(body);
        if (now % 4 == 0) {
            body.swingMainHand();
        }
        if (s.breakProgress < 1) {
            crack(body, block, s.breakProgress);
            return;
        }
        Collection<ItemStack> drops = block.getDrops(tool, body);
        Material type = block.getType();
        block.getWorld().playEffect(block.getLocation(), Effect.STEP_SOUND, type);
        block.setType(Material.AIR);
        clearCrack(s);
        Job job = jobs.get(s.master);
        Player master = Soldiers.master(s.master);
        if (job != null && type == job.type) {
            job.mined++;
            if (master != null) {
                for (ItemStack leftover : master.getInventory().addItem(drops.toArray(new ItemStack[0])).values()) {
                    master.getWorld().dropItemNaturally(master.getLocation(), leftover); // inventory full
                }
                return;
            }
        }
        for (ItemStack drop : drops) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop); // tunnel blocks stay where they are
        }
    }

    private static void crack(Player body, Block block, float progress) {
        for (Player viewer : block.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(block.getLocation()) < 32 * 32) {
                viewer.sendBlockDamage(block.getLocation(), Math.min(progress, 1), body.getEntityId());
            }
        }
    }

    private static void clearCrack(Soldiers.Soldier s) {
        Player body = s.body();
        if (s.breaking != null && body != null) {
            crack(body, s.breaking, 0);
        }
        s.breaking = null;
        s.breakProgress = 0;
    }

    private static boolean solid(Block block) {
        return block.getType().isSolid() && block.getType().getHardness() >= 0;
    }
}
