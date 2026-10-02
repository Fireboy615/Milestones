package net.fireboy.milestone.milestone;

import java.util.List;

/**
 * Defines the gameplay profile for a Milestone site: its public identity,
 * contributor thresholds, and the task pool selected by MilestoneTaskCatalog.
 *
 * These are deliberately code-defined for now so the gameplay can settle before
 * moving the profiles into datapack JSON.
 */
public enum MilestoneType {
    GENERIC(
            "Awakening Milestone",
            "Prototype Milestone used for general testing.",
            4,
            List.of(
                    new RewardThreshold(4, null, "Primary milestone unlocked"),
                    new RewardThreshold(5, null, "Bonus participation tier I"),
                    new RewardThreshold(6, null, "Bonus participation tier II")
            )
    ),

    NETHER_GATE(
            "Nether Milestone",
            "A communal awakening that opens the server's path into the Nether.",
            4,
            List.of(
                    new RewardThreshold(4, MilestoneUnlock.SHARED_NETHER_GATEWAY, "Shared Nether gateway unlocked"),
                    new RewardThreshold(5, MilestoneUnlock.NETHER_PUBLIC_GATEWAY_1, "First additional public gateway unlocked"),
                    new RewardThreshold(6, MilestoneUnlock.NETHER_PUBLIC_GATEWAY_2, "Second additional public gateway unlocked"),
                    new RewardThreshold(7, MilestoneUnlock.NETHER_PERSONAL_PORTALS, "Ordinary personal Nether portals unlocked")
            )
    );

    private final String displayName;
    private final String description;
    private final int minimumContributors;
    private final List<RewardThreshold> thresholds;

    MilestoneType(
            String displayName,
            String description,
            int minimumContributors,
            List<RewardThreshold> thresholds
    ) {
        this.displayName = displayName;
        this.description = description;
        this.minimumContributors = minimumContributors;
        this.thresholds = List.copyOf(thresholds);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public int minimumContributors() {
        return minimumContributors;
    }

    public List<RewardThreshold> thresholds() {
        return thresholds;
    }

    public RewardThreshold nextThresholdAfter(int contributors) {
        for (RewardThreshold threshold : thresholds) {
            if (threshold.contributors() > contributors) {
                return threshold;
            }
        }
        return null;
    }

    public int finalThreshold() {
        return thresholds.isEmpty()
                ? minimumContributors
                : thresholds.get(thresholds.size() - 1).contributors();
    }

    public record RewardThreshold(int contributors, MilestoneUnlock unlock, String description) {
    }
}
