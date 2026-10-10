package net.fireboy.milestone.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Global timed stock and per-player reservations. A reservation holds the offer
 * across one reset; it does NOT deduct currency or guarantee future affordability.
 */
public final class MilestoneShopStock extends SavedData {
    private static final long PERIOD = 2L * 60L * 60L * 1000L;
    private static final int MAX_RESERVED = 2;
    private static final SavedData.Factory<MilestoneShopStock> FACTORY =
            new SavedData.Factory<>(MilestoneShopStock::new, MilestoneShopStock::load);
    private static final String KEY = "milestone_shop_stock";

    public record Hold(String id, long expires) {}

    private long cycle = 0L;
    private long nextReset = 0L;
    private final Map<UUID, LinkedHashMap<String,Long>> holds = new HashMap<>();
    /** Offers purchased by each player in the CURRENT reset window. */
    private final Map<UUID,Set<String>> purchased = new HashMap<>();

    public static MilestoneShopStock get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,KEY);
    }

    private static MilestoneShopStock load(CompoundTag tag, HolderLookup.Provider provider) {
        MilestoneShopStock data = new MilestoneShopStock();
        data.cycle = Math.max(0,tag.getLong("Cycle"));
        data.nextReset = Math.max(0,tag.getLong("NextReset"));
        for (Tag tagRow:tag.getList("Reservations",Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)tagRow;
            try {
                UUID owner=row.getUUID("Player");
                String id=row.getString("Offer");
                long expires=row.getLong("Expires");
                if (!id.isBlank() && expires>0)
                    data.holds.computeIfAbsent(owner,ignored->new LinkedHashMap<>()).put(id,expires);
            } catch (Exception ignored) {}
        }
        for(Tag rowTag:tag.getList("Bought",Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)rowTag;
            try {
                UUID owner=row.getUUID("Player");
                String id=row.getString("Offer");
                if(!id.isBlank())
                    data.purchased.computeIfAbsent(owner,k->new HashSet<>()).add(id);
            }catch(Exception ignored){}
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider provider) {
        root.putLong("Cycle",cycle);
        root.putLong("NextReset",nextReset);
        ListTag list=new ListTag();
        for (var player:holds.entrySet()) for (var held:player.getValue().entrySet()) {
            CompoundTag row=new CompoundTag();
            row.putUUID("Player",player.getKey());
            row.putString("Offer",held.getKey());
            row.putLong("Expires",held.getValue());
            list.add(row);
        }
        root.put("Reservations",list);
        ListTag purchasedList=new ListTag();
        for(var owner:purchased.entrySet())for(String id:owner.getValue()){
            CompoundTag row=new CompoundTag();
            row.putUUID("Player",owner.getKey());
            row.putString("Offer",id);
            purchasedList.add(row);
        }
        root.put("Bought",purchasedList);
        return root;
    }
    public void refresh() {
        long now=System.currentTimeMillis();
        if (nextReset==0) {
            nextReset=now+PERIOD;
            setDirty();
        } else if(now>=nextReset) {
            long passed=1+(now-nextReset)/PERIOD;
            cycle+=passed;
            nextReset+=passed*PERIOD;
            purchased.clear();
            setDirty();
        }
        boolean changed=false;
        for(var wallet:holds.values())
            if(wallet.entrySet().removeIf(row->row.getValue()<=now)) changed=true;
        if(changed)setDirty();
    }
    /** Operator-triggered restock; exactly the same cycle advance as the
     * automatic two-hour reset. Reset the countdown, clear per-player
     * purchase limits and keep any still-valid item reservations. */
    public void rerollNow() {
        refresh();
        cycle++;
        nextReset=System.currentTimeMillis()+PERIOD;
        purchased.clear();
        setDirty();
    }
    public long remainingMs() { refresh();return Math.max(0,nextReset-System.currentTimeMillis()); }
    public boolean boughtThisCycle(UUID player,String offerId) {
        refresh();
        return purchased.getOrDefault(player,Set.of()).contains(offerId);
    }
    public void recordPurchase(UUID player,String offerId) {
        refresh();
        if(purchased.computeIfAbsent(player,ignored->new HashSet<>()).add(offerId))
            setDirty();
    }

    /**
     * A stable, server-wide shuffled stock per cycle. Every timed reset changes
     * both WHICH offers are on sale and their order, even if a category has
     * fewer offers than its cap. Reloading the UI does not reroll stock.
     * Configuration limits apply separately to each rarity.
     */
    private List<MilestoneShop.Offer> pick(List<MilestoneShop.Offer> catalog,
                                           Map<String,Integer> caps,long rotation) {
        List<MilestoneShop.Offer> selected=new ArrayList<>();
        for(String rarity:MilestoneShop.RARITIES) {
            int cap=caps.getOrDefault(rarity,MilestoneShop.DEFAULT_LIMITS.get(rarity));
            if(cap<=0)continue;
            List<MilestoneShop.Offer> group=new ArrayList<>();
            for(var offer:catalog)
                if(rarity.equals(offer.rarity()))group.add(offer);
            // Stable for the entire reset window, reproducible across restarts.
            // Mix the reset number with the rarity to give each band a distinct
            // independent shuffle.
            long seed=0x5A0F5EEDL ^ ((rotation+1)*0x9E3779B97F4A7C15L)
                    ^ (long)rarity.hashCode()*0xBF58476D1CE4E5B9L;
            Collections.shuffle(group,new Random(seed));
            selected.addAll(group.subList(0,Math.min(cap,group.size())));
        }
        return List.copyOf(selected);
    }
    public List<MilestoneShop.Offer> active(List<MilestoneShop.Offer> catalog,
                                            Map<String,Integer> caps) {
        refresh();
        return pick(catalog,caps,cycle);
    }
    /** Preview the next shuffle, without making it purchasable. */
    public List<MilestoneShop.Offer> upcoming(List<MilestoneShop.Offer> catalog,
                                              Map<String,Integer> caps) {
        refresh();
        return pick(catalog,caps,cycle+1);
    }
    public List<String> reserved(UUID player) {
        refresh();
        return List.copyOf(holds.getOrDefault(player,new LinkedHashMap<>()).keySet());
    }
    public boolean isReserved(UUID player,String id) {
        refresh();
        return holds.getOrDefault(player,new LinkedHashMap<>()).containsKey(id);
    }
    public String reserve(UUID player,String id) {
        refresh();
        var playerHolds=holds.computeIfAbsent(player,ignored->new LinkedHashMap<>());
        if(playerHolds.containsKey(id)) {
            playerHolds.remove(id);
            setDirty();
            return "Reservation canceled.";
        }
        if(playerHolds.size()>=MAX_RESERVED)return "Both reservation slots are already full.";
        // One entire rotation after this one, to give time to return to the Exchange.
        playerHolds.put(id,nextReset+PERIOD);
        setDirty();
        return "Reserved until the following shop reset. No credits spent.";
    }
    public void consume(UUID player,String id) {
        var wallet=holds.get(player);
        if(wallet!=null && wallet.remove(id)!=null)setDirty();
    }
}
