package net.fireboy.milestone.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Private persistent wallets; stored once per server, never trusted to the client. */
public final class MilestoneShopAccounts extends SavedData {
    private static final SavedData.Factory<MilestoneShopAccounts> FACTORY =
            new SavedData.Factory<>(MilestoneShopAccounts::new, MilestoneShopAccounts::load);
    private static final String FILE = "milestone_shop_accounts";
    private final Map<UUID,Integer> credits = new HashMap<>();
    private final Map<UUID,Integer> lifetimeEarned = new HashMap<>();

    public static MilestoneShopAccounts get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
    }
    private static MilestoneShopAccounts load(CompoundTag tag,HolderLookup.Provider registries) {
        MilestoneShopAccounts data=new MilestoneShopAccounts();
        for(Tag row:tag.getList("Wallets",Tag.TAG_COMPOUND)) {
            CompoundTag account=(CompoundTag)row;
            try {
                UUID player=account.getUUID("Player");
                data.credits.put(player,Math.max(0,account.getInt("Credits")));
                data.lifetimeEarned.put(player,Math.max(0,account.getInt("Lifetime")));
            } catch (Exception ignored) { }
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(var entry:credits.entrySet()) {
            CompoundTag account=new CompoundTag();
            account.putUUID("Player",entry.getKey());
            account.putInt("Credits",entry.getValue());
            account.putInt("Lifetime",lifetimeEarned.getOrDefault(entry.getKey(),0));
            list.add(account);
        }
        root.put("Wallets",list);
        return root;
    }
    public int balance(UUID player) {return credits.getOrDefault(player,0);}
    public int lifetime(UUID player) {return lifetimeEarned.getOrDefault(player,0);}
    public void earn(UUID player,int amount) {
        if(amount<=0)return;
        int added=Math.min(amount,Integer.MAX_VALUE-balance(player));
        credits.put(player,balance(player)+added);
        lifetimeEarned.put(player,Math.min(Integer.MAX_VALUE,
                lifetime(player)+Math.min(added,Integer.MAX_VALUE-lifetime(player))));
        setDirty();
    }
    /** Deduct exactly once; return false if the wallet cannot cover this purchase. */
    public boolean spend(UUID player,int cost) {
        if(cost<=0 || balance(player)<cost)return false;
        credits.put(player,balance(player)-cost);
        setDirty();
        return true;
    }
}
