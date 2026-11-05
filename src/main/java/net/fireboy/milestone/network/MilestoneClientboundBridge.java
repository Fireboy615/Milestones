package net.fireboy.milestone.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet types register on dedicated servers, where Minecraft GUI classes
 * are unavailable. Resolve the physical-client-only dispatcher only on CLIENT.
 */
public final class MilestoneClientboundBridge {
    private MilestoneClientboundBridge() {}

    public static <T extends CustomPacketPayload> void handle(T packet, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            net.fireboy.milestone.client.MilestoneClientPackets.handle(packet, context);
        }
    }
}
