package net.fireboy.milestone.item;

import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * A unique, UUID-bound reward for finding a Core. A readable survey of its
 * original chamber, not a buildable/consumable structure item yet.
 */
public final class DiscoverySchematicItem extends Item {
    public DiscoverySchematicItem(Properties properties) {
        super(properties);
    }

    private static CompoundTag storedData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = storedData(stack);
        if (!tag.hasUUID("MilestoneId")) {
            tooltip.add(Component.literal("Unbound discovery schematic")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.literal("Core: " + tag.getString("MilestoneType"))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("Site: " + tag.getString("Dimension"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Right-click to examine the chamber survey")
                .withStyle(ChatFormatting.GOLD));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            CompoundTag tag = storedData(stack);
            if (!tag.hasUUID("MilestoneId")) {
                serverPlayer.sendSystemMessage(Component.literal(
                        "This schematic is unbound. Only a discovered Core creates a real survey.")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                UUID id = tag.getUUID("MilestoneId");
                BlockPos pos = BlockPos.of(tag.getLong("CorePos"));
                serverPlayer.sendSystemMessage(Component.literal("══════ DISCOVERY SCHEMATIC ══════")
                        .withStyle(ChatFormatting.DARK_AQUA));
                serverPlayer.sendSystemMessage(Component.literal(
                        tag.getString("MilestoneType") + "  |  " + tag.getString("Dimension"))
                        .withStyle(ChatFormatting.AQUA));
                serverPlayer.sendSystemMessage(Component.literal("Core at " +
                        pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                        .withStyle(ChatFormatting.GRAY));
                serverPlayer.sendSystemMessage(Component.literal(
                        "Chamber plan: 5×5 polished-deepslate floor, hollow central chamber,")
                        .withStyle(ChatFormatting.GOLD));
                serverPlayer.sendSystemMessage(Component.literal(
                        "sea lanterns in the four upper corners; reinforced-deepslate pedestal beneath the Core.")
                        .withStyle(ChatFormatting.GOLD));
                MilestoneRecord current = MilestoneManager.get(serverPlayer.getServer(), id).orElse(null);
                if (current != null && current.discoveredBy() != null) {
                    serverPlayer.sendSystemMessage(Component.literal("Current state: "
                            + current.state().name() + "  |  Contributions: "
                            + current.contributorCount() + "/" + current.type().minimumContributors())
                            .withStyle(ChatFormatting.GREEN));
                }
                serverPlayer.sendSystemMessage(Component.literal(
                        "For assignments and submissions, return to the Mission Station.")
                        .withStyle(ChatFormatting.DARK_AQUA));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
