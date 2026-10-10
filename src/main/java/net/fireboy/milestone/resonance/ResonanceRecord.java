package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record ResonanceRecord(
        String dimension,
        BlockPos pos,
        UUID milestoneId,
        BlockState originalState,
        CompoundTag originalBlockEntity
) {
    public ResonanceRecord {
        pos = pos.immutable();
        originalBlockEntity = originalBlockEntity == null ? null : originalBlockEntity.copy();
    }
}
