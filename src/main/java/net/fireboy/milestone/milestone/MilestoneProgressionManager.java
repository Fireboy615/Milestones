package net.fireboy.milestone.milestone;

import net.fireboy.milestone.world.MilestoneProgressionSavedData;
import net.minecraft.server.MinecraftServer;
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

    /** Development/admin reset for testing progression from a sealed server. */
    public static void clear(MinecraftServer server) {
        data(server).clear();
    }
}
