package net.fireboy.milestone.unlock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.network.RecipeLockNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative, non-destructive recipe and machine-use gates.
 *
 * All recipe definitions remain in the RecipeManager and are visible to JEI.
 * Crafting/processing looks up matching recipes through RecipeManager, where
 * our server-only filter prevents locked matches from being selected.
 *
 * This lookup does not cover custom processing systems which bypass the vanilla
 * RecipeManager API (Create and some modded machines need separate adapters).
 */
public final class MilestoneFeatureLocks {
    private static final Logger LOG = LoggerFactory.getLogger(MilestoneFeatureLocks.class);
    private static final Map<UUID, Long> WARNED = new HashMap<>();
    private static volatile List<Gate> gates = List.of();

    private MilestoneFeatureLocks() { }

    /** Recipe selectors: "modid:recipe" or "modid:*". */
    public record Gate(String id, String name, int core, int unlockContributors,
                       boolean recipesLocked, boolean machinesLocked,
                       List<String> recipes, List<ResourceLocation> excludeRecipes,
                       List<ResourceLocation> blocks) {
        public Gate {
            recipes = List.copyOf(recipes);
            excludeRecipes = List.copyOf(excludeRecipes);
            blocks = List.copyOf(blocks);
        }

        public boolean matchesRecipe(ResourceLocation recipe) {
            if (excludeRecipes.contains(recipe)) return false;
            for (String selector : recipes)
                if (matchesSelector(selector, recipe)) return true;
            return false;
        }
    }

    public static List<Gate> gates() { return gates; }

    public static List<ResourceLocation> allRecipeIds(MinecraftServer server) {
        return server.getRecipeManager().getRecipes().stream()
                .map(RecipeHolder::id).distinct().sorted().toList();
    }

    public static boolean matchesSelector(String selector, ResourceLocation recipeId) {
        if (selector.endsWith(":*")) {
            String namespace = selector.substring(0, selector.length() - 2);
            return recipeId.getNamespace().equals(namespace);
        }
        return recipeId.toString().equals(selector);
    }

