package dev.nulltotem.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives a soldier's fake connection a channel: Forge reads attributes off it when the player joins. */
@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    void nulltotem$setChannel(Channel channel);
}
