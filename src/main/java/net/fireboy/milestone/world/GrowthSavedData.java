package net.fireboy.milestone.world;

import net.fireboy.milestone.resonance.GrowthPlan;
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

/** Persistent active tendril-growth plans. */
public class GrowthSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_growth";

    private final Map<UUID, GrowthPlan> plans = new LinkedHashMap<>();

    public static GrowthSavedData create() {
        return new GrowthSavedData();
    }

    public static GrowthSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        GrowthSavedData data = create();
        ListTag list = tag.getList("Plans", Tag.TAG_COMPOUND);

        for (Tag entry : list) {
            CompoundTag planTag = (CompoundTag) entry;
            if (!planTag.hasUUID("MilestoneId")) {
                continue;
            }

            // Growth-plan formats are intentionally versioned. v0.8 adds persistent
            // side-branch plans/state. Older active plans are discarded rather than
            // guessed into the new shape; existing Resonant terrain remains safely
            // retractable through ResonanceSavedData.
            if (planTag.getInt("FormatVersion") < GrowthPlan.FORMAT_VERSION) {
                continue;
            }

            GrowthPlan plan = GrowthPlan.load(planTag);
            data.plans.put(plan.milestoneId(), plan);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (GrowthPlan plan : plans.values()) {
            list.add(plan.save());
        }
        tag.put("Plans", list);
        return tag;
    }

    public Collection<GrowthPlan> all() {
        return plans.values();
    }

    public Optional<GrowthPlan> get(UUID milestoneId) {
        return Optional.ofNullable(plans.get(milestoneId));
    }

    public void put(GrowthPlan plan) {
        plans.put(plan.milestoneId(), plan);
        setDirty();
    }

    public void remove(UUID milestoneId) {
        if (plans.remove(milestoneId) != null) {
            setDirty();
        }
    }
}
