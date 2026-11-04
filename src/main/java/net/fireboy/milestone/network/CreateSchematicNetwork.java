package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Create loads Schematic items from a local .nbt file on the client and from
 * uploaded/<owner>/<filename> on the server. Discovered chamber templates
 * are small, so sync the exact template bytes to the player's local Create
 * schematics folder. Works for dedicated servers as well as singleplayer.
 */
public final class CreateSchematicNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateSchematicNetwork.class);
    private static final Pattern FILENAME =
            Pattern.compile("(?:milestone-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|milestone-shop-[a-z0-9_-]{1,64}-[0-9a-f]{16})\\.nbt");
    private static final int MAX_BYTES = 262144;

    private CreateSchematicNetwork() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BlueprintSync.TYPE,
                BlueprintSync.CODEC, MilestoneClientboundBridge::handle);
    }

    public static void send(ServerPlayer player, String filename, byte[] compressed) {
        if (!valid(filename, compressed)) {
            LOGGER.warn("Refusing to sync invalid Milestone blueprint: {}", filename);
            return;
        }
        PacketDistributor.sendToPlayer(player, new BlueprintSync(filename, compressed));
    }

    public static boolean valid(String filename, byte[] compressed) {
        return filename != null && FILENAME.matcher(filename).matches()
                && compressed != null && compressed.length > 0 && compressed.length <= MAX_BYTES;
    }



    public record BlueprintSync(String filename, byte[] compressedNbt) implements CustomPacketPayload {
        public static final Type<BlueprintSync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "create_schematic"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintSync> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, BlueprintSync::filename,
                        ByteBufCodecs.byteArray(MAX_BYTES), BlueprintSync::compressedNbt,
                        BlueprintSync::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
