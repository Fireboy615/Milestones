package net.fireboy.milestone.command;

import net.fireboy.milestone.milestone.MilestoneJournal;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Public journal access; existing /milestone debug commands remain operator-only. */
public final class MilestoneJournalCommands {
    private MilestoneJournalCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestonejournal")
                .executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player == null) {
                        context.getSource().sendFailure(Component.literal("Players only."));
                        return 0;
                    }
                    MilestoneJournal.show(player);
                    return 1;
                }));
    }
}
