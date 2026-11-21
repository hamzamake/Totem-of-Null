package dev.nulltotem;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
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

import java.util.Map;

/** A soldier's kit: unbreakable netherite armour with a netherite Flow trim, an enchanted sword and the Null shield. */
final class SoldierGear {
    // No Knockback: it would keep pushing targets out of the bots' reach
    private static final Map<ResourceKey<Enchantment>, Integer> SWORD = Map.of(
            Enchantments.SHARPNESS, 5, Enchantments.FIRE_ASPECT, 2, Enchantments.LOOTING, 3,
            Enchantments.SWEEPING_EDGE, 3, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<ResourceKey<Enchantment>, Integer> SHIELD = Map.of(
            Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);

    static void equip(ServerPlayer bot) {
        RegistryAccess registries = bot.registryAccess();
        HolderLookup.RegistryLookup<Enchantment> enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        HolderLookup.RegistryLookup<BannerPattern> patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
        ArmorTrim trim = new ArmorTrim(registries.lookupOrThrow(Registries.TRIM_MATERIAL).getOrThrow(TrimMaterials.NETHERITE),
                registries.lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(TrimPatterns.FLOW));

        Inventory inventory = bot.getInventory();
        inventory.clearContent();
        bot.setItemSlot(EquipmentSlot.HEAD, armor(Items.NETHERITE_HELMET, trim));
        bot.setItemSlot(EquipmentSlot.CHEST, armor(Items.NETHERITE_CHESTPLATE, trim));
        bot.setItemSlot(EquipmentSlot.LEGS, armor(Items.NETHERITE_LEGGINGS, trim));
        bot.setItemSlot(EquipmentSlot.FEET, armor(Items.NETHERITE_BOOTS, trim));

        ItemStack sword = unbreakable(Items.NETHERITE_SWORD);
        SWORD.forEach((key, level) -> sword.enchant(enchantments.getOrThrow(key), level));
        inventory.setSelectedSlot(0);
        inventory.setItem(0, sword);

        ItemStack shield = unbreakable(Items.SHIELD);
        SHIELD.forEach((key, level) -> shield.enchant(enchantments.getOrThrow(key), level));
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
