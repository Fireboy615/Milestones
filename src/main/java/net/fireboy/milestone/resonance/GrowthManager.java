package net.fireboy.milestone.resonance;

import net.fireboy.milestone.world.GrowthSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Advances a precomputed main centreline, matures old trunk sections, and lets
 * thinner side branches wake up from established parts of the trunk.
 */
public final class GrowthManager {
    /** One growth update every 4 server ticks = five updates per second. */
    public static final int GROWTH_TICK_INTERVAL = 4;

    /** Number of new main-centreline points exposed per growth update. */
    public static final int POSITIONS_PER_STEP = 1;

    /** Number of centreline points each active branch may add per update. */
    public static final int BRANCH_POSITIONS_PER_STEP = 1;

    public static final int MAX_MATURITY_STAGE = 3;
    public static final int MAX_BRANCH_MATURITY_STAGE = 2;

    /** A branch only wakes once the trunk it grows from is established. */
    public static final int BRANCH_TRIGGER_STAGE = 2;

    // Ages are measured in growth updates, not raw Minecraft ticks.
    private static final int STAGE_ONE_AGE = 8;
    private static final int STAGE_TWO_AGE = 22;
    private static final int STAGE_THREE_AGE = 45;

    private static final SavedData.Factory<GrowthSavedData> FACTORY =
            new SavedData.Factory<>(GrowthSavedData::create, GrowthSavedData::load);

    private GrowthManager() {
    }

