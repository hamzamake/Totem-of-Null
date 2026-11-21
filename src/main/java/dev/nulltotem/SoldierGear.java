package dev.nulltotem;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import net.minecraft.world.item.equipment.trim.TrimPatterns;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * A soldier's kit, all unbreakable and enchanted: netherite armour with a netherite Flow trim, a sword, pickaxe, axe and
 * shovel, the Null shield, golden apples, and lasting Fire Resistance, Water Breathing and Saturation (so mining near
 * lava or underwater doesn't kill them, and they never starve).
 */
final class SoldierGear {
    static final int SWORD_SLOT = 0;
    static final int PICKAXE_SLOT = 1;
    static final int AXE_SLOT = 2;
    static final int SHOVEL_SLOT = 3;
    static final int APPLE_SLOT = 4;
    private static final int APPLES = 64;

    // No Knockback: it would keep pushing targets out of the bots' reach
    private static final Map<ResourceKey<Enchantment>, Integer> SWORD = Map.of(
            Enchantments.SHARPNESS, 5, Enchantments.FIRE_ASPECT, 2, Enchantments.LOOTING, 3,
            Enchantments.SWEEPING_EDGE, 3, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> SHIELD = Map.of(
            Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> TOOL = Map.of(
            Enchantments.EFFICIENCY, 5, Enchantments.FORTUNE, 3, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> ARMOR = Map.of(
            Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> HELMET = Map.of(
            Enchantments.RESPIRATION, 3, Enchantments.AQUA_AFFINITY, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> BOOTS = Map.of(
            Enchantments.FEATHER_FALLING, 4, Enchantments.DEPTH_STRIDER, 3);

    static void equip(ServerPlayer bot) {
        RegistryAccess registries = bot.registryAccess();
        HolderLookup.RegistryLookup<Enchantment> enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        HolderLookup.RegistryLookup<BannerPattern> patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
        ArmorTrim trim = new ArmorTrim(registries.lookupOrThrow(Registries.TRIM_MATERIAL).getOrThrow(TrimMaterials.NETHERITE),
                registries.lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(TrimPatterns.FLOW));

        Inventory inventory = bot.getInventory();
        inventory.clearContent();
        bot.setItemSlot(EquipmentSlot.HEAD, enchanted(armor(Items.NETHERITE_HELMET, trim), enchantments, ARMOR, HELMET));
        bot.setItemSlot(EquipmentSlot.CHEST, enchanted(armor(Items.NETHERITE_CHESTPLATE, trim), enchantments, ARMOR));
        bot.setItemSlot(EquipmentSlot.LEGS, enchanted(armor(Items.NETHERITE_LEGGINGS, trim), enchantments, ARMOR));
        bot.setItemSlot(EquipmentSlot.FEET, enchanted(armor(Items.NETHERITE_BOOTS, trim), enchantments, ARMOR, BOOTS));

        inventory.setItem(SWORD_SLOT, enchanted(unbreakable(Items.NETHERITE_SWORD), enchantments, SWORD));
        inventory.setItem(PICKAXE_SLOT, enchanted(unbreakable(Items.NETHERITE_PICKAXE), enchantments, TOOL));
        inventory.setItem(AXE_SLOT, enchanted(unbreakable(Items.NETHERITE_AXE), enchantments, TOOL));
        inventory.setItem(SHOVEL_SLOT, enchanted(unbreakable(Items.NETHERITE_SHOVEL), enchantments, TOOL));
        inventory.setItem(APPLE_SLOT, new ItemStack(Items.GOLDEN_APPLE, APPLES));
        inventory.setSelectedSlot(SWORD_SLOT);

        for (var effect : List.of(MobEffects.FIRE_RESISTANCE, MobEffects.WATER_BREATHING, MobEffects.SATURATION)) {
            bot.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        }

        ItemStack shield = enchanted(unbreakable(Items.SHIELD), enchantments, SHIELD);
        // The Null shield: a light gray banner with black Field Masoned, Gradient, Base Gradient, Bend, a white
        // Roundel and a black Bordure Indented, in that order
        shield.set(DataComponents.BASE_COLOR, DyeColor.LIGHT_GRAY);
        shield.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
                .add(patterns.getOrThrow(BannerPatterns.BRICKS), DyeColor.BLACK)
                .add(patterns.getOrThrow(BannerPatterns.GRADIENT), DyeColor.BLACK)
                .add(patterns.getOrThrow(BannerPatterns.GRADIENT_UP), DyeColor.BLACK)
                .add(patterns.getOrThrow(BannerPatterns.STRIPE_DOWNRIGHT), DyeColor.BLACK)
                .add(patterns.getOrThrow(BannerPatterns.CIRCLE_MIDDLE), DyeColor.WHITE)
                .add(patterns.getOrThrow(BannerPatterns.CURLY_BORDER), DyeColor.BLACK)
                .build());
        bot.setItemSlot(EquipmentSlot.OFFHAND, shield);
    }

    @SafeVarargs
    private static ItemStack enchanted(ItemStack stack, HolderLookup.RegistryLookup<Enchantment> enchantments,
                                       Map<ResourceKey<Enchantment>, Integer>... sets) {
        for (Map<ResourceKey<Enchantment>, Integer> set : sets) {
            set.forEach((key, level) -> stack.enchant(enchantments.getOrThrow(key), level));
        }
        return stack;
    }

    /** The hotbar slot holding the tool that breaks this block fastest (the pickaxe when none helps). */
    static int bestToolSlot(ServerPlayer bot, BlockState state) {
        int best = PICKAXE_SLOT;
        float bestSpeed = 1;
        for (int slot = PICKAXE_SLOT; slot <= SHOVEL_SLOT; slot++) {
            float speed = bot.getInventory().getItem(slot).getDestroySpeed(state);
            if (speed > bestSpeed) {
                best = slot;
                bestSpeed = speed;
            }
        }
        return best;
    }

    private static ItemStack armor(Item item, ArmorTrim trim) {
        ItemStack stack = unbreakable(item);
        stack.set(DataComponents.TRIM, trim);
        return stack;
    }

    private static ItemStack unbreakable(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        return stack;
    }
}
