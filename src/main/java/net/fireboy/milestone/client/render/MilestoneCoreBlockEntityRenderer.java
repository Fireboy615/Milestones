package net.fireboy.milestone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class MilestoneCoreBlockEntityRenderer implements BlockEntityRenderer<MilestoneCoreBlockEntity> {
    public MilestoneCoreBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            MilestoneCoreBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        MilestoneCoreGeometry.render(poseStack, bufferSource, packedLight, packedOverlay);
    }
}
