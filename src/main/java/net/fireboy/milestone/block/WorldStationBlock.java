package net.fireboy.milestone.block;

import com.mojang.serialization.MapCodec;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.fireboy.milestone.blockentity.MissionStationBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Both permanent stations; Mission Station now uses the former Core renderer. */
public class WorldStationBlock extends BaseEntityBlock {
    public static final MapCodec<WorldStationBlock> CODEC = simpleCodec(WorldStationBlock::new);
    private static final VoxelShape STATION_SHAPE = box(1, 0, 1, 15, 9, 15);

    public WorldStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.is(ModBlocks.MISSION_STATION.get())
                ? new MissionStationBlockEntity(pos, state) : null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.is(ModBlocks.MISSION_STATION.get())
                ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.is(ModBlocks.MISSION_STATION.get()) ? STATION_SHAPE : super.getShape(state, level, pos, context);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.is(ModBlocks.MISSION_STATION.get()) ? STATION_SHAPE : super.getCollisionShape(state, level, pos, context);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        inform(level, state, pos, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        inform(level, state, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void inform(Level level, BlockState state, BlockPos pos, Player player) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) return;
        if (state.is(ModBlocks.MISSION_STATION.get()) && level instanceof ServerLevel serverLevel) {
            MilestoneGameplayManager.openMissionStation(serverLevel, pos, serverPlayer);
            return;
        }
        serverPlayer.sendSystemMessage(Component.literal(
                "Resonant Exchange: the Marks shop and rotating stock are not implemented yet."));
    }
}
