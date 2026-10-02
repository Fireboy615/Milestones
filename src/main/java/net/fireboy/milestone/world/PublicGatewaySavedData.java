package net.fireboy.milestone.world;

import net.fireboy.milestone.gateway.PublicGatewayRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PublicGatewaySavedData extends SavedData {
    public static final String DATA_NAME = "milestone_public_gateways";

    private final Map<UUID, PublicGatewayRecord> gateways = new LinkedHashMap<>();

    public static PublicGatewaySavedData create() {
        return new PublicGatewaySavedData();
    }

    public static PublicGatewaySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PublicGatewaySavedData data = create();
        ListTag list = tag.getList("Gateways", Tag.TAG_COMPOUND);
        for (Tag entry : list) {
            PublicGatewayRecord record = PublicGatewayRecord.load((CompoundTag) entry);
            data.gateways.put(record.id(), record);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PublicGatewayRecord record : gateways.values()) {
            list.add(record.save());
        }
        tag.put("Gateways", list);
        return tag;
    }

    public Collection<PublicGatewayRecord> all() {
        return new ArrayList<>(gateways.values());
    }

    public Optional<PublicGatewayRecord> get(UUID id) {
        return Optional.ofNullable(gateways.get(id));
    }

    public void put(PublicGatewayRecord record) {
        gateways.put(record.id(), record);
        setDirty();
    }

    public boolean remove(UUID id) {
        if (gateways.remove(id) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public void clear() {
        if (!gateways.isEmpty()) {
            gateways.clear();
            setDirty();
        }
    }
}
