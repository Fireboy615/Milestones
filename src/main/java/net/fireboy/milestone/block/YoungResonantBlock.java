package net.fireboy.milestone.block;

import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.resonance.ResonanceManager;
import net.fireboy.milestone.resonance.ResonanceRecord;
import net.fireboy.milestone.resonance.RetractionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fresh Resonance. It uses the sparse/cutout texture, glows softly, and then
 * hardens into the normal Resonant block after a short server-side delay.
 */
public class YoungResonantBlock extends ResonantBlock {
    /** Two seconds: long enough to see the growth front move, short enough to stay lively. */
    public static final int MATURATION_TICKS = 40;

    public YoungResonantBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // The scheduled tick may fire after stripping/retraction or after another mod
        // has changed the block. In that case, leave the world alone.
        if (!level.getBlockState(pos).is(ModBlocks.YOUNG_RESONANT_BLOCK.get())) {
            return;
        }

        // A visible Resonant block without a saved record is stale/corrupt state. Do
        // not turn it into permanent Resonance because we would no longer know what
        // terrain should be restored later.
        ResonanceRecord record = ResonanceManager.get(level, pos).orElse(null);
        if (record == null) {
            return;
        }

        // During regression the young texture is the withering/fading stage. Any old
        // maturation tick that was scheduled while this voxel was growing must not
        // harden it back into full Resonance behind the retraction front.
        if (RetractionManager.get(level.getServer(), record.milestoneId()).isPresent()) {
            return;
        }

        if (level.setBlock(
                pos,
                ModBlocks.RESONANT_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        )) {
            // Mature growth can sprout sparse vanilla Glow Lichen on exposed faces.
            // The lichen is tracked separately so retraction can remove only what
            // this Milestone created.
            net.fireboy.milestone.resonance.GlowLichenManager.tryGrowFrom(
                    level,
                    pos,
                    record.milestoneId()
            );
        }
    }
}
