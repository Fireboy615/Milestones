package net.fireboy.milestone.resonance;

import net.fireboy.milestone.world.RetractionSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Regresses Resonance gradually from the outermost growth back toward its Core.
 * Mature Resonance first fades into the sparse young texture, then the original
 * terrain is restored a short distance behind that fading front.
 */
public final class RetractionManager {
    /** Retraction updates ten times per second. */
    public static final int RETRACTION_TICK_INTERVAL = 2;

    /** Number of voxels moved through each phase per update. */
    public static final int BLOCKS_PER_STEP = 12;

    /**
     * Keep roughly this many ordered positions in the young/faded state between
     * the fading front and the terrain-restoration front.
     */
    public static final int YOUNG_TRAIL_POSITIONS = 48;

    private static final SavedData.Factory<RetractionSavedData> FACTORY =
            new SavedData.Factory<>(RetractionSavedData::create, RetractionSavedData::load);

    private RetractionManager() {
    }

    public static RetractionSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, RetractionSavedData.DATA_NAME);
    }

    public static Optional<RetractionPlan> get(MinecraftServer server, UUID milestoneId) {
        return data(server).get(milestoneId);
    }

    public static StartResult start(
            ServerLevel level,
            BlockPos corePos,
            UUID milestoneId
    ) {
        RetractionSavedData data = data(level.getServer());
        if (data.get(milestoneId).isPresent()) {
            return new StartResult(false, 0, false, "This Milestone is already retracting.");
        }

        boolean cancelledGrowth = GrowthManager.cancel(level.getServer(), milestoneId);
        // Retraction is a terminal cleanup phase for the current living mass; stripped
        // positions must not suddenly grow back behind the retreating front.
        RegenerationManager.cancelMilestone(level.getServer(), milestoneId);
        List<ResonanceRecord> records = ResonanceManager.records(level, milestoneId);
        if (records.isEmpty()) {
            return new StartResult(false, 0, cancelledGrowth, "This Milestone has no Resonance to retract.");
        }

        List<BlockPos> ordered = new ArrayList<>(records.size());
        for (ResonanceRecord record : records) {
            ordered.add(record.pos().immutable());
        }

        // Pull the living mass back toward the Core. The distance score makes distant
        // tips/branches regress first; deterministic noise breaks up perfect shells.
        ordered.sort(
                Comparator.<BlockPos>comparingDouble(pos -> retractionScore(pos, corePos, milestoneId))
                        .reversed()
        );

        RetractionPlan plan = RetractionPlan.fresh(
                milestoneId,
                level.dimension().location().toString(),
                corePos,
                ordered
        );
        data.put(plan);
        return new StartResult(true, ordered.size(), cancelledGrowth, null);
    }

    public static boolean cancel(MinecraftServer server, UUID milestoneId) {
        RetractionSavedData data = data(server);
        if (data.get(milestoneId).isEmpty()) {
            return false;
        }
        data.remove(milestoneId);
        return true;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % RETRACTION_TICK_INTERVAL != 0) {
            return;
        }

        RetractionSavedData data = data(server);
        var plans = new ArrayList<>(data.all());

        for (RetractionPlan plan : plans) {
            ServerLevel level = findLevel(server, plan.dimension());
            if (level == null) {
                continue;
            }

            int fadeCursor = plan.fadeCursor();
            int restoreCursor = plan.restoreCursor();
            int fadedThisStep = 0;

            // Phase 1: mature/full Resonance fades into the young sparse texture.
            while (fadeCursor < plan.totalPositions() && fadedThisStep < BLOCKS_PER_STEP) {
                BlockPos pos = plan.positions().get(fadeCursor);
                ResonanceManager.FadeOutcome outcome = ResonanceManager.fadeOwnedForRetraction(
                        level,
                        pos,
                        plan.milestoneId()
                );

                if (outcome == ResonanceManager.FadeOutcome.UNLOADED) {
                    // Never force-load terrain just to animate regression.
                    break;
                }

                fadeCursor++;
                fadedThisStep++;
            }

            // Phase 2: follow behind the fade front and restore the original terrain.
            // While the fading front is still moving, retain a visible young trail.
            int restoreLimit;
            if (fadeCursor >= plan.totalPositions()) {
                // Once everything has faded, let the restore front drain the tail.
                restoreLimit = fadeCursor;
            } else {
                restoreLimit = Math.max(0, fadeCursor - YOUNG_TRAIL_POSITIONS);
            }

            int restoredThisStep = 0;
            while (restoreCursor < restoreLimit && restoredThisStep < BLOCKS_PER_STEP) {
                BlockPos pos = plan.positions().get(restoreCursor);
                ResonanceManager.RestoreOutcome outcome = ResonanceManager.restoreOwned(
                        level,
                        pos,
                        plan.milestoneId()
                );

                if (outcome == ResonanceManager.RestoreOutcome.UNLOADED) {
                    break;
                }

                restoreCursor++;
                restoredThisStep++;
            }

            if (restoreCursor >= plan.totalPositions()) {
                data.remove(plan.milestoneId());
            } else if (fadeCursor != plan.fadeCursor() || restoreCursor != plan.restoreCursor()) {
                data.put(plan.withCursors(fadeCursor, restoreCursor));
            }
        }
    }

    private static double retractionScore(BlockPos pos, BlockPos corePos, UUID milestoneId) {
        double dx = pos.getX() - corePos.getX();
        double dy = pos.getY() - corePos.getY();
        double dz = pos.getZ() - corePos.getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        long mixed = pos.asLong()
                ^ milestoneId.getMostSignificantBits()
                ^ Long.rotateLeft(milestoneId.getLeastSignificantBits(), 23);
        mixed = mix64(mixed);
        double jitter = (mixed & 0xFFFFL) / 65535.0D;

        return distanceSquared + jitter * 3.0D;
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
            boolean cancelledGrowth,
            String failureReason
    ) {
    }
}
