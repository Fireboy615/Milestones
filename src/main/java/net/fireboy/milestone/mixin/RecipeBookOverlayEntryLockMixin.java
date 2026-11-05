package net.fireboy.milestone.mixin;

import net.fireboy.milestone.client.MilestoneRecipeBookLockVisuals;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The nested recipe-book overlay button represents exactly one recipe, so the
 * popup shows a lock even when grouped recipes differ in lock state.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton")
public abstract class RecipeBookOverlayEntryLockMixin {
    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void milestone$drawLock(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick, CallbackInfo ci) {
        var button = (AbstractWidget) (Object) this;
        RecipeHolder<?> recipe = ((RecipeBookOverlayButtonAccess) this).milestone$getRecipe();
        if (recipe != null && MilestoneRecipeBookLockVisuals.locked(recipe.id()))
            MilestoneRecipeBookLockVisuals.draw(graphics, button.getX(), button.getY());
    }
}
