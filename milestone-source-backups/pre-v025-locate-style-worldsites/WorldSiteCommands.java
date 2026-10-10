package net.fireboy.milestone.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator-only worldgen status and deliberate, safe teleport commands. */
public final class WorldSiteCommands {
    private WorldSiteCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestone")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("worldsites")
                        .executes(WorldSiteCommands::show)
                        .then(Commands.literal("tp")
                                .then(Commands.literal("exchange")
                                        .executes(ctx -> teleport(ctx, "exchange", 0)))
                                .then(Commands.literal("mission")
                                        .executes(ctx -> teleport(ctx, "mission", 0)))
                                .then(Commands.literal("core")
                                        .then(Commands.argument("tier", IntegerArgumentType.integer(1, 6))
                                                .executes(ctx -> teleport(ctx, "core",
                                                        IntegerArgumentType.getInteger(ctx, "tier"))))))));
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        WorldSiteSavedData data = WorldSiteManager.data(context.getSource().getServer());
        MutableComponent stations = Component.literal("Spawn Exchange: ");
        stations.append(data.exchange().map(pos -> link(format(pos),
                tpCommand(pos))).orElseGet(() -> Component.literal("not placed yet")));
        stations.append(Component.literal(" | Mission Station: "));
        stations.append(data.mission().map(pos -> link(format(pos),
                tpCommand(pos))).orElseGet(() -> Component.literal("not placed yet")));
        context.getSource().sendSuccess(() -> stations, false);

        for (WorldSiteSavedData.CorePlan core : data.cores()) {
            int tier = core.tier() + 1;
            MutableComponent row = Component.literal("Core tier " + tier + ": " + core.type().name()
                    + " | " + (core.placed() ? "PLACED " : "RESERVED "));
            String coords = core.placed() ? format(core.placedPos())
                    : "x=" + core.x() + " z=" + core.z();
            row.append(link(coords, core.placed()
                    ? tpCommand(core.placedPos()) : "/milestone worldsites tp core " + tier));
            if (!core.placed()) row.append(Component.literal(" (not generated yet; surface teleport)"));
            context.getSource().sendSuccess(() -> row, false);
        }
        return 1;
    }

    private static MutableComponent link(String label, String command) {
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to fill in teleport command; press Enter to run"))));
    }

    /** Put the player directly one block above the site block, centered on it. */
    private static String tpCommand(BlockPos pos) {
        return "/tp @s " + (pos.getX() + 0.5D) + " " + (pos.getY() + 1) + " " + (pos.getZ() + 0.5D);
    }

    private static int teleport(CommandContext<CommandSourceStack> context, String kind, int tier) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        WorldSiteSavedData data = WorldSiteManager.data(source.getServer());
        BlockPos site = null;
        boolean reserved = false;
        if (kind.equals("exchange")) site = data.exchange().orElse(null);
        else if (kind.equals("mission")) site = data.mission().orElse(null);
        else {
            for (WorldSiteSavedData.CorePlan plan : data.cores()) {
                if (plan.tier() == tier - 1) {
                    reserved = !plan.placed();
                    site = reserved ? new BlockPos(plan.x(), level.getSeaLevel(), plan.z())
                            : plan.placedPos();
                    break;
                }
            }
        }
        if (site == null) {
            source.sendFailure(Component.literal("That site isn't planned/placed yet."));
            return 0;
        }
        // The operator requested exact placement, even in obstructed blocks.
        // Reserved Cores have no Y yet, so use the terrain surface at their X/Z.
        BlockPos destination = reserved
                ? new BlockPos(site.getX(), level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.getX(), site.getZ()), site.getZ())
                : site.above();
        player.teleportTo(level, destination.getX() + 0.5D, destination.getY(),
                destination.getZ() + 0.5D, player.getYRot(), player.getXRot());
        final String message = "Teleported to " + kind
                + (kind.equals("core") ? " tier " + tier : "")
                + " at " + format(destination)
                + (reserved ? " (surface of reserved site)" : "");
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
