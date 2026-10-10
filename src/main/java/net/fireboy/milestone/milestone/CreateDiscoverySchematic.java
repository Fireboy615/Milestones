package net.fireboy.milestone.milestone;

import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.foundation.utility.CreatePaths;
import net.fireboy.milestone.network.CreateSchematicNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Actual Create 6.0.10 filled schematics, not a retextured paper item.
 *
 * The 5x5 chamber structure intentionally uses a chiseled deepslate marker
 * in place of the unbreakable, registered Milestone Core. A schematicannon
 * must never be able to duplicate a progression Core.
 */
public final class CreateDiscoverySchematic {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateDiscoverySchematic.class);

    private CreateDiscoverySchematic() { }

    public static String filename(MilestoneRecord record) {
        return "milestone-" + record.id() + ".nbt";
    }

    private static String owner(MilestoneRecord record) {
        return record.discoveredBy().toString();
    }

    private static byte[] blueprintBytes() throws IOException {
        CompoundTag structure = new CompoundTag();
        structure.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        structure.put("size", integers(5, 5, 5));

        ListTag palette = new ListTag();
        for (String name : new String[] {
                "minecraft:air",
                "minecraft:polished_deepslate",
                "minecraft:chiseled_deepslate",
                "minecraft:sea_lantern"
        }) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", name);
            palette.add(state);
        }
        structure.put("palette", palette);

        ListTag blocks = new ListTag();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                for (int y = 0; y < 5; y++) {
                    int paletteId;
                    if (y == 0) {
                        paletteId = (x == 2 && z == 2) ? 2 : 1;
                    } else if (y == 3 && (x == 0 || x == 4) && (z == 0 || z == 4)) {
                        paletteId = 3;
                    } else {
                        paletteId = 0;
                    }
                    CompoundTag block = new CompoundTag();
                    block.put("pos", integers(x, y, z));
                    block.putInt("state", paletteId);
                    blocks.add(block);
                }
            }
        }
        structure.put("blocks", blocks);
        structure.put("entities", new ListTag());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(structure, buffer);
        return buffer.toByteArray();
    }

    private static ListTag integers(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }

    /** Make Create's server-side uploaded template exist before Create reads it. */
    private static byte[] ensureServerFile(MilestoneRecord record) throws IOException {
        if (record.discoveredBy() == null) {
            throw new IOException("Cannot create schematic for an undiscovered Core");
        }
        byte[] bytes = blueprintBytes();
        Path root = CreatePaths.UPLOADED_SCHEMATICS_DIR.toAbsolutePath().normalize();
        Path directory = root.resolve(owner(record)).normalize();
        if (!directory.startsWith(root)) throw new IOException("Invalid schematic owner");
        Files.createDirectories(directory);
        Path path = directory.resolve(filename(record));
        if (!Files.exists(path) || !Arrays.equals(bytes, Files.readAllBytes(path))) {
            Files.write(path, bytes);
        }
        return bytes;
    }

    public static ItemStack create(ServerPlayer player, MilestoneRecord record) throws IOException {
        byte[] bytes = ensureServerFile(record);
        ItemStack schematic = SchematicItem.create(player.level(), filename(record), owner(record));
        if (schematic.isEmpty()) throw new IOException("Create returned an empty schematic item");
        CompoundTag extra = new CompoundTag();
        extra.putUUID("MilestoneId", record.id());
        extra.putString("MilestoneType", record.type().name());
        extra.putString("Dimension", record.dimension());
        extra.putLong("CorePos", record.corePos().asLong());
        schematic.set(DataComponents.CUSTOM_DATA, CustomData.of(extra));
        schematic.set(DataComponents.CUSTOM_NAME,
                Component.literal("Milestone Chamber: " + record.type().displayName())
                        .withStyle(ChatFormatting.AQUA));
        CreateSchematicNetwork.send(player, filename(record), bytes);
        return schematic;
    }

    /** Give every joining player the local file needed to view traded schematics. */
    public static void sync(ServerPlayer player, MilestoneRecord record) {
        if (record.discoveredBy() == null) return;
        try {
            CreateSchematicNetwork.send(player, filename(record), ensureServerFile(record));
        } catch (Exception exception) {
            LOGGER.error("Failed to prepare Create blueprint for {}",
                    record.id(), exception);
        }
    }
}
