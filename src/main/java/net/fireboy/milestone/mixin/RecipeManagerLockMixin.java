package net.fireboy.milestone.mixin;

import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Server-only recipe selection filter. All recipes remain registered so JEI
 * can display them; the crafting result is denied before consumption.
 *
 * Vanilla 1.21.1 delegates all getRecipeFor overloads to this four-argument
 * RecipeHolder overload, including its cached checks.
 *
 * IMPORTANT: Do not filter getRecipesFor lists globally: recipe indexes in
 * stonecutters and modded GUIs must agree between the client and server.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerLockMixin {
    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true)
    private <I extends RecipeInput, T extends Recipe<I>> void milestone$filterRecipe(
            RecipeType<T> type, I input, Level level, RecipeHolder<T> lastUsed,
            CallbackInfoReturnable<Optional<RecipeHolder<T>>> result) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Optional<RecipeHolder<T>> found = result.getReturnValue();
        if (found == null || found.isEmpty()
                || !MilestoneFeatureLocks.isRecipeLocked(serverLevel.getServer(), found.get().id()))
            return;

        RecipeManager manager = (RecipeManager) (Object) this;
        for (RecipeHolder<T> candidate : manager.getAllRecipesFor(type)) {
            if (!MilestoneFeatureLocks.isRecipeLocked(serverLevel.getServer(), candidate.id())
                    && candidate.value().matches(input, level)) {
                result.setReturnValue(Optional.of(candidate));
                return;
            }
        }
        result.setReturnValue(Optional.empty());
    }
}
