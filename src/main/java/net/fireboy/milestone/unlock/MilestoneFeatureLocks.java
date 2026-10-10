package net.fireboy.milestone.unlock;

import com.google.gson.*;
import net.fireboy.milestone.milestone.*;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Server-wide feature locks with independently switchable recipe and player
 * machine-use restrictions. Never unregisters mod content or destroys machines. */
public final class MilestoneFeatureLocks {
    private static final Logger LOG = LoggerFactory.getLogger(MilestoneFeatureLocks.class);
    private static final Map<RecipeManager, List<RecipeHolder<?>>> ALL_RECIPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID,Long> WARNED = new HashMap<>();
    private static volatile List<Gate> gates = List.of();
    private MilestoneFeatureLocks() {}

    public record Gate(String id,String name,int core,boolean recipesLocked,boolean machinesLocked,
                       List<ResourceLocation> recipes,List<ResourceLocation> blocks) {
        public Gate { recipes=List.copyOf(recipes); blocks=List.copyOf(blocks); }
    }

    public static List<Gate> gates() { return gates; }

    /** Server-side catalog also includes temporarily locked/hidden recipe IDs. */
    public static List<ResourceLocation> allRecipeIds(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        List<RecipeHolder<?>> baseline = ALL_RECIPES.get(manager);
        if (baseline == null) baseline = List.copyOf(manager.getRecipes());
        return baseline.stream().map(RecipeHolder::id).sorted().toList();
    }

    public static List<Gate> parse(JsonObject root,Set<Integer> validCores) {
        if (!root.has("features")) return List.of();
        JsonArray array=root.getAsJsonArray("features");
        if(array.size()>80)throw new IllegalArgumentException("Maximum 80 feature gates");
        Set<String> names=new HashSet<>();
        List<Gate> parsed=new ArrayList<>();
        for(JsonElement element:array) {
            JsonObject o=element.getAsJsonObject();
            String id=string(o,"id"), name=string(o,"name");
            if(!id.matches("[a-z0-9_-]{1,64}") || !names.add(id))
                throw new IllegalArgumentException("Duplicate/invalid feature ID "+id);
            if(name.isBlank() || name.length()>80)
                throw new IllegalArgumentException("Invalid feature name for "+id);
            if(!o.has("unlock_core"))throw new IllegalArgumentException(id+": needs unlock_core");
            int core=o.get("unlock_core").getAsInt();
            if(!validCores.contains(core))throw new IllegalArgumentException(id+": unknown Core "+core);
            boolean recipesLock=flag(o,"lock_recipes"), machinesLock=flag(o,"lock_machine_use");
            List<ResourceLocation> recipes=ids(o,"recipes"),blocks=ids(o,"machine_blocks");
            if(recipesLock&&recipes.isEmpty())
                throw new IllegalArgumentException(id+": select recipes before enabling recipe lock");
            if(machinesLock&&blocks.isEmpty())
                throw new IllegalArgumentException(id+": select blocks before enabling machine lock");
            parsed.add(new Gate(id,name,core,recipesLock,machinesLock,recipes,blocks));
        }
        return List.copyOf(parsed);
    }
    private static String string(JsonObject o,String k) {
        return o.has(k)?o.get(k).getAsString():"";
    }
    private static boolean flag(JsonObject o,String k) {
        if(!o.has(k))return false;
        if(!o.get(k).isJsonPrimitive() || !o.get(k).getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(k+" must be boolean");
        return o.get(k).getAsBoolean();
    }
    private static List<ResourceLocation> ids(JsonObject o,String k) {
        if(!o.has(k))return List.of();
        JsonArray arr=o.getAsJsonArray(k);
        if(arr.size()>256)throw new IllegalArgumentException(k+" max 256 IDs");
        List<ResourceLocation> values=new ArrayList<>();
        for(JsonElement element:arr) {
            ResourceLocation id=ResourceLocation.tryParse(element.getAsString());
            if(id==null)throw new IllegalArgumentException("Invalid "+k+" ID: "+element);
            if(!values.contains(id))values.add(id);
        }
        return values;
    }
    public static void install(JsonObject root,Set<Integer> validCores) {
        gates=parse(root,validCores);
        LOG.info("Configured {} Milestone feature gates",gates.size());
    }
    private static boolean unlocked(MinecraftServer server, Gate gate) {
        if(!MilestoneContentConfig.enabled())return true;
        for(MilestoneRecord record:MilestoneManager.data(server).all())
            if(record.coreTier()==gate.core() && record.state()==MilestoneState.COMPLETED)
                return true;
        return false;
    }
    public static void onServerStarting(ServerStartingEvent event) {
        var snapshot=MilestoneContentConfig.server();
        JsonObject root=JsonParser.parseString(snapshot.payload()).getAsJsonObject()
                .getAsJsonObject("cores");
        install(root,snapshot.cores().keySet());
        applyRecipes(event.getServer(),true,false);
    }
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        applyRecipes(event.getPlayerList().getServer(),event.getPlayer()==null,false);
    }
    public static void applyRecipes(MinecraftServer server,boolean recapture,boolean syncPlayers) {
        if(server==null)return;
        RecipeManager manager=server.getRecipeManager();
        if(recapture || !ALL_RECIPES.containsKey(manager))
            ALL_RECIPES.put(manager,List.copyOf(manager.getRecipes()));
        List<RecipeHolder<?>> original=ALL_RECIPES.get(manager);
        Set<ResourceLocation> blocked=new HashSet<>();
        for(Gate gate:gates)
            if(gate.recipesLocked()&&!unlocked(server,gate))blocked.addAll(gate.recipes());
        List<RecipeHolder<?>> available=new ArrayList<>(original.size());
        for(RecipeHolder<?> recipe:original)
            if(!blocked.contains(recipe.id()))available.add(recipe);
        if(sameIds(manager.getRecipes(),available))return;
        manager.replaceRecipes(available);
        LOG.info("Milestone recipe locks: {}/{} hidden",original.size()-available.size(),original.size());
        if(syncPlayers) {
            var packet=new ClientboundUpdateRecipesPacket(available);
            for(ServerPlayer player:server.getPlayerList().getPlayers())
                player.connection.send(packet);
        }
    }
    private static boolean sameIds(Collection<RecipeHolder<?>> a,List<RecipeHolder<?>> b) {
        if(a.size()!=b.size())return false;
        Set<ResourceLocation> ids=new HashSet<>();
        for(var recipe:a)ids.add(recipe.id());
        for(var recipe:b)if(!ids.contains(recipe.id()))return false;
        return true;
    }
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        MinecraftServer server=player.getServer();
        if(server==null)return;
        ResourceLocation id=BuiltInRegistries.BLOCK.getKey(
                player.serverLevel().getBlockState(event.getPos()).getBlock());
        for(Gate gate:gates) {
            if(!gate.machinesLocked() || !gate.blocks().contains(id) || unlocked(server,gate))continue;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            long now=player.serverLevel().getGameTime();
            if(now-WARNED.getOrDefault(player.getUUID(),-1000L)>30) {
                WARNED.put(player.getUUID(),now);
                player.sendSystemMessage(Component.literal(gate.name()+" requires completion of Core "
                        +(gate.core()+1)).withStyle(ChatFormatting.RED));
            }
            return;
        }
    }
}
