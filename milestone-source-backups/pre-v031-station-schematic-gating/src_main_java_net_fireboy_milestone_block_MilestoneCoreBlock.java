package net.fireboy.milestone.block;

import com.mojang.serialization.MapCodec;
import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.fireboy.milestone.resonance.GrowthManager;
import net.fireboy.milestone.resonance.ResonanceManager;
import net.fireboy.milestone.resonance.RegenerationManager;
import net.fireboy.milestone.resonance.RetractionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;

public class MilestoneCoreBlock extends BaseEntityBlock {
    public static final MapCodec<MilestoneCoreBlock> CODEC = simpleCodec(MilestoneCoreBlock::new);

    // 1 pixel inset from every horizontal side, and 9 pixels tall.
    // Minecraft block-shape coordinates run from 0..16.
    private static final VoxelShape SHAPE = box(
            1.0D, 0.0D, 1.0D,
            15.0D, 9.0D, 15.0D
    );

    public MilestoneCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MilestoneCoreBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }


    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        if (!level.isClientSide
                && level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer) {
            MilestoneGameplayManager.discoverCore(serverLevel, pos, serverPlayer);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        if (!level.isClientSide
                && level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer) {
            MilestoneGameplayManager.discoverCore(serverLevel, pos, serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level instanceof ServerLevel serverLevel) {
            BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
            if (blockEntity instanceof MilestoneCoreBlockEntity core) {
                MilestoneManager.register(serverLevel, core);
            }
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
            if (blockEntity instanceof MilestoneCoreBlockEntity core) {
                // A removed Core must not keep growing or leave temporary terrain behind.
                GrowthManager.cancel(serverLevel.getServer(), core.getMilestoneId());
                RetractionManager.cancel(serverLevel.getServer(), core.getMilestoneId());
                RegenerationManager.cancelMilestone(serverLevel.getServer(), core.getMilestoneId());
                ResonanceManager.retract(serverLevel, core.getMilestoneId());
                MilestoneManager.unregister(serverLevel, core.getMilestoneId());
            } else {
                // Fallback in case another mod removed the block entity before this callback.
                MilestoneManager.unregisterAt(serverLevel, pos);
            }
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
