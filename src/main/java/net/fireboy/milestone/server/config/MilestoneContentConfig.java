package net.fireboy.milestone.server.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.fireboy.milestone.shop.MilestoneShop;
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
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Server-owned editable task definitions and dynamically sized Core profiles.
 * Config files live in <instance>/config/milestone, NOT inside the jar.
 * Parse on startup, snapshot assignments by ID, and sync view data with menu opens.
 */
public final class MilestoneContentConfig {
    private static final Logger LOG = LoggerFactory.getLogger(MilestoneContentConfig.class);
    private static volatile Snapshot serverSnapshot;
    private static volatile Snapshot clientSnapshot;
    private MilestoneContentConfig() {}

    public record TaskEntry(MilestoneTaskDefinition task, String category, String difficulty) {}
    public record RewardItem(ResourceLocation item, int count) {}
    public record CoreSettings(int tier, boolean enabled, MilestoneType profile,
                               String name, int minY, int maxY, int minDistance,
                               int maxDistance, double depthFraction,
                               String difficulty, List<String> categories,
                               List<String> taskIds, String schematic, String color,
                               String taskMode, List<Integer> requiresCompletedCores,
                               List<String> allowedBiomes, List<String> blockedBiomes,
                               String dimension, int rewardXp, List<String> rewardCommands,
                               List<RewardItem> rewardItems) {
        public CoreSettings {
            categories = List.copyOf(categories);
            taskIds = List.copyOf(taskIds);
            requiresCompletedCores = List.copyOf(requiresCompletedCores);
            allowedBiomes = List.copyOf(allowedBiomes);
            blockedBiomes = List.copyOf(blockedBiomes);
            rewardCommands = List.copyOf(rewardCommands);
            rewardItems = List.copyOf(rewardItems);
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

    /** Re-read the two files, retaining the prior working version on validation failure. */
    public static synchronized boolean reloadServer() {
        try {
            Path directory = FMLPaths.CONFIGDIR.get().resolve("milestone");
            Files.createDirectories(directory);
            String tasks = readConfig(directory, "tasks.json");
            String cores = readConfig(directory, "cores.json");
            Snapshot candidate = parse(tasks, cores);
            JsonObject newRoot = JsonParser.parseString(cores).getAsJsonObject();
            MilestoneShop.parse(newRoot, candidate.cores().keySet());
            MilestoneFeatureLocks.install(newRoot, candidate.cores().keySet());
            serverSnapshot = candidate;
            LOG.info("Loaded {} custom tasks and {} Cores from {}",
                    candidate.tasks.size(), candidate.cores.size(), directory);
            return true;
        } catch (Exception exception) {
            LOG.error("Milestone config invalid. Keeping last valid configuration", exception);
            if (serverSnapshot == null) serverSnapshot = defaults();
            return false;
        }
    }

    public static void loadServer() { reloadServer(); }

    /** Entire draft commits as a unit after strict validation, never field-by-field. */
    public static synchronized String saveBundle(String bundle) {
        return saveBundle(bundle, null);
    }

    public static synchronized String saveBundle(String bundle, MinecraftServer runningServer) {
        if (bundle == null || bundle.length() > 64000) return "Config exceeds 64 KiB";
        try {
            JsonObject root = JsonParser.parseString(bundle).getAsJsonObject();
            JsonObject taskRoot = root.getAsJsonObject("tasks");
            JsonObject coreRoot = root.getAsJsonObject("cores");
            if (taskRoot == null || coreRoot == null) return "Missing tasks or cores";
            if (!coreRoot.has("enabled")) coreRoot.addProperty("enabled", true);
            String tasks = new GsonBuilder().setPrettyPrinting().create().toJson(taskRoot) + "\n";
            String cores = new GsonBuilder().setPrettyPrinting().create().toJson(coreRoot) + "\n";
            Snapshot tested = validateStrict(tasks, cores);
            var newGates = MilestoneFeatureLocks.parse(coreRoot,tested.cores().keySet());
            MilestoneShop.parse(coreRoot,tested.cores().keySet());
            validateSchematicFiles(cores, runningServer != null);
            if (runningServer != null) {
                // Never delete a Core that has already generated. Its persistent
                // milestone ID, rewards, tasks and assigned players must remain intact.
                var saved = net.fireboy.milestone.worldgen.WorldSiteManager.data(runningServer);
                for (var plan : saved.cores()) {
                    if (plan.placed() && !tested.cores().containsKey(plan.tier()))
                        return "Cannot delete Core " + (plan.tier() + 1)
                                + ": already placed in this world. Disable it instead.";
                }
                for (MilestoneRecord record : MilestoneManager.data(runningServer).all()) {
                    if (record.coreTier() >= 0 && !tested.cores().containsKey(record.coreTier()))
                        return "Cannot delete Core " + (record.coreTier() + 1)
                                + ": existing milestone progress. Disable it instead.";
                }
            }
            Path folder = FMLPaths.CONFIGDIR.get().resolve("milestone");
            Files.createDirectories(folder);
            if (tasks.getBytes(StandardCharsets.UTF_8).length > 30000
                    || cores.getBytes(StandardCharsets.UTF_8).length > 30000)
                return "Each config file must be smaller than 30 KiB";
            // Roll back both files if a write fails, so Save is all-or-nothing.
            Path tasksFile = folder.resolve("tasks.json"), coresFile = folder.resolve("cores.json");
            String previousTasks = readConfig(folder, "tasks.json");
            String previousCores = readConfig(folder, "cores.json");
            try {
                atomicWrite(tasksFile, tasks);
                atomicWrite(coresFile, cores);
            } catch (Exception writeFailure) {
                atomicWrite(tasksFile, previousTasks);
                atomicWrite(coresFile, previousCores);
                throw writeFailure;
            }
            serverSnapshot = tested;
            MilestoneFeatureLocks.install(coreRoot, tested.cores().keySet());
            if (runningServer != null) MilestoneFeatureLocks.applyRecipes(runningServer,false,true);
            LOG.info("Milestone config saved: {} tasks, {} Core profiles",
                    tested.tasks().size(), tested.cores().size());
            return "Saved Core and task configuration";
        } catch (Exception e) {
            LOG.warn("Rejected Milestone config save", e);
            return "Not saved: " + e.getMessage();
        }
    }

    /** Menu data for the title-screen editor, which modifies local instance config. */
    public static synchronized String localBundle() {
        try {
            Path folder = FMLPaths.CONFIGDIR.get().resolve("milestone");
            Files.createDirectories(folder);
            String tasks = readConfig(folder, "tasks.json");
            String cores = readConfig(folder, "cores.json");
            validateStrict(tasks, cores);
            JsonObject combined = new JsonObject();
            combined.add("tasks", JsonParser.parseString(tasks).getAsJsonObject());
            JsonObject coreRoot = JsonParser.parseString(cores).getAsJsonObject();
            if (!coreRoot.has("enabled")) coreRoot.addProperty("enabled", true);
            combined.add("cores", coreRoot);
            return combined.toString();
        } catch (Exception exception) {
            LOG.warn("Cannot load local Milestone config; using defaults in menu", exception);
            return defaults().payload();
        }
    }

    /** Strict mode rejects silent partial parsing, duplicate IDs and omitted profiles. */
    private static Snapshot validateStrict(String tasks, String cores) {
        JsonObject taskRoot = JsonParser.parseString(tasks).getAsJsonObject();
        JsonObject coreRoot = JsonParser.parseString(cores).getAsJsonObject();
        if (!coreRoot.has("enabled")) coreRoot.addProperty("enabled", true);
        if (!coreRoot.get("enabled").isJsonPrimitive()
                || !coreRoot.get("enabled").getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("enabled must be true or false");
        JsonArray taskList = taskRoot.getAsJsonArray("tasks");
        JsonArray coreList = coreRoot.getAsJsonArray("cores");
        if (taskList == null || coreList == null) throw new IllegalArgumentException("Missing tasks/cores arrays");
        if (taskList.size() > 80 || coreList.size() > 32)
            throw new IllegalArgumentException("Maximum 80 tasks and 32 Core profiles");
        Snapshot checked = parse(tasks, cores);
        MilestoneFeatureLocks.parse(coreRoot,checked.cores().keySet());
        MilestoneShop.parse(coreRoot,checked.cores().keySet());
        if (checked.tasks().size() != taskList.size() || checked.cores().size() != coreList.size())
            throw new IllegalArgumentException("Invalid or duplicate task/Core entry; inspect log");
        return checked;
    }

    private static void atomicWrite(Path path, String contents) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "milestone-", ".json.tmp");
        try {
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Named blueprint references live alongside Core profiles in cores.json. */
    public record SchematicEntry(String id, String name, String file) { }

    public static List<SchematicEntry> schematics(Snapshot snapshot) {
        JsonObject root = JsonParser.parseString(snapshot.payload()).getAsJsonObject()
                .getAsJsonObject("cores");
        List<SchematicEntry> entries = new ArrayList<>();
        if (root.has("schematics")) {
            for (JsonElement element : root.getAsJsonArray("schematics")) {
                JsonObject entry = element.getAsJsonObject();
                entries.add(new SchematicEntry(
                        str(entry, "id", ""), str(entry, "name", ""), str(entry, "file", "")));
            }
        }
        return entries;
    }

    public static SchematicEntry schematic(Snapshot snapshot, String id) {
        for (var entry : schematics(snapshot)) if (entry.id().equals(id)) return entry;
        return null;
    }

    private static void validateSchematicReferences(JsonObject coresRoot, Map<Integer, CoreSettings> cores) {
        Set<String> ids = new HashSet<>();
        if (coresRoot.has("schematics")) {
            JsonArray entries = coresRoot.getAsJsonArray("schematics");
            if (entries.size() > 64) throw new IllegalArgumentException("Maximum 64 schematics");
            for (JsonElement el : entries) {
                JsonObject entry = el.getAsJsonObject();
                String id = str(entry, "id", "");
                String file = str(entry, "file", "");
                String name = str(entry, "name", "");
                if (!id.matches("[a-z0-9_-]{1,64}") || !ids.add(id) || id.equals("generated"))
                    throw new IllegalArgumentException("Invalid or duplicated schematic ID " + id);
                if (!file.matches("[a-zA-Z0-9_.-]{1,100}\\.nbt")
                        || file.startsWith(".") || file.contains(".."))
                    throw new IllegalArgumentException("Invalid schematic filename: " + file);
                if (name.isBlank() || name.length() > 80)
                    throw new IllegalArgumentException("Schematic name must be 1-80 characters");
            }
        }
        for (CoreSettings core : cores.values())
            if (!core.schematic().equals("generated") && !ids.contains(core.schematic()))
                throw new IllegalArgumentException("Core " + core.tier()
                        + " uses missing schematic: " + core.schematic());
    }

    /** Validate real files only on authoritative Save, not when clients parse menu data. */
    private static void validateSchematicFiles(String coresJson, boolean authoritative) throws IOException {
        JsonObject root = JsonParser.parseString(coresJson).getAsJsonObject();
        if (!root.has("schematics")) return;
        for (JsonElement element : root.getAsJsonArray("schematics")) {
            JsonObject o = element.getAsJsonObject();
            String file = str(o, "file", "");
            if (authoritative || !file.isBlank())
                net.fireboy.milestone.milestone.ConfiguredSchematic.validateFile(file);
        }
    }

    public static boolean enabled() {
        JsonObject cores = JsonParser.parseString(server().payload()).getAsJsonObject().getAsJsonObject("cores");
        return !cores.has("enabled") || cores.get("enabled").getAsBoolean();
    }


    private static final Set<String> TEXT_FIELDS = Set.of("name", "profile", "difficulty",
            "dimension", "color", "schematic", "task_mode");
    private static final Set<String> NUMBER_FIELDS = Set.of("min_y", "max_y",
            "min_distance", "max_distance", "reward_xp", "depth_fraction");
    private static final Set<String> LIST_FIELDS = Set.of("categories", "task_ids",
            "requires_completed_cores", "allowed_biomes", "blocked_biomes",
            "reward_commands", "reward_items");

    /** Admin edits are field-whitelisted, validated against all profiles, and written atomically. */
    public static synchronized String updateCoreField(int tier, String field, String value) {
        if (tier < 0 || tier >= 32 || field.length() > 40 || value.length() > 3000)
            return "Invalid Core/field size";
        try {
            Path folder = FMLPaths.CONFIGDIR.get().resolve("milestone");
            Files.createDirectories(folder);
            String original = readConfig(folder, "cores.json");
            JsonObject root = JsonParser.parseString(original).getAsJsonObject();
            JsonObject core = null;
            for (JsonElement elem : root.getAsJsonArray("cores")) {
                JsonObject c = elem.getAsJsonObject();
                if (number(c, "tier", -1) == tier) { core = c; break; }
            }
            if (core == null) return "No configured Core for tier " + tier;
            if (TEXT_FIELDS.contains(field)) {
                core.addProperty(field, value.trim());
            } else if (field.equals("enabled")) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                    return "enabled must be true or false";
                core.addProperty(field, Boolean.parseBoolean(value));
            } else if (NUMBER_FIELDS.contains(field)) {
                if (field.equals("depth_fraction")) core.addProperty(field, Double.parseDouble(value));
                else core.addProperty(field, Integer.parseInt(value));
            } else if (LIST_FIELDS.contains(field)) {
                JsonArray array = new JsonArray();
                if (field.equals("reward_items")) {
                    if (!value.isBlank()) for (String spec : value.split(",")) {
                        String[] parts = spec.trim().split("\\*");
                        if (parts.length != 2) return "Use item_id*count, item_id*count";
                        JsonObject reward = new JsonObject();
                        reward.addProperty("item", parts[0].trim());
                        reward.addProperty("count", Integer.parseInt(parts[1].trim()));
                        array.add(reward);
                    }
                } else if (!value.isBlank()) {
                    String[] entries = field.equals("reward_commands") ? value.split(";;") : value.split(",");
                    for (String entry : entries) {
                        if (entry.isBlank()) continue;
                        if (field.equals("requires_completed_cores"))
                            array.add(Integer.parseInt(entry.trim()));
                        else array.add(entry.trim());
                    }
                }
                core.add(field, array);
            } else return "This setting cannot be edited here";
            String proposed = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            String tasks = readConfig(folder, "tasks.json");
            Snapshot candidate = parse(tasks, proposed);
            if (candidate.core(tier) == null || candidate.cores.size() != server().cores.size())
                return "Invalid setting: check the log and existing task IDs";
            if (proposed.length() > 30000) return "Core config would exceed 30 KiB";
            Path temp = Files.createTempFile(folder, "cores-", ".json.tmp");
            try {
                Files.writeString(temp, proposed + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(temp, folder.resolve("cores.json"), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temp, folder.resolve("cores.json"), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            serverSnapshot = candidate;
            return "Saved tier " + tier + " / " + field;
        } catch (Exception exception) {
            LOG.warn("Rejecting Core configuration update (tier {}, field {})", tier, field, exception);
            return "Invalid value: " + exception.getMessage();
        }
    }

    /** Do not expose reward console commands to ordinary connected clients. */
    public static String publicPayload() {
        JsonObject root = JsonParser.parseString(server().payload()).getAsJsonObject();
        for (JsonElement element : root.getAsJsonObject("cores").getAsJsonArray("cores"))
            element.getAsJsonObject().remove("reward_commands");
        return root.toString();
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
                if (tier < 0 || tier >= 32 || cores.containsKey(tier))
                    throw new IllegalArgumentException("Core ID must be unique and in 0-31");
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
                        !schematic.matches("[a-z0-9_-]{1,64}"))
                    throw new IllegalArgumentException("Schematic must be generated or a named schematic ID");
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
                for (String id : selected) {
                    boolean found = tasks.containsKey(id)
                            || MilestoneTaskCatalog.tasksFor(type).stream()
                                    .anyMatch(task -> task.id().equals(id));
                    if (mode.equals("manual") && !found)
                        throw new IllegalArgumentException("Unknown manual task id: " + id);
                }
                if (mode.equals("manual") && selected.isEmpty())
                    throw new IllegalArgumentException("Manual mode requires task_ids");
                List<Integer> prerequisites = ints(o, "requires_completed_cores");
                if (prerequisites.stream().anyMatch(t -> t < 0 || t >= 32 || t == tier))
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
                        dimension, xp, commands, rewardItems(o)));
            } catch (Exception exception) {
                LOG.warn("Skipping invalid Milestone Core configuration: {}", exception.getMessage());
            }
        }
        for (CoreSettings core : cores.values())
            for (int requirement : core.requiresCompletedCores())
                if (!cores.containsKey(requirement))
                    throw new IllegalArgumentException("Core " + core.tier()
                            + " requires missing Core " + requirement);
        validatePrerequisiteGraph(cores);
        validateSchematicReferences(coresRoot, cores);
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
    private static List<RewardItem> rewardItems(JsonObject o) {
        if (!o.has("reward_items")) return List.of();
        List<RewardItem> result = new ArrayList<>();
        for (JsonElement element : o.getAsJsonArray("reward_items")) {
            JsonObject item = element.getAsJsonObject();
            ResourceLocation id = requiredId(item, "item");
            if (!BuiltInRegistries.ITEM.containsKey(id))
                throw new IllegalArgumentException("Unknown reward item " + id);
            int count = number(item, "count", 1);
            if (count < 1 || count > 2304)
                throw new IllegalArgumentException("Invalid reward item count");
            result.add(new RewardItem(id, count));
        }
        if (result.size() > 16) throw new IllegalArgumentException("Too many reward items");
        return result;
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
