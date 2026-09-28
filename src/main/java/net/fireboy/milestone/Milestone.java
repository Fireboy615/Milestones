package net.fireboy.milestone;

import net.fireboy.milestone.command.MilestoneCommands;
import net.fireboy.milestone.registry.ModBlockEntities;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.registry.ModItems;
import net.fireboy.milestone.resonance.GrowthManager;
import net.fireboy.milestone.resonance.ResonanceEvents;
import net.fireboy.milestone.resonance.RegenerationManager;
import net.fireboy.milestone.resonance.RetractionManager;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(Milestone.MOD_ID)
public final class Milestone {
    public static final String MOD_ID = "milestone";

    public Milestone(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreativeTabContents);
        NeoForge.EVENT_BUS.addListener(MilestoneCommands::register);
        NeoForge.EVENT_BUS.addListener(ResonanceEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(GrowthManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RetractionManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RegenerationManager::onServerTick);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.MILESTONE_CORE.get());
        }
    }
}
