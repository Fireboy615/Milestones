package net.fireboy.milestone.milestone;

import net.fireboy.milestone.registry.ModItems;
import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.fireboy.milestone.task.TaskAssignment;
import net.fireboy.milestone.world.DiscoveryRewardsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The public, read-only discovery journal. The Mission Station still owns
 * assignment creation and contributions; the journal never exposes secret
 * undiscovered sites.
 */
public final class MilestoneJournal {
    private static final SavedData.Factory<DiscoveryRewardsSavedData> REWARDS =
            new SavedData.Factory<>(DiscoveryRewardsSavedData::create, DiscoveryRewardsSavedData::load);

    private MilestoneJournal() { }

    private static DiscoveryRewardsSavedData rewards(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(REWARDS, DiscoveryRewardsSavedData.DATA_NAME);
    }

    public static void show(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        List<MilestoneRecord> discovered = new ArrayList<>(MilestoneManager.data(server).all());
        discovered.removeIf(record -> record.discoveredBy() == null);
        discovered.sort(Comparator.comparingLong(MilestoneRecord::createdGameTime)
                .thenComparing(record -> record.id().toString()));

        player.sendSystemMessage(Component.literal("══════ MILESTONE JOURNAL ══════")
                .withStyle(ChatFormatting.DARK_AQUA));
        player.sendSystemMessage(Component.literal("Discovered: " + discovered.size()
                + "  |  Global unlocks: " + MilestoneProgressionManager.unlocks(server).size())
                .withStyle(ChatFormatting.AQUA));
        if (discovered.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "No Milestones discovered yet. Follow the Resonance to a hidden Core.")
                    .withStyle(ChatFormatting.GRAY));
        }
        for (int i = 0; i < discovered.size(); i++) {
            MilestoneRecord record = discovered.get(i);
            player.sendSystemMessage(Component.literal((i + 1) + ". " + record.type().displayName()
                    + "  [" + record.state().name() + "]")
                    .withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(Component.literal("   " + record.contributorCount() + "/"
                    + record.type().minimumContributors() + " contributors  |  "
                    + record.dimension() + " @ " + formatPos(record.corePos()))
                    .withStyle(ChatFormatting.GRAY));

            TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
            if (assignment == null) {
                player.sendSystemMessage(Component.literal(
                        "   Your objective: visit the Mission Station to receive it.")
                        .withStyle(ChatFormatting.YELLOW));
            } else {
                MilestoneTaskDefinition task = MilestoneTaskCatalog.get(record, assignment.taskId());
                String title = task == null ? "Unknown objective" : task.clue();
                player.sendSystemMessage(Component.literal("   Your objective: " + title + " — "
                        + (assignment.completed() ? "COMPLETE" : "INCOMPLETE"))
                        .withStyle(assignment.completed() ? ChatFormatting.GREEN : ChatFormatting.AQUA));
            }

            MilestoneType.RewardThreshold next = record.type().nextThresholdAfter(record.contributorCount());
            if (next != null) {
                player.sendSystemMessage(Component.literal("   Next reward at " + next.contributors()
                        + ": " + next.description()).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
        var unlocks = MilestoneProgressionManager.unlocks(server);
        if (!unlocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("Server unlocks:")
                    .withStyle(ChatFormatting.GREEN));
            for (MilestoneUnlock unlock : MilestoneUnlock.values()) {
                if (unlocks.contains(unlock)) {
                    player.sendSystemMessage(Component.literal("   ✓ " + unlock.name())
                            .withStyle(ChatFormatting.GREEN));
                }
            }
        }
        player.sendSystemMessage(Component.literal(
                "Visit the Mission Station to see or submit your secret objectives.")
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    private static String formatPos(net.minecraft.core.BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /** First discovery and old-world migration both use the same one-time path. */
    public static void grantDiscoveryRewards(ServerPlayer player, MilestoneRecord record) {
        if (record.discoveredBy() == null || !record.discoveredBy().equals(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        DiscoveryRewardsSavedData saved = rewards(server);

        if (!saved.hasCreateSchematic(record.id())) {
            try {
                // Filled Create schematic, with a real NBT template and client sync.
                ItemStack schematic = CreateDiscoverySchematic.create(player, record);
                boolean replaced = replaceLegacySchematic(player, record.id(), schematic);
                if (!replaced) player.getInventory().placeItemBackInInventory(schematic);
                saved.markSchematic(record.id());
                saved.markCreateSchematic(record.id());
                for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                    if (online != player) CreateDiscoverySchematic.sync(online, record);
                }
                player.sendSystemMessage(Component.literal(replaced
                        ? "Your old paper survey was upgraded to a filled Create schematic."
                        : "Discovery reward: a filled Create schematic of the Core chamber!")
                        .withStyle(ChatFormatting.GOLD));
            } catch (Exception exception) {
                player.sendSystemMessage(Component.literal(
                        "Could not prepare the Create schematic. It will retry next login.")
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (!saved.hasJournal(player.getUUID())) {
            player.getInventory().placeItemBackInInventory(
                    new ItemStack(ModItems.MILESTONE_JOURNAL.get()));
            saved.markJournal(player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "You received a Milestone Journal. Use it to review server discoveries.")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** Replace legacy paper in hand/inventory when upgrading an existing save. */
    private static boolean replaceLegacySchematic(ServerPlayer player, UUID milestoneId,
                                                   ItemStack replacement) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack old = inventory.getItem(slot);
            if (!old.is(ModItems.DISCOVERY_SCHEMATIC.get())) continue;
            CompoundTag tag = old.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (tag.hasUUID("MilestoneId") && milestoneId.equals(tag.getUUID("MilestoneId"))) {
                inventory.setItem(slot, replacement);
                return true;
            }
        }
        return false;
    }

    /** Login migrates existing rewards and syncs blueprints to all players for trading. */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        for (MilestoneRecord record : new ArrayList<>(MilestoneManager.data(server).all())) {
            if (record.discoveredBy() == null) continue;
            if (player.getUUID().equals(record.discoveredBy())) {
                grantDiscoveryRewards(player, record);
            }
            CreateDiscoverySchematic.sync(player, record);
        }
    }
}
