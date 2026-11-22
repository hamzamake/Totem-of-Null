package dev.nulltotem.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Saved data (plugins/TotemOfNull/data.yml): the hit list, the soldier cap, and which totem is the real one and who has it. */
final class NullState {
    static final int HARD_CAP = 20;

    private final File file;
    private final Logger log;
    final Set<UUID> tagged = new LinkedHashSet<>();
    private int maxSoldiers = HARD_CAP;
    private int totemSerial;
    @Nullable
    private UUID totemHolder;

    NullState(File file, Logger log) {
        this.file = file;
        this.log = log;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.getStringList("tagged").forEach(s -> tagged.add(UUID.fromString(s)));
        maxSoldiers = Math.clamp(yaml.getInt("max-soldiers", HARD_CAP), 1, HARD_CAP);
        totemSerial = yaml.getInt("totem-serial", 0);
        String holder = yaml.getString("totem-holder");
        totemHolder = holder == null || holder.isEmpty() ? null : UUID.fromString(holder);
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("tagged", tagged.stream().map(UUID::toString).toList());
        yaml.set("max-soldiers", maxSoldiers);
        yaml.set("totem-serial", totemSerial);
        yaml.set("totem-holder", totemHolder == null ? "" : totemHolder.toString());
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Couldn't save " + file + ": " + e);
        }
    }

    int maxSoldiers() {
        return maxSoldiers;
    }

    void setMaxSoldiers(int value) {
        maxSoldiers = Math.clamp(value, 1, HARD_CAP);
        save();
    }

    int totemSerial() {
        return totemSerial;
    }

    int nextTotemSerial() {
        totemSerial++;
        save();
        return totemSerial;
    }

    void setTotemSerial(int serial) {
        totemSerial = serial;
        save();
    }

    @Nullable
    UUID totemHolder() {
        return totemHolder;
    }

    void setTotemHolder(@Nullable UUID holder) {
        if (!Objects.equals(holder, totemHolder)) {
            totemHolder = holder;
            save();
        }
    }
}
