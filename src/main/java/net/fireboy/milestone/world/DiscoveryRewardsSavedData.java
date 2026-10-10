package net.fireboy.milestone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Server-owned, persisted one-time discovery rewards, including old-world catch-up. */
public final class DiscoveryRewardsSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_discovery_rewards";
    private final Set<UUID> awardedSchematics = new HashSet<>();
    /** v0.30: filled Create schematics, tracked separately from legacy paper. */
    private final Set<UUID> awardedCreateSchematics = new HashSet<>();
    private final Set<UUID> awardedJournals = new HashSet<>();

    public static DiscoveryRewardsSavedData create() {
        return new DiscoveryRewardsSavedData();
    }

    public static DiscoveryRewardsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        DiscoveryRewardsSavedData data = create();
        for (Tag element : tag.getList("Schematics", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            if (entry.hasUUID("Id")) data.awardedSchematics.add(entry.getUUID("Id"));
        }
        for (Tag element : tag.getList("CreateSchematics", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            if (entry.hasUUID("Id")) data.awardedCreateSchematics.add(entry.getUUID("Id"));
        }
        for (Tag element : tag.getList("Journals", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            if (entry.hasUUID("Id")) data.awardedJournals.add(entry.getUUID("Id"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Schematics", saveIds(awardedSchematics));
        tag.put("CreateSchematics", saveIds(awardedCreateSchematics));
        tag.put("Journals", saveIds(awardedJournals));
        return tag;
    }

    private static ListTag saveIds(Set<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            list.add(entry);
        }
        return list;
    }

    public boolean hasSchematic(UUID milestoneId) {
        return awardedSchematics.contains(milestoneId);
    }

    public void markSchematic(UUID milestoneId) {
        if (awardedSchematics.add(milestoneId)) setDirty();
    }

    public boolean hasCreateSchematic(UUID milestoneId) {
        return awardedCreateSchematics.contains(milestoneId);
    }

    public void markCreateSchematic(UUID milestoneId) {
        if (awardedCreateSchematics.add(milestoneId)) setDirty();
    }

    public boolean hasJournal(UUID playerId) {
        return awardedJournals.contains(playerId);
    }

    public void markJournal(UUID playerId) {
        if (awardedJournals.add(playerId)) setDirty();
    }
}
