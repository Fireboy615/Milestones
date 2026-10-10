package net.fireboy.milestone.client;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.client.render.MissionStationRenderer;
import net.fireboy.milestone.client.render.MilestoneCoreItemExtensions;
import net.fireboy.milestone.client.screen.MilestoneScreen;
import net.fireboy.milestone.registry.ModBlockEntities;
import net.fireboy.milestone.registry.ModItems;
import net.fireboy.milestone.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = Milestone.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MilestoneClientEvents {
    private MilestoneClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.MISSION_STATION.get(),
                MissionStationRenderer::new
        );
    }

    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(
                new MilestoneCoreItemExtensions(),
                ModItems.MISSION_STATION.get()
        );
    }
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MILESTONE.get(), MilestoneScreen::new);
    }

}
