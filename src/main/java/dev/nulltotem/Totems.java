package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.entity.living.LivingUseTotemEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Totem of Null: a real Totem of Undying (so vanilla does the save and the pop animation) renamed, cursed and
 * marked with a serial. Only the current serial works, and only one copy of it may exist: a scan every half second
 * deletes copies and records who has it. While someone has it, the anvil rename that makes one refuses (with a
 * barrier saying who has it).
 */
public final class Totems {
    static final int RENAME_COST = 20;
    private static final String SERIAL = "nulltotem_serial";
    private static final int SCAN_EVERY = 10;
    private static final int GONE_AFTER = 200; // ticks unseen in an online holder's inventory before it counts as lost

    private static long lastSeenTick = -1;

    private static Component hint(String text) {
        return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    /** A cursed Totem of Null with this serial; it only works once that serial is the current one. */
    static ItemStack stamped(ServerLevel level, int serial) {
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING);
        // epic items' name colour, not italic (it isn't a player's rename); the curse gives it the glint
        stack.setHoverName(Component.literal("Totem of Null").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
        lore(stack, List.of(
                hint("Saves you from death and erases every tagged player"),
                hint("Sneak + right-click, off hand: summon Null Soldiers"),
                hint("Sneak + right-click, main hand: soldier orders"),
                hint("Sneak + left-click, off hand: send them to a spot or a mob"),
                hint("   (do it again to call them back)"),
                hint("Sneak + left-click, main hand: netherrack bridge")));
        stack.getOrCreateTag().putInt(SERIAL, serial);
        stack.enchant(Enchantments.VANISHING_CURSE, 1);
        return stack;
    }

    private static void lore(ItemStack stack, List<Component> lines) {
        ListTag lore = new ListTag();
        lines.forEach(line -> lore.add(StringTag.valueOf(Component.Serializer.toJson(line))));
        stack.getOrCreateTagElement(ItemStack.TAG_DISPLAY).put(ItemStack.TAG_LORE, lore);
    }

    /** The one working Totem of Null for this player (an op's /null give: it replaces whoever had it). */
    static ItemStack give(ServerPlayer player) {
        ItemStack stack = stamped(player.serverLevel(), NullState.get(player.getServer()).nextTotemSerial());
        claimed(player);
        return stack;
    }

    static int serialOf(@Nullable ItemStack stack) {
        if (stack == null || !stack.is(Items.TOTEM_OF_UNDYING)) {
            return 0;
        }
        return stack.getTag() == null ? 0 : stack.getTag().getInt(SERIAL);
    }

    public static boolean isCurrent(@Nullable ItemStack stack, MinecraftServer server) {
        int serial = serialOf(stack);
        return serial != 0 && serial == NullState.get(server).totemSerial();
    }

    /** Whoever has the Totem of Null (see the class comment), or null when a new one may be made. */
    @Nullable
    static String holderName(MinecraftServer server) {
        NullState state = NullState.get(server);
        UUID holder = state.totemHolder();
        if (holder == null) {
            return null;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(holder);
        if (online == null) {
            return "a player who's offline";
        }
        long now = server.getTickCount();
        if ((lastSeenTick >= 0 && now - lastSeenTick <= GONE_AFTER) || (lastSeenTick < 0 && now <= GONE_AFTER)) {
            return online.getScoreboardName(); // (right after a restart, give their inventory a moment to be scanned)
        }
        state.setTotemHolder(null);
        return null;
    }

    static void claimed(ServerPlayer holder) {
        NullState.get(holder.getServer()).setTotemHolder(holder.getUUID());
        lastSeenTick = holder.getServer().getTickCount();
    }

    /**
     * Copies crumble, the real one's holder is recorded, and a totem fresh out of the anvil (the next serial) becomes
     * the real one as soon as it's in someone's inventory or on their cursor. The holder is scanned first, so it's
     * always the other copy that crumbles.
     */
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_EVERY != 0) {
            return;
        }
        NullState state = NullState.get(server);
        UUID holder = state.totemHolder();
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        players.sort((a, b) -> Boolean.compare(!a.getUUID().equals(holder), !b.getUUID().equals(holder)));
        boolean found = false;
        for (ServerPlayer player : players) {
            if (player instanceof FakeSoldier) {
                continue;
            }
            if (serialOf(player.containerMenu.getCarried()) == state.totemSerial() + 1 && holderName(server) == null) {
                state.setTotemSerial(state.totemSerial() + 1); // taken out of the anvil
                claimed(player);
            }
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                int serial = serialOf(stack);
                if (serial == 0) {
                    continue;
                }
                if (serial == state.totemSerial() + 1 && holderName(server) == null) {
                    state.setTotemSerial(serial); // shift-clicked out of the anvil
                }
                if (serial != state.totemSerial() || found) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                    player.displayClientMessage(Component.literal("There can only be one Totem of Null. The copy crumbled.")
                            .withStyle(ChatFormatting.DARK_PURPLE), true);
                    continue;
                }
                found = true;
                state.setTotemHolder(player.getUUID());
                lastSeenTick = server.getTickCount();
            }
        }
    }

    /** Renaming a lone Totem of Undying to "Totem of Null" makes the totem for 20 levels, unless someone has it. */
    @SubscribeEvent
    public static void onAnvil(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        String name = event.getName();
        if (!left.is(Items.TOTEM_OF_UNDYING) || serialOf(left) != 0 || !event.getRight().isEmpty()
                || name == null || !name.trim().equalsIgnoreCase("Totem of Null")
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        String holder = holderName(player.getServer());
        // the client guesses a rename's cost itself (1 level); resend the real one, or a refused barrier would still
        // show a green "Enchantment Cost: 1" when the cost was already 0 on the server and so never gets sent
        player.getServer().execute(() -> player.containerMenu.sendAllDataToRemote());
        if (holder != null) {
            // there can only be one, and it's taken. The reason goes in the result slot (the anvil screen covers the
            // action bar); a result that costs 0 levels can never be taken, so the barrier stays put
            ItemStack blocked = new ItemStack(Items.BARRIER);
            blocked.setHoverName(Component.literal("The Totem of Null already exists").withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_PURPLE)));
            lore(blocked, List.of(hint(holder + " has it. There can only be one.")));
            event.setOutput(blocked);
            event.setCost(0);
            return;
        }
        // the next serial; it only becomes the real one once it's taken (see onTick), so a preview never voids anything
        event.setOutput(stamped(player.serverLevel(), NullState.get(player.getServer()).totemSerial() + 1));
        event.setCost(RENAME_COST);
    }

    /** Vanilla saves the holder (it's a real Totem of Undying); an outdated copy doesn't, and the real one triggers NULL. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTotem(LivingUseTotemEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer saved)) {
            return;
        }
        ItemStack totem = event.getTotem();
        MinecraftServer server = saved.getServer();
        if (serialOf(totem) == 0) {
            return; // a plain Totem of Undying
        }
        if (!isCurrent(totem, server)) {
            event.setCanceled(true); // an outdated copy
            return;
        }
        NullState.get(server).setTotemHolder(null);
        lastSeenTick = -1;
        server.execute(() -> {
            int erased = erase(saved);
            int soldiers = Soldiers.dismissAll(server);
            saved.sendSystemMessage(Component.literal("NULL: " + erased + " tagged player(s) erased, " + soldiers
                    + " Null Soldier(s) returned to the void").withStyle(ChatFormatting.DARK_PURPLE));
        });
    }

    /** The NULL effect: every tagged player online, in any dimension, is dropped into that dimension's void and killed. */
    private static int erase(ServerPlayer saved) {
        MinecraftServer server = saved.getServer();
        int count = 0;
        for (UUID id : NullState.get(server).tagged) {
            ServerPlayer victim = server.getPlayerList().getPlayer(id);
            if (victim == null || victim == saved || !victim.isAlive()) {
                continue;
            }
            victim.stopRiding();
            victim.teleportTo(victim.getX(), -100, victim.getZ());
            victim.hurt(victim.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            count++;
        }
        return count;
    }
}
