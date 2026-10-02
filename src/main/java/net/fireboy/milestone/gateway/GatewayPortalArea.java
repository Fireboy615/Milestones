package net.fireboy.milestone.gateway;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public record GatewayPortalArea(
        String dimension,
        BlockPos min,
        BlockPos max,
        Direction.Axis axis
) {
    private static final int MAX_CAPTURE_BLOCKS = 1024;

    public boolean contains(String dimension, BlockPos pos) {
        return this.dimension.equals(dimension)
                && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public boolean samePhysicalArea(GatewayPortalArea other) {
        return dimension.equals(other.dimension)
                && min.equals(other.min)
                && max.equals(other.max)
                && axis == other.axis;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension);
        tag.putInt("MinX", min.getX());
        tag.putInt("MinY", min.getY());
        tag.putInt("MinZ", min.getZ());
        tag.putInt("MaxX", max.getX());
        tag.putInt("MaxY", max.getY());
        tag.putInt("MaxZ", max.getZ());
        tag.putString("Axis", axis.name());
        return tag;
    }

    public static GatewayPortalArea load(CompoundTag tag) {
        Direction.Axis axis;
        try {
            axis = Direction.Axis.valueOf(tag.getString("Axis"));
        } catch (IllegalArgumentException ignored) {
            axis = Direction.Axis.X;
        }

        return new GatewayPortalArea(
                tag.getString("Dimension"),
                new BlockPos(tag.getInt("MinX"), tag.getInt("MinY"), tag.getInt("MinZ")),
                new BlockPos(tag.getInt("MaxX"), tag.getInt("MaxY"), tag.getInt("MaxZ")),
                axis
        );
    }

    public static GatewayPortalArea capture(ServerLevel level, BlockPos start) {
        BlockState initial = level.getBlockState(start);
        if (!initial.is(Blocks.NETHER_PORTAL)) {
            return null;
        }

        Direction.Axis axis = initial.getValue(NetherPortalBlock.AXIS);
        Direction positiveHorizontal = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction negativeHorizontal = positiveHorizontal.getOpposite();

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos immutableStart = start.immutable();
        queue.add(immutableStart);
        visited.add(immutableStart);

        int minX = start.getX();
        int minY = start.getY();
        int minZ = start.getZ();
        int maxX = start.getX();
        int maxY = start.getY();
        int maxZ = start.getZ();

        Direction[] connectedDirections = {
                Direction.UP,
                Direction.DOWN,
                positiveHorizontal,
                negativeHorizontal
        };

        while (!queue.isEmpty() && visited.size() <= MAX_CAPTURE_BLOCKS) {
            BlockPos current = queue.removeFirst();
            minX = Math.min(minX, current.getX());
            minY = Math.min(minY, current.getY());
            minZ = Math.min(minZ, current.getZ());
            maxX = Math.max(maxX, current.getX());
            maxY = Math.max(maxY, current.getY());
            maxZ = Math.max(maxZ, current.getZ());

            for (Direction direction : connectedDirections) {
                BlockPos next = current.relative(direction).immutable();
                if (visited.contains(next)) {
                    continue;
                }

                BlockState state = level.getBlockState(next);
                if (!state.is(Blocks.NETHER_PORTAL)
                        || state.getValue(NetherPortalBlock.AXIS) != axis) {
                    continue;
                }

                visited.add(next);
                queue.addLast(next);
            }
        }

        return new GatewayPortalArea(
                level.dimension().location().toString(),
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ),
                axis
        );
    }
}
