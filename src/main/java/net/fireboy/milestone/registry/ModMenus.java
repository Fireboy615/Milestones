package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.menu.MilestoneMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Milestone.MOD_ID);

    public static final Supplier<MenuType<MilestoneMenu>> MILESTONE =
            MENUS.register("milestone", () -> IMenuTypeExtension.create(MilestoneMenu::new));

    private ModMenus() {
    }
}
