package net.fireboy.milestone.world;

import net.fireboy.milestone.milestone.MilestoneType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One authoritative world-level record; restarting never plans or spawns duplicates. */
public final class WorldSiteSavedData extends SavedData {
    public static final String DATA_NAME = "milestone_world_sites";

    private boolean initialized;
    private BlockPos exchange;
    private BlockPos mission;
    private final List<CorePlan> cores = new ArrayList<>();

    public static WorldSiteSavedData create() { return new WorldSiteSavedData(); }

    public static WorldSiteSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        WorldSiteSavedData data = create();
        data.initialized = tag.getBoolean("Initialized");
        if (tag.getBoolean("ExchangePlaced")) data.exchange = BlockPos.of(tag.getLong("Exchange"));
        if (tag.getBoolean("MissionPlaced")) data.mission = BlockPos.of(tag.getLong("Mission"));
        for (Tag entry : tag.getList("Cores", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            MilestoneType type;
            try { type = MilestoneType.valueOf(t.getString("Type")); }
            catch (IllegalArgumentException e) { type = MilestoneType.GENERIC; }
            data.cores.add(new CorePlan(t.getInt("Tier"), t.getInt("X"), t.getInt("Z"),
                    type, t.getBoolean("Placed"), t.getBoolean("Placed")
                    ? BlockPos.of(t.getLong("PlacedPos")) : null));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putBoolean("Initialized", initialized);
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
            t.putBoolean("Placed", core.placed());
            if (core.placedPos() != null) t.putLong("PlacedPos", core.placedPos().asLong());
            list.add(t);
        }
        tag.put("Cores", list);
        return tag;
    }

    public boolean initialized() { return initialized; }
    public Optional<BlockPos> exchange() { return Optional.ofNullable(exchange); }
    public Optional<BlockPos> mission() { return Optional.ofNullable(mission); }
    public List<CorePlan> cores() { return List.copyOf(cores); }

    public void initialize(List<CorePlan> plans) {
        if (initialized) return;
        cores.addAll(plans);
        initialized = true;
        setDirty();
    }

    public void setExchange(BlockPos pos) { if (exchange == null) { exchange = pos.immutable(); setDirty(); } }
    public void setMission(BlockPos pos) { if (mission == null) { mission = pos.immutable(); setDirty(); } }

    public void setCorePlaced(int tier, BlockPos pos) {
        for (int i = 0; i < cores.size(); i++) {
            CorePlan core = cores.get(i);
            if (core.tier() == tier && !core.placed()) {
                cores.set(i, new CorePlan(core.tier(), core.x(), core.z(), core.type(), true, pos.immutable()));
                setDirty();
                break;
            }
        }
    }

    public record CorePlan(int tier, int x, int z, MilestoneType type, boolean placed, BlockPos placedPos) { }
}
