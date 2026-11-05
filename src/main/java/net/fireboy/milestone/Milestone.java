package net.fireboy.milestone;

import net.fireboy.milestone.command.MilestoneCommands;
import net.fireboy.milestone.command.MilestoneShopCommands;
import net.fireboy.milestone.command.MilestoneConfigCommands;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.command.MilestoneJournalCommands;
import net.fireboy.milestone.milestone.MilestoneJournal;
import net.fireboy.milestone.network.CreateSchematicNetwork;
import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.fireboy.milestone.network.MilestoneShopNetwork;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.fireboy.milestone.network.RecipeLockNetwork;
import net.fireboy.milestone.network.FoxyNetwork;
import net.fireboy.milestone.easteregg.FoxyJumpscare;
import net.fireboy.milestone.loot.MilestoneLootHistory;
import net.fireboy.milestone.loot.MilestoneLootTargeting;
import net.fireboy.milestone.gateway.NetherPortalEvents;
import net.fireboy.milestone.registry.ModBlockEntities;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.registry.ModItems;
import net.fireboy.milestone.registry.ModMenus;
import net.fireboy.milestone.registry.ModSounds;
import net.fireboy.milestone.resonance.GrowthManager;
import net.fireboy.milestone.resonance.ResonanceEvents;
import net.fireboy.milestone.resonance.RegenerationManager;
import net.fireboy.milestone.resonance.RetractionManager;
import net.fireboy.milestone.task.MilestoneTaskEvents;
import net.fireboy.milestone.shop.MilestoneShopUnlocks;
import net.fireboy.milestone.shop.MilestoneShopPlayerIntel;
import net.fireboy.milestone.shop.MilestoneShopSchematicRewards;
import net.fireboy.milestone.resonance.GlowLichenManager;
import net.fireboy.milestone.worldgen.WorldSiteManager;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.fireboy.milestone.worldgen.WorldSiteCommands;
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
        ModMenus.MENUS.register(modBus);
        ModSounds.SOUNDS.register(modBus);

        modBus.addListener(this::addCreativeTabContents);
        modBus.addListener(CreateSchematicNetwork::register);
        modBus.addListener(MilestoneConfigNetwork::register);
        modBus.addListener(MilestoneShopNetwork::register);
        modBus.addListener(MilestoneLootNetwork::register);
        modBus.addListener(RecipeLockNetwork::register);
        modBus.addListener(FoxyNetwork::register);
        NeoForge.EVENT_BUS.addListener(MilestoneContentConfig::onServerStarting);
        NeoForge.EVENT_BUS.addListener(MilestoneFeatureLocks::onServerStarting);
        NeoForge.EVENT_BUS.addListener(MilestoneFeatureLocks::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(MilestoneFeatureLocks::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(MilestoneFeatureLocks::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(MilestoneCommands::register);
        NeoForge.EVENT_BUS.addListener(MilestoneShopCommands::register);
        NeoForge.EVENT_BUS.addListener(MilestoneConfigCommands::register);
        NeoForge.EVENT_BUS.addListener(MilestoneJournalCommands::register);
        NeoForge.EVENT_BUS.addListener(MilestoneJournal::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(WorldSiteCommands::register);
        NeoForge.EVENT_BUS.addListener(WorldSiteManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(FoxyJumpscare::onServerTick);
        NeoForge.EVENT_BUS.addListener(ResonanceEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(GrowthManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RetractionManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RegenerationManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(GlowLichenManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(MilestoneTaskEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(MilestoneTaskEvents::onAdvancementEarned);
        NeoForge.EVENT_BUS.addListener(MilestoneShopUnlocks::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(MilestoneShopUnlocks::onAdvancement);
        NeoForge.EVENT_BUS.addListener(MilestoneShopUnlocks::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(MilestoneShopPlayerIntel::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(MilestoneShopPlayerIntel::onLogout);
        NeoForge.EVENT_BUS.addListener(MilestoneShopSchematicRewards::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(MilestoneShopSchematicRewards::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(MilestoneShopSchematicRewards::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(MilestoneLootHistory::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(MilestoneLootTargeting::onRightClick);
        NeoForge.EVENT_BUS.addListener(NetherPortalEvents::onPortalSpawn);
        NeoForge.EVENT_BUS.addListener(NetherPortalEvents::onTravelToDimension);
        NeoForge.EVENT_BUS.addListener(NetherPortalEvents::onPlayerChangedDimension);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.MILESTONE_CORE.get());
            event.accept(ModItems.RESONANT_EXCHANGE.get());
            event.accept(ModItems.MISSION_STATION.get());
            event.accept(ModItems.MILESTONE_JOURNAL.get());
            // The legacy placeholder remains registered only to migrate old-world items.
        }
    }
}
