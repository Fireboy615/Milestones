package net.fireboy.milestone.task;

import net.minecraft.world.entity.EntityType;

/** The assigned player must personally kill one entity of the requested type. */
public record KillTaskDefinition(
        String id,
        EntityType<?> target,
        String clue,
        int count
) implements MilestoneTaskDefinition {
    public KillTaskDefinition(String id, EntityType<?> target, String clue) {
        this(id, target, clue, 1);
    }

    @Override
    public MilestoneTaskType type() {
        return MilestoneTaskType.KILL;
    }
}
