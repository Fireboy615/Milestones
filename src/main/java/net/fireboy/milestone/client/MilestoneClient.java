package net.fireboy.milestone.client;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.client.screen.MilestoneConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only NeoForge Mods-list configuration entry.
 * The normal server mod entrypoint remains safe on dedicated servers.
 */
@Mod(value = Milestone.MOD_ID, dist = Dist.CLIENT)
public final class MilestoneClient {
    public MilestoneClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parent) -> new MilestoneConfigScreen(parent));
    }
}
