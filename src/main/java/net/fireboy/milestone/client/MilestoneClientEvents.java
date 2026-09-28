package net.fireboy.milestone.client;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.client.render.MilestoneCoreBlockEntityRenderer;
import net.fireboy.milestone.client.render.MilestoneCoreItemExtensions;
import net.fireboy.milestone.registry.ModBlockEntities;
import net.fireboy.milestone.registry.ModItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = Milestone.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MilestoneClientEvents {
    private MilestoneClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.MILESTONE_CORE.get(),
                MilestoneCoreBlockEntityRenderer::new
        );
    }

    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(
                new MilestoneCoreItemExtensions(),
                ModItems.MILESTONE_CORE.get()
        );
    }
}
