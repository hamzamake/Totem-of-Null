package dev.nulltotem;

import carpet.helpers.EntityPlayerActionPack;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SoldierHandler {
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
    private static final int SPAWN_TIMEOUT = 200;      // ticks to wait for Carpet's asynchronous spawn
    private static final int PORTAL_LOCK = 20;

    /** A bot Carpet is still spawning; master is null once its master cleared them, so it's dismissed on arrival. */
    private record Pending(@Nullable UUID master, int expiresAt) {
    }

    /** Where a master told their soldiers to go and wait. */
    private record Post(ResourceKey<Level> dimension, Vec3 pos) {
    }

    private record Eating(int applesBefore, int until) {
    }

    private static final Map<String, Pending> pending = new HashMap<>();
    private static final Map<UUID, LivingEntity> targets = new HashMap<>();  // soldier -> what it's fighting
    private static final Map<UUID, Integer> outOfReach = new HashMap<>();    // soldier -> ticks it couldn't reach its target
    private static final Map<UUID, LivingEntity> orders = new HashMap<>();   // master -> mob they sent the soldiers at
    private static final Map<UUID, Post> posts = new HashMap<>();            // master -> where the soldiers wait
    private static final Map<UUID, Eating> eating = new HashMap<>();         // soldier -> golden apple in progress

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SoldierHandler::tick);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer master && entity instanceof LivingEntity target) {
                command(master, target, false);
            }
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayer hurt) || !(source.getEntity() instanceof LivingEntity attacker)) {
                return;
            }
            if (masterOf(hurt) == null) {
                command(hurt, attacker, false); // a master was hurt: the whole squad answers
            } else if (!isFriendly(hurt, attacker) && targets.get(hurt.getUUID()) == null) {
                targets.put(hurt.getUUID(), attacker); // a soldier was hurt: it hits back
            }
        });
        // Neutrality: a soldier's hit (or sword sweep) never lands on its master or its master's other soldiers
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(source.getEntity() instanceof ServerPlayer attacker && isFriendly(attacker, entity)));
        // Registered after TotemHandler's, so this only runs when the death goes ahead: empty the bot so no gear drops
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer bot && masterOf(bot) != null) {
                bot.getInventory().clearContent();
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> SoldierMining.onBreak(player, pos, state));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            pending.clear();
            targets.clear();
            outOfReach.clear();
            orders.clear();
            posts.clear();
            eating.clear();
            SoldierMining.reset();
            SoldierPaths.reset();
        });
    }

    @Nullable
    public static UUID masterOf(Entity entity) {
        return entity instanceof ServerPlayer player && CarpetIntegration.isFake(player) ? player.getAttached(SoldierAttachments.MASTER_UUID) : null;
    }

    /** A bot Carpet is spawning for us right now (its join message is kept out of chat). */
    public static boolean isArriving(String name) {
        return pending.keySet().stream().anyMatch(name::equalsIgnoreCase);
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
        for (ServerPlayer bot : soldiersOf(master.level().getServer(), master.getUUID())) {
            targets.put(bot.getUUID(), target);
            outOfReach.remove(bot.getUUID());
        }
        if (ordered) {
            orders.put(master.getUUID(), target);
        }
    }

    // ---- orders (see NullOrders) ----

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
        LivingEntity ordered = orders.get(master);
        String doing = mining != null ? mining
                : ordered != null && ordered.isAlive() ? "attacking " + ordered.getName().getString()
                : posts.containsKey(master) ? "holding the spot you sent them to"
                : "following you";
        return "You lead " + count + " Null Soldier(s), " + doing + ".";
    }

    // ---- spawning and dismissing ----

    /** Starts up to {@code amount} Carpet fake players, capped so the master never has more than the configured max. */
    static int spawn(ServerPlayer master, int amount) {
        MinecraftServer server = master.level().getServer();
        NullServerState state = NullServerState.get(server);
        UUID id = master.getUUID();
        long have = state.soldiers.getOrDefault(id, List.of()).stream().filter(n -> server.getPlayerList().getPlayerByName(n) != null).count()
                + pending.values().stream().filter(p -> id.equals(p.master())).count();
        long toSpawn = Math.min(amount, state.maxSoldiers() - have);
        if (toSpawn <= 0) {
            return 0;
        }
        Vec3 portal = NullPortal.exit(master);
        if (portal == null) {
            master.sendSystemMessage(Component.literal("No room for a Null portal here, so your soldiers arrive beside you.")
                    .withStyle(ChatFormatting.GRAY));
        }
        RandomSource random = master.getRandom();
        int started = 0;
        for (int tries = 0; started < toSpawn && tries < toSpawn * 10; tries++) {
            String name = randomName(random);
            if (taken(server, state, name)) {
                continue;
            }
            Vec3 pos = portal;
            if (pos == null) {
                double angle = 2 * Math.PI * started / toSpawn;
                pos = master.position().add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
                if (!master.level().noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(pos))) {
                    pos = master.position(); // ring spot is inside a wall
                }
            }
            if (CarpetIntegration.spawn(name, master, pos)) {
                pending.put(name, new Pending(id, server.getTickCount() + SPAWN_TIMEOUT));
                started++;
            }
        }
        return started;
    }

    private static String randomName(RandomSource random) {
        char[] name = new char[NAME_LENGTH];
        for (int i = 0; i < name.length; i++) {
            name[i] = LETTERS.charAt(random.nextInt(LETTERS.length()));
        }
        return new String(name);
    }

    private static boolean taken(MinecraftServer server, NullServerState state, String name) {
        return server.getPlayerList().getPlayerByName(name) != null || pending.containsKey(name) || CarpetIntegration.isSpawning(name)
                || state.soldiers.values().stream().anyMatch(names -> names.contains(name));
    }

    /** Removes every soldier this master owns, like {@code /player <name> kill} on each. */
    static int clear(ServerPlayer master) {
        return dismiss(master.level().getServer(), master.getUUID(), Component.literal("Dismissed by " + master.getScoreboardName()));
    }

    /** Every soldier on the server vanishes, whoever summoned them (a Totem of Null popped). */
    static int dismissAll(MinecraftServer server) {
        return dismiss(server, null, Component.literal("Erased by the Totem of Null"));
    }

    /** Removes the soldiers of {@code onlyMaster}, or of everyone when null, including any still being spawned. */
    private static int dismiss(MinecraftServer server, @Nullable UUID onlyMaster, Component reason) {
        pending.replaceAll((name, p) -> onlyMaster == null || onlyMaster.equals(p.master()) ? new Pending(null, p.expiresAt()) : p);
        if (onlyMaster == null) {
            orders.clear();
            posts.clear();
            SoldierMining.reset();
        } else {
            orders.remove(onlyMaster);
            posts.remove(onlyMaster);
            SoldierMining.cancel(onlyMaster);
        }
        int removed = 0;
        // forgetGone() drops the names from the saved list and the team once Carpet has disconnected them
        for (ServerPlayer bot : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID master = masterOf(bot);
            if (master != null && (onlyMaster == null || onlyMaster.equals(master))) {
                bot.level().sendParticles(ParticleTypes.REVERSE_PORTAL, bot.getX(), bot.getY() + 1, bot.getZ(), 40, 0.3, 0.8, 0.3, 0.05);
                CarpetIntegration.remove(bot, reason);
                targets.remove(bot.getUUID());
                removed++;
            }
        }
        return removed;
    }

    private static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        pending.entrySet().removeIf(e -> {
            ServerPlayer bot = server.getPlayerList().getPlayerByName(e.getKey());
            if (bot != null && CarpetIntegration.isFake(bot)) {
                if (e.getValue().master() == null) {
                    CarpetIntegration.remove(bot, Component.literal("Dismissed"));
                } else {
                    enlist(server, bot, e.getValue().master());
                }
                return true;
            }
            return now > e.getValue().expiresAt() && !CarpetIntegration.isSpawning(e.getKey());
        });
        SoldierMining.deliver(server);
        if (now % 20 == 0) {
            forgetGone(server);
        }
        for (ServerPlayer bot : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID master = masterOf(bot);
            if (master != null && bot.isAlive()) {
                control(bot, master, server.getPlayerList().getPlayer(master), now);
            }
        }
    }

    /** Done a tick after Carpet's spawn, once it has loaded any saved data for this name, so nothing overwrites it. */
    private static void enlist(MinecraftServer server, ServerPlayer bot, UUID master) {
        bot.setAttached(SoldierAttachments.MASTER_UUID, master); // first: it's what keeps the armour's advancement quiet
        SoldierGear.equip(bot);
        // a reused name has the old bot's UUID
        targets.remove(bot.getUUID());
        outOfReach.remove(bot.getUUID());
        eating.remove(bot.getUUID());

        // Players always render their account name, so "Null Soldier" goes above them as a team prefix
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
            team.setPlayerPrefix(Component.translatableWithFallback("nulltotem.soldier.prefix", "[Null Soldier] ").withStyle(ChatFormatting.DARK_PURPLE));
        }
        scoreboard.addPlayerToTeam(bot.getScoreboardName(), team);

        NullServerState state = NullServerState.get(server);
        state.soldiers.computeIfAbsent(master, k -> new ArrayList<>()).add(bot.getScoreboardName());
        state.setDirty();
    }

    /**
     * Drops soldiers that are gone (died, /player kill, server restart) from the saved list and the team, so their
     * names can be reused. Polled because Carpet's fake connection never fires Fabric's DISCONNECT event.
     */
    private static void forgetGone(MinecraftServer server) {
        NullServerState state = NullServerState.get(server);
        Scoreboard scoreboard = server.getScoreboard();
        for (List<String> names : state.soldiers.values()) {
            names.removeIf(name -> {
                if (server.getPlayerList().getPlayerByName(name) != null || pending.containsKey(name)) {
                    return false;
                }
                PlayerTeam team = scoreboard.getPlayersTeam(name);
                if (team != null && team.getName().equals(TEAM)) {
                    scoreboard.removePlayerFromTeam(name, team);
                }
                state.setDirty();
                return true;
            });
        }
        state.soldiers.values().removeIf(List::isEmpty);
    }

    // ---- the brain ----

    /**
     * One tick of a soldier: eat when low, fight its target, work its mining claim, or else hold its post / walk after
     * the master. Soldiers never teleport, except to follow their master into another dimension.
     */
    private static void control(ServerPlayer bot, UUID masterId, @Nullable ServerPlayer master, int now) {
        // portals never take soldiers anywhere (they follow their master between dimensions by teleport), so standing
        // in the portal they came out of doesn't send them to the Nether
        bot.setPortalCooldown(PORTAL_LOCK);
        if (eat(bot, now)) {
            return;
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
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        Post post = posts.get(masterId);
        if (post != null && post.dimension() == bot.level().dimension()) {
            walkTo(bot, post.pos(), POST_DISTANCE);
        } else if (master == null || !master.isAlive()) {
            actions.stopMovement();
        } else if (master.level() != bot.level()) {
            bot.teleportTo(master.level(), master.getX(), master.getY(), master.getZ(), Set.of(), bot.getYRot(), bot.getXRot(), true);
        } else {
            walkTo(bot, master.position(), FOLLOW_DISTANCE);
        }
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
    private static void fight(ServerPlayer bot, LivingEntity target) {
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        selectSlot(bot, SoldierGear.SWORD_SLOT);
        actions.lookAt(target.getBoundingBox().getCenter()); // ATTACK hits whatever the bot looks at
        boolean inReach = bot.isWithinEntityInteractionRange(target, 0);
        if (inReach) {
            outOfReach.put(bot.getUUID(), 0);
        } else {
            outOfReach.merge(bot.getUUID(), 1, Integer::sum);
        }

        if (target instanceof Creeper creeper && creeper.getSwellDir() > 0) {
            actions.setForward(-1);
            actions.setStrafing(0);
            actions.setSprinting(false);
            return;
        }
        actions.setForward(inReach ? 0 : 1);
        actions.setSprinting(!inReach && bot.distanceTo(target) > 5);
        // half the squad circles left, half right, so they spread around the target instead of stacking
        actions.setStrafing(inReach ? ((bot.getUUID().getLeastSignificantBits() & 1) == 0 ? 0.6f : -0.6f) : 0);
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
            CarpetIntegration.attackOnce(bot);
        } else if (canJump) {
            CarpetIntegration.jump(bot);
        }
    }

    /** Below 3 hearts a soldier backs off and eats a golden apple (if it has any left). True while it's eating. */
    private static boolean eat(ServerPlayer bot, int now) {
        UUID id = bot.getUUID();
        ItemStack apples = bot.getInventory().getItem(SoldierGear.APPLE_SLOT);
        int count = apples.is(Items.GOLDEN_APPLE) ? apples.getCount() : 0;
        Eating meal = eating.get(id);
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        if (meal != null) {
            if (count < meal.applesBefore() || now > meal.until()) {
                CarpetIntegration.stopUse(bot);
                eating.remove(id);
                selectSlot(bot, SoldierGear.SWORD_SLOT);
                return false;
            }
            actions.setForward(-1); // backs away from whatever it was facing
            return true;
        }
        if (bot.getHealth() >= LOW_HEALTH || count == 0) {
            return false;
        }
        CarpetIntegration.stopAttack(bot);
        selectSlot(bot, SoldierGear.APPLE_SLOT);
        actions.look(bot.getYRot(), 90); // at the ground, so "use" eats instead of interacting with a mob
        actions.setStrafing(0);
        actions.setSprinting(false);
        CarpetIntegration.holdUse(bot);
        eating.put(id, new Eating(count, now + EAT_TIMEOUT));
        return true;
    }

    /** Walks towards the goal along a route around obstacles (see SoldierPaths), stopping {@code stopAt} short of it. */
    static void walkTo(ServerPlayer bot, Vec3 goal, double stopAt) {
        if (SoldierMining.climbOut(bot, goal)) {
            return;
        }
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        double distance = bot.position().distanceTo(goal);
        actions.setStrafing(0);
        if (distance <= stopAt) {
            actions.setForward(0);
            actions.setSprinting(false);
            return;
        }
        Vec3 step = SoldierPaths.next(bot, goal, bot.level().getServer().getTickCount());
        Vec3 toward = step != null ? step : goal;
        actions.lookAt(new Vec3(toward.x, bot.getEyeY(), toward.z)); // level gaze, so it walks straight at it
        actions.setForward(1);
        actions.setSprinting(distance > 2 * stopAt + 4);
        if (bot.onGround() && (bot.horizontalCollision || (step != null && step.y > bot.getY() + 0.5))) {
            CarpetIntegration.jump(bot); // up the step in the way
        }
    }

    static void jumpIfBlocked(ServerPlayer bot) {
        if (bot.horizontalCollision && bot.onGround()) {
            CarpetIntegration.jump(bot); // step up the block in the way
        }
    }

    static void selectSlot(ServerPlayer bot, int slot) {
        Inventory inventory = bot.getInventory();
        if (inventory.getSelectedSlot() != slot) {
            inventory.setSelectedSlot(slot);
        }
    }
}
