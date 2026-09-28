package net.fireboy.milestone.world;

import net.fireboy.milestone.resonance.RetractionPlan;
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

/** Persistent active reverse-growth plans. */
public class RetractionSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_retraction";

    private final Map<UUID, RetractionPlan> plans = new LinkedHashMap<>();

    public static RetractionSavedData create() {
        return new RetractionSavedData();
    }

    public static RetractionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RetractionSavedData data = create();
        ListTag list = tag.getList("Plans", Tag.TAG_COMPOUND);

        for (Tag entry : list) {
            CompoundTag planTag = (CompoundTag) entry;
            if (!planTag.hasUUID("MilestoneId")) {
                continue;
            }
            if (planTag.getInt("FormatVersion") < RetractionPlan.FORMAT_VERSION) {
                continue;
            }

            RetractionPlan plan = RetractionPlan.load(planTag);
            data.plans.put(plan.milestoneId(), plan);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (RetractionPlan plan : plans.values()) {
            list.add(plan.save());
        }
        tag.put("Plans", list);
        return tag;
    }

    public Collection<RetractionPlan> all() {
        return plans.values();
    }

    public Optional<RetractionPlan> get(UUID milestoneId) {
        return Optional.ofNullable(plans.get(milestoneId));
    }

    public void put(RetractionPlan plan) {
        plans.put(plan.milestoneId(), plan);
        setDirty();
    }

    public void remove(UUID milestoneId) {
        if (plans.remove(milestoneId) != null) {
            setDirty();
        }
    }
}