    public static GrowthSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, GrowthSavedData.DATA_NAME);
    }

    public static StartResult start(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            UUID milestoneId
    ) {
        GrowthSavedData data = data(level.getServer());
        if (data.get(milestoneId).isPresent()) {
            return new StartResult(false, 0, 0, "This Milestone already has an active growth plan.");
        }
        if (RetractionManager.get(level.getServer(), milestoneId).isPresent()) {
            return new StartResult(false, 0, 0, "This Milestone is currently retracting.");
        }

        var positions = ResonanceManager.planTendril(start, end, milestoneId);
        if (positions.isEmpty()) {
            return new StartResult(false, 0, 0, "The tendril planner produced no positions.");
        }

        var branches = ResonanceManager.planBranches(positions, milestoneId);
        GrowthPlan plan = GrowthPlan.fresh(
                milestoneId,
                level.dimension().location().toString(),
                start.immutable(),
                end.immutable(),
                positions,
                branches
        );
        data.put(plan);
        return new StartResult(true, positions.size(), branches.size(), null);
    }

    public static Optional<GrowthPlan> get(MinecraftServer server, UUID milestoneId) {
        return data(server).get(milestoneId);
    }

    public static boolean pause(MinecraftServer server, UUID milestoneId) {
        GrowthSavedData data = data(server);
        GrowthPlan plan = data.get(milestoneId).orElse(null);
        if (plan == null || plan.paused()) {
            return false;
        }
        data.put(plan.withPaused(true));
        return true;
    }

    public static boolean resume(MinecraftServer server, UUID milestoneId) {
        GrowthSavedData data = data(server);
        GrowthPlan plan = data.get(milestoneId).orElse(null);
        if (plan == null || !plan.paused()) {
            return false;
        }
        data.put(plan.withPaused(false));
        return true;
    }

    public static boolean cancel(MinecraftServer server, UUID milestoneId) {
        GrowthSavedData data = data(server);
        if (data.get(milestoneId).isEmpty()) {
            return false;
        }
        data.remove(milestoneId);
        return true;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % GROWTH_TICK_INTERVAL != 0) {
            return;
        }

        GrowthSavedData data = data(server);
        var plans = new ArrayList<>(data.all());

        for (GrowthPlan plan : plans) {
            if (plan.paused()) {
                continue;
            }

            ServerLevel level = findLevel(server, plan.dimension());
            if (level == null) {
                continue;
            }

            ArrayList<Integer> ages = new ArrayList<>(plan.ages());
            ArrayList<Integer> appliedStages = new ArrayList<>(plan.appliedStages());
            ArrayList<GrowthBranch> branches = new ArrayList<>(plan.branches());
            int cursor = plan.cursor();
            boolean changed = false;

            // Existing trunk growth gets older every update. This continues after the
            // main propagation front arrives so its final section can still thicken.
            for (int i = 0; i < cursor; i++) {
                if (ages.get(i) >= 0) {
                    ages.set(i, ages.get(i) + 1);
                    changed = true;
                }
            }

            // Advance only the narrow centreline at the live tip.
            int added = 0;
            while (cursor < plan.totalPositions() && added < POSITIONS_PER_STEP) {
                BlockPos center = plan.positions().get(cursor);
                if (!level.hasChunkAt(center)) {
                    break;
                }

                ages.set(cursor, 0);
                cursor++;
                added++;
                changed = true;
            }

            // Bring active trunk nodes up to their earned maturity stage.
            for (int i = 0; i < cursor; i++) {
                int desiredStage = maturityStageForAge(ages.get(i));
                int appliedStage = appliedStages.get(i);

                if (desiredStage <= appliedStage) {
                    continue;
                }

                double radius = radiusForStage(plan, i, desiredStage);
                ResonanceManager.GrowthVolumeResult result = ResonanceManager.coverGrowthVolume(
                        level,
                        plan.positions().get(i),
                        radius,
                        plan.milestoneId()
                );

                if (result.unloaded() == 0) {
                    appliedStages.set(i, desiredStage);
                    changed = true;
                }
            }

            // Branches are already planned, but only begin once the trunk node they
            // belong to has reached stage 2. This makes them visibly emerge from old
            // growth well behind the current propagation front.
            for (int branchIndex = 0; branchIndex < branches.size(); branchIndex++) {
                GrowthBranch branch = branches.get(branchIndex);
                int rootIndex = branch.rootIndex();

                if (rootIndex < 0 || rootIndex >= appliedStages.size()) {
                    continue;
                }
                if (appliedStages.get(rootIndex) < BRANCH_TRIGGER_STAGE) {
                    continue;
                }

                ArrayList<Integer> branchAges = new ArrayList<>(branch.ages());
                ArrayList<Integer> branchStages = new ArrayList<>(branch.appliedStages());
                int branchCursor = branch.cursor();
                boolean branchChanged = false;

                for (int i = 0; i < branchCursor; i++) {
                    if (branchAges.get(i) >= 0) {
                        branchAges.set(i, branchAges.get(i) + 1);
                        branchChanged = true;
                    }
                }

                int branchAdded = 0;
                while (branchCursor < branch.totalPositions()
                        && branchAdded < BRANCH_POSITIONS_PER_STEP) {
                    BlockPos center = branch.positions().get(branchCursor);
                    if (!level.hasChunkAt(center)) {
                        break;
                    }

                    branchAges.set(branchCursor, 0);
                    branchCursor++;
                    branchAdded++;
                    branchChanged = true;
                }

                for (int i = 0; i < branchCursor; i++) {
                    int desiredStage = Math.min(
                            MAX_BRANCH_MATURITY_STAGE,
                            maturityStageForAge(branchAges.get(i))
                    );
                    int appliedStage = branchStages.get(i);
                    if (desiredStage <= appliedStage) {
                        continue;
                    }

                    double radius = branchRadiusForStage(plan, branch, branchIndex, i, desiredStage);
                    ResonanceManager.GrowthVolumeResult result = ResonanceManager.coverGrowthVolume(
                            level,
                            branch.positions().get(i),
                            radius,
                            plan.milestoneId()
                    );

                    if (result.unloaded() == 0) {
                        branchStages.set(i, desiredStage);
                        branchChanged = true;
                    }
                }

                if (branchChanged) {
                    branches.set(
                            branchIndex,
                            branch.withProgress(branchCursor, branchAges, branchStages)
                    );
                    changed = true;
                }
            }

            GrowthPlan updated = changed
                    ? plan.withProgress(cursor, ages, appliedStages, branches)
                    : plan;

            if (isFullyMature(updated)) {
                data.remove(updated.milestoneId());
            } else if (changed) {
                data.put(updated);
            }
        }
    }

    public static int maturityStageForAge(int age) {
        if (age >= STAGE_THREE_AGE) {
            return 3;
        }
        if (age >= STAGE_TWO_AGE) {
            return 2;
        }
        if (age >= STAGE_ONE_AGE) {
            return 1;
        }
        return 0;
    }

    /** Main trunk radius by maturity stage. */
    public static double radiusForStage(GrowthPlan plan, int index, int stage) {
        return switch (stage) {
            case 0 -> 0.78D;
            case 1 -> 1.30D;
            case 2 -> 1.90D;
            default -> matureRadius(plan, index);
        };
    }

    /** Side branches use the same age ladder but never become as heavy as the trunk. */
    public static double branchRadiusForStage(
            GrowthPlan plan,
            GrowthBranch branch,
            int branchIndex,
            int index,
            int stage
    ) {
        return switch (stage) {
            case 0 -> 0.72D;
            case 1 -> 1.02D;
            default -> matureBranchRadius(plan, branch, branchIndex, index);
        };
    }

    public static int matureNodeCount(GrowthPlan plan) {
        int count = 0;
        for (int i = 0; i < plan.cursor(); i++) {
            if (plan.appliedStages().get(i) >= MAX_MATURITY_STAGE) {
                count++;
            }
        }
        return count;
    }

    public static int startedBranchCount(GrowthPlan plan) {
        int count = 0;
        for (GrowthBranch branch : plan.branches()) {
            if (branch.cursor() > 0) {
                count++;
            }
        }
        return count;
    }

    public static int completedBranchCount(GrowthPlan plan) {
        int count = 0;
        for (GrowthBranch branch : plan.branches()) {
            if (isBranchFullyMature(branch)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isFullyMature(GrowthPlan plan) {
        if (!plan.frontComplete()) {
            return false;
        }
        for (int i = 0; i < plan.totalPositions(); i++) {
            if (plan.appliedStages().get(i) < MAX_MATURITY_STAGE) {
                return false;
            }
        }
        for (GrowthBranch branch : plan.branches()) {
            if (!isBranchFullyMature(branch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBranchFullyMature(GrowthBranch branch) {
        if (!branch.frontComplete()) {
            return false;
        }
        for (int i = 0; i < branch.totalPositions(); i++) {
            if (branch.appliedStages().get(i) < MAX_BRANCH_MATURITY_STAGE) {
                return false;
            }
        }
        return true;
    }

    private static double matureRadius(GrowthPlan plan, int index) {
        double phase = ((plan.milestoneId().getLeastSignificantBits() >>> 8) & 0xFFFFL)
                / 65535.0D * Math.PI * 2.0D;
        double wave = Math.sin(index * 0.27D + phase) * 0.32D;

        long mixed = plan.positions().get(index).asLong()
                ^ plan.milestoneId().getMostSignificantBits()
                ^ Long.rotateLeft(plan.milestoneId().getLeastSignificantBits(), 19);
        mixed = mix64(mixed);
        double local = (mixed & 0xFFFFL) / 65535.0D;

        double radius = 2.55D + wave + (local - 0.5D) * 0.28D;
        if (local > 0.965D) {
            radius += 0.45D;
        }
        return Math.max(2.15D, Math.min(3.30D, radius));
    }

    private static double matureBranchRadius(
            GrowthPlan plan,
            GrowthBranch branch,
            int branchIndex,
            int index
    ) {
        double phase = (branchIndex * 1.73D)
                + (((plan.milestoneId().getMostSignificantBits() >>> 12) & 0xFFFFL)
                / 65535.0D * Math.PI * 2.0D);
        double wave = Math.sin(index * 0.42D + phase) * 0.12D;

        long mixed = branch.positions().get(index).asLong()
                ^ Long.rotateLeft(plan.milestoneId().getMostSignificantBits(), 11)
                ^ Long.rotateLeft(plan.milestoneId().getLeastSignificantBits(), 29)
                ^ branchIndex;
        mixed = mix64(mixed);
        double local = (mixed & 0xFFFFL) / 65535.0D;

        double radius = 1.34D + wave + (local - 0.5D) * 0.14D;
        return Math.max(1.12D, Math.min(1.58D, radius));
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return value;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    public record StartResult(
            boolean started,
            int plannedPositions,
            int plannedBranches,
            String failureReason
    ) {
    }
}
