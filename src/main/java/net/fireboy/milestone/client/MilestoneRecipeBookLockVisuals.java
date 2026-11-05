package net.fireboy.milestone.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Tiny pixel-art lock for the vanilla recipe book and its expanded recipe list. */
public final class MilestoneRecipeBookLockVisuals {
    private MilestoneRecipeBookLockVisuals() { }

    public static boolean locked(ResourceLocation recipeId) {
        return !RecipeLockClientState.requirements(recipeId).isEmpty();
    }

    /** x and y are the top-left corner of a 25x25 recipe button. */
    public static void draw(GuiGraphics graphics, int x, int y) {
        // Minecraft renders item sprites on a raised GUI Z layer (typically
        // +150). Draw the padlock above that layer rather than behind the item.
        // The pose is restored so later widgets and tooltips are unaffected.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 250.0F);
        int left = x + 15;
        int top = y + 1;
        final int border = 0xFF261B16;
        final int light = 0xFFFCE8B8;
        final int metal = 0xFFDBAA5F;
        final int shadow = 0xFF966139;

        // Dark backing separates the lock from the recipe output without
        // covering its centre. The icon remains crisp at every GUI scale.
        graphics.fill(left - 1, top, left + 10, top + 13, 0xC9342A26);
        graphics.fill(left + 2, top + 1, left + 7, top + 3, border);
        graphics.fill(left + 1, top + 3, left + 3, top + 7, border);
        graphics.fill(left + 6, top + 3, left + 8, top + 7, border);
        graphics.fill(left + 3, top + 2, left + 6, top + 4, light);
        graphics.fill(left + 2, top + 4, left + 3, top + 7, metal);
        graphics.fill(left + 6, top + 4, left + 7, top + 7, metal);
        graphics.fill(left, top + 6, left + 9, top + 12, border);
        graphics.fill(left + 1, top + 7, left + 8, top + 11, metal);
        graphics.fill(left + 2, top + 7, left + 7, top + 8, light);
        graphics.fill(left + 1, top + 10, left + 8, top + 11, shadow);
        graphics.fill(left + 4, top + 8, left + 5, top + 10, border);
        graphics.pose().popPose();
    }

    /** Same information as JEI, appended to vanilla's regular item tooltip. */
    public static List<Component> tooltip(ResourceLocation recipeId) {
        List<RecipeLockClientState.Requirement> requirements =
                RecipeLockClientState.requirements(recipeId);
        if (requirements.isEmpty()) return List.of();
        List<Component> text = new ArrayList<>();
        text.add(Component.literal("Recipe locked by Milestones")
                .withStyle(ChatFormatting.RED));
        for (var requirement : requirements)
            text.add(Component.literal(requirement.feature() + ": "
                            + requirement.milestone() + " ("
                            + requirement.current() + "/" + requirement.needed()
                            + " contributors)")
                    .withStyle(ChatFormatting.GOLD));
        return List.copyOf(text);
    }
}
