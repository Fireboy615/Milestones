package net.fireboy.milestone.network;

import com.google.gson.JsonObject;
import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.loot.MilestoneLootTools;
import net.fireboy.milestone.loot.MilestoneLootTargeting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Read-only inspector and explicit OP-only datapack exporter.
 * The server never accepts arbitrary target filesystem paths or JSON from
 * a client. Lootr API is optional and only reflected if the mod is loaded. */
public final class MilestoneLootNetwork {
    private MilestoneLootNetwork(){}
    public static void register(RegisterPayloadHandlersEvent event) {
        var channel=event.registrar("1");
        channel.playToServer(Request.TYPE,Request.CODEC,MilestoneLootNetwork::request);
        channel.playToServer(AddEntryRequest.TYPE,AddEntryRequest.CODEC,
                MilestoneLootNetwork::addEntry);
        channel.playToServer(EnchantRequest.TYPE,EnchantRequest.CODEC,
                MilestoneLootNetwork::enchant);
        channel.playToServer(EditEnchantRuleRequest.TYPE,EditEnchantRuleRequest.CODEC,
                MilestoneLootNetwork::editEnchantRule);
        channel.playToClient(Response.TYPE,Response.CODEC,MilestoneClientboundBridge::handle);
        channel.playToClient(OpenLootInspector.TYPE,OpenLootInspector.CODEC,MilestoneClientboundBridge::handle);
    }
    private static void request(Request req,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) ||!player.hasPermissions(2))return;
        if(req.query().length()>120||req.item().length()>128)return;
        JsonObject result;
        String action=req.action();
        switch(action){
            case "browse" -> result=MilestoneLootTools.browse(player,req.query(),
                    Math.max(0,Math.min(100000,req.offset())),req.item(),req.chance());
            case "inspect" -> result=MilestoneLootTools.inspect(player);
            case "arm" -> {
                MilestoneLootTargeting.arm(player);
                result=new JsonObject();
                result.addProperty("armed",true);
            }
            case "describe" -> result=MilestoneLootTools.describe(player,req.query());
            case "enchant_rules" -> result=MilestoneLootTools.enchantRules(
                    player,req.query(),Math.max(0,Math.min(100000,req.offset())),
                    req.min());
            case "pool_page" -> result=MilestoneLootTools.poolPage(
                    player,req.query(),req.offset(),req.chance(),req.min());
            case "set_weight","set_count","remove_entry" -> {
                result=new JsonObject();
                String status=MilestoneLootTools.editEntry(player,req.query(),req.item(),
                        action,req.offset(),req.chance(),req.min(),req.max());
                result.addProperty("message",status);
                result.addProperty("success",status.startsWith("Staged "));
            }
            case "restore_override" -> {
                result=new JsonObject();
                String status=MilestoneLootTools.restoreOriginal(
                        player,req.query(),req.item());
                result.addProperty("message",status);
                result.addProperty("success",status.startsWith("Milestone override removed"));
            }
            case "add_bonus" -> {
                result=new JsonObject();
                String status=MilestoneLootTools.addBonus(player,req.query(),req.item(),
                        req.chance(),req.min(),req.max());
                result.addProperty("message",status);
                result.addProperty("success",status.startsWith("Wrote datapack override"));
            }
            default -> {return;}
        }
        result.addProperty("action",action);
        PacketDistributor.sendToPlayer(player,new Response(result.toString()));
    }
    private static void addEntry(AddEntryRequest req,IPayloadContext context){
        if(!(context.player() instanceof ServerPlayer player)||!player.hasPermissions(2))
            return;
        String result=MilestoneLootTools.addItemEntry(player,req.table(),
                req.revision(),req.item(),req.pool(),req.weight(),req.min(),req.max());
        JsonObject response=new JsonObject();
        response.addProperty("action","add_entry");
        response.addProperty("success",result.startsWith("Staged new "));
        response.addProperty("message",result);
        PacketDistributor.sendToPlayer(player,new Response(response.toString()));
    }

    private static void enchant(EnchantRequest request,IPayloadContext context){
        if(!(context.player() instanceof ServerPlayer player)||!player.hasPermissions(2))
            return;
        String message=MilestoneLootTools.editEnchantment(player,request.table(),
                request.revision(),request.pool(),request.entry(),request.enchantment(),
                request.level(),request.remove());
        JsonObject result=new JsonObject();
        result.addProperty("action","edit_enchant");
        result.addProperty("message",message);
        result.addProperty("success",message.startsWith("Staged "));
        PacketDistributor.sendToPlayer(player,new Response(result.toString()));
    }
    private static void editEnchantRule(EditEnchantRuleRequest req,IPayloadContext context){
        if(!(context.player() instanceof ServerPlayer player)||!player.hasPermissions(2))
            return;
        String status=MilestoneLootTools.editEnchantmentRule(
                player,req.table(),req.revision(),req.path(),req.functionIndex(),
                req.operation(),req.options(),req.min(),req.max(),req.onlyCompatible());
        JsonObject response=new JsonObject();
        response.addProperty("action","edit_enchant_rule");
        response.addProperty("message",status);
        response.addProperty("success",status.startsWith("Staged enchant rule change"));
        PacketDistributor.sendToPlayer(player,new Response(response.toString()));
    }



    public record OpenLootInspector(String json) implements CustomPacketPayload {
        public static final Type<OpenLootInspector> TYPE=new Type<>(
                ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"open_loot_inspector"));
        public static final StreamCodec<RegistryFriendlyByteBuf,OpenLootInspector> CODEC=
                StreamCodec.of((buffer,p)->buffer.writeUtf(p.json(),8192),
                        buffer->new OpenLootInspector(buffer.readUtf(8192)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(String action,String query,String item,int offset,
                          int chance,int min,int max) implements CustomPacketPayload {
        public static final Type<Request> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"loot_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=
                StreamCodec.of((buffer,req)->{
                    buffer.writeUtf(req.action(),20);
                    buffer.writeUtf(req.query(),120);
                    buffer.writeUtf(req.item(),128);
                    buffer.writeVarInt(req.offset());
                    buffer.writeVarInt(req.chance());
                    buffer.writeVarInt(req.min());
                    buffer.writeVarInt(req.max());
                },buffer->new Request(buffer.readUtf(20),buffer.readUtf(120),buffer.readUtf(128),
                        buffer.readVarInt(),buffer.readVarInt(),buffer.readVarInt(),buffer.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record AddEntryRequest(String table,String revision,String item,
                                  int pool,int weight,int min,int max)
                                  implements CustomPacketPayload {
        public static final Type<AddEntryRequest> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,
                        "loot_add_entry"));
        public static final StreamCodec<RegistryFriendlyByteBuf,AddEntryRequest> CODEC=
                StreamCodec.of((buf,r)->{
                    buf.writeUtf(r.table(),120);
                    buf.writeUtf(r.revision(),64);
                    buf.writeUtf(r.item(),128);
                    buf.writeVarInt(r.pool());
                    buf.writeVarInt(r.weight());
                    buf.writeVarInt(r.min());
                    buf.writeVarInt(r.max());
                },buf->new AddEntryRequest(buf.readUtf(120),buf.readUtf(64),
                        buf.readUtf(128),buf.readVarInt(),buf.readVarInt(),
                        buf.readVarInt(),buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record EnchantRequest(String table,String revision,String enchantment,
                                 int pool,int entry,int level,boolean remove)
                                 implements CustomPacketPayload {
        public static final Type<EnchantRequest> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,
                        "loot_enchant_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf,EnchantRequest> CODEC=
                StreamCodec.of((buf,req)->{
                    buf.writeUtf(req.table(),120);
                    buf.writeUtf(req.revision(),64);
                    buf.writeUtf(req.enchantment(),128);
                    buf.writeVarInt(req.pool());
                    buf.writeVarInt(req.entry());
                    buf.writeVarInt(req.level());
                    buf.writeBoolean(req.remove());
                },buf->new EnchantRequest(buf.readUtf(120),buf.readUtf(64),
                        buf.readUtf(128),buf.readVarInt(),buf.readVarInt(),
                        buf.readVarInt(),buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record EditEnchantRuleRequest(String table,String revision,String path,
                                         int functionIndex,String operation,
                                         String options,int min,int max,boolean onlyCompatible)
                                         implements CustomPacketPayload {
        public static final Type<EditEnchantRuleRequest> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,
                        "loot_edit_enchant_rule"));
        public static final StreamCodec<RegistryFriendlyByteBuf,EditEnchantRuleRequest> CODEC=
                StreamCodec.of((buf,r)->{
                    buf.writeUtf(r.table(),120);
                    buf.writeUtf(r.revision(),64);
                    buf.writeUtf(r.path(),256);
                    buf.writeVarInt(r.functionIndex());
                    buf.writeUtf(r.operation(),20);
                    buf.writeUtf(r.options(),512);
                    buf.writeVarInt(r.min());
                    buf.writeVarInt(r.max());
                    buf.writeBoolean(r.onlyCompatible());
                },buf->new EditEnchantRuleRequest(buf.readUtf(120),buf.readUtf(64),
                        buf.readUtf(256),buf.readVarInt(),buf.readUtf(20),
                        buf.readUtf(512),buf.readVarInt(),buf.readVarInt(),
                        buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }

    public record Response(String json) implements CustomPacketPayload {
        public static final Type<Response> TYPE=
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"loot_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Response> CODEC=
                StreamCodec.of((buffer,packet)->buffer.writeUtf(packet.json(),32768),
                        buffer->new Response(buffer.readUtf(32768)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
