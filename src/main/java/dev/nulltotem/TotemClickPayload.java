package dev.nulltotem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the player sneak + left-clicked while holding the Totem of Null. Needed because a left-click on
 * air never reaches the server on its own; the client sends this instead of attacking (see MinecraftMixin).
 */
public record TotemClickPayload() implements CustomPacketPayload {
    public static final Type<TotemClickPayload> TYPE = new Type<>(NullTotemMod.id("totem_click"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TotemClickPayload> CODEC = StreamCodec.unit(new TotemClickPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
