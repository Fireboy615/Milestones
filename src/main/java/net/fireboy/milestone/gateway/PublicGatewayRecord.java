package net.fireboy.milestone.gateway;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record PublicGatewayRecord(
        UUID id,
        UUID registeredBy,
        long createdGameTime,
        List<GatewayPortalArea> sides
) {
    public PublicGatewayRecord {
        sides = Collections.unmodifiableList(new ArrayList<>(sides));
    }

    public boolean contains(String dimension, BlockPos pos) {
        return sides.stream().anyMatch(side -> side.contains(dimension, pos));
    }

    public PublicGatewayRecord withOrReplaceSide(GatewayPortalArea newSide) {
        ArrayList<GatewayPortalArea> updated = new ArrayList<>();
        boolean inserted = false;

        for (GatewayPortalArea side : sides) {
            if (side.dimension().equals(newSide.dimension())) {
                if (!inserted) {
                    updated.add(newSide);
                    inserted = true;
                }
            } else {
                updated.add(side);
            }
        }

        if (!inserted) {
            updated.add(newSide);
        }

        return new PublicGatewayRecord(id, registeredBy, createdGameTime, updated);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (registeredBy != null) {
            tag.putUUID("RegisteredBy", registeredBy);
        }
        tag.putLong("CreatedGameTime", createdGameTime);

        ListTag sideList = new ListTag();
        for (GatewayPortalArea side : sides) {
            sideList.add(side.save());
        }
        tag.put("Sides", sideList);
        return tag;
    }

    public static PublicGatewayRecord load(CompoundTag tag) {
        UUID registeredBy = tag.hasUUID("RegisteredBy") ? tag.getUUID("RegisteredBy") : null;
        ArrayList<GatewayPortalArea> sides = new ArrayList<>();
        ListTag sideList = tag.getList("Sides", Tag.TAG_COMPOUND);
        for (Tag entry : sideList) {
            sides.add(GatewayPortalArea.load((CompoundTag) entry));
        }

        return new PublicGatewayRecord(
                tag.getUUID("Id"),
                registeredBy,
                tag.getLong("CreatedGameTime"),
                sides
        );
    }
}
