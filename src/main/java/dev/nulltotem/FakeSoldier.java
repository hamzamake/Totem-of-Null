package dev.nulltotem;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import dev.nulltotem.mixin.ConnectionAccessor;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A Null Soldier: a real server-side player with no client behind it (what Carpet's fake players are on Fabric).
 * It joins through the normal player list, so it loads chunks, has an inventory and takes damage like any player.
 * Its brain (Soldiers) drives it by setting its movement input, look and actions each tick.
 */
public final class FakeSoldier extends ServerPlayer {
    /** True while a soldier is joining the player list. */
    public static boolean joining;
    final UUID master;
    // the movement input its brain sets, applied each tick like a client's keys
    private float forward;
    private float strafe;
    private boolean jumpQueued;

    private FakeSoldier(MinecraftServer server, ServerLevel level, GameProfile profile, UUID master) {
        super(server, level, profile);
        this.master = master;
    }

    static FakeSoldier spawn(MinecraftServer server, ServerLevel level, String name, Vec3 pos, float yaw, UUID master) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        FakeSoldier bot = new FakeSoldier(server, level, profile, master);
        joining = true; // keeps its "joined the game" out of chat (see PlayerListMixin)
        try {
            server.getPlayerList().placeNewPlayer(new FakeConnection(), bot);
        } finally {
            joining = false;
        }
        bot.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0);
        bot.setHealth(bot.getMaxHealth());
        bot.unsetRemoved();
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        bot.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // every skin layer shown
        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(bot, (byte) (yaw * 256 / 360)), level.dimension());
        server.getPlayerList().broadcastAll(new ClientboundTeleportEntityPacket(bot), level.dimension());
        return bot;
    }

    /** Leaves the server (like a player disconnecting): no death, no drops. */
    void dismiss(Component reason) {
        connection.onDisconnect(reason);
    }

    // ---- controls (the brain's "keys") ----

    void setForward(float value) {
        forward = value;
    }

    void setStrafing(float value) {
        strafe = value;
    }

    void jump() {
        jumpQueued = true;
    }

    void setSneaking(boolean value) {
        setShiftKeyDown(value);
    }

    void stopMovement() {
        forward = 0;
        strafe = 0;
        setSprinting(false);
        setShiftKeyDown(false);
    }

    void look(float yaw, float pitch) {
        setYRot(yaw);
        setXRot(Mth.clamp(pitch, -90, 90));
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    void lookAt(Vec3 target) {
        Vec3 eye = getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        look((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90, (float) -(Mth.atan2(dy, flat) * Mth.RAD_TO_DEG));
    }

    /** One full-strength swing at a target in reach, like a player's left-click on it. */
    void swingAt(Entity target) {
        attack(target);
        swing(InteractionHand.MAIN_HAND);
        resetAttackStrengthTicker();
    }

    // a real player's movement is ticked by its own movement packets; this one has none, so it ticks itself, with
    // the input its brain set
    @Override
    public void tick() {
        if (getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        super.tick();
        zza = forward;
        xxa = strafe;
        setJumping(jumpQueued);
        jumpQueued = false;
        doTick();
        setJumping(false);
    }

    // a real player's falls are counted from its movement packets (this is empty for ServerPlayer); without it a
    // soldier never falls: no fall damage, and no falling critical hits
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0, y, 0, onGround);
    }

    // a player is invulnerable until its client says it arrived in the new dimension; a soldier has no client, so
    // without this it would stay unkillable (and immune to falls) after following its master through a dimension
    @Override
    public Entity changeDimension(ServerLevel level, ITeleporter teleporter) {
        Entity moved = super.changeDimension(level, teleporter);
        if (isChangingDimension()) {
            hasChangedDimension();
        }
        return moved;
    }

    // a soldier that dies is gone (it drops nothing: see Soldiers' drops handler)
    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        getServer().execute(() -> dismiss(Component.literal("Died")));
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    /** A connection to nowhere: everything sent to it is dropped. It has a channel, because Forge looks at it. */
    private static final class FakeConnection extends Connection {
        FakeConnection() {
            super(PacketFlow.SERVERBOUND);
            ((ConnectionAccessor) (Object) this).nulltotem$setChannel(new EmbeddedChannel());
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void setReadOnly() {
        }

        @Override
        public void handleDisconnection() {
        }

        @Override
        public void setProtocol(ConnectionProtocol protocol) {
        }
    }
}
