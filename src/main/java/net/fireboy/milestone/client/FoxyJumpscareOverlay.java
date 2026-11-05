package net.fireboy.milestone.client;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Renders frames extracted from the user-supplied green-screen Foxy MP4.
 * Each client starts on the shared server's broadcast packet and plays
 * the original clip audio, with no gameplay effect or chat announcement.
 */
@EventBusSubscriber(modid = Milestone.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FoxyJumpscareOverlay {
    private static final ResourceLocation FRAMES =
            ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,
                    "textures/gui/foxy_frames.png");
    // The supplied clip has 26 frames at 30000/1001 FPS (~0.868 seconds of
    // frame starts), followed by a short final-pose hold/fade.
    private static final int FRAME_COUNT = 26;
    private static final int FRAME_WIDTH = 480, FRAME_HEIGHT = 270;
    private static final int SHEET_WIDTH = FRAME_WIDTH * 4;
    private static final int SHEET_HEIGHT = FRAME_HEIGHT * 7;
    private static final long FRAME_NS = 33_366_667L;
    private static final long DURATION_NS = 1_120_000_000L;
    private static long startedAt = -1L;

    private FoxyJumpscareOverlay() { }

    /** Triggered only on the client thread after the server's group packet. */
    public static void start() {
        Minecraft game = Minecraft.getInstance();
        if (game.level == null) return;
        startedAt = System.nanoTime();
        game.getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.FOXY_JUMPSCARE.get(), 1.0F, 1.0F));
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        // GUIs paint over the ordinary HUD. Handle open screens separately.
        if (Minecraft.getInstance().screen == null) render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void renderScreen(ScreenEvent.Render.Post event) {
        render(event.getGuiGraphics());
    }

    private static void render(GuiGraphics graphics) {
        long beginning = startedAt;
        if (beginning < 0) return;
        long elapsed = System.nanoTime() - beginning;
        if (elapsed < 0 || elapsed >= DURATION_NS) {
            startedAt = -1L;
            return;
        }
        Minecraft game = Minecraft.getInstance();
        if (game.level == null) {
            startedAt = -1L;
            return;
        }

        int width = game.getWindow().getGuiScaledWidth();
        int height = game.getWindow().getGuiScaledHeight();
        int frame = Math.min(FRAME_COUNT - 1, (int) (elapsed / FRAME_NS));
        int sourceX = frame % 4 * FRAME_WIDTH;
        int sourceY = frame / 4 * FRAME_HEIGHT;
        // Cover the entire screen while preserving the 16:9 clip's aspect ratio.
        // The transparent edges still reveal the game behind the tinted layer.
        float scale = Math.max(width / (float) FRAME_WIDTH,
                height / (float) FRAME_HEIGHT);
        float drawW = FRAME_WIDTH * scale;
        float drawH = FRAME_HEIGHT * scale;
        int shakeX = (int) ((frame * 17L + 3L) % 9L) - 4;
        int shakeY = (int) ((frame * 11L + 7L) % 9L) - 4;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 750.0F);
        graphics.fill(0, 0, width, height, 0x9508000A);
        graphics.pose().popPose();

        graphics.pose().pushPose();
        graphics.pose().translate((width - drawW) / 2.0F + shakeX,
                (height - drawH) / 2.0F + shakeY, 800.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(FRAMES, 0, 0, (float) sourceX, (float) sourceY,
                FRAME_WIDTH, FRAME_HEIGHT, SHEET_WIDTH, SHEET_HEIGHT);
        graphics.pose().popPose();

        // One short impact flash and a final fade; no flashing loop.
        if (elapsed < 55_000_000L || elapsed > DURATION_NS - 190_000_000L) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 900.0F);
            if (elapsed < 55_000_000L) {
                graphics.fill(0, 0, width, height, 0x35FFE7DD);
            } else {
                int fade = (int) Math.min(255L,
                        (elapsed - (DURATION_NS - 190_000_000L)) * 255L
                                / 190_000_000L);
                graphics.fill(0, 0, width, height, (fade << 24) | 0x050305);
            }
            graphics.pose().popPose();
        }
    }
}
