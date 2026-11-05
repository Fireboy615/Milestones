package net.fireboy.milestone.loot;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Server-only administrative loot browser, inspector and datapack exporter.
 * All inputs are validated and source table IDs come from the live resource pack.
 * Never opens a container or generates a player's Lootr inventory. */
public final class MilestoneLootTools {
    private static final int MAX_SOURCE=1024*1024;
    private static final int MAX_PACKET_ROWS=8;
    private static final String DATAPACK="milestone_loot_editor";
    private MilestoneLootTools(){}

    private static ResourceLocation jsonFile(ResourceLocation table) {
        return ResourceLocation.fromNamespaceAndPath(table.getNamespace(),
                "loot_table/"+table.getPath()+".json");
    }
    private static List<ResourceLocation> cachedTables=List.of();
    private static List<ResourceLocation> all(MinecraftServer server) {
        var manager=server.getResourceManager();
        if(manager==cachedManager&&!cachedTables.isEmpty())return cachedTables;
        cachedManager=manager;
        CATEGORIES.clear();
        List<ResourceLocation> tables=new ArrayList<>();
        for(ResourceLocation resource:manager
                .listResources("loot_table",id->id.getPath().endsWith(".json")).keySet()){
            String path=resource.getPath();
            if(!path.startsWith("loot_table/")||!path.endsWith(".json"))continue;
            ResourceLocation id=ResourceLocation.fromNamespaceAndPath(resource.getNamespace(),
                    path.substring("loot_table/".length(),path.length()-5));
            tables.add(id);
        }
        tables.sort(Comparator.comparing(ResourceLocation::toString));
        cachedTables=List.copyOf(tables);
        return cachedTables;
    }
    private static boolean recognized(MinecraftServer server,ResourceLocation table) {
        return table!=null&&all(server).contains(table);
    }
    private static Path packRoot(MinecraftServer server){
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(DATAPACK)
                .toAbsolutePath().normalize();
    }
    private static Path overrideFile(MinecraftServer server,ResourceLocation table){
        return packRoot(server).resolve("data").resolve(table.getNamespace())
                .resolve("loot_table").resolve(table.getPath()+".json").normalize();
    }
    private static JsonObject readTable(MinecraftServer server,ResourceLocation table) throws IOException {
        if(!recognized(server,table))throw new IOException("No loaded loot table for "+table);
        Path existing=overrideFile(server,table),root=packRoot(server);
        // Include our previously written overrides even before /reload,
        // so a second edit can't accidentally erase the earlier addition.
        if(existing.startsWith(root)&&Files.isRegularFile(existing)&&!Files.isSymbolicLink(existing)) {
            if(Files.size(existing)>MAX_SOURCE)throw new IOException("Existing override is too large");
            try(Reader reader=Files.newBufferedReader(existing,StandardCharsets.UTF_8)){
                return JsonParser.parseReader(reader).getAsJsonObject();
            }catch(RuntimeException ex){throw new IOException("Invalid existing override JSON",ex);}
        }
        var file=server.getResourceManager().getResource(jsonFile(table));
        if(file.isEmpty())throw new IOException("No JSON resource for "+table);
        try(InputStream input=file.get().open()){
            byte[] bytes=input.readNBytes(MAX_SOURCE+1);
            if(bytes.length>MAX_SOURCE)throw new IOException("Loot table exceeds 1 MiB limit");
            try(Reader reader=new InputStreamReader(new ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8)){
                return JsonParser.parseReader(reader).getAsJsonObject();
            }catch(RuntimeException ex){throw new IOException("Invalid loot JSON: "+table,ex);}
        }
    }
    // Cache the classification for tables with unusual modded paths.
    // Reloading resources swaps the manager instance, invalidating the cache.
    private static net.minecraft.server.packs.resources.ResourceManager cachedManager;
    private static final Map<ResourceLocation,String> CATEGORIES=new HashMap<>();
    private static String lootCategory(MinecraftServer server,ResourceLocation id) {
        String path="/"+id.getPath().toLowerCase(Locale.ROOT)+"/";
        if(path.matches(".*/(?:chests?|containers?|loot_chests?|loot_containers?)/.*"))
            return "chests";
        if(path.matches(".*/blocks?/.*"))return "blocks";
        if(path.matches(".*/entities?/.*"))return "entities";
        if(path.matches(".*/fishing/.*"))return "fishing";
        // Some modded chest tables are named without a chests/ directory:
        // read their declared table type once, never on every scroll tick.
        var resources=server.getResourceManager();
        if(resources!=cachedManager){
            CATEGORIES.clear();
            cachedManager=resources;
        }
        String cached=CATEGORIES.get(id);
        if(cached!=null)return cached;
        String category="other";
        var resource=resources.getResource(jsonFile(id));
        if(resource.isPresent())try(InputStream input=resource.get().open();
                    com.google.gson.stream.JsonReader reader=
                        new com.google.gson.stream.JsonReader(
                            new InputStreamReader(input,StandardCharsets.UTF_8))){
            // Read the top-level table type, not a nested entry's "type".
            // Gson's streaming reader can skip nested pools without
            // loading entire modded loot tables into memory.
            reader.beginObject();
            while(reader.hasNext()){
                String name=reader.nextName();
                if(name.equals("type")){
                    String type=reader.nextString();
                    if(type.equals("minecraft:chest")||type.equals("chest"))
                        category="chests";
                    else if(type.equals("minecraft:entity")||type.equals("entity"))
                        category="entities";
                    else if(type.equals("minecraft:block")||type.equals("block"))
                        category="blocks";
                    else if(type.equals("minecraft:fishing")||type.equals("fishing"))
                        category="fishing";
                    break;
                }
                reader.skipValue();
            }
        }catch(IOException|RuntimeException ignored){}
        CATEGORIES.put(id,category);
        return category;
    }
    public static JsonObject browse(ServerPlayer player,String query,int offset,String requestedCategory,int requestedVisible) {
        JsonObject root=new JsonObject();
        if(!player.hasPermissions(2))return root;
        String category=List.of("chests","blocks","entities","fishing","other","all")
                .contains(requestedCategory)?requestedCategory:"chests";
        String needle=query.toLowerCase(Locale.ROOT);
        List<ResourceLocation> matches=new ArrayList<>();
        for(var id:all(player.getServer())){
            if(!id.toString().toLowerCase(Locale.ROOT).contains(needle))continue;
            if(!category.equals("all") && !lootCategory(player.getServer(),id).equals(category))
                continue;
            matches.add(id);
        }
        int rowsVisible=Math.max(3,Math.min(MAX_PACKET_ROWS,requestedVisible));
        int first=Math.min(Math.max(0,matches.size()-1)/rowsVisible*rowsVisible,
                Math.max(0,offset)/rowsVisible*rowsVisible);
        JsonArray rows=new JsonArray();
        for(int i=first;i<Math.min(matches.size(),first+rowsVisible);i++)
            rows.add(matches.get(i).toString());
        root.add("tables",rows);
        root.addProperty("total",matches.size());
        root.addProperty("offset",first);
        root.addProperty("query",query);
        root.addProperty("category",category);
        root.addProperty("lootr",ModList.get().isLoaded("lootr"));
        return root;
    }
    public static JsonObject describe(ServerPlayer player,String id) {
        JsonObject response=new JsonObject();
        if(!player.hasPermissions(2))return response;
        ResourceLocation table=ResourceLocation.tryParse(id);
        if(table==null){response.addProperty("error","Invalid loot table ID");return response;}
        try{
            JsonObject root=readTable(player.getServer(),table);
            response.addProperty("id",table.toString());
            JsonArray pools=root.has("pools")?root.getAsJsonArray("pools"):new JsonArray();
            response.addProperty("pools",pools.size());
            response.addProperty("original_type",root.has("type")
                    ?root.get("type").getAsString():"(unspecified)");
            JsonArray examples=new JsonArray();
            Set<String> unique=new LinkedHashSet<>();
            collectItems(root,unique,0);
            for(String item:unique){
                if(examples.size()>=96)break;
                examples.add(item);
            }
            response.add("entries",examples);
            response.addProperty("entry_count",unique.size());
            response.addProperty("override_staged",
                    Files.isRegularFile(overrideFile(player.getServer(),table)));
        }catch(Exception ex){
            response.addProperty("error",ex.getMessage()==null?"Could not read table":ex.getMessage());
        }
        return response;
    }
    private static void collectItems(JsonElement node,Set<String> result,int depth){
        if(node==null||depth>16||result.size()>2000)return;
        if(node.isJsonObject()){
            JsonObject object=node.getAsJsonObject();
            if(object.has("type")&&object.get("type").isJsonPrimitive()
                    &&object.get("type").getAsString().matches("(?:minecraft:)?item")
                    &&object.has("name"))
                result.add(object.get("name").getAsString());
            for(var field:object.entrySet())collectItems(field.getValue(),result,depth+1);
        }else if(node.isJsonArray()){
            for(JsonElement item:node.getAsJsonArray())collectItems(item,result,depth+1);
        }
    }
    private static ResourceLocation lootrTable(BlockEntity block) {
        if(!ModList.get().isLoaded("lootr")||block==null)return null;
        try{
            Class<?> api=Class.forName("noobanidus.mods.lootr.common.api.data.ILootrInfo");
            if(!api.isInstance(block))return null;
            Object result=api.getMethod("getInfoLootTable").invoke(block);
            if(result instanceof ResourceKey<?> key)return key.location();
        }catch(ReflectiveOperationException|LinkageError ignored){}
        return null;
    }
    public static JsonObject inspect(ServerPlayer player) {
        JsonObject result=new JsonObject();
        if(!player.hasPermissions(2))return result;
        HitResult hit=player.pick(6.0,0f,false);
        if(!(hit instanceof BlockHitResult target) || hit.getType()!=HitResult.Type.BLOCK){
            result.addProperty("error","Look directly at a chest or container within 6 blocks");
            return result;
        }
        return inspectAt(player,target.getBlockPos());
    }
    public static JsonObject inspectAt(ServerPlayer player,BlockPos pos) {
        JsonObject result=new JsonObject();
        if(!player.hasPermissions(2))return result;
        var level=player.serverLevel();
        if(!level.hasChunkAt(pos) || player.distanceToSqr(pos.getCenter())>49){
            result.addProperty("error","Container is too far away");
            return result;
        }
        BlockEntity block=level.getBlockEntity(pos);
        if(block==null){
            result.addProperty("error","That block is not a block-entity container");
            return result;
        }
        ResourceLocation table=lootrTable(block);
        String source=table==null?"":"Lootr API (original)";
        if(table==null && block instanceof RandomizableContainerBlockEntity loot
                && loot.getLootTable()!=null) {
            table=loot.getLootTable().location();
            source="Unopened container (confirmed)";
        }
        if(table!=null)MilestoneLootHistory.get(player.getServer()).capture(level,pos,table);
        if(table==null && block instanceof RandomizableContainerBlockEntity) {
            table=MilestoneLootHistory.get(player.getServer()).find(level,pos);
            if(table!=null)source="Historical record (may be stale if block replaced)";
        }
        result.addProperty("block",BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()).toString());
        result.addProperty("position",pos.getX()+", "+pos.getY()+", "+pos.getZ());
        result.addProperty("dimension",level.dimension().location().toString());
        result.addProperty("lootr",ModList.get().isLoaded("lootr")
                && block.getClass().getName().startsWith("noobanidus.mods."));
        if(table!=null) {
            result.addProperty("id",table.toString());
            result.addProperty("source",source);
        }else{
            result.addProperty("source","Not recorded");
            result.addProperty("error","No reliable table reference found. Inventory matching is not implemented yet.");
        }
        return result;
    }


    /** A bounded, index-addressed view of direct loot-pool entries. Nested
     * alternatives, tags, dynamic tables and modded entries are read-only;
     * they are never silently flattened or interpreted as simple items. */
    public static JsonObject poolPage(ServerPlayer player,String rawId,int requestedPool,int page,int requestedRows) {
        JsonObject result=new JsonObject();
        if(!player.hasPermissions(2))return result;
        ResourceLocation id=ResourceLocation.tryParse(rawId);
        if(id==null){
            result.addProperty("error","Invalid loot table ID");
            return result;
        }
        try {
            JsonObject root=readTable(player.getServer(),id);
            JsonArray pools=root.has("pools")?root.getAsJsonArray("pools"):new JsonArray();
            result.addProperty("id",id.toString());
            result.addProperty("pool_total",pools.size());
            result.addProperty("revision",fingerprint(root));
            result.addProperty("staged",Files.isRegularFile(overrideFile(player.getServer(),id)));
            if(pools.isEmpty()){
                result.add("rows",new JsonArray());
                result.addProperty("entry_total",0);
                return result;
            }
            int pool=Math.max(0,Math.min(pools.size()-1,requestedPool));
            JsonObject selected=pools.get(pool).getAsJsonObject();
            result.addProperty("pool",pool);
            if(selected.has("rolls"))
                result.addProperty("rolls",trim(selected.get("rolls").toString(),90));
            if(selected.has("bonus_rolls"))
                result.addProperty("bonus_rolls",trim(selected.get("bonus_rolls").toString(),90));
            result.addProperty("pool_conditions",selected.has("conditions"));
            JsonArray entries=selected.has("entries")
                    ?selected.getAsJsonArray("entries"):new JsonArray();
            result.addProperty("entry_total",entries.size());
            int visible=Math.max(2,Math.min(6,requestedRows));
            int first=Math.max(0,Math.min(Math.max(0,(entries.size()-1)/visible)*visible,
                    Math.max(0,page)*visible));
            result.addProperty("entry_offset",first);
            JsonArray rows=new JsonArray();
            for(int i=first;i<Math.min(entries.size(),first+visible);i++){
                JsonObject row=entries.get(i).getAsJsonObject();
                JsonObject view=new JsonObject();
                view.addProperty("index",i);
                String type=row.has("type")?row.get("type").getAsString():"unknown";
                view.addProperty("type",type);
                view.addProperty("name",trim(row.has("name")
                        ?row.get("name").getAsString():type,120));
                boolean direct=type.equals("minecraft:item")||type.equals("item");
                view.addProperty("direct_item",direct);
                view.addProperty("weight",row.has("weight")&&row.get("weight").isJsonPrimitive()
                        ?row.get("weight").getAsInt():1);
                view.addProperty("conditions",row.has("conditions"));
                view.addProperty("functions",row.has("functions"));
                EnchantInfo enchantInfo=analyzeEnchantments(row,selected,root);
                view.addProperty("enchant_editable",direct&&enchantInfo.editable());
                view.addProperty("enchant_note",enchantInfo.note());
                view.add("enchantments",enchantInfo.values());
                int[] range=readSimpleCount(row);
                view.addProperty("count_editable",direct&&range!=null);
                if(range!=null){
                    view.addProperty("min",range[0]);view.addProperty("max",range[1]);
                }
                rows.add(view);
            }
            result.add("rows",rows);
        }catch(Exception error){
            result.addProperty("error",trim(error.getMessage()==null
                    ?"Could not read loot pool":error.getMessage(),160));
        }
        return result;
    }
    private static String trim(String s,int max) {
        return s==null?"":s.length()>max?s.substring(0,max):s;
    }
    private static String fingerprint(JsonObject root) throws IOException {
        try{
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(byte b:bytes)hex.append(String.format("%02x",b&255));
            return hex.toString();
        }catch(java.security.NoSuchAlgorithmException exception){
            throw new IOException("SHA-256 is unavailable",exception);
        }
    }
    /** Returns null when the entry has nontrivial count logic.
     * Never destroy a mod's existing functions by trying to simplify them. */
    private static int[] readSimpleCount(JsonObject entry) {
        int[] value={1,1};
        if(!entry.has("functions"))return value;
        JsonArray functions=entry.getAsJsonArray("functions");
        boolean found=false;
        for(JsonElement element:functions){
            if(!element.isJsonObject())return null;
            JsonObject function=element.getAsJsonObject();
            if(!function.has("function"))continue;
            String name=function.get("function").getAsString();
            if(!name.equals("minecraft:set_count")&&!name.equals("set_count"))continue;
            if(found||function.has("conditions")||!function.has("count"))return null;
            found=true;
            JsonElement count=function.get("count");
            try{
                if(count.isJsonPrimitive()&&count.getAsJsonPrimitive().isNumber()){
                    int n=count.getAsInt();
                    if(n<1||n>64)return null;
                    value[0]=n;value[1]=n;
                }else if(count.isJsonObject()){
                    JsonObject range=count.getAsJsonObject();
                    if(!range.has("type")||!range.get("type").getAsString()
                            .matches("(?:minecraft:)?uniform"))return null;
                    int min=range.get("min").getAsInt(),max=range.get("max").getAsInt();
                    if(min<1||max>64||min>max)return null;
                    value[0]=min;value[1]=max;
                }else return null;
            }catch(RuntimeException invalid){return null;}
        }
        return value;
    }

    /** Fixed-level enchants are editable; random/modded enchant functions
     * stay untouched and are marked read-only. */
    private record EnchantInfo(boolean editable,String note,JsonObject values) {}
    private static boolean enchantFunction(JsonObject function) {
        if(!function.has("function")||!function.get("function").isJsonPrimitive())
            return false;
        String type=function.get("function").getAsString();
        if(type.equals("minecraft:set_enchantments")||type.equals("set_enchantments"))
            return true;
        if(type.startsWith("minecraft:enchant_")||type.startsWith("enchant_"))
            return true;
        if(type.equals("minecraft:set_components")||type.equals("set_components")){
            if(!function.has("components"))return false;
            String components=function.get("components").toString();
            return components.contains("enchantments");
        }
        return false;
    }
    private static boolean hasOuterEnchantmentFunctions(JsonObject owner) {
        if(!owner.has("functions")||!owner.get("functions").isJsonArray())
            return false;
        for(JsonElement element:owner.getAsJsonArray("functions"))
            if(element.isJsonObject()&&enchantFunction(element.getAsJsonObject()))
                return true;
        return false;
    }
    private static EnchantInfo analyzeEnchantments(JsonObject entry,JsonObject pool,JsonObject table) {
        JsonObject existing=new JsonObject();
        String type=entry.has("type")?entry.get("type").getAsString():"";
        if(!type.equals("minecraft:item")&&!type.equals("item"))
            return new EnchantInfo(false,"Only direct item entries can be enchanted.",existing);
        if(hasOuterEnchantmentFunctions(pool)||hasOuterEnchantmentFunctions(table))
            return new EnchantInfo(false,"Pool/table applies enchant functions; edit them separately.",existing);
        if(!entry.has("functions"))
            return new EnchantInfo(true,"No fixed enchantments yet.",existing);
        if(!entry.get("functions").isJsonArray())
            return new EnchantInfo(false,"Nonstandard loot functions.",existing);
        int fixed=0;
        for(JsonElement element:entry.getAsJsonArray("functions")){
            if(!element.isJsonObject())
                return new EnchantInfo(false,"Nonstandard loot function.",existing);
            JsonObject function=element.getAsJsonObject();
            if(!enchantFunction(function))continue;
            String name=function.get("function").getAsString();
            if(!name.equals("minecraft:set_enchantments")&&!name.equals("set_enchantments"))
                return new EnchantInfo(false,"Random/other enchant functions are read-only.",existing);
            if(++fixed>1||function.has("conditions")||
                    !function.has("enchantments")||!function.get("enchantments").isJsonObject())
                return new EnchantInfo(false,"Complex fixed enchantment function.",existing);
            for(var enchant:function.getAsJsonObject("enchantments").entrySet()){
                if(!enchant.getValue().isJsonPrimitive()
                        ||!enchant.getValue().getAsJsonPrimitive().isNumber())
                    return new EnchantInfo(false,"Randomized levels cannot be edited here.",existing);
                try{
                    int level=enchant.getValue().getAsInt();
                    if(level<1||level>255)
                        return new EnchantInfo(false,"Nonstandard fixed enchant level.",existing);
                    ResourceLocation enchantId=ResourceLocation.tryParse(enchant.getKey());
                    if(enchantId==null)return new EnchantInfo(false,
                            "Unrecognized enchantment ID in loot table.",existing);
                    existing.addProperty(enchantId.toString(),level);
                }catch(RuntimeException invalid){
                    return new EnchantInfo(false,"Nonstandard enchantment value.",existing);
                }
            }
        }
        return new EnchantInfo(true,existing.size()==0
                ?"No fixed enchantments yet.":"Fixed enchantments ready to edit.",existing);
    }

    /** A table-wide read-only inspector of enchantment-related loot functions.
     * It includes functions on the table, pools and nested entries. The
     * underlying JSON is NOT flattened into synthetic enchantment rewards.
     * It never rolls loot or opens an existing container. */
    public static JsonObject enchantRules(ServerPlayer player,String rawId,
                                          int requestedOffset,int requestedRows) {
        JsonObject result=new JsonObject();
        if(!player.hasPermissions(2))return result;
        ResourceLocation id=ResourceLocation.tryParse(rawId);
        if(id==null){result.addProperty("error","Invalid loot table ID");return result;}
        try{
            JsonObject root=readTable(player.getServer(),id);
            List<JsonObject> rules=new ArrayList<>();
            appendEnchantRuleRows(root,"Table","table",rules,0);
            if(root.has("pools")&&root.get("pools").isJsonArray()){
                JsonArray pools=root.getAsJsonArray("pools");
                for(int i=0;i<pools.size()&&rules.size()<1000;i++)
                    if(pools.get(i).isJsonObject())
                        appendEnchantRuleRows(pools.get(i).getAsJsonObject(),
                                "Pool "+(i+1),"table/pools/"+i,rules,0);
            }
            int total=rules.size();
            int pageSize=Math.max(2,Math.min(7,requestedRows));
            int first=Math.max(0,Math.min(requestedOffset/pageSize,
                    Math.max(0,(total-1)/pageSize)))*pageSize;
            JsonArray visible=new JsonArray();
            for(int i=first;i<Math.min(total,first+pageSize);i++)
                visible.add(rules.get(i));
            result.addProperty("id",id.toString());
            result.addProperty("revision",fingerprint(root));
            result.addProperty("offset",first);
            result.addProperty("total",total);
            result.addProperty("truncated",total>=1000);
            result.add("rules",visible);
        }catch(Exception error){
            result.addProperty("error","Unable to inspect table: "+
                    trim(error.getMessage()==null?"invalid JSON":error.getMessage(),150));
        }
        return result;
    }
    private static void appendEnchantRuleRows(JsonObject owner,String scope,String path,
                                               List<JsonObject> output,int depth) {
        if(depth>12||output.size()>=1000)return;
        if(owner.has("functions")&&owner.get("functions").isJsonArray()){
            JsonArray functions=owner.getAsJsonArray("functions");
            for(int i=0;i<functions.size()&&output.size()<1000;i++) {
                JsonElement element=functions.get(i);
                if(!element.isJsonObject())continue;
                JsonObject function=element.getAsJsonObject();
                if(!enchantFunction(function))continue;
                String functionName=function.has("function")?
                        function.get("function").getAsString():"unknown";
                JsonObject display=new JsonObject();
                display.addProperty("scope",trim(scope,140));
                display.addProperty("path",path);
                display.addProperty("function_index",i);
                appendRuleSettings(function,display);
                display.addProperty("type",trim(functionName,90));
                display.addProperty("summary",summarizeEnchantRule(function));
                display.addProperty("conditional",function.has("conditions"));
                String pretty=new GsonBuilder().setPrettyPrinting().create().toJson(function);
                boolean clipped=pretty.length()>2400;
                display.addProperty("details",clipped?pretty.substring(0,2400):pretty);
                display.addProperty("clipped",clipped);
                output.add(display);
            }
        }
        // Vanilla alternatives/sequence/group children; also supports
        // custom tables that attach nested entry arrays to pools or entries.
        for(String field:List.of("entries","children")){
            if(!owner.has(field)||!owner.get(field).isJsonArray())continue;
            JsonArray children=owner.getAsJsonArray(field);
            for(int i=0;i<children.size()&&output.size()<1000;i++){
                JsonElement element=children.get(i);
                if(!element.isJsonObject())continue;
                JsonObject child=element.getAsJsonObject();
                String name=child.has("name")&&child.get("name").isJsonPrimitive()
                        ?child.get("name").getAsString():
                        child.has("type")&&child.get("type").isJsonPrimitive()
                        ?child.get("type").getAsString():"entry";
                String childScope=scope+" > "+(field.equals("entries")?"Entry ":"Child ")
                        +(i+1)+" ("+trim(name,64)+")";
                appendEnchantRuleRows(child,childScope,path+"/"+field+"/"+i,
                        output,depth+1);
            }
        }
    }

    /** The settings returned here are for display, never trusted as a write. */
    private static void appendRuleSettings(JsonObject function,JsonObject display) {
        String name=function.get("function").getAsString();
        if(name.startsWith("minecraft:"))name=name.substring(10);
        display.addProperty("kind",name);
        boolean eligible=List.of("enchant_randomly","enchant_with_levels",
                "set_enchantments").contains(name);
        String note=eligible?"Edit settings and confirm before staging.":"Modded/complex rule is read-only.";
        if(eligible && function.has("conditions"))
            note="Conditional function: conditions are preserved.";
        if(name.equals("enchant_randomly")||name.equals("enchant_with_levels")){
            JsonElement options=function.get("options");
            String value="";
            if(options!=null){
                if(options.isJsonPrimitive()&&options.getAsJsonPrimitive().isString())
                    value=options.getAsString();
                else if(options.isJsonArray()){
                    StringJoiner join=new StringJoiner(", ");
                    for(JsonElement item:options.getAsJsonArray()){
                        if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString()){
                            eligible=false;note="Complex enchantment options; read-only.";
                            break;
                        }
                        join.add(item.getAsString());
                    }
                    value=join.toString();
                }else{eligible=false;note="Complex enchantment options; read-only.";}
            }
            if(value.length()>512){
                eligible=false;note="Too many enchantment options for this editor.";
            }
            display.addProperty("options",trim(value,512));
            boolean compatible=true;
            if(function.has("only_compatible")){
                JsonElement flag=function.get("only_compatible");
                if(!flag.isJsonPrimitive()||!flag.getAsJsonPrimitive().isBoolean()){
                    eligible=false;note="Complex compatibility setting; read-only.";
                }else compatible=flag.getAsBoolean();
            }
            display.addProperty("only_compatible",compatible);
            if(name.equals("enchant_with_levels")){
                if(!function.has("levels")){
                    eligible=false;note="Missing levels provider; read-only.";
                }else{
                    JsonElement levels=function.get("levels");
                    if(levels.isJsonPrimitive()&&levels.getAsJsonPrimitive().isNumber()){
                        display.addProperty("min",levels.getAsInt());
                        display.addProperty("max",levels.getAsInt());
                    }else if(levels.isJsonObject()){
                        JsonObject range=levels.getAsJsonObject();
                        String t=range.has("type")?range.get("type").getAsString():"";
                        if((t.equals("minecraft:uniform")||t.equals("uniform"))
                                &&range.has("min")&&range.has("max")
                                &&range.get("min").isJsonPrimitive()
                                &&range.get("max").isJsonPrimitive()){
                            display.addProperty("min",range.get("min").getAsInt());
                            display.addProperty("max",range.get("max").getAsInt());
                        }else{eligible=false;note="Advanced levels provider; read-only.";}
                    }else{eligible=false;note="Advanced levels provider; read-only.";}
                }
            }
        }
        if(name.equals("set_enchantments")){
            JsonObject fixed=function.has("enchantments")
                    &&function.get("enchantments").isJsonObject()
                    ?function.getAsJsonObject("enchantments"):null;
            if(fixed==null){eligible=false;note="Complex fixed enchantments; read-only.";}
            else{
                JsonObject simple=new JsonObject();
                for(var item:fixed.entrySet()){
                    if(!item.getValue().isJsonPrimitive()
                            ||!item.getValue().getAsJsonPrimitive().isNumber()){
                        eligible=false;note="Random level provider; read-only.";break;
                    }
                    int n=item.getValue().getAsInt();
                    if(n<1||n>255){eligible=false;note="Nonstandard level; read-only.";break;}
                    simple.addProperty(item.getKey(),n);
                }
                display.add("fixed",simple);
            }
        }
        display.addProperty("editable",eligible);
        display.addProperty("edit_note",note);
    }


    /** Edits a single verified vanilla enchantment function. Does not accept
     * arbitrary client JSON, filesystem paths, or modifications to conditions. */
    public static String editEnchantmentRule(ServerPlayer player,String rawTable,
            String expectedRevision,String path,int functionIndex,String operation,
            String options,int min,int max,boolean onlyCompatible) {
        if(!player.hasPermissions(2))return "Only operators may edit enchant rules.";
        ResourceLocation table=ResourceLocation.tryParse(rawTable);
        if(table==null||expectedRevision==null
                ||!expectedRevision.matches("[0-9a-f]{64}"))
            return "Invalid table or revision.";
        if(path==null||path.length()>256
                ||!path.matches("table(?:/(?:pools|entries|children)/[0-9]{1,4})*"))
            return "Invalid enchant rule location.";
        if(options==null||options.length()>512
                ||!List.of("random","levels","fixed_set","fixed_remove").contains(operation))
            return "Unsupported enchant rule change.";
        try{
            JsonObject root=readTable(player.getServer(),table);
            if(!fingerprint(root).equals(expectedRevision))
                return "Loot table has changed; refresh Enchant Rules before writing.";
            JsonObject owner=root;
            String[] segments=path.split("/");
            if(segments.length>27)return "Too many nested loot entries.";
            for(int i=1;i<segments.length;i+=2){
                String field=segments[i];
                if(!owner.has(field)||!owner.get(field).isJsonArray())
                    return "Loot rule path is no longer valid.";
                JsonArray array=owner.getAsJsonArray(field);
                int index=Integer.parseInt(segments[i+1]);
                if(index<0||index>=array.size()||!array.get(index).isJsonObject())
                    return "Loot rule path is out of date.";
                owner=array.get(index).getAsJsonObject();
            }
            if(!owner.has("functions")||!owner.get("functions").isJsonArray())
                return "No loot functions found at this location.";
            JsonArray functions=owner.getAsJsonArray("functions");
            if(functionIndex<0||functionIndex>=functions.size()
                    ||!functions.get(functionIndex).isJsonObject())
                return "Enchantment function index is out of date.";
            JsonObject target=functions.get(functionIndex).getAsJsonObject();
            JsonObject metadata=new JsonObject();
            appendRuleSettings(target,metadata);
            if(!metadata.get("editable").getAsBoolean())
                return "This rule uses unsupported settings and is read-only.";
            String kind=metadata.get("kind").getAsString();
            if(operation.equals("random")&&!kind.equals("enchant_randomly")
                    ||operation.equals("levels")&&!kind.equals("enchant_with_levels")
                    ||operation.startsWith("fixed_")&&!kind.equals("set_enchantments"))
                return "This action does not match the enchant function.";
            if(operation.equals("random")||operation.equals("levels")){
                if(operation.equals("levels")&&(min<1||max<min||max>100))
                    return "Enchantment power levels must be 1-100.";
                JsonElement parsedOptions=parseEnchantOptions(player,options);
                if(parsedOptions==null&&!options.isBlank())
                    return "Invalid enchant IDs or tag. Use comma-separated registered IDs or a #tag.";
                if(parsedOptions==null)target.remove("options");
                else target.add("options",parsedOptions);
                if(operation.equals("random"))
                    target.addProperty("only_compatible",onlyCompatible);
                else if(min==max)target.addProperty("levels",min);
                else{
                    JsonObject range=new JsonObject();
                    range.addProperty("type","minecraft:uniform");
                    range.addProperty("min",min);
                    range.addProperty("max",max);
                    target.add("levels",range);
                }
            }else{
                ResourceLocation enchant=ResourceLocation.tryParse(options.trim());
                var registry=player.registryAccess().registry(
                        net.minecraft.core.registries.Registries.ENCHANTMENT);
                if(enchant==null||registry.isEmpty()||!registry.get().containsKey(enchant))
                    return "Enchantment ID not registered on this server.";
                JsonObject values=target.getAsJsonObject("enchantments");
                String key=enchant.toString();
                for(String name:values.keySet())
                    if(enchant.equals(ResourceLocation.tryParse(name))){
                        key=name;break;
                    }
                if(operation.equals("fixed_remove")){
                    if(!values.has(key))
                        return "This rule does not contain "+enchant+".";
                    values.remove(key);
                    if(values.size()==0)functions.remove(functionIndex);
                }else{
                    int maximum=registry.get().get(enchant).getMaxLevel();
                    if(min<1||min>maximum)
                        return "Enchantment level must be 1-"+maximum+".";
                    if(!values.has(key)&&values.size()>=24)
                        return "Maximum 24 fixed enchantments in one function.";
                    values.addProperty(key,min);
                }
            }
            writeOverride(player.getServer(),table,root);
            return "Staged enchant rule change for "+table+". Run /reload to apply.";
        }catch(Exception exception){
            return "Enchant rule rejected: "+trim(exception.getMessage()==null
                    ?"Unexpected settings":exception.getMessage(),150);
        }
    }
    private static JsonElement parseEnchantOptions(ServerPlayer player,String raw) {
        String input=raw.trim();
        if(input.isEmpty())return null;
        var registry=player.registryAccess().registry(
                net.minecraft.core.registries.Registries.ENCHANTMENT);
        if(registry.isEmpty())return null;
        if(input.startsWith("#")){
            if(input.indexOf(',')>=0)return null;
            ResourceLocation tag=ResourceLocation.tryParse(input.substring(1));
            if(tag==null)return null;
            var key=net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ENCHANTMENT,tag);
            if(registry.get().getTag(key).isEmpty())return null;
            return new JsonPrimitive("#"+tag);
        }
        String[] names=input.split(",",-1);
        if(names.length>32)return null;
        JsonArray values=new JsonArray();
        java.util.Set<ResourceLocation> used=new java.util.HashSet<>();
        for(String name:names){
            ResourceLocation id=ResourceLocation.tryParse(name.trim());
            if(id==null||!registry.get().containsKey(id)||!used.add(id))
                return null;
            values.add(id.toString());
        }
        return names.length==1?values.get(0):values;
    }

    private static String summarizeEnchantRule(JsonObject function) {
        String type=function.has("function")?
                function.get("function").getAsString():"unknown";
        String name=type.startsWith("minecraft:")?type.substring(10):type;
        String summary=switch(name){
            case "set_enchantments" -> "Fixed enchantments";
            case "enchant_with_levels" -> "Random enchantments based on levels";
            case "enchant_randomly" -> "Random enchantment choice";
            case "set_components" -> "Enchantment item components";
            default -> "Modded / additional enchantment function";
        };
        if(name.equals("set_enchantments")&&function.has("enchantments")
                &&function.get("enchantments").isJsonObject()){
            JsonObject items=function.getAsJsonObject("enchantments");
            StringJoiner detail=new StringJoiner(", ");
            int n=0;
            for(var item:items.entrySet()){
                if(n++>=4)break;
                detail.add(item.getKey()+"="+trim(item.getValue().toString(),36));
            }
            summary+=": "+(detail.length()==0?"none":detail.toString());
            if(items.size()>4)summary+=" (+"+(items.size()-4)+" more)";
        }else if(function.has("levels")){
            summary+="; levels="+trim(function.get("levels").toString(),100);
        }else if(function.has("options")){
            summary+="; options="+trim(function.get("options").toString(),100);
        }
        if(function.has("conditions"))summary+=" [conditions]";
        return trim(summary,280);
    }

    /** Changes exactly one entry's fixed enchantment function, preserving
     * every unrelated function, pool condition and datapack JSON field. */
    public static String editEnchantment(ServerPlayer player,String rawId,String revision,
            int poolIndex,int entryIndex,String rawEnchant,int requestedLevel,boolean remove) {
        if(!player.hasPermissions(2))return "Only operators may edit loot.";
        ResourceLocation table=ResourceLocation.tryParse(rawId);
        ResourceLocation enchant=ResourceLocation.tryParse(rawEnchant);
        if(table==null||enchant==null||revision==null||!revision.matches("[0-9a-f]{64}"))
            return "Invalid loot table, enchantment, or edit revision.";
        var registry=player.registryAccess().registry(net.minecraft.core.registries.Registries.ENCHANTMENT);
        if(registry.isEmpty()||!registry.get().containsKey(enchant))
            return "Enchantment does not exist on this server.";
        if(!remove) {
            int maximum=registry.get().get(enchant).getMaxLevel();
            if(requestedLevel<1||requestedLevel>maximum)
                return "Level must be 1-"+maximum+" for "+enchant+".";
        }
        try{
            JsonObject root=readTable(player.getServer(),table);
            if(!fingerprint(root).equals(revision))
                return "Loot table changed; refresh the pool before editing enchantments.";
            JsonArray pools=root.getAsJsonArray("pools");
            if(poolIndex<0||poolIndex>=pools.size())return "Invalid loot pool.";
            JsonObject pool=pools.get(poolIndex).getAsJsonObject();
            JsonArray entries=pool.getAsJsonArray("entries");
            if(entryIndex<0||entryIndex>=entries.size())return "Invalid item entry.";
            JsonObject entry=entries.get(entryIndex).getAsJsonObject();
            EnchantInfo info=analyzeEnchantments(entry,pool,root);
            if(!info.editable())return info.note();
            if(remove&&!info.values().has(enchant.toString()))
                return "This entry does not have that fixed enchantment.";
            if(!remove&&!info.values().has(enchant.toString())&&info.values().size()>=24)
                return "Maximum 24 fixed enchantments per entry.";
            JsonArray functions=entry.has("functions")
                    ?entry.getAsJsonArray("functions"):new JsonArray();
            int functionIndex=-1;
            for(int i=0;i<functions.size();i++){
                JsonObject function=functions.get(i).getAsJsonObject();
                if(function.has("function")){
                    String type=function.get("function").getAsString();
                    if(type.equals("minecraft:set_enchantments")||type.equals("set_enchantments"))
                        functionIndex=i;
                }
            }
            if(functionIndex>=0){
                JsonObject fixed=functions.get(functionIndex).getAsJsonObject();
                JsonObject values=fixed.getAsJsonObject("enchantments");
                // Preserve the spelling of existing keys (some datapacks
                // omit the minecraft: namespace) without making duplicates.
                String actual=enchant.toString();
                for(String candidate:values.keySet()){
                    if(enchant.equals(ResourceLocation.tryParse(candidate))){
                        actual=candidate;
                        break;
                    }
                }
                if(remove)values.remove(actual);
                else values.addProperty(actual,requestedLevel);
                if(values.size()==0)functions.remove(functionIndex);
            }else if(!remove){
                JsonObject fixed=new JsonObject();
                fixed.addProperty("function","minecraft:set_enchantments");
                JsonObject values=new JsonObject();
                values.addProperty(enchant.toString(),requestedLevel);
                fixed.add("enchantments",values);
                functions.add(fixed);
            }
            if(functions.isEmpty())entry.remove("functions");
            else entry.add("functions",functions);
            writeOverride(player.getServer(),table,root);
            return "Staged "+(remove?"removal of ":"level "+requestedLevel+" for ")
                    +enchant+" in "+table+". Run /reload to apply.";
        }catch(Exception error){
            return "Enchant edit rejected: "+trim(error.getMessage()==null
                    ?"check logs":error.getMessage(),160);
        }
    }

    /** Mutates only one vanilla-format, direct item entry. All untouched
     * pools, conditions, functions and NBT are preserved by deep copy. */
    public static String editEntry(ServerPlayer player,String rawId,String expectedRevision,
                                   String operation,int poolIndex,int entryIndex,
                                   int min,int max) {
        if(!player.hasPermissions(2))return "Only operators may edit loot.";
        if(!List.of("set_weight","set_count","remove_entry").contains(operation))
            return "Unsupported edit.";
        ResourceLocation id=ResourceLocation.tryParse(rawId);
        if(id==null||expectedRevision==null||!expectedRevision.matches("[0-9a-f]{64}"))
            return "Invalid table or edit revision.";
        try{
            JsonObject root=readTable(player.getServer(),id);
            if(!fingerprint(root).equals(expectedRevision))
                return "Loot table changed since you opened it. Refresh before editing.";
            JsonArray pools=root.getAsJsonArray("pools");
            if(poolIndex<0||poolIndex>=pools.size())return "Invalid pool selection.";
            JsonObject pool=pools.get(poolIndex).getAsJsonObject();
            JsonArray entries=pool.getAsJsonArray("entries");
            if(entryIndex<0||entryIndex>=entries.size())return "Invalid entry selection.";
            JsonObject entry=entries.get(entryIndex).getAsJsonObject();
            String type=entry.has("type")?entry.get("type").getAsString():"";
            if(!type.equals("minecraft:item")&&!type.equals("item"))
                return "This entry is complex; editing it is not supported yet.";
            if(operation.equals("remove_entry")) {
                if(entries.size()<=1)return "Cannot remove the last entry from a pool.";
                entries.remove(entryIndex);
            }else if(operation.equals("set_weight")){
                if(min<1||min>1000)return "Weight must be 1 to 1000.";
                if(entry.has("weight")&&!entry.get("weight").isJsonPrimitive())
                    return "This entry has a nonstandard weight.";
                entry.addProperty("weight",min);
            }else{
                if(min<1||max<min||max>64)return "Counts must be 1 to 64.";
                if(readSimpleCount(entry)==null)
                    return "Count uses special functions; refusing to overwrite it.";
                JsonArray functions=entry.has("functions")
                        ?entry.getAsJsonArray("functions"):new JsonArray();
                int setCountAt=-1;
                for(int i=0;i<functions.size();i++) {
                    JsonObject fun=functions.get(i).getAsJsonObject();
                    String name=fun.has("function")?fun.get("function").getAsString():"";
                    if(name.equals("minecraft:set_count")||name.equals("set_count"))
                        setCountAt=i;
                }
                JsonObject setCount=new JsonObject();
                setCount.addProperty("function","minecraft:set_count");
                if(min==max)setCount.addProperty("count",min);
                else{
                    JsonObject range=new JsonObject();
                    range.addProperty("type","minecraft:uniform");
                    range.addProperty("min",min);
                    range.addProperty("max",max);
                    setCount.add("count",range);
                }
                if(setCountAt>=0)functions.set(setCountAt,setCount);
                else functions.add(setCount);
                entry.add("functions",functions);
            }
            writeOverride(player.getServer(),id,root);
            return "Staged "+operation+" for "+id+". Run /reload to apply.";
        }catch(Exception error){
            return "Edit rejected: "+trim(error.getMessage(),160);
        }
    }
    /**
     * Adds a normal item entry to an EXISTING pool; unlike addBonus this
     * shares the pool's roll count, pool-level conditions and competitors.
     * Never changes any existing entries, conditions or modded functions.
     */
    public static String addItemEntry(ServerPlayer player,String rawTable,
                                      String expectedRevision,String rawItem,
                                      int poolIndex,int weight,int min,int max) {
        if(!player.hasPermissions(2))return "Only operators may edit loot.";
        ResourceLocation table=ResourceLocation.tryParse(rawTable);
        ResourceLocation item=ResourceLocation.tryParse(rawItem);
        if(table==null||item==null
                ||!BuiltInRegistries.ITEM.containsKey(item)
                ||BuiltInRegistries.ITEM.get(item)==Items.AIR)
            return "Invalid loot-table or registered item ID.";
        if(expectedRevision==null||!expectedRevision.matches("[0-9a-f]{64}"))
            return "Invalid table revision; reopen Edit Pools.";
        if(weight<1||weight>1000||min<1||max<min||max>64)
            return "Weight must be 1-1000 and count between 1 and 64.";
        try{
            JsonObject root=readTable(player.getServer(),table);
            if(!fingerprint(root).equals(expectedRevision))
                return "Loot table changed while you were editing. Refresh the pool first.";
            if(!root.has("pools")||!root.get("pools").isJsonArray())
                return "This table has no editable loot pools.";
            JsonArray pools=root.getAsJsonArray("pools");
            if(poolIndex<0||poolIndex>=pools.size())
                return "That loot pool no longer exists.";
            JsonObject pool=pools.get(poolIndex).getAsJsonObject();
            if(!pool.has("entries")||!pool.get("entries").isJsonArray())
                return "This pool has no standard entry list.";
            JsonArray entries=pool.getAsJsonArray("entries");
            if(entries.size()>=256)
                return "This pool is full (256 entries maximum).";
            for(JsonElement previous:entries){
                if(!previous.isJsonObject())continue;
                JsonObject row=previous.getAsJsonObject();
                if(row.has("type")&&row.has("name")
                        &&("minecraft:item".equals(row.get("type").getAsString())
                            ||"item".equals(row.get("type").getAsString()))
                        &&item.toString().equals(row.get("name").getAsString())
                        &&!row.has("conditions"))
                    return "This item already has an unconditional entry in this pool; edit it instead.";
            }
            JsonObject entry=new JsonObject();
            entry.addProperty("type","minecraft:item");
            entry.addProperty("name",item.toString());
            entry.addProperty("weight",weight);
            if(min!=1||max!=1) {
                JsonObject count=new JsonObject();
                count.addProperty("function","minecraft:set_count");
                if(min==max)count.addProperty("count",min);
                else {
                    JsonObject uniform=new JsonObject();
                    uniform.addProperty("type","minecraft:uniform");
                    uniform.addProperty("min",min);
                    uniform.addProperty("max",max);
                    count.add("count",uniform);
                }
                JsonArray functions=new JsonArray();
                functions.add(count);
                entry.add("functions",functions);
            }
            entries.add(entry);
            writeOverride(player.getServer(),table,root);
            return "Staged new "+item+" in pool "+(poolIndex+1)+
                    " of "+table+". Run /reload to apply.";
        }catch(Exception ex){
            return "Entry rejected: "+trim(ex.getMessage()==null
                    ?"Could not edit loot table":ex.getMessage(),160);
        }
    }

    public static String restoreOriginal(ServerPlayer player,String rawId,
                                         String expectedRevision) {
        if(!player.hasPermissions(2))return "Only operators may edit loot.";
        ResourceLocation id=ResourceLocation.tryParse(rawId);
        if(id==null||expectedRevision==null||!expectedRevision.matches("[0-9a-f]{64}"))
            return "Invalid table or edit revision.";
        try{
            JsonObject root=readTable(player.getServer(),id);
            if(!fingerprint(root).equals(expectedRevision))
                return "Loot table changed. Refresh before restoring.";
            Path file=overrideFile(player.getServer(),id),pack=packRoot(player.getServer());
            if(!file.startsWith(pack)||!Files.isRegularFile(file)
                    ||Files.isSymbolicLink(file))return "No Milestone override to restore.";
            backupFile(player.getServer(),id,file);
            Files.delete(file);
            return "Milestone override removed; original datapack table returns after /reload.";
        }catch(Exception error){
            return "Restore rejected: "+trim(error.getMessage(),160);
        }
    }
    private static void backupFile(MinecraftServer server,ResourceLocation id,Path file)
            throws IOException {
        Path backup=server.getWorldPath(LevelResource.ROOT)
                .resolve("milestone-loot-backups")
                .resolve(id.getNamespace())
                .resolve(id.getPath()+"-"+System.currentTimeMillis()+".json").normalize();
        Files.createDirectories(backup.getParent());
        if(!Files.isSymbolicLink(backup.getParent()))
            Files.copy(file,backup,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void writeOverride(MinecraftServer server,ResourceLocation id,JsonObject root)
            throws IOException {
        Path pack=packRoot(server),file=overrideFile(server,id);
        if(!file.startsWith(pack))throw new IOException("Invalid world datapack path");
        Files.createDirectories(file.getParent());
        Path walk=file.getParent();
        while(walk!=null&&walk.startsWith(pack)){
            if(Files.isSymbolicLink(walk))throw new IOException("Symlink datapack path rejected");
            walk=walk.getParent();
        }
        if(Files.isSymbolicLink(file))throw new IOException("Symlink loot-table override rejected");
        Path meta=pack.resolve("pack.mcmeta");
        if(Files.isSymbolicLink(meta))throw new IOException("Symlink pack.mcmeta rejected");
        if(!Files.exists(meta))
            Files.writeString(meta,"{\"pack\":{\"pack_format\":48,"+
                    "\"description\":\"Milestone OP loot editor\"}}\n",
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        if(Files.exists(file))backupFile(server,id,file);
        Path temp=Files.createTempFile(file.getParent(),"milestone-loot-",".tmp");
        try{
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(root)+"\n",
                    StandardCharsets.UTF_8);
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){
                Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
            }
        }finally{Files.deleteIfExists(temp);}
    }

    /** Writes an additive bonus pool to the world datapack; it never mutates
     * the source mod or currently open chests, and takes effect after /reload. */
    public static String addBonus(ServerPlayer player,String rawTable,String rawItem,
            int chancePercent,int min,int max) {
        if(!player.hasPermissions(2))return "Only operators may edit loot.";
        ResourceLocation table=ResourceLocation.tryParse(rawTable);
        ResourceLocation item=ResourceLocation.tryParse(rawItem);
        if(table==null||item==null||!BuiltInRegistries.ITEM.containsKey(item)
                ||BuiltInRegistries.ITEM.get(item)==Items.AIR)
            return "Invalid table or item ID.";
        if(chancePercent<1||chancePercent>100||min<1||max<min||max>64)
            return "Use chance 1-100%, and item counts 1-64.";
        try{
            JsonObject root=readTable(player.getServer(),table);
            JsonArray pools=root.has("pools")?root.getAsJsonArray("pools"):new JsonArray();
            if(pools.size()>=100)return "Too many loot pools in this table.";
            JsonObject bonus=new JsonObject();
            bonus.addProperty("rolls",1);
            if(chancePercent<100){
                JsonArray conditions=new JsonArray();
                JsonObject condition=new JsonObject();
                condition.addProperty("condition","minecraft:random_chance");
                condition.addProperty("chance",chancePercent/100.0f);
                conditions.add(condition);
                bonus.add("conditions",conditions);
            }
            JsonArray entries=new JsonArray();
            JsonObject entry=new JsonObject();
            entry.addProperty("type","minecraft:item");
            entry.addProperty("name",item.toString());
            if(min!=1||max!=1){
                JsonArray functions=new JsonArray();
                JsonObject count=new JsonObject();
                count.addProperty("function","minecraft:set_count");
                if(min==max)count.addProperty("count",min);
                else{
                    JsonObject range=new JsonObject();
                    range.addProperty("type","minecraft:uniform");
                    range.addProperty("min",min);
                    range.addProperty("max",max);
                    count.add("count",range);
                }
                functions.add(count);
                entry.add("functions",functions);
            }
            entries.add(entry);
            bonus.add("entries",entries);
            pools.add(bonus);
            root.add("pools",pools);
            writeOverride(player.getServer(),table,root);
            return "Wrote datapack override for "+table+". Run /reload (enable file/milestone_loot_editor if needed).";
        }catch(Exception ex){
            return "Datapack edit failed: "+(ex.getMessage()==null?"check logs":ex.getMessage());
        }
    }
}
