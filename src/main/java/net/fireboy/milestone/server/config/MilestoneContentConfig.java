package net.fireboy.milestone.server.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.minecraft.server.MinecraftServer;
import net.fireboy.milestone.task.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Server-owned editable task definitions and six Core profiles.
 * Config files live in <instance>/config/milestone, NOT inside the jar.
 * Parse on startup, snapshot assignments by ID, and sync view data with menu opens.
 */
public final class MilestoneContentConfig {
    private static final Logger LOG = LoggerFactory.getLogger(MilestoneContentConfig.class);
    private static volatile Snapshot serverSnapshot;
    private static volatile Snapshot clientSnapshot;
    private MilestoneContentConfig() {}

    public record TaskEntry(MilestoneTaskDefinition task, String category, String difficulty) {}
    public record CoreSettings(int tier, boolean enabled, MilestoneType profile,
                               String name, int minY, int maxY, int minDistance,
                               int maxDistance, double depthFraction,
                               String difficulty, List<String> categories,
                               List<String> taskIds, String schematic, String color,
                               String taskMode, List<Integer> requiresCompletedCores,
                               List<String> allowedBiomes, List<String> blockedBiomes,
                               String dimension, int rewardXp, List<String> rewardCommands) {
        public CoreSettings {
            categories = List.copyOf(categories);
            taskIds = List.copyOf(taskIds);
            requiresCompletedCores = List.copyOf(requiresCompletedCores);
            allowedBiomes = List.copyOf(allowedBiomes);
            blockedBiomes = List.copyOf(blockedBiomes);
            rewardCommands = List.copyOf(rewardCommands);
        }
    }
    public record Snapshot(Map<String, TaskEntry> tasks,
                           Map<Integer, CoreSettings> cores, String payload) {
        public Snapshot {
            tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
            cores = Collections.unmodifiableMap(new LinkedHashMap<>(cores));
        }
        public CoreSettings core(int tier) { return cores.get(tier); }
    }

    public static void onServerStarting(ServerStartingEvent event) { loadServer(); }

    /** Re-read files only on server boot. Previously assigned task IDs are persistent. */
    public static synchronized void loadServer() {
        try {
            Path directory = FMLPaths.CONFIGDIR.get().resolve("milestone");
            Files.createDirectories(directory);
            String tasks = readConfig(directory, "tasks.json");
            String cores = readConfig(directory, "cores.json");
            serverSnapshot = parse(tasks, cores);
            LOG.info("Loaded {} custom tasks and {} Core configurations from {}",
                    serverSnapshot.tasks.size(), serverSnapshot.cores.size(), directory);
        } catch (Exception exception) {
            LOG.error("Invalid Milestone configuration; retaining previous/default definitions", exception);
            if (serverSnapshot == null) serverSnapshot = defaults();
        }
    }

    public static Snapshot server() {
        if (serverSnapshot == null) loadServer();
        return serverSnapshot;
    }

    public static Snapshot client() {
        if (clientSnapshot == null) clientSnapshot = defaults();
        return clientSnapshot;
    }

