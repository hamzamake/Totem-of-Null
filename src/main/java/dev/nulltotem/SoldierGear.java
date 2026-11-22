package dev.nulltotem;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * A soldier's kit, all unbreakable and enchanted: netherite armour with a netherite Silence trim (1.20.1 has no Flow
 * trim), a sword, pickaxe, axe and shovel, the Null shield, golden apples, and lasting Fire Resistance, Water Breathing
 * and Saturation (so mining near lava or underwater doesn't kill them, and they never starve).
 */
final class SoldierGear {
    static final int SWORD_SLOT = 0;
    static final int PICKAXE_SLOT = 1;
    static final int AXE_SLOT = 2;
    static final int SHOVEL_SLOT = 3;
    static final int APPLE_SLOT = 4;
    static final int NETHERRACK_SLOT = 5; // for bridges (NullBridge); a builder never runs out, the stack is for show
    private static final int APPLES = 64;

    // No Knockback: it would keep pushing targets out of the bots' reach
    private static final Map<Enchantment, Integer> SWORD = Map.of(
            Enchantments.SHARPNESS, 5, Enchantments.FIRE_ASPECT, 2, Enchantments.MOB_LOOTING, 3,
            Enchantments.SWEEPING_EDGE, 3, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<Enchantment, Integer> SHIELD = Map.of(
            Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<Enchantment, Integer> TOOL = Map.of(
            Enchantments.BLOCK_EFFICIENCY, 5, Enchantments.BLOCK_FORTUNE, 3, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<Enchantment, Integer> ARMOR = Map.of(
            Enchantments.ALL_DAMAGE_PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
    private static final Map<Enchantment, Integer> HELMET = Map.of(
            Enchantments.RESPIRATION, 3, Enchantments.AQUA_AFFINITY, 1);
    private static final Map<Enchantment, Integer> BOOTS = Map.of(
            Enchantments.FALL_PROTECTION, 4, Enchantments.DEPTH_STRIDER, 3);

    static void equip(ServerPlayer bot) {
        RegistryAccess registries = bot.level().registryAccess();
        ArmorTrim trim = new ArmorTrim(registries.registryOrThrow(Registries.TRIM_MATERIAL).getHolderOrThrow(TrimMaterials.NETHERITE),
                registries.registryOrThrow(Registries.TRIM_PATTERN).getHolderOrThrow(TrimPatterns.SILENCE));

        Inventory inventory = bot.getInventory();
        inventory.clearContent();
        bot.setItemSlot(EquipmentSlot.HEAD, enchanted(armor(registries, Items.NETHERITE_HELMET, trim), ARMOR, HELMET));
        bot.setItemSlot(EquipmentSlot.CHEST, enchanted(armor(registries, Items.NETHERITE_CHESTPLATE, trim), ARMOR));
        bot.setItemSlot(EquipmentSlot.LEGS, enchanted(armor(registries, Items.NETHERITE_LEGGINGS, trim), ARMOR));
        bot.setItemSlot(EquipmentSlot.FEET, enchanted(armor(registries, Items.NETHERITE_BOOTS, trim), ARMOR, BOOTS));

        inventory.setItem(SWORD_SLOT, enchanted(unbreakable(Items.NETHERITE_SWORD), SWORD));
        inventory.setItem(PICKAXE_SLOT, enchanted(unbreakable(Items.NETHERITE_PICKAXE), TOOL));
        inventory.setItem(AXE_SLOT, enchanted(unbreakable(Items.NETHERITE_AXE), TOOL));
        inventory.setItem(SHOVEL_SLOT, enchanted(unbreakable(Items.NETHERITE_SHOVEL), TOOL));
        inventory.setItem(APPLE_SLOT, new ItemStack(Items.GOLDEN_APPLE, APPLES));
        inventory.setItem(NETHERRACK_SLOT, new ItemStack(Items.NETHERRACK, 64));
        inventory.selected = SWORD_SLOT;

        for (var effect : List.of(MobEffects.FIRE_RESISTANCE, MobEffects.WATER_BREATHING, MobEffects.SATURATION)) {
            bot.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        }

        ItemStack shield = enchanted(unbreakable(Items.SHIELD), SHIELD);
        // The Null shield: a light gray banner with black Field Masoned, Gradient, Base Gradient, Bend, a white
        // Roundel and a black Bordure Indented, in that order
        CompoundTag banner = new CompoundTag();
        banner.putInt("Base", DyeColor.LIGHT_GRAY.getId());
        banner.put("Patterns", new BannerPattern.Builder()
                .addPattern(BannerPatterns.BRICKS, DyeColor.BLACK)
                .addPattern(BannerPatterns.GRADIENT, DyeColor.BLACK)
                .addPattern(BannerPatterns.GRADIENT_UP, DyeColor.BLACK)
                .addPattern(BannerPatterns.STRIPE_DOWNRIGHT, DyeColor.BLACK)
                .addPattern(BannerPatterns.CIRCLE_MIDDLE, DyeColor.WHITE)
                .addPattern(BannerPatterns.CURLY_BORDER, DyeColor.BLACK)
                .toListTag());
        BlockItem.setBlockEntityData(shield, BlockEntityType.BANNER, banner);
        bot.setItemSlot(EquipmentSlot.OFFHAND, shield);
    }

    @SafeVarargs
    private static ItemStack enchanted(ItemStack stack, Map<Enchantment, Integer>... sets) {
        for (Map<Enchantment, Integer> set : sets) {
            set.forEach(stack::enchant);
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

    private static ItemStack armor(RegistryAccess registries, Item item, ArmorTrim trim) {
        ItemStack stack = unbreakable(item);
        ArmorTrim.setTrim(registries, stack, trim);
        return stack;
    }

    private static ItemStack unbreakable(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putBoolean("Unbreakable", true);
        return stack;
    }
}
