package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Sends only the currently locked recipe selectors and requirements. */
public final class RecipeLockNetwork {
    private RecipeLockNetwork() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Sync.TYPE, Sync.CODEC,
                MilestoneClientboundBridge::handle);
    }

    public static void sync(ServerPlayer player) {
        if (player.getServer() != null)
            PacketDistributor.sendToPlayer(player,
                    new Sync(MilestoneFeatureLocks.clientSnapshot(player.getServer())));
    }

    public static void syncAll(MinecraftServer server) {
        if (server == null) return;
        Sync snapshot = new Sync(MilestoneFeatureLocks.clientSnapshot(server));
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            PacketDistributor.sendToPlayer(player, snapshot);
    }

    public record Sync(String json) implements CustomPacketPayload {
        public static final Type<Sync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "recipe_locks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Sync::json, Sync::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
