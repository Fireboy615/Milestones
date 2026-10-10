package net.fireboy.milestone.worldgen;

import net.fireboy.milestone.world.WorldSiteSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Operator-only world-site overview, with /locate-style green coordinates.
 * Clicking coordinates suggests vanilla /tp @s x y z; it never executes a command.
 * An unplaced Core has no actual Y, so links to a high-altitude staging position
 * instead of using an unloaded chunk's possibly invalid heightmap.
 */
public final class WorldSiteCommands {
    private WorldSiteCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestone")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("worldsites")
                        .executes(context -> {
                            show(context.getSource().getServer().overworld(),
                                    context.getSource());
                            return 1;
                        })));
    }

    private static void show(ServerLevel level,
                             net.minecraft.commands.CommandSourceStack source) {
        WorldSiteSavedData data = WorldSiteManager.data(level.getServer());

        MutableComponent stations = Component.literal("Exchange: ");
        stations.append(data.exchange()
                .map(pos -> tpLink(pos.above()))
                .orElseGet(() -> Component.literal("not placed yet")));
        stations.append(Component.literal(" | Mission Station: "));
        stations.append(data.mission()
                .map(pos -> tpLink(pos.above()))
                .orElseGet(() -> Component.literal("not placed yet")));
        source.sendSuccess(() -> stations, false);

        // A reserved X/Z has no Y until actual generated terrain is examined.
        // This is a clear, consistent sky staging height, NOT a guessed Core Y.
        final int stagingY = Math.min(level.getMaxBuildHeight() - 2,
                Math.max(180, level.getSeaLevel() + 100));

        for (WorldSiteSavedData.CorePlan core : data.cores()) {
            boolean placed = core.placed() && core.placedPos() != null;
            BlockPos destination = placed ? core.placedPos().above()
                    : new BlockPos(core.x(), stagingY, core.z());

            MutableComponent row = Component.literal(
                    "Core tier " + (core.tier() + 1) + ": " + core.type().name()
                            + " | " + (placed ? "PLACED " : "RESERVED "));
            row.append(tpLink(destination));
            if (!placed) {
                row.append(Component.literal(" (Y is sky staging, Core not generated)")
                        .withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> row, false);
        }
    }

    private static MutableComponent tpLink(BlockPos destination) {
        String command = "/tp @s "
                + (destination.getX() + 0.5D) + " "
                + destination.getY() + " "
                + (destination.getZ() + 0.5D);
        String coordinates = "["
                + destination.getX() + ", "
                + destination.getY() + ", "
                + destination.getZ() + "]";
        return Component.literal(coordinates).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to fill chat: " + command + " (press Enter)"))));
    }
}
