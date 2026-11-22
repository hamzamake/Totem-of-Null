package dev.nulltotem;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(NullTotemMod.MOD_ID)
public class NullTotemMod {
    public static final String MOD_ID = "nulltotem";

    public NullTotemMod() {
        Payloads.register();
        MinecraftForge.EVENT_BUS.register(Totems.class);
        MinecraftForge.EVENT_BUS.register(Soldiers.class);
        MinecraftForge.EVENT_BUS.register(Orders.class);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> NullCommand.register(event.getDispatcher()));
    }
}
