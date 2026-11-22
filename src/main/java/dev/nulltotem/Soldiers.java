package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Null Soldiers: spawning, dismissing, and one brain tick per soldier per server tick
 * (eat, then bridge, fight, mine, hold a post or follow). Port of the Fabric SoldierHandler, with
 * {@link FakeSoldier} in place of Carpet's fake players.
 */
public final class Soldiers {
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int NAME_LENGTH = 9;
    /** Scoreboard team of every soldier; it shows "[Null Soldier]" before the name and the client gives it the black skin. */
    public static final String TEAM = "null_soldiers";
    private static final double FOLLOW_DISTANCE = 4;
    private static final double POST_DISTANCE = 3;
    private static final double LEASH = 24;            // targets farther than this from the master (or post) are let go
    private static final double DEFEND_RADIUS = 16;    // hostiles after the master or a soldier within this get engaged
    private static final int GIVE_UP = 200;            // ticks out of reach before a target is dropped
    private static final int ORDERED_GIVE_UP = 600;    // longer for a target the master pointed at
    private static final float LOW_HEALTH = 6;         // 3 hearts: eat a golden apple
    private static final int EAT_TIMEOUT = 60;         // a golden apple takes 32 ticks
    private static final int PORTAL_LOCK = 20;

    /** Where a master told their soldiers to go and wait. */
    private record Post(ResourceKey<Level> dimension, Vec3 pos) {
    }

    private record Eating(int applesBefore, int until) {
    }

    private static final Map<UUID, LivingEntity> targets = new HashMap<>();  // soldier -> what it's fighting
    private static final Map<UUID, Integer> outOfReach = new HashMap<>();    // soldier -> ticks it couldn't reach its target
    private static final Map<UUID, LivingEntity> orders = new HashMap<>();   // master -> mob they sent the soldiers at
    private static final Map<UUID, Post> posts = new HashMap<>();            // master -> where the soldiers wait
    private static final Map<UUID, Eating> eating = new HashMap<>();         // soldier -> golden apple in progress

