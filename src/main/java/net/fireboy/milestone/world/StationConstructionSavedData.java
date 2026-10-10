package net.fireboy.milestone.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One-time, permanent certification per discovered Milestone. */
public final class StationConstructionSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_station_construction";
    private final Map<UUID, BlockPos> verified = new HashMap<>();

    public static StationConstructionSavedData create() {
        return new StationConstructionSavedData();
    }

    public static StationConstructionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        StationConstructionSavedData data = create();
        for (Tag raw : tag.getList("Verified", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            if (entry.hasUUID("Milestone")) {
                data.verified.put(entry.getUUID("Milestone"), BlockPos.of(entry.getLong("Center")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : verified.entrySet()) {
            CompoundTag result = new CompoundTag();
            result.putUUID("Milestone", entry.getKey());
            result.putLong("Center", entry.getValue().asLong());
            list.add(result);
        }
        tag.put("Verified", list);
        return tag;
    }

    public boolean isVerified(UUID milestoneId) {
        return verified.containsKey(milestoneId);
    }

    public boolean positionUsed(BlockPos center) {
        return verified.containsValue(center);
    }

    public void verify(UUID milestoneId, BlockPos center) {
        if (!verified.containsKey(milestoneId) && !positionUsed(center)) {
            verified.put(milestoneId, center.immutable());
            setDirty();
        }
    }
}
