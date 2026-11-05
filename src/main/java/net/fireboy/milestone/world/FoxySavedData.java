package net.fireboy.milestone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Secret opt-in server-wide Easter egg state. Separate from milestone
 * progression so clearing rewards or configuring cores does not alter it.
 */
public final class FoxySavedData extends SavedData {
    public static final String NAME = "milestone_foxy";
    private boolean enabled;

    public static FoxySavedData create() {
        return new FoxySavedData();
    }

    public static FoxySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FoxySavedData data = create();
        data.enabled = tag.getBoolean("Enabled");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("Enabled", enabled);
        return tag;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        setDirty();
    }
}
