package net.fireboy.milestone.milestone;

import com.simibubi.create.AllDataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.fireboy.milestone.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Station-only, repeatable way to recover lost Create schematics. These are
 * not consumed or used as progression credentials; verified world structures are.
 */
public final class StationSchematicClaims {
    private static final Map<String, Long> LAST_CLAIM = new HashMap<>();
    private static final int CLAIM_COOLDOWN_TICKS = 20 * 5;

    private StationSchematicClaims() { }

    public static void claim(ServerPlayer player, MilestoneRecord record) {
        if (record == null || record.discoveredBy() == null) return;
        String expected = CreateDiscoverySchematic.filename(record);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (!current.is(BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("create", "schematic")))) continue;
            if (expected.equals(current.get(AllDataComponents.SCHEMATIC_FILE))) {
                CreateDiscoverySchematic.sync(player, record);
                player.sendSystemMessage(Component.literal(
                        "You already have this Create schematic. Use it near the Mission Station.")
                        .withStyle(ChatFormatting.AQUA));
                return;
            }
        }

        String key = player.getUUID() + ":" + record.id();
        long now = player.level().getGameTime();
        Long last = LAST_CLAIM.get(key);
        if (last != null && now - last < CLAIM_COOLDOWN_TICKS) {
            player.sendSystemMessage(Component.literal(
                    "Wait a moment before reclaiming another schematic.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        try {
            ItemStack schematic = CreateDiscoverySchematic.create(player, record);
            // Upgrade any legacy v0.29 paper schematic in inventory instead of
            // leaving behind a duplicate placeholder.
            boolean converted = false;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack old = inventory.getItem(slot);
                if (!old.is(ModItems.DISCOVERY_SCHEMATIC.get())) continue;
                CompoundTag oldData = old.getOrDefault(
                        DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                if (oldData.hasUUID("MilestoneId")
                        && record.id().equals(oldData.getUUID("MilestoneId"))) {
                    inventory.setItem(slot, schematic);
                    converted = true;
                    break;
                }
            }
            if (!converted) inventory.placeItemBackInInventory(schematic);
            LAST_CLAIM.put(key, now);
            player.sendSystemMessage(Component.literal(
                    "Create schematic " + (converted ? "upgraded" : "claimed")
                            + ". Lost it? Return here to claim it again.")
                    .withStyle(ChatFormatting.GREEN));
        } catch (Exception exception) {
            player.sendSystemMessage(Component.literal(
                    "Couldn't prepare the Create schematic. Please try again.")
                    .withStyle(ChatFormatting.RED));
        }
    }
}
