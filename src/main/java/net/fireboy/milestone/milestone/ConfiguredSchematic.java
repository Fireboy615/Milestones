package net.fireboy.milestone.milestone;

import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Validates and compares vanilla/Create structure .nbt files without loading world chunks. */
public final class ConfiguredSchematic {
    private static final int MAX_FILE_BYTES = 262144;
    private static final int MAX_SIDE = 24;
    private static final int MAX_BLOCKS = 4096;
    private static final int MAX_MATCHES = 8192;

    private ConfiguredSchematic() { }

    public record BlockRequirement(BlockPos position, CompoundTag state) { }
    public record Blueprint(int width, int height, int depth,
                            BlockPos anchor, List<BlockRequirement> blocks, byte[] bytes) {
        public Blueprint {
            blocks = List.copyOf(blocks);
        }
    }

    public static String selectedId(MilestoneRecord record) {
        var core = MilestoneContentConfig.server().core(record.coreTier());
        return core == null ? "generated" : core.schematic();
    }

    public static String displayName(MilestoneRecord record) {
        String id = selectedId(record);
        var entry = MilestoneContentConfig.schematic(MilestoneContentConfig.server(), id);
        return entry == null ? "Milestone Chamber" : entry.name();
    }

    public static Blueprint forRecord(MilestoneRecord record) throws IOException {
        String id = selectedId(record);
        if (id.equals("generated")) return null;
        var entry = MilestoneContentConfig.schematic(MilestoneContentConfig.server(), id);
        if (entry == null) throw new IOException("Missing blueprint entry: " + id);
        return readFile(entry.file());
    }

    public static void validateFile(String filename) throws IOException {
        readFile(filename);
    }

    public static Blueprint readFile(String filename) throws IOException {
        if (filename == null || !filename.matches("[a-zA-Z0-9_.-]{1,100}\\.nbt")
                || filename.startsWith(".") || filename.contains(".."))
            throw new IOException("Invalid filename: " + filename);
        Path root = FMLPaths.CONFIGDIR.get().resolve("milestone").resolve("schematics")
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve(filename).normalize();
        if (!file.getParent().equals(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file))
            throw new IOException("Put " + filename + " in config/milestone/schematics/");
        long length = Files.size(file);
        if (length <= 0 || length > MAX_FILE_BYTES)
            throw new IOException("Compressed schematic must be 1-262144 bytes: " + filename);
        byte[] bytes = Files.readAllBytes(file);
        CompoundTag structure;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            structure = NbtIo.readCompressed(input, NbtAccounter.create(8_388_608));
        } catch (Exception ex) {
            throw new IOException("Not a valid compressed structure .nbt: " + filename, ex);
        }
        ListTag dims = structure.getList("size", Tag.TAG_INT);
        if (dims.size() != 3) throw new IOException("NBT must include size [x,y,z]");
        int sx = dims.getInt(0), sy = dims.getInt(1), sz = dims.getInt(2);
        if (sx < 1 || sy < 1 || sz < 1 || sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE)
            throw new IOException("Schematic dimensions must be 1-24 blocks per side");
        if (sx * sy * sz > MAX_MATCHES)
            throw new IOException("Schematic volume too large");
        ListTag palettes = structure.getList("palette", Tag.TAG_COMPOUND);
        if (palettes.isEmpty() || palettes.size() > 1024)
            throw new IOException("Missing or oversized palette");
        if (!structure.getList("entities", Tag.TAG_COMPOUND).isEmpty())
            throw new IOException("Remove entities from imported schematics");
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > MAX_BLOCKS) throw new IOException("Too many blocks in schematic");
        List<BlockRequirement> required = new ArrayList<>();
        for (Tag blockTag : blocks) {
            CompoundTag block = (CompoundTag) blockTag;
            // Block entities are intentionally unsupported: prevents unsafe schematic duplication.
            if (block.contains("nbt")) throw new IOException("Block entities must be removed from schematic");
            int index = block.getInt("state");
            if (index < 0 || index >= palettes.size())
                throw new IOException("Invalid palette index " + index);
            CompoundTag state = palettes.getCompound(index);
            String name = state.getString("Name");
            if (name.isBlank()) throw new IOException("Missing block state name");
            if (name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                    || name.equals("minecraft:void_air")) continue;
            if (name.equals("milestone:milestone_core") || name.equals("minecraft:command_block")
                    || name.equals("minecraft:repeating_command_block")
                    || name.equals("minecraft:chain_command_block")
                    || name.equals("minecraft:structure_block") || name.equals("minecraft:jigsaw"))
                throw new IOException("Unsafe/unplaceable block: " + name);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) throw new IOException("Missing block position");
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz)
                throw new IOException("Block outside schematic dimensions");
            required.add(new BlockRequirement(new BlockPos(x,y,z), state.copy()));
        }
        if (required.isEmpty()) throw new IOException("Schematic must contain non-air blocks");
        return new Blueprint(sx,sy,sz,required.get(0).position(),required,bytes);
    }

    /** Exact block-state matching; the Create placement orientation must be unrotated. */
    public static boolean matches(ServerLevel level, BlockPos anchorWorld, Blueprint blueprint) {
        BlockPos origin = anchorWorld.subtract(blueprint.anchor());
        for (BlockRequirement required : blueprint.blocks()) {
            BlockPos worldPos = origin.offset(required.position());
            if (!level.hasChunkAt(worldPos)) return false;
            BlockState actual = level.getBlockState(worldPos);
            if (!NbtUtils.writeBlockState(actual).equals(required.state())) return false;
        }
        return true;
    }
}
