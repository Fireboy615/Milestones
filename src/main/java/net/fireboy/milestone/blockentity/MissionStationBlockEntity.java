package net.fireboy.milestone.blockentity;

import net.fireboy.milestone.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Renderer host for the Mission Station's former Core appearance. */
public final class MissionStationBlockEntity extends BlockEntity {
    public MissionStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MISSION_STATION.get(), pos, state);
    }
}
