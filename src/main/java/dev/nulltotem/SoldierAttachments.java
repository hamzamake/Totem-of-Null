package dev.nulltotem;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

public final class SoldierAttachments {
    /** UUID of the player who spawned this soldier; saved with the fake player's data. */
    public static final AttachmentType<UUID> MASTER_UUID = AttachmentRegistry.create(
            NullTotemMod.id("master_uuid"), builder -> builder.persistent(UUIDUtil.CODEC));

    static void init() {
    }
}
