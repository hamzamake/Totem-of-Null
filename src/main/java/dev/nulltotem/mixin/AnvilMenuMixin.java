package dev.nulltotem.mixin;

import dev.nulltotem.ModItems;
import dev.nulltotem.NullServerState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renaming a plain Totem of Undying to "Totem of Null" in an anvil costs 20 levels and turns it into the one real
 * Totem of Null. The result carries the next serial, but it only becomes current (voiding the old totem) when taken,
 * so typing the name never breaks the existing one.
 */
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin extends ItemCombinerMenu {
    @Shadow
    private String itemName;
    @Shadow
    @Final
    private DataSlot cost;

    @Unique
    private int nulltotem$serial; // serial of the Totem of Null in the result slot, 0 when the result is something else

    private AnvilMenuMixin(MenuType<?> type, int id, Inventory inventory, ContainerLevelAccess access, ItemCombinerMenuSlotDefinition slots) {
        super(type, id, inventory, access, slots);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void nulltotem$renameToNull(CallbackInfo ci) {
        nulltotem$serial = 0;
        if (!inputSlots.getItem(0).is(Items.TOTEM_OF_UNDYING) || !inputSlots.getItem(1).isEmpty()
                || itemName == null || !itemName.trim().equalsIgnoreCase("Totem of Null")) {
            return;
        }
        if (player.level() instanceof ServerLevel level) {
            nulltotem$serial = NullServerState.get(level.getServer()).totemSerial() + 1;
            resultSlots.setItem(0, ModItems.stamped(level, nulltotem$serial));
        } else {
            resultSlots.setItem(0, new ItemStack(ModItems.TOTEM_OF_NULL)); // client preview; the server's result replaces it
        }
        cost.set(ModItems.RENAME_COST);
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void nulltotem$makeCurrent(Player taker, ItemStack stack, CallbackInfo ci) {
        if (nulltotem$serial != 0 && taker.level() instanceof ServerLevel level) {
            NullServerState.get(level.getServer()).setTotemSerial(nulltotem$serial);
            nulltotem$serial = 0;
        }
    }
}
