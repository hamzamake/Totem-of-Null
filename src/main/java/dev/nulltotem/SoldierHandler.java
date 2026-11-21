package dev.nulltotem;

import carpet.helpers.EntityPlayerActionPack;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SoldierHandler {
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int NAME_LENGTH = 9;
    /** Scoreboard team of every soldier; it shows "[Null Soldier]" before the name and the client gives it the black skin. */
    public static final String TEAM = "null_soldiers";
    private static final double TELEPORT_DISTANCE = 30; // follow on foot inside this, teleport beyond it
    private static final double FOLLOW_DISTANCE = 4;
    private static final double REACH = 2.5;
    private static final int ATTACK_INTERVAL = 13; // a netherite sword's full charge (1.6 attacks/s)
    private static final int SPAWN_TIMEOUT = 200;  // ticks to wait for Carpet's asynchronous spawn
    private static final int PORTAL_LOCK = 20;

    /** A bot Carpet is still spawning; master is null once its master cleared them, so it's dismissed on arrival. */
    private record Pending(@Nullable UUID master, int expiresAt) {
    }

    private static final Map<String, Pending> pending = new HashMap<>();
    private static final Map<UUID, LivingEntity> targets = new HashMap<>(); // master -> what their soldiers attack
    private static final Set<UUID> fighting = new HashSet<>();             // soldiers whose ATTACK action is running

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SoldierHandler::tick);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer master && entity instanceof LivingEntity target) {
                command(master, target);
            }
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer master && source.getEntity() instanceof LivingEntity attacker) {
                command(master, attacker);
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
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            pending.clear();
            targets.clear();
            fighting.clear();
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

    /** Points every soldier of this master at the target, unless the target is on the master's side. */
    private static void command(ServerPlayer master, LivingEntity target) {
        if (target == master || masterOf(master) != null || master.getUUID().equals(masterOf(target))
                || !NullServerState.get(master.level().getServer()).soldiers.containsKey(master.getUUID())) {
            return;
        }
        targets.put(master.getUUID(), target);
    }

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
            targets.clear();
        } else {
            targets.remove(onlyMaster);
        }
        int removed = 0;
        // forgetGone() drops the names from the saved list and the team once Carpet has disconnected them
        for (ServerPlayer bot : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID master = masterOf(bot);
            if (master != null && (onlyMaster == null || onlyMaster.equals(master))) {
                bot.level().sendParticles(ParticleTypes.REVERSE_PORTAL, bot.getX(), bot.getY() + 1, bot.getZ(), 40, 0.3, 0.8, 0.3, 0.05);
                CarpetIntegration.remove(bot, reason);
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
        if (now % 20 == 0) {
            forgetGone(server);
        }
        for (ServerPlayer bot : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID master = masterOf(bot);
            if (master != null && bot.isAlive()) {
                control(bot, server.getPlayerList().getPlayer(master));
            }
        }
    }

    /** Done a tick after Carpet's spawn, once it has loaded any saved data for this name, so nothing overwrites it. */
    private static void enlist(MinecraftServer server, ServerPlayer bot, UUID master) {
        bot.setAttached(SoldierAttachments.MASTER_UUID, master); // first: it's what keeps the armour's advancement quiet
        SoldierGear.equip(bot);
        fighting.remove(bot.getUUID()); // a reused name has the old bot's UUID, but a fresh action pack with no ATTACK running

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

    /** One tick of a soldier's brain: fight the master's target, otherwise stay with the master. */
    private static void control(ServerPlayer bot, @Nullable ServerPlayer master) {
        EntityPlayerActionPack actions = CarpetIntegration.actions(bot);
        // portals never take soldiers anywhere (they follow their master between dimensions by teleport), so standing
        // in the portal they came out of doesn't send them to the Nether
        bot.setPortalCooldown(PORTAL_LOCK);
        LivingEntity target = master == null ? null : targets.get(master.getUUID());
        if (target != null && (!target.isAlive() || target.level() != master.level() || target.distanceTo(master) > TELEPORT_DISTANCE)) {
            targets.remove(master.getUUID());
            target = null;
        }

        if (target != null && target.level() == bot.level()) {
            actions.lookAt(target.getBoundingBox().getCenter()); // ATTACK hits whatever the bot looks at
            double distance = bot.distanceTo(target);
            actions.setForward(distance > REACH ? 1 : 0);
            actions.setSprinting(distance > FOLLOW_DISTANCE);
            if (fighting.add(bot.getUUID())) {
                CarpetIntegration.attackEvery(bot, ATTACK_INTERVAL);
            }
        } else {
            if (fighting.remove(bot.getUUID())) {
                actions.stopAll();
            }
            if (master == null || !master.isAlive()) {
                actions.stopMovement();
                return;
            }
            if (master.level() != bot.level() || bot.distanceTo(master) > TELEPORT_DISTANCE) {
                bot.teleportTo(master.level(), master.getX(), master.getY(), master.getZ(), Set.of(), bot.getYRot(), bot.getXRot(), true);
                return;
            }
            double distance = bot.distanceTo(master);
            actions.lookAt(master.getEyePosition());
            actions.setForward(distance > FOLLOW_DISTANCE ? 1 : 0);
            actions.setSprinting(distance > 2 * FOLLOW_DISTANCE);
        }
        if (bot.horizontalCollision && bot.onGround()) {
            CarpetIntegration.jump(bot); // step up the block in the way
        }
    }
}
