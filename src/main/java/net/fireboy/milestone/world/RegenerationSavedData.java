package net.fireboy.milestone.world;

import net.fireboy.milestone.resonance.RegenerationEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RegenerationSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_regeneration";

    private final Map<Key, RegenerationEntry> entries = new LinkedHashMap<>();

    public static RegenerationSavedData create() {
        return new RegenerationSavedData();
    }

    public static RegenerationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RegenerationSavedData data = create();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        for (Tag raw : list) {
            CompoundTag entryTag = (CompoundTag) raw;
            if (!entryTag.hasUUID("MilestoneId")) {
                continue;
            }

            String dimension = entryTag.getString("Dimension");
            BlockPos pos = BlockPos.of(entryTag.getLong("Pos"));
            BlockState expectedState = NbtUtils.readBlockState(
                    blockLookup,
                    entryTag.getCompound("ExpectedState")
            );

            RegenerationEntry entry = new RegenerationEntry(
                    dimension,
                    pos,
                    entryTag.getUUID("MilestoneId"),
                    expectedState,
                    entryTag.getLong("DueGameTime")
            );
            data.entries.put(Key.of(dimension, pos), entry);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (RegenerationEntry entry : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Dimension", entry.dimension());
            entryTag.putLong("Pos", entry.pos().asLong());
            entryTag.putUUID("MilestoneId", entry.milestoneId());
            entryTag.put("ExpectedState", NbtUtils.writeBlockState(entry.expectedState()));
            entryTag.putLong("DueGameTime", entry.dueGameTime());
            list.add(entryTag);
        }

        tag.put("Entries", list);
        return tag;
    }

    public Optional<RegenerationEntry> get(String dimension, BlockPos pos) {
        return Optional.ofNullable(entries.get(Key.of(dimension, pos)));
    }

    public Collection<RegenerationEntry> all() {
        return entries.values();
    }

    public List<RegenerationEntry> forMilestone(UUID milestoneId) {
        List<RegenerationEntry> result = new ArrayList<>();
        for (RegenerationEntry entry : entries.values()) {
            if (entry.milestoneId().equals(milestoneId)) {
                result.add(entry);
            }
        }
        return result;
    }

    public void put(RegenerationEntry entry) {
        entries.put(Key.of(entry.dimension(), entry.pos()), entry);
        setDirty();
    }

    public void remove(String dimension, BlockPos pos) {
        if (entries.remove(Key.of(dimension, pos)) != null) {
            setDirty();
        }
    }

    public void removeMilestone(UUID milestoneId) {
        boolean changed = entries.values().removeIf(entry -> entry.milestoneId().equals(milestoneId));
        if (changed) {
            setDirty();
        }
    }

    private record Key(String dimension, long pos) {
        static Key of(String dimension, BlockPos pos) {
            return new Key(dimension, pos.asLong());
        }
    }
}
