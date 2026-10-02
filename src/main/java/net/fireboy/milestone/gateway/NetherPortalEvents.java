package net.fireboy.milestone.gateway;

import net.fireboy.milestone.milestone.MilestoneProgressionManager;
import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NetherPortalEvents {
    private static final int COUNTERPART_SCAN_RADIUS = 8;
    private static final long PENDING_MAX_AGE_TICKS = 200L;
    private static final Map<UUID, PendingGatewayTravel> PENDING_TRAVEL = new HashMap<>();

    private NetherPortalEvents() {
    }

    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (MilestoneProgressionManager.isUnlocked(level.getServer(), MilestoneUnlock.NETHER_PERSONAL_PORTALS)) {
            return;
        }

        if (!MilestoneProgressionManager.isUnlocked(level.getServer(), MilestoneUnlock.SHARED_NETHER_GATEWAY)) {
            event.setCanceled(true);
        }
    }

    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel sourceLevel)) {
            return;
        }

        ResourceKey<Level> destination = event.getDimension();
        if (!isOverworldNetherPair(sourceLevel.dimension(), destination)) {
            return;
        }

        if (entity.portalProcess == null) {
            return;
        }

        BlockPos entryPos = entity.portalProcess.getEntryPosition();
        if (!sourceLevel.getBlockState(entryPos).is(Blocks.NETHER_PORTAL)) {
            return;
        }

        if (MilestoneProgressionManager.isUnlocked(sourceLevel.getServer(), MilestoneUnlock.NETHER_PERSONAL_PORTALS)) {
            PENDING_TRAVEL.remove(entity.getUUID());
            return;
        }

        if (PublicGatewayManager.allowedGatewayCount(sourceLevel.getServer()) <= 0) {
            PENDING_TRAVEL.remove(entity.getUUID());
            event.setCanceled(true);
            deny(entity, "The Nether is still sealed. Complete the Nether Milestone first.");
            return;
        }

        PublicGatewayRecord gateway = PublicGatewayManager.findAt(sourceLevel, entryPos).orElse(null);
        if (gateway == null) {
            PENDING_TRAVEL.remove(entity.getUUID());
            event.setCanceled(true);
            deny(entity, "This portal is not an active public Milestone gateway yet.");
            return;
        }

        if (entity instanceof ServerPlayer) {
            PENDING_TRAVEL.put(
                    entity.getUUID(),
                    new PendingGatewayTravel(gateway.id(), destination, sourceLevel.getGameTime())
            );
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        PendingGatewayTravel pending = PENDING_TRAVEL.remove(player.getUUID());
        if (pending == null || !pending.destination().equals(event.getTo())) {
            return;
        }

        long age = player.serverLevel().getGameTime() - pending.startedGameTime();
        if (age < 0L || age > PENDING_MAX_AGE_TICKS) {
            return;
        }

        if (PublicGatewayManager.attachNearestSide(player, pending.gatewayId(), COUNTERPART_SCAN_RADIUS)) {
            player.sendSystemMessage(
                    Component.literal("The public gateway anchors to this side of the portal.")
                            .withStyle(ChatFormatting.DARK_AQUA)
            );
        }
    }

    private static boolean isOverworldNetherPair(ResourceKey<Level> from, ResourceKey<Level> to) {
        return (from.equals(Level.OVERWORLD) && to.equals(Level.NETHER))
                || (from.equals(Level.NETHER) && to.equals(Level.OVERWORLD));
    }

    private static void deny(Entity entity, String message) {
        if (entity instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        }
    }

    private record PendingGatewayTravel(UUID gatewayId, ResourceKey<Level> destination, long startedGameTime) {
    }
}
