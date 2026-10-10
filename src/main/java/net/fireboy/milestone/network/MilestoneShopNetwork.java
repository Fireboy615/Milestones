package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.shop.MilestoneShop;
import net.fireboy.milestone.shop.MilestoneShopStock;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Small, server-authoritative shop messages. No client-provided price or item. */
public final class MilestoneShopNetwork {
    private MilestoneShopNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var channel=event.registrar("1");
        channel.playToServer(ShopRequest.TYPE,ShopRequest.CODEC,MilestoneShopNetwork::request);
        channel.playToClient(ShopView.TYPE,ShopView.CODEC,MilestoneShopNetwork::view);
        channel.playToClient(OpenShop.TYPE,OpenShop.CODEC,MilestoneShopNetwork::open);
    }
    private static void request(ShopRequest packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player))return;
        if(!MilestoneShop.atExchange(player,packet.station())) {
            // Always reply, even if the player walked away mid-purchase;
            // otherwise the client would remain stuck in a waiting state.
            PacketDistributor.sendToPlayer(player,new ShopView(
                    "{\"balance\":0,\"earned\":0,\"offers\":[]}",
                    "Move closer to the Resonant Exchange to use the shop."));
            return;
        }
        String message="";
        if(packet.action().equals("reroll")) {
            if(!player.hasPermissions(2)) {
                message="Only server operators can reroll the shop.";
            }else if(!MilestoneContentConfig.enabled()) {
                message="Milestones are currently disabled.";
            }else {
                // Rotate once on the server, then push each player's own
                // current stock and purchase state to every open shop menu.
                MilestoneShopStock.get(player.getServer()).rerollNow();
                for(ServerPlayer online:player.getServer().getPlayerList().getPlayers()) {
                    PacketDistributor.sendToPlayer(online,new ShopView(
                            MilestoneShop.snapshot(online),
                            online==player
                                ?"Stock rerolled - next reset in 2h."
                                :"Shop rerolled by an operator."));
                }
                return;
            }
        }else if(!packet.offerId().isBlank()) {
            if(packet.offerId().length()>64)return;
            message=packet.action().equals("reserve")
                    ?MilestoneShop.reserve(player,packet.station(),packet.offerId())
                    :packet.action().equals("buy")
                        ?MilestoneShop.buy(player,packet.station(),packet.offerId())
                        :"Unknown shop action.";
        }
        PacketDistributor.sendToPlayer(player,new ShopView(
                MilestoneShop.snapshot(player),message));
    }
    private static void open(OpenShop packet,IPayloadContext context) {
        if (!context.player().level().isClientSide()) return;
        var client=net.minecraft.client.Minecraft.getInstance();
        // The server sends this only after a valid Resonant Exchange interaction.
        client.setScreen(new net.fireboy.milestone.client.screen.MilestoneShopScreen(packet.pos()));
    }
    public record OpenShop(BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenShop> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"open_shop"));
        public static final StreamCodec<RegistryFriendlyByteBuf,OpenShop> CODEC=
                StreamCodec.composite(BlockPos.STREAM_CODEC,OpenShop::pos,OpenShop::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static void view(ShopView packet,IPayloadContext context) {
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneShopScreen screen)
            screen.onShopView(packet.catalogJson(),packet.message());
    }
    public record ShopRequest(BlockPos station,String offerId,String action) implements CustomPacketPayload {
        public static final Type<ShopRequest> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"shop_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ShopRequest> CODEC=
                StreamCodec.composite(BlockPos.STREAM_CODEC,ShopRequest::station,
                        ByteBufCodecs.STRING_UTF8,ShopRequest::offerId,
                        ByteBufCodecs.STRING_UTF8,ShopRequest::action,ShopRequest::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record ShopView(String catalogJson,String message) implements CustomPacketPayload {
        public static final Type<ShopView> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"shop_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ShopView> CODEC=
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8,ShopView::catalogJson,
                        ByteBufCodecs.STRING_UTF8,ShopView::message,ShopView::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
