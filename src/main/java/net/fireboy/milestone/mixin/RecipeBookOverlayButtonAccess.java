package net.fireboy.milestone.mixin;

import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The vanilla nested popup button holds the exact recipe, unlike grouped buttons. */
@Mixin(targets = "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton")
public interface RecipeBookOverlayButtonAccess {
    @Accessor("recipe")
    RecipeHolder<?> milestone$getRecipe();
}
