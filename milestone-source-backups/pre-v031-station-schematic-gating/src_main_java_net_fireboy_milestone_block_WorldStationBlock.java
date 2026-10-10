package net.fireboy.milestone.block;

import com.mojang.serialization.MapCodec;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.milestone.MilestoneGameplayManager;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Permanent anchor for either of the two one-off spawn stations. */
public class WorldStationBlock extends Block {
    public static final MapCodec<WorldStationBlock> CODEC = simpleCodec(WorldStationBlock::new);

    public WorldStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends Block> codec() {
        return CODEC;
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
