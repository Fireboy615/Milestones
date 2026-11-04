package net.fireboy.milestone.task;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/**
 * One assigned objective per player per Milestone.
 * completed means the Station accepted the contribution; readyToSubmit means
 * the objective was achieved in the world but has not been submitted.
 */
public record TaskAssignment(
        UUID playerId,
        String taskId,
        boolean completed,
        long assignedGameTime,
        long completedGameTime,
        int progress,
        boolean readyToSubmit
) {
    /** Preserve existing constructor call sites and old-world semantics. */
    public TaskAssignment(UUID playerId, String taskId, boolean completed,
                          long assignedGameTime, long completedGameTime) {
        this(playerId, taskId, completed, assignedGameTime, completedGameTime, 0, false);
    }

    public TaskAssignment(UUID playerId, String taskId, boolean completed,
                          long assignedGameTime, long completedGameTime, int progress) {
        this(playerId, taskId, completed, assignedGameTime, completedGameTime, progress, false);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", playerId);
        tag.putString("TaskId", taskId);
        tag.putBoolean("Completed", completed);
        tag.putLong("AssignedGameTime", assignedGameTime);
        tag.putLong("CompletedGameTime", completedGameTime);
        tag.putInt("Progress", progress);
        tag.putBoolean("ReadyToSubmit", readyToSubmit);
        return tag;
    }

    public static TaskAssignment load(CompoundTag tag) {
        boolean accepted = tag.getBoolean("Completed");
        return new TaskAssignment(
                tag.getUUID("PlayerId"),
                tag.getString("TaskId"),
                accepted,
                tag.getLong("AssignedGameTime"),
                tag.getLong("CompletedGameTime"),
                Math.max(0, tag.getInt("Progress")),
                !accepted && tag.getBoolean("ReadyToSubmit")
        );
    }

    /** Only called after explicit server-validated Station submission. */
    public TaskAssignment complete(long gameTime) {
        if (completed) return this;
        return new TaskAssignment(playerId, taskId, true, assignedGameTime, gameTime, progress, false);
    }

    public TaskAssignment markReady() {
        if (completed || readyToSubmit) return this;
        return new TaskAssignment(playerId, taskId, false, assignedGameTime, -1L, progress, true);
    }

    public TaskAssignment withProgress(int newProgress) {
        return new TaskAssignment(playerId, taskId, completed, assignedGameTime,
                completedGameTime, Math.max(0, newProgress), readyToSubmit);
    }

    public TaskAssignment reset() {
        return new TaskAssignment(playerId, taskId, false, assignedGameTime, -1L, 0, false);
    }
}
