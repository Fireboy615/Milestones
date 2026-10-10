package net.fireboy.milestone.worldgen;

import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.world.WorldSiteSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Conservative, deferred placement. Never loads a chunk to generate a site.
 * No respawns: successful placements are permanently recorded even if a
 * structure is later modified or removed by an admin.
 */
public final class WorldSiteManager {
    private static final SavedData.Factory<WorldSiteSavedData> FACTORY =
            new SavedData.Factory<>(WorldSiteSavedData::create, WorldSiteSavedData::load);
    private static final int CORE_COUNT = 6;
    private static final int CHECK_INTERVAL = 100;
    private static final int SPAWN_SITE_RADIUS = 100;
    private static final long EXCHANGE_SALT = 0x59A3B13L;
    private static final long MISSION_SALT = 0x647DF31L;

    private WorldSiteManager() { }

    public static WorldSiteSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, WorldSiteSavedData.DATA_NAME);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL != 0) return;
        ServerLevel level = server.overworld();
        WorldSiteSavedData data = data(server);
        if (!data.initialized()) data.initialize(planCores(level));
        if (data.exchange().isEmpty()) trySpawnStation(level, data, true);
        if (data.mission().isEmpty()) trySpawnStation(level, data, false);
        for (WorldSiteSavedData.CorePlan plan : data.cores()) {
            if (plan.placed()) continue;
            // Existing reservations are always tried first, including lakebeds,
            // rivers and ocean floors. Only truly unsuitable chambers relocate.
            trySpawnCore(level, data, plan);
        }
    }

    /** Persist X/Z reservations once; actual vertical placement needs generated terrain. */
    private static List<WorldSiteSavedData.CorePlan> planCores(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        double borderRadius = level.getWorldBorder().getSize() / 2.0D;
        int maxRadius = (int) Math.min(8000.0D, Math.max(450.0D, borderRadius * 0.72D));
        List<WorldSiteSavedData.CorePlan> plans = new ArrayList<>();
        Random random = new Random(level.getSeed() ^ 0x71A5D0C01L);
        for (int tier = 0; tier < CORE_COUNT; tier++) {
            int inner = 200 + (maxRadius - 200) * tier / CORE_COUNT;
            int outer = 200 + (maxRadius - 200) * (tier + 1) / CORE_COUNT;
            int x = spawn.getX();
            int z = spawn.getZ();
            for (int attempt = 0; attempt < 768; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                int distance = inner + random.nextInt(Math.max(1, outer - inner + 1));
                int candidateX = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
                int candidateZ = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos candidate = new BlockPos(candidateX, level.getSeaLevel(), candidateZ);
                if (!level.getWorldBorder().isWithinBounds(candidate)
                        || !landBiomeAt(level, candidateX, candidateZ)) continue;
                boolean tooClose = false;
                for (WorldSiteSavedData.CorePlan prior : plans) {
                    long dx = (long) candidateX - prior.x();
                    long dz = (long) candidateZ - prior.z();
                    if (dx * dx + dz * dz < 200L * 200L) { tooClose = true; break; }
                }
                if (tooClose) continue;
                x = candidateX;
                z = candidateZ;
                break;
            }
            MilestoneType type = tier == 1 ? MilestoneType.NETHER_GATE : MilestoneType.GENERIC;
            plans.add(new WorldSiteSavedData.CorePlan(tier, x, z, type, false, null));
        }
        return plans;
    }

    private static void trySpawnStation(ServerLevel level, WorldSiteSavedData data, boolean exchange) {
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos other = exchange ? data.mission().orElse(null) : data.exchange().orElse(null);
        Random random = new Random(level.getSeed() ^ (exchange ? EXCHANGE_SALT : MISSION_SALT));
        // Fixed-seed candidates remain consistent between ticks, but a large
        // search pool handles mountains and patchy coastlines near world spawn.
        for (int attempt = 0; attempt < 1600; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = 28 + random.nextInt(SPAWN_SITE_RADIUS - 28);
            int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos probe = new BlockPos(x, level.getSeaLevel(), z);
            if (!level.getWorldBorder().isWithinBounds(probe) || !areaLoaded(level, x, z, 3)) continue;

            BlockPos anchor = stationAnchor(level, x, z);
            if (anchor == null) continue;
            if (other != null && other.distSqr(anchor) < 35L * 35L) continue;
            if (!stationSiteValid(level, anchor)) continue;
            buildStation(level, anchor, exchange);
            if (exchange) data.setExchange(anchor); else data.setMission(anchor);
            return;
        }
    }

    /** Place a level platform on mildly uneven terrain without cutting hills. */
    private static BlockPos stationAnchor(ServerLevel level, int x, int z) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
            lowest = Math.min(lowest, surface);
            highest = Math.max(highest, surface);
        }
        if (highest - lowest > 3) return null;
        return new BlockPos(x, highest, z);
    }

    private static boolean stationSiteValid(ServerLevel level, BlockPos anchor) {
        int y = anchor.getY();
        if (y <= level.getMinBuildHeight() + 4 || y >= level.getMaxBuildHeight() - 5) return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = anchor.getX() + dx, z = anchor.getZ() + dz;
            int localSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (localSurface > y || y - localSurface > 3) return false;

            BlockPos ground = new BlockPos(x, localSurface - 1, z);
            if (!isNaturalSolid(level.getBlockState(ground))) return false;

            // Above the natural ground, only air/replacable plants are allowed.
            // No fluids, tile entities, existing buildings, or substantial terrain.
            for (int testY = localSurface; testY <= y + 3; testY++) {
                BlockPos p = new BlockPos(x, testY, z);
                BlockState state = level.getBlockState(p);
                if (level.getBlockEntity(p) != null || !state.getFluidState().isEmpty()) return false;
                if (!state.isAir() && !state.canBeReplaced()) return false;
            }
        }
        return true;
    }

    private static void buildStation(ServerLevel level, BlockPos anchor, boolean exchange) {
        int y = anchor.getY();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos ground = anchor.offset(dx, -1, dz);
            boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            // Fill only the 0-3 air layers between the natural surface and platform.
            int naturalSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    ground.getX(), ground.getZ());
            for (int supportY = naturalSurface; supportY < y - 1; supportY++) {
                level.setBlock(new BlockPos(ground.getX(), supportY, ground.getZ()),
                        Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
            level.setBlock(ground, (rim ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS).defaultBlockState(), 3);
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                level.setBlock(anchor.offset(dx, 0, dz), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
                level.setBlock(anchor.offset(dx, 1, dz), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
            }
        }
        level.setBlock(anchor.below(), Blocks.CRYING_OBSIDIAN.defaultBlockState(), 3);
        level.setBlock(anchor, (exchange ? ModBlocks.RESONANT_EXCHANGE.get() : ModBlocks.MISSION_STATION.get()).defaultBlockState(), 3);
    }

    /**
     * Existing worlds can contain an unplaced reservation in an ocean or a
     * structure/cave which makes the original chamber position unusable.
     * Re-reserve deterministically, and never move a Core which was already placed.
     */
    private static void relocateCore(ServerLevel level, WorldSiteSavedData data,
                                     WorldSiteSavedData.CorePlan plan) {
        int maxRadius = maxCoreRadius(level);
        int inner = 200 + (maxRadius - 200) * plan.tier() / CORE_COUNT;
        int outer = 200 + (maxRadius - 200) * (plan.tier() + 1) / CORE_COUNT;
        BlockPos spawn = level.getSharedSpawnPos();
        long salt = ((long) plan.x() << 32) ^ (plan.z() & 0xFFFFFFFFL)
                ^ ((long) plan.tier() * 0x9E3779B97F4A7C15L);
        Random random = new Random(level.getSeed() ^ 0x52A1F4C0930L ^ salt);
        for (int attempt = 0; attempt < 1536; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = inner + random.nextInt(Math.max(1, outer - inner + 1));
            int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if ((x == plan.x() && z == plan.z())
                    || !level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getSeaLevel(), z))
                    || !landBiomeAt(level, x, z)) continue;
            boolean overlaps = false;
            for (WorldSiteSavedData.CorePlan other : data.cores()) {
                if (other.tier() == plan.tier()) continue;
                long dx = (long) x - other.x();
                long dz = (long) z - other.z();
                if (dx * dx + dz * dz < 200L * 200L) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;
            data.relocateUnplacedCore(plan.tier(), x, z);
            return;
        }
        // No candidate found; leave the old reservation untouched and retry later.
    }

    private static int maxCoreRadius(ServerLevel level) {
        double borderRadius = level.getWorldBorder().getSize() / 2.0D;
        return (int) Math.min(8000.0D, Math.max(450.0D, borderRadius * 0.72D));
    }

    /** Evaluate a biome without forcing the chunk to generate. */
    private static boolean landBiomeAt(ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        var biome = source.getGenerator().getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(level.getSeaLevel()),
                QuartPos.fromBlock(z),
                source.randomState().sampler()
        );
        return !biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_RIVER);
    }

    private static void trySpawnCore(ServerLevel level, WorldSiteSavedData data,
                                     WorldSiteSavedData.CorePlan plan) {
        // Require a small local neighborhood already loaded by real players.
        if (!areaLoaded(level, plan.x(), plan.z(), 12)) return;

        // The exact point may intersect a cave, water, or another structure.
        // Try nearby placements without moving the reservation or creating chunks.
        int[][] offsets = {
                {0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4},
                {4, 4}, {-4, 4}, {4, -4}, {-4, -4},
                {8, 0}, {-8, 0}, {0, 8}, {0, -8}
        };
        int[] verticalOffsets = {0, -3, 3, -6, 6, -9};
        int bottom = level.getMinBuildHeight() + 10;
        for (int[] offset : offsets) {
            int x = plan.x() + offset[0], z = plan.z() + offset[1];
            // OCEAN_FLOOR ignores the water column; "surface" is the top of
            // the lakebed/seabed, not the surface of the water itself.
            int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            BlockPos top = new BlockPos(x, surface - 1, z);
            if (!isNaturalSolid(level.getBlockState(top))) continue;

            double fraction = 0.055D + 0.90D * plan.tier() / (CORE_COUNT - 1.0D);
            int targetY = (int) Math.round(surface - Math.max(0, surface - bottom) * fraction);
            targetY = Math.max(bottom, Math.min(surface - 4, targetY));
            for (int vertical : verticalOffsets) {
                int y = targetY + vertical;
                if (y < bottom || y > surface - 4) continue;
                BlockPos center = new BlockPos(x, y, z);
                if (!chamberSiteValid(level, center)) continue;
                buildCoreChamber(level, center);
                if (level.getBlockEntity(center) instanceof MilestoneCoreBlockEntity core) {
                    var record = MilestoneManager.register(level, core);
                    MilestoneManager.save(level, record.withTypeResetProgress(plan.type()));
                    data.setCorePlaced(plan.tier(), center);
                }
                return;
            }
        }
        // All candidates were examined in loaded terrain and none were suitable.
        // Retry at a different site rather than permanently abandoning this tier.
        relocateCore(level, data, plan);
    }

    private static boolean chamberSiteValid(ServerLevel level, BlockPos center) {
        if (center.getY() < level.getMinBuildHeight() + 4 || center.getY() >= level.getMaxBuildHeight() - 5)
            return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            for (int dy = -1; dy <= 3; dy++) {
                BlockPos p = center.offset(dx, dy, dz);
                BlockState state = level.getBlockState(p);
                // Flooded caves and underwater chambers are allowed, but lava,
                // block entities, and non-natural constructed blocks are not.
                if (level.getBlockEntity(p) != null) return false;
                if (!state.getFluidState().isEmpty()) {
                    if (!state.is(Blocks.WATER)
                            && !state.is(Blocks.SEAGRASS)
                            && !state.is(Blocks.TALL_SEAGRASS)
                            && !state.is(Blocks.KELP)
                            && !state.is(Blocks.KELP_PLANT)) return false;
                } else if (!state.isAir() && !isNaturalSolid(state)) {
                    return false;
                }
            }
        return true;
    }

    private static void buildCoreChamber(ServerLevel level, BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            level.setBlock(center.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
            for (int dy = 0; dy <= 3; dy++) {
                level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2)
                level.setBlock(center.offset(dx, 2, dz), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        }
        level.setBlock(center.below(), Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 3);
        level.setBlock(center, ModBlocks.MILESTONE_CORE.get().defaultBlockState(), 3);
    }

    private static boolean areaLoaded(ServerLevel level, int x, int z, int radius) {
        for (int dx : new int[]{-radius, radius}) for (int dz : new int[]{-radius, radius})
            if (!level.hasChunkAt(new BlockPos(x + dx, level.getSeaLevel(), z + dz))) return false;
        return true;
    }

    private static boolean isNaturalSolid(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
                || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.CLAY) || state.is(Blocks.CALCITE)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.DEEPSLATE_IRON_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_GOLD_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.DEEPSLATE_LAPIS_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.COAL_ORE) || state.is(Blocks.COPPER_ORE)
                || state.is(Blocks.IRON_ORE) || state.is(Blocks.GOLD_ORE)
                || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.LAPIS_ORE)
                || state.is(Blocks.DIAMOND_ORE);
    }
}
