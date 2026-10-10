package net.fireboy.milestone.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Simple console-friendly companion to the admin GUI. */
public final class MilestoneConfigCommands {
    private MilestoneConfigCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestoneconfig")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    boolean valid = MilestoneContentConfig.reloadServer();
                    if (valid) {
                        MilestoneFeatureLocks.applyRecipes(ctx.getSource().getServer(),false,true);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "Milestone tasks and Core profiles reloaded. Existing sites remain where they are."), true);
                    } else ctx.getSource().sendFailure(Component.literal(
                            "Config invalid. Prior valid settings retained; check server log."));
                    return valid ? 1 : 0;
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    var cores = MilestoneContentConfig.server().cores();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Configured Core profiles: " + cores.size()), false);
                    for (var core : cores.values()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "Tier " + core.tier() + ": " + core.name() + " | "
                                        + core.taskMode() + " | difficulty " + core.difficulty()
                                        + " | dimension " + core.dimension()), false);
                    }
                    return cores.size();
                }))
                .then(Commands.literal("show")
                        .then(Commands.argument("tier", IntegerArgumentType.integer(0, 5))
                                .executes(ctx -> {
                                    int tier = IntegerArgumentType.getInteger(ctx, "tier");
                                    var core = MilestoneContentConfig.server().core(tier);
                                    if (core == null) {
                                        ctx.getSource().sendFailure(Component.literal("Tier not configured"));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Tier " + tier + " | " + core.name()
                                                    + " | tasks=" + core.taskMode()
                                                    + " (" + core.taskIds().size() + " manual IDs)"
                                                    + " | prereqs=" + core.requiresCompletedCores()
                                                    + " | biome allow=" + core.allowedBiomes()
                                                    + " | biome deny=" + core.blockedBiomes()
                                                    + " | reward XP=" + core.rewardXp()), false);
                                    return 1;
                                }))));
    }
}
