package net.fireboy.milestone.world;

import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Persistent server-wide unlock flags earned from Milestones. */
public final class MilestoneProgressionSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_progression";

    private final EnumSet<MilestoneUnlock> unlocks = EnumSet.noneOf(MilestoneUnlock.class);

    public static MilestoneProgressionSavedData create() {
        return new MilestoneProgressionSavedData();
    }

    public static MilestoneProgressionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MilestoneProgressionSavedData data = create();
        ListTag list = tag.getList("Unlocks", Tag.TAG_STRING);
        for (Tag entry : list) {
            try {
                data.unlocks.add(MilestoneUnlock.valueOf(entry.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // Ignore removed/renamed flags rather than breaking an old world.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MilestoneUnlock unlock : unlocks) {
            list.add(StringTag.valueOf(unlock.name()));
        }
        tag.put("Unlocks", list);
        return tag;
    }

    public boolean isUnlocked(MilestoneUnlock unlock) {
        return unlocks.contains(unlock);
    }

    /** @return true only when this call newly unlocked the flag. */
    public boolean unlock(MilestoneUnlock unlock) {
        if (!unlocks.add(unlock)) {
            return false;
        }
        setDirty();
        return true;
    }

    public Set<MilestoneUnlock> unlocks() {
        return Collections.unmodifiableSet(EnumSet.copyOf(unlocks));
    }

    public void clear() {
        if (!unlocks.isEmpty()) {
            unlocks.clear();
            setDirty();
        }
    }
}
