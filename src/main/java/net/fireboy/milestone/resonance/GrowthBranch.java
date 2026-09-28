package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent state for one side branch growing from a main tendril node.
 *
 * The branch is preplanned, but remains dormant until its root on the main
 * tendril is mature enough. Once active it advances and matures exactly like
 * the main tendril, just with a smaller maximum radius.
 */
public record GrowthBranch(
        int rootIndex,
        List<BlockPos> positions,
        int cursor,
        List<Integer> ages,
        List<Integer> appliedStages
) {
    public GrowthBranch {
        positions = List.copyOf(positions);
        cursor = Math.max(0, Math.min(cursor, positions.size()));
        ages = normalizedList(ages, positions.size(), -1);
        appliedStages = normalizedList(appliedStages, positions.size(), -1);
    }

    public static GrowthBranch fresh(int rootIndex, List<BlockPos> positions) {
        return new GrowthBranch(
                rootIndex,
                positions,
                0,
                Collections.nCopies(positions.size(), -1),
                Collections.nCopies(positions.size(), -1)
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("RootIndex", rootIndex);

        long[] packedPositions = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            packedPositions[i] = positions.get(i).asLong();
        }
        tag.putLongArray("Positions", packedPositions);
        tag.putInt("Cursor", cursor);
        tag.putIntArray("Ages", toIntArray(ages));
        tag.putIntArray("AppliedStages", toIntArray(appliedStages));
        return tag;
    }

    public static GrowthBranch load(CompoundTag tag) {
        long[] packedPositions = tag.getLongArray("Positions");
        List<BlockPos> positions = new ArrayList<>(packedPositions.length);
        for (long packed : packedPositions) {
            positions.add(BlockPos.of(packed));
        }

        return new GrowthBranch(
                tag.getInt("RootIndex"),
                positions,
                tag.getInt("Cursor"),
                fromIntArray(tag.getIntArray("Ages")),
                fromIntArray(tag.getIntArray("AppliedStages"))
        );
    }

    public GrowthBranch withProgress(
            int newCursor,
            List<Integer> newAges,
            List<Integer> newAppliedStages
    ) {
        return new GrowthBranch(
                rootIndex,
                positions,
                newCursor,
                newAges,
                newAppliedStages
        );
    }

    public int totalPositions() {
        return positions.size();
    }

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
