package net.fireboy.milestone.milestone;

import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.world.MilestoneSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public final class MilestoneManager {
    private static final SavedData.Factory<MilestoneSavedData> FACTORY =
            new SavedData.Factory<>(MilestoneSavedData::create, MilestoneSavedData::load);

    private MilestoneManager() {
    }

    public static MilestoneSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, MilestoneSavedData.DATA_NAME);
    }

    public static MilestoneRecord register(ServerLevel level, MilestoneCoreBlockEntity core) {
        MilestoneRecord record = MilestoneRecord.fresh(
                core.getMilestoneId(),
                level.dimension().location().toString(),
                core.getBlockPos(),
                core.getMilestoneState(),
                level.getGameTime()
        );
        data(level.getServer()).put(record);
        return record;
    }


    public static Optional<MilestoneRecord> get(MinecraftServer server, UUID milestoneId) {
        return data(server).get(milestoneId);
    }

    /** Resolve the level that owns a persisted Milestone record. */
    public static ServerLevel levelFor(MinecraftServer server, MilestoneRecord record) {
        ResourceLocation location = ResourceLocation.tryParse(record.dimension());
        if (location == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    /** Persist a gameplay-updated record and mirror its lifecycle state into the loaded Core. */
    public static MilestoneRecord save(ServerLevel level, MilestoneRecord record) {
        data(level.getServer()).put(record);

        if (level.getBlockEntity(record.corePos()) instanceof MilestoneCoreBlockEntity core) {
            core.setMilestoneState(record.state());
        }
        return record;
    }

    public static MilestoneRecord setState(ServerLevel level, MilestoneRecord record, MilestoneState state) {
        return save(level, record.withState(state));
    }

    public static void unregister(ServerLevel level, UUID milestoneId) {
        data(level.getServer()).remove(milestoneId);
    }

    public static void unregisterAt(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        data(level.getServer()).removeAt(dimension, pos);
    }

    public static void updateState(ServerLevel level, MilestoneCoreBlockEntity core) {
        MilestoneSavedData data = data(level.getServer());
        MilestoneRecord existing = data.get(core.getMilestoneId()).orElseGet(() -> register(level, core));
        data.put(existing.withState(core.getMilestoneState()));
    }

    public static Optional<MilestoneRecord> nearest(MinecraftServer server, ServerLevel level, BlockPos pos, int radius) {
        String dimension = level.dimension().location().toString();
        long radiusSquared = (long) radius * radius;
        MilestoneSavedData savedData = data(server);

        // Copy before iterating because stale records are removed as we encounter them.
        var candidates = new ArrayList<>(savedData.all());
        candidates.sort(Comparator.comparingDouble(record -> record.corePos().distSqr(pos)));

        for (MilestoneRecord record : candidates) {
            if (!record.dimension().equals(dimension)) {
                continue;
            }
            if (record.corePos().distSqr(pos) > radiusSquared) {
                continue;
            }

            // Nearby chunks should already be loaded. If a saved record points at a block
            // that no longer exists, remove the stale record instead of reporting it.
            if (!level.getBlockState(record.corePos()).is(ModBlocks.MILESTONE_CORE.get())) {
                savedData.remove(record.id());
                continue;
            }

            return Optional.of(record);
        }

        return Optional.empty();
    }
}
