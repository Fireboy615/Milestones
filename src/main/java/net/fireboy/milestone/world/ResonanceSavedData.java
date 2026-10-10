package net.fireboy.milestone.world;

import net.fireboy.milestone.resonance.ResonanceRecord;
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

public class ResonanceSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_resonance";

    private final Map<Key, ResonanceRecord> records = new LinkedHashMap<>();

    public static ResonanceSavedData create() {
        return new ResonanceSavedData();
    }

    public static ResonanceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ResonanceSavedData data = create();
        ListTag list = tag.getList("Records", Tag.TAG_COMPOUND);
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        for (Tag entry : list) {
            CompoundTag recordTag = (CompoundTag) entry;
            String dimension = recordTag.getString("Dimension");
            BlockPos pos = BlockPos.of(recordTag.getLong("Pos"));
            BlockState originalState = NbtUtils.readBlockState(
                    blockLookup,
                    recordTag.getCompound("OriginalState")
            );

            if (!recordTag.hasUUID("MilestoneId")) {
                continue;
            }

            ResonanceRecord record = new ResonanceRecord(
                    dimension,
                    pos,
                    recordTag.getUUID("MilestoneId"),
                    originalState,
                    recordTag.contains("OriginalBlockEntity")
                            ? recordTag.getCompound("OriginalBlockEntity") : null
            );
            data.records.put(Key.of(dimension, pos), record);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (ResonanceRecord record : records.values()) {
            CompoundTag recordTag = new CompoundTag();
            recordTag.putString("Dimension", record.dimension());
            recordTag.putLong("Pos", record.pos().asLong());
            recordTag.putUUID("MilestoneId", record.milestoneId());
            recordTag.put("OriginalState", NbtUtils.writeBlockState(record.originalState()));
            if (record.originalBlockEntity() != null) {
                recordTag.put("OriginalBlockEntity", record.originalBlockEntity().copy());
            }
            list.add(recordTag);
        }

        tag.put("Records", list);
        return tag;
    }

    public Optional<ResonanceRecord> get(String dimension, BlockPos pos) {
        return Optional.ofNullable(records.get(Key.of(dimension, pos)));
    }

    public Collection<ResonanceRecord> all() {
        return records.values();
    }

    /**
     * Returns a snapshot rather than the live collection so callers can safely restore
     * blocks and remove records while iterating.
     */
    public List<ResonanceRecord> forMilestone(String dimension, UUID milestoneId) {
        List<ResonanceRecord> matches = new ArrayList<>();
        for (ResonanceRecord record : records.values()) {
            if (record.dimension().equals(dimension) && record.milestoneId().equals(milestoneId)) {
                matches.add(record);
            }
        }
        return matches;
    }

    public void put(ResonanceRecord record) {
        records.put(Key.of(record.dimension(), record.pos()), record);
        setDirty();
    }

    public void remove(String dimension, BlockPos pos) {
        if (records.remove(Key.of(dimension, pos)) != null) {
            setDirty();
        }
    }

    private record Key(String dimension, long pos) {
        static Key of(String dimension, BlockPos pos) {
            return new Key(dimension, pos.asLong());
        }
    }
}
