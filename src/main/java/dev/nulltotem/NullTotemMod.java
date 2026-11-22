package dev.nulltotem;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(NullTotemMod.MOD_ID)
public class NullTotemMod {
    public static final String MOD_ID = "nulltotem";

    public NullTotemMod(IEventBus modBus) {
        modBus.addListener(Payloads::register);
        NeoForge.EVENT_BUS.register(Totems.class);
        NeoForge.EVENT_BUS.register(Soldiers.class);
        NeoForge.EVENT_BUS.register(Orders.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> NullCommand.register(event.getDispatcher()));
    }
}
