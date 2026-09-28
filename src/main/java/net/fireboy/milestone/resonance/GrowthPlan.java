package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persistent state for one growing tendril.
 *
 * positions is the main tendril centreline. Each centreline point has an age
 * and applied maturity stage. Side branches are preplanned and stored here too,
 * but do not begin propagating until their parent trunk section has matured.
 */
public record GrowthPlan(
        UUID milestoneId,
        String dimension,
        BlockPos start,
        BlockPos end,
        List<BlockPos> positions,
        int cursor,
        boolean paused,
        List<Integer> ages,
        List<Integer> appliedStages,
        List<GrowthBranch> branches
) {
    public static final int FORMAT_VERSION = 3;

    public GrowthPlan {
        positions = List.copyOf(positions);
        cursor = Math.max(0, Math.min(cursor, positions.size()));
        ages = normalizedList(ages, positions.size(), -1);
        appliedStages = normalizedList(appliedStages, positions.size(), -1);
        branches = List.copyOf(branches);
    }

    public static GrowthPlan fresh(
            UUID milestoneId,
            String dimension,
            BlockPos start,
            BlockPos end,
            List<BlockPos> positions,
            List<GrowthBranch> branches
    ) {
        return new GrowthPlan(
                milestoneId,
                dimension,
                start,
                end,
                positions,
                0,
                false,
                Collections.nCopies(positions.size(), -1),
                Collections.nCopies(positions.size(), -1),
                branches
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("FormatVersion", FORMAT_VERSION);
        tag.putUUID("MilestoneId", milestoneId);
        tag.putString("Dimension", dimension);
        tag.putLong("Start", start.asLong());
        tag.putLong("End", end.asLong());

        long[] packedPositions = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            packedPositions[i] = positions.get(i).asLong();
        }
        tag.putLongArray("Positions", packedPositions);
        tag.putInt("Cursor", cursor);
        tag.putBoolean("Paused", paused);
        tag.putIntArray("Ages", toIntArray(ages));
        tag.putIntArray("AppliedStages", toIntArray(appliedStages));

        ListTag branchList = new ListTag();
        for (GrowthBranch branch : branches) {
            branchList.add(branch.save());
        }
        tag.put("Branches", branchList);
        return tag;
    }

    public static GrowthPlan load(CompoundTag tag) {
        long[] packedPositions = tag.getLongArray("Positions");
        List<BlockPos> positions = new ArrayList<>(packedPositions.length);
        for (long packed : packedPositions) {
            positions.add(BlockPos.of(packed));
        }

        List<GrowthBranch> branches = new ArrayList<>();
        ListTag branchList = tag.getList("Branches", Tag.TAG_COMPOUND);
        for (Tag entry : branchList) {
            branches.add(GrowthBranch.load((CompoundTag) entry));
        }

        return new GrowthPlan(
                tag.getUUID("MilestoneId"),
                tag.getString("Dimension"),
                BlockPos.of(tag.getLong("Start")),
                BlockPos.of(tag.getLong("End")),
                positions,
                tag.getInt("Cursor"),
                tag.getBoolean("Paused"),
                fromIntArray(tag.getIntArray("Ages")),
                fromIntArray(tag.getIntArray("AppliedStages")),
                branches
        );
    }

    public GrowthPlan withProgress(
            int newCursor,
            List<Integer> newAges,
            List<Integer> newAppliedStages,
            List<GrowthBranch> newBranches
    ) {
        return new GrowthPlan(
                milestoneId,
                dimension,
                start,
                end,
                positions,
                newCursor,
                paused,
                newAges,
                newAppliedStages,
                newBranches
        );
    }

    public GrowthPlan withPaused(boolean newPaused) {
        return new GrowthPlan(
                milestoneId,
                dimension,
                start,
                end,
                positions,
                cursor,
                newPaused,
                ages,
                appliedStages,
                branches
        );
    }

    public int totalPositions() {
        return positions.size();
    }

    public int remainingPositions() {
        return Math.max(0, positions.size() - cursor);
    }

    /** The main propagation front has reached its target; maturation may continue. */
    public boolean frontComplete() {
        return cursor >= positions.size();
    }

    private static List<Integer> normalizedList(List<Integer> source, int size, int fill) {
        ArrayList<Integer> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(i < source.size() ? source.get(i) : fill);
        }
        return List.copyOf(result);
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static List<Integer> fromIntArray(int[] values) {
        ArrayList<Integer> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(value);
        }
        return result;
    }
}
