package net.fireboy.milestone.milestone;

import net.fireboy.milestone.task.TaskAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative persistent record for one Milestone site.
 *
 * The Core block entity mirrors only the site's identity/lifecycle state. Player
 * assignments and contribution progress live here so they remain available even
 * when the Core chunk is unloaded.
 */
public record MilestoneRecord(
        UUID id,
        String dimension,
        BlockPos corePos,
        MilestoneState state,
        long createdGameTime,
        MilestoneType type,
        UUID discoveredBy,
        long discoveredGameTime,
        Map<UUID, TaskAssignment> assignments,
        Set<UUID> contributors
) {
    public MilestoneRecord {
        assignments = Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
        contributors = Collections.unmodifiableSet(new LinkedHashSet<>(contributors));
    }

    public static MilestoneRecord fresh(
            UUID id,
            String dimension,
            BlockPos corePos,
            MilestoneState state,
            long createdGameTime
    ) {
        return new MilestoneRecord(
                id,
                dimension,
                corePos,
                state,
                createdGameTime,
                MilestoneType.GENERIC,
                null,
                -1L,
                Map.of(),
                Set.of()
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Dimension", dimension);
        tag.putInt("X", corePos.getX());
        tag.putInt("Y", corePos.getY());
        tag.putInt("Z", corePos.getZ());
        tag.putString("State", state.name());
        tag.putLong("CreatedGameTime", createdGameTime);
        tag.putString("Type", type.name());

        if (discoveredBy != null) {
            tag.putUUID("DiscoveredBy", discoveredBy);
        }
        tag.putLong("DiscoveredGameTime", discoveredGameTime);

        ListTag assignmentList = new ListTag();
        for (TaskAssignment assignment : assignments.values()) {
            assignmentList.add(assignment.save());
        }
        tag.put("Assignments", assignmentList);

        ListTag contributorList = new ListTag();
        for (UUID contributor : contributors) {
            CompoundTag contributorTag = new CompoundTag();
            contributorTag.putUUID("Id", contributor);
            contributorList.add(contributorTag);
        }
        tag.put("Contributors", contributorList);

        return tag;
    }

    public static MilestoneRecord load(CompoundTag tag) {
        MilestoneState state;
        try {
            state = MilestoneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            state = MilestoneState.DORMANT;
        }

        MilestoneType type;
        try {
            type = tag.contains("Type")
                    ? MilestoneType.valueOf(tag.getString("Type"))
                    : MilestoneType.GENERIC;
        } catch (IllegalArgumentException ignored) {
            type = MilestoneType.GENERIC;
        }

        UUID discoveredBy = tag.hasUUID("DiscoveredBy")
                ? tag.getUUID("DiscoveredBy")
                : null;
        long discoveredGameTime = tag.contains("DiscoveredGameTime")
                ? tag.getLong("DiscoveredGameTime")
                : -1L;

        Map<UUID, TaskAssignment> assignments = new LinkedHashMap<>();
        ListTag assignmentList = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (Tag entry : assignmentList) {
            TaskAssignment assignment = TaskAssignment.load((CompoundTag) entry);
            assignments.put(assignment.playerId(), assignment);
        }

        Set<UUID> contributors = new LinkedHashSet<>();
        ListTag contributorList = tag.getList("Contributors", Tag.TAG_COMPOUND);
        for (Tag entry : contributorList) {
            CompoundTag contributorTag = (CompoundTag) entry;
            if (contributorTag.hasUUID("Id")) {
                contributors.add(contributorTag.getUUID("Id"));
            }
        }

        // Backward/repair path: a completed assignment always counts as a contribution.
        for (TaskAssignment assignment : assignments.values()) {
            if (assignment.completed()) {
                contributors.add(assignment.playerId());
            }
        }

        return new MilestoneRecord(
                tag.getUUID("Id"),
                tag.getString("Dimension"),
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                state,
                tag.getLong("CreatedGameTime"),
                type,
                discoveredBy,
                discoveredGameTime,
                assignments,
                contributors
        );
    }

    public MilestoneRecord withState(MilestoneState newState) {
        return new MilestoneRecord(
                id, dimension, corePos, newState, createdGameTime, type,
                discoveredBy, discoveredGameTime, assignments, contributors
        );
    }

    /**
     * Administrative/prototype type switch. A task from one Milestone profile
     * must never leak into another profile, so changing type intentionally clears
     * assignments and contribution progress while preserving discovery history.
     */
    public MilestoneRecord withTypeResetProgress(MilestoneType newType) {
        MilestoneState resetState = discoveredBy == null
                ? state
                : MilestoneState.DISCOVERED;
        return new MilestoneRecord(
                id, dimension, corePos, resetState, createdGameTime, newType,
                discoveredBy, discoveredGameTime, Map.of(), Set.of()
        );
    }

    public MilestoneRecord withDiscovery(UUID finder, long gameTime) {
        return new MilestoneRecord(
                id, dimension, corePos, MilestoneState.DISCOVERED, createdGameTime, type,
                finder, gameTime, assignments, contributors
        );
    }

    public Optional<TaskAssignment> assignment(UUID playerId) {
        return Optional.ofNullable(assignments.get(playerId));
    }

    public MilestoneRecord withAssignment(TaskAssignment assignment) {
        LinkedHashMap<UUID, TaskAssignment> updated = new LinkedHashMap<>(assignments);
        updated.put(assignment.playerId(), assignment);
        return new MilestoneRecord(
                id, dimension, corePos, state, createdGameTime, type,
                discoveredBy, discoveredGameTime, updated, contributors
        );
    }

    public MilestoneRecord completeTask(UUID playerId, long gameTime) {
        TaskAssignment existing = assignments.get(playerId);
        if (existing == null) {
            return this;
        }

        LinkedHashMap<UUID, TaskAssignment> updatedAssignments = new LinkedHashMap<>(assignments);
        updatedAssignments.put(playerId, existing.complete(gameTime));

        LinkedHashSet<UUID> updatedContributors = new LinkedHashSet<>(contributors);
        updatedContributors.add(playerId);

        return new MilestoneRecord(
                id, dimension, corePos, state, createdGameTime, type,
                discoveredBy, discoveredGameTime, updatedAssignments, updatedContributors
        );
    }

    public MilestoneRecord resetAssignment(UUID playerId) {
        LinkedHashMap<UUID, TaskAssignment> updatedAssignments = new LinkedHashMap<>(assignments);
        updatedAssignments.remove(playerId);

        LinkedHashSet<UUID> updatedContributors = new LinkedHashSet<>(contributors);
        updatedContributors.remove(playerId);

        return new MilestoneRecord(
                id, dimension, corePos, state, createdGameTime, type,
                discoveredBy, discoveredGameTime, updatedAssignments, updatedContributors
        );
    }

    public MilestoneRecord withContributor(UUID playerId) {
        if (contributors.contains(playerId)) {
            return this;
        }
        LinkedHashSet<UUID> updated = new LinkedHashSet<>(contributors);
        updated.add(playerId);
        return new MilestoneRecord(
                id, dimension, corePos, state, createdGameTime, type,
                discoveredBy, discoveredGameTime, assignments, updated
        );
    }

    public MilestoneRecord clearContributionProgress() {
        LinkedHashMap<UUID, TaskAssignment> resetAssignments = new LinkedHashMap<>();
        for (TaskAssignment assignment : assignments.values()) {
            resetAssignments.put(assignment.playerId(), assignment.reset());
        }

        MilestoneState resetState = discoveredBy == null
                ? MilestoneState.DORMANT
                : MilestoneState.DISCOVERED;

        return new MilestoneRecord(
                id, dimension, corePos, resetState, createdGameTime, type,
                discoveredBy, discoveredGameTime, resetAssignments, Set.of()
        );
    }

    public int contributorCount() {
        return contributors.size();
    }
}
