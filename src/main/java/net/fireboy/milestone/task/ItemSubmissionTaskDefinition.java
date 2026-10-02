package net.fireboy.milestone.task;

import net.minecraft.world.item.Item;

/** The assigned player must personally bring a particular stack to the Core. */
public record ItemSubmissionTaskDefinition(
        String id,
        Item item,
        int count,
        String clue
) implements MilestoneTaskDefinition {
    @Override
    public MilestoneTaskType type() {
        return MilestoneTaskType.ITEM_SUBMISSION;
    }
}
