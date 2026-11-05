package net.fireboy.milestone.client;

import net.fireboy.milestone.Milestone;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Never carry a previous server's JEI progression indicators into another world. */
@EventBusSubscriber(modid = Milestone.MOD_ID, value = Dist.CLIENT)
public final class RecipeLockClientEvents {
    private RecipeLockClientEvents() { }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RecipeLockClientState.clear();
    }
}
