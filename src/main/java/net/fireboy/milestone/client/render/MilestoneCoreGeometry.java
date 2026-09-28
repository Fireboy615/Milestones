package net.fireboy.milestone.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fireboy.milestone.Milestone;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Runtime renderer for the exact geometry from "Milestone Core.bbmodel".
 *
 * Vanilla block-model JSON only permits element rotations of
 * -45, -22.5, 0, 22.5 and 45 degrees. This renderer is intentionally
 * independent of that limitation, so the original +/-15 and +/-105 degree
 * rotations can be preserved.
 */
public final class MilestoneCoreGeometry {
    private static final float PIXEL = 1.0F / 16.0F;

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Milestone.MOD_ID,
            "textures/block/milestone_core.png"
    );

    private MilestoneCoreGeometry() {
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        // Main base.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                1, 0, 1, 15, 7, 15,
                1, 0, 1, 0, 0, 0,
                uv(1, 0, 15, 7),
                uv(1, 0, 15, 7),
                uv(1, 0, 15, 7),
                uv(1, 0, 15, 7),
                uv(1, 1, 15, 15),
                uv(2, 1, 16, 8)
        );

        // West arm: exact -15 degree Z rotation from the Blockbench model.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                1, 7, 3, 3, 9, 13,
                1, 7, 3, 0, 0, -15,
                uv(0, 0, 2, 10),
                uv(0, 0, 2, 10),
                uv(0, 0, 2, 10),
                uv(0, 0, 2, 10),
                uv(0, 0, 2, 10),
                uv(0, 0, 2, 10)
        );

        // East arm: exact 105 degree Z rotation.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                15, 7, 3, 17, 9, 13,
                15, 7, 3, 0, 0, 105,
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(3, 6, 13, 8),
                uv(0, 3, 2, 13)
        );

        // North arm: exact 15 degree X rotation.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                3, 7, 1, 13, 9, 3,
                3, 7, 1, 15, 0, 0,
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3)
        );

        // South arm: exact -105 degree X rotation.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                3, 7, 15, 13, 9, 17,
                3, 7, 15, -105, 0, 0,
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3),
                uv(0, 1, 10, 3)
        );

        // Top plate.
        renderElement(
                poseStack, consumer, packedLight, packedOverlay,
                4, 7, 4, 12, 8, 12,
                4, 7, 4, 0, 0, 0,
                uv(0, 0, 8, 8),
                uv(0, 0, 8, 8),
                uv(0, 0, 8, 8),
                uv(0, 0, 8, 8),
                uv(0, 0, 16, 16),
                uv(0, 0, 8, 8)
        );
    }

    private static Uv uv(float u0, float v0, float u1, float v1) {
        return new Uv(u0 / 16.0F, v0 / 16.0F, u1 / 16.0F, v1 / 16.0F);
    }

    private static void renderElement(
            PoseStack poseStack,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float fromX,
            float fromY,
            float fromZ,
            float toX,
            float toY,
            float toZ,
            float originX,
            float originY,
            float originZ,
            float rotateX,
            float rotateY,
            float rotateZ,
            Uv north,
            Uv east,
            Uv south,
            Uv west,
            Uv up,
            Uv down
    ) {
        poseStack.pushPose();

        poseStack.translate(originX * PIXEL, originY * PIXEL, originZ * PIXEL);
        if (rotateX != 0) {
            poseStack.mulPose(Axis.XP.rotationDegrees(rotateX));
        }
        if (rotateY != 0) {
            poseStack.mulPose(Axis.YP.rotationDegrees(rotateY));
        }
        if (rotateZ != 0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(rotateZ));
        }
        poseStack.translate(-originX * PIXEL, -originY * PIXEL, -originZ * PIXEL);

        float x0 = fromX * PIXEL;
        float y0 = fromY * PIXEL;
        float z0 = fromZ * PIXEL;
        float x1 = toX * PIXEL;
        float y1 = toY * PIXEL;
        float z1 = toZ * PIXEL;

        // NORTH (-Z)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x1, y1, z0, north.u0, north.v0,
                x1, y0, z0, north.u0, north.v1,
                x0, y0, z0, north.u1, north.v1,
                x0, y1, z0, north.u1, north.v0,
                0, 0, -1);

        // EAST (+X)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x1, y1, z1, east.u0, east.v0,
                x1, y0, z1, east.u0, east.v1,
                x1, y0, z0, east.u1, east.v1,
                x1, y1, z0, east.u1, east.v0,
                1, 0, 0);

        // SOUTH (+Z)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x0, y1, z1, south.u0, south.v0,
                x0, y0, z1, south.u0, south.v1,
                x1, y0, z1, south.u1, south.v1,
                x1, y1, z1, south.u1, south.v0,
                0, 0, 1);

        // WEST (-X)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x0, y1, z0, west.u0, west.v0,
                x0, y0, z0, west.u0, west.v1,
                x0, y0, z1, west.u1, west.v1,
                x0, y1, z1, west.u1, west.v0,
                -1, 0, 0);

        // UP (+Y)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x0, y1, z0, up.u0, up.v0,
                x0, y1, z1, up.u0, up.v1,
                x1, y1, z1, up.u1, up.v1,
                x1, y1, z0, up.u1, up.v0,
                0, 1, 0);

        // DOWN (-Y)
        quad(poseStack, consumer, packedLight, packedOverlay,
                x0, y0, z1, down.u0, down.v0,
                x0, y0, z0, down.u0, down.v1,
                x1, y0, z0, down.u1, down.v1,
                x1, y0, z1, down.u1, down.v0,
                0, -1, 0);

        poseStack.popPose();
    }

    private static void quad(
            PoseStack poseStack,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float normalX, float normalY, float normalZ
    ) {
        PoseStack.Pose pose = poseStack.last();

        vertex(consumer, pose, packedLight, packedOverlay, x0, y0, z0, u0, v0, normalX, normalY, normalZ);
        vertex(consumer, pose, packedLight, packedOverlay, x1, y1, z1, u1, v1, normalX, normalY, normalZ);
        vertex(consumer, pose, packedLight, packedOverlay, x2, y2, z2, u2, v2, normalX, normalY, normalZ);
        vertex(consumer, pose, packedLight, packedOverlay, x3, y3, z3, u3, v3, normalX, normalY, normalZ);
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            int packedLight,
            int packedOverlay,
            float x,
            float y,
            float z,
            float u,
            float v,
            float normalX,
            float normalY,
            float normalZ
    ) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(1.0F, 1.0F, 1.0F, 1.0F)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, normalX, normalY, normalZ);
    }

    private record Uv(float u0, float v0, float u1, float v1) {
    }
}
