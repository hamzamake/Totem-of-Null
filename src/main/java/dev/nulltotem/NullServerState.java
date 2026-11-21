package dev.nulltotem;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-world data: the totem hit list, master -> soldier names, and the soldier cap. */
public class NullServerState extends SavedData {
    public static final int HARD_CAP = 20;

    private static final Codec<NullServerState> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.STRING_CODEC.listOf().fieldOf("tagged").forGetter(s -> List.copyOf(s.tagged)),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).fieldOf("soldiers").forGetter(s -> s.soldiers),
            Codec.intRange(1, HARD_CAP).optionalFieldOf("max_soldiers", HARD_CAP).forGetter(s -> s.maxSoldiers),
            Codec.INT.optionalFieldOf("totem_serial", 0).forGetter(s -> s.totemSerial)
    ).apply(i, NullServerState::new));

    private static final SavedDataType<NullServerState> TYPE = new SavedDataType<>(NullTotemMod.MOD_ID, NullServerState::new, CODEC, null);

    public final Set<UUID> tagged;
    public final Map<UUID, List<String>> soldiers = new HashMap<>();
    private int maxSoldiers;
    /** Serial of the one Totem of Null that works; every other copy vanishes (see ModItems). */
    private int totemSerial;

    private NullServerState() {
        this(List.of(), Map.of(), HARD_CAP, 0);
    }

    private NullServerState(List<UUID> tagged, Map<UUID, List<String>> soldiers, int maxSoldiers, int totemSerial) {
        this.tagged = new LinkedHashSet<>(tagged);
        soldiers.forEach((master, names) -> this.soldiers.put(master, new ArrayList<>(names)));
        this.maxSoldiers = maxSoldiers;
        this.totemSerial = totemSerial;
    }

    public static NullServerState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int maxSoldiers() {
        return maxSoldiers;
    }

    public int totemSerial() {
        return totemSerial;
    }

    /** Makes a new totem the only valid one. */
    public int nextTotemSerial() {
        setDirty();
        return ++totemSerial;
    }

    public void setTotemSerial(int serial) {
        totemSerial = serial;
        setDirty();
    }

    public void setMaxSoldiers(int value) {
        maxSoldiers = Math.clamp(value, 1, HARD_CAP);
        setDirty();
    }
}
