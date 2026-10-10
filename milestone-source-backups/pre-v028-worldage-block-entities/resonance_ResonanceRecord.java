package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public record ResonanceRecord(
        String dimension,
        BlockPos pos,
        UUID milestoneId,
        BlockState originalState
) {
}
