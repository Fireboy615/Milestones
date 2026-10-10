package net.fireboy.milestone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fireboy.milestone.blockentity.MissionStationBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Reuse the existing Blockbench geometry for the Mission Station. */
public final class MissionStationRenderer implements BlockEntityRenderer<MissionStationBlockEntity> {
    public MissionStationRenderer(BlockEntityRendererProvider.Context context) { }

    @Override
    public void render(MissionStationBlockEntity entity, float partialTick,
                       PoseStack stack, MultiBufferSource buffers,
                       int light, int overlay) {
        MilestoneCoreGeometry.render(stack, buffers, light, overlay);
    }
}
