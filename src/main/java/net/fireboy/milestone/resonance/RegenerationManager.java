package net.fireboy.milestone.resonance;

import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.world.RegenerationSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles living Resonance growing back after a player strips it with an axe.
 *
 * This is deliberately conservative: a stripped position only regrows if the
 * exact terrain state exposed by stripping is still there when the cooldown
 * expires. If a player mines or replaces that block, Milestone leaves it alone.
 */
public final class RegenerationManager {
    /** Check pending regrowth once per second. */
    public static final int REGEN_TICK_INTERVAL = 20;

    /** Test-friendly delay. We can move this to server config once the feel is right. */
    public static final int STRIP_REGROW_DELAY_TICKS = 20 * 10;

    private static final SavedData.Factory<RegenerationSavedData> FACTORY =
            new SavedData.Factory<>(RegenerationSavedData::create, RegenerationSavedData::load);

    private RegenerationManager() {
    }

    public static RegenerationSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, RegenerationSavedData.DATA_NAME);
    }

    public static boolean scheduleAfterStrip(ServerLevel level, ResonanceRecord record) {
        MinecraftServer server = level.getServer();
        if (!canRegenerate(server, record.milestoneId())) {
            return false;
        }
        if (RetractionManager.get(server, record.milestoneId()).isPresent()) {
            return false;
        }

        long due = level.getGameTime() + STRIP_REGROW_DELAY_TICKS;
        data(server).put(new RegenerationEntry(
                record.dimension(),
                record.pos().immutable(),
                record.milestoneId(),
                record.originalState(),
                due
        ));
        return true;
    }

    /** Cancel a pending regrowth because Resonance has already reclaimed this spot. */
    public static void cancelAt(MinecraftServer server, String dimension, net.minecraft.core.BlockPos pos) {
        data(server).remove(dimension, pos);
    }

    public static void cancelMilestone(MinecraftServer server, UUID milestoneId) {
        data(server).removeMilestone(milestoneId);
    }

    public static int pendingCount(MinecraftServer server, UUID milestoneId) {
        return data(server).forMilestone(milestoneId).size();
    }

    public static Optional<Long> ticksUntilNext(ServerLevel level, UUID milestoneId) {
        long now = level.getGameTime();
        long best = Long.MAX_VALUE;
        for (RegenerationEntry entry : data(level.getServer()).forMilestone(milestoneId)) {
            if (!entry.dimension().equals(level.dimension().location().toString())) {
                continue;
            }
            best = Math.min(best, Math.max(0L, entry.dueGameTime() - now));
        }
        return best == Long.MAX_VALUE ? Optional.empty() : Optional.of(best);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % REGEN_TICK_INTERVAL != 0) {
            return;
        }

        RegenerationSavedData saved = data(server);
        var entries = new ArrayList<>(saved.all());
        var readyToRegrow = new ArrayList<RegenerationEntry>();

        // First decide which stripped positions are allowed to regrow without placing
        // anything yet. This is intentional: a block restored during this tick cannot
        // immediately become support for the next block in the same tick. Regeneration
        // therefore advances outward one connected shell at a time instead of an entire
        // unsupported chain popping back at once.
        for (RegenerationEntry entry : entries) {
            ServerLevel level = findLevel(server, entry.dimension());
            if (level == null) {
                continue;
            }

            MilestoneRecord milestone = MilestoneManager.data(server)
                    .get(entry.milestoneId())
                    .orElse(null);

            if (milestone == null || !canRegenerate(milestone.state())) {
                saved.remove(entry.dimension(), entry.pos());
                continue;
            }

            if (RetractionManager.get(server, entry.milestoneId()).isPresent()) {
                saved.remove(entry.dimension(), entry.pos());
                continue;
            }

            if (level.getGameTime() < entry.dueGameTime()) {
                continue;
            }

            if (!level.hasChunkAt(entry.pos())) {
                // Do not force-load chunks. The overdue task can finish when a player
                // naturally loads this area again.
                continue;
            }

            // If this position is Resonant again already, the task is obsolete.
            ResonanceRecord existing = ResonanceManager.get(level, entry.pos()).orElse(null);
            if (existing != null) {
                saved.remove(entry.dimension(), entry.pos());
                continue;
            }

            // Regrowth must never overwrite player edits. Only reclaim the exact block
            // state that stripping exposed (air included).
            if (!level.getBlockState(entry.pos()).equals(entry.expectedState())) {
                saved.remove(entry.dimension(), entry.pos());
                continue;
            }

            // A stripped block may only regrow if it is face-connected to Resonance
            // belonging to the same Milestone (or directly to that Milestone's Core).
            // Unsupported islands remain pending until living growth reconnects to them.
            if (!hasConnectedSupport(level, milestone, entry.pos())) {
                continue;
            }

            readyToRegrow.add(entry);
        }

        // Apply the shell chosen above. A newly restored block can support another
        // pending block on the next regeneration tick, producing visible propagation.
        for (RegenerationEntry entry : readyToRegrow) {
            ServerLevel level = findLevel(server, entry.dimension());
            if (level == null || !level.hasChunkAt(entry.pos())) {
                continue;
            }

            if (ResonanceManager.cover(level, entry.pos(), entry.milestoneId())) {
                // cover() also clears this task, but removing again is harmless and
                // keeps this method robust if cover's internals change later.
                saved.remove(entry.dimension(), entry.pos());
            }
        }
    }


    /**
     * Returns true when this stripped position can regrow without creating a floating
     * island. Only the six face-neighbours count; diagonal contact is deliberately not
     * enough. The owning Core acts as the root anchor for the first Resonance block.
     */
    private static boolean hasConnectedSupport(
            ServerLevel level,
            MilestoneRecord milestone,
            BlockPos pos
    ) {
        if (!milestone.dimension().equals(level.dimension().location().toString())) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);

            if (neighbour.equals(milestone.corePos())) {
                return true;
            }

            ResonanceRecord neighbourRecord = ResonanceManager.get(level, neighbour).orElse(null);
            if (neighbourRecord != null && neighbourRecord.milestoneId().equals(milestone.id())) {
                return true;
            }
        }

        return false;
    }

    public static boolean canRegenerate(MinecraftServer server, UUID milestoneId) {
        MilestoneRecord record = MilestoneManager.data(server).get(milestoneId).orElse(null);
        return record != null && canRegenerate(record.state());
    }

    public static boolean canRegenerate(MilestoneState state) {
        return switch (state) {
            case DORMANT, GERMINATING, RISING, BLOOMING -> true;
            case DISCOVERED, ACTIVE, COMPLETED -> false;
        };
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