    // ---- events ----

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        SoldierMining.deliver(server);
        if (now % 20 == 0) {
            NullBridge.check(server);
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player instanceof FakeSoldier bot && bot.isAlive()) {
                control(bot, bot.master, server.getPlayerList().getPlayer(bot.master), now);
            }
        }
    }

    /** A master hitting something: the whole squad joins in. */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer master && event.getTarget() instanceof LivingEntity target) {
            command(master, target, false);
        }
    }

    /** Neutrality: a soldier's hit (or sword sweep) never lands on its master or its master's other soldiers. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker && isFriendly(attacker, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer hurt) || !(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        if (!(hurt instanceof FakeSoldier)) {
            command(hurt, attacker, false); // a master was hurt: the whole squad answers
        } else if (!isFriendly(hurt, attacker) && targets.get(hurt.getUUID()) == null) {
            targets.put(hurt.getUUID(), attacker); // a soldier was hurt: it hits back
        }
    }

    /** A soldier that dies drops nothing. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof FakeSoldier) {
            event.setCanceled(true);
        }
    }

    /** A soldier that's gone (dismissed, died, server stopping) leaves the team, so its name can't pile up there. */
    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof FakeSoldier bot) {
            Scoreboard scoreboard = bot.getServer().getScoreboard();
            PlayerTeam team = scoreboard.getPlayersTeam(bot.getScoreboardName());
            if (team != null && team.getName().equals(TEAM)) {
                scoreboard.removePlayerFromTeam(bot.getScoreboardName(), team);
            }
            targets.remove(bot.getUUID());
            outOfReach.remove(bot.getUUID());
            eating.remove(bot.getUUID());
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        targets.clear();
        outOfReach.clear();
        orders.clear();
        posts.clear();
        eating.clear();
        SoldierMining.reset();
        SoldierPaths.reset();
        NullBridge.reset();
    }

    // ---- who's who ----

    @Nullable
    public static UUID masterOf(Entity entity) {
        return entity instanceof FakeSoldier bot ? bot.master : null;
    }

    static boolean isFriendly(ServerPlayer soldier, Entity other) {
        UUID master = masterOf(soldier);
        return master != null && (other.getUUID().equals(master) || master.equals(masterOf(other)));
    }

    public static List<ServerPlayer> soldiersOf(MinecraftServer server, UUID master) {
        return server.getPlayerList().getPlayers().stream().filter(p -> master.equals(masterOf(p))).toList();
    }

    /** Points every soldier of this master at the target, unless the target is on the master's side. */
    private static void command(ServerPlayer master, LivingEntity target, boolean ordered) {
        if (target == master || masterOf(master) != null || master.getUUID().equals(masterOf(target))) {
            return;
        }
        for (ServerPlayer bot : soldiersOf(master.getServer(), master.getUUID())) {
            targets.put(bot.getUUID(), target);
            outOfReach.remove(bot.getUUID());
        }
        if (ordered) {
            orders.put(master.getUUID(), target);
        }
    }

    // ---- orders (see Orders) ----

    /** Sends the squad at a mob the master pointed at, however far it is (up to 64 blocks). */
    static void attack(ServerPlayer master, LivingEntity target) {
        command(master, target, true);
    }

    /** Sends the squad to a spot to wait there; they stop following until called back. */
    static void hold(ServerPlayer master, Vec3 pos) {
        SoldierMining.cancel(master.getUUID());
        posts.put(master.getUUID(), new Post(master.level().dimension(), pos));
    }

    /** Back to normal: follow the master, forget any post, pointed-at target or mining job. */
    static void follow(ServerPlayer master) {
        UUID id = master.getUUID();
        posts.remove(id);
        LivingEntity ordered = orders.remove(id);
        if (ordered != null) {
            targets.values().removeIf(t -> t == ordered);
        }
        SoldierMining.cancel(id);
    }

    /** True while the soldiers are holding a post or chasing a mob the master pointed at. */
    static boolean isCommanded(UUID master) {
        LivingEntity ordered = orders.get(master);
        if (ordered != null && !ordered.isAlive()) {
            orders.remove(master);
            ordered = null;
        }
        return ordered != null || posts.containsKey(master);
    }

    static String status(MinecraftServer server, UUID master) {
        int count = soldiersOf(server, master).size();
        String mining = SoldierMining.status(master);
        if (mining == null) {
            mining = NullBridge.status(master);
        }
        LivingEntity ordered = orders.get(master);
        String doing = mining != null ? mining
                : ordered != null && ordered.isAlive() ? "attacking " + ordered.getName().getString()
                : posts.containsKey(master) ? "holding the spot you sent them to"
                : "following you";
        return "You lead " + count + " Null Soldier(s), " + doing + ".";
    }

    // ---- spawning and dismissing ----

    /** Spawns up to {@code amount} soldiers, capped so the master never has more than the configured max. */
    static int spawn(ServerPlayer master, int amount) {
        MinecraftServer server = master.getServer();
        ServerLevel level = master.serverLevel();
        int have = soldiersOf(server, master.getUUID()).size();
        int toSpawn = Math.min(amount, NullState.get(server).maxSoldiers() - have);
        if (toSpawn <= 0) {
            return 0;
        }
        Vec3 portal = NullPortal.exit(master);
        if (portal == null) {
            master.sendSystemMessage(Component.literal("No room for a Null portal here, so your soldiers arrive beside you.")
                    .withStyle(ChatFormatting.GRAY));
        }
        PlayerTeam team = team(server);
        RandomSource random = master.getRandom();
        int started = 0;
        for (int tries = 0; started < toSpawn && tries < toSpawn * 10; tries++) {
            String name = randomName(random);
            if (server.getPlayerList().getPlayerByName(name) != null) {
                continue;
            }
            Vec3 pos = portal;
            if (pos == null) {
                double angle = 2 * Math.PI * started / toSpawn;
                pos = master.position().add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
                if (!level.noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(pos))) {
                    pos = master.position(); // ring spot is inside a wall
                }
            }
            FakeSoldier bot = FakeSoldier.spawn(server, level, name, pos, master.getYRot(), master.getUUID());
            server.getScoreboard().addPlayerToTeam(bot.getScoreboardName(), team);
            SoldierGear.equip(bot);
            started++;
        }
        return started;
    }

    /** The soldiers' team: "[Null Soldier]" name prefix; they pass through each other but bump into everyone else. */
    private static PlayerTeam team(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
            team.setPlayerPrefix(Component.translatableWithFallback("nulltotem.soldier.prefix", "[Null Soldier] ").withStyle(ChatFormatting.DARK_PURPLE));
        }
        // 20 crowding a 1-wide bridge would shove the builder off it; and none of them ends up standing inside you
        team.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
        return team;
    }

    private static String randomName(RandomSource random) {
        char[] name = new char[NAME_LENGTH];
        for (int i = 0; i < name.length; i++) {
            name[i] = LETTERS.charAt(random.nextInt(LETTERS.length()));
        }
        return new String(name);
    }

    /** Removes every soldier this master owns. */
    static int clear(ServerPlayer master) {
        return dismiss(master.getServer(), master.getUUID(), Component.literal("Dismissed by " + master.getScoreboardName()));
    }

    /** Every soldier on the server vanishes, whoever summoned them (a Totem of Null popped). */
    static int dismissAll(MinecraftServer server) {
        return dismiss(server, null, Component.literal("Erased by the Totem of Null"));
    }

    private static int dismiss(MinecraftServer server, @Nullable UUID onlyMaster, Component reason) {
        if (onlyMaster == null) {
            orders.clear();
            posts.clear();
            SoldierMining.reset();
            NullBridge.reset();
        } else {
            orders.remove(onlyMaster);
            posts.remove(onlyMaster);
            SoldierMining.cancel(onlyMaster);
            NullBridge.cancel(onlyMaster);
        }
        int removed = 0;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player instanceof FakeSoldier bot && (onlyMaster == null || onlyMaster.equals(bot.master))) {
                bot.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, bot.getX(), bot.getY() + 1, bot.getZ(), 40, 0.3, 0.8, 0.3, 0.05);
                bot.dismiss(reason);
                removed++;
            }
        }
        return removed;
    }

    // ---- the brain ----

    /**
     * One tick of a soldier: eat when low, fight its target, work its mining claim, or else hold its post / walk after
     * the master. Soldiers never teleport, except to follow their master into another dimension.
     */
    private static void control(FakeSoldier bot, UUID masterId, @Nullable ServerPlayer master, int now) {
        // portals never take soldiers anywhere (they follow their master between dimensions by teleport), so standing
        // in the portal they came out of doesn't send them to the Nether
        bot.setPortalCooldown(PORTAL_LOCK);
        if (eat(bot, now)) {
            return;
        }
        if (NullBridge.work(bot, masterId, master, now)) {
            return; // the builder keeps building; the rest of the squad does the fighting
        }
        LivingEntity target = target(bot, masterId, master, now);
        if (target != null) {
            SoldierMining.pause(bot);
            fight(bot, target);
            return;
        }
        if (SoldierMining.work(bot, masterId, master, now)) {
            return;
        }
        selectSlot(bot, SoldierGear.SWORD_SLOT);
        Post post = posts.get(masterId);
        if (post != null && post.dimension() == bot.level().dimension()) {
            walkTo(bot, slot(bot, masterId, post.pos(), POST_DISTANCE - 1), 1);
        } else if (master == null || !master.isAlive()) {
            bot.stopMovement();
        } else if (master.level() != bot.level()) {
            bot.teleportTo(master.serverLevel(), master.getX(), master.getY(), master.getZ(), bot.getYRot(), bot.getXRot());
        } else if (bot.distanceTo(master) < 2) {
            // too close to its master: step back, so it isn't standing in your face
            bot.lookAt(new Vec3(master.getX(), bot.getEyeY(), master.getZ()));
            bot.setForward(-0.5f);
            bot.setStrafing(0);
        } else {
            walkTo(bot, slot(bot, masterId, master.position(), FOLLOW_DISTANCE - 1), 1);
        }
    }

    /**
     * This soldier's own spot on a ring around {@code center}. Soldiers pass through each other, so without it the
     * whole squad would stand in one spot with their name tags piled up.
     */
    private static Vec3 slot(ServerPlayer bot, UUID masterId, Vec3 center, double radius) {
        List<ServerPlayer> squad = soldiersOf(bot.getServer(), masterId);
        int index = Math.max(0, squad.indexOf(bot));
        double angle = 2 * Math.PI * index / Math.max(1, squad.size());
        return center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    /** The soldier's current target, dropping one that's dead, too far away or out of reach for too long; else a threat. */
    @Nullable
    private static LivingEntity target(ServerPlayer bot, UUID masterId, @Nullable ServerPlayer master, int now) {
        UUID id = bot.getUUID();
        LivingEntity target = targets.get(id);
        if (target != null) {
            boolean ordered = orders.get(masterId) == target;
            Vec3 anchor = anchor(bot, masterId, master);
            if (!target.isAlive() || target.isRemoved() || target.level() != bot.level()
                    || (!ordered && target.position().distanceTo(anchor) > LEASH)
                    || outOfReach.getOrDefault(id, 0) > (ordered ? ORDERED_GIVE_UP : GIVE_UP)) {
                targets.remove(id);
                outOfReach.remove(id);
                target = null;
            }
        }
        if (target == null && (now + id.hashCode()) % 10 == 0) {
            target = threat(bot, masterId);
            if (target != null) {
                targets.put(id, target);
            }
        }
        return target;
    }

    /** What the soldier stays near: its post, else its master (when in the same dimension), else itself. */
    private static Vec3 anchor(ServerPlayer bot, UUID masterId, @Nullable ServerPlayer master) {
        Post post = posts.get(masterId);
        if (post != null && post.dimension() == bot.level().dimension()) {
            return post.pos();
        }
        return master != null && master.level() == bot.level() ? master.position() : bot.position();
    }

    /** The closest hostile mob within reach of trouble that is going after the master or one of the master's soldiers. */
    @Nullable
    private static LivingEntity threat(ServerPlayer bot, UUID masterId) {
        return bot.level().getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(DEFEND_RADIUS), mob -> {
            LivingEntity victim = mob.getTarget();
            return mob instanceof Enemy && mob.isAlive() && victim != null
                    && (victim.getUUID().equals(masterId) || masterId.equals(masterOf(victim)));
        }).stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
    }

    /**
     * Melee like a good player: close in, circle the target, swing only at full charge, and jump first so the hit lands
     * as a falling critical. Backs off from a creeper that's about to blow.
     */
    private static void fight(FakeSoldier bot, LivingEntity target) {
        selectSlot(bot, SoldierGear.SWORD_SLOT);
        bot.lookAt(target.getBoundingBox().getCenter());
        boolean inReach = bot.canInteractWithEntity(target, 0);
        if (inReach) {
            outOfReach.put(bot.getUUID(), 0);
        } else {
            outOfReach.merge(bot.getUUID(), 1, Integer::sum);
        }

        if (target instanceof Creeper creeper && creeper.getSwellDir() > 0) {
            bot.setForward(-1);
            bot.setStrafing(0);
            bot.setSprinting(false);
            return;
        }
        bot.setForward(inReach ? 0 : 1);
        bot.setSprinting(!inReach && bot.distanceTo(target) > 5);
        // half the squad circles left, half right, so they spread around the target instead of stacking
        bot.setStrafing(inReach ? ((bot.getUUID().getLeastSignificantBits() & 1) == 0 ? 0.6f : -0.6f) : 0);
        if (!inReach) {
            jumpIfBlocked(bot);
            return;
        }
        if (bot.getAttackStrengthScale(0.5f) < 0.95f) {
            return;
        }
        boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0;
        boolean canJump = bot.onGround() && !bot.isInWater() && bot.level().noCollision(bot, bot.getBoundingBox().move(0, 1, 0));
        if (falling || bot.isInWater() || bot.onClimbable() || (bot.onGround() && !canJump)) {
            bot.swingAt(target);
        } else if (canJump) {
            bot.jump();
        }
    }

    /** Below 3 hearts a soldier backs off and eats a golden apple (if it has any left). True while it's eating. */
    private static boolean eat(FakeSoldier bot, int now) {
        UUID id = bot.getUUID();
        ItemStack apples = bot.getInventory().getItem(SoldierGear.APPLE_SLOT);
        int count = apples.is(Items.GOLDEN_APPLE) ? apples.getCount() : 0;
        Eating meal = eating.get(id);
        if (meal != null) {
            if (count < meal.applesBefore() || now > meal.until() || !bot.isUsingItem()) {
                bot.stopUsingItem();
                eating.remove(id);
                selectSlot(bot, SoldierGear.SWORD_SLOT);
                return false;
            }
            bot.setForward(-1); // backs away from whatever it was facing
            return true;
        }
        if (bot.getHealth() >= LOW_HEALTH || count == 0) {
            return false;
        }
        selectSlot(bot, SoldierGear.APPLE_SLOT);
        bot.setStrafing(0);
        bot.setSprinting(false);
        bot.gameMode.useItem(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        eating.put(id, new Eating(count, now + EAT_TIMEOUT));
        return true;
    }

    /** Walks towards the goal along a route around obstacles (see SoldierPaths), stopping {@code stopAt} short of it. */
    static void walkTo(ServerPlayer player, Vec3 goal, double stopAt) {
        FakeSoldier bot = (FakeSoldier) player;
        if (SoldierMining.climbOut(bot, goal)) {
            return;
        }
        double distance = bot.position().distanceTo(goal);
        bot.setStrafing(0);
        if (distance <= stopAt) {
            bot.setSprinting(false);
            bot.setForward(0);
            return;
        }
        Vec3 step = SoldierPaths.next(bot, goal, bot.getServer().getTickCount());
        Vec3 toward = step != null ? step : goal;
        bot.lookAt(new Vec3(toward.x, bot.getEyeY(), toward.z)); // level gaze, so it walks straight at it
        bot.setForward(1);
        bot.setSprinting(distance > 2 * stopAt + 4);
        if (bot.onGround() && (bot.horizontalCollision || (step != null && step.y > bot.getY() + 0.5))) {
            bot.jump(); // up the step in the way
        }
    }

    static void jumpIfBlocked(ServerPlayer bot) {
        if (bot.horizontalCollision && bot.onGround()) {
            ((FakeSoldier) bot).jump(); // step up the block in the way
        }
    }

    static void selectSlot(ServerPlayer bot, int slot) {
        bot.getInventory().selected = slot;
    }
}
