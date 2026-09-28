package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * One stripped Resonance position that is eligible to grow back later.
 * expectedState is the exposed terrain state that must still be present when
 * the cooldown expires; if a player changes the terrain, regeneration gives up.
 */
public record RegenerationEntry(
        String dimension,
        BlockPos pos,
        UUID milestoneId,
        BlockState expectedState,
        long dueGameTime
) {
}
