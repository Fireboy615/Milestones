package net.fireboy.milestone.task;

/**
 * Shared definition contract for secret per-player Milestone objectives.
 * Definitions are static catalog data; per-player completion lives in TaskAssignment.
 */
public interface MilestoneTaskDefinition {
    String id();

    String clue();

    MilestoneTaskType type();
}
