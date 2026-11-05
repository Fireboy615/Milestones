package net.fireboy.milestone.client.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.registration.IAdvancedRegistration;
import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.client.RecipeLockClientState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.LinkedHashSet;

/**
 * Optional JEI 19 integration. No recipes are hidden or removed.
 * All JEI categories backed by RecipeHolder IDs can display lock information,
 * including supported modded categories (Create/Mekanism subject to category type).
 */
@JeiPlugin
public final class MilestoneJeiPlugin implements IModPlugin {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "recipe_locks");

    @Override
    public ResourceLocation getPluginUid() { return ID; }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        var types = new LinkedHashSet<mezz.jei.api.recipe.RecipeType<?>>();
        registration.getJeiHelpers().getAllRecipeTypes().forEach(types::add);
        // Explicit fallback for built-in categories.
        types.add(RecipeTypes.CRAFTING);
        types.add(RecipeTypes.SMELTING);
        types.add(RecipeTypes.BLASTING);
        types.add(RecipeTypes.SMOKING);
        types.add(RecipeTypes.STONECUTTING);
        types.add(RecipeTypes.CAMPFIRE_COOKING);
        types.add(RecipeTypes.SMITHING);
        for (var type : types) register(registration, type);
    }

    /**
     * Crafting has a known 116x54 JEI layout: the arrow sits between the
     * ingredient grid and result slot. Other categories have different layouts,
     * so they use a small corner lock rather than covering an unknown slot.
     */
    private static <T> void register(
            IAdvancedRegistration registry, mezz.jei.api.recipe.RecipeType<T> type) {
        final boolean crafting = RecipeTypes.CRAFTING.equals(type);
        registry.addRecipeCategoryDecorator(type, new IRecipeCategoryDecorator<T>() {
            private ResourceLocation id(T recipe) {
                return recipe instanceof RecipeHolder<?> holder ? holder.id() : null;
            }

            @Override
            public void draw(T recipe, IRecipeCategory<T> category,
                             IRecipeSlotsView slots,
                             GuiGraphics graphics, double mouseX, double mouseY) {
                if (RecipeLockClientState.requirements(id(recipe)).isEmpty()) return;
                if (crafting) {
                    // 116x54 vanilla crafting: centre of JEI's crafting arrow.
                    int lockX = category.getWidth() - 43;
                    int lockY = category.getHeight() / 2 - 5;
                    drawLock(graphics, lockX, lockY);
                    drawLockedLabel(graphics, category.getWidth(), lockX + 6);
                } else {
                    // Non-crafting categories can have entirely different
                    // ingredient layouts. Keep a small, unobtrusive corner icon.
                    drawLock(graphics, Math.max(1, category.getWidth() - 14), 2);
                }
            }

            @Override
            public void decorateTooltips(ITooltipBuilder tooltip, T recipe,
                                         IRecipeCategory<T> category,
                                         IRecipeSlotsView slots,
                                         double mouseX, double mouseY) {
                var requirements = RecipeLockClientState.requirements(id(recipe));
                if (requirements.isEmpty()) return;
                if (crafting) {
                    int lockX = category.getWidth() - 43;
                    int lockY = category.getHeight() / 2 - 5;
                    int badgeWidth = Minecraft.getInstance().font.width("LOCKED") + 10;
                    int badgeLeft = Math.max(0, Math.min(category.getWidth() - badgeWidth,
                            lockX + 6 - badgeWidth / 2));
                    boolean overLabel = mouseX >= badgeLeft && mouseX < badgeLeft + badgeWidth
                            && mouseY >= 2 && mouseY < 16;
                    boolean overArrow = mouseX >= lockX - 3 && mouseX < lockX + 16
                            && mouseY >= lockY - 3 && mouseY < lockY + 16;
                    if (!overLabel && !overArrow) return;
                } else {
                    int x = Math.max(1, category.getWidth() - 14);
                    if (mouseX < x || mouseX >= x + 13
                            || mouseY < 1 || mouseY >= 17) return;
                }
                tooltip.add(Component.literal("Recipe locked by Milestones")
                        .withStyle(ChatFormatting.RED));
                for (var requirement : requirements) {
                    tooltip.add(Component.literal(requirement.feature() + ": "
                            + requirement.milestone() + " ("
                            + requirement.current() + "/" + requirement.needed()
                            + " contributors)")
                            .withStyle(ChatFormatting.GOLD));
                }
            }
        });
    }

    /**
     * Small, hand-drawn 12x14 Minecraft-style padlock. Pixel rectangles instead
     * of a texture asset keep it sharp at every Minecraft GUI scale.
     */
    private static void drawLock(GuiGraphics graphics, int x, int y) {
        final int outline = 0xFF291B19;
        final int light = 0xFFF7D89B;
        final int gold = 0xFFD9A05E;
        final int darkGold = 0xFFAA693C;

        // Soft shadow against the grey JEI recipe arrow.
        graphics.fill(x + 2, y + 13, x + 13, y + 15, 0x66000000);

        // Pixelated shackle, with a dark outline and lit metal interior.
        graphics.fill(x + 3, y, x + 9, y + 2, outline);
        graphics.fill(x + 2, y + 2, x + 4, y + 7, outline);
        graphics.fill(x + 8, y + 2, x + 10, y + 7, outline);
        graphics.fill(x + 4, y + 1, x + 8, y + 3, light);
        graphics.fill(x + 3, y + 3, x + 4, y + 7, gold);
        graphics.fill(x + 8, y + 3, x + 9, y + 7, darkGold);

        // Gold body, dark pixel border and one-pixel highlights.
        graphics.fill(x, y + 5, x + 12, y + 14, outline);
        graphics.fill(x + 1, y + 6, x + 11, y + 13, gold);
        graphics.fill(x + 2, y + 7, x + 10, y + 9, light);
        graphics.fill(x + 2, y + 11, x + 10, y + 12, darkGold);
        graphics.fill(x + 5, y + 8, x + 7, y + 10, outline);
        graphics.fill(x + 6, y + 10, x + 7, y + 12, outline);
    }

    /** Small Minecraft-font label above the arrow, never over the output item. */
    private static void drawLockedLabel(GuiGraphics graphics, int layoutWidth, int centerX) {
        var font = Minecraft.getInstance().font;
        int badgeWidth = font.width("LOCKED") + 10;
        int x = Math.max(0, Math.min(layoutWidth - badgeWidth,
                centerX - badgeWidth / 2));
        final int border = 0xFF2E1A1A;
        final int fill = 0xFF663736;
        graphics.fill(x, 2, x + badgeWidth, 16, border);
        graphics.fill(x + 1, 3, x + badgeWidth - 1, 15, fill);
        graphics.fill(x + 2, 3, x + badgeWidth - 2, 4, 0xFFA96860);
        graphics.fill(x + 2, 14, x + badgeWidth - 2, 15, 0xFF4B2525);
        graphics.drawString(font, "LOCKED", x + 5, 5, 0xFFFCE8DB, false);

        // Tiny pixel speech-bubble tip pointing to the lock on the arrow.
        graphics.fill(centerX - 3, 16, centerX + 4, 17, border);
        graphics.fill(centerX - 2, 17, centerX + 3, 18, border);
        graphics.fill(centerX - 1, 18, centerX + 2, 19, border);
        graphics.fill(centerX - 2, 16, centerX + 3, 17, fill);
    }
}
