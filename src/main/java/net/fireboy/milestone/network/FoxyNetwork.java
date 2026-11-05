package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * One tiny server-to-client signal. All connected clients receive the same
 * event on the same server tick. There is deliberately no client-to-server
 * counterpart or player-controlled trigger packet.
 */
public final class FoxyNetwork {
    private FoxyNetwork() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(
                Jumpscare.TYPE, Jumpscare.CODEC, MilestoneClientboundBridge::handle);
    }

    public record Jumpscare() implements CustomPacketPayload {
        public static final Type<Jumpscare> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "secret_fox"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Jumpscare> CODEC =
                StreamCodec.unit(new Jumpscare());

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
