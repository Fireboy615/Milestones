package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.block.MilestoneCoreBlock;
import net.fireboy.milestone.block.ResonantBlock;
import net.fireboy.milestone.block.YoungResonantBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Milestone.MOD_ID);

    public static final DeferredBlock<MilestoneCoreBlock> MILESTONE_CORE =
            BLOCKS.registerBlock(
                    "milestone_core",
                    MilestoneCoreBlock::new,
                    BlockBehaviour.Properties.of()
                            .destroyTime(-1.0F)
                            .explosionResistance(3_600_000.0F)
                            .sound(SoundType.DEEPSLATE_TILES)
                            .pushReaction(PushReaction.BLOCK)
                            .noOcclusion()
            );

    /** Fresh propagation layer. Sparse texture + glow-lichen-like luminance. */
    public static final DeferredBlock<YoungResonantBlock> YOUNG_RESONANT_BLOCK =
            BLOCKS.registerBlock(
                    "young_resonant_block",
                    YoungResonantBlock::new,
                    BlockBehaviour.Properties.of()
                            .destroyTime(-1.0F)
                            .explosionResistance(3_600_000.0F)
                            .sound(SoundType.AMETHYST)
                            .pushReaction(PushReaction.BLOCK)
                            .lightLevel(state -> 7)
                            .noOcclusion()
            );

    public static final DeferredBlock<ResonantBlock> RESONANT_BLOCK =
            BLOCKS.registerBlock(
                    "resonant_block",
                    ResonantBlock::new,
                    BlockBehaviour.Properties.of()
                            .destroyTime(-1.0F)
                            .explosionResistance(3_600_000.0F)
                            .sound(SoundType.AMETHYST)
                            .pushReaction(PushReaction.BLOCK)
                            .lightLevel(state -> 4)
            );

    private ModBlocks() {
    }
}
