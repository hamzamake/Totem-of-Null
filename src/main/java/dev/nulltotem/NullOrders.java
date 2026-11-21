package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Everything a master can tell their soldiers, from the totem's clicks and from the orders menu (a vanilla server
 * dialog, so no client code is needed for it). Every order answers with an action-bar message.
 */
final class NullOrders {
    private static final int RANGE = 64;
    private static final int CLICK_COOLDOWN = 10;

    /** How to use the totem; shown in the orders menu (the item tooltip says the same). */
    static final List<String> CONTROLS = List.of(
            "Sneak + right-click: off hand summons, main hand opens this menu",
            "Sneak + left-click, off hand: send them where you look (again: call back)",
            "Sneak + left-click, main hand: netherrack bridge");

    // ---- the totem's sneak + left-click (sent by the client, see TotemClickPayload) ----

    static void click(ServerPlayer player) {
        InteractionHand hand = ModItems.isCurrent(player.getMainHandItem(), player.level().getServer()) ? InteractionHand.MAIN_HAND
                : ModItems.isCurrent(player.getOffhandItem(), player.level().getServer()) ? InteractionHand.OFF_HAND : null;
        if (hand == null || player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) {
            return;
        }
        ItemStack totem = player.getItemInHand(hand);
        player.getCooldowns().addCooldown(totem, CLICK_COOLDOWN);
        if (hand == InteractionHand.MAIN_HAND) {
            int placed = NullBridge.build(player);
            tell(player, placed > 0 ? "Bridged " + placed + " block(s) of netherrack" : "Nothing to bridge there: look at open air or across a gap");
        } else {
            sendOrRecall(player);
        }
    }

    /** Off-hand sneak + left-click: attack the looked-at mob, or hold the looked-at spot; a second click calls them back. */
    private static void sendOrRecall(ServerPlayer master) {
        if (!hasSoldiers(master)) {
            return;
        }
        if (SoldierHandler.isCommanded(master.getUUID())) {
            SoldierHandler.follow(master);
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
            SoldierHandler.hold(master, pos);
            master.level().sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 0.5, pos.z, 60, 0.4, 0.6, 0.4, 0.02);
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
        SoldierHandler.follow(master);
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
        SoldierHandler.attack(master, mob);
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false)); // so you can see who they're after
        tell(master, "Your Null Soldiers are attacking " + mob.getName().getString());
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
        if (!holdsTotem(master)) {
            tell(master, "Hold the Totem of Null to summon soldiers");
            return 0;
        }
        int started = SoldierHandler.spawn(master, NullServerState.HARD_CAP);
        int max = NullServerState.get(master.level().getServer()).maxSoldiers();
        tell(master, started > 0 ? "Summoning " + started + " Null Soldier(s)" : "You already lead the max of " + max + " Null Soldiers");
        return started;
    }

    static int dismiss(ServerPlayer master) {
        int removed = SoldierHandler.clear(master);
        tell(master, "Dismissed " + removed + " Null Soldier(s)");
        return removed;
    }

    /** The orders menu: status, the controls, and one button per order. */
    static int openMenu(ServerPlayer player) {
        List<DialogBody> body = new java.util.ArrayList<>();
        body.add(new PlainMessage(Component.literal(SoldierHandler.status(player.level().getServer(), player.getUUID())), 300));
        body.add(new PlainMessage(Component.literal(String.join("\n", CONTROLS)).withStyle(ChatFormatting.GRAY), 300));
        List<ActionButton> buttons = List.of(
                button("Follow me", "Stop whatever they're doing and walk with you", "follow"),
                button("Attack what I look at", "A mob up to " + RANGE + " blocks away; it glows", "attack"),
                button("Mine blocks like this", "Every block like the one you look at, within 16 blocks of it. The drops go to you", "mine"),
                button("Summon soldiers", "Up to your max, out of a nether portal", "summon"),
                button("Dismiss soldiers", "They return to the void", "dismiss"));
        // two columns and short lines, so every button fits on screen without scrolling
        MultiActionDialog dialog = new MultiActionDialog(
                new CommonDialogData(Component.literal("Null Soldier Orders"), Optional.empty(), true, false, DialogAction.CLOSE, body, List.of()),
                buttons, Optional.empty(), 2);
        player.openDialog(Holder.direct(dialog));
        return 1;
    }

    // a fixed command, so a plain run-command action (a CommandTemplate must contain a $(variable))
    private static ActionButton button(String label, String tooltip, String order) {
        return new ActionButton(new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), 150),
                Optional.of(new StaticAction(new ClickEvent.RunCommand("/null order " + order))));
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
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && !master.getUUID().equals(SoldierHandler.masterOf(e)),
                distance * distance);
        return hit != null && hit.getEntity() instanceof LivingEntity mob ? mob : null;
    }

    private static boolean hasSoldiers(ServerPlayer master) {
        if (!SoldierHandler.soldiersOf(master.level().getServer(), master.getUUID()).isEmpty()) {
            return true;
        }
        tell(master, "You have no Null Soldiers. Sneak + right-click with the totem in your off hand to summon them");
        return false;
    }

    private static boolean holdsTotem(ServerPlayer player) {
        return ModItems.isCurrent(player.getMainHandItem(), player.level().getServer())
                || ModItems.isCurrent(player.getOffhandItem(), player.level().getServer());
    }

    private static void tell(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.DARK_PURPLE), true);
    }
}
