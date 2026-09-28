package net.fireboy.milestone.world;

import net.fireboy.milestone.milestone.MilestoneRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class MilestoneSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_sites";

    private final Map<UUID, MilestoneRecord> milestones = new LinkedHashMap<>();

    public static MilestoneSavedData create() {
        return new MilestoneSavedData();
    }

    public static MilestoneSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MilestoneSavedData data = create();
        ListTag list = tag.getList("Milestones", Tag.TAG_COMPOUND);

        for (Tag entry : list) {
            MilestoneRecord record = MilestoneRecord.load((CompoundTag) entry);
            data.milestones.put(record.id(), record);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MilestoneRecord record : milestones.values()) {
            list.add(record.save());
        }
        tag.put("Milestones", list);
        return tag;
    }

    public Collection<MilestoneRecord> all() {
        return milestones.values();
    }

    public Optional<MilestoneRecord> get(UUID id) {
        return Optional.ofNullable(milestones.get(id));
    }

    public void put(MilestoneRecord record) {
        milestones.put(record.id(), record);
        setDirty();
    }

    public void remove(UUID id) {
        if (milestones.remove(id) != null) {
            setDirty();
        }
    }

    public void removeAt(String dimension, net.minecraft.core.BlockPos pos) {
        boolean changed = milestones.values().removeIf(record ->
                record.dimension().equals(dimension) && record.corePos().equals(pos));
        if (changed) {
            setDirty();
        }
    }
}
