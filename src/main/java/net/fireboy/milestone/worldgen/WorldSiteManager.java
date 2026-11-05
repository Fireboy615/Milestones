package net.fireboy.milestone.worldgen;

import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.resonance.GrowthManager;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.world.WorldSiteSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.neoforged.neoforge.network.PacketDistributor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
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
    private static final int CHECK_INTERVAL = 100;
    private static final int SPAWN_SITE_RADIUS = 100;
    private static final long EXCHANGE_SALT = 0x59A3B13L;
    private static final long MISSION_SALT = 0x647DF31L;
    private static final int MAX_STATION_CANDIDATES = 64;
    // Manual actions are bounded and processed on the server thread, one
    // generation step per tick. Default automatic placement remains unchanged.
    private static final Map<MinecraftServer, ArrayDeque<GenerationJob>> REQUESTS =
            new WeakHashMap<>();

    private enum Kind { CORE, SHOP, MISSION }

    private static final class GenerationJob {
        private final Kind kind;
        private final int tier;
        private final UUID requestedBy;
        private int chunkCursor;
        private int retries;
        private final Random candidates;

        private GenerationJob(Kind kind, int tier, UUID requestedBy, long seed) {
            this.kind = kind;
            this.tier = tier;
            this.requestedBy = requestedBy;
            this.candidates = new Random(seed);
        }

        private boolean same(Kind other, int target) {
            return kind == other && (kind != Kind.CORE || tier == target);
        }
    }

    private WorldSiteManager() { }

    public static WorldSiteSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, WorldSiteSavedData.DATA_NAME);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!MilestoneContentConfig.enabled()) return;
        processRequests(server);
        if (server.getTickCount() % CHECK_INTERVAL != 0) return;
        ServerLevel level = server.overworld();
        WorldSiteSavedData data = data(server);
        ensurePlans(server, data);
        if (data.exchange().isEmpty()) trySpawnStation(level, data, true);
        if (data.mission().isEmpty()) trySpawnStation(level, data, false);
        for (WorldSiteSavedData.CorePlan plan : data.cores()) {
            ServerLevel coreLevel = levelFor(server, plan.dimension());
            if (coreLevel == null) continue;
            if (!plan.placed()) {
                var configured = MilestoneContentConfig.server().core(plan.tier());
                if (configured == null || !configured.enabled()) continue;
            }
            if (plan.placed()) {
                upgradeSavedCoreTier(server, coreLevel, plan);
                if (!data.autoTendrilStarted(plan.tier())
                        || !data.autoTendrilWorldAged(plan.tier())) {
                    tryStartAutomaticTendril(coreLevel, data, plan);
                }
                continue;
            }
            // Existing reservations are always tried first, including lakebeds,
            // rivers and ocean floors. Only truly unsuitable chambers relocate.
            trySpawnCore(coreLevel, data, plan);
        }
    }

    /** Use exactly the same deterministic world-site reservations as normal startup. */
    private static void ensurePlans(MinecraftServer server, WorldSiteSavedData sites) {
        if (!sites.initialized()) sites.initialize(planCores(server, List.of()));
        else sites.reconcile(planCores(server, sites.cores()),
                MilestoneContentConfig.server().cores().keySet());
    }

    private static ArrayDeque<GenerationJob> queue(MinecraftServer server) {
        return REQUESTS.computeIfAbsent(server, ignored -> new ArrayDeque<>());
    }

    private static boolean queued(MinecraftServer server, Kind kind, int tier) {
        return queue(server).stream().anyMatch(job -> job.same(kind, tier));
    }

    private static WorldSiteSavedData.CorePlan plan(WorldSiteSavedData sites, int tier) {
        return sites.cores().stream().filter(candidate -> candidate.tier() == tier)
                .findFirst().orElse(null);
    }

    /** Called only from the permission-checked config network handler.
     * A manual request loads a bounded area; normal automatic placement doesn't. */
    public static String requestGeneration(MinecraftServer server, UUID requester,
                                            String action, int tier) {
        WorldSiteSavedData sites = data(server);
        if (action.equals("status")) {
            // Prepare reservations immediately on opening the controls, rather
            // than waiting for the first 100-tick startup generator pass.
            if (MilestoneContentConfig.enabled()) ensurePlans(server, sites);
            return "Generation status refreshed.";
        }
        if (!MilestoneContentConfig.enabled()) return "Enable Milestones before generating structures.";
        ensurePlans(server, sites);
        if (action.equals("all")) {
            int added = 0;
            for (int configuredTier : configuredTiers()) {
                if (enqueueCore(server, sites, requester, configuredTier)) added++;
            }
            return added == 0 ? "All configured cores are already placed or queued."
                    : "Queued " + added + " Core(s). Chunks generate gradually; existing sites are skipped.";
        }
        if (action.equals("core")) {
            if (tier < 0 || tier >= 32 || !MilestoneContentConfig.server().cores().containsKey(tier))
                return "Unknown Core. Save it in Config before generating.";
            if (!MilestoneContentConfig.server().core(tier).enabled())
                return "This Core is disabled.";
            WorldSiteSavedData.CorePlan reserved = plan(sites, tier);
            if (reserved == null)
                return "Core " + (tier + 1) + " has no reservation. Check its dimension and spawn rules.";
            if (reserved.placed())
                return "Core " + (tier + 1) + " is already placed. It will not be regenerated.";
            if (queued(server, Kind.CORE, tier))
                return "Core " + (tier + 1) + " is already queued.";
            enqueueCore(server, sites, requester, tier);
            return "Queued Core " + (tier + 1) + " in " + reserved.dimension() + ".";
        }
        if (action.equals("shop") || action.equals("station")) {
            Kind kind = action.equals("shop") ? Kind.SHOP : Kind.MISSION;
            if (kind == Kind.SHOP ? sites.exchange().isPresent() : sites.mission().isPresent())
                return (kind == Kind.SHOP ? "Shop" : "Mission Station")
                        + " already generated; no duplicate created.";
            if (queued(server, kind, -1))
                return (kind == Kind.SHOP ? "Shop" : "Mission Station") + " already queued.";
            queue(server).add(new GenerationJob(kind, -1, requester,
                    server.overworld().getSeed() ^ (kind == Kind.SHOP ? EXCHANGE_SALT : MISSION_SALT)));
            return "Queued " + (kind == Kind.SHOP ? "Resonant Exchange" : "Mission Station")
                    + " near world spawn. Searching for safe terrain.";
        }
        return "Unknown generation request.";
    }

    private static boolean enqueueCore(MinecraftServer server, WorldSiteSavedData sites,
                                        UUID requester, int tier) {
        var configured = MilestoneContentConfig.server().core(tier);
        WorldSiteSavedData.CorePlan reserved = plan(sites, tier);
        if (configured == null || !configured.enabled() || reserved == null
                || reserved.placed() || queued(server, Kind.CORE, tier)) return false;
        queue(server).add(new GenerationJob(Kind.CORE, tier, requester, 0L));
        return true;
    }

    /** Small per-tick work budget avoids generating thousands of chunks at once. */
    private static void processRequests(MinecraftServer server) {
        ArrayDeque<GenerationJob> pending = queue(server);
        if (pending.isEmpty()) return;
        GenerationJob job = pending.peek();
        String result;
        try {
            result = switch (job.kind) {
                case CORE -> generateCoreStep(server, job);
                case SHOP, MISSION -> generateStationStep(server, job);
            };
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(WorldSiteManager.class)
                    .error("Manual generation failed: {} Core {}", job.kind, job.tier, failure);
            result = "Generation failed: " + failure.getClass().getSimpleName()
                    + ". Inspect server logs; the site remains reserved.";
        }
        if (result == null) return; // More chunks or candidate sites to process.
        pending.remove();
        ServerPlayer player = server.getPlayerList().getPlayer(job.requestedBy);
        if (player != null) {
            player.sendSystemMessage(Component.literal("[Milestones] " + result)
                    .withStyle(ChatFormatting.AQUA));
            PacketDistributor.sendToPlayer(player,
                    new MilestoneConfigNetwork.GenerationStatus(generationStatus(server, result)));
        }
    }

    /** One chunk per tick, at most a small 3x3 neighborhood around the Core. */
    private static String generateCoreStep(MinecraftServer server, GenerationJob job) {
        WorldSiteSavedData sites = data(server);
        WorldSiteSavedData.CorePlan reserved = plan(sites, job.tier);
        if (reserved == null) return "Core " + (job.tier + 1) + " is no longer configured.";
        if (reserved.placed()) return "Core " + (job.tier + 1) + " was already generated.";
        ServerLevel level = levelFor(server, reserved.dimension());
        if (level == null) return "Core " + (job.tier + 1) + " has an unavailable dimension.";

        int lowX = Math.floorDiv(reserved.x() - 12, 16);
        int highX = Math.floorDiv(reserved.x() + 12, 16);
        int lowZ = Math.floorDiv(reserved.z() - 12, 16);
        int highZ = Math.floorDiv(reserved.z() + 12, 16);
        int cols = highX - lowX + 1;
        int rows = highZ - lowZ + 1;
        int total = cols * rows;
        if (job.chunkCursor < total) {
            int cx = lowX + job.chunkCursor % cols;
            int cz = lowZ + job.chunkCursor / cols;
            // Manual action explicitly permits generation of previously missing
            // chunks. This does not mark them permanently force-loaded.
            level.getChunk(cx, cz);
            job.chunkCursor++;
            return null;
        }

        trySpawnCore(level, sites, reserved);
        if (plan(sites, job.tier) != null && plan(sites, job.tier).placed())
            return "Generated Core " + (job.tier + 1) + " at "
                    + plan(sites, job.tier).placedPos() + " (" + reserved.dimension() + ").";
        if (++job.retries < 3) {
            // Existing safety checks may relocate an unsuitable reservation.
            job.chunkCursor = 0;
            return null;
        }
        return "Core " + (job.tier + 1)
                + " could not find safe terrain in three attempts; reservation kept for retry.";
    }

    /** A few near-spawn candidates are prepared per manual station request.
     * Auto-generation still uses only player-loaded chunks. */
    private static String generateStationStep(MinecraftServer server, GenerationJob job) {
        WorldSiteSavedData sites = data(server);
        boolean exchange = job.kind == Kind.SHOP;
        BlockPos placed = (exchange ? sites.exchange() : sites.mission()).orElse(null);
        String title = exchange ? "Resonant Exchange" : "Mission Station";
        if (placed != null) return title + " already placed at " + placed + ".";
        if (job.retries >= MAX_STATION_CANDIDATES)
            return "No safe site found for " + title + " in " + MAX_STATION_CANDIDATES
                    + " near-spawn candidates; try again after inspecting the terrain.";

        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        double angle = job.candidates.nextDouble() * Math.PI * 2.0D;
        int distance = 28 + job.candidates.nextInt(SPAWN_SITE_RADIUS - 28);
        int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
        int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
        job.retries++;

        BlockPos probe = new BlockPos(x, level.getSeaLevel(), z);
        if (!level.getWorldBorder().isWithinBounds(probe)) return null;

        // Only the chunks needed for the 5x5 platform are generated.
        for (int cx = Math.floorDiv(x - 4, 16); cx <= Math.floorDiv(x + 4, 16); cx++)
            for (int cz = Math.floorDiv(z - 4, 16); cz <= Math.floorDiv(z + 4, 16); cz++)
                level.getChunk(cx, cz);
        if (tryPlaceStationCandidate(level, sites, exchange, x, z))
            return "Generated " + title + " at "
                    + (exchange ? sites.exchange().orElseThrow() : sites.mission().orElseThrow()) + ".";
        return null;
    }

    /** Shared safe-site validation used by manual generation. */
    private static boolean tryPlaceStationCandidate(ServerLevel level, WorldSiteSavedData sites,
                                                     boolean exchange, int x, int z) {
        BlockPos anchor = stationAnchor(level, x, z);
        if (anchor == null) return false;
        BlockPos other = exchange ? sites.mission().orElse(null) : sites.exchange().orElse(null);
        if (other != null && other.distSqr(anchor) < 35L * 35L) return false;
        if (!stationSiteValid(level, anchor)) return false;
        buildStation(level, anchor, exchange);
        if (exchange) sites.setExchange(anchor); else sites.setMission(anchor);
        return true;
    }

    /** Authoritative world status for the operator's generation screen. */
    public static String generationStatus(MinecraftServer server, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        WorldSiteSavedData sites = data(server);
        result.addProperty("shop", sites.exchange().isPresent() ? "Placed"
                : queued(server, Kind.SHOP, -1) ? "Queued" : "Not generated");
        result.addProperty("station", sites.mission().isPresent() ? "Placed"
                : queued(server, Kind.MISSION, -1) ? "Queued" : "Not generated");
        JsonArray cores = new JsonArray();
        for (int tier : configuredTiers()) {
            JsonObject row = new JsonObject();
            var configured = MilestoneContentConfig.server().core(tier);
            var reserved = plan(sites, tier);
            row.addProperty("tier", tier);
            row.addProperty("name", configured.name());
            row.addProperty("dimension", configured.dimension());
            row.addProperty("state", reserved != null && reserved.placed() ? "Placed"
                    : queued(server, Kind.CORE, tier) ? "Queued"
                    : reserved == null ? "Not reserved" : "Reserved");
            cores.add(row);
        }
        result.add("cores", cores);
        return result.toString();
    }

    /** One-time migration of legacy saved Cores created before per-Core tier identity existed. */
    private static void upgradeSavedCoreTier(MinecraftServer server, ServerLevel level,
                                             WorldSiteSavedData.CorePlan plan) {
        if (plan.placedPos() == null) return;
        for (MilestoneRecord record : MilestoneManager.data(server).all()) {
            if (record.coreTier() < 0
                    && record.corePos().equals(plan.placedPos())
                    && record.dimension().equals(plan.dimension())) {
                MilestoneManager.save(level, record.withCoreTier(plan.tier()));
                return;
            }
        }
    }

    /** Persist X/Z reservations once; actual vertical placement needs generated terrain. */
    private static ServerLevel levelFor(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static List<Integer> configuredTiers() {
        return MilestoneContentConfig.server().cores().keySet().stream().sorted().toList();
    }

    private static int automaticInner(int maxRadius, int tier) {
        List<Integer> tiers = configuredTiers();
        int rank = Math.max(0, tiers.indexOf(tier));
        return 200 + (maxRadius - 200) * rank / Math.max(1, tiers.size());
    }

    private static int automaticOuter(int maxRadius, int tier) {
        List<Integer> tiers = configuredTiers();
        int rank = Math.max(0, tiers.indexOf(tier));
        return 200 + (maxRadius - 200) * (rank + 1) / Math.max(1, tiers.size());
    }

    /** Plan only new tiers; all existing reservations keep their original positions. */
    private static List<WorldSiteSavedData.CorePlan> planCores(MinecraftServer server,
                                                                List<WorldSiteSavedData.CorePlan> existing) {
        // Deleted *unplaced* reservations must not be reintroduced by reconcile.
        // Keep placed sites even if an offline config edit removed their profile.
        List<WorldSiteSavedData.CorePlan> plans = new ArrayList<>();
        for (var plan : existing)
            if (plan.placed() || MilestoneContentConfig.server().cores().containsKey(plan.tier()))
                plans.add(plan);
        for (int tier : configuredTiers()) {
            var settings = MilestoneContentConfig.server().core(tier);
            if (settings == null || !settings.enabled()) continue;
            if (plans.stream().anyMatch(plan -> plan.tier() == tier)) continue;
            String dimension = settings.dimension();
            ServerLevel level = levelFor(server, dimension);
            if (level == null) continue;
            BlockPos spawn = level.getSharedSpawnPos();
            int maxRadius = maxCoreRadius(level);
            int inner = settings.minDistance() > 0
                    ? settings.minDistance() : automaticInner(maxRadius, tier);
            int outer = settings.maxDistance() > 0
                    ? settings.maxDistance() : automaticOuter(maxRadius, tier);
            // Per-tier RNG is stable even if other profiles are added or deleted.
            Random random = new Random(level.getSeed() ^ 0x71A5D0C01L
                    ^ ((long) tier * 0x9E3779B97F4A7C15L));
            for (int attempt = 0; attempt < 768; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                int distance = inner + random.nextInt(Math.max(1, outer - inner + 1));
                int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos candidate = new BlockPos(x, level.getSeaLevel(), z);
                if (!level.getWorldBorder().isWithinBounds(candidate)
                        || !siteBiomeAllowed(level, x, z, settings)) continue;
                boolean tooClose = false;
                for (WorldSiteSavedData.CorePlan prior : plans) {
                    if (!prior.dimension().equals(dimension)) continue;
                    long dx = (long) x - prior.x(), dz = (long) z - prior.z();
                    if (dx * dx + dz * dz < 200L * 200L) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;
                plans.add(new WorldSiteSavedData.CorePlan(tier, x, z,
                        settings.profile(), false, null, dimension));
                break;
            }
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
        var settings = MilestoneContentConfig.server().core(plan.tier());
        int inner = settings != null && settings.minDistance() > 0
                ? settings.minDistance() : automaticInner(maxRadius, plan.tier());
        int outer = settings != null && settings.maxDistance() > 0
                ? settings.maxDistance() : automaticOuter(maxRadius, plan.tier());
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
                    || !siteBiomeAllowed(level, x, z, settings)) continue;
            boolean overlaps = false;
            for (WorldSiteSavedData.CorePlan other : data.cores()) {
                if (other.tier() == plan.tier() || !other.dimension().equals(plan.dimension())) continue;
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
    private static boolean siteBiomeAllowed(ServerLevel level, int x, int z,
                                             MilestoneContentConfig.CoreSettings settings) {
        var source = level.getChunkSource();
        var biome = source.getGenerator().getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(level.getSeaLevel()),
                QuartPos.fromBlock(z),
                source.randomState().sampler()
        );
        String id = biome.unwrapKey().map(key -> key.location().toString()).orElse("");
        if (settings != null) {
            if (settings.blockedBiomes().contains(id)) return false;
            if (!settings.allowedBiomes().isEmpty())
                return settings.allowedBiomes().contains(id);
        }
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
            var settings = MilestoneContentConfig.server().core(plan.tier());
            if (!siteBiomeAllowed(level, x, z, settings)) continue;
            int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            BlockPos top = new BlockPos(x, surface - 1, z);
            if (!isNaturalSolid(level.getBlockState(top))) continue;

            double fraction = settings != null ? settings.depthFraction() : 0.5D;
            int targetY = (int) Math.round(surface - Math.max(0, surface - bottom) * fraction);
            targetY = Math.max(bottom, Math.min(surface - 4, targetY));
            for (int vertical : verticalOffsets) {
                int y = targetY + vertical;
                if (y < bottom || y > surface - 4) continue;
                if (settings != null && (y < settings.minY() || y > settings.maxY())) continue;
                BlockPos center = new BlockPos(x, y, z);
                if (!chamberSiteValid(level, center)) continue;
                buildCoreChamber(level, center);
                if (level.getBlockEntity(center) instanceof MilestoneCoreBlockEntity core) {
                    var record = MilestoneManager.register(level, core);
                    MilestoneManager.save(level, record.withTypeResetProgress(plan.type()).withCoreTier(plan.tier()));
                    data.setCorePlaced(plan.tier(), center);
                    // Backdate and materialize the visible surface tip immediately
                    // rather than waiting for another periodic site check.
                    tryStartAutomaticTendril(level, data,
                            new WorldSiteSavedData.CorePlan(
                                    plan.tier(), plan.x(), plan.z(), plan.type(), true, center, plan.dimension()));
                }
                return;
            }
        }
        // All candidates were examined in loaded terrain and none were suitable.
        // Retry at a different site rather than permanently abandoning this tier.
        relocateCore(level, data, plan);
    }

    /**
     * Exactly one automatic surface-reaching tendril per generated Core.
     * Existing save files pick it up when the site chunks are next loaded.
     * Discovered Cores never restart growth.
     */
    private static void tryStartAutomaticTendril(ServerLevel level,
                                                  WorldSiteSavedData sites,
                                                  WorldSiteSavedData.CorePlan plan) {
        BlockPos core = plan.placedPos();
        if (core == null || !level.hasChunkAt(core)) return;

        MilestoneRecord record = null;
        for (MilestoneRecord candidate : MilestoneManager.data(level.getServer()).all()) {
            if (candidate.corePos().equals(core)
                    && candidate.dimension().equals(level.dimension().location().toString())) {
                record = candidate;
                break;
            }
        }
        if (record == null) return;
        if (record.discoveredBy() != null
                || record.state() == net.fireboy.milestone.milestone.MilestoneState.DISCOVERED
                || record.state() == net.fireboy.milestone.milestone.MilestoneState.ACTIVE
                || record.state() == net.fireboy.milestone.milestone.MilestoneState.COMPLETED) {
            sites.markAutoTendrilStarted(plan.tier());
            sites.markAutoTendrilWorldAged(plan.tier());
            return;
        }
        if (GrowthManager.get(level.getServer(), record.id()).isPresent()) {
            // Upgrade old auto-plans once; don't backdate unrelated manual tests.
            if (sites.autoTendrilStarted(plan.tier())
                    && !sites.autoTendrilWorldAged(plan.tier())) {
                GrowthManager.fastForward(level.getServer(), record.id(), level.getGameTime());
                GrowthManager.materializeVisibleTip(level, record.id());
            }
            sites.markAutoTendrilStarted(plan.tier());
            sites.markAutoTendrilWorldAged(plan.tier());
            return;
        }
        if (sites.autoTendrilStarted(plan.tier())) {
            // The earlier plan completed (or was cancelled). Don't duplicate it.
            sites.markAutoTendrilWorldAged(plan.tier());
            return;
        }

        long seed = record.id().getLeastSignificantBits() ^ level.getSeed();
        int x = core.getX() + Math.floorMod((int) seed, 11) - 5;
        int z = core.getZ() + Math.floorMod((int) (seed >>> 24), 11) - 5;
        BlockPos probe = new BlockPos(x, level.getSeaLevel(), z);
        if (!level.hasChunkAt(probe)) return;

        // Tip emerges above terrain OR the water surface if submerged.
        int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int tipY = Math.min(level.getMaxBuildHeight() - 6, Math.max(floor + 2, surface + 1));
        if (tipY <= core.getY() + 2) tipY = Math.min(level.getMaxBuildHeight() - 6, core.getY() + 5);
        BlockPos tip = new BlockPos(x, tipY, z);

        GrowthManager.StartResult result = GrowthManager.startFromWorldBeginning(
                level, core, tip, record.id());
        if (result.started()) {
            sites.markAutoTendrilStarted(plan.tier());
            sites.markAutoTendrilWorldAged(plan.tier());
        }
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
                || state.is(Blocks.DIAMOND_ORE)
                || state.is(Blocks.NETHERRACK) || state.is(Blocks.BASALT)
                || state.is(Blocks.BLACKSTONE) || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL) || state.is(Blocks.CRIMSON_NYLIUM)
                || state.is(Blocks.WARPED_NYLIUM) || state.is(Blocks.END_STONE);
    }
}
