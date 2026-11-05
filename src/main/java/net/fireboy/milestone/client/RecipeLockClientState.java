package net.fireboy.milestone.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.unlock.MilestoneFeatureLocks;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Read-only client view of server locks for JEI presentation, never authority. */
public final class RecipeLockClientState {
    public record Requirement(String feature, String milestone,
                              int core, int current, int needed,
                              List<String> selectors, List<ResourceLocation> excludes) {
        public boolean applies(ResourceLocation recipeId) {
            if (excludes.contains(recipeId)) return false;
            for (String selector : selectors)
                if (MilestoneFeatureLocks.matchesSelector(selector, recipeId)) return true;
            return false;
        }
    }

    private static volatile List<Requirement> locked = List.of();
    private RecipeLockClientState() { }

    public static void install(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<Requirement> result = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("locked")) {
                JsonObject row = element.getAsJsonObject();
                List<String> selectors = new ArrayList<>();
                for (JsonElement value : row.getAsJsonArray("recipes"))
                    selectors.add(value.getAsString());
                List<ResourceLocation> excludes = new ArrayList<>();
                for (JsonElement value : row.getAsJsonArray("exclude_recipes")) {
                    ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
                    if (id != null) excludes.add(id);
                }
                result.add(new Requirement(row.get("name").getAsString(),
                        row.get("core_name").getAsString(),
                        row.get("core").getAsInt(), row.get("current").getAsInt(),
                        row.get("needed").getAsInt(),
                        List.copyOf(selectors), List.copyOf(excludes)));
            }
            locked = List.copyOf(result);
        } catch (RuntimeException exception) {
            locked = List.of();
        }
    }

    public static List<Requirement> requirements(ResourceLocation recipeId) {
        if (recipeId == null) return List.of();
        List<Requirement> result = new ArrayList<>();
        for (Requirement requirement : locked)
            if (requirement.applies(recipeId)) result.add(requirement);
        return List.copyOf(result);
    }

    public static void clear() { locked = List.of(); }
}
