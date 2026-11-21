package dev.nulltotem;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.resources.Identifier;

public class NullTotemMod implements ModInitializer {
    public static final String MOD_ID = "nulltotem";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModItems.init();
        SoldierAttachments.init();
        TotemHandler.init(); // before SoldierHandler: its ALLOW_DEATH must get the first say (see SoldierHandler.init)
        SoldierHandler.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> NullCommand.register(dispatcher));
    }
}
