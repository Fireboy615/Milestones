package net.fireboy.milestone.loot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.ChunkEvent;
import java.util.HashMap;
import java.util.Map;

/** Records vanilla chest loot-table origins before inventory generation,
 * without opening containers or generating rewards. */
public final class MilestoneLootHistory extends SavedData {
    private static final SavedData.Factory<MilestoneLootHistory> FACTORY =
            new SavedData.Factory<>(MilestoneLootHistory::new,MilestoneLootHistory::load);
    private static final String FILE="milestone_loot_origins";
    private static final int LIMIT=100000;
    private final Map<String,String> origins=new HashMap<>();

    public static MilestoneLootHistory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,FILE);
    }
    private static String key(ServerLevel level,BlockPos pos) {
        return level.dimension().location()+"|"+pos.asLong();
    }
    private static MilestoneLootHistory load(CompoundTag root,HolderLookup.Provider registries) {
        MilestoneLootHistory data=new MilestoneLootHistory();
        for(Tag element:root.getList("Records",Tag.TAG_COMPOUND)){
            CompoundTag row=(CompoundTag)element;
            if(!row.getString("Position").isBlank()&&!row.getString("Table").isBlank()
                    && data.origins.size()<LIMIT)
                data.origins.put(row.getString("Position"),row.getString("Table"));
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(var entry:origins.entrySet()){
            CompoundTag row=new CompoundTag();
            row.putString("Position",entry.getKey());
            row.putString("Table",entry.getValue());
            list.add(row);
        }
        root.put("Records",list);
        return root;
    }
    public ResourceLocation find(ServerLevel level,BlockPos pos) {
        String raw=origins.get(key(level,pos));
        return raw==null?null:ResourceLocation.tryParse(raw);
    }
    public void capture(ServerLevel level,BlockPos pos,ResourceLocation table) {
        if(table==null)return;
        String id=key(level,pos);
        if(origins.containsKey(id)||origins.size()>=LIMIT)return;
        origins.put(id,table.toString());
        setDirty();
    }
    public static void onChunkLoad(ChunkEvent.Load event){
        if(!(event.getLevel() instanceof ServerLevel level)
                ||!(event.getChunk() instanceof LevelChunk chunk))return;
        // NeoForge can fire chunk-load events while MinecraftServer is
        // still creating its levels. During that period overworld() is
        // null, so the shared SavedData storage is not available yet.
        // Do not capture history until startup has finished; unopened
        // containers retain their loot table and can still be inspected.
        MinecraftServer server=level.getServer();
        if(server==null || server.overworld()==null)return;
        MilestoneLootHistory history=get(server);
        for(BlockEntity entity:chunk.getBlockEntities().values()){
            if(entity instanceof RandomizableContainerBlockEntity container
                    && container.getLootTable()!=null)
                history.capture(level,entity.getBlockPos(),
                        container.getLootTable().location());
        }
    }
}
