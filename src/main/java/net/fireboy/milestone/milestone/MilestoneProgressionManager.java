package net.fireboy.milestone.milestone;

import net.fireboy.milestone.world.MilestoneProgressionSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Set;

/** Central access point for server-wide Milestone progression flags. */
public final class MilestoneProgressionManager {
    private static final SavedData.Factory<MilestoneProgressionSavedData> FACTORY =
            new SavedData.Factory<>(MilestoneProgressionSavedData::create, MilestoneProgressionSavedData::load);

    private MilestoneProgressionManager() {
    }

    public static MilestoneProgressionSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, MilestoneProgressionSavedData.DATA_NAME);
    }

    public static boolean isUnlocked(MinecraftServer server, MilestoneUnlock unlock) {
        return data(server).isUnlocked(unlock);
    }

    public static boolean unlock(MinecraftServer server, MilestoneUnlock unlock) {
        return data(server).unlock(unlock);
    }

    public static Set<MilestoneUnlock> unlocks(MinecraftServer server) {
        return data(server).unlocks();
    }

    public static boolean unlockDimension(MinecraftServer server, ResourceLocation dimension) {
        return data(server).unlockDimension(dimension);
    }

    public static boolean isDimensionUnlocked(MinecraftServer server, ResourceLocation dimension) {
        return data(server).isDimensionUnlocked(dimension);
    }

    /** Generic access flags are saved even without a corresponding mod adapter.
     * The vanilla Nether portal is mapped to its original four gameplay flags. */
    public static boolean unlockPortal(MinecraftServer server, ResourceLocation dimension,
                                       ResourceLocation portalType, MilestonePortalAccess access) {
        boolean added = data(server).unlockPortal(dimension, portalType, access.name());
        if (dimension.toString().equals("minecraft:the_nether")
                && portalType.toString().equals("minecraft:nether_portal"))
            unlock(server, access.legacyNetherFlag());
        return added;
    }

    public static boolean isPortalUnlocked(MinecraftServer server, ResourceLocation dimension,
                                           ResourceLocation portalType, MilestonePortalAccess access) {
        return data(server).isPortalUnlocked(dimension, portalType, access.name())
                || dimension.toString().equals("minecraft:the_nether")
                    && portalType.toString().equals("minecraft:nether_portal")
                    && isUnlocked(server, access.legacyNetherFlag());
    }

    /** Development/admin reset for testing progression from a sealed server. */
    public static void clear(MinecraftServer server) {
        data(server).clear();
    }
}