    public static void installClient(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            clientSnapshot = parse(root.getAsJsonObject("tasks").toString(),
                    root.getAsJsonObject("cores").toString());
        } catch (Exception exception) {
            LOG.warn("Invalid Milestone menu task configuration", exception);
        }
    }

    private static String readConfig(Path folder, String name) throws IOException {
        Path file = folder.resolve(name);
        if (!Files.exists(file)) Files.writeString(file, resource(name), StandardCharsets.UTF_8);
        if (Files.size(file) > 30000) throw new IOException(name + " exceeds 30 KiB");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = MilestoneContentConfig.class.getResourceAsStream(
                "/defaults/milestone/" + name)) {
            if (stream == null) throw new IOException("Missing default " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Snapshot defaults() {
        try {
            return parse(resource("tasks.json"), resource("cores.json"));
        } catch (Exception exception) {
            throw new IllegalStateException("Milestone defaults missing", exception);
        }
    }

    private static Snapshot parse(String tasksJson, String coresJson) {
        JsonObject tasksRoot = JsonParser.parseString(tasksJson).getAsJsonObject();
        JsonObject coresRoot = JsonParser.parseString(coresJson).getAsJsonObject();
        Map<String, TaskEntry> tasks = new LinkedHashMap<>();
        for (JsonElement element : tasksRoot.getAsJsonArray("tasks")) {
            try {
                JsonObject o = element.getAsJsonObject();
                String id = str(o, "id", "");
                if (!id.matches("[a-z0-9_./-]{1,64}") || tasks.containsKey(id))
                    throw new IllegalArgumentException("Invalid/duplicate task ID: " + id);
                String clue = str(o, "clue", id);
                if (clue.length() > 256) throw new IllegalArgumentException("Clue exceeds 256 characters");
                String kind = str(o, "type", "");
                MilestoneTaskDefinition definition;
                switch (kind) {
                    case "item" -> {
                        ResourceLocation itemId = requiredId(o, "item");
                        if (!BuiltInRegistries.ITEM.containsKey(itemId))
                            throw new IllegalArgumentException("Unknown item " + itemId);
                        int count = number(o, "count", 1);
                        if (count < 1 || count > 2304)
                            throw new IllegalArgumentException("Item count must be 1-2304");
                        definition = new ItemSubmissionTaskDefinition(
                                id, BuiltInRegistries.ITEM.get(itemId), count, clue);
                    }
                    case "kill" -> {
                        ResourceLocation entityId = requiredId(o, "entity");
                        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityId))
                            throw new IllegalArgumentException("Unknown entity " + entityId);
                        int count = number(o, "count", 1);
                        if (count < 1 || count > 1000)
                            throw new IllegalArgumentException("Kill count must be 1-1000");
                        definition = new KillTaskDefinition(
                                id, BuiltInRegistries.ENTITY_TYPE.get(entityId), clue, count);
                    }
                    case "advancement" -> definition = new AdvancementTaskDefinition(
                            id, requiredId(o, "advancement"), clue);
                    default -> throw new IllegalArgumentException("Unknown task type " + kind);
                }
                tasks.put(id, new TaskEntry(definition,
                        str(o, "category", "general"), str(o, "difficulty", "normal")));
            } catch (Exception exception) {
                LOG.warn("Skipping invalid Milestone task: {}", exception.getMessage());
            }
        }
        Map<Integer, CoreSettings> cores = new LinkedHashMap<>();
        for (JsonElement element : coresRoot.getAsJsonArray("cores")) {
            try {
                JsonObject o = element.getAsJsonObject();
                int tier = number(o, "tier", -1);
                if (tier < 0 || tier >= 6 || cores.containsKey(tier))
                    throw new IllegalArgumentException("Tier must be unique and in 0-5");
                int minY = number(o, "min_y", -54), maxY = number(o, "max_y", 120);
                if (minY > maxY) throw new IllegalArgumentException("min_y must be <= max_y");
                int minDistance = number(o, "min_distance", 0);
                int maxDistance = number(o, "max_distance", 0);
                if (minDistance < 0 || maxDistance < 0 ||
                        ((minDistance != 0 || maxDistance != 0) && maxDistance < minDistance))
                    throw new IllegalArgumentException("Invalid spawn distance limits");
                double depth = o.has("depth_fraction") ? o.get("depth_fraction").getAsDouble() : .5;
                if (!Double.isFinite(depth) || depth < 0 || depth > 1)
                    throw new IllegalArgumentException("depth_fraction must be 0-1");
                String schematic = str(o, "schematic", "generated");
                if (!schematic.equals("generated") &&
                        !schematic.matches("[a-z0-9_-]{1,64}\\.nbt"))
                    throw new IllegalArgumentException("Schematic must be generated or a safe .nbt filename");
                String color = str(o, "color", "#39D8DB");
                if (!color.matches("#[a-fA-F0-9]{6}"))
                    throw new IllegalArgumentException("Color must be #RRGGBB");
                String profile = str(o, "profile", "generic");
                MilestoneType type = profile.equalsIgnoreCase("nether_gate")
                        ? MilestoneType.NETHER_GATE : MilestoneType.GENERIC;
                String mode = str(o, "task_mode", o.has("task_ids")
                        && o.getAsJsonArray("task_ids").size() > 0 ? "manual" : "preset");
                if (!mode.equals("manual") && !mode.equals("preset"))
                    throw new IllegalArgumentException("task_mode must be preset or manual");
                List<String> selected = strings(o, "task_ids");
                if (mode.equals("manual") && selected.isEmpty())
                    throw new IllegalArgumentException("Manual mode requires task_ids");
                List<Integer> prerequisites = ints(o, "requires_completed_cores");
                if (prerequisites.stream().anyMatch(t -> t < 0 || t >= 6 || t == tier))
                    throw new IllegalArgumentException("Invalid prerequisite tier or self-dependency");
                int xp = number(o, "reward_xp", 0);
                if (xp < 0 || xp > 100000) throw new IllegalArgumentException("Invalid reward_xp");
                List<String> commands = strings(o, "reward_commands");
                if (commands.size() > 16 || commands.stream().anyMatch(c -> c.length() > 256))
                    throw new IllegalArgumentException("Too many/long reward commands");
                String dimension = str(o, "dimension", "minecraft:overworld");
                if (ResourceLocation.tryParse(dimension) == null)
                    throw new IllegalArgumentException("Invalid dimension ID");
                cores.put(tier, new CoreSettings(tier,
                        !o.has("enabled") || o.get("enabled").getAsBoolean(),
                        type, str(o, "name", "Milestone " + (tier + 1)),
                        minY, maxY, minDistance, maxDistance, depth,
                        str(o, "difficulty", "normal"), strings(o, "categories"),
                        selected, schematic, color, mode, prerequisites,
                        strings(o, "allowed_biomes"), strings(o, "blocked_biomes"),
                        dimension, xp, commands));
            } catch (Exception exception) {
                LOG.warn("Skipping invalid Milestone Core configuration: {}", exception.getMessage());
            }
        }
        validatePrerequisiteGraph(cores);
        JsonObject combined = new JsonObject();
        combined.add("tasks", tasksRoot);
        combined.add("cores", coresRoot);
        return new Snapshot(tasks, cores, combined.toString());
    }

    public static List<MilestoneTaskDefinition> pool(Snapshot snapshot, int tier,
                                                       MilestoneType type,
                                                       List<MilestoneTaskDefinition> legacy) {
        CoreSettings core = snapshot.core(tier);
        if (core == null) return legacy;
        List<MilestoneTaskDefinition> result = new ArrayList<>();
        if (core.taskMode().equals("manual")) {
            for (String id : core.taskIds()) {
                TaskEntry entry = snapshot.tasks().get(id);
                if (entry != null) result.add(entry.task());
                else for (MilestoneTaskDefinition original : legacy) {
                    if (original.id().equals(id)) { result.add(original); break; }
                }
            }
        } else {
            for (TaskEntry entry : snapshot.tasks().values()) {
                if ((core.difficulty().equals("*") || core.difficulty().equals(entry.difficulty()))
                        && (core.categories().isEmpty() ||
                            core.categories().contains(entry.category()))) {
                    result.add(entry.task());
                }
            }
        }
        // Preserve working gameplay if a profile has no matching eligible tasks.
        return result.isEmpty() ? legacy : List.copyOf(result);
    }

    /** An unlock gate for the entire cooperating server, not an individual player. */
    public static boolean prerequisitesMet(MinecraftServer server, MilestoneRecord record) {
        CoreSettings settings = server().core(record.coreTier());
        if (settings == null || settings.requiresCompletedCores().isEmpty()) return true;
        for (int tier : settings.requiresCompletedCores()) {
            boolean completed = false;
            for (MilestoneRecord candidate : MilestoneManager.data(server).all()) {
                if (candidate.coreTier() == tier && candidate.state() == MilestoneState.COMPLETED) {
                    completed = true;
                    break;
                }
            }
            if (!completed) return false;
        }
        return true;
    }

    private static void validatePrerequisiteGraph(Map<Integer, CoreSettings> cores) {
        for (Integer tier : cores.keySet()) {
            Set<Integer> visited = new HashSet<>();
            Set<Integer> frontier = new HashSet<>();
            checkCycles(tier, cores, visited, frontier);
        }
    }
    private static void checkCycles(int tier, Map<Integer, CoreSettings> cores,
                                     Set<Integer> visited, Set<Integer> frontier) {
        if (frontier.contains(tier)) throw new IllegalArgumentException(
                "Cycle in Core prerequisites involving tier " + tier);
        if (!visited.add(tier)) return;
        frontier.add(tier);
        CoreSettings core = cores.get(tier);
        if (core != null) for (int prerequisite : core.requiresCompletedCores())
            checkCycles(prerequisite, cores, visited, frontier);
        frontier.remove(tier);
    }
    private static List<Integer> ints(JsonObject o, String key) {
        if (!o.has(key)) return List.of();
        List<Integer> list = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(key)) list.add(el.getAsInt());
        return list;
    }

    private static ResourceLocation requiredId(JsonObject o, String key) {
        ResourceLocation id = ResourceLocation.tryParse(str(o, key, ""));
        if (id == null) throw new IllegalArgumentException("Invalid resource ID in " + key);
        return id;
    }
    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }
    private static int number(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }
    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key)) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : o.getAsJsonArray(key)) result.add(element.getAsString());
        return result;
    }
}
