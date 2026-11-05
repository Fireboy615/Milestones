package net.fireboy.milestone.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-wide real-time wage index. Each index unit is one credit-millisecond;
 * dividing by 3,600,000 yields whole shop credits.
 *
 * Rate changes settle the previous rate first. This lets players earn correct
 * historical wages while offline (and while the server is shut down) without
 * requiring hourly tasks, player objects or background processes.
 */
public final class MilestonePassiveIncome extends SavedData {
    public static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final String FILE = "milestone_passive_income";
    private static final SavedData.Factory<MilestonePassiveIncome> FACTORY =
            new SavedData.Factory<>(MilestonePassiveIncome::new, MilestonePassiveIncome::load);

    private long lastUpdatedMs;
    private long totalCreditMillis;
    private int hourlyPay;
    private final Set<String> appliedRewards = new HashSet<>();

    public static MilestonePassiveIncome get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    private static MilestonePassiveIncome load(CompoundTag root, HolderLookup.Provider registries) {
        MilestonePassiveIncome income = new MilestonePassiveIncome();
        income.lastUpdatedMs = Math.max(0, root.getLong("LastUpdatedMs"));
        income.totalCreditMillis = Math.max(0, root.getLong("TotalCreditMillis"));
        income.hourlyPay = Math.clamp(root.getInt("HourlyPay"), 0, 1_000_000);
        for (Tag element : root.getList("AppliedStageRewards", Tag.TAG_COMPOUND)) {
            String key = ((CompoundTag) element).getString("Key");
            if (!key.isBlank()) income.appliedRewards.add(key);
        }
        return income;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putLong("LastUpdatedMs", lastUpdatedMs);
        root.putLong("TotalCreditMillis", totalCreditMillis);
        root.putInt("HourlyPay", hourlyPay);
        ListTag rewards = new ListTag();
        for (String key : appliedRewards) {
            CompoundTag row = new CompoundTag();
            row.putString("Key", key);
            rewards.add(row);
        }
        root.put("AppliedStageRewards", rewards);
        return root;
    }

    /** Advance only when consulted. Time includes offline players and downtime. */
    public void settleToNow() {
        long now = System.currentTimeMillis();
        if (lastUpdatedMs == 0) {
            lastUpdatedMs = now;
            setDirty();
            return;
        }
        if (now <= lastUpdatedMs) return; // Do not pay twice on a backward clock jump.
        long elapsed = now - lastUpdatedMs;
        long increase;
        try {
            increase = Math.multiplyExact(elapsed, (long) hourlyPay);
        } catch (ArithmeticException overflow) {
            increase = Long.MAX_VALUE - totalCreditMillis;
        }
        totalCreditMillis = Math.min(Long.MAX_VALUE,
                totalCreditMillis > Long.MAX_VALUE - increase
                        ? Long.MAX_VALUE : totalCreditMillis + increase);
        lastUpdatedMs = now;
        setDirty();
    }

    public long index() {
        settleToNow();
        return totalCreditMillis;
    }
    public int hourlyPay() { return hourlyPay; }

    /**
     * Each accepted stage reward may increase the pay rate only once for its
     * milestone UUID, threshold and reward index, even if progress is reset.
     */
    public boolean addHourlyPayOnce(String rewardKey, int increase) {
        if (increase <= 0 || !appliedRewards.add(rewardKey)) return false;
        settleToNow();
        hourlyPay = (int)Math.min(1_000_000L, (long) hourlyPay + increase);
        setDirty();
        return true;
    }
}
