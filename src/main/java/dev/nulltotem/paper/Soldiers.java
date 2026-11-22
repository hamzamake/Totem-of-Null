package dev.nulltotem.paper;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.ShieldMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Null Soldiers: Citizens player NPCs (the Paper stand-in for Carpet's fake players), one brain tick each per
 * server tick, in priority order: eat a golden apple below 3 hearts, build a bridge, fight, mine, hold a post, follow.
 * They only teleport to follow their master into another world; Citizens' default "teleport when stuck" is replaced
 * by pillaring up with netherrack.
 */
final class Soldiers implements Listener {
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final double FOLLOW_DISTANCE = 4;
    private static final double POST_DISTANCE = 3;
    private static final double LEASH = 24;
    private static final double DEFEND_RADIUS = 16;
    private static final int GIVE_UP = 200;
    private static final int ORDERED_GIVE_UP = 600;
    private static final double LOW_HEALTH = 6;
    private static final int EAT_TICKS = 32;
    private static final int HOP_INTERVAL = 6;
    private static final int STUCK_AFTER = 40;
    private static final int SPAWN_GRACE = 100;

    /** One soldier: its NPC, master, kit and what its brain is doing. */
    static final class Soldier {
        final NPC npc;
        final UUID master;
        final ItemStack sword;
        final ItemStack pickaxe;
        final ItemStack axe;
        final ItemStack shovel;
        @Nullable
        LivingEntity target;
        int outOfReach;
        int apples = 64;
        int eatUntil = -1;
        boolean stuck;
        boolean climbing;
        int lastHop;
        Location lastProgressPos;
        int lastProgressTick;
        @Nullable
        Location navGoal;
        boolean dugIn;
        @Nullable
        Block breaking;
        float breakProgress;
        final int enlistedAt = Bukkit.getCurrentTick();
        boolean ready; // its body has spawned and got its effects

        Soldier(NPC npc, UUID master, ItemStack sword, ItemStack pickaxe, ItemStack axe, ItemStack shovel) {
            this.npc = npc;
            this.master = master;
            this.sword = sword;
            this.pickaxe = pickaxe;
            this.axe = axe;
            this.shovel = shovel;
        }

        @Nullable
        Player body() {
            return npc.isSpawned() && npc.getEntity() instanceof Player p ? p : null;
        }
    }

    private final NullTotemPlugin plugin;
    private final NPCRegistry registry;
    // NPC UUID -> soldier. (Not the NPC id: ids are only unique within one Citizens registry, and other plugins' NPCs
    // live in other registries.)
    private final Map<UUID, Soldier> soldiers = new HashMap<>();
    private final Map<UUID, LivingEntity> orders = new HashMap<>();   // master -> mob they sent the soldiers at
    private final Map<UUID, Location> posts = new HashMap<>();        // master -> where the soldiers wait
    private final Random random = new Random();

    Soldiers(NullTotemPlugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.createInMemoryNPCRegistry("nulltotem");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    // ---- lookups ----

    @Nullable
    Soldier soldier(@Nullable Entity entity) {
        NPC npc = entity == null ? null : CitizensAPI.getNPCRegistry().getNPC(entity);
        if (npc == null) {
            npc = entity == null ? null : registry.getNPC(entity);
        }
        return npc == null || npc.getOwningRegistry() != registry ? null : soldiers.get(npc.getUniqueId());
    }

    boolean isSoldier(Entity entity) {
        return soldier(entity) != null;
    }

    @Nullable
    UUID masterOf(@Nullable Entity entity) {
        Soldier s = soldier(entity);
        return s == null ? null : s.master;
    }

    List<Soldier> soldiersOf(UUID master) {
        return soldiers.values().stream().filter(s -> s.master.equals(master) && s.body() != null).toList();
    }

    /** The master as a player in the world: online players, and (on a test server) a Citizens NPC standing in for one. */
    @Nullable
    static Player master(UUID id) {
        Player online = Bukkit.getPlayer(id);
        return online != null ? online : Bukkit.getEntity(id) instanceof Player npc ? npc : null;
    }

    boolean isFriendly(UUID master, Entity other) {
        return other.getUniqueId().equals(master) || master.equals(masterOf(other));
    }

    // ---- orders ----

    /** Points every soldier of this master at the target, unless it's on the master's side. */
    void command(Player master, LivingEntity target, boolean ordered) {
        if (target == master || isSoldier(master) || isFriendly(master.getUniqueId(), target)) {
            return;
        }
        for (Soldier s : soldiersOf(master.getUniqueId())) {
            s.target = target;
            s.outOfReach = 0;
        }
        if (ordered) {
            orders.put(master.getUniqueId(), target);
        }
    }

    void hold(Player master, Location pos) {
        plugin.mining().cancel(master.getUniqueId());
        posts.put(master.getUniqueId(), pos);
    }

    void follow(Player master) {
        UUID id = master.getUniqueId();
        posts.remove(id);
        LivingEntity ordered = orders.remove(id);
        for (Soldier s : soldiersOf(id)) {
            if (s.target == ordered) {
                s.target = null;
            }
        }
        plugin.mining().cancel(id);
    }

    boolean isCommanded(UUID master) {
        LivingEntity ordered = orders.get(master);
        if (ordered != null && ordered.isDead()) {
            orders.remove(master);
            ordered = null;
        }
        return ordered != null || posts.containsKey(master);
    }

    String status(UUID master) {
        int count = soldiersOf(master).size();
        String doing = plugin.mining().status(master);
        if (doing == null) {
            doing = plugin.bridges().status(master);
        }
        LivingEntity ordered = orders.get(master);
        if (doing == null) {
            doing = ordered != null && !ordered.isDead() ? "attacking " + ordered.getName()
                    : posts.containsKey(master) ? "holding the spot you sent them to" : "following you";
        }
        return "You lead " + count + " Null Soldier(s), " + doing + ".";
    }

    // ---- spawning and dismissing ----

    /** Spawns up to {@code amount} soldiers out of a nether portal, capped so the master never has more than the max. */
    int spawn(Player master, int amount) {
        int toSpawn = Math.min(amount, plugin.state().maxSoldiers() - soldiersOf(master.getUniqueId()).size());
        if (toSpawn <= 0) {
            return 0;
        }
        Location portal = Portal.exit(master);
        if (portal == null) {
            master.sendMessage(Component.text("No room for a Null portal here, so your soldiers arrive beside you.", NamedTextColor.GRAY));
        }
        for (int i = 0; i < toSpawn; i++) {
            Location at = portal;
            if (at == null) {
                double angle = 2 * Math.PI * i / toSpawn;
                at = master.getLocation().add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
            }
            enlist(master, at);
        }
        return toSpawn;
    }

    private String randomName() {
        while (true) {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                name.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
            }
            String n = name.toString();
            if (Bukkit.getPlayerExact(n) == null && soldiers.values().stream().noneMatch(s -> s.npc.getRawName().endsWith(n))) {
                return n;
            }
        }
    }

    private void enlist(Player master, Location at) {
        String name = randomName();
        NPC npc = registry.createNPC(EntityType.PLAYER, "§5[Null Soldier] §f" + name);
        npc.setProtected(false); // they can be hurt and killed
        npc.data().setPersistent(NPC.Metadata.DROPS_ITEMS, false);
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, false); // a crowd mustn't shove the bridge builder off its bridge
        npc.data().setPersistent(NPC.Metadata.SHOULD_SAVE, false);
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        String value = plugin.getConfig().getString("soldier-skin.value", "");
        String signature = plugin.getConfig().getString("soldier-skin.signature", "");
        if (!value.isEmpty() && !signature.isEmpty()) {
            skin.setSkinPersistent("null_soldier", signature, value);
        } else {
            skin.setFetchDefaultSkin(false); // not a random account's skin that happens to share the name
        }
        Soldier s = new Soldier(npc, master.getUniqueId(),
                gear(Material.NETHERITE_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.LOOTING, 3,
                        Enchantment.SWEEPING_EDGE, 3, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1)),
                tool(Material.NETHERITE_PICKAXE), tool(Material.NETHERITE_AXE), tool(Material.NETHERITE_SHOVEL));
        soldiers.put(npc.getUniqueId(), s);

        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        params.range(64).attackDelayTicks(12).attackRange(2.8).distanceMargin(1.2).speedModifier(1.25f)
                .stuckAction((stuckNpc, navigator) -> {
                    Soldier stuck = soldiers.get(stuckNpc.getUniqueId());
                    if (stuck != null) {
                        stuck.stuck = true; // pillar up instead of Citizens' default teleport
                    }
                    return false;
                });
        npc.spawn(at);
        if (npc.getEntity() != null) {
            npc.getEntity().setPortalCooldown(20);
        }
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        Map<Enchantment, Integer> armor = Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
        equipment.set(Equipment.EquipmentSlot.HELMET, armor(Material.NETHERITE_HELMET, armor, Map.of(Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1)));
        equipment.set(Equipment.EquipmentSlot.CHESTPLATE, armor(Material.NETHERITE_CHESTPLATE, armor, Map.of()));
        equipment.set(Equipment.EquipmentSlot.LEGGINGS, armor(Material.NETHERITE_LEGGINGS, armor, Map.of()));
        equipment.set(Equipment.EquipmentSlot.BOOTS, armor(Material.NETHERITE_BOOTS, armor, Map.of(Enchantment.FEATHER_FALLING, 4, Enchantment.DEPTH_STRIDER, 3)));
        equipment.set(Equipment.EquipmentSlot.HAND, s.sword);
        equipment.set(Equipment.EquipmentSlot.OFF_HAND, shield());
        s.lastProgressPos = at.clone();
        s.lastProgressTick = Bukkit.getCurrentTick();
    }

    int clear(Player master) {
        posts.remove(master.getUniqueId());
        orders.remove(master.getUniqueId());
        plugin.mining().cancel(master.getUniqueId());
        plugin.bridges().cancel(master.getUniqueId());
        return dismiss(s -> s.master.equals(master.getUniqueId()));
    }

    /** Every soldier on the server vanishes (a Totem of Null popped). */
    int dismissAll() {
        posts.clear();
        orders.clear();
        plugin.mining().reset();
        plugin.bridges().reset();
        return dismiss(s -> true);
    }

    private int dismiss(java.util.function.Predicate<Soldier> which) {
        int removed = 0;
        for (Soldier s : List.copyOf(soldiers.values())) {
            if (!which.test(s)) {
                continue;
            }
            Player body = s.body();
            if (body != null) {
                body.getWorld().spawnParticle(Particle.REVERSE_PORTAL, body.getLocation().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.05);
                removed++;
            }
            soldiers.remove(s.npc.getUniqueId());
            s.npc.destroy();
        }
        return removed;
    }

    void shutdown() {
        dismiss(s -> true);
        registry.deregisterAll();
    }

    // ---- gear ----

    private static ItemStack gear(Material type, Map<Enchantment, Integer> enchantments) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setUnbreakable(true);
        enchantments.forEach((e, level) -> meta.addEnchant(e, level, true));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack tool(Material type) {
        return gear(type, Map.of(Enchantment.EFFICIENCY, 5, Enchantment.FORTUNE, 3, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
    }

    private static ItemStack armor(Material type, Map<Enchantment, Integer> base, Map<Enchantment, Integer> extra) {
        ItemStack stack = gear(type, base);
        ArmorMeta meta = (ArmorMeta) stack.getItemMeta();
        extra.forEach((e, level) -> meta.addEnchant(e, level, true));
        meta.setTrim(new ArmorTrim(TrimMaterial.NETHERITE, TrimPattern.FLOW));
        stack.setItemMeta(meta);
        return stack;
    }

    /** The Null shield: light gray, black Field Masoned, Gradient, Base Gradient, Bend, a white Roundel, black Bordure Indented. */
    private static ItemStack shield() {
        ItemStack stack = gear(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        ShieldMeta meta = (ShieldMeta) stack.getItemMeta();
        meta.setBaseColor(DyeColor.LIGHT_GRAY);
        meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.BRICKS));
        meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.GRADIENT));
        meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.GRADIENT_UP));
        meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.STRIPE_DOWNRIGHT));
        meta.addPattern(new Pattern(DyeColor.WHITE, PatternType.CIRCLE));
        meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.CURLY_BORDER));
        stack.setItemMeta(meta);
        return stack;
    }

    void hold(Soldier s, ItemStack item) {
        Equipment equipment = s.npc.getOrAddTrait(Equipment.class);
        if (!item.isSimilar(equipment.get(Equipment.EquipmentSlot.HAND))) {
            equipment.set(Equipment.EquipmentSlot.HAND, item);
        }
    }

    ItemStack bestTool(Soldier s, Block block) {
        Material type = block.getType();
        if (Tag.MINEABLE_SHOVEL.isTagged(type)) {
            return s.shovel;
        }
        if (Tag.MINEABLE_AXE.isTagged(type)) {
            return s.axe;
        }
        return s.pickaxe;
    }

    // ---- the brain ----

    private void tick() {
        int now = Bukkit.getCurrentTick();
        for (Soldier s : List.copyOf(soldiers.values())) {
            Player body = s.body();
            if (body == null || body.isDead()) {
                // Citizens finishes spawning a player NPC a little later; only a soldier that was up and is gone is dead
                if (s.ready || now - s.enlistedAt > SPAWN_GRACE) {
                    soldiers.remove(s.npc.getUniqueId());
                    s.npc.destroy();
                }
                continue;
            }
            if (!s.ready) {
                s.ready = true;
                for (PotionEffectType effect : List.of(PotionEffectType.FIRE_RESISTANCE, PotionEffectType.WATER_BREATHING, PotionEffectType.SATURATION)) {
                    body.addPotionEffect(new PotionEffect(effect, PotionEffect.INFINITE_DURATION, 0, false, false));
                }
            }
            // portals never take soldiers anywhere (they follow their master between worlds by teleport), so standing
            // in the portal they came out of doesn't send them to the Nether
            body.setPortalCooldown(20);
            Player master = master(s.master);
            if (eat(s, body, now)) {
                continue;
            }
            if (plugin.bridges().work(s, master, now)) {
                continue;
            }
            LivingEntity target = target(s, body, master, now);
            if (target != null) {
                plugin.mining().pause(s);
                fight(s, body, target);
                continue;
            }
            if (plugin.mining().work(s, master, now)) {
                continue;
            }
            hold(s, s.sword);
            Location post = posts.get(s.master);
            if (post != null && post.getWorld() == body.getWorld()) {
                walkTo(s, body, slot(s, post, POST_DISTANCE - 1), 1, now);
            } else if (master == null || master.isDead()) {
                stop(s);
            } else if (master.getWorld() != body.getWorld()) {
                s.npc.teleport(master.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN); // only across dimensions
            } else {
                walkTo(s, body, slot(s, master.getLocation(), FOLLOW_DISTANCE - 1), 1, now);
            }
        }
    }

    @Nullable
    private LivingEntity target(Soldier s, Player body, @Nullable Player master, int now) {
        LivingEntity target = s.target;
        if (target != null) {
            boolean ordered = orders.get(s.master) == target;
            Location anchor = anchor(s, body, master);
            if (target.isDead() || !target.isValid() || target.getWorld() != body.getWorld()
                    || (!ordered && target.getLocation().distance(anchor) > LEASH)
                    || s.outOfReach > (ordered ? ORDERED_GIVE_UP : GIVE_UP)) {
                s.target = null;
                s.outOfReach = 0;
                target = null;
            }
        }
        if (target == null && (now + s.npc.getId()) % 10 == 0) {
            target = threat(s, body);
            s.target = target;
        }
        return target;
    }

    private Location anchor(Soldier s, Player body, @Nullable Player master) {
        Location post = posts.get(s.master);
        if (post != null && post.getWorld() == body.getWorld()) {
            return post;
        }
        return master != null && master.getWorld() == body.getWorld() ? master.getLocation() : body.getLocation();
    }

    /** The closest hostile mob that is going after the master or one of the master's soldiers. */
    @Nullable
    private LivingEntity threat(Soldier s, Player body) {
        return body.getNearbyEntities(DEFEND_RADIUS, DEFEND_RADIUS, DEFEND_RADIUS).stream()
                .filter(e -> e instanceof Mob mob && e instanceof Enemy && !mob.isDead() && mob.getTarget() != null
                        && isFriendly(s.master, mob.getTarget()))
                .map(e -> (LivingEntity) e)
                .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(body.getLocation())))
                .orElse(null);
    }

    /** Citizens' attack strategy does the swinging (with the soldier's own sword); this picks targets and dodges creepers. */
    private void fight(Soldier s, Player body, LivingEntity target) {
        hold(s, s.sword);
        Navigator navigator = s.npc.getNavigator();
        double distance = body.getLocation().distance(target.getLocation());
        s.outOfReach = distance > 3 ? s.outOfReach + 1 : 0;
        if (target instanceof Creeper creeper && creeper.getFuseTicks() > 0) {
            Vector away = body.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) {
                away = new Vector(1, 0, 0);
            }
            navigate(s, body.getLocation().add(away.normalize().multiply(6)));
            return;
        }
        if (navigator.getEntityTarget() == null || navigator.getEntityTarget().getTarget() != target) {
            navigator.setTarget(target, true);
            s.navGoal = null;
        }
    }

    /** Below 3 hearts a soldier stops, eats a golden apple (32 ticks) and gets its regeneration and absorption. */
    private boolean eat(Soldier s, Player body, int now) {
        if (s.eatUntil >= 0) {
            if (now < s.eatUntil) {
                if (now % 4 == 0) {
                    body.getWorld().playSound(body.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.5f, 1);
                }
                return true;
            }
            s.eatUntil = -1;
            s.apples--;
            body.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
            body.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
            body.getWorld().playSound(body.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1);
            hold(s, s.sword);
            return false;
        }
        if (body.getHealth() >= LOW_HEALTH || s.apples <= 0) {
            return false;
        }
        stop(s);
        hold(s, new ItemStack(Material.GOLDEN_APPLE, s.apples));
        s.eatUntil = now + EAT_TICKS;
        return true;
    }

    // ---- moving ----

    /**
     * This soldier's place in a ring around {@code center}. Soldiers pass through each other (so a crowd can't shove the
     * bridge builder off), so without their own spots they'd all stand in one, their name tags piled into a smear.
     */
    private Location slot(Soldier s, Location center, double radius) {
        List<Soldier> squad = soldiersOf(s.master);
        int index = Math.max(0, squad.indexOf(s));
        double angle = 2 * Math.PI * index / Math.max(1, squad.size());
        return center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    void navigate(Soldier s, Location goal) {
        if (s.navGoal == null || s.navGoal.getWorld() != goal.getWorld() || s.navGoal.distanceSquared(goal) > 1 || !s.npc.getNavigator().isNavigating()) {
            s.npc.getNavigator().setTarget(goal);
            s.navGoal = goal.clone();
        }
    }

    /**
     * A short step (a block or two) straight at {@code to}, like walking input. Citizens' navigator treats anything
     * within its arrival margin as reached, so it won't take the single steps a tunnel needs.
     */
    void step(Soldier s, Player body, Location to) {
        stop(s);
        Vector toward = to.toVector().subtract(body.getLocation().toVector()).setY(0);
        if (toward.lengthSquared() < 0.01) {
            return;
        }
        s.npc.faceLocation(to.clone().add(0, body.getEyeHeight(), 0));
        Vector walk = toward.normalize().multiply(0.22);
        body.setVelocity(new Vector(walk.getX(), body.getVelocity().getY(), walk.getZ()));
    }

    void stop(Soldier s) {
        if (s.npc.getNavigator().isNavigating()) {
            s.npc.getNavigator().cancelNavigation();
        }
        s.navGoal = null;
    }

    /** Walks towards the goal (Citizens pathfinds around obstacles), stopping {@code stopAt} short of it; pillars out of pits. */
    void walkTo(Soldier s, Player body, Location goal, double stopAt, int now) {
        if (climb(s, body, goal, now)) {
            return;
        }
        double distance = body.getLocation().distance(goal);
        if (distance <= stopAt) {
            if (stopAt >= FOLLOW_DISTANCE && distance < 2) {
                // too close to its master: step back, so it isn't standing in your face
                Vector away = body.getLocation().toVector().subtract(goal.toVector()).setY(0);
                if (away.lengthSquared() > 0.01) {
                    navigate(s, goal.clone().add(away.normalize().multiply(3)));
                }
            } else {
                stop(s);
            }
            return;
        }
        navigate(s, goal);
    }

    /**
     * Gets a soldier out of a pit it can't walk out of: when it's below its goal and stuck (Citizens says so, or it made
     * no sideways progress for 2 seconds), it pillars up, hopping onto netherrack it places under itself every 6 ticks,
     * then bridges any gap between it and the goal. It only digs (the block above its head) if it dug its own way down.
     */
    boolean climb(Soldier s, Player body, Location goal, int now) {
        Location at = body.getLocation();
        boolean below = goal.getY() > at.getY() + 1.2;
        if (!s.climbing) {
            if (Math.hypot(at.getX() - s.lastProgressPos.getX(), at.getZ() - s.lastProgressPos.getZ()) > 0.5
                    || at.getWorld() != s.lastProgressPos.getWorld()) {
                s.lastProgressPos = at.clone();
                s.lastProgressTick = now;
            }
            boolean stuck = s.stuck || now - s.lastProgressTick > STUCK_AFTER;
            if (!below || (!s.dugIn && !stuck)) {
                s.stuck = false;
                return false;
            }
            s.climbing = true;
            stop(s);
        }
        double flat = Math.hypot(goal.getX() - at.getX(), goal.getZ() - at.getZ());
        if (!below && flat <= 3) {
            s.climbing = false;
            s.stuck = false;
            s.dugIn = false;
            body.setSneaking(false);
            s.lastProgressPos = at.clone();
            s.lastProgressTick = now;
            return false;
        }
        Block feet = at.getBlock();
        if (below) {
            Block head = feet.getRelative(0, 2, 0);
            if (head.getType().isSolid()) {
                if (!s.dugIn) {
                    s.climbing = false; // a ceiling it didn't dig itself: leave it be
                    return false;
                }
                plugin.mining().breakBlock(s, body, head, now);
                return true;
            }
            hold(s, new ItemStack(Material.NETHERRACK, 64));
            if (body.isOnGround() && now - s.lastHop >= HOP_INTERVAL && feet.isReplaceable()) {
                Location up = at.clone();
                up.setY(feet.getY() + 1);
                s.npc.teleport(up, PlayerTeleportEvent.TeleportCause.PLUGIN); // a one-block hop onto the block it places
                place(body, feet);
                s.lastHop = now;
            }
            return true;
        }
        // level with the goal: walk to it, filling any gap in the floor ahead
        BlockFace toward = face(goal.getX() - at.getX(), goal.getZ() - at.getZ());
        Block front = feet.getRelative(toward);
        Block floor = front.getRelative(BlockFace.DOWN);
        if (front.isReplaceable() && floor.isReplaceable()) {
            body.setSneaking(true);
            s.npc.faceLocation(floor.getLocation().add(0.5, 0.5, 0.5));
            hold(s, new ItemStack(Material.NETHERRACK, 64));
            if (now % 4 == 0) {
                place(body, floor);
            }
            return true;
        }
        body.setSneaking(false);
        navigate(s, front.getLocation().add(0.5, 0, 0.5));
        return true;
    }

    static BlockFace face(double dx, double dz) {
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST) : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    }

    static void place(Player body, Block block) {
        block.setType(Material.NETHERRACK);
        World world = block.getWorld();
        world.playSound(block.getLocation(), Sound.BLOCK_NETHERRACK_PLACE, 1, 0.8f);
        body.swingMainHand();
    }

    // ---- events ----

    @Nullable
    private static Entity attacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** Neutrality: a soldier's hit (or sweep, or arrow) never lands on its master or the master's other soldiers. */
    @EventHandler(priority = EventPriority.LOW)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        UUID master = masterOf(attacker(event));
        if (master != null && isFriendly(master, event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** What the master hits, what hits the master, and what hits a soldier become targets. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity attacker = attacker(event);
        Entity victim = event.getEntity();
        if (attacker instanceof Player master && !isSoldier(master) && victim instanceof LivingEntity target) {
            command(master, target, false);
        }
        if (victim instanceof Player master && !isSoldier(master) && attacker instanceof LivingEntity target) {
            command(master, target, false);
        }
        Soldier hurt = soldier(victim);
        if (hurt != null && attacker instanceof LivingEntity target && !isFriendly(hurt.master, target) && hurt.target == null) {
            hurt.target = target; // hits back
        }
    }

    /** A soldier that dies drops nothing and is gone. */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Soldier s = soldier(event.getEntity());
        if (s != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            Bukkit.getScheduler().runTask(plugin, () -> {
                soldiers.remove(s.npc.getUniqueId());
                s.npc.destroy();
            });
        }
    }

    /** The soldiers of everyone (for the totem's pop and the reload). */
    List<Soldier> all() {
        return new ArrayList<>(soldiers.values());
    }
}
