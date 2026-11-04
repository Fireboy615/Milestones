package net.fireboy.milestone.world;

import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Persistent server-wide unlock flags earned from Milestones. */
public final class MilestoneProgressionSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_progression";

    private final EnumSet<MilestoneUnlock> unlocks = EnumSet.noneOf(MilestoneUnlock.class);
    // New dimension and portal access flags are key-based and mod-independent.
    // Existing enum flags above remain for Nether-portal compatibility.
    private final Set<String> dimensions = new HashSet<>();
    private final Set<String> portals = new HashSet<>();

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
        for (Tag entry : tag.getList("DimensionUnlocks", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null) data.dimensions.add(id.toString());
        }
        for (Tag entry : tag.getList("PortalUnlocks", Tag.TAG_STRING)) {
            String rule = entry.getAsString();
            if (rule.length() <= 220 && rule.split("\\|", -1).length == 3)
                data.portals.add(rule);
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
        ListTag savedDimensions = new ListTag();
        dimensions.stream().sorted().forEach(value ->
                savedDimensions.add(StringTag.valueOf(value)));
        tag.put("DimensionUnlocks", savedDimensions);
        ListTag savedPortals = new ListTag();
        portals.stream().sorted().forEach(value ->
                savedPortals.add(StringTag.valueOf(value)));
        tag.put("PortalUnlocks", savedPortals);
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

    public boolean unlockDimension(ResourceLocation destination) {
        if (!dimensions.add(destination.toString())) return false;
        setDirty();
        return true;
    }

    public boolean isDimensionUnlocked(ResourceLocation destination) {
        return dimensions.contains(destination.toString());
    }

    public Set<String> dimensions() { return Collections.unmodifiableSet(dimensions); }

    public static String portalKey(ResourceLocation destination, ResourceLocation portalType,
                                   String access) {
        return destination + "|" + portalType + "|" + access;
    }

    public boolean unlockPortal(ResourceLocation destination, ResourceLocation portalType,
                                String access) {
        if (!portals.add(portalKey(destination, portalType, access))) return false;
        setDirty();
        return true;
    }

    public boolean isPortalUnlocked(ResourceLocation destination, ResourceLocation portalType,
                                    String access) {
        return portals.contains(portalKey(destination, portalType, access));
    }

    public Set<String> portals() { return Collections.unmodifiableSet(portals); }

    public void clear() {
        if (!unlocks.isEmpty() || !dimensions.isEmpty() || !portals.isEmpty()) {
            unlocks.clear();
            dimensions.clear();
            portals.clear();
            setDirty();
        }
    }
}
