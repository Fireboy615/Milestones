package net.fireboy.milestone.network;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.fireboy.milestone.worldgen.WorldSiteManager;
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
        registrar.playToClient(ConfigSnapshot.TYPE, ConfigSnapshot.CODEC, MilestoneClientboundBridge::handle);
        registrar.playToServer(ConfigSave.TYPE, ConfigSave.CODEC, MilestoneConfigNetwork::save);
        registrar.playToClient(ConfigResult.TYPE, ConfigResult.CODEC, MilestoneClientboundBridge::handle);
        registrar.playToServer(IdSearchRequest.TYPE, IdSearchRequest.CODEC, MilestoneConfigNetwork::idSearch);
        registrar.playToClient(IdSearchResults.TYPE, IdSearchResults.CODEC, MilestoneClientboundBridge::handle);
        registrar.playToServer(GenerationRequest.TYPE, GenerationRequest.CODEC,
                MilestoneConfigNetwork::generate);
        registrar.playToClient(GenerationStatus.TYPE, GenerationStatus.CODEC,
                MilestoneClientboundBridge::handle);
    }

    private static void generate(GenerationRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        if (!List.of("status", "all", "core", "shop", "station").contains(request.action())) return;
        if (request.tier() < -1 || request.tier() > 31) return;
        String message = WorldSiteManager.requestGeneration(
                player.getServer(), player.getUUID(), request.action(), request.tier());
        PacketDistributor.sendToPlayer(player,
                new GenerationStatus(WorldSiteManager.generationStatus(player.getServer(), message)));
    }

    public record GenerationRequest(String action, int tier) implements CustomPacketPayload {
        public static final Type<GenerationRequest> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "generate_world"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GenerationRequest> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GenerationRequest::action,
                        ByteBufCodecs.VAR_INT, GenerationRequest::tier, GenerationRequest::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record GenerationStatus(String json) implements CustomPacketPayload {
        public static final Type<GenerationStatus> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "generation_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GenerationStatus> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GenerationStatus::json,
                        GenerationStatus::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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



    private static void save(ConfigSave payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        String result = MilestoneContentConfig.saveBundle(payload.json(), player.getServer());
        boolean success = result.startsWith("Saved");
        PacketDistributor.sendToPlayer(player, new ConfigResult(success, result));
        if (success) PacketDistributor.sendToPlayer(player,
                new ConfigSnapshot(MilestoneContentConfig.server().payload()));
    }



    /** Operator-only, bounded remote lookup with shared token filters and
     * human-readable labels. Recipes stay searchable while locked. */
    private static void idSearch(IdSearchRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
        if (request.query().length() > 90) return;
        int offset=Math.min(100000,Math.max(0,request.offset()));
        int limit=Math.min(30,Math.max(1,request.limit()));
        String search=request.query().toLowerCase(Locale.ROOT);
        var parsed=MilestonePickerSearch.parse(search);
        String[] args=request.kind().split("/",2);
        if (args.length!=2) return;
        MilestonePickerSearch.Field field;
        try {field=MilestonePickerSearch.Field.valueOf(args[1].toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException exception){return;}
        record Match(String id,String name,String icon) {}
        List<Match> matches=new ArrayList<>();
        java.util.Map<String,net.minecraft.world.item.crafting.RecipeHolder<?>> recipeResults =
                new java.util.HashMap<>();
        if(args[0].equals("recipe")) {
            // Never remove recipes from the catalog: a locked recipe must remain browsable.
            for(var recipe:player.getServer().getRecipeManager().getRecipes()) {
                String id=recipe.id().toString(),name=prettyId(id);
                if(parsed.matches(id,name,field)) {
                    matches.add(new Match(id,name,""));
                    recipeResults.put(id,recipe);
                }
            }
        } else if(args[0].equals("recipe_mod")) {
            // A single modid:* selector applies to all current and future recipes.
            // Keep only the count and an example output icon, not 2,000+ IDs.
            java.util.Map<String,Integer> counts=new java.util.HashMap<>();
            java.util.Map<String,String> icons=new java.util.HashMap<>();
            for(var recipe:player.getServer().getRecipeManager().getRecipes()) {
                String namespace=recipe.id().getNamespace();
                counts.merge(namespace,1,Integer::sum);
                if(!icons.containsKey(namespace)) {
                    String icon=recipeIcon(recipe,player.getServer());
                    if(!icon.isBlank()) icons.put(namespace,icon);
                }
            }
            for(var entry:counts.entrySet()) {
                String namespace=entry.getKey();
                String modName=net.neoforged.fml.ModList.get().getModContainerById(namespace)
                        .map(container->container.getModInfo().getDisplayName())
                        .orElse(prettyId(namespace));
                String label=modName+" ("+entry.getValue()+" recipes)";
                String rule=namespace+":*";
                if(parsed.matches(rule,label,field))
                    matches.add(new Match(rule,label,icons.getOrDefault(namespace,"")));
            }
        } else if(args[0].equals("dimension")) {
            // Query the actual server level registry. This picks up installed
            // modded dimensions without maintaining a hardcoded list.
            for (var dimension : player.getServer().levelKeys()) {
                var location = dimension.location();
                String namespace = location.getNamespace();
                String mod = net.neoforged.fml.ModList.get()
                        .getModContainerById(namespace)
                        .map(container -> container.getModInfo().getDisplayName())
                        .orElse(prettyId(namespace));
                String name = prettyId(location.getPath()) + " (" + mod + ")";
                String icon = location.toString().equals("minecraft:the_nether")
                        ? "minecraft:obsidian"
                        : location.toString().equals("minecraft:the_end")
                            ? "minecraft:end_stone" : "minecraft:grass_block";
                if (parsed.matches(location.toString(), name, field))
                    matches.add(new Match(location.toString(), name, icon));
            }
        } else if(args[0].equals("advancement")) {
            for(var adv:player.getServer().getAdvancements().getAllAdvancements()) {
                String id=adv.id().toString();
                String name=adv.value().display()
                        .map(display->display.getTitle().getString())
                        .orElse(prettyId(id));
                if(parsed.matches(id,name,field))
                    matches.add(new Match(id,name,""));
            }
        } else return;
        matches.sort(java.util.Comparator.comparingInt(
                (Match m)->parsed.rank(m.id(),m.name()))
                .thenComparing(Match::name).thenComparing(Match::id));
        int end=Math.min(matches.size(),offset+limit);
        List<String> ids=new ArrayList<>(),names=new ArrayList<>(),icons=new ArrayList<>();
        for(int i=offset;i<end;i++) {
            Match match=matches.get(i);
            ids.add(match.id());
            names.add(match.name().length()>120?match.name().substring(0,120):match.name());
            var recipe=recipeResults.get(match.id());
            icons.add(recipe==null ? match.icon() : recipeIcon(recipe,player.getServer()));
        }
        PacketDistributor.sendToPlayer(player,new IdSearchResults(
                search,request.kind(),offset,matches.size(),ids,names,icons));
    }

    /** Best-effort visual for a recipe's result; never modifies its data. */
    private static String recipeIcon(net.minecraft.world.item.crafting.RecipeHolder<?> holder,
                                     net.minecraft.server.MinecraftServer server) {
        try {
            var stack=holder.value().getResultItem(server.registryAccess());
            if(stack.isEmpty()) return "";
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (RuntimeException | LinkageError error) {
            // Some modded processing recipes do not have a static item result.
            return "";
        }
    }

    private static String prettyId(String id) {
        String path=id.contains(":")?id.substring(id.indexOf(':')+1):id;
        path=path.replace('_',' ').replace('/',' ');
        if(path.isEmpty()) return id;
        StringBuilder name=new StringBuilder();
        for(String part:path.split(" ")) {
            if(part.isEmpty())continue;
            if(!name.isEmpty())name.append(' ');
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.toString();
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
    public record IdSearchResults(String query,String kind,int offset,int total,
                                  List<String> ids,List<String> names,List<String> icons)
            implements CustomPacketPayload {
        public static final Type<IdSearchResults> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID,"id_search_results"));
        public static final StreamCodec<RegistryFriendlyByteBuf,IdSearchResults> CODEC =
            StreamCodec.of((buffer,result)->{
                buffer.writeUtf(result.query(),90);
                buffer.writeUtf(result.kind(),24);
                buffer.writeVarInt(result.offset());
                buffer.writeVarInt(result.total());
                buffer.writeVarInt(result.ids().size());
                for(int i=0;i<result.ids().size();i++) {
                    buffer.writeUtf(result.ids().get(i),128);
                    buffer.writeUtf(result.names().get(i),128);
                    buffer.writeUtf(result.icons().get(i),128);
                }
            },buffer->{
                String q=buffer.readUtf(90),kind=buffer.readUtf(24);
                int offset=buffer.readVarInt(),total=buffer.readVarInt();
                int size=buffer.readVarInt();
                if(size<0||size>30)throw new IllegalArgumentException("Invalid ID search result count");
                List<String> ids=new ArrayList<>(),names=new ArrayList<>(),icons=new ArrayList<>();
                for(int i=0;i<size;i++) {
                    ids.add(buffer.readUtf(128));
                    names.add(buffer.readUtf(128));
                    icons.add(buffer.readUtf(128));
                }
                return new IdSearchResults(q,kind,offset,total,
                        List.copyOf(ids),List.copyOf(names),List.copyOf(icons));
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
