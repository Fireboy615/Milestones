package net.fireboy.milestone.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.world.WorldSiteSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Operator-only site locator, scoped to the command source's current dimension.
 * Placed cores have exact positions. Reserved sites have X/Z only, never a fake Y.
 * Clicking a placed core runs a validated teleport to a safe position at the core.
 */
public final class WorldSiteCommands {
    private WorldSiteCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestone")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("worldsites")
                        .executes(context -> show(context.getSource()))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("core", IntegerArgumentType.integer(0, 31))
                                        .executes(context -> teleportToCore(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "core")))))));
    }

    private static int show(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        WorldSiteSavedData data = WorldSiteManager.data(level.getServer());
        String dimension = level.dimension().location().toString();

        source.sendSuccess(() -> Component.literal("Milestone sites in " + dimension + ":")
                .withStyle(ChatFormatting.AQUA), false);

        // The public Station and Exchange are generated only in the Overworld.
        if (level.dimension().equals(Level.OVERWORLD)) {
            MutableComponent stations = Component.literal("Exchange: ");
            stations.append(data.exchange()
                    .map(pos -> stationLink(pos.above()))
                    .orElseGet(() -> Component.literal("not placed yet")));
            stations.append(Component.literal(" | Mission Station: "));
            stations.append(data.mission()
                    .map(pos -> stationLink(pos.above()))
                    .orElseGet(() -> Component.literal("not placed yet")));
            source.sendSuccess(() -> stations, false);
        }

        int found = 0;
        for (WorldSiteSavedData.CorePlan core : data.cores()) {
            if (!dimension.equals(core.dimension())) continue;
            found++;
            var settings = MilestoneContentConfig.server().core(core.tier());
            String name = settings == null ? core.type().name() : settings.name();
            boolean placed = core.placed() && core.placedPos() != null;
            MutableComponent row = Component.literal(
                    "Core " + (core.tier() + 1) + " (" + name + ") | "
                            + (placed ? "PLACED " : "RESERVED "));
            if (placed) {
                // Coordinates display the real core block; teleport lands safely at it.
                row.append(coreLink(core.placedPos(), core.tier()));
            } else {
                row.append(Component.literal("X=" + core.x() + ", Z=" + core.z()
                        + " (not generated; Y unknown)")
                        .withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> row, false);
        }

        if (found == 0) {
            source.sendSuccess(() -> Component.literal("No Milestone cores configured or reserved in this dimension.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return found;
    }

    private static int teleportToCore(CommandSourceStack source, int tier) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can teleport to a Milestone Core."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        WorldSiteSavedData.CorePlan core = WorldSiteManager.data(level.getServer()).cores()
                .stream().filter(plan -> plan.tier() == tier).findFirst().orElse(null);
        if (core == null) {
            source.sendFailure(Component.literal("No such Milestone Core."));
            return 0;
        }
        if (!core.dimension().equals(level.dimension().location().toString())) {
            source.sendFailure(Component.literal("Core " + (tier + 1) + " belongs to "
                    + core.dimension() + ". Enter that dimension and use /milestone worldsites again."));
            return 0;
        }
        if (!core.placed() || core.placedPos() == null) {
            source.sendFailure(Component.literal("Core " + (tier + 1)
                    + " hasn't generated yet. Its reserved X/Z is " + core.x() + ", " + core.z() + "."));
            return 0;
        }
        BlockPos corePos = core.placedPos();
        // Load the saved site chunk before trusting its persisted block position.
        if (!level.getBlockState(corePos).is(ModBlocks.MILESTONE_CORE.get())) {
            source.sendFailure(Component.literal("The Core is no longer at its saved coordinates. Teleport cancelled."));
            return 0;
        }

        BlockPos landing = safeLandingNearCore(level, corePos);
        if (landing == null) {
            source.sendFailure(Component.literal("No clear landing spot next to this Core. Teleport cancelled."));
            return 0;
        }

        // Use vanilla tp in the current dimension; no fake elevation or cross-dimension targeting.
        String command = "tp @s " + (landing.getX() + 0.5D) + " " + landing.getY()
                + " " + (landing.getZ() + 0.5D);
        source.getServer().getCommands().performPrefixedCommand(source, command);
        return 1;
    }

    private static BlockPos safeLandingNearCore(ServerLevel level, BlockPos core) {
        // Directly above the Core first; if growth blocks it, try the chamber floor beside it.
        BlockPos[] candidates = { core.above(), core.north(), core.south(), core.east(), core.west() };
        for (BlockPos feet : candidates) {
            if (feet.getY() <= level.getMinBuildHeight()
                    || feet.getY() + 2 >= level.getMaxBuildHeight()) continue;
            BlockPos head = feet.above();
            BlockPos floor = feet.below();
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || !level.getFluidState(feet).isEmpty()
                    || !level.getFluidState(head).isEmpty()
                    || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    private static MutableComponent coreLink(BlockPos core, int tier) {
        String coordinates = "[" + core.getX() + ", " + core.getY() + ", " + core.getZ() + "]";
        String command = "/milestone worldsites tp " + tier;
        return Component.literal(coordinates).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to teleport to this Core's chamber"))));
    }

    /** Station locations remain suggested vanilla commands, not automatic teleports. */
    private static MutableComponent stationLink(BlockPos destination) {
        String command = "/tp @s " + (destination.getX() + 0.5D) + " "
                + destination.getY() + " " + (destination.getZ() + 0.5D);
        String coordinates = "[" + destination.getX() + ", " + destination.getY()
                + ", " + destination.getZ() + "]";
        return Component.literal(coordinates).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to fill chat: " + command + " (press Enter)"))));
    }
}
