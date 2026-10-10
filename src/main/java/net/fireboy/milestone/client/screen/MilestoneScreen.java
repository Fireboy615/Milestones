package net.fireboy.milestone.client.screen;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.menu.MilestoneMenu;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.task.AdvancementTaskDefinition;
import net.fireboy.milestone.task.ItemSubmissionTaskDefinition;
import net.fireboy.milestone.task.KillTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * Vanilla-style Mission Station container screen.
 *
 * The screen deliberately uses the normal 176px container width and a compact
 * 238px height. Minecraft container screens operate in GUI-scaled coordinates,
 * so the entire layout naturally follows the user's GUI Scale setting instead
 * of drawing a fixed-size desktop-style panel.
 */
public final class MilestoneScreen extends AbstractContainerScreen<MilestoneMenu> {
    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "textures/gui/milestone_core.png");
    private static final ResourceLocation SLOT_SPRITE =
            ResourceLocation.withDefaultNamespace("container/slot");

    private static final int VANILLA_TEXT = 0x404040;
    private static final int MUTED_TEXT = 0x606060;
    private static final int COMPLETE_TEXT = 0x208020;
    private static final int INCOMPLETE_TEXT = 0xA03030;
    private static final int PROGRESS_BG = 0xFF555555;
    private static final int PROGRESS_FILL = 0xFF80B918;

    private Button previousButton;
    private Button nextButton;
    private Button claimButton;
    private Button verifyButton;

    @Override
    protected void init() {
        super.init();
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"),
                button -> switchMilestone(MilestoneMenu.BUTTON_PREVIOUS))
                .bounds(leftPos + 136, topPos + 4, 16, 13).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"),
                button -> switchMilestone(MilestoneMenu.BUTTON_NEXT))
                .bounds(leftPos + 155, topPos + 4, 16, 13).build());
        claimButton = addRenderableWidget(Button.builder(Component.literal("Get Schematic"),
                button -> stationAction(MilestoneMenu.BUTTON_CLAIM_SCHEMATIC))
                .bounds(leftPos + 8, topPos + 119, 82, 18).build());
        verifyButton = addRenderableWidget(Button.builder(Component.literal("Verify Build"),
                button -> stationAction(MilestoneMenu.BUTTON_VERIFY_STRUCTURE))
                .bounds(leftPos + 93, topPos + 119, 75, 18).build());
    }

    private void stationAction(int action) {
        if (menu.availableCount() > 0 && minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
        }
    }

    private void switchMilestone(int direction) {
        if (menu.availableCount() > 1 && minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, direction);
        }
    }

    public MilestoneScreen(MilestoneMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 238;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 144;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (previousButton != null) previousButton.active = menu.availableCount() > 1;
        if (nextButton != null) nextButton.active = menu.availableCount() > 1;
        if (claimButton != null) claimButton.active = menu.availableCount() > 0;
        if (verifyButton != null) verifyButton.active =
                menu.availableCount() > 0 && !menu.structureVerified();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        MilestoneTaskDefinition task = menu.taskDefinition();
        if (menu.structureVerified() && task instanceof ItemSubmissionTaskDefinition && !menu.taskComplete()) {
            graphics.blitSprite(
                    SLOT_SPRITE,
                    leftPos + MilestoneMenu.SUBMISSION_SLOT_X - 1,
                    topPos + MilestoneMenu.SUBMISSION_SLOT_Y - 1,
                    18,
                    18
            );
        }

        int contributors = menu.contributorCount();
        int target = Math.max(menu.minimumContributors(), menu.milestoneType().finalThreshold());
        int barX = leftPos + 8;
        int barY = topPos + 112;
        int barWidth = 160;
        graphics.fill(barX, barY, barX + barWidth, barY + 5, PROGRESS_BG);

        if (target > 0 && contributors > 0) {
            int fill = Math.min(barWidth, (int) Math.round(barWidth * (contributors / (double) target)));
            graphics.fill(barX, barY, barX + fill, barY + 5, PROGRESS_FILL);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        MilestoneTaskDefinition task = menu.taskDefinition();

        // Station-owned UI: a selectable view of all discovered Cores.
        if (menu.availableCount() == 0) {
            graphics.drawString(font, "Mission Station", titleLabelX, titleLabelY, VANILLA_TEXT, false);
            graphics.drawString(font, "No discovered Milestones yet.", 8, 35, INCOMPLETE_TEXT, false);
            graphics.drawString(font, "Find a Core and right-click it", 8, 48, MUTED_TEXT, false);
            graphics.drawString(font, "to unlock its schematic here.", 8, 59, MUTED_TEXT, false);
            graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, VANILLA_TEXT, false);
            return;
        }

        var core = MilestoneContentConfig.client().core(menu.coreTier());
        String name = font.plainSubstrByWidth(core == null ? menu.milestoneType().displayName() : core.name(), 103);
        graphics.drawString(font, name, titleLabelX, titleLabelY, VANILLA_TEXT, false);
        graphics.drawString(font, (menu.selectedIndex() + 1) + "/" + menu.availableCount(),
                112, titleLabelY, MUTED_TEXT, false);
        graphics.drawString(font, "State: " + pretty(menu.milestoneState().name()), 8, 18, MUTED_TEXT, false);

        if (!menu.prerequisitesMet()) {
            graphics.drawString(font, "Complete the required Core first.", 8, 42, INCOMPLETE_TEXT, false);
            graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, VANILLA_TEXT, false);
            return;
        }

        if (!menu.structureVerified()) {
            graphics.drawString(font, "Construction required", 8, 37, INCOMPLETE_TEXT, false);
            graphics.drawString(font, "1. Claim the Create schematic below.", 8, 52, VANILLA_TEXT, false);
            graphics.drawString(font, "2. Build it within 24 blocks", 8, 65, VANILLA_TEXT, false);
            graphics.drawString(font, "   of the Mission Station.", 8, 75, VANILLA_TEXT, false);
            graphics.drawString(font, "3. Press Verify Build.", 8, 88, VANILLA_TEXT, false);
            graphics.drawString(font, "Tasks unlock permanently.", 8, 102, MUTED_TEXT, false);
            graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, VANILLA_TEXT, false);
            return;
        }

        graphics.drawString(font, taskHeading(task), 8, 34, VANILLA_TEXT, false);

        int clueWidth = task instanceof ItemSubmissionTaskDefinition && !menu.taskComplete() ? 136 : 160;
        String clue = task == null ? "No task assigned." : task.clue();
        if (task instanceof KillTaskDefinition hunt)
            clue += " (" + menu.killProgress() + "/" + hunt.count() + ")";
        List<FormattedCharSequence> lines = font.split(Component.literal(clue), clueWidth);
        int clueY = 46;
        for (int i = 0; i < Math.min(lines.size(), 4); i++) {
            graphics.drawString(font, lines.get(i), 8, clueY + i * 9, VANILLA_TEXT, false);
        }

        if (menu.structureVerified() && task instanceof ItemSubmissionTaskDefinition && !menu.taskComplete()) {
            graphics.drawString(font, "Offer", 143, 34, MUTED_TEXT, false);
        }

        String status = menu.taskComplete() ? "Complete" : "Incomplete";
        graphics.drawString(
                font,
                "Status: " + status,
                8,
                84,
                menu.taskComplete() ? COMPLETE_TEXT : INCOMPLETE_TEXT,
                false
        );

        graphics.drawString(font, completionMode(task), 8, 95, MUTED_TEXT, false);

        int contributors = menu.contributorCount();
        int required = menu.minimumContributors();
        graphics.drawString(font, "Contributors: " + contributors + "/" + required + " required", 8, 103, VANILLA_TEXT, false);

        MilestoneType.RewardThreshold next = menu.nextThreshold();
        String nextText = next == null
                ? "All participation rewards unlocked"
                : "Next: " + next.contributors() + " - " + next.description();
        List<FormattedCharSequence> rewardLines = font.split(Component.literal(nextText), 160);
        // The construction/claim actions occupy this row; rewards remain in
        // the Journal until the expanded Station UI arrives.

        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, VANILLA_TEXT, false);
    }

    private static String taskHeading(MilestoneTaskDefinition task) {
        if (task instanceof ItemSubmissionTaskDefinition) {
            return "Offering";
        }
        if (task instanceof KillTaskDefinition) {
            return "Hunt";
        }
        if (task instanceof AdvancementTaskDefinition) {
            return "Advancement";
        }
        return "Task";
    }

    private static String completionMode(MilestoneTaskDefinition task) {
        if (task instanceof ItemSubmissionTaskDefinition) {
            return "Drag the item into the slot.";
        }
        if (task instanceof KillTaskDefinition hunt) {
            return "Kill " + hunt.count() + " target(s) yourself.";
        }
        if (task instanceof AdvancementTaskDefinition) {
            return "Automatic: advancement.";
        }
        return "No completion method available.";
    }

    private static String pretty(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
