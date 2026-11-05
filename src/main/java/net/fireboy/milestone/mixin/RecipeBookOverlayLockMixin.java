package net.fireboy.milestone.mixin;

import net.fireboy.milestone.client.MilestoneRecipeBookLockVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Adds a matching indicator and hover details to expanded recipe choices. */
@Mixin(OverlayRecipeComponent.class)
public abstract class RecipeBookOverlayLockMixin {
    @Shadow @Final private List<?> recipeButtons;
    @Shadow private boolean isVisible;

    @Inject(method = "render", at = @At("TAIL"))
    private void milestone$renderHoverTooltip(GuiGraphics graphics,
                                               int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        if (!isVisible) return;
        for (Object entry : recipeButtons) {
            if (!(entry instanceof AbstractWidget button)
                    || !(entry instanceof RecipeBookOverlayButtonAccess access)
                    || !button.isMouseOver(mouseX, mouseY)) continue;
            RecipeHolder<?> recipe = access.milestone$getRecipe();
            if (recipe == null) continue;
            var details = MilestoneRecipeBookLockVisuals.tooltip(recipe.id());
            if (!details.isEmpty()) {
                var minecraft = Minecraft.getInstance();
                graphics.renderComponentTooltip(minecraft.font, details, mouseX, mouseY);
            }
            break;
        }
    }
}
