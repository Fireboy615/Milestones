package net.fireboy.milestone.mixin;

import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Preserve all client/server stonecutter recipe indexes, but deny the output of
 * whichever selected recipe is locked. Items are never removed after crafting.
 */
@Mixin(StonecutterMenu.class)
public abstract class StonecutterLockMixin {
    @Shadow @Final private DataSlot selectedRecipeIndex;
    @Shadow @Final private Level level;
    @Shadow private List<RecipeHolder<StonecutterRecipe>> recipes;
    @Shadow @Final private ResultContainer resultContainer;

    @Inject(method = "setupResultSlot", at = @At("HEAD"), cancellable = true)
    private void milestone$denyLockedResult(CallbackInfo info) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        int selected = selectedRecipeIndex.get();
        if (selected < 0 || selected >= recipes.size()) return;
        RecipeHolder<StonecutterRecipe> holder = recipes.get(selected);
        if (!MilestoneFeatureLocks.isRecipeLocked(serverLevel.getServer(), holder.id())) return;
        resultContainer.setItem(0, ItemStack.EMPTY);
        ((StonecutterMenu) (Object) this).broadcastChanges();
        info.cancel();
    }
}
