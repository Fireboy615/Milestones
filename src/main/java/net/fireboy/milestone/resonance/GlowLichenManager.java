package net.fireboy.milestone.resonance;

import net.fireboy.milestone.world.GlowLichenSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Adds sparse vanilla Glow Lichen to mature Resonance surfaces and tracks every
 * lichen voxel Milestone creates. Milestone lichen is only ever placed into empty
 * air and never merged into pre-existing lichen, so the whole tracked voxel belongs
 * to the Milestone and can be removed reliably during stripping/retraction.
 */
public final class GlowLichenManager {
    /** Chance that one newly-matured surface voxel sprouts lichen at all. */
    private static final double GROW_CHANCE = 0.18D;

    private static final SavedData.Factory<GlowLichenSavedData> FACTORY =
            new SavedData.Factory<>(GlowLichenSavedData::create, GlowLichenSavedData::load);

    private GlowLichenManager() {
    }

    public static GlowLichenSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, GlowLichenSavedData.DATA_NAME);
    }

    /**
     * Periodic repair pass for tracked Milestone lichen. This catches reload/order
     * edge cases where the supporting Resonance vanished before its decorative
     * lichen face was removed. Only Milestone-owned faces are touched.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 100 != 0) {
            return;
        }

        GlowLichenSavedData saved = data(server);
        List<GlowLichenRecord> snapshot = new ArrayList<>(saved.all());
        for (GlowLichenRecord record : snapshot) {
            ServerLevel level = findLevel(server, record.dimension());
            if (level == null || !level.hasChunkAt(record.pos()) || !level.hasChunkAt(record.supportPos())) {
                continue;
            }

            ResonanceRecord support = ResonanceManager.get(level, record.supportPos()).orElse(null);
            boolean validSupport = support != null
                    && support.milestoneId().equals(record.milestoneId())
                    && ResonanceManager.isResonantState(level.getBlockState(record.supportPos()));

            if (!validSupport) {
                removeTrackedBlock(level, record);
                saved.remove(record.dimension(), record.pos());
                continue;
            }

            // Face orientation is intentionally not used as an ownership check.
            // Older builds could save/remove the wrong face and then discard the
            // record, leaving a floating lichen voxel behind. If the tracked voxel
            // is still Glow Lichen, it remains ours regardless of its face state.
            BlockState state = level.getBlockState(record.pos());
            if (!state.is(Blocks.GLOW_LICHEN)) {
                saved.remove(record.dimension(), record.pos());
            }
        }
    }


    /**
     * Returns true when this exact voxel is temporary Glow Lichen created by the
     * same Milestone. Growth uses this before covering the voxel so it can treat
     * the decoration as part of the living vine rather than as original terrain.
     */
    public static boolean isOwnedAt(ServerLevel level, BlockPos pos, UUID milestoneId) {
        GlowLichenRecord record = data(level.getServer()).get(dimension(level), pos);
        return record != null
                && record.milestoneId().equals(milestoneId)
                && level.getBlockState(pos).is(Blocks.GLOW_LICHEN);
    }

    /**
     * Growth has successfully replaced one of its own decorative lichen voxels.
     * The world block is already Resonance at this point, so only discard the
     * lichen ownership record; do not touch the newly placed Resonance block.
     */
    public static void consumedByGrowth(ServerLevel level, BlockPos pos, UUID milestoneId) {
        String dimension = dimension(level);
        GlowLichenRecord record = data(level.getServer()).get(dimension, pos);
        if (record != null && record.milestoneId().equals(milestoneId)) {
            data(level.getServer()).remove(dimension, pos);
        }
    }

    /** Called when a Young Resonance voxel hardens into mature Resonance. */
    public static void tryGrowFrom(ServerLevel level, BlockPos supportPos, UUID milestoneId) {
        if (!level.getBlockState(supportPos).is(net.fireboy.milestone.registry.ModBlocks.RESONANT_BLOCK.get())) {
            return;
        }

        Random random = new Random(seed(supportPos, milestoneId));
        if (random.nextDouble() >= GROW_CHANCE) {
            return;
        }

        List<Direction> directions = new ArrayList<>(List.of(Direction.values()));
        Collections.shuffle(directions, random);

        MultifaceBlock glowLichen = (MultifaceBlock) Blocks.GLOW_LICHEN;
        String dimension = dimension(level);

        for (Direction outward : directions) {
            BlockPos lichenPos = supportPos.relative(outward);
            // Keep Milestone lichen in the same chunk as its supporting Resonance.
            // That guarantees support cleanup never needs to force-load an adjacent
            // chunk just to remove one decorative face during retraction.
            if ((lichenPos.getX() >> 4) != (supportPos.getX() >> 4)
                    || (lichenPos.getZ() >> 4) != (supportPos.getZ() >> 4)) {
                continue;
            }
            if (!level.hasChunkAt(lichenPos)) {
                continue;
            }

            // Only occupy genuinely empty space. We never merge into natural/player
            // lichen because that would make ownership and later cleanup ambiguous.
            BlockState current = level.getBlockState(lichenPos);
            if (!current.isAir()) {
                continue;
            }
            if (data(level.getServer()).get(dimension, lichenPos) != null) {
                continue;
            }

            // Multiface directions point from the lichen voxel toward its support.
            Direction supportFace = outward.getOpposite();
            BlockState placed = glowLichen.getStateForPlacement(
                    current,
                    level,
                    lichenPos,
                    supportFace
            );
            if (placed == null) {
                continue;
            }

            if (level.setBlock(lichenPos, placed, Block.UPDATE_ALL)) {
                data(level.getServer()).put(new GlowLichenRecord(
                        dimension,
                        lichenPos.immutable(),
                        supportPos.immutable(),
                        supportFace,
                        milestoneId
                ));
            }
            return; // Maximum one new lichen voxel per matured Resonance voxel.
        }
    }

    /**
     * Removes Milestone-created lichen supported by this Resonance position.
     *
     * Milestone lichen is only created in an AIR voxel and we refuse to merge into
     * existing lichen, so a tracked Glow Lichen block is entirely ours. Removing the
     * whole voxel is considerably more robust than trying to remove a saved face and
     * also fixes the old floating-lichen failure mode.
     */
    public static void removeSupportedBy(ServerLevel level, BlockPos supportPos, UUID milestoneId) {
        String dimension = dimension(level);
        GlowLichenSavedData saved = data(level.getServer());
        List<GlowLichenRecord> records = saved.supportedBy(dimension, supportPos, milestoneId);

        for (GlowLichenRecord record : records) {
            BlockPos pos = record.pos();
            if (!level.hasChunkAt(pos)) {
                // Leave the ownership record intact. The periodic repair pass will
                // remove it once the lichen chunk is loaded again.
                continue;
            }

            removeTrackedBlock(level, record);
            saved.remove(dimension, pos);
        }
    }

    /**
     * Final safety sweep for lichen left behind by older Milestone builds. Those
     * versions could accidentally save temporary lichen as a Resonance voxel's
     * original terrain and later restore it after its support had vanished. We only
     * remove Glow Lichen that Minecraft itself says can no longer survive, so valid
     * natural/player lichen is preserved.
     */
    public static int cleanupUnsupportedAround(ServerLevel level, List<BlockPos> formerResonance) {
        Set<Long> checked = new HashSet<>();
        int removed = 0;

        for (BlockPos resonancePos : formerResonance) {
            removed += cleanupUnsupportedAt(level, resonancePos, checked);
            for (Direction direction : Direction.values()) {
                removed += cleanupUnsupportedAt(level, resonancePos.relative(direction), checked);
            }
        }

        return removed;
    }

    private static int cleanupUnsupportedAt(ServerLevel level, BlockPos pos, Set<Long> checked) {
        if (!checked.add(pos.asLong()) || !level.hasChunkAt(pos)) {
            return 0;
        }

        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.GLOW_LICHEN) && !state.canSurvive(level, pos)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            data(level.getServer()).remove(dimension(level), pos);
            return 1;
        }
        return 0;
    }

    /** Emergency cleanup used when an entire Milestone is removed. */
    public static void removeAllLoaded(ServerLevel level, UUID milestoneId) {
        GlowLichenSavedData saved = data(level.getServer());
        List<GlowLichenRecord> records = new ArrayList<>();
        for (GlowLichenRecord record : saved.all()) {
            if (record.milestoneId().equals(milestoneId)
                    && record.dimension().equals(dimension(level))) {
                records.add(record);
            }
        }

        for (GlowLichenRecord record : records) {
            if (!level.hasChunkAt(record.pos())) {
                // Keep unloaded records so they can be repaired when loaded later.
                continue;
            }

            removeTrackedBlock(level, record);
            saved.remove(record.dimension(), record.pos());
        }
    }

    /**
     * Removes a tracked Milestone lichen voxel if it still exists. Because these
     * records are only created after placing into AIR, the entire Glow Lichen block
     * belongs to Milestone rather than just one directional face.
     */
    private static void removeTrackedBlock(ServerLevel level, GlowLichenRecord record) {
        BlockState state = level.getBlockState(record.pos());
        if (state.is(Blocks.GLOW_LICHEN)) {
            level.setBlock(record.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension(level).equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static long seed(BlockPos pos, UUID milestoneId) {
        long value = pos.asLong();
        value ^= milestoneId.getMostSignificantBits();
        value ^= Long.rotateLeft(milestoneId.getLeastSignificantBits(), 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return value;
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
