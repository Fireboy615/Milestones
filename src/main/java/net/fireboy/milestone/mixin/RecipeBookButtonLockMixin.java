package net.fireboy.milestone.mixin;

import net.fireboy.milestone.client.MilestoneRecipeBookLockVisuals;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe-book grid cycles between multiple RecipeHolders on one button.
 * Query getRecipe() every render so the lock matches the current output, and
 * append the existing milestone requirement to the native item tooltip.
 */
@Mixin(RecipeButton.class)
public abstract class RecipeBookButtonLockMixin {
    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void milestone$renderLock(GuiGraphics graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        RecipeButton button = (RecipeButton) (Object) this;
        RecipeHolder<?> current = button.getRecipe();
        if (current != null && MilestoneRecipeBookLockVisuals.locked(current.id()))
            MilestoneRecipeBookLockVisuals.draw(graphics, button.getX(), button.getY());
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"), cancellable = true)
    private void milestone$appendLockTooltip(CallbackInfoReturnable<List<Component>> cir) {
        RecipeHolder<?> current = ((RecipeButton) (Object) this).getRecipe();
        if (current == null) return;
        List<Component> extra = MilestoneRecipeBookLockVisuals.tooltip(current.id());
        if (extra.isEmpty()) return;
        List<Component> combined = new ArrayList<>(cir.getReturnValue());
        combined.addAll(extra);
        cir.setReturnValue(combined);
    }
}
