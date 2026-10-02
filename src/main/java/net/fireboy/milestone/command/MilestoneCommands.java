package net.fireboy.milestone.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.gateway.PublicGatewayManager;
import net.fireboy.milestone.gateway.PublicGatewayRecord;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneProgressionManager;
import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.resonance.GrowthManager;
import net.fireboy.milestone.resonance.GrowthPlan;
import net.fireboy.milestone.resonance.ResonanceManager;
import net.fireboy.milestone.resonance.RegenerationManager;
import net.fireboy.milestone.resonance.ResonanceRecord;
import net.fireboy.milestone.resonance.RetractionManager;
import net.fireboy.milestone.resonance.RetractionPlan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

public final class MilestoneCommands {
    private static final int SEARCH_RADIUS = 12;
    private static final int RESONANCE_OWNER_RADIUS = 32;
    private static final int TENDRIL_OWNER_RADIUS = 128;
    private static final int GATEWAY_SEARCH_RADIUS = 8;
    private static final double MAX_TENDRIL_DISTANCE = 96.0D;

    private MilestoneCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("milestone")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spawncore")
                                .executes(MilestoneCommands::spawnCore))
                        .then(Commands.literal("info")
                                .executes(MilestoneCommands::info))
                        .then(Commands.literal("list")
                                .executes(MilestoneCommands::list))
                        .then(Commands.literal("unlocks")
                                .executes(MilestoneCommands::unlocks)
                                .then(Commands.literal("clear")
                                        .executes(MilestoneCommands::unlocksClear)))
                        .then(Commands.literal("setstate")
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (MilestoneState state : MilestoneState.values()) {
                                                builder.suggest(state.name().toLowerCase(Locale.ROOT));
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(MilestoneCommands::setState)))
                        .then(Commands.literal("settype")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (MilestoneType type : MilestoneType.values()) {
                                                builder.suggest(type.name().toLowerCase(Locale.ROOT));
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(MilestoneCommands::setType)))
                        .then(Commands.literal("resonate")
                                .executes(MilestoneCommands::resonate))
                        .then(Commands.literal("resonatearea")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 12))
                                        .executes(MilestoneCommands::resonateArea)))
                        .then(Commands.literal("growto")
                                .executes(MilestoneCommands::growTo))
                        .then(Commands.literal("growthstatus")
                                .executes(MilestoneCommands::growthStatus))
                        .then(Commands.literal("pausegrowth")
                                .executes(MilestoneCommands::pauseGrowth))
                        .then(Commands.literal("resumegrowth")
                                .executes(MilestoneCommands::resumeGrowth))
                        .then(Commands.literal("retract")
                                .executes(MilestoneCommands::retract))
                        .then(Commands.literal("retractionstatus")
                                .executes(MilestoneCommands::retractionStatus))
                        .then(Commands.literal("regenerationstatus")
                                .executes(MilestoneCommands::regenerationStatus))
                        .then(Commands.literal("resonanceinfo")
                                .executes(MilestoneCommands::resonanceInfo))
                        .then(Commands.literal("task")
                                .then(Commands.literal("view")
                                        .executes(MilestoneCommands::taskView))
                                .then(Commands.literal("reroll")
                                        .executes(MilestoneCommands::taskReroll)))
                        .then(Commands.literal("contributor")
                                .then(Commands.literal("addfake")
                                        .executes(MilestoneCommands::contributorAddFake)
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                                .executes(MilestoneCommands::contributorAddFake)))
                                .then(Commands.literal("clear")
                                        .executes(MilestoneCommands::contributorClear)))
                        .then(Commands.literal("gateway")
                                .then(Commands.literal("register")
                                        .executes(MilestoneCommands::gatewayRegister))
                                .then(Commands.literal("unregister")
                                        .executes(MilestoneCommands::gatewayUnregister))
                                .then(Commands.literal("list")
                                        .executes(MilestoneCommands::gatewayList))
                                .then(Commands.literal("clear")
                                        .executes(MilestoneCommands::gatewayClear)))
        );
    }

    private static int spawnCore(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 2);

        if (!level.setBlockAndUpdate(pos, ModBlocks.MILESTONE_CORE.get().defaultBlockState())) {
            source.sendFailure(Component.literal("Could not place a Milestone Core at " + formatPos(pos) + "."));
            return 0;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof MilestoneCoreBlockEntity core)) {
            source.sendFailure(Component.literal("Core block placed, but its block entity was not created."));
            return 0;
        }

        MilestoneRecord record = MilestoneManager.register(level, core);
        source.sendSuccess(
                () -> Component.literal(
                        "Spawned Milestone " + shortId(record) + " at " + formatPos(pos) +
                                " in state " + record.state().name() + "."
                ),
                true
        );
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        MilestoneRecord record = MilestoneManager.nearest(source.getServer(), level, origin, SEARCH_RADIUS).orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal("No registered Milestone Core within " + SEARCH_RADIUS + " blocks."));
            return 0;
        }

        int resonanceCount = ResonanceManager.count(level, record.id());
        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(record) +
                                " | type=" + record.type().name() +
                                " | state=" + record.state().name() +
                                " | pos=" + formatPos(record.corePos()) +
                                " | dimension=" + record.dimension() +
                                " | contributors=" + record.contributorCount() +
                                " | assignedTasks=" + record.assignments().size() +
                                " | resonance=" + resonanceCount +
                                " | createdTick=" + record.createdGameTime()
                ),
                false
        );
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var records = MilestoneManager.data(source.getServer()).all();

        source.sendSuccess(() -> Component.literal("Registered Milestones: " + records.size()), false);
        for (MilestoneRecord record : records) {
            source.sendSuccess(
                    () -> Component.literal(
                            "- " + shortId(record) +
                                    " " + record.state().name() +
                                    " contributors=" + record.contributorCount() +
                                    " @ " + formatPos(record.corePos()) +
                                    " [" + record.dimension() + "]"
                    ),
                    false
            );
        }
        return records.size();
    }

    private static int unlocks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var unlocked = MilestoneProgressionManager.unlocks(source.getServer());

        if (unlocked.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No server-wide Milestone unlocks have been earned yet."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Server-wide Milestone unlocks (" + unlocked.size() + "):"), false);
        for (MilestoneUnlock unlock : MilestoneUnlock.values()) {
            if (unlocked.contains(unlock)) {
                source.sendSuccess(() -> Component.literal("- " + unlock.name()), false);
            }
        }
        return unlocked.size();
    }

    private static int unlocksClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int before = MilestoneProgressionManager.unlocks(source.getServer()).size();
        MilestoneProgressionManager.clear(source.getServer());
        source.sendSuccess(
                () -> Component.literal("Cleared " + before + " server-wide Milestone unlock(s)."),
                true
        );
        return before;
    }

    private static int setState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        String requested = StringArgumentType.getString(context, "state");

        MilestoneState newState;
        try {
            newState = MilestoneState.valueOf(requested.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.literal("Unknown Milestone state: " + requested));
            return 0;
        }

        MilestoneRecord record = MilestoneManager.nearest(source.getServer(), level, origin, SEARCH_RADIUS).orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal("No registered Milestone Core within " + SEARCH_RADIUS + " blocks."));
            return 0;
        }

        BlockEntity blockEntity = level.getBlockEntity(record.corePos());
        if (!(blockEntity instanceof MilestoneCoreBlockEntity core)) {
            source.sendFailure(Component.literal("The registered Core is not currently loaded or is missing."));
            return 0;
        }

        core.setMilestoneState(newState);
        MilestoneManager.updateState(level, core);
        if (!RegenerationManager.canRegenerate(newState)) {
            RegenerationManager.cancelMilestone(source.getServer(), record.id());
        }

        source.sendSuccess(
                () -> Component.literal("Milestone " + shortId(record) + " state -> " + newState.name()),
                true
        );
        return 1;
    }

    private static int setType(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String requested = StringArgumentType.getString(context, "type");

        MilestoneType newType;
        try {
            newType = MilestoneType.valueOf(requested.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.literal("Unknown Milestone type: " + requested));
            return 0;
        }

        MilestoneRecord record = nearestGameplayCore(source);
        if (record == null) {
            return 0;
        }

        if (record.type() == newType) {
            source.sendSuccess(
                    () -> Component.literal("Milestone " + shortId(record) + " is already " + newType.name() + "."),
                    false
            );
            return 1;
        }

        MilestoneRecord updated = record.withTypeResetProgress(newType);
        MilestoneManager.save(level, updated);

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(updated) + " type -> " + newType.name() +
                                ". Tasks and contribution progress were reset."
                ),
                true
        );
        return 1;
    }

    private static int resonate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos targetPos = lookedAtBlock(player);
        if (targetPos == null) {
            source.sendFailure(Component.literal("Look at a block within reach first."));
            return 0;
        }

        MilestoneRecord owner = nearestOwner(source, level, targetPos);
        if (owner == null) {
            return 0;
        }

        if (!ResonanceManager.cover(level, targetPos, owner.id())) {
            source.sendFailure(Component.literal("That block cannot be covered by Resonance yet."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Covered " + formatPos(targetPos) +
                                " with Resonance owned by Milestone " + shortId(owner) + "."
                ),
                true
        );
        return 1;
    }

    private static int resonateArea(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos center = lookedAtBlock(player);
        if (center == null) {
            source.sendFailure(Component.literal("Look at a block within reach first."));
            return 0;
        }

        MilestoneRecord owner = nearestOwner(source, level, center);
        if (owner == null) {
            return 0;
        }

        int radius = IntegerArgumentType.getInteger(context, "radius");
        ResonanceManager.GrowthResult result = ResonanceManager.coverArea(
                level,
                center,
                radius,
                owner.id()
        );

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) + " grew " + result.covered() +
                                " Resonant blocks around " + formatPos(center) +
                                " (radius " + radius + ", skipped " + result.skipped() + ")."
                ),
                true
        );
        return result.covered();
    }

    private static int growTo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos target = lookedAtBlock(player);
        if (target == null) {
            source.sendFailure(Component.literal("Look at the block you want the tendril to grow toward."));
            return 0;
        }

        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                target,
                TENDRIL_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + TENDRIL_OWNER_RADIUS + " blocks of the target."
            ));
            return 0;
        }

        double dx = owner.corePos().getX() - target.getX();
        double dy = owner.corePos().getY() - target.getY();
        double dz = owner.corePos().getZ() - target.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > MAX_TENDRIL_DISTANCE) {
            source.sendFailure(Component.literal(
                    "Target is " + String.format(Locale.ROOT, "%.1f", distance) +
                            " blocks from the Core; test tendrils are limited to " +
                            (int) MAX_TENDRIL_DISTANCE + " blocks for now."
            ));
            return 0;
        }

        GrowthManager.StartResult result = GrowthManager.start(
                level,
                owner.corePos(),
                target,
                owner.id()
        );

        if (!result.started()) {
            source.sendFailure(Component.literal(result.failureReason()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) + " planned a " +
                                String.format(Locale.ROOT, "%.1f", distance) + " block tendril to " +
                                formatPos(target) + " (" + result.plannedPositions() +
                                " centreline points, " + result.plannedBranches() +
                                " planned branches). Growth has started."
                ),
                true
        );
        return result.plannedPositions();
    }

    private static int growthStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                TENDRIL_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + TENDRIL_OWNER_RADIUS + " blocks."
            ));
            return 0;
        }

        GrowthPlan plan = GrowthManager.get(source.getServer(), owner.id()).orElse(null);
        if (plan == null) {
            source.sendSuccess(
                    () -> Component.literal("Milestone " + shortId(owner) + " has no active growth plan."),
                    false
            );
            return 1;
        }

        double percent = plan.totalPositions() == 0
                ? 100.0D
                : plan.cursor() * 100.0D / plan.totalPositions();
        int mature = GrowthManager.matureNodeCount(plan);
        int startedBranches = GrowthManager.startedBranchCount(plan);
        int completedBranches = GrowthManager.completedBranchCount(plan);

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) +
                                " growth " + (plan.paused() ? "PAUSED" : "RUNNING") +
                                " | front=" + plan.cursor() + "/" + plan.totalPositions() +
                                " (" + String.format(Locale.ROOT, "%.1f", percent) + "%)" +
                                " | mature=" + mature + "/" + plan.totalPositions() +
                                " | branches=" + startedBranches + "/" + plan.branches().size() +
                                " started, " + completedBranches + " mature" +
                                " | target=" + formatPos(plan.end()) +
                                " | step=" + GrowthManager.POSITIONS_PER_STEP +
                                " centreline point every " + GrowthManager.GROWTH_TICK_INTERVAL + " ticks"
                ),
                false
        );
        return 1;
    }

    private static int pauseGrowth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MilestoneRecord owner = nearestGrowthOwner(source);
        if (owner == null) {
            return 0;
        }

        if (!GrowthManager.pause(source.getServer(), owner.id())) {
            source.sendFailure(Component.literal("That Milestone has no running growth plan to pause."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Paused growth for Milestone " + shortId(owner) + "."),
                true
        );
        return 1;
    }

    private static int resumeGrowth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MilestoneRecord owner = nearestGrowthOwner(source);
        if (owner == null) {
            return 0;
        }

        if (!GrowthManager.resume(source.getServer(), owner.id())) {
            source.sendFailure(Component.literal("That Milestone has no paused growth plan to resume."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Resumed growth for Milestone " + shortId(owner) + "."),
                true
        );
        return 1;
    }

    private static int retract(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                RESONANCE_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + RESONANCE_OWNER_RADIUS + " blocks."
            ));
            return 0;
        }

        RetractionManager.StartResult result = RetractionManager.start(
                level,
                owner.corePos(),
                owner.id()
        );
        if (!result.started()) {
            source.sendFailure(Component.literal(result.failureReason()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) + " began retracting " +
                                result.plannedPositions() + " Resonant blocks" +
                                (result.cancelledGrowth() ? " (active growth cancelled)" : "") + "."
                ),
                true
        );
        return result.plannedPositions();
    }

    private static int retractionStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                RESONANCE_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + RESONANCE_OWNER_RADIUS + " blocks."
            ));
            return 0;
        }

        RetractionPlan plan = RetractionManager.get(source.getServer(), owner.id()).orElse(null);
        if (plan == null) {
            source.sendSuccess(
                    () -> Component.literal("Milestone " + shortId(owner) + " is not retracting."),
                    false
            );
            return 1;
        }

        double percent = plan.totalPositions() == 0
                ? 100.0D
                : (plan.restoredPositions() * 100.0D / plan.totalPositions());

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) +
                                " retraction=" + String.format(Locale.ROOT, "%.1f", percent) + "%" +
                                " | faded=" + plan.fadedPositions() + "/" + plan.totalPositions() +
                                " | young=" + plan.youngPositions() +
                                " | restored=" + plan.restoredPositions() + "/" + plan.totalPositions() +
                                " | remaining=" + plan.remainingPositions()
                ),
                false
        );
        return 1;
    }


    private static int regenerationStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                RESONANCE_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + RESONANCE_OWNER_RADIUS + " blocks."
            ));
            return 0;
        }

        int pending = RegenerationManager.pendingCount(source.getServer(), owner.id());
        var next = RegenerationManager.ticksUntilNext(level, owner.id());
        String nextText = next.isPresent()
                ? String.format(Locale.ROOT, "%.1fs", next.get() / 20.0D)
                : "none";

        source.sendSuccess(
                () -> Component.literal(
                        "Milestone " + shortId(owner) +
                                " regeneration=" + (RegenerationManager.canRegenerate(owner.state()) ? "ENABLED" : "DISABLED") +
                                " | pending=" + pending +
                                " | next=" + nextText +
                                " | strip delay=" + (RegenerationManager.STRIP_REGROW_DELAY_TICKS / 20) + "s"
                ),
                false
        );
        return 1;
    }

    private static int resonanceInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos targetPos = lookedAtBlock(player);
        if (targetPos == null) {
            source.sendFailure(Component.literal("Look at a Resonant block within reach first."));
            return 0;
        }

        ResonanceRecord record = ResonanceManager.get(level, targetPos).orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal("That position has no saved Resonance record."));
            return 0;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(record.originalState().getBlock()).toString();
        source.sendSuccess(
                () -> Component.literal(
                        "Resonance @ " + formatPos(record.pos()) +
                                " | owner=" + record.milestoneId().toString().substring(0, 8) +
                                " | original=" + blockId +
                                " | state=" + record.originalState()
                ),
                false
        );
        return 1;
    }


    private static int taskView(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        MilestoneRecord record = nearestGameplayCore(source);
        if (record == null) {
            return 0;
        }

        record = MilestoneGameplayManager.ensureAssignment(source.getLevel(), record, player.getUUID());
        MilestoneGameplayManager.showStatus(player, record);
        return 1;
    }

    private static int taskReroll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        MilestoneRecord record = nearestGameplayCore(source);
        if (record == null) {
            return 0;
        }

        record = MilestoneGameplayManager.rerollPlayerTask(source.getLevel(), record, player);
        source.sendSuccess(() -> Component.literal("Rerolled your prototype Milestone task."), false);
        MilestoneGameplayManager.showStatus(player, record);
        return 1;
    }

    private static int contributorAddFake(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MilestoneRecord record = nearestGameplayCore(source);
        if (record == null) {
            return 0;
        }

        int amount;
        try {
            amount = IntegerArgumentType.getInteger(context, "amount");
        } catch (IllegalArgumentException ignored) {
            amount = 1;
        }

        MilestoneRecord updated = MilestoneGameplayManager.addFakeContributors(
                source.getLevel(),
                record,
                amount
        );
        int added = amount;
        source.sendSuccess(
                () -> Component.literal("Added " + added + " fake contributor(s). Total=" + updated.contributorCount()),
                true
        );
        return amount;
    }

    private static int contributorClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MilestoneRecord record = nearestGameplayCore(source);
        if (record == null) {
            return 0;
        }

        MilestoneRecord updated = MilestoneGameplayManager.clearContributors(source.getLevel(), record);
        source.sendSuccess(
                () -> Component.literal("Cleared contribution progress. State=" + updated.state().name()),
                true
        );
        return 1;
    }

    private static int gatewayRegister(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        PublicGatewayManager.RegistrationResult result =
                PublicGatewayManager.registerNearest(player, GATEWAY_SEARCH_RADIUS);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(result.message()), true);
        return 1;
    }

    private static int gatewayUnregister(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        PublicGatewayManager.RegistrationResult result =
                PublicGatewayManager.unregisterNearest(player, GATEWAY_SEARCH_RADIUS);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(result.message()), true);
        return 1;
    }

    private static int gatewayList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int allowed = PublicGatewayManager.allowedGatewayCount(source.getServer());
        var gateways = PublicGatewayManager.sorted(source.getServer());
        String allowedText = allowed == Integer.MAX_VALUE ? "unlimited (personal portals unlocked)" : Integer.toString(allowed);

        source.sendSuccess(
                () -> Component.literal("Public Nether gateways: " + gateways.size() + "/" + allowedText),
                false
        );

        for (PublicGatewayRecord gateway : gateways) {
            StringBuilder sides = new StringBuilder();
            for (int i = 0; i < gateway.sides().size(); i++) {
                var side = gateway.sides().get(i);
                if (i > 0) {
                    sides.append(" <-> ");
                }
                sides.append(side.dimension())
                        .append(" @ ")
                        .append(formatPos(side.min()));
            }
            String sideText = sides.length() == 0 ? "unbound" : sides.toString();
            source.sendSuccess(
                    () -> Component.literal("- " + PublicGatewayManager.shortId(gateway) + " | " + sideText),
                    false
            );
        }
        return 1;
    }

    private static int gatewayClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int before = PublicGatewayManager.registeredGatewayCount(source.getServer());
        PublicGatewayManager.clear(source.getServer());
        source.sendSuccess(
                () -> Component.literal("Cleared " + before + " public Nether gateway registration(s)."),
                true
        );
        return before;
    }

    private static MilestoneRecord nearestGameplayCore(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        MilestoneRecord record = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                SEARCH_RADIUS
        ).orElse(null);

        if (record == null) {
            source.sendFailure(Component.literal("No registered Milestone Core within " + SEARCH_RADIUS + " blocks."));
        }
        return record;
    }

    private static MilestoneRecord nearestGrowthOwner(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                origin,
                TENDRIL_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + TENDRIL_OWNER_RADIUS + " blocks."
            ));
        }
        return owner;
    }

    private static MilestoneRecord nearestOwner(
            CommandSourceStack source,
            ServerLevel level,
            BlockPos targetPos
    ) {
        MilestoneRecord owner = MilestoneManager.nearest(
                source.getServer(),
                level,
                targetPos,
                RESONANCE_OWNER_RADIUS
        ).orElse(null);

        if (owner == null) {
            source.sendFailure(Component.literal(
                    "No registered Milestone Core within " + RESONANCE_OWNER_RADIUS + " blocks of that position."
            ));
        }
        return owner;
    }

    private static BlockPos lookedAtBlock(ServerPlayer player) {
        double reach = Math.max(5.0D, player.blockInteractionRange());
        HitResult hit = player.pick(reach, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) hit).getBlockPos();
    }

    private static String shortId(MilestoneRecord record) {
        return record.id().toString().substring(0, 8);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
