package dev.nulltotem.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Totem of Null. On Paper the players' clients are vanilla, so it's a real Totem of Undying (vanilla does the save
 * and the pop animation) renamed, cursed and marked with a serial. Only the current serial works, and only one copy of
 * it may exist: a scan every half second deletes copies and records who has it. While someone has it, the anvil
 * rename that makes one refuses (with a barrier saying who has it).
 */
final class Totems implements Listener {
    static final int RENAME_COST = 20;
    private static final int SCAN_EVERY = 10;
    private static final int GONE_AFTER = 200; // ticks unseen in an online holder's inventory before it counts as lost

    private final NullTotemPlugin plugin;
    private final NamespacedKey serialKey;
    private long lastSeenTick = -1;

    Totems(NullTotemPlugin plugin) {
        this.plugin = plugin;
        this.serialKey = new NamespacedKey(plugin, "serial");
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, SCAN_EVERY, SCAN_EVERY);
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    /** A cursed Totem of Null with this serial; it only works once that serial is the current one. */
    ItemStack stamped(int serial) {
        ItemStack stack = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = stack.getItemMeta();
        meta.itemName(Component.text("Totem of Null"));
        meta.setRarity(ItemRarity.EPIC);
        meta.lore(List.of(
                hint("Saves you from death and erases every tagged player"),
                hint("Sneak + right-click, off hand: summon Null Soldiers"),
                hint("Sneak + right-click, main hand: soldier orders"),
                hint("Sneak + left-click, off hand: send them to a spot or a mob"),
                hint("   (do it again to call them back)"),
                hint("Sneak + left-click, main hand: netherrack bridge")));
        meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.INTEGER, serial);
        stack.setItemMeta(meta);
        return stack;
    }

    /** The one working Totem of Null for this player (an op's /null give: it replaces whoever had it). */
    ItemStack give(Player player) {
        ItemStack stack = stamped(plugin.state().nextTotemSerial());
        claimed(player);
        return stack;
    }

    int serialOf(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() != Material.TOTEM_OF_UNDYING || !stack.hasItemMeta()) {
            return 0;
        }
        Integer serial = stack.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.INTEGER);
        return serial == null ? 0 : serial;
    }

    boolean isCurrent(@Nullable ItemStack stack) {
        int serial = serialOf(stack);
        return serial != 0 && serial == plugin.state().totemSerial();
    }

    /** Whoever has the Totem of Null (see the class comment), or null when a new one may be made. */
    @Nullable
    String holderName() {
        UUID holder = plugin.state().totemHolder();
        if (holder == null) {
            return null;
        }
        Player online = Bukkit.getPlayer(holder);
        if (online == null) {
            return "a player who's offline";
        }
        long now = Bukkit.getCurrentTick();
        if ((lastSeenTick >= 0 && now - lastSeenTick <= GONE_AFTER) || (lastSeenTick < 0 && now <= GONE_AFTER)) {
            return online.getName(); // (right after a restart, give their inventory a moment to be scanned)
        }
        plugin.state().setTotemHolder(null);
        return null;
    }

    void claimed(Player holder) {
        plugin.state().setTotemHolder(holder.getUniqueId());
        lastSeenTick = Bukkit.getCurrentTick();
    }

    /**
     * Copies crumble, the real one's holder is recorded, and a totem fresh out of the anvil (the next serial) becomes
     * the real one as soon as it's in someone's inventory or on their cursor. The holder is scanned first, so it's
     * always the other copy that crumbles.
     */
    private void scan() {
        NullState state = plugin.state();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        UUID holder = state.totemHolder();
        players.sort((a, b) -> Boolean.compare(!a.getUniqueId().equals(holder), !b.getUniqueId().equals(holder)));
        boolean found = false;
        for (Player player : players) {
            if (plugin.soldiers().isSoldier(player)) {
                continue;
            }
            int cursorSerial = serialOf(player.getItemOnCursor());
            if (cursorSerial == state.totemSerial() + 1 && holderName() == null) {
                state.setTotemSerial(cursorSerial); // taken out of the anvil
                claimed(player);
            }
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                int serial = serialOf(stack);
                if (serial == 0) {
                    continue;
                }
                if (serial == state.totemSerial() + 1 && holderName() == null) {
                    state.setTotemSerial(serial); // shift-clicked out of the anvil
                }
                if (serial != state.totemSerial() || found) {
                    inventory.setItem(slot, null);
                    player.sendActionBar(Component.text("There can only be one Totem of Null. The copy crumbled.", NamedTextColor.DARK_PURPLE));
                    continue;
                }
                found = true;
                state.setTotemHolder(player.getUniqueId());
                lastSeenTick = Bukkit.getCurrentTick();
            }
        }
    }

    /** Renaming a lone Totem of Undying to "Totem of Null" makes the totem for 20 levels, unless someone has it. */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        String name = event.getView().getRenameText();
        if (first == null || first.getType() != Material.TOTEM_OF_UNDYING || serialOf(first) != 0
                || (second != null && !second.isEmpty()) || name == null || !name.trim().equalsIgnoreCase("Totem of Null")) {
            return;
        }
        String holder = holderName();
        if (holder != null) {
            // there can only be one, and it's taken. The reason goes in the result slot (the anvil screen covers the
            // action bar); a result that costs 0 levels can never be taken, so the barrier stays put
            ItemStack blocked = new ItemStack(Material.BARRIER);
            ItemMeta meta = blocked.getItemMeta();
            meta.itemName(Component.text("The Totem of Null already exists", NamedTextColor.DARK_PURPLE));
            meta.lore(List.of(hint(holder + " has it. There can only be one.")));
            blocked.setItemMeta(meta);
            event.setResult(blocked);
            event.getView().setRepairCost(0);
            return;
        }
        // the next serial; it only becomes the real one once it's taken (see scan), so a preview never voids anything
        event.setResult(stamped(plugin.state().totemSerial() + 1));
        event.getView().setRepairCost(RENAME_COST);
    }

    /** Vanilla saves the holder (it's a real Totem of Undying); a void copy doesn't, and the real one triggers NULL. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack used = held(player, event.getHand());
        if (serialOf(used) != 0 && !isCurrent(used)) {
            event.setCancelled(true); // an outdated copy
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPopped(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player saved) || !isCurrent(held(saved, event.getHand()))) {
            return;
        }
        plugin.state().setTotemHolder(null);
        lastSeenTick = -1;
        Bukkit.getScheduler().runTask(plugin, () -> {
            int erased = erase(saved);
            int soldiers = plugin.soldiers().dismissAll();
            saved.sendMessage(Component.text("NULL: " + erased + " tagged player(s) erased, " + soldiers
                    + " Null Soldier(s) returned to the void", NamedTextColor.DARK_PURPLE));
        });
    }

    @Nullable
    private static ItemStack held(Player player, @Nullable EquipmentSlot hand) {
        if (hand != null) {
            return player.getInventory().getItem(hand);
        }
        // the event doesn't say which hand when there's none; vanilla checks the main hand first
        ItemStack main = player.getInventory().getItemInMainHand();
        return main.getType() == Material.TOTEM_OF_UNDYING ? main : player.getInventory().getItemInOffHand();
    }

    /** The NULL effect: every tagged player online, in any dimension, is dropped into that dimension's void and killed. */
    private int erase(Player saved) {
        int count = 0;
        for (UUID id : plugin.state().tagged) {
            Player victim = Bukkit.getPlayer(id);
            if (victim == null || victim == saved || victim.isDead()) {
                continue;
            }
            victim.leaveVehicle();
            Location loc = victim.getLocation();
            loc.setY(-100);
            victim.teleport(loc);
            victim.damage(Float.MAX_VALUE, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
            count++;
        }
        return count;
    }
}
