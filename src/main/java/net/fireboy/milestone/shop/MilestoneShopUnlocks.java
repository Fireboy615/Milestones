package net.fireboy.milestone.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

/** Persistent server-wide and per-player unlock progress, independent of the
 * timed shop stock rotation. Only the server's configured objectives are tracked. */
public final class MilestoneShopUnlocks extends SavedData {
    private static final String FILE="milestone_shop_unlocks";
    private static final SavedData.Factory<MilestoneShopUnlocks> FACTORY =
            new SavedData.Factory<>(MilestoneShopUnlocks::new,MilestoneShopUnlocks::load);
    private final Map<String,Integer> global=new HashMap<>();
    private final Map<UUID,Map<String,Integer>> personal=new HashMap<>();
    // Mob farms can produce many deaths per second. Parse configured shop
    // unlocks only when the saved config payload actually changes.
    private static String lastConfig;
    private static List<MilestoneShop.UnlockCondition> configured=List.of();
    private static List<MilestoneShop.UnlockCondition> configuredConditions() {
        String payload=net.fireboy.milestone.server.config.MilestoneContentConfig
                .server().payload();
        if(!payload.equals(lastConfig)) {
            Map<String,MilestoneShop.UnlockCondition> unique=new LinkedHashMap<>();
            for(var offer:MilestoneShop.settings().offers())
                for(var condition:offer.conditions())
                    unique.putIfAbsent(condition.key(),condition);
            configured=List.copyOf(unique.values());
            lastConfig=payload;
        }
        return configured;
    }

    public static MilestoneShopUnlocks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,FILE);
    }
    private static MilestoneShopUnlocks load(CompoundTag tag,HolderLookup.Provider provider) {
        MilestoneShopUnlocks data=new MilestoneShopUnlocks();
        for(Tag item:tag.getList("Global",Tag.TAG_COMPOUND)){
            CompoundTag row=(CompoundTag)item;
            if(!row.getString("Key").isBlank())
                data.global.put(row.getString("Key"),Math.max(0,row.getInt("Progress")));
        }
        for(Tag item:tag.getList("Players",Tag.TAG_COMPOUND)){
            CompoundTag player=(CompoundTag)item;
            try{
                UUID id=player.getUUID("Player");
                Map<String,Integer> counters=new HashMap<>();
                for(Tag raw:player.getList("Progress",Tag.TAG_COMPOUND)){
                    CompoundTag row=(CompoundTag)raw;
                    if(!row.getString("Key").isBlank())
                        counters.put(row.getString("Key"),Math.max(0,row.getInt("Progress")));
                }
                data.personal.put(id,counters);
            }catch(Exception ignored){}
        }
        return data;
    }
    private static ListTag saveCounters(Map<String,Integer> entries) {
        ListTag rows=new ListTag();
        for(var entry:entries.entrySet()){
            CompoundTag row=new CompoundTag();
            row.putString("Key",entry.getKey());
            row.putInt("Progress",entry.getValue());
            rows.add(row);
        }
        return rows;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider provider) {
        root.put("Global",saveCounters(global));
        ListTag players=new ListTag();
        for(var entry:personal.entrySet()){
            CompoundTag row=new CompoundTag();
            row.putUUID("Player",entry.getKey());
            row.put("Progress",saveCounters(entry.getValue()));
            players.add(row);
        }
        root.put("Players",players);
        return root;
    }
    public int progress(ServerPlayer player,MilestoneShop.UnlockCondition condition) {
        int recorded=condition.global()?global.getOrDefault(condition.key(),0):
                personal.getOrDefault(player.getUUID(),Map.of()).getOrDefault(condition.key(),0);
        // Existing individual vanilla advancements continue to count when
        // an OP adds a new offer after the player has already earned one.
        if(!condition.global() && condition.type().equals("advancement")){
            var advancement=player.getServer().getAdvancements().get(condition.target());
            if(advancement!=null && player.getAdvancements()
                    .getOrStartProgress(advancement).isDone())return 1;
        }
        return recorded;
    }
    private void advance(ServerPlayer player,MilestoneShop.UnlockCondition condition){
        String key=condition.key();
        Map<String,Integer> store=condition.global()?global:
                personal.computeIfAbsent(player.getUUID(),ignored->new HashMap<>());
        int old=store.getOrDefault(key,0);
        // Do not cap at the first offer's target count: another offer
        // may require more kills for this same mob and unlock scope.
        if(old>=100000)return;
        int now=old+1;
        store.put(key,now);
        setDirty();
        if(now==condition.count()){
            // A global condition affects every buyer; no need to send
            // a separate packet to every closed shop.
            if(condition.global()){
                player.getServer().getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "Resonant Exchange unlocked globally: "+condition.type()+
                        " "+condition.target()),false);
            }else{
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Resonant Exchange unlocked: "+condition.type()+" "+condition.target()));
            }
        }
    }
    private static List<MilestoneShop.UnlockCondition> matching(
            String type,ResourceLocation target) {
        List<MilestoneShop.UnlockCondition> result=new ArrayList<>();
        for(var condition:configuredConditions())
            if(condition.type().equals(type)&&condition.target().equals(target))
                result.add(condition);
        return result;
    }
    public static void onLivingDeath(LivingDeathEvent event){
        if(!(event.getSource().getEntity() instanceof ServerPlayer player))return;
        if(!net.fireboy.milestone.server.config.MilestoneContentConfig.enabled())return;
        ResourceLocation type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(event.getEntity().getType());
        MilestoneShopUnlocks data=get(player.getServer());
        for(var condition:matching("kill",type))
            data.advance(player,condition);
    }
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        if(!net.fireboy.milestone.server.config.MilestoneContentConfig.enabled())return;
        MilestoneShopUnlocks data=get(player.getServer());
        for(var condition:matching("advancement",event.getAdvancement().id()))
            data.advance(player,condition);
    }
    /** Periodic in-world discovery check. Look only inside the player's loaded
     * chunk and only for structures required by configured offers. Does not
     * generate structures or teleport/search distant chunks. */
    public static void onPlayerTick(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player)
                || player.tickCount%100!=0)return;
        if(!net.fireboy.milestone.server.config.MilestoneContentConfig.enabled())return;
        var level=player.serverLevel();
        if(!level.hasChunkAt(player.blockPosition()))return;
        MilestoneShopUnlocks data=get(player.getServer());
        Map<String,MilestoneShop.UnlockCondition> relevant=new LinkedHashMap<>();
        for(var condition:configuredConditions())
            if(condition.type().equals("structure")
                    && data.progress(player,condition)<condition.count())
                relevant.putIfAbsent(condition.key(),condition);
        if(relevant.isEmpty())return;
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for(var condition:relevant.values()){
            Structure structure=registry.get(condition.target());
            if(structure==null)continue;
            // The structure start must be valid AND contain this player.
            var start=level.structureManager().getStructureAt(player.blockPosition(),structure);
            if(start.isValid() && start.getBoundingBox().isInside(player.blockPosition()))
                data.advance(player,condition);
        }
    }
}
