package net.fireboy.milestone.milestone;

import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.menu.MilestoneMenu;
import net.fireboy.milestone.resonance.RegenerationManager;
import net.fireboy.milestone.resonance.RetractionManager;
import net.fireboy.milestone.task.AdvancementTaskDefinition;
import net.fireboy.milestone.task.ItemSubmissionTaskDefinition;
import net.fireboy.milestone.task.KillTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.fireboy.milestone.task.TaskAssignment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-authoritative Milestone gameplay loop: discovery, secret mixed task
 * assignment, contribution counting, and participation reward thresholds.
 */
public final class MilestoneGameplayManager {
    private MilestoneGameplayManager() {
    }

    /** The Core only discovers the location. All tasks now live at the Mission Station. */
    public static void discoverCore(ServerLevel level, BlockPos corePos, ServerPlayer player) {
        MilestoneRecord record = recordAt(level, corePos);
        if (record == null) {
            player.sendSystemMessage(Component.literal("This Core is not registered."));
            return;
        }
        boolean wasUndiscovered = isPreDiscovery(record.state());
        discoverIfNeeded(level, record, player);
        player.sendSystemMessage(Component.literal(wasUndiscovered
                ? "This Milestone is now discovered. Visit the Mission Station near spawn to receive and submit your task."
                : "This Milestone has already been discovered. The Mission Station near spawn handles its tasks and progress.")
                .withStyle(ChatFormatting.AQUA));
    }

