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
    /** First-join epoch, including players currently offline. */
    private final Map<UUID, Long> firstJoinMs = new HashMap<>();
    /** Cumulative global pay index consumed by each player's wallet. */
    private final Map<UUID, Long> wageClaimIndex = new HashMap<>();

    public static MilestoneShopAccounts get(MinecraftServer server) {
        MilestoneShopAccounts accounts =
                server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
        accounts.settleAllOffline(server);
        return accounts;
    }
    private static MilestoneShopAccounts load(CompoundTag tag,HolderLookup.Provider registries) {
        MilestoneShopAccounts data=new MilestoneShopAccounts();
        for(Tag row:tag.getList("Wallets",Tag.TAG_COMPOUND)) {
            CompoundTag account=(CompoundTag)row;
            try {
                UUID player=account.getUUID("Player");
                data.credits.put(player,Math.max(0,account.getInt("Credits")));
                data.lifetimeEarned.put(player,Math.max(0,account.getInt("Lifetime")));
                if (account.contains("FirstJoinMs"))
                    data.firstJoinMs.put(player, Math.max(0, account.getLong("FirstJoinMs")));
                if (account.contains("WageClaimIndex"))
                    data.wageClaimIndex.put(player, Math.max(0, account.getLong("WageClaimIndex")));
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
            if (firstJoinMs.containsKey(entry.getKey()))
                account.putLong("FirstJoinMs", firstJoinMs.get(entry.getKey()));
            if (wageClaimIndex.containsKey(entry.getKey()))
                account.putLong("WageClaimIndex", wageClaimIndex.get(entry.getKey()));
            list.add(account);
        }
        root.put("Wallets",list);
        return root;
    }
    /** Starts the wage clock on the first login, never on discovery or
     * config changes. Existing wallets migrate when their owner next logs in. */
    public void registerFirstJoin(MinecraftServer server, UUID player) {
        if (firstJoinMs.containsKey(player)) return;
        firstJoinMs.put(player, System.currentTimeMillis());
        wageClaimIndex.put(player, MilestonePassiveIncome.get(server).index());
        credits.putIfAbsent(player, 0);
        setDirty();
    }

    /** No hourly tick required. Reconcile every known first-joined wallet with
     * the real-time global index whenever shop/account data is requested. */
    private void settleAllOffline(MinecraftServer server) {
        if (firstJoinMs.isEmpty()) return;
        long current = MilestonePassiveIncome.get(server).index();
        for (UUID player : firstJoinMs.keySet()) {
            long marker = wageClaimIndex.getOrDefault(player, current);
            if (current <= marker) continue;
            long earned = (current - marker) / MilestonePassiveIncome.MILLIS_PER_HOUR;
            if (earned <= 0) continue;
            int room = Integer.MAX_VALUE - balance(player);
            int granted = (int)Math.min((long)room, earned);
            if (granted > 0) earn(player, granted);
            // Preserve fractional credits for future queries. If full, drop
            // unpayable credits rather than granting them again after purchases.
            long next = earned > room ? current
                    : marker + earned * MilestonePassiveIncome.MILLIS_PER_HOUR;
            wageClaimIndex.put(player, next);
            setDirty();
        }
    }

    public long firstJoinMs(UUID player) {
        return firstJoinMs.getOrDefault(player, 0L);
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
