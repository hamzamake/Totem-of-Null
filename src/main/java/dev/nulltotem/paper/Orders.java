package dev.nulltotem.paper;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Everything a master can tell their soldiers: the totem's sneak-clicks (detected on the server, since Paper players
 * have vanilla clients), the orders menu and the settings window (Paper dialogs), and the /null order commands.
 */
final class Orders implements Listener {
    private static final int RANGE = 64;
    private static final int CLICK_COOLDOWN = 10;
    static final List<String> CONTROLS = List.of(
            "Sneak + right-click: off hand summons, main hand opens this menu",
            "Sneak + left-click, off hand: send them where you look (again: call back)",
            "Sneak + left-click, main hand: netherrack bridge");

    private final NullTotemPlugin plugin;
    private final Map<UUID, Integer> lastClick = new HashMap<>();

    Orders(NullTotemPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean holdsTotem(Player player, EquipmentSlot hand) {
        return plugin.totems().isCurrent(player.getInventory().getItem(hand));
    }

    private boolean holdsTotem(Player player) {
        return holdsTotem(player, EquipmentSlot.HAND) || holdsTotem(player, EquipmentSlot.OFF_HAND);
    }

    // ---- the totem's clicks ----

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || event.getHand() == null) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (event.getHand() == EquipmentSlot.OFF_HAND && holdsTotem(player, EquipmentSlot.OFF_HAND)) {
                event.setCancelled(true);
                if (cooldown(player, 40)) {
                    summon(player);
                }
            } else if (event.getHand() == EquipmentSlot.HAND && holdsTotem(player, EquipmentSlot.HAND)) {
                event.setCancelled(true);
                openMenu(player);
            }
        } else if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) && holdsTotem(player)) {
            event.setCancelled(true); // an order, not a punch or the start of mining
            leftClick(player, null);
        }
    }

    /** Sneak + left-click on a mob: the click is an order, so the hit doesn't land. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitWhileOrdering(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && player.isSneaking() && holdsTotem(player) && !plugin.soldiers().isSoldier(player)) {
            event.setCancelled(true);
            leftClick(player, event.getEntity());
        }
    }

    // holding the button after the click must not mine the block either (instantly, in creative)
    @EventHandler(priority = EventPriority.LOW)
    public void onDig(BlockDamageEvent event) {
        if (event.getPlayer().isSneaking() && holdsTotem(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().isSneaking() && holdsTotem(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean cooldown(Player player, int ticks) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < ticks) {
            return false;
        }
        lastClick.put(player.getUniqueId(), now);
        return true;
    }

    private void leftClick(Player player, @Nullable Entity clicked) {
        if (!cooldown(player, CLICK_COOLDOWN)) {
            return;
        }
        if (holdsTotem(player, EquipmentSlot.HAND)) {
            bridge(player);
        } else {
            sendOrRecall(player, clicked instanceof LivingEntity mob ? mob : null);
        }
    }

    /** Off-hand sneak + left-click: attack the looked-at mob, or hold the looked-at spot; a second click calls them back. */
    private void sendOrRecall(Player master, @Nullable LivingEntity clicked) {
        if (!hasSoldiers(master)) {
            return;
        }
        Soldiers soldiers = plugin.soldiers();
        if (soldiers.isCommanded(master.getUniqueId())) {
            soldiers.follow(master);
            tell(master, "Your Null Soldiers are following you again");
            return;
        }
        LivingEntity mob = clicked != null ? clicked : lookedAtMob(master);
        if (mob != null && !soldiers.isFriendly(master.getUniqueId(), mob)) {
            attack(master, mob);
            return;
        }
        RayTraceResult hit = master.rayTraceBlocks(RANGE, FluidCollisionMode.NEVER);
        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            Block spot = hit.getHitBlock().getRelative(hit.getHitBlockFace());
            Location pos = spot.getLocation().add(0.5, 0, 0.5);
            soldiers.hold(master, pos);
            master.getWorld().spawnParticle(Particle.REVERSE_PORTAL, pos.clone().add(0, 0.5, 0), 60, 0.4, 0.6, 0.4, 0.02);
            tell(master, "Your Null Soldiers are heading to " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                    + ". Sneak + left-click again to call them back");
            return;
        }
        tell(master, "Look at a block or a mob (up to " + RANGE + " blocks away)");
    }

    // ---- orders ----

    int follow(Player master) {
        if (!hasSoldiers(master)) {
            return 0;
        }
        plugin.soldiers().follow(master);
        tell(master, "Your Null Soldiers are following you");
        return 1;
    }

    int attackLooked(Player master) {
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

    private void attack(Player master, LivingEntity mob) {
        plugin.soldiers().command(master, mob, true);
        mob.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false)); // so you can see who they're after
        tell(master, "Your Null Soldiers are attacking " + mob.getName());
    }

    int mineLooked(Player master) {
        if (!hasSoldiers(master)) {
            return 0;
        }
        Block block = master.getTargetBlockExact(32, FluidCollisionMode.NEVER);
        if (block == null) {
            tell(master, "Look at a block (up to 32 blocks away), then choose Mine");
            return 0;
        }
        String name = Mining.niceName(block.getType());
        int found = plugin.mining().start(master, block);
        if (found == 0) {
            tell(master, name + " can't be mined");
            return 0;
        }
        tell(master, "Your Null Soldiers are mining " + found + " " + name + ". It all goes into your inventory");
        return found;
    }

    int bridge(Player master) {
        int blocks = plugin.bridges().start(master);
        tell(master, blocks < 0 ? "Your Null Soldiers build bridges: summon some first (sneak + right-click, off hand)"
                : blocks == 0 ? "Nothing to bridge there: look at open air or across a gap"
                : "Your Null Soldier is building a " + blocks + "-block bridge");
        return Math.max(blocks, 0);
    }

    int summon(Player master) {
        if (!holdsTotem(master)) {
            tell(master, "Hold the Totem of Null to summon soldiers");
            return 0;
        }
        int started = plugin.soldiers().spawn(master, NullState.HARD_CAP);
        tell(master, started > 0 ? "Summoning " + started + " Null Soldier(s)"
                : "You already lead the max of " + plugin.state().maxSoldiers() + " Null Soldiers");
        return started;
    }

    int dismiss(Player master) {
        int removed = plugin.soldiers().clear(master);
        tell(master, "Dismissed " + removed + " Null Soldier(s)");
        return removed;
    }

    // ---- the windows ----

    /** The orders menu: status, the controls, and one button per order (two columns, so all fit without scrolling). */
    int openMenu(Player player) {
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text(plugin.soldiers().status(player.getUniqueId())), 300),
                DialogBody.plainMessage(Component.text(String.join("\n", CONTROLS), NamedTextColor.GRAY), 300));
        List<ActionButton> buttons = List.of(
                button("Follow me", "Stop whatever they're doing and walk with you", this::follow),
                button("Attack what I look at", "A mob up to " + RANGE + " blocks away; it glows", this::attackLooked),
                button("Mine blocks like this", "Every block like the one you look at, within 16 blocks of it. The drops go to you", this::mineLooked),
                button("Bridge to where I look", "A soldier builds a netherrack bridge, up to 64 blocks", this::bridge),
                button("Summon soldiers", "Up to your max, out of a nether portal", this::summon),
                button("Dismiss soldiers", "They return to the void", this::dismiss));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Null Soldier Orders")).body(body).canCloseWithEscape(true).pause(false).build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
        return 1;
    }

    private interface Order {
        int run(Player player);
    }

    private ActionButton button(String label, String tooltip, Order order) {
        return ActionButton.builder(Component.text(label)).tooltip(Component.text(tooltip)).width(150)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) {
                        order.run(player);
                    }
                }, ClickCallback.Options.builder().uses(1).build()))
                .build();
    }

    /** The settings window: a slider for the max soldiers per player. */
    int openSettings(Player player) {
        int current = plugin.state().maxSoldiers();
        Consumer<Float> save = value -> {
            plugin.state().setMaxSoldiers(Math.round(value));
            player.sendMessage(Component.text("Max Null Soldiers per player set to " + plugin.state().maxSoldiers() + ".", NamedTextColor.DARK_PURPLE));
        };
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Totem of Null Settings"))
                        .body(List.of(DialogBody.plainMessage(Component.text("Lower this if 20 NPCs are too heavy for the server."), 200)))
                        .inputs(List.of(DialogInput.numberRange("max", Component.text("Max soldiers per player"), 1, NullState.HARD_CAP)
                                .step(1f).initial((float) current).width(200).build()))
                        .canCloseWithEscape(true).build())
                .type(DialogType.notice(ActionButton.builder(Component.text("Save")).width(150)
                        .action(DialogAction.customClick((response, audience) -> {
                            Float value = response.getFloat("max");
                            if (value != null) {
                                save.accept(value);
                            }
                        }, ClickCallback.Options.builder().uses(1).build())).build())));
        player.showDialog(dialog);
        return 1;
    }

    // ---- helpers ----

    /** The living thing in the master's crosshair, up to 64 blocks and not behind a wall; never one of their own. */
    @Nullable
    private LivingEntity lookedAtMob(Player master) {
        RayTraceResult hit = master.getWorld().rayTrace(master.getEyeLocation(), master.getEyeLocation().getDirection(), RANGE,
                FluidCollisionMode.NEVER, true, 0.3,
                e -> e instanceof LivingEntity && e != master && !e.isDead() && !plugin.soldiers().isFriendly(master.getUniqueId(), e));
        return hit != null && hit.getHitEntity() instanceof LivingEntity mob ? mob : null;
    }

    private boolean hasSoldiers(Player master) {
        if (!plugin.soldiers().soldiersOf(master.getUniqueId()).isEmpty()) {
            return true;
        }
        tell(master, "You have no Null Soldiers. Sneak + right-click with the totem in your off hand to summon them");
        return false;
    }

    static void tell(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.DARK_PURPLE));
    }
}
