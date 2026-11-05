package net.fireboy.milestone.milestone;

import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.shop.MilestonePassiveIncome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;

/** Server-wide rewards triggered by accepted contributor thresholds. */
public final class MilestoneStageRewards {
    private MilestoneStageRewards() {}

    /** Recipes are gated by MilestoneFeatureLocks, dimension flags are applied
     * on threshold, and hourly pay adjusts the server-wide passive-income rate.
     * Legacy achievement rewards are retained in config but have no effect. */
    public static void award(MinecraftServer server, MilestoneRecord core,
                             MilestoneContentConfig.RewardStage stage) {
        for (int index = 0; index < stage.rewards().size(); index++) {
            var reward = stage.rewards().get(index);
            if (reward.type().equals("hourly_pay")) {
                String key = core.id() + ":" + stage.contributors() + ":" + index;
                MilestonePassiveIncome.get(server).addHourlyPayOnce(key, reward.hourlyPay());
            }
            if (reward.type().equals("dimension")) {
                for (String id : reward.ids()) {
                    try {
                        // Preserve older save configurations that used the
                        // Nether flag names under the "dimension" reward.
                        MilestoneProgressionManager.unlock(server, MilestoneUnlock.valueOf(id));
                    } catch (IllegalArgumentException notLegacy) {
                        ResourceLocation dimension = ResourceLocation.tryParse(id);
                        if (dimension != null)
                            MilestoneProgressionManager.unlockDimension(server, dimension);
                    }
                }
            }
            if (reward.type().equals("portal")) {
                ResourceLocation dimension = ResourceLocation.tryParse(reward.dimension());
                ResourceLocation portal = ResourceLocation.tryParse(reward.portalType());
                if (dimension != null && portal != null && reward.portalAccess() != null)
                    MilestoneProgressionManager.unlockPortal(server, dimension, portal,
                            reward.portalAccess());
            }
        }
    }

    // No advancement rewards: players earn advancements by completing tasks.
}
