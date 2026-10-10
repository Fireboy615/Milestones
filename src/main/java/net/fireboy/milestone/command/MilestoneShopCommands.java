package net.fireboy.milestone.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fireboy.milestone.shop.MilestoneShopAccounts;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator testing controls; real players earn shop credits from contributions. */
public final class MilestoneShopCommands {
    private MilestoneShopCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("milestoneshop")
            .then(Commands.literal("balance").executes(ctx -> {
                var player=ctx.getSource().getPlayerOrException();
                int amount=MilestoneShopAccounts.get(player.getServer()).balance(player.getUUID());
                ctx.getSource().sendSuccess(()->Component.literal(
                        "Milestone Shop: "+amount+" credits."),false);
                return amount;
            }))
            .then(Commands.literal("grant").requires(source->source.hasPermission(2))
                .then(Commands.argument("player",EntityArgument.player())
                    .then(Commands.argument("amount",IntegerArgumentType.integer(1,100000))
                        .executes(ctx -> {
                            var player=EntityArgument.getPlayer(ctx,"player");
                            int amount=IntegerArgumentType.getInteger(ctx,"amount");
                            var accounts=MilestoneShopAccounts.get(ctx.getSource().getServer());
                            accounts.earn(player.getUUID(),amount);
                            ctx.getSource().sendSuccess(()->Component.literal(
                                    "Granted "+amount+" shop credits to "
                                        +player.getScoreboardName()
                                        +" (balance "+accounts.balance(player.getUUID())+")."),true);
                            return 1;
                        })))));
    }
}
