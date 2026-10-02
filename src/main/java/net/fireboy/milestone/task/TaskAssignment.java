package net.fireboy.milestone.task;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Persistent assignment of one secret task to one player at one Milestone. */
public record TaskAssignment(
        UUID playerId,
        String taskId,
        boolean completed,
        long assignedGameTime,
        long completedGameTime
) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", playerId);
        tag.putString("TaskId", taskId);
        tag.putBoolean("Completed", completed);
        tag.putLong("AssignedGameTime", assignedGameTime);
        tag.putLong("CompletedGameTime", completedGameTime);
        return tag;
    }

    public static TaskAssignment load(CompoundTag tag) {
        return new TaskAssignment(
                tag.getUUID("PlayerId"),
                tag.getString("TaskId"),
                tag.getBoolean("Completed"),
                tag.getLong("AssignedGameTime"),
                tag.getLong("CompletedGameTime")
        );
    }

    public TaskAssignment complete(long gameTime) {
        if (completed) {
            return this;
        }
        return new TaskAssignment(playerId, taskId, true, assignedGameTime, gameTime);
    }

    public TaskAssignment reset() {
        return new TaskAssignment(playerId, taskId, false, assignedGameTime, -1L);
    }
}
