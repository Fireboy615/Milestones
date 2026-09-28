package net.fireboy.milestone.world;

import net.fireboy.milestone.resonance.GlowLichenRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent ownership data for vanilla Glow Lichen placed by Milestones. */
public final class GlowLichenSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_glow_lichen";

    private final Map<Key, GlowLichenRecord> entries = new LinkedHashMap<>();

    public static GlowLichenSavedData create() {
        return new GlowLichenSavedData();
    }

    public static GlowLichenSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        GlowLichenSavedData data = create();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);

        for (Tag raw : list) {
            CompoundTag entryTag = (CompoundTag) raw;
            if (!entryTag.hasUUID("MilestoneId")) {
                continue;
            }

            String dimension = entryTag.getString("Dimension");
            BlockPos pos = BlockPos.of(entryTag.getLong("Pos"));
            BlockPos supportPos = BlockPos.of(entryTag.getLong("SupportPos"));
            Direction face = Direction.from3DDataValue(entryTag.getInt("Face"));

            GlowLichenRecord record = new GlowLichenRecord(
                    dimension,
                    pos,
                    supportPos,
                    face,
                    entryTag.getUUID("MilestoneId")
            );
            data.entries.put(Key.of(dimension, pos), record);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (GlowLichenRecord record : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Dimension", record.dimension());
            entryTag.putLong("Pos", record.pos().asLong());
            entryTag.putLong("SupportPos", record.supportPos().asLong());
            entryTag.putInt("Face", record.face().get3DDataValue());
            entryTag.putUUID("MilestoneId", record.milestoneId());
            list.add(entryTag);
        }

        tag.put("Entries", list);
        return tag;
    }

    public Collection<GlowLichenRecord> all() {
        return entries.values();
    }

    public GlowLichenRecord get(String dimension, BlockPos pos) {
        return entries.get(Key.of(dimension, pos));
    }

    public List<GlowLichenRecord> supportedBy(String dimension, BlockPos supportPos, UUID milestoneId) {
        List<GlowLichenRecord> result = new ArrayList<>();
        for (GlowLichenRecord record : entries.values()) {
            if (record.dimension().equals(dimension)
                    && record.supportPos().equals(supportPos)
                    && record.milestoneId().equals(milestoneId)) {
                result.add(record);
            }
        }
        return result;
    }

    public void put(GlowLichenRecord record) {
        entries.put(Key.of(record.dimension(), record.pos()), record);
        setDirty();
    }

    public void remove(String dimension, BlockPos pos) {
        if (entries.remove(Key.of(dimension, pos)) != null) {
            setDirty();
        }
    }

    public void removeMilestone(UUID milestoneId) {
        boolean changed = entries.values().removeIf(record -> record.milestoneId().equals(milestoneId));
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
