package net.fireboy.milestone.task;

import net.minecraft.world.entity.EntityType;

/** The assigned player must personally kill one entity of the requested type. */
public record KillTaskDefinition(
        String id,
        EntityType<?> target,
        String clue
) implements MilestoneTaskDefinition {
    @Override
    public MilestoneTaskType type() {
        return MilestoneTaskType.KILL;
    }
}
