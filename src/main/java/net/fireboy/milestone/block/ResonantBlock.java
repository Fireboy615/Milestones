package net.fireboy.milestone.block;

import net.fireboy.milestone.resonance.ResonanceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ItemAbilities;

public class ResonantBlock extends Block {
    public ResonantBlock(Properties properties) {
        super(properties);
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
        if (!stack.canPerformAction(ItemAbilities.AXE_STRIP)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // The client does not own the saved original block state. It only needs to
        // acknowledge the interaction; the server performs the authoritative restore.
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.FAIL;
        }

        if (!ResonanceManager.strip(serverLevel, pos)) {
            return ItemInteractionResult.FAIL;
        }

        serverLevel.playSound(
                null,
                pos,
                SoundEvents.AXE_STRIP,
                SoundSource.BLOCKS,
                1.0F,
                1.0F
        );

        if (!player.getAbilities().instabuild) {
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        }

        return ItemInteractionResult.SUCCESS;
    }
}
