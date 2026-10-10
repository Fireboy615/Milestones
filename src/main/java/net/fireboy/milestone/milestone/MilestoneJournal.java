package net.fireboy.milestone.milestone;

import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.fireboy.milestone.task.TaskAssignment;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The public, read-only discovery journal. The Mission Station still owns
 * assignment creation and contributions; the journal never exposes secret
 * undiscovered sites.
 */
public final class MilestoneJournal {
    private MilestoneJournal() { }

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

            if (!StationConstructionManager.isVerified(server, record)) {
                player.sendSystemMessage(Component.literal(
                        "   Construction required: claim the Create schematic at the Mission Station, build and verify it nearby.")
                        .withStyle(ChatFormatting.YELLOW));
                continue;
            }
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

    /** Login migrates existing rewards and syncs blueprints to all players for trading. */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        for (MilestoneRecord record : new ArrayList<>(MilestoneManager.data(server).all())) {
            if (record.discoveredBy() == null) continue;

            CreateDiscoverySchematic.sync(player, record);
        }
    }
}
