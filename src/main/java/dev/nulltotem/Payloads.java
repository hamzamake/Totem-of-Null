package dev.nulltotem;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The mod's packets: the totem's sneak + left-click (client to server) and the two windows (server to client). */
public final class Payloads {
    /** The player sneak + left-clicked while holding the Totem of Null. */
    public record TotemClick() implements CustomPacketPayload {
        static final Type<TotemClick> TYPE = new Type<>(id("totem_click"));
        static final StreamCodec<ByteBuf, TotemClick> CODEC = StreamCodec.unit(new TotemClick());

        @Override
        public Type<TotemClick> type() {
            return TYPE;
        }
    }

    /** Open the orders menu, with what the soldiers are doing. */
    public record OpenOrders(String status) implements CustomPacketPayload {
        static final Type<OpenOrders> TYPE = new Type<>(id("open_orders"));
        static final StreamCodec<ByteBuf, OpenOrders> CODEC = ByteBufCodecs.STRING_UTF8.map(OpenOrders::new, OpenOrders::status);

        @Override
        public Type<OpenOrders> type() {
            return TYPE;
        }
    }

    /** Open the settings window, with the current max soldiers per player. */
    public record OpenConfig(int maxSoldiers) implements CustomPacketPayload {
        static final Type<OpenConfig> TYPE = new Type<>(id("open_config"));
        static final StreamCodec<ByteBuf, OpenConfig> CODEC = ByteBufCodecs.VAR_INT.map(OpenConfig::new, OpenConfig::maxSoldiers);

        @Override
        public Type<OpenConfig> type() {
            return TYPE;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NullTotemMod.MOD_ID, path);
    }

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TotemClick.TYPE, TotemClick.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                Orders.click(player);
            }
        });
        // the screens are client classes, only touched on the client
        registrar.playToClient(OpenOrders.TYPE, OpenOrders.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist.isClient()) {
                NullClient.openOrders(payload.status());
            }
        });
        registrar.playToClient(OpenConfig.TYPE, OpenConfig.CODEC, (payload, context) -> {
            if (FMLEnvironment.dist.isClient()) {
                NullClient.openConfig(payload.maxSoldiers());
            }
        });
    }
}
