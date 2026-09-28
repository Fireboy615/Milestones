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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Adds sparse vanilla Glow Lichen to mature Resonance surfaces and tracks every
 * face Milestone creates so it can be cleaned up safely during stripping and
 * retraction without touching unrelated lichen.
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
     * Removes only Milestone-created lichen faces supported by this Resonance
     * position. Any other faces later added to the same lichen block are preserved.
     */
    public static void removeSupportedBy(ServerLevel level, BlockPos supportPos, UUID milestoneId) {
        String dimension = dimension(level);
        GlowLichenSavedData saved = data(level.getServer());
        List<GlowLichenRecord> records = saved.supportedBy(dimension, supportPos, milestoneId);

        for (GlowLichenRecord record : records) {
            BlockPos pos = record.pos();
            if (!level.hasChunkAt(pos)) {
                // Retraction already refuses to force-load its own Resonance chunks.
                // Leave the record so a later loaded cleanup can still identify it.
                continue;
            }

            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.GLOW_LICHEN) && MultifaceBlock.hasFace(state, record.face())) {
                BlockState updated = state.setValue(
                        MultifaceBlock.getFaceProperty(record.face()),
                        Boolean.FALSE
                );

                if (MultifaceBlock.availableFaces(updated).isEmpty()) {
                    updated = Blocks.AIR.defaultBlockState();
                }
                level.setBlock(pos, updated, Block.UPDATE_ALL);
            }

            saved.remove(dimension, pos);
        }
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
            if (level.hasChunkAt(record.pos())) {
                BlockState state = level.getBlockState(record.pos());
                if (state.is(Blocks.GLOW_LICHEN) && MultifaceBlock.hasFace(state, record.face())) {
                    BlockState updated = state.setValue(
                            MultifaceBlock.getFaceProperty(record.face()),
                            Boolean.FALSE
                    );
                    if (MultifaceBlock.availableFaces(updated).isEmpty()) {
                        updated = Blocks.AIR.defaultBlockState();
                    }
                    level.setBlock(record.pos(), updated, Block.UPDATE_ALL);
                }
            }
            saved.remove(record.dimension(), record.pos());
        }
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
