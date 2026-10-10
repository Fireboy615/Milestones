package net.fireboy.milestone.item;

import net.fireboy.milestone.milestone.MilestoneJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Portable, server-authoritative Milestone progress viewer. */
public final class MilestoneJournalItem extends Item {
    public MilestoneJournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MilestoneJournal.show(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Right-click to read discoveries and progress")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Tasks are submitted at the Mission Station")
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
