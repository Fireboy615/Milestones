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

    private ModItems() {
    }
}
