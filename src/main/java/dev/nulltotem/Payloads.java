package dev.nulltotem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** The mod's packets: the totem's sneak + left-click (client to server) and the two windows (server to client). */
public final class Payloads {
    private static final String VERSION = "1";
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NullTotemMod.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** The player sneak + left-clicked while holding the Totem of Null. */
    public record TotemClick() {
    }

    /** Open the orders menu, with what the soldiers are doing. */
    public record OpenOrders(String status) {
    }

    /** Open the settings window, with the current max soldiers per player. */
    public record OpenConfig(int maxSoldiers) {
    }

    static void register() {
        CHANNEL.messageBuilder(TotemClick.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buf) -> {
                })
                .decoder(buf -> new TotemClick())
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        Orders.click(player);
                    }
                })
                .add();
        // the screens are client classes, only touched on the client
        CHANNEL.messageBuilder(OpenOrders.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buf) -> buf.writeUtf(message.status()))
                .decoder(buf -> new OpenOrders(buf.readUtf()))
                .consumerMainThread((message, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        NullClient.openOrders(message.status());
                    }
                })
                .add();
        CHANNEL.messageBuilder(OpenConfig.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buf) -> buf.writeVarInt(message.maxSoldiers()))
                .decoder(buf -> new OpenConfig(buf.readVarInt()))
                .consumerMainThread((message, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        NullClient.openConfig(message.maxSoldiers());
                    }
                })
                .add();
    }

    static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
