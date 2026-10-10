package net.fireboy.milestone.task;

import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mixed task catalogs keyed by Milestone type.
 *
 * The generic catalog remains useful for development. NETHER_GATE is the first
 * real progression profile: every objective is intentionally completable in the
 * Overworld so the Milestone never requires access to the dimension it unlocks.
 */
public final class MilestoneTaskCatalog {
    private static final List<MilestoneTaskDefinition> GENERIC_TASKS = List.of(
            new ItemSubmissionTaskDefinition(
                    "oak_fruit",
                    Items.APPLE,
                    4,
                    "Bring four fruits most often found beneath oak leaves."
            ),
            new ItemSubmissionTaskDefinition(
                    "common_metal",
                    Items.IRON_INGOT,
                    16,
                    "Offer sixteen bars smelted from the world's most familiar metal."
            ),
            new ItemSubmissionTaskDefinition(
                    "geode_heart",
                    Items.AMETHYST_SHARD,
                    8,
                    "Bring eight purple shards broken from a geode's heart."
            ),
            new ItemSubmissionTaskDefinition(
                    "living_redstone",
                    Items.REDSTONE,
                    32,
                    "Offer thirty-two dusts capable of carrying a signal."
            ),
            new ItemSubmissionTaskDefinition(
                    "slime_remains",
                    Items.SLIME_BALL,
                    4,
                    "Bring four sticky remnants of a creature that refuses to stay still."
            ),
            new ItemSubmissionTaskDefinition(
                    "enchanters_blue",
                    Items.LAPIS_LAZULI,
                    16,
                    "Offer sixteen blue stones prized by enchanters."
            ),
            new ItemSubmissionTaskDefinition(
                    "worked_hide",
                    Items.LEATHER,
                    8,
                    "Bring eight pieces of worked hide."
            ),
            new ItemSubmissionTaskDefinition(
                    "hive_sweetness",
                    Items.HONEY_BOTTLE,
                    4,
                    "Bring four bottles filled with the sweetness of a hive."
            ),

            new KillTaskDefinition(
                    "hunt_enderman",
                    EntityType.ENDERMAN,
                    "Personally bring down a tall wanderer that slips between spaces."
            ),
            new KillTaskDefinition(
                    "hunt_witch",
                    EntityType.WITCH,
                    "Personally silence a hostile brewer before another potion can be thrown."
            ),
            new KillTaskDefinition(
                    "hunt_pillager",
                    EntityType.PILLAGER,
                    "Personally defeat one of the crossbow-bearing raiders."
            ),
            new KillTaskDefinition(
                    "hunt_ravager",
                    EntityType.RAVAGER,
                    "Personally fell the great beast that charges beside a raid."
            ),

            new AdvancementTaskDefinition(
                    "advance_trade",
                    ResourceLocation.parse("minecraft:adventure/trade"),
                    "Complete the advancement earned by making a fair deal with a villager."
            ),
            new AdvancementTaskDefinition(
                    "advance_take_aim",
                    ResourceLocation.parse("minecraft:adventure/shoot_arrow"),
                    "Complete the advancement earned by successfully striking a target with an arrow."
            ),
            new AdvancementTaskDefinition(
                    "advance_enchant",
                    ResourceLocation.parse("minecraft:story/enchant_item"),
                    "Complete the advancement earned by enchanting an item at a table."
            ),
            new AdvancementTaskDefinition(
                    "advance_monster_hunter",
                    ResourceLocation.parse("minecraft:adventure/kill_a_mob"),
                    "Complete the advancement earned by defeating a hostile creature."
            )
    );

    private static final List<MilestoneTaskDefinition> NETHER_GATE_TASKS = List.of(
            new ItemSubmissionTaskDefinition(
                    "nether_obsidian",
                    Items.OBSIDIAN,
                    8,
                    "Offer eight blocks born where water conquers a source of lava."
            ),
            new ItemSubmissionTaskDefinition(
                    "nether_gold",
                    Items.GOLD_INGOT,
                    24,
                    "Offer twenty-four bars of the metal prized beyond the gate."
            ),
            new ItemSubmissionTaskDefinition(
                    "nether_pearls",
                    Items.ENDER_PEARL,
                    4,
                    "Bring four pearls taken from wanderers that step between spaces."
            ),
            new ItemSubmissionTaskDefinition(
                    "nether_diamond",
                    Items.DIAMOND,
                    2,
                    "Offer two stones found only after reaching the deepest veins of the Overworld."
            ),
            new ItemSubmissionTaskDefinition(
                    "nether_golden_apple",
                    Items.GOLDEN_APPLE,
                    1,
                    "Bring a fruit wrapped in gold, prepared for a dangerous crossing."
            ),
            new ItemSubmissionTaskDefinition(
                    "nether_flint_steel",
                    Items.FLINT_AND_STEEL,
                    1,
                    "Offer the simple tool used to wake a frame of obsidian."
            ),

            new KillTaskDefinition(
                    "nether_hunt_enderman",
                    EntityType.ENDERMAN,
                    "Personally defeat an Enderman and prove you can survive a creature touched by another realm."
            ),
            new KillTaskDefinition(
                    "nether_hunt_witch",
                    EntityType.WITCH,
                    "Personally defeat a witch and endure a battle fought through fire, poison, and potions."
            ),
            new KillTaskDefinition(
                    "nether_hunt_evoker",
                    EntityType.EVOKER,
                    "Personally defeat an Evoker, one of the Overworld's most dangerous spellcasters."
            ),
            new KillTaskDefinition(
                    "nether_hunt_ravager",
                    EntityType.RAVAGER,
                    "Personally bring down a Ravager during a raid."
            ),

            new AdvancementTaskDefinition(
                    "nether_advance_diamond",
                    ResourceLocation.parse("minecraft:story/mine_diamond"),
                    "Earn the advancement for obtaining diamonds from the depths."
            ),
            new AdvancementTaskDefinition(
                    "nether_advance_hot_stuff",
                    ResourceLocation.parse("minecraft:story/lava_bucket"),
                    "Earn the advancement for filling a bucket with lava."
            ),
            new AdvancementTaskDefinition(
                    "nether_advance_voluntary_exile",
                    ResourceLocation.parse("minecraft:adventure/voluntary_exile"),
                    "Earn the advancement tied to defeating an ominous raid captain."
            ),
            new AdvancementTaskDefinition(
                    "nether_advance_hero",
                    ResourceLocation.parse("minecraft:adventure/hero_of_the_village"),
                    "Earn the advancement granted for successfully defending a village from a raid."
            )
    );

