package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.item.DiscoverySchematicItem;
import net.fireboy.milestone.item.MilestoneJournalItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Milestone.MOD_ID);

    public static final DeferredItem<BlockItem> MILESTONE_CORE =
            ITEMS.registerSimpleBlockItem(ModBlocks.MILESTONE_CORE);
    public static final DeferredItem<BlockItem> RESONANT_EXCHANGE =
            ITEMS.registerSimpleBlockItem(ModBlocks.RESONANT_EXCHANGE);
    public static final DeferredItem<BlockItem> MISSION_STATION =
            ITEMS.registerSimpleBlockItem(ModBlocks.MISSION_STATION);

    public static final DeferredItem<MilestoneJournalItem> MILESTONE_JOURNAL =
            ITEMS.registerItem("milestone_journal", MilestoneJournalItem::new,
                    new Item.Properties().stacksTo(1));
    /** Legacy v0.29 paper placeholder; kept so old saves can upgrade it safely. */
    public static final DeferredItem<DiscoverySchematicItem> DISCOVERY_SCHEMATIC =
            ITEMS.registerItem("discovery_schematic", DiscoverySchematicItem::new,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON));

    private ModItems() { }
}
