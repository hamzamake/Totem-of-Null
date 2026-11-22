package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a master can tell their soldiers, from the totem's clicks and from the orders menu. Every order answers
 * with an action-bar message. Sneak + right-click is a normal item use the server sees; sneak + left-click is sent by
 * the client as a {@link Payloads.TotemClick} (a left-click on air never reaches the server on its own).
 */
public final class Orders {
    static final int RANGE = 64;
    private static final int CLICK_COOLDOWN = 10;
    private static final int SUMMON_COOLDOWN = 40;

    /** The totem held in a hand: any Totem of Null on the client, only the current one on the server. */
    @Nullable
    static InteractionHand totemHand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player instanceof ServerPlayer server ? Totems.isCurrent(player.getItemInHand(hand), server.getServer())
                    : Totems.serialOf(player.getItemInHand(hand)) != 0) {
                return hand;
            }
        }
        return null;
    }

    /** Sneak + right-click with the totem: off hand summons, main hand opens the orders menu. Runs on both sides. */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || Totems.serialOf(event.getItemStack()) == 0) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS); // and the other hand isn't tried
        if (!(player instanceof ServerPlayer master) || !Totems.isCurrent(event.getItemStack(), master.getServer())) {
            return;
        }
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            openMenu(master);
        } else if (!master.getCooldowns().isOnCooldown(Items.TOTEM_OF_UNDYING)) {
            master.getCooldowns().addCooldown(Items.TOTEM_OF_UNDYING, SUMMON_COOLDOWN);
            summon(master);
        }
    }

    static void click(ServerPlayer player) {
        InteractionHand hand = totemHand(player);
        if (hand == null || player.getCooldowns().isOnCooldown(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        player.getCooldowns().addCooldown(Items.TOTEM_OF_UNDYING, CLICK_COOLDOWN);
        if (hand == InteractionHand.MAIN_HAND) {
            bridge(player);
        } else {
            sendOrRecall(player);
        }
    }

    /** Off-hand sneak + left-click: attack the looked-at mob, or hold the looked-at spot; a second click calls them back. */
    private static void sendOrRecall(ServerPlayer master) {
        if (!hasSoldiers(master)) {
            return;
        }
        if (Soldiers.isCommanded(master.getUUID())) {
            Soldiers.follow(master);
            tell(master, "Your Null Soldiers are following you again");
            return;
        }
        LivingEntity mob = lookedAtMob(master);
        if (mob != null) {
            attack(master, mob);
            return;
        }
        if (master.pick(RANGE, 0, false) instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            BlockPos spot = block.getBlockPos().relative(block.getDirection());
            Vec3 pos = Vec3.atBottomCenterOf(spot);
            Soldiers.hold(master, pos);
            master.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 0.5, pos.z, 60, 0.4, 0.6, 0.4, 0.02);
            tell(master, "Your Null Soldiers are heading to " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                    + ". Sneak + left-click again to call them back");
            return;
        }
        tell(master, "Look at a block or a mob (up to " + RANGE + " blocks away)");
    }

    // ---- orders (the menu's buttons run /null order <name>) ----

    static int follow(ServerPlayer master) {
        if (!hasSoldiers(master)) {
            return 0;
        }
        Soldiers.follow(master);
        tell(master, "Your Null Soldiers are following you");
        return 1;
    }

    static int attackLooked(ServerPlayer master) {
        if (!hasSoldiers(master)) {
            return 0;
        }
        LivingEntity mob = lookedAtMob(master);
        if (mob == null) {
            tell(master, "Look at a mob (up to " + RANGE + " blocks away), then choose Attack");
            return 0;
        }
        attack(master, mob);
        return 1;
    }

    private static void attack(ServerPlayer master, LivingEntity mob) {
        Soldiers.attack(master, mob);
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false)); // so you can see who they're after
        tell(master, "Your Null Soldiers are attacking " + mob.getName().getString());
    }

    /** A soldier builds a netherrack bridge from the master's feet to where they look. */
    static int bridge(ServerPlayer master) {
        int blocks = NullBridge.start(master);
        tell(master, blocks < 0 ? "Your Null Soldiers build bridges: summon some first (sneak + right-click, off hand)"
                : blocks == 0 ? "Nothing to bridge there: look at open air or across a gap"
                : "Your Null Soldier is building a " + blocks + "-block bridge");
        return Math.max(blocks, 0);
    }

    static int mineLooked(ServerPlayer master) {
        if (!hasSoldiers(master)) {
            return 0;
        }
        if (!(master.pick(32, 0, false) instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) {
            tell(master, "Look at a block (up to 32 blocks away), then choose Mine");
            return 0;
        }
        String name = master.level().getBlockState(block.getBlockPos()).getBlock().getName().getString();
        int found = SoldierMining.start(master, block.getBlockPos());
        if (found == 0) {
            tell(master, name + " can't be mined");
            return 0;
        }
        tell(master, "Your Null Soldiers are mining " + found + " " + name + ". It all goes into your inventory");
        return found;
    }

    static int summon(ServerPlayer master) {
        if (totemHand(master) == null) {
            tell(master, "Hold the Totem of Null to summon soldiers");
            return 0;
        }
        int started = Soldiers.spawn(master, NullState.HARD_CAP);
        int max = NullState.get(master.getServer()).maxSoldiers();
        tell(master, started > 0 ? "Summoning " + started + " Null Soldier(s)" : "You already lead the max of " + max + " Null Soldiers");
        return started;
    }

    static int dismiss(ServerPlayer master) {
        int removed = Soldiers.clear(master);
        tell(master, "Dismissed " + removed + " Null Soldier(s)");
        return removed;
    }

    /** The orders menu (a client screen, see OrdersScreen): it shows the status and one button per order. */
    static int openMenu(ServerPlayer player) {
        Payloads.sendTo(player, new Payloads.OpenOrders(Soldiers.status(player.getServer(), player.getUUID())));
        return 1;
    }

    // ---- helpers ----

    /** The living thing in the master's crosshair, up to 64 blocks and not behind a wall; never one of their own. */
    @Nullable
    private static LivingEntity lookedAtMob(ServerPlayer master) {
        Vec3 eye = master.getEyePosition();
        Vec3 look = master.getViewVector(1);
        HitResult block = master.pick(RANGE, 0, false);
        double distance = block.getType() == HitResult.Type.MISS ? RANGE : block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(look.scale(distance));
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(master, eye, end, master.getBoundingBox().expandTowards(look.scale(distance)).inflate(1),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && !master.getUUID().equals(Soldiers.masterOf(e)),
                distance * distance);
        return hit != null && hit.getEntity() instanceof LivingEntity mob ? mob : null;
    }

    private static boolean hasSoldiers(ServerPlayer master) {
        if (!Soldiers.soldiersOf(master.getServer(), master.getUUID()).isEmpty()) {
            return true;
        }
        tell(master, "You have no Null Soldiers. Sneak + right-click with the totem in your off hand to summon them");
        return false;
    }

    private static void tell(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.DARK_PURPLE), true);
    }
}
