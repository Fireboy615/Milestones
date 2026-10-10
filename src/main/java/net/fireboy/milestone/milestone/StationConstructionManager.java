package net.fireboy.milestone.milestone;

import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.world.StationConstructionSavedData;
import net.fireboy.milestone.worldgen.WorldSiteManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-authoritative construction certification, one chamber per Milestone.
 * The Create schematic provides its shape; this compares real placed blocks.
 */
public final class StationConstructionManager {
    public static final int RADIUS = 24;
    private static final int VERTICAL = 8;
    private static final SavedData.Factory<StationConstructionSavedData> FACTORY =
            new SavedData.Factory<>(StationConstructionSavedData::create,
                    StationConstructionSavedData::load);

    private StationConstructionManager() { }

    public static StationConstructionSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, StationConstructionSavedData.DATA_NAME);
    }

    public static boolean isVerified(MinecraftServer server, MilestoneRecord record) {
        return data(server).isVerified(record.id());
    }

    public static String verify(ServerLevel stationLevel, BlockPos station,
                                MilestoneRecord record) {
        if (!stationLevel.dimension().equals(ServerLevel.OVERWORLD)
                || !WorldSiteManager.data(stationLevel.getServer()).mission()
                        .filter(station::equals).isPresent()
                || !stationLevel.getBlockState(station).is(ModBlocks.MISSION_STATION.get())) {
            return "This is not the registered Mission Station.";
        }
        if (record == null || record.discoveredBy() == null) {
            return "Discover this Core before building its schematic.";
        }
        StationConstructionSavedData saved = data(stationLevel.getServer());
        if (saved.isVerified(record.id())) return "This Milestone's structure is already certified.";

        ConfiguredSchematic.Blueprint custom = null;
        try {
            custom = ConfiguredSchematic.forRecord(record);
        } catch (java.io.IOException exception) {
            return "Schematic file is missing or invalid: " + exception.getMessage();
        }
        // Scan near the station without generating new chunks.
        // Custom structure uses the first non-air block as an anchor.
        // The legacy chamber still uses its fixed chiseled-deepslate centre.
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS || dx * dx + dz * dz < 49) continue;
                for (int dy = -VERTICAL; dy <= VERTICAL; dy++) {
                    BlockPos center = station.offset(dx, dy, dz);
                    if (saved.positionUsed(center) || !stationLevel.hasChunkAt(center))
                        continue;
                    if (custom == null) {
                        if (!stationLevel.getBlockState(center).is(Blocks.CHISELED_DEEPSLATE)
                                || !matches(stationLevel, center)) continue;
                    } else {
                        // Early anchor comparison avoids full checks at every candidate.
                        var first = custom.blocks().get(0).state();
                        if (!net.minecraft.nbt.NbtUtils.writeBlockState(
                                stationLevel.getBlockState(center)).equals(first)) continue;
                        if (!ConfiguredSchematic.matches(stationLevel, center, custom)) continue;
                    }
                    saved.verify(record.id(), center);
                    return (custom == null ? "Chamber" : "Custom schematic")
                            + " verified! This Milestone's tasks are now unlocked permanently.";
                }
            }
        }
        return (custom == null ? "No complete chamber" : "No matching schematic")
                + " found within " + RADIUS + " blocks horizontally and " + VERTICAL
                + " vertically. Build the assigned Create schematic without rotation.";
    }

    private static boolean matches(ServerLevel level, BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos floor = center.offset(dx, 0, dz);
                BlockPos lantern = center.offset(dx, 3, dz);
                if (!level.hasChunkAt(floor) || !level.hasChunkAt(lantern)) return false;
                if (dx != 0 || dz != 0) {
                    if (!level.getBlockState(floor).is(Blocks.POLISHED_DEEPSLATE)) return false;
                }
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2
                        && !level.getBlockState(lantern).is(Blocks.SEA_LANTERN)) {
                    return false;
                }
            }
        }
        return true;
    }
}
