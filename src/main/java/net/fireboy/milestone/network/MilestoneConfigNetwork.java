package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Permission-checked admin configuration editing: never trust client values. */
public final class MilestoneConfigNetwork {
    private MilestoneConfigNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(ConfigRequest.TYPE, ConfigRequest.CODEC, MilestoneConfigNetwork::request);
        registrar.playToServer(ConfigEdit.TYPE, ConfigEdit.CODEC, MilestoneConfigNetwork::edit);
        registrar.playToClient(ConfigSnapshot.TYPE, ConfigSnapshot.CODEC, MilestoneConfigNetwork::snapshot);
        registrar.playToServer(ConfigSave.TYPE, ConfigSave.CODEC, MilestoneConfigNetwork::save);
        registrar.playToClient(ConfigResult.TYPE, ConfigResult.CODEC, MilestoneConfigNetwork::result);
        registrar.playToServer(IdSearchRequest.TYPE, IdSearchRequest.CODEC, MilestoneConfigNetwork::idSearch);
        registrar.playToClient(IdSearchResults.TYPE, IdSearchResults.CODEC, MilestoneConfigNetwork::idSearchResults);
    }

    private static void request(ConfigRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        PacketDistributor.sendToPlayer(player,
                new ConfigSnapshot(MilestoneContentConfig.server().payload()));
    }

    private static void edit(ConfigEdit payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        String message = MilestoneContentConfig.updateCoreField(
                payload.tier(), payload.field(), payload.value());
        boolean success = message.startsWith("Saved");
        player.sendSystemMessage(Component.literal("Milestone Config: " + message)
                .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (success) PacketDistributor.sendToPlayer(player,
                new ConfigSnapshot(MilestoneContentConfig.server().payload()));
    }

    private static void snapshot(ConfigSnapshot payload, IPayloadContext context) {
        if (context.player().level().isClientSide()) {
            MilestoneContentConfig.installClient(payload.json());
            // If the Mods menu opened the editor, update the initial field from
            // the server's authoritative config rather than stale client defaults.
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof net.fireboy.milestone.client.screen.MilestoneConfigScreen config)
                config.refreshFromServer(payload.json());
        }
    }

    private static void save(ConfigSave payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        String result = MilestoneContentConfig.saveBundle(payload.json(), player.getServer());
        boolean success = result.startsWith("Saved");
        PacketDistributor.sendToPlayer(player, new ConfigResult(success, result));
        if (success) PacketDistributor.sendToPlayer(player,
                new ConfigSnapshot(MilestoneContentConfig.server().payload()));
    }

    private static void result(ConfigResult payload, IPayloadContext context) {
        if (!context.player().level().isClientSide()) return;
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof net.fireboy.milestone.client.screen.MilestoneConfigScreen config)
            config.onSaveResult(payload.success(), payload.message());
    }

    /** Search the SERVER recipe catalog, including recipes hidden by Milestone locks.
     * Only operators may search. Results are bounded and paginated. */
    private static void idSearch(IdSearchRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        if (request.query().length() > 90) return;
        int offset=Math.max(0,request.offset());
        int limit=Math.min(30,Math.max(1,request.limit()));
        String query=request.query().toLowerCase(Locale.ROOT);
        List<String> matches=new ArrayList<>();
        // This is an on-demand administrative search; no recipe data is sent
        // to ordinary clients who haven't earned it.
        if(request.kind().equals("recipe")) {
            for(var id:MilestoneFeatureLocks.allRecipeIds(player.getServer()))
                if(id.toString().contains(query))matches.add(id.toString());
        } else if(request.kind().equals("advancement")) {
            for(var adv:player.getServer().getAdvancements().getAllAdvancements())
                if(adv.id().toString().contains(query))matches.add(adv.id().toString());
        } else return;
        matches.sort(String::compareTo);
        int end=Math.min(matches.size(),offset+limit);
        List<String> page=offset>=matches.size()?List.of():matches.subList(offset,end);
        PacketDistributor.sendToPlayer(player,new IdSearchResults(query,offset,matches.size(),page));
    }

    private static void idSearchResults(IdSearchResults result, IPayloadContext context) {
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneIdPickerScreen picker)
            picker.receiveServerResults(result.query(),result.offset(),result.total(),result.ids());
    }

    public record IdSearchRequest(String query,int offset,int limit,String kind) implements CustomPacketPayload {
        public static final Type<IdSearchRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"id_search"));
        public static final StreamCodec<RegistryFriendlyByteBuf,IdSearchRequest> CODEC =
            StreamCodec.of((buffer, request)->{
                buffer.writeUtf(request.query(),90);
                buffer.writeVarInt(request.offset());
                buffer.writeVarInt(request.limit());
                buffer.writeUtf(request.kind(),24);
            },buffer->new IdSearchRequest(buffer.readUtf(90),buffer.readVarInt(),
                    buffer.readVarInt(),buffer.readUtf(24)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record IdSearchResults(String query,int offset,int total,List<String> ids)
            implements CustomPacketPayload {
        public static final Type<IdSearchResults> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"id_search_results"));
        public static final StreamCodec<RegistryFriendlyByteBuf,IdSearchResults> CODEC =
            StreamCodec.of((buffer,result)->{
                buffer.writeUtf(result.query(),90);
                buffer.writeVarInt(result.offset());
                buffer.writeVarInt(result.total());
                buffer.writeVarInt(result.ids().size());
                for(String id:result.ids())buffer.writeUtf(id,128);
            },buffer->{
                String q=buffer.readUtf(90);
                int offset=buffer.readVarInt(),total=buffer.readVarInt();
                int size=buffer.readVarInt();
                if(size<0||size>30)throw new IllegalArgumentException("Invalid ID search result count");
                List<String> ids=new ArrayList<>();
                for(int i=0;i<size;i++)ids.add(buffer.readUtf(128));
                return new IdSearchResults(q,offset,total,List.copyOf(ids));
            });
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }

    public record ConfigSave(String json) implements CustomPacketPayload {
        public static final Type<ConfigSave> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "config_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSave> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ConfigSave::json, ConfigSave::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigResult(boolean success, String message) implements CustomPacketPayload {
        public static final Type<ConfigResult> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "config_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigResult> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, ConfigResult::success,
                        ByteBufCodecs.STRING_UTF8, ConfigResult::message, ConfigResult::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigRequest() implements CustomPacketPayload {
        public static final Type<ConfigRequest> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "config_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigRequest> CODEC =
                StreamCodec.unit(new ConfigRequest());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigEdit(int tier, String field, String value) implements CustomPacketPayload {
        public static final Type<ConfigEdit> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "config_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigEdit> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ConfigEdit::tier,
                        ByteBufCodecs.STRING_UTF8, ConfigEdit::field,
                        ByteBufCodecs.STRING_UTF8, ConfigEdit::value,
                        ConfigEdit::new
                );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigSnapshot(String json) implements CustomPacketPayload {
        public static final Type<ConfigSnapshot> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "config_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshot> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ConfigSnapshot::json,
                        ConfigSnapshot::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