    private MilestoneTaskCatalog() {
    }

    public static List<MilestoneTaskDefinition> tasksFor(MilestoneRecord record) {
        return MilestoneContentConfig.pool(MilestoneContentConfig.server(),
                record.coreTier(), record.type(), tasksFor(record.type()));
    }

    public static List<MilestoneTaskDefinition> tasksFor(MilestoneType type) {
        return switch (type) {
            case NETHER_GATE -> NETHER_GATE_TASKS;
            case GENERIC -> GENERIC_TASKS;
        };
    }

    public static int indexOf(MilestoneRecord record, String id) {
        List<MilestoneTaskDefinition> tasks = tasksFor(record);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    public static MilestoneTaskDefinition byIndex(MilestoneType type, int tier, int index) {
        List<MilestoneTaskDefinition> tasks = MilestoneContentConfig.pool(
                MilestoneContentConfig.client(), tier, type, tasksFor(type));
        return index >= 0 && index < tasks.size() ? tasks.get(index) : null;
    }

    public static int indexOf(MilestoneType type, String id) {
        List<MilestoneTaskDefinition> tasks = tasksFor(type);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public static MilestoneTaskDefinition byIndex(MilestoneType type, int index) {
        List<MilestoneTaskDefinition> tasks = tasksFor(type);
        return index >= 0 && index < tasks.size() ? tasks.get(index) : null;
    }

    public static MilestoneTaskDefinition get(MilestoneRecord record, String id) {
        for (MilestoneTaskDefinition definition : tasksFor(record)) {
            if (definition.id().equals(id)) {
                return definition;
            }
        }
        return null;
    }

    /**
     * Assigns different tasks to players at the same Milestone until the pool is
     * exhausted. Advancement tasks already completed by that player are skipped
     * so a newly assigned objective never starts already complete.
     */
    public static MilestoneTaskDefinition choose(MilestoneRecord record, ServerPlayer player) {
        List<MilestoneTaskDefinition> pool = tasksFor(record);
        if (pool.isEmpty()) {
            throw new IllegalStateException("Milestone task pool is empty");
        }

        Set<String> used = new HashSet<>();
        for (TaskAssignment assignment : record.assignments().values()) {
            used.add(assignment.taskId());
        }

        UUID playerId = player.getUUID();
        long mixed = record.id().getMostSignificantBits()
                ^ record.id().getLeastSignificantBits()
                ^ playerId.getMostSignificantBits()
                ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 17);
        int start = Math.floorMod(Long.hashCode(mixed), pool.size());

        // First pass: unique + currently available.
        for (int offset = 0; offset < pool.size(); offset++) {
            MilestoneTaskDefinition candidate = pool.get((start + offset) % pool.size());
            if (!used.contains(candidate.id()) && isAvailableTo(player, candidate)) {
                return candidate;
            }
        }

        // Second pass: task reuse is allowed once the unique available pool is exhausted.
        for (int offset = 0; offset < pool.size(); offset++) {
            MilestoneTaskDefinition candidate = pool.get((start + offset) % pool.size());
            if (isAvailableTo(player, candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Milestone task pool has no task available to this player");
    }

    /** Debug/admin reroll helper: choose a different available task when possible. */
    public static MilestoneTaskDefinition chooseDifferent(
            MilestoneRecord record,
            ServerPlayer player,
            String previousTaskId
    ) {
        List<MilestoneTaskDefinition> pool = tasksFor(record);
        if (pool.isEmpty()) {
            throw new IllegalStateException("Milestone task pool is empty");
        }

        Set<String> usedByOthers = new HashSet<>();
        for (TaskAssignment assignment : record.assignments().values()) {
            if (!assignment.playerId().equals(player.getUUID())) {
                usedByOthers.add(assignment.taskId());
            }
        }

        int previousIndex = indexOf(record, previousTaskId);
        int start = previousIndex < 0 ? 0 : (previousIndex + 1) % pool.size();

        for (int offset = 0; offset < pool.size(); offset++) {
            MilestoneTaskDefinition candidate = pool.get((start + offset) % pool.size());
            if (!candidate.id().equals(previousTaskId)
                    && !usedByOthers.contains(candidate.id())
                    && isAvailableTo(player, candidate)) {
                return candidate;
            }
        }

        for (int offset = 0; offset < pool.size(); offset++) {
            MilestoneTaskDefinition candidate = pool.get((start + offset) % pool.size());
            if (!candidate.id().equals(previousTaskId) && isAvailableTo(player, candidate)) {
                return candidate;
            }
        }

        return choose(record, player);
    }

    public static boolean isAvailableTo(ServerPlayer player, MilestoneTaskDefinition definition) {
        if (!(definition instanceof AdvancementTaskDefinition advancementTask)) {
            return true;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        AdvancementHolder advancement = server.getAdvancements().get(advancementTask.advancementId());
        return advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
