package net.fireboy.milestone.resonance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;

/**
 * One Glow Lichen face created by a Milestone. The face direction is expressed
 * from the lichen block toward the Resonance block that supports it.
 */
public record GlowLichenRecord(
        String dimension,
        BlockPos pos,
        BlockPos supportPos,
        Direction face,
        UUID milestoneId
) {
}
