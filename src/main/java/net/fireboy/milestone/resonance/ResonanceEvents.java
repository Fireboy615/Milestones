package net.fireboy.milestone.resonance;

import net.fireboy.milestone.registry.ModBlocks;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class ResonanceEvents {
    private ResonanceEvents() {
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getState().is(ModBlocks.RESONANT_BLOCK.get())
                || event.getState().is(ModBlocks.YOUNG_RESONANT_BLOCK.get())) {
            // Resonance is a coating, not a second mineable copy of the underlying block.
            // It must be stripped with an axe first.
            event.setCanceled(true);
        }
    }
}
