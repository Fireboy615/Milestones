package net.fireboy.milestone.resonance;

import net.fireboy.milestone.block.YoungResonantBlock;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.world.ResonanceSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Random;
import java.util.UUID;

public final class ResonanceManager {
    private static final SavedData.Factory<ResonanceSavedData> FACTORY =
            new SavedData.Factory<>(ResonanceSavedData::create, ResonanceSavedData::load);

    private ResonanceManager() {
    }

    public static ResonanceSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, ResonanceSavedData.DATA_NAME);
    }

    public static Optional<ResonanceRecord> get(ServerLevel level, BlockPos pos) {
        return data(level.getServer()).get(dimension(level), pos);
    }

    /**
     * Covers one position with Resonance and remembers exactly what was there before.
     * Air is intentionally valid: this is how Milestone tendrils can cross caves and
     * other open spaces without needing a second temporary-block system.
     */
    public static boolean cover(ServerLevel level, BlockPos pos, UUID milestoneId) {
        String dimension = dimension(level);
        ResonanceSavedData data = data(level.getServer());

        if (data.get(dimension, pos).isPresent()) {
            return false;
        }

        BlockState originalState = level.getBlockState(pos);
        if (!canCover(level, pos, originalState)) {
            return false;
        }

        ResonanceRecord record = new ResonanceRecord(
                dimension,
                pos.immutable(),
                milestoneId,
                originalState
        );

        data.put(record);
        boolean changed = level.setBlock(
                pos,
                ModBlocks.YOUNG_RESONANT_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        );

        if (!changed) {
            data.remove(dimension, pos);
            return false;
        }

        // Fresh Resonance starts as the sparse young-growth texture and hardens later.
        level.scheduleTick(
                pos,
                ModBlocks.YOUNG_RESONANT_BLOCK.get(),
                YoungResonantBlock.MATURATION_TICKS
        );

        // If this position had been axe-stripped earlier and was waiting to regrow,
        // normal growth has now reclaimed it, so the old pending task is obsolete.
        RegenerationManager.cancelAt(level.getServer(), dimension, pos);
        return true;
    }

    /**
     * Creates a deliberately simple organic test mass around a point. Kept as a useful
     * debug primitive even though normal Milestone growth will eventually use tendrils.
     */
    public static GrowthResult coverArea(
            ServerLevel level,
            BlockPos center,
            int radius,
            UUID milestoneId
    ) {
        int covered = 0;
        int skipped = 0;
        int bounds = radius + 1;

        for (int dx = -bounds; dx <= bounds; dx++) {
            for (int dy = -bounds; dy <= bounds; dy++) {
                for (int dz = -bounds; dz <= bounds; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

                    double noise = positionNoise(pos, milestoneId);
                    double localRadius = radius * (0.78D + noise * 0.28D);
                    if (distance > localRadius) {
                        continue;
                    }

                    if (cover(level, pos, milestoneId)) {
                        covered++;
                    } else {
                        skipped++;
                    }
                }
            }
        }

        return new GrowthResult(covered, skipped);
    }

    /**
     * Plans only the tendril centreline. Thickness is no longer baked into the plan;
     * GrowthManager adds radius over time as each centreline point matures.
     */
    public static List<BlockPos> planTendril(
            BlockPos start,
            BlockPos end,
            UUID milestoneId
    ) {
        Vec3 p0 = Vec3.atCenterOf(start);
        Vec3 p3 = Vec3.atCenterOf(end);
        Vec3 delta = p3.subtract(p0);
        double distance = delta.length();

        if (distance < 1.0D) {
            return List.of(end.immutable());
        }

        Vec3 forward = delta.normalize();
        Vec3 reference = Math.abs(forward.y) < 0.90D
                ? new Vec3(0.0D, 1.0D, 0.0D)
                : new Vec3(1.0D, 0.0D, 0.0D);

        Vec3 sideA = forward.cross(reference).normalize();
        Vec3 sideB = forward.cross(sideA).normalize();

        Random random = new Random(tendrilSeed(start, end, milestoneId));
        double bend = Math.min(11.0D, Math.max(2.5D, distance * 0.20D));

        Vec3 p1 = p0
                .add(delta.scale(0.33D))
                .add(sideA.scale(symmetric(random) * bend))
                .add(sideB.scale(symmetric(random) * bend * 0.65D));

        Vec3 p2 = p0
                .add(delta.scale(0.66D))
                .add(sideA.scale(symmetric(random) * bend))
                .add(sideB.scale(symmetric(random) * bend * 0.65D));

        double phaseA = random.nextDouble() * Math.PI * 2.0D;
        double phaseB = random.nextDouble() * Math.PI * 2.0D;
        int samples = Math.max(6, (int) Math.ceil(distance * 2.0D));
        Set<Long> planned = new LinkedHashSet<>();

        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            Vec3 point = cubicBezier(p0, p1, p2, p3, t);

            // Small higher-frequency wobble keeps the centreline from reading as a
            // mathematically perfect Bezier while preserving the larger curve.
            double envelope = Math.sin(Math.PI * t);
            point = point
                    .add(sideA.scale(Math.sin(t * Math.PI * 4.0D + phaseA) * 0.55D * envelope))
                    .add(sideB.scale(Math.sin(t * Math.PI * 5.0D + phaseB) * 0.35D * envelope));

            planned.add(BlockPos.containing(point.x, point.y, point.z).asLong());
        }

        // Guarantee the requested destination is represented even after voxel rounding.
        planned.add(end.asLong());

        List<BlockPos> positions = new ArrayList<>(planned.size());
        for (long packed : planned) {
            positions.add(BlockPos.of(packed));
        }
        return positions;
    }

    /**
     * Preplans short side branches along established portions of the main trunk.
     *
     * Roots are deliberately kept away from both the Core and the live tip. Branches
     * are only geometric plans here; GrowthManager decides when each one wakes up.
     */
    public static List<GrowthBranch> planBranches(
            List<BlockPos> trunk,
            UUID milestoneId
    ) {
        if (trunk.size() < 14) {
            return List.of();
        }

        Random random = new Random(branchSeed(milestoneId, trunk.size()));
        int desiredCount = Math.max(1, Math.min(8, trunk.size() / 12));
        int minRoot = Math.max(3, (int) Math.floor(trunk.size() * 0.16D));
        int maxRoot = Math.min(trunk.size() - 5, (int) Math.floor(trunk.size() * 0.78D));
        int available = Math.max(0, maxRoot - minRoot + 1);
        if (available <= 0) {
            return List.of();
        }

        List<Integer> roots = new ArrayList<>();
        int attempts = desiredCount * 18;
        int minSpacing = Math.max(4, trunk.size() / Math.max(8, desiredCount * 2));

        while (roots.size() < desiredCount && attempts-- > 0) {
            int candidate = minRoot + random.nextInt(available);
            boolean tooClose = false;
            for (int existing : roots) {
                if (Math.abs(existing - candidate) < minSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                roots.add(candidate);
            }
        }
        roots.sort(Integer::compareTo);

        List<GrowthBranch> branches = new ArrayList<>();

        // Spread branch roots around the full circumference of the local trunk axis.
        // A random phase rotates the whole pattern per Milestone, while each branch
        // gets its own sector plus a small jitter. This keeps the result organic but
        // avoids the old "comb" effect where random angles could cluster on one side.
        Random angleRandom = new Random(branchSeed(milestoneId, 0x6A09E667F3BCC909L ^ roots.size()));
        double baseAngle = angleRandom.nextDouble() * Math.PI * 2.0D;
        double sectorWidth = (Math.PI * 2.0D) / roots.size();

        for (int branchIndex = 0; branchIndex < roots.size(); branchIndex++) {
            int rootIndex = roots.get(branchIndex);
            double jitter = symmetric(angleRandom) * sectorWidth * 0.22D;
            double branchAngle = baseAngle + sectorWidth * branchIndex + jitter;

            List<BlockPos> positions = planBranch(
                    trunk,
                    rootIndex,
                    milestoneId,
                    branchIndex,
                    branchAngle
            );
            if (positions.size() >= 3) {
                branches.add(GrowthBranch.fresh(rootIndex, positions));
            }
        }
        return branches;
    }

    private static List<BlockPos> planBranch(
            List<BlockPos> trunk,
            int rootIndex,
            UUID milestoneId,
            int branchIndex,
            double branchAngle
    ) {
        BlockPos root = trunk.get(rootIndex);
        Vec3 p0 = Vec3.atCenterOf(root);

        int beforeIndex = Math.max(0, rootIndex - 2);
        int afterIndex = Math.min(trunk.size() - 1, rootIndex + 2);
        Vec3 tangent = Vec3.atCenterOf(trunk.get(afterIndex))
                .subtract(Vec3.atCenterOf(trunk.get(beforeIndex)));
        if (tangent.lengthSqr() < 1.0E-6D) {
            tangent = new Vec3(0.0D, 1.0D, 0.0D);
        } else {
            tangent = tangent.normalize();
        }

        Vec3 reference = Math.abs(tangent.y) < 0.86D
                ? new Vec3(0.0D, 1.0D, 0.0D)
                : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 sideA = tangent.cross(reference).normalize();
        Vec3 sideB = tangent.cross(sideA).normalize();

        Random random = new Random(branchSeed(milestoneId, root.asLong() ^ branchIndex));
        Vec3 outward = sideA.scale(Math.cos(branchAngle))
                .add(sideB.scale(Math.sin(branchAngle)))
                .add(tangent.scale(symmetric(random) * 0.12D))
                .normalize();

        double length = 4.0D + random.nextDouble() * 11.0D;
        // A mild vertical bias keeps branches varied without making every one point up.
        Vec3 p3 = p0
                .add(outward.scale(length))
                .add(0.0D, symmetric(random) * Math.min(3.0D, length * 0.22D), 0.0D);

        double bend = Math.max(0.8D, length * 0.22D);
        Vec3 p1 = p0
                .add(outward.scale(length * 0.32D))
                .add(sideA.scale(symmetric(random) * bend))
                .add(sideB.scale(symmetric(random) * bend));
        Vec3 p2 = p0
                .add(outward.scale(length * 0.70D))
                .add(sideA.scale(symmetric(random) * bend))
                .add(sideB.scale(symmetric(random) * bend));

        int samples = Math.max(5, (int) Math.ceil(length * 1.65D));
        double phaseA = random.nextDouble() * Math.PI * 2.0D;
        double phaseB = random.nextDouble() * Math.PI * 2.0D;
        Set<Long> planned = new LinkedHashSet<>();

        // Do not include the root itself. It is already part of the main trunk, and
        // omitting it makes the first new branch voxel visibly emerge from that trunk.
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            Vec3 point = cubicBezier(p0, p1, p2, p3, t);
            double envelope = Math.sin(Math.PI * t);
            point = point
                    .add(sideA.scale(Math.sin(t * Math.PI * 3.0D + phaseA) * 0.36D * envelope))
                    .add(sideB.scale(Math.sin(t * Math.PI * 4.0D + phaseB) * 0.28D * envelope));
            planned.add(BlockPos.containing(point.x, point.y, point.z).asLong());
        }

        List<BlockPos> positions = new ArrayList<>(planned.size());
        for (long packed : planned) {
            BlockPos pos = BlockPos.of(packed);
            if (!pos.equals(root)) {
                positions.add(pos);
            }
        }
        return positions;
    }

    /**
     * Expands one centreline point into an organic Resonant volume. Existing Resonance
     * is naturally skipped by cover(), so calling this again at a larger radius only
     * adds the newly earned outer shell.
     */
    public static GrowthVolumeResult coverGrowthVolume(
            ServerLevel level,
            BlockPos center,
            double radius,
            UUID milestoneId
    ) {
        int covered = 0;
        int skipped = 0;
        int unloaded = 0;
        int bounds = (int) Math.ceil(radius + 0.75D);
        Vec3 centerPoint = Vec3.atCenterOf(center);

        for (int dx = -bounds; dx <= bounds; dx++) {
            for (int dy = -bounds; dy <= bounds; dy++) {
                for (int dz = -bounds; dz <= bounds; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Vec3 blockCenter = Vec3.atCenterOf(pos);
                    double distance = blockCenter.distanceTo(centerPoint);

                    // Edge noise roughens the shell without making neighbouring
                    // centreline points wildly different in thickness.
                    double edgeNoise = 0.90D + positionNoise(pos, milestoneId) * 0.20D;
                    if (distance > radius * edgeNoise) {
                        continue;
                    }

                    if (!level.hasChunkAt(pos)) {
                        unloaded++;
                        continue;
                    }

                    if (cover(level, pos, milestoneId)) {
                        covered++;
                    } else {
                        skipped++;
                    }
                }
            }
        }

        return new GrowthVolumeResult(covered, skipped, unloaded);
    }

    /** Immediate mature tendril retained as a debug primitive. */
    public static GrowthResult growTendril(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            UUID milestoneId
    ) {
        List<BlockPos> centres = planTendril(start, end, milestoneId);
        int covered = 0;
        int skipped = 0;

        for (int i = 0; i < centres.size(); i++) {
            // A modest mature radius is enough for this legacy debug helper. Timed
            // growth uses GrowthManager's smoother per-node mature radius instead.
            GrowthVolumeResult result = coverGrowthVolume(
                    level,
                    centres.get(i),
                    2.45D,
                    milestoneId
            );
            covered += result.covered();
            skipped += result.skipped() + result.unloaded();
        }
        return new GrowthResult(covered, skipped);
    }

    /**
     * Strips one Resonant position and restores its exact saved original state.
     */
    /** Returns a snapshot of all Resonance owned by this Milestone in the level. */
    public static List<ResonanceRecord> records(ServerLevel level, UUID milestoneId) {
        return data(level.getServer()).forMilestone(dimension(level), milestoneId);
    }

    /**
     * Moves one owned Resonant voxel into the sparse young texture as the first stage
     * of regression. No maturation tick is scheduled here; RetractionManager owns the
     * lifetime of this temporary faded state.
     */
    public static FadeOutcome fadeOwnedForRetraction(ServerLevel level, BlockPos pos, UUID milestoneId) {
        if (!level.hasChunkAt(pos)) {
            return FadeOutcome.UNLOADED;
        }

        String dimension = dimension(level);
        ResonanceRecord record = data(level.getServer()).get(dimension, pos).orElse(null);
        if (record == null || !record.milestoneId().equals(milestoneId)) {
            return FadeOutcome.STALE;
        }

        BlockState current = level.getBlockState(pos);
        if (!isResonantState(current)) {
            data(level.getServer()).remove(dimension, pos);
            return FadeOutcome.STALE;
        }

        if (current.is(ModBlocks.YOUNG_RESONANT_BLOCK.get())) {
            return FadeOutcome.FADED;
        }

        if (level.setBlock(
                pos,
                ModBlocks.YOUNG_RESONANT_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        )) {
            return FadeOutcome.FADED;
        }

        return FadeOutcome.FAILED;
    }

    /**
     * Restores exactly one Resonant position, but only if it is still owned by the
     * requested Milestone. Used by timed retraction so an old plan can never peel
     * Resonance that has since been replaced by another Milestone.
     */
    public static RestoreOutcome restoreOwned(ServerLevel level, BlockPos pos, UUID milestoneId) {
        if (!level.hasChunkAt(pos)) {
            return RestoreOutcome.UNLOADED;
        }

        String dimension = dimension(level);
        ResonanceSavedData data = data(level.getServer());
        ResonanceRecord record = data.get(dimension, pos).orElse(null);

        if (record == null || !record.milestoneId().equals(milestoneId)) {
            return RestoreOutcome.STALE;
        }

        if (!isResonantState(level.getBlockState(pos))) {
            data.remove(dimension, pos);
            return RestoreOutcome.STALE;
        }

        GlowLichenManager.removeSupportedBy(level, pos, milestoneId);
        if (level.setBlock(pos, record.originalState(), Block.UPDATE_ALL)) {
            data.remove(dimension, pos);
            return RestoreOutcome.RESTORED;
        }

        return RestoreOutcome.FAILED;
    }

    public static boolean strip(ServerLevel level, BlockPos pos) {
        String dimension = dimension(level);
        ResonanceSavedData data = data(level.getServer());
        ResonanceRecord record = data.get(dimension, pos).orElse(null);

        if (record == null) {
            return false;
        }

        if (!isResonantState(level.getBlockState(pos))) {
            // Something external replaced the temporary block. Do not overwrite it.
            data.remove(dimension, pos);
            return false;
        }

        GlowLichenManager.removeSupportedBy(level, pos, record.milestoneId());
        boolean changed = level.setBlock(pos, record.originalState(), Block.UPDATE_ALL);
        if (changed) {
            data.remove(dimension, pos);
            // Stripping removes the visible coating, but a still-living Milestone
            // remembers the position and may reclaim it after a cooldown.
            RegenerationManager.scheduleAfterStrip(level, record);
        }
        return changed;
    }

    /**
     * Restores every currently Resonant block in this dimension owned by one Milestone.
     * This intentionally works from a snapshot so removing records while iterating is safe.
     */
    public static RetractionResult retract(ServerLevel level, UUID milestoneId) {
        RegenerationManager.cancelMilestone(level.getServer(), milestoneId);
        String dimension = dimension(level);
        ResonanceSavedData data = data(level.getServer());
        List<ResonanceRecord> records = data.forMilestone(dimension, milestoneId);

        int restored = 0;
        int stale = 0;
        int failed = 0;

        for (ResonanceRecord record : records) {
            BlockPos pos = record.pos();
            BlockState current = level.getBlockState(pos);

            GlowLichenManager.removeSupportedBy(level, pos, milestoneId);

            if (!isResonantState(current)) {
                data.remove(dimension, pos);
                stale++;
                continue;
            }

            if (level.setBlock(pos, record.originalState(), Block.UPDATE_ALL)) {
                data.remove(dimension, pos);
                restored++;
            } else {
                failed++;
            }
        }

        return new RetractionResult(restored, stale, failed);
    }

    public static int count(ServerLevel level, UUID milestoneId) {
        return data(level.getServer()).forMilestone(dimension(level), milestoneId).size();
    }

    public static boolean canCover(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(ModBlocks.MILESTONE_CORE.get()) || isResonantState(state)) {
            return false;
        }
        if (state.hasBlockEntity() || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }

        if (state.isAir()) {
            return true;
        }

        return state.getDestroySpeed(level, pos) >= 0.0F;
    }


    public static boolean isResonantState(BlockState state) {
        return state.is(ModBlocks.RESONANT_BLOCK.get())
                || state.is(ModBlocks.YOUNG_RESONANT_BLOCK.get());
    }

    private static Vec3 cubicBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double oneMinusT = 1.0D - t;
        double a = oneMinusT * oneMinusT * oneMinusT;
        double b = 3.0D * oneMinusT * oneMinusT * t;
        double c = 3.0D * oneMinusT * t * t;
        double d = t * t * t;

        return new Vec3(
                p0.x * a + p1.x * b + p2.x * c + p3.x * d,
                p0.y * a + p1.y * b + p2.y * c + p3.y * d,
                p0.z * a + p1.z * b + p2.z * c + p3.z * d
        );
    }

    private static double symmetric(Random random) {
        return random.nextDouble() * 2.0D - 1.0D;
    }

    private static long tendrilSeed(BlockPos start, BlockPos end, UUID milestoneId) {
        long seed = start.asLong();
        seed ^= Long.rotateLeft(end.asLong(), 23);
        seed ^= milestoneId.getMostSignificantBits();
        seed ^= Long.rotateLeft(milestoneId.getLeastSignificantBits(), 41);
        return seed;
    }

    private static long branchSeed(UUID milestoneId, long salt) {
        long seed = milestoneId.getMostSignificantBits();
        seed ^= Long.rotateLeft(milestoneId.getLeastSignificantBits(), 31);
        seed ^= Long.rotateLeft(salt, 17);
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdl;
        seed ^= seed >>> 33;
        return seed;
    }

    private static double positionNoise(BlockPos pos, UUID milestoneId) {
        long value = pos.asLong()
                ^ milestoneId.getMostSignificantBits()
                ^ Long.rotateLeft(milestoneId.getLeastSignificantBits(), 17);

        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;

        return (value & 0xFFFFL) / 65535.0D;
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public record GrowthVolumeResult(int covered, int skipped, int unloaded) {
    }

    public record GrowthResult(int covered, int skipped) {
    }

    public enum FadeOutcome {
        FADED,
        STALE,
        UNLOADED,
        FAILED
    }

    public enum RestoreOutcome {
        RESTORED,
        STALE,
        FAILED,
        UNLOADED
    }

    public record RetractionResult(int restored, int stale, int failed) {
    }
}
