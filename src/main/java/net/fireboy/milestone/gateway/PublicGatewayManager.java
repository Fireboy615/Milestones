package net.fireboy.milestone.gateway;

import net.fireboy.milestone.milestone.MilestoneProgressionManager;
import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.fireboy.milestone.world.PublicGatewaySavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public final class PublicGatewayManager {
    private static final SavedData.Factory<PublicGatewaySavedData> FACTORY =
            new SavedData.Factory<>(PublicGatewaySavedData::create, PublicGatewaySavedData::load);

    private PublicGatewayManager() {
    }

    public static PublicGatewaySavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, PublicGatewaySavedData.DATA_NAME);
    }

    public static int allowedGatewayCount(MinecraftServer server) {
        if (MilestoneProgressionManager.isUnlocked(server, MilestoneUnlock.NETHER_PERSONAL_PORTALS)) {
            return Integer.MAX_VALUE;
        }
        if (MilestoneProgressionManager.isUnlocked(server, MilestoneUnlock.NETHER_PUBLIC_GATEWAY_2)) {
            return 3;
        }
        if (MilestoneProgressionManager.isUnlocked(server, MilestoneUnlock.NETHER_PUBLIC_GATEWAY_1)) {
            return 2;
        }
        if (MilestoneProgressionManager.isUnlocked(server, MilestoneUnlock.SHARED_NETHER_GATEWAY)) {
            return 1;
        }
        return 0;
    }

    public static int registeredGatewayCount(MinecraftServer server) {
        return data(server).all().size();
    }

    public static Optional<PublicGatewayRecord> findAt(ServerLevel level, BlockPos portalPos) {
        String dimension = level.dimension().location().toString();
        return data(level.getServer()).all().stream()
                .filter(record -> record.contains(dimension, portalPos))
                .findFirst();
    }

    public static RegistrationResult registerNearest(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();

        if (MilestoneProgressionManager.isUnlocked(server, MilestoneUnlock.NETHER_PERSONAL_PORTALS)) {
            return RegistrationResult.failure("Personal Nether portals are already unlocked; public registration is no longer required.");
        }

        int allowed = allowedGatewayCount(server);
        if (allowed <= 0) {
            return RegistrationResult.failure("No public Nether gateway slot has been unlocked yet.");
        }

        BlockPos portalPos = findNearestPortal(level, player.blockPosition(), radius).orElse(null);
        if (portalPos == null) {
            return RegistrationResult.failure("No lit Nether portal found within " + radius + " blocks.");
        }

        GatewayPortalArea area = GatewayPortalArea.capture(level, portalPos);
        if (area == null) {
            return RegistrationResult.failure("Could not capture that portal.");
        }

        Optional<PublicGatewayRecord> existing = findAt(level, portalPos);
        if (existing.isPresent()) {
            return RegistrationResult.success(existing.get(), false, "That portal is already a registered public gateway.");
        }

        int registered = registeredGatewayCount(server);
        if (registered >= allowed) {
            return RegistrationResult.failure("All public gateway slots are in use (" + registered + "/" + allowed + ").");
        }

        PublicGatewayRecord record = new PublicGatewayRecord(
                UUID.randomUUID(),
                player.getUUID(),
                level.getGameTime(),
                java.util.List.of(area)
        );
        data(server).put(record);
        return RegistrationResult.success(
                record,
                true,
                "Registered public gateway " + shortId(record) + " (" + (registered + 1) + "/" + allowed + ")."
        );
    }

    public static boolean attachNearestSide(ServerPlayer player, UUID gatewayId, int radius) {
        ServerLevel level = player.serverLevel();
        PublicGatewayRecord record = data(level.getServer()).get(gatewayId).orElse(null);
        if (record == null) {
            return false;
        }

        BlockPos portalPos = findNearestPortal(level, player.blockPosition(), radius).orElse(null);
        if (portalPos == null) {
            return false;
        }

        GatewayPortalArea area = GatewayPortalArea.capture(level, portalPos);
        if (area == null) {
            return false;
        }

        boolean unchanged = record.sides().stream().anyMatch(side -> side.samePhysicalArea(area));
        if (unchanged) {
            return false;
        }

        data(level.getServer()).put(record.withOrReplaceSide(area));
        return true;
    }

    public static RegistrationResult unregisterNearest(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        BlockPos portalPos = findNearestPortal(level, player.blockPosition(), radius).orElse(null);
        if (portalPos == null) {
            return RegistrationResult.failure("No lit Nether portal found within " + radius + " blocks.");
        }

        PublicGatewayRecord record = findAt(level, portalPos).orElse(null);
        if (record == null) {
            return RegistrationResult.failure("That portal is not part of a registered public gateway.");
        }

        data(level.getServer()).remove(record.id());
        return RegistrationResult.success(record, true, "Unregistered public gateway " + shortId(record) + ".");
    }

    public static void clear(MinecraftServer server) {
        data(server).clear();
    }

    public static Optional<BlockPos> findNearestPortal(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius)
        )) {
            if (!level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                continue;
            }
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return Optional.ofNullable(best);
    }

    public static String shortId(PublicGatewayRecord record) {
        return record.id().toString().substring(0, 8);
    }

    public static java.util.List<PublicGatewayRecord> sorted(MinecraftServer server) {
        return data(server).all().stream()
                .sorted(Comparator.comparingLong(PublicGatewayRecord::createdGameTime))
                .toList();
    }

    public record RegistrationResult(boolean success, PublicGatewayRecord gateway, boolean changed, String message) {
        public static RegistrationResult failure(String message) {
            return new RegistrationResult(false, null, false, message);
        }

        public static RegistrationResult success(PublicGatewayRecord gateway, boolean changed, String message) {
            return new RegistrationResult(true, gateway, changed, message);
        }
    }
}
