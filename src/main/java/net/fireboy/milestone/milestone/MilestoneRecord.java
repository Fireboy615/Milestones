package net.fireboy.milestone.milestone;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record MilestoneRecord(
        UUID id,
        String dimension,
        BlockPos corePos,
        MilestoneState state,
        long createdGameTime
) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Dimension", dimension);
        tag.putInt("X", corePos.getX());
        tag.putInt("Y", corePos.getY());
        tag.putInt("Z", corePos.getZ());
        tag.putString("State", state.name());
        tag.putLong("CreatedGameTime", createdGameTime);
        return tag;
    }

    public static MilestoneRecord load(CompoundTag tag) {
        MilestoneState state;
        try {
            state = MilestoneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            state = MilestoneState.DORMANT;
        }

        return new MilestoneRecord(
                tag.getUUID("Id"),
                tag.getString("Dimension"),
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                state,
                tag.getLong("CreatedGameTime")
        );
    }

    public MilestoneRecord withState(MilestoneState newState) {
        return new MilestoneRecord(id, dimension, corePos, newState, createdGameTime);
    }
}
