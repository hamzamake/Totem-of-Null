package dev.nulltotem;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Per-world data (data/nulltotem.dat): the hit list, the soldier cap, and which totem is the real one and who has it. */
public final class NullState extends SavedData {
    public static final int HARD_CAP = 20;

    private static final Factory<NullState> FACTORY = new Factory<>(NullState::new, NullState::load, null);

    final Set<UUID> tagged = new LinkedHashSet<>();
    private int maxSoldiers = HARD_CAP;
    private int totemSerial;
    @Nullable
    private UUID totemHolder;

    public static NullState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NullTotemMod.MOD_ID);
    }

    private static NullState load(CompoundTag tag, HolderLookup.Provider registries) {
        NullState state = new NullState();
        for (Tag t : tag.getList("tagged", Tag.TAG_STRING)) {
            state.tagged.add(UUID.fromString(t.getAsString()));
        }
        state.maxSoldiers = tag.contains("max_soldiers") ? Math.clamp(tag.getInt("max_soldiers"), 1, HARD_CAP) : HARD_CAP;
        state.totemSerial = tag.getInt("totem_serial");
        state.totemHolder = tag.hasUUID("totem_holder") ? tag.getUUID("totem_holder") : null;
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        tagged.forEach(id -> list.add(StringTag.valueOf(id.toString())));
        tag.put("tagged", list);
        tag.putInt("max_soldiers", maxSoldiers);
        tag.putInt("totem_serial", totemSerial);
        if (totemHolder != null) {
            tag.putUUID("totem_holder", totemHolder);
        }
        return tag;
    }

    public int maxSoldiers() {
        return maxSoldiers;
    }

    public void setMaxSoldiers(int value) {
        maxSoldiers = Math.clamp(value, 1, HARD_CAP);
        setDirty();
    }

    int totemSerial() {
        return totemSerial;
    }

    int nextTotemSerial() {
        setDirty();
        return ++totemSerial;
    }

    void setTotemSerial(int serial) {
        totemSerial = serial;
        setDirty();
    }

    @Nullable
    UUID totemHolder() {
        return totemHolder;
    }

    void setTotemHolder(@Nullable UUID holder) {
        if (!Objects.equals(holder, totemHolder)) {
            totemHolder = holder;
            setDirty();
        }
    }
}
