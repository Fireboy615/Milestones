package net.fireboy.milestone.block;

import net.fireboy.milestone.Milestone;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Milestone.MOD_ID);

    public static final DeferredBlock<MilestoneCoreBlock> CLOAKING_CORE =
            BLOCKS.register(
                    "cloaking_core",
                    () -> new MilestoneCoreBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F)
                                    .requiresCorrectToolForDrops()
                                    .noOcclusion()
                    )
            );
}