    private static List<String> selectors(JsonObject object, String key) {
        if (!object.has(key)) return List.of();
        JsonArray entries = object.getAsJsonArray(key);
        if (entries.size() > 256)
            throw new IllegalArgumentException(key + ": max 256 selectors");
        List<String> selectors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : entries) {
            String value = entry.getAsString().trim();
            boolean wildcard = value.matches("[a-z0-9_.-]+:\\*");
            if (!wildcard && (ResourceLocation.tryParse(value) == null
                    || !ResourceLocation.tryParse(value).toString().equals(value)))
                throw new IllegalArgumentException("Invalid " + key + " selector: " + value);
            if (seen.add(value)) selectors.add(value);
        }
        return List.copyOf(selectors);
    }

    private static List<ResourceLocation> ids(JsonObject object, String key) {
        if (!object.has(key)) return List.of();
        JsonArray entries = object.getAsJsonArray(key);
        if (entries.size() > 256) throw new IllegalArgumentException(key + ": max 256 IDs");
        List<ResourceLocation> values = new ArrayList<>();
        for (JsonElement entry : entries) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id == null) throw new IllegalArgumentException("Invalid " + key + " ID: " + entry);
            if (!values.contains(id)) values.add(id);
        }
        return List.copyOf(values);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key) {
        if (!object.has(key)) return false;
        if (!object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be boolean");
        return object.get(key).getAsBoolean();
    }

    public static List<Gate> parse(JsonObject root, Set<Integer> validCores) {
        JsonArray array = root.has("features")
                ? root.getAsJsonArray("features") : new JsonArray();
        if (array.size() > 80) throw new IllegalArgumentException("Maximum 80 feature gates");
        Set<String> names = new HashSet<>();
        List<Gate> parsed = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String id = string(object, "id"), name = string(object, "name");
            if (!id.matches("[a-z0-9_-]{1,64}") || !names.add(id))
                throw new IllegalArgumentException("Duplicate/invalid feature ID " + id);
            if (name.isBlank() || name.length() > 80)
                throw new IllegalArgumentException("Invalid feature name for " + id);
            if (!object.has("unlock_core"))
                throw new IllegalArgumentException(id + ": needs unlock_core");
            int core = object.get("unlock_core").getAsInt();
            if (!validCores.contains(core))
                throw new IllegalArgumentException(id + ": unknown Core " + core);
            int unlockAt = object.has("unlock_contributors")
                    ? object.get("unlock_contributors").getAsInt() : 0;
            if (unlockAt < 0 || unlockAt > 256)
                throw new IllegalArgumentException(id + ": unlock_contributors must be 0-256");

            boolean lockRecipes = flag(object, "lock_recipes");
            boolean lockMachines = flag(object, "lock_machine_use");
            List<String> recipes = selectors(object, "recipes");
            List<ResourceLocation> excludes = ids(object, "exclude_recipes");
            List<ResourceLocation> blocks = ids(object, "machine_blocks");
            if (lockRecipes && recipes.isEmpty())
                throw new IllegalArgumentException(id + ": select recipes before enabling recipe lock");
            if (lockMachines && blocks.isEmpty())
                throw new IllegalArgumentException(id + ": select blocks before enabling machine lock");
            parsed.add(new Gate(id, name, core, unlockAt,
                    lockRecipes, lockMachines, recipes, excludes, blocks));
        }
        // Rewards configured directly on individual Core contribution stages
        // use the same non-destructive server-authoritative recipe locks.
        for (JsonElement coreElement : root.getAsJsonArray("cores")) {
            JsonObject core = coreElement.getAsJsonObject();
            int tier = core.get("tier").getAsInt();
            if (!validCores.contains(tier) || !core.has("reward_stages")) continue;
            for (JsonElement stageElement : core.getAsJsonArray("reward_stages")) {
                JsonObject stage = stageElement.getAsJsonObject();
                int contributors = stage.get("contributors").getAsInt();
                if (!stage.has("rewards")) continue;
                int index = 0;
                for (JsonElement rewardElement : stage.getAsJsonArray("rewards")) {
                    JsonObject reward = rewardElement.getAsJsonObject();
                    int rewardIndex = index++;
                    if (!"recipes".equals(string(reward, "type"))) continue;
                    List<String> recipeIds = selectors(reward, "ids");
                    if (recipeIds.isEmpty()) continue;
                    String title = core.has("name") ? core.get("name").getAsString()
                            : "Core " + (tier + 1);
                    parsed.add(new Gate("core_" + tier + "_stage_" + contributors
                            + "_reward_" + rewardIndex, title + " recipe reward",
                            tier, contributors, true, false,
                            recipeIds, List.of(), List.of()));
                }
            }
        }
        return List.copyOf(parsed);
    }

    public static void install(JsonObject root, Set<Integer> validCores) {
        gates = parse(root, validCores);
        LOG.info("Configured {} non-destructive Milestone gates", gates.size());
    }

    private static int threshold(MinecraftServer server, Gate gate) {
        if (gate.unlockContributors() > 0) return gate.unlockContributors();
        var core = MilestoneContentConfig.server().core(gate.core());
        return core == null ? Integer.MAX_VALUE : core.finalThreshold();
    }

    private static int contributorCount(MinecraftServer server, int tier) {
        int max = 0;
        for (MilestoneRecord record : MilestoneManager.data(server).all())
            if (record.coreTier() == tier)
                max = Math.max(max, record.contributorCount());
        return max;
    }

    private static boolean unlocked(MinecraftServer server, Gate gate) {
        return !MilestoneContentConfig.enabled()
                || contributorCount(server, gate.core()) >= threshold(server, gate);
    }

    /** Safe to call from server-side recipe matching. Never mutates recipes. */
    public static boolean isRecipeLocked(MinecraftServer server, ResourceLocation recipeId) {
        if (server == null || !MilestoneContentConfig.enabled()) return false;
        for (Gate gate : gates)
            if (gate.recipesLocked() && gate.matchesRecipe(recipeId) && !unlocked(server, gate))
                return true;
        return false;
    }

    /**
     * A compact client snapshot includes selectors and lock requirements, not
     * duplicated thousands of concrete recipe IDs. Only active locks are sent.
     */
    public static String clientSnapshot(MinecraftServer server) {
        JsonObject root = new JsonObject();
        JsonArray locked = new JsonArray();
        if (MilestoneContentConfig.enabled()) for (Gate gate : gates) {
            if (!gate.recipesLocked() || unlocked(server, gate)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("name", gate.name());
            entry.addProperty("core", gate.core() + 1);
            var config = MilestoneContentConfig.server().core(gate.core());
            entry.addProperty("core_name", config == null ? "Core " + (gate.core() + 1) : config.name());
            entry.addProperty("needed", threshold(server, gate));
            entry.addProperty("current", contributorCount(server, gate.core()));
            JsonArray selectors = new JsonArray();
            gate.recipes().forEach(selectors::add);
            entry.add("recipes", selectors);
            JsonArray exceptions = new JsonArray();
            gate.excludeRecipes().forEach(value -> exceptions.add(value.toString()));
            entry.add("exclude_recipes", exceptions);
            locked.add(entry);
        }
        root.add("locked", locked);
        return root.toString();
    }

    /**
     * Compatibility entry point from existing contribution, reset and config code.
     * Replaces neither RecipeManager recipes nor vanilla recipe book entries.
     */
    public static void applyRecipes(MinecraftServer server, boolean recapture, boolean syncPlayers) {
        if (server == null) return;
        if (syncPlayers) RecipeLockNetwork.syncAll(server);
    }

    public static void onServerStarting(ServerStartingEvent event) {
        var config = MilestoneContentConfig.server();
        JsonObject root = JsonParser.parseString(config.payload()).getAsJsonObject()
                .getAsJsonObject("cores");
        install(root, config.cores().keySet());
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null)
            RecipeLockNetwork.sync(event.getPlayer());
        else RecipeLockNetwork.syncAll(event.getPlayerList().getServer());
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            net.fireboy.milestone.shop.MilestoneShopAccounts.get(player.getServer())
                    .registerFirstJoin(player.getServer(), player.getUUID());
            RecipeLockNetwork.sync(player);
        }
    }

    /** Existing machine-use restrictions stay separate from recipe matching. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(
                player.serverLevel().getBlockState(event.getPos()).getBlock());
        for (Gate gate : gates) {
            if (!gate.machinesLocked() || !gate.blocks().contains(id) || unlocked(server, gate)) continue;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            long now = player.serverLevel().getGameTime();
            if (now - WARNED.getOrDefault(player.getUUID(), -1000L) > 30) {
                WARNED.put(player.getUUID(), now);
                player.sendSystemMessage(Component.literal(gate.name() + " requires Core "
                        + (gate.core() + 1) + " to reach " + threshold(server, gate)
                        + " contributors.").withStyle(ChatFormatting.RED));
            }
            return;
        }
    }
}