    /** Open the central board; it can read unloaded Cores from persistent SavedData. */
    public static void openMissionStation(ServerLevel level, BlockPos stationPos, ServerPlayer player) {
        java.util.List<MilestoneRecord> records = new ArrayList<>(MilestoneManager.data(level.getServer()).all());
        records.removeIf(record -> record.discoveredBy() == null);
        records.sort(java.util.Comparator.comparingLong(MilestoneRecord::createdGameTime)
                .thenComparing(record -> record.id().toString()));
        java.util.List<UUID> ids = records.stream().map(MilestoneRecord::id).toList();
        if (!records.isEmpty()) {
            MilestoneRecord first = records.get(0);
            ServerLevel owningLevel = MilestoneManager.levelFor(level.getServer(), first);
            if (owningLevel != null) ensureAssignment(owningLevel, first, player);
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, menuPlayer) ->
                        new MilestoneMenu(containerId, inventory, level, stationPos, ids),
                Component.literal("Mission Station")),
                buffer -> buffer.writeBlockPos(stationPos));
    }

    /** Legacy debug compatibility; ordinary gameplay uses the Station. */
    public static void useEmptyHand(ServerLevel level, BlockPos corePos, ServerPlayer player) {
        discoverCore(level, corePos, player);
    }

    /** Legacy/debug entry point, not called by Core interactions anymore. */
    public static void useItem(ServerLevel level, BlockPos corePos, ServerPlayer player, ItemStack stack) {
        MilestoneRecord record = recordAt(level, corePos);
        if (record != null && record.discoveredBy() != null) {
            submitItemForRecord(level, record, player, stack);
        }
    }

    /** Item submission from the public station; the Core chunk needn't be loaded. */
    public static void submitFromStation(ServerLevel stationLevel, UUID milestoneId,
                                         ServerPlayer player, ItemStack stack) {
        MilestoneRecord record = MilestoneManager.get(stationLevel.getServer(), milestoneId).orElse(null);
        if (record == null || record.discoveredBy() == null) {
            player.sendSystemMessage(Component.literal("That Milestone hasn't been discovered."));
            return;
        }
        ServerLevel owningLevel = MilestoneManager.levelFor(stationLevel.getServer(), record);
        if (owningLevel == null) return;
        submitItemForRecord(owningLevel, record, player, stack);
    }

    private static void submitItemForRecord(ServerLevel level, MilestoneRecord record,
                                            ServerPlayer player, ItemStack stack) {
        record = ensureAssignment(level, record, player);
        TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
        if (assignment == null) return;
        if (assignment.completed()) {
            player.sendSystemMessage(Component.literal("You've already contributed to this Milestone.")
                    .withStyle(ChatFormatting.GREEN));
            return;
        }

        MilestoneTaskDefinition genericDefinition = MilestoneTaskCatalog.get(record, assignment.taskId());
        if (!(genericDefinition instanceof ItemSubmissionTaskDefinition definition)) {
            player.sendSystemMessage(Component.literal("This objective completes automatically, not by offering an item.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!stack.is(definition.item()) || stack.getCount() < definition.count()) {
            player.sendSystemMessage(Component.literal("The Mission Station rejects this offering.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!player.getAbilities().instabuild) stack.shrink(definition.count());
        completeContribution(level, record, player,
                Component.literal("The Mission Station accepts your contribution.")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static MilestoneRecord ensureAssignment(ServerLevel level, MilestoneRecord record, ServerPlayer player) {
        if (record.assignment(player.getUUID()).isPresent()) {
            return record;
        }

        MilestoneTaskDefinition definition = MilestoneTaskCatalog.choose(record, player);
        TaskAssignment assignment = new TaskAssignment(
                player.getUUID(),
                definition.id(),
                false,
                level.getGameTime(),
                -1L
        );
        record = record.withAssignment(assignment);
        return MilestoneManager.save(level, record);
    }

    /** Compatibility helper for commands/callers that only have a UUID. */
    public static MilestoneRecord ensureAssignment(ServerLevel level, MilestoneRecord record, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return record;
        }
        return ensureAssignment(level, record, player);
    }

    public static void showStatus(ServerPlayer player, MilestoneRecord record) {
        player.sendSystemMessage(Component.literal("──────── MILESTONE ────────")
                .withStyle(ChatFormatting.DARK_AQUA));
        player.sendSystemMessage(Component.literal(record.type().displayName())
                .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal("State: " + record.state().name())
                .withStyle(ChatFormatting.GRAY));

        TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
        if (assignment == null) {
            player.sendSystemMessage(Component.literal("Your task: not assigned yet")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            MilestoneTaskDefinition definition = MilestoneTaskCatalog.get(record, assignment.taskId());
            String clue = definition == null ? "Unknown task" : definition.clue();
            player.sendSystemMessage(Component.literal("Your task: " + clue)
                    .withStyle(ChatFormatting.AQUA));
            if (definition != null) {
                player.sendSystemMessage(Component.literal(completionInstruction(definition))
                        .withStyle(ChatFormatting.GRAY));
            }
            player.sendSystemMessage(Component.literal(
                    "Your status: " + (assignment.completed() ? "COMPLETE" : "INCOMPLETE")
            ).withStyle(assignment.completed() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }

        int contributors = record.contributorCount();
        int required = record.type().minimumContributors();
        player.sendSystemMessage(Component.literal(
                "Contributors: " + contributors + "/" + required + " required"
        ).withStyle(ChatFormatting.GOLD));

        MilestoneType.RewardThreshold next = record.type().nextThresholdAfter(contributors);
        if (next != null) {
            player.sendSystemMessage(Component.literal(
                    "Next unlock: " + next.contributors() + " contributors — " + next.description()
            ).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            player.sendSystemMessage(Component.literal("All current participation rewards unlocked.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        player.sendSystemMessage(Component.literal("───────────────────────────")
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    public static MilestoneRecord resetPlayerTask(ServerLevel level, MilestoneRecord record, UUID playerId) {
        MilestoneRecord updated = record.resetAssignment(playerId);
        return MilestoneManager.save(level, normalizeProgressState(level, updated));
    }

    /** Debug/admin reroll that deliberately advances to a different task when possible. */
    public static MilestoneRecord rerollPlayerTask(ServerLevel level, MilestoneRecord record, ServerPlayer player) {
        TaskAssignment existing = record.assignment(player.getUUID()).orElse(null);
        String previousId = existing == null ? "" : existing.taskId();

        MilestoneRecord cleared = record.resetAssignment(player.getUUID());
        cleared = MilestoneManager.save(level, normalizeProgressState(level, cleared));

        MilestoneTaskDefinition definition = previousId.isEmpty()
                ? MilestoneTaskCatalog.choose(cleared, player)
                : MilestoneTaskCatalog.chooseDifferent(cleared, player, previousId);

        TaskAssignment replacement = new TaskAssignment(
                player.getUUID(),
                definition.id(),
                false,
                level.getGameTime(),
                -1L
        );
        return MilestoneManager.save(level, cleared.withAssignment(replacement));
    }

    public static MilestoneRecord addFakeContributor(ServerLevel level, MilestoneRecord record) {
        return addFakeContributors(level, record, 1);
    }

    public static MilestoneRecord addFakeContributors(ServerLevel level, MilestoneRecord record, int amount) {
        int count = Math.max(0, amount);
        MilestoneRecord updated = record;
        for (int i = 0; i < count; i++) {
            int before = updated.contributorCount();
            updated = updated.withContributor(UUID.randomUUID());
            MilestoneManager.save(level, updated);
            updated = applyThresholds(level, updated, before, updated.contributorCount());
        }
        return updated;
    }

    public static MilestoneRecord clearContributors(ServerLevel level, MilestoneRecord record) {
        MilestoneRecord updated = record.clearContributionProgress();
        return MilestoneManager.save(level, updated);
    }

    public static MilestoneRecord recordAt(ServerLevel level, BlockPos corePos) {
        if (!(level.getBlockEntity(corePos) instanceof MilestoneCoreBlockEntity core)) {
            return null;
        }
        return MilestoneManager.get(level.getServer(), core.getMilestoneId())
                .orElseGet(() -> MilestoneManager.register(level, core));
    }

    /** Complete any assigned hunt task whose target was personally killed by this player. */
    public static void onPlayerKill(ServerPlayer player, EntityType<?> killedType) {
        completeMatchingWorldTasks(
                player,
                definition -> definition instanceof KillTaskDefinition killTask
                        && killTask.target() == killedType,
                Component.literal("The Mission Station acknowledges your hunt.").withStyle(ChatFormatting.GREEN)
        );
    }

    /** Complete any assigned advancement task matching the advancement just earned. */
    public static void onAdvancementEarned(ServerPlayer player, ResourceLocation advancementId) {
        completeMatchingWorldTasks(
                player,
                definition -> definition instanceof AdvancementTaskDefinition advancementTask
                        && advancementTask.advancementId().equals(advancementId),
                Component.literal("The Mission Station acknowledges your achievement.").withStyle(ChatFormatting.GREEN)
        );
    }

    private static void completeMatchingWorldTasks(
            ServerPlayer player,
            Predicate<MilestoneTaskDefinition> matcher,
            Component successMessage
    ) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        // Snapshot because completing a task replaces records in SavedData.
        for (MilestoneRecord record : new ArrayList<>(MilestoneManager.data(server).all())) {
            TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
            if (assignment == null || assignment.completed()) {
                continue;
            }

            MilestoneTaskDefinition definition = MilestoneTaskCatalog.get(record, assignment.taskId());
            if (definition == null || !matcher.test(definition)) {
                continue;
            }

            ServerLevel owningLevel = MilestoneManager.levelFor(server, record);
            if (owningLevel == null) {
                continue;
            }

            completeContribution(owningLevel, record, player, successMessage);
        }
    }

    private static MilestoneRecord completeContribution(
            ServerLevel level,
            MilestoneRecord record,
            ServerPlayer player,
            Component successMessage
    ) {
        int before = record.contributorCount();
        record = record.completeTask(player.getUUID(), level.getGameTime());
        MilestoneManager.save(level, record);
        int after = record.contributorCount();

        player.sendSystemMessage(successMessage);
        broadcast(level.getServer(), Component.literal(
                player.getScoreboardName() + " contributed to " + record.type().displayName() +
                        " (" + after + "/" + record.type().minimumContributors() + ")."
        ).withStyle(ChatFormatting.GOLD));

        record = applyThresholds(level, record, before, after);
        showStatus(player, record);
        return record;
    }

    public static String completionInstruction(MilestoneTaskDefinition definition) {
        if (definition instanceof ItemSubmissionTaskDefinition) {
            return "Submission: bring the requested offering to the Mission Station.";
        }
        if (definition instanceof KillTaskDefinition) {
            return "Completion: automatic when you personally land the killing blow.";
        }
        if (definition instanceof AdvancementTaskDefinition) {
            return "Completion: automatic when you earn the requested advancement.";
        }
        return "Completion method unknown.";
    }

    private static MilestoneRecord discoverIfNeeded(
            ServerLevel level,
            MilestoneRecord record,
            ServerPlayer finder
    ) {
        if (!isPreDiscovery(record.state())) {
            return record;
        }

        record = record.withDiscovery(finder.getUUID(), level.getGameTime());
        MilestoneManager.save(level, record);

        // Discovery ends the living-growth phase. Existing Resonance retracts while
        // contribution gameplay begins.
        RegenerationManager.cancelMilestone(level.getServer(), record.id());
        RetractionManager.start(level, record.corePos(), record.id());

        broadcast(level.getServer(), Component.literal(
                finder.getScoreboardName() + " discovered " + record.type().displayName() + "."
        ).withStyle(ChatFormatting.AQUA));
        finder.sendSystemMessage(Component.literal("You are recorded as this Milestone's finder.")
                .withStyle(ChatFormatting.GOLD));
        MilestoneJournal.grantDiscoveryRewards(finder, record);
        return record;
    }

    private static boolean isPreDiscovery(MilestoneState state) {
        return state == MilestoneState.DORMANT
                || state == MilestoneState.GERMINATING
                || state == MilestoneState.RISING
                || state == MilestoneState.BLOOMING;
    }

    private static MilestoneRecord applyThresholds(
            ServerLevel level,
            MilestoneRecord record,
            int before,
            int after
    ) {
        for (MilestoneType.RewardThreshold threshold : record.type().thresholds()) {
            if (before < threshold.contributors() && after >= threshold.contributors()) {
                boolean newlyUnlocked = threshold.unlock() == null
                        || MilestoneProgressionManager.unlock(level.getServer(), threshold.unlock());

                // Site-local prototype thresholds can always announce. Global progression
                // rewards announce only the first time the server earns that unlock.
                if (newlyUnlocked) {
                    broadcast(level.getServer(), Component.literal(
                            record.type().displayName() + ": " + threshold.description() +
                                    " (" + threshold.contributors() + " contributors)."
                    ).withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
        }

        if (before < record.type().minimumContributors()
                && after >= record.type().minimumContributors()
                && record.state() != MilestoneState.COMPLETED) {
            record = MilestoneManager.setState(level, record, MilestoneState.ACTIVE);
        }

        if (after >= record.type().finalThreshold()
                && record.state() != MilestoneState.COMPLETED) {
            record = MilestoneManager.setState(level, record, MilestoneState.COMPLETED);
        }
        return record;
    }

    private static MilestoneRecord normalizeProgressState(ServerLevel level, MilestoneRecord record) {
        int count = record.contributorCount();
        MilestoneState desired;
        if (record.discoveredBy() == null) {
            desired = MilestoneState.DORMANT;
        } else if (count >= record.type().finalThreshold()) {
            desired = MilestoneState.COMPLETED;
        } else if (count >= record.type().minimumContributors()) {
            desired = MilestoneState.ACTIVE;
        } else {
            desired = MilestoneState.DISCOVERED;
        }
        return MilestoneManager.setState(level, record, desired);
    }

    private static void broadcast(MinecraftServer server, Component component) {
        server.getPlayerList().broadcastSystemMessage(component, false);
    }
}
