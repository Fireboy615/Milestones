package net.fireboy.milestone.world;

import net.fireboy.milestone.milestone.MilestoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** One authoritative world-level record; restarting never plans or spawns duplicates. */
public final class WorldSiteSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_world_sites";

    private boolean initialized;
    private BlockPos exchange;
    private BlockPos mission;
    private final List<CorePlan> cores = new ArrayList<>();
    /** Persisted one-time automatic tendril starts, independent of active plans. */
    private final Set<Integer> autoTendrilsStarted = new HashSet<>();
    /** Mark sites whose growth plan has been aged back to world creation. */
    private final Set<Integer> autoTendrilsWorldAged = new HashSet<>();

    public static WorldSiteSavedData create() { return new WorldSiteSavedData(); }

    public static WorldSiteSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        WorldSiteSavedData data = create();
        data.initialized = tag.getBoolean("Initialized");
        for (int tier : tag.getIntArray("AutoTendrilsStarted")) data.autoTendrilsStarted.add(tier);
        for (int tier : tag.getIntArray("AutoTendrilsWorldAged")) data.autoTendrilsWorldAged.add(tier);
        if (tag.getBoolean("ExchangePlaced")) data.exchange = BlockPos.of(tag.getLong("Exchange"));
        if (tag.getBoolean("MissionPlaced")) data.mission = BlockPos.of(tag.getLong("Mission"));
        for (Tag entry : tag.getList("Cores", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            MilestoneType type;
            try { type = MilestoneType.valueOf(t.getString("Type")); }
            catch (IllegalArgumentException e) { type = MilestoneType.GENERIC; }
            data.cores.add(new CorePlan(t.getInt("Tier"), t.getInt("X"), t.getInt("Z"),
                    type, t.getBoolean("Placed"), t.getBoolean("Placed")
                    ? BlockPos.of(t.getLong("PlacedPos")) : null,
                    t.contains("Dimension") ? t.getString("Dimension") : "minecraft:overworld"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putBoolean("Initialized", initialized);
        tag.putIntArray("AutoTendrilsStarted",
                autoTendrilsStarted.stream().sorted().mapToInt(Integer::intValue).toArray());
        tag.putIntArray("AutoTendrilsWorldAged",
                autoTendrilsWorldAged.stream().sorted().mapToInt(Integer::intValue).toArray());
        tag.putBoolean("ExchangePlaced", exchange != null);
        if (exchange != null) tag.putLong("Exchange", exchange.asLong());
        tag.putBoolean("MissionPlaced", mission != null);
        if (mission != null) tag.putLong("Mission", mission.asLong());
        ListTag list = new ListTag();
        for (CorePlan core : cores) {
            CompoundTag t = new CompoundTag();
            t.putInt("Tier", core.tier());
            t.putInt("X", core.x());
            t.putInt("Z", core.z());
            t.putString("Type", core.type().name());
            t.putString("Dimension", core.dimension());
            t.putBoolean("Placed", core.placed());
            if (core.placedPos() != null) t.putLong("PlacedPos", core.placedPos().asLong());
            list.add(t);
        }
        tag.put("Cores", list);
        return tag;
    }

    public boolean initialized() { return initialized; }
    public boolean autoTendrilStarted(int tier) { return autoTendrilsStarted.contains(tier); }
    public boolean autoTendrilWorldAged(int tier) { return autoTendrilsWorldAged.contains(tier); }
    public void markAutoTendrilWorldAged(int tier) {
        if (autoTendrilsWorldAged.add(tier)) setDirty();
    }
    public void markAutoTendrilStarted(int tier) {
        if (autoTendrilsStarted.add(tier)) setDirty();
    }
    public Optional<BlockPos> exchange() { return Optional.ofNullable(exchange); }
    public Optional<BlockPos> mission() { return Optional.ofNullable(mission); }
    public List<CorePlan> cores() { return List.copyOf(cores); }

    public void initialize(List<CorePlan> plans) {
        if (initialized) return;
        cores.addAll(plans);
        initialized = true;
        setDirty();
    }

    /** Reconcile only unplaced reservations. Existing placed Cores are immutable. */
    public void reconcile(List<CorePlan> desired, Set<Integer> configuredTiers) {
        boolean changed = cores.removeIf(core ->
                !core.placed() && !configuredTiers.contains(core.tier()));
        for (CorePlan candidate : desired) {
            if (cores.stream().noneMatch(existing -> existing.tier() == candidate.tier())) {
                cores.add(candidate);
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    public void setExchange(BlockPos pos) { if (exchange == null) { exchange = pos.immutable(); setDirty(); } }
    public void setMission(BlockPos pos) { if (mission == null) { mission = pos.immutable(); setDirty(); } }

    /** Move only an unplaced reservation; once a Core exists its location is immutable. */
    public void relocateUnplacedCore(int tier, int x, int z) {
        for (int i = 0; i < cores.size(); i++) {
            CorePlan core = cores.get(i);
            if (core.tier() == tier && !core.placed()
                    && (core.x() != x || core.z() != z)) {
                cores.set(i, new CorePlan(core.tier(), x, z, core.type(), false, null, core.dimension()));
                setDirty();
                break;
            }
        }
    }

    public void setCorePlaced(int tier, BlockPos pos) {
        for (int i = 0; i < cores.size(); i++) {
            CorePlan core = cores.get(i);
            if (core.tier() == tier && !core.placed()) {
                cores.set(i, new CorePlan(core.tier(), core.x(), core.z(), core.type(), true, pos.immutable(), core.dimension()));
                setDirty();
                break;
            }
        }
    }

    public record CorePlan(int tier, int x, int z, MilestoneType type,
                           boolean placed, BlockPos placedPos, String dimension) {
        public CorePlan(int tier, int x, int z, MilestoneType type,
                        boolean placed, BlockPos placedPos) {
            this(tier, x, z, type, placed, placedPos, "minecraft:overworld");
        }
    }
}
