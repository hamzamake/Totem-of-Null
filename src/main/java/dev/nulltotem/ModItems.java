package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ModItems {
    public static final ResourceKey<Item> TOTEM_KEY = ResourceKey.create(Registries.ITEM, NullTotemMod.id("totem_of_null"));
    private static final String SERIAL = "nulltotem_serial";
    private static final int SUMMON_COOLDOWN = 40;
    /** Levels it costs to turn a Totem of Undying into the Totem of Null by renaming it in an anvil. */
    public static final int RENAME_COST = 20;

    public static final Item TOTEM_OF_NULL = Registry.register(BuiltInRegistries.ITEM, TOTEM_KEY, new TotemOfNull(new Item.Properties()
            .setId(TOTEM_KEY)
            .stacksTo(1)
            .rarity(Rarity.EPIC)
            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            .component(DataComponents.LORE, new ItemLore(List.of(
                    hint("Sneak + right-click in your off hand: summon Null Soldiers"),
                    hint("Saves you from death and erases every tagged player"))))
            // the client's pop animation shows the held item that has this component; the save itself is TotemHandler's
            .component(DataComponents.DEATH_PROTECTION, DeathProtection.TOTEM_OF_UNDYING)));

    private static long dedupeTick = -1;

    static void init() {
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    /** The one working Totem of Null: cursed, and carrying a new serial so every older copy stops working. */
    public static ItemStack create(ServerLevel level) {
        return stamped(level, NullServerState.get(level.getServer()).nextTotemSerial());
    }

    /** A cursed Totem of Null with this serial; it only works once the serial is made current. */
    public static ItemStack stamped(ServerLevel level, int serial) {
        ItemStack stack = new ItemStack(TOTEM_OF_NULL);
        CompoundTag tag = new CompoundTag();
        tag.putInt(SERIAL, serial);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        curse(stack, level);
        return stack;
    }

    /** Only the most recently made totem works; copies from vanilla /give have no serial and never do. */
    public static boolean isCurrent(ItemStack stack, MinecraftServer server) {
        int serial = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getIntOr(SERIAL, 0);
        return stack.is(TOTEM_OF_NULL) && serial != 0 && serial == NullServerState.get(server).totemSerial();
    }

    static void curse(ItemStack stack, ServerLevel level) {
        Holder<Enchantment> vanishing = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE);
        if (stack.getEnchantments().getLevel(vanishing) == 0) {
            stack.enchant(vanishing, 1);
        }
    }

    static class TotemOfNull extends Item {
        TotemOfNull(Properties properties) {
            super(properties);
        }

        // Players' inventories tick every stack once per server tick, so a second valid copy ticked in the same tick is
        // a duplicate (creative copy, dupe glitch). It, and any outdated or serial-less copy, vanishes on the spot.
        @Override
        public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot) {
            long now = level.getServer().getTickCount();
            if (!isCurrent(stack, level.getServer()) || dedupeTick == now) {
                stack.setCount(0);
                if (entity instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.literal("There can only be one Totem of Null. The copy crumbled.")
                            .withStyle(ChatFormatting.DARK_PURPLE), true);
                }
                return;
            }
            dedupeTick = now;
            curse(stack, level);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (hand != InteractionHand.OFF_HAND || !player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer master) {
                if (!isCurrent(stack, master.level().getServer())) {
                    return InteractionResult.FAIL;
                }
                int started = SoldierHandler.spawn(master, NullServerState.HARD_CAP);
                int max = NullServerState.get(master.level().getServer()).maxSoldiers();
                master.displayClientMessage(Component.literal(started > 0
                        ? "Summoning " + started + " Null Soldier(s)"
                        : "You already lead the max of " + max + " Null Soldiers").withStyle(ChatFormatting.DARK_PURPLE), true);
                master.getCooldowns().addCooldown(stack, SUMMON_COOLDOWN);
            }
            return InteractionResult.SUCCESS;
        }
    }
}
