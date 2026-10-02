package net.fireboy.milestone.task;

import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;

/** Server event bridge for task types that complete out in the world. */
public final class MilestoneTaskEvents {
    private MilestoneTaskEvents() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MilestoneGameplayManager.onPlayerKill(player, event.getEntity().getType());
    }

    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MilestoneGameplayManager.onAdvancementEarned(player, event.getAdvancement().id());
    }
}
