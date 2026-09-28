package net.fireboy.milestone.blockentity;

import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class MilestoneCoreBlockEntity extends BlockEntity {
    private UUID milestoneId = UUID.randomUUID();
    private MilestoneState milestoneState = MilestoneState.DORMANT;

    public MilestoneCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MILESTONE_CORE.get(), pos, state);
    }

    public UUID getMilestoneId() {
        return milestoneId;
    }

    public MilestoneState getMilestoneState() {
        return milestoneState;
    }

    public void setMilestoneState(MilestoneState milestoneState) {
        this.milestoneState = milestoneState;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("MilestoneId", milestoneId);
        tag.putString("MilestoneState", milestoneState.name());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.hasUUID("MilestoneId")) {
            milestoneId = tag.getUUID("MilestoneId");
        }

        if (tag.contains("MilestoneState")) {
            try {
                milestoneState = MilestoneState.valueOf(tag.getString("MilestoneState"));
            } catch (IllegalArgumentException ignored) {
                milestoneState = MilestoneState.DORMANT;
            }
        }
    }
}
