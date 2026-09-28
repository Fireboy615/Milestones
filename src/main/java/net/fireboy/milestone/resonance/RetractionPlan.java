package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent reverse-growth plan for one Milestone. */
public record RetractionPlan(
        UUID milestoneId,
        String dimension,
        BlockPos corePos,
        List<BlockPos> positions,
        int fadeCursor,
        int restoreCursor
) {
    /**
     * v2 adds separate "fade to young" and "restore terrain" cursors so the
     * sparse young Resonance texture becomes a visible part of regression.
     */
    public static final int FORMAT_VERSION = 2;

    public RetractionPlan {
        positions = List.copyOf(positions);
        fadeCursor = Math.max(0, Math.min(fadeCursor, positions.size()));
        restoreCursor = Math.max(0, Math.min(restoreCursor, fadeCursor));
    }

    public static RetractionPlan fresh(
            UUID milestoneId,
            String dimension,
            BlockPos corePos,
            List<BlockPos> positions
    ) {
        return new RetractionPlan(
                milestoneId,
                dimension,
                corePos.immutable(),
                positions,
                0,
                0
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("FormatVersion", FORMAT_VERSION);
        tag.putUUID("MilestoneId", milestoneId);
        tag.putString("Dimension", dimension);
        tag.putLong("CorePos", corePos.asLong());

        long[] packed = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            packed[i] = positions.get(i).asLong();
        }
        tag.putLongArray("Positions", packed);
        tag.putInt("FadeCursor", fadeCursor);
        tag.putInt("RestoreCursor", restoreCursor);
        return tag;
    }

    public static RetractionPlan load(CompoundTag tag) {
        long[] packed = tag.getLongArray("Positions");
        List<BlockPos> positions = new ArrayList<>(packed.length);
        for (long value : packed) {
            positions.add(BlockPos.of(value));
        }

        return new RetractionPlan(
                tag.getUUID("MilestoneId"),
                tag.getString("Dimension"),
                BlockPos.of(tag.getLong("CorePos")),
                positions,
                tag.getInt("FadeCursor"),
                tag.getInt("RestoreCursor")
        );
    }

    public RetractionPlan withCursors(int newFadeCursor, int newRestoreCursor) {
        return new RetractionPlan(
                milestoneId,
                dimension,
                corePos,
                positions,
                newFadeCursor,
                newRestoreCursor
        );
    }

    public int totalPositions() {
        return positions.size();
    }

    public int fadedPositions() {
        return fadeCursor;
    }

    public int restoredPositions() {
        return restoreCursor;
    }

    public int youngPositions() {
        return Math.max(0, fadeCursor - restoreCursor);
    }

    public int remainingPositions() {
        return Math.max(0, positions.size() - restoreCursor);
    }

    public boolean complete() {
        return restoreCursor >= positions.size();
    }
}
