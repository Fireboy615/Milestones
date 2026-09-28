package net.fireboy.milestone.client.render;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public final class MilestoneCoreItemExtensions implements IClientItemExtensions {
    private final MilestoneCoreItemRenderer renderer = new MilestoneCoreItemRenderer();

    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return renderer;
    }
}
