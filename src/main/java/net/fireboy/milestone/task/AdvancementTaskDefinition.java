package net.fireboy.milestone.task;

import net.minecraft.resources.ResourceLocation;

/** The assigned player must earn the requested advancement after assignment. */
public record AdvancementTaskDefinition(
        String id,
        ResourceLocation advancementId,
        String clue
) implements MilestoneTaskDefinition {
    @Override
    public MilestoneTaskType type() {
        return MilestoneTaskType.ADVANCEMENT;
    }
}
