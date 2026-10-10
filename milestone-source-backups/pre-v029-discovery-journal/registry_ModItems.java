package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
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

    private ModItems() { }
}
