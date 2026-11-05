package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.milestone.MilestoneUnlock;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Per-Core contribution rewards: stages, required unique players, and typed
 * effects. Edits stay in the parent config draft until its Save is pressed.
 *
 * Each stage can contain Recipe, Dimension, Portal and Hourly Pay rewards.
 * Legacy portal-unlock flags remain intact when editing old saved stages.
 */
public final class MilestoneRewardStagesScreen extends Screen {
    private static final int PANEL = 0xFF333333;
    private static final int TEXT = 0xFFF0F0F0;
    private static final int MUTED = 0xFFBDBDBD;
    private final MilestoneConfigScreen parent;
    private final JsonObject core;
    private final JsonObject draft;
    private int editing = -1, first, rewardFirst;
    private EditBox countBox, descriptionBox, incomeBox;
    private boolean editingIncome;
    private int incomeRewardIndex = -1;
    private String notice = "";

    public MilestoneRewardStagesScreen(MilestoneConfigScreen parent, JsonObject core, JsonObject draft) {
        super(Component.literal("Contribution Rewards"));
        this.parent = parent;
        this.core = core;
        this.draft = draft;
    }

    private boolean canEdit() {
        return minecraft != null && (minecraft.getConnection() == null
                || minecraft.player != null && minecraft.player.hasPermissions(2));
    }
    private int pw() { return Math.min(454, width - 20); }
    private int ph() { return Math.min(340, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int bottom() { return top() + ph(); }
    private MilestoneType type() {
        return core.has("profile") && core.get("profile").getAsString().equals("nether_gate")
                ? MilestoneType.NETHER_GATE : MilestoneType.GENERIC;
    }
    private JsonArray stages() {
        if (core.has("reward_stages")) return core.getAsJsonArray("reward_stages");
        JsonArray defaults = new JsonArray();
        for (var old : type().thresholds()) {
            JsonObject stage = new JsonObject();
            stage.addProperty("contributors", old.contributors());
            stage.addProperty("description", old.description());
            if (old.unlock() != null) stage.addProperty("unlock", old.unlock().name());
            defaults.add(stage);
        }
        return defaults;
    }
    private void ensureStages() {
        if (!core.has("reward_stages")) core.add("reward_stages", stages());
    }
    private JsonObject stage() {
        JsonArray rows = stages();
        return editing >= 0 && editing < rows.size() ? rows.get(editing).getAsJsonObject() : null;
    }
    private JsonArray effects() {
        JsonObject stage = stage();
        if (!stage.has("rewards")) stage.add("rewards", new JsonArray());
        return stage.getAsJsonArray("rewards");
    }
    private void changed(String message) {
        parent.markConfigDirty();
        notice = message;
    }
    private int lastCount() {
        int max = 0;
        for (JsonElement stage : stages())
            max = Math.max(max, stage.getAsJsonObject().get("contributors").getAsInt());
        return max;
    }
    private int stageRows() { return Math.max(1, (ph() - 174) / 27); }
    private void rebuild() { clearWidgets(); init(); }

    @Override protected void init() {
        int l = left(), t = top(), w = pw();
        addRenderableWidget(Button.builder(Component.literal(editing < 0 ? "< Core" : "< Rewards"),
                b -> onClose()).bounds(l + 12, t + 12, 92, 20).build());
        if (editingIncome) {
            int amount = incomeRewardIndex < 0 ? 10
                    : effects().get(incomeRewardIndex).getAsJsonObject().get("amount").getAsInt();
            incomeBox = new EditBox(font, l + 135, t + 107, w - 167, 22,
                    Component.literal("Credits earned per hour"));
            incomeBox.setMaxLength(6);
            incomeBox.setValue(Integer.toString(amount));
            incomeBox.setEditable(canEdit());
            addRenderableWidget(incomeBox);
            addRenderableWidget(Button.builder(Component.literal("Apply Hourly Pay"),
                    b -> saveIncome()).bounds(l + (w - 164) / 2,
                    t + 166, 164, 23).build()).active = canEdit();
            return;
        }
        if (editing >= 0) {
            JsonObject current = stage();
            if (current == null) { editing = -1; rebuild(); return; }
            countBox = new EditBox(font, l + 160, t + 56, w - 184, 19,
                    Component.literal("Contributor threshold"));
            countBox.setMaxLength(3);
            countBox.setValue(current.get("contributors").getAsString());
            countBox.setEditable(canEdit());
            addRenderableWidget(countBox);
            descriptionBox = new EditBox(font, l + 24, t + 104, w - 48, 19,
                    Component.literal("Stage description"));
            descriptionBox.setMaxLength(120);
            descriptionBox.setValue(current.get("description").getAsString());
            descriptionBox.setEditable(canEdit());
            addRenderableWidget(descriptionBox);

            int quarter = (w - 66) / 4;
            String[] types = {"Recipes", "Dimension", "Portal", "Hourly Pay"};
            for (int i = 0; i < types.length; i++) {
                String rewardType = types[i].equals("Hourly Pay") ? "hourly_pay"
                        : types[i].toLowerCase(java.util.Locale.ROOT);
                addRenderableWidget(Button.builder(Component.literal("+ " + types[i]),
                        b -> addReward(rewardType)).bounds(l + 24 + i * (quarter + 6),
                        t + 136, quarter, 20).build()).active = canEdit() && effects().size() < 12;
            }

            JsonArray rewards = effects();
            int visible = Math.max(1, (ph() - 263) / 24);
            rewardFirst = Math.max(0, Math.min(rewardFirst, Math.max(0, rewards.size() - visible)));
            for (int i = rewardFirst; i < Math.min(rewards.size(), rewardFirst + visible); i++) {
                final int index = i;
                JsonObject reward = rewards.get(i).getAsJsonObject();
                int y = t + 171 + (i - rewardFirst) * 24;
                String label = describe(reward);
                addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, w - 146)),
                        b -> editReward(index)).bounds(l + 24, y, w - 126, 20).build())
                        .active = canEdit();
                addRenderableWidget(Button.builder(Component.literal("Remove"),
                        b -> removeReward(index)).bounds(l + w - 98, y, 73, 20).build())
                        .active = canEdit();
            }
            addRenderableWidget(Button.builder(Component.literal("Previous rewards"),
                    b -> { rewardFirst = Math.max(0, rewardFirst - visible); rebuild(); })
                    .bounds(l + 24, bottom() - 89, 117, 18).build())
                    .active = rewardFirst > 0;
            addRenderableWidget(Button.builder(Component.literal("Next rewards"),
                    b -> { rewardFirst += visible; rebuild(); })
                    .bounds(l + w - 141, bottom() - 89, 117, 18).build())
                    .active = rewardFirst + visible < rewards.size();
            addRenderableWidget(Button.builder(Component.literal("Apply Stage"),
                    b -> applyStage()).bounds(l + (w - 150) / 2, bottom() - 41, 150, 22).build())
                    .active = canEdit();
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("+ Add Stage"),
                b -> addStage()).bounds(l + 24, t + 60, w - 48, 22).build())
                .active = canEdit() && stages().size() < 32 && lastCount() < 256;
        JsonArray entries = stages();
        int capacity = stageRows();
        first = Math.max(0, Math.min(first, Math.max(0, entries.size() - capacity)));
        for (int i = first; i < Math.min(entries.size(), first + capacity); i++) {
            int index = i, y = t + 96 + (i - first) * 27;
            JsonObject row = entries.get(i).getAsJsonObject();
            int count = row.has("rewards") ? row.getAsJsonArray("rewards").size() : 0;
            if (row.has("unlock")) count++;
            String label = row.get("contributors").getAsInt() + " players | "
                    + row.get("description").getAsString() + " | " + count + " reward(s)";
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, w - 142)),
                    b -> openStage(index)).bounds(l + 24, y, w - 121, 22).build());
            addRenderableWidget(Button.builder(Component.literal("X"),
                    b -> removeStage(index)).bounds(l + w - 89, y, 64, 22).build())
                    .active = canEdit();
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),
                b -> { first = Math.max(0, first - capacity); rebuild(); })
                .bounds(l + 24, bottom() - 42, 98, 20).build()).active = first > 0;
        addRenderableWidget(Button.builder(Component.literal("Next"),
                b -> { first += capacity; rebuild(); })
                .bounds(l + w - 122, bottom() - 42, 98, 20).build())
                .active = first + capacity < entries.size();
    }

    private String describe(JsonObject reward) {
        String type = reward.get("type").getAsString();
        if (type.equals("hourly_pay"))
            return "Hourly Pay: +$" + reward.get("amount").getAsInt() + " per hour";
        if (type.equals("portal")) {
            String access = reward.get("access").getAsString().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ');
            return "Portal: " + reward.get("dimension").getAsString() + " - " + access;
        }
        JsonArray ids = reward.getAsJsonArray("ids");
        if (type.equals("recipes")) {
            int wholeMods = 0, individual = 0;
            String singleMod = "";
            for (JsonElement entry : ids) {
                String rule = entry.getAsString();
                if (rule.endsWith(":*")) {
                    wholeMods++;
                    singleMod = rule.substring(0, rule.length() - 2);
                } else individual++;
            }
            if (wholeMods == 1 && individual == 0)
                return "Recipes: ALL " + singleMod + " recipes";
            if (wholeMods > 0)
                return "Recipes: " + wholeMods + " entire mod(s) + "
                        + individual + " individual recipe(s)";
            return "Recipes: " + individual + " selected recipe(s)";
        }
        String detail = ids.isEmpty() ? "(empty)"
                : ids.size() == 1 ? ids.get(0).getAsString()
                : ids.size() + " entries";
        return switch(type) {
            case "achievement" -> "Legacy advancement reward (inactive): " + detail;
            case "recipes" -> "Recipes: " + detail;
            case "dimension" -> "Dimension access: " + (ids.size() == 1
                    ? labelUnlock(detail) : ids.size() + " dimensions");
            default -> type + ": " + detail;
        };
    }

    private String labelUnlock(String flag) {
        return switch (flag) {
            case "SHARED_NETHER_GATEWAY" -> "Shared Nether gateway";
            case "NETHER_PUBLIC_GATEWAY_1" -> "Public Nether gateway 1";
            case "NETHER_PUBLIC_GATEWAY_2" -> "Public Nether gateway 2";
            case "NETHER_PERSONAL_PORTALS" -> "Personal Nether portals";
            default -> flag;
        };
    }

    private void addStage() {
        if (!canEdit() || stages().size() >= 32 || lastCount() >= 256) return;
        ensureStages();
        JsonObject newStage = new JsonObject();
        newStage.addProperty("contributors",
                stages().isEmpty() ? 1 : lastCount() + 1);
        newStage.addProperty("description", "New milestone reward");
        newStage.add("rewards", new JsonArray());
        core.getAsJsonArray("reward_stages").add(newStage);
        changed("Stage added - choose its rewards.");
        openStage(core.getAsJsonArray("reward_stages").size() - 1);
    }

    private void removeStage(int index) {
        JsonArray rows = stages();
        if (!canEdit() || rows.isEmpty() || index < 0 || index >= rows.size()) return;
        ensureStages();
        core.getAsJsonArray("reward_stages").remove(index);
        changed(core.getAsJsonArray("reward_stages").isEmpty()
                ? "All stages removed; Core completes on first contribution."
                : "Stage removed from draft.");
        rebuild();
    }

    private void openStage(int index) {
        if (index < 0 || index >= stages().size()) return;
        ensureStages();
        editing = index;
        rewardFirst = 0;
        if (stage().has("unlock"))
            notice = "Existing legacy Nether flag is preserved.";
        rebuild();
    }

    private boolean saveFields(boolean exitEditor) {
        if (stage() == null || countBox == null || descriptionBox == null) return false;
        try {
            int required = Integer.parseInt(countBox.getValue().trim());
            String description = descriptionBox.getValue().trim();
            if (required < 1 || required > 256)
                throw new IllegalArgumentException("Contributor count must be 1-256.");
            if (description.isEmpty() || description.length() > 120)
                throw new IllegalArgumentException("Description must be 1-120 characters.");
            for (int i = 0; i < stages().size(); i++)
                if (i != editing && stages().get(i).getAsJsonObject()
                        .get("contributors").getAsInt() == required)
                    throw new IllegalArgumentException("Contributor count already used by another stage.");
            JsonObject row = stage();
            if (required != row.get("contributors").getAsInt()
                    || !description.equals(row.get("description").getAsString())) {
                row.addProperty("contributors", required);
                row.addProperty("description", description);
                changed("Stage edited - Save in Config.");
            }
            if (exitEditor) {
                List<JsonObject> sorted = new ArrayList<>();
                for (JsonElement stage : stages()) sorted.add(stage.getAsJsonObject());
                sorted.sort(Comparator.comparingInt(s -> s.get("contributors").getAsInt()));
                JsonArray ordered = new JsonArray();
                sorted.forEach(ordered::add);
                core.add("reward_stages", ordered);
                editing = -1;
                first = 0;
                rebuild();
            }
            return true;
        } catch (NumberFormatException e) { notice = "Contributor count must be a number."; }
        catch (IllegalArgumentException e) { notice = e.getMessage(); }
        return false;
    }

    private void applyStage() { saveFields(true); }

    private void addReward(String type) {
        if (!canEdit() || effects().size() >= 12 || !saveFields(false)) return;
        if (type.equals("hourly_pay")) {
            incomeRewardIndex = -1;
            editingIncome = true;
            rebuild();
        } else if (type.equals("portal")) {
            openPortalEditor(-1);
        } else if (type.equals("dimension") || type.equals("recipes")) {
            chooseIds(type, -1);
        }
    }
    private void chooseIds(String type, int existingIndex) {
        if ((!type.equals("recipes") && !type.equals("dimension"))
                || minecraft == null || !saveFields(false)) return;
        if (type.equals("dimension") && existingIndex >= 0) {
            for (JsonElement element : effects().get(existingIndex)
                    .getAsJsonObject().getAsJsonArray("ids")) {
                if (element.getAsString().equals("SHARED_NETHER_GATEWAY")
                        || element.getAsString().startsWith("NETHER_")) {
                    notice = "Legacy Nether flags: use + Portal for new rewards.";
                    return;
                }
            }
        }
        List<String> existing = new ArrayList<>();
        if (existingIndex >= 0) for (JsonElement el : effects()
                .get(existingIndex).getAsJsonObject().getAsJsonArray("ids"))
            existing.add(el.getAsString());
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                type.equals("dimension") ? MilestoneIdPickerScreen.Kind.DIMENSION
                        : MilestoneIdPickerScreen.Kind.RECIPE,
                true, existing, List.of(),
                ids -> {
                    if (ids.isEmpty()) {
                        notice = "No IDs selected; reward unchanged.";
                        return;
                    }
                    if (existingIndex >= 0) {
                        JsonArray values = new JsonArray();
                        ids.forEach(values::add);
                        effects().get(existingIndex).getAsJsonObject().add("ids", values);
                        changed("Reward IDs updated.");
                    } else addEffect(type, ids);
                }));
    }
    private void addEffect(String type, List<String> ids) {
        ensureStages();
        JsonObject reward = new JsonObject();
        reward.addProperty("type", type);
        JsonArray entries = new JsonArray();
        ids.forEach(entries::add);
        reward.add("ids", entries);
        effects().add(reward);
        changed("Added " + type + " reward.");
        rebuild();
    }
    private void editReward(int index) {
        if (!canEdit() || index < 0 || index >= effects().size()) return;
        JsonObject reward = effects().get(index).getAsJsonObject();
        String type = reward.get("type").getAsString();
        if (type.equals("achievement")) {
            notice = "Achievement rewards are retired. Remove this legacy reward.";
            return;
        }
        if (type.equals("hourly_pay")) {
            if (!saveFields(false)) return;
            incomeRewardIndex = index;
            editingIncome = true;
            rebuild();
        } else if (type.equals("portal")) {
            if (saveFields(false)) openPortalEditor(index);
        } else if (type.equals("dimension")) {
            chooseIds(type, index);
        } else chooseIds(type, index);
    }
    private void openPortalEditor(int existingIndex) {
        if (minecraft == null || stage() == null) return;
        JsonObject existing = existingIndex < 0 ? null
                : effects().get(existingIndex).getAsJsonObject();
        minecraft.setScreen(new MilestonePortalRewardScreen(this, existing, configured -> {
            if (existingIndex < 0) {
                if (effects().size() >= 12) return;
                effects().add(configured);
            } else effects().set(existingIndex, configured);
            changed("Portal reward saved in draft.");
        }));
    }

    private void saveIncome() {
        if (!editingIncome || !canEdit() || incomeBox == null) return;
        try {
            int amount = Integer.parseInt(incomeBox.getValue().trim());
            if (amount < 1 || amount > 100000)
                throw new IllegalArgumentException("Choose $1-$100,000 per hour.");
            JsonObject reward = new JsonObject();
            reward.addProperty("type", "hourly_pay");
            reward.addProperty("amount", amount);
            if (incomeRewardIndex < 0) {
                if (effects().size() >= 12) return;
                effects().add(reward);
            } else {
                effects().set(incomeRewardIndex, reward);
            }
            editingIncome = false;
            incomeRewardIndex = -1;
            changed("Hourly pay increase saved in draft.");
            rebuild();
        } catch (NumberFormatException error) {
            notice = "Hourly pay must be a whole number.";
        } catch (IllegalArgumentException error) {
            notice = error.getMessage();
        }
    }

    private void removeReward(int index) {
        if (!canEdit() || index < 0 || index >= effects().size()) return;
        effects().remove(index);
        changed("Reward removed from stage.");
        rebuild();
    }

    @Override public void onClose() {
        if (editingIncome) {
            editingIncome = false;
            incomeRewardIndex = -1;
            rebuild();
        } else if (editing >= 0) {
            if (saveFields(true)) notice = "";
        } else if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        super.render(g, mx, my, tick);
        int l = left(), t = top(), w = pw();
        g.fill(0, 0, width, height, 0xFF171717);
        g.fill(l, t, l + w, t + ph(), PANEL);
        g.drawCenteredString(font,
                (editingIncome ? "Hourly Pay" : editing >= 0 ? "Edit Reward Stage"
                        : "Contribution Rewards")
                        + " - Core " + (core.get("tier").getAsInt() + 1),
                width / 2, t + 18, TEXT);
        if (editingIncome) {
            g.drawCenteredString(font,
                    "Increase every registered player's future hourly income.",
                    width / 2, t + 72, MUTED);
            g.drawString(font, "Credits/hour +", l + 24, t + 113, MUTED);
            g.drawCenteredString(font,
                    "Pay runs in real time, even when players are offline.",
                    width / 2, t + 146, MUTED);
        } else if (editing >= 0) {
            g.drawString(font, "Contributors:", l + 24, t + 62, MUTED);
            g.drawString(font, "Stage description", l + 24, t + 88, MUTED);
            g.drawString(font, font.plainSubstrByWidth(
                    "Dimension: destination access  |  Portal: type and gateway permission",
                    w - 48), l + 24, t + 157, MUTED);
        } else {
            if (stages().isEmpty())
                g.drawCenteredString(font,
                        "No stages: Core completes after one contribution.",
                        width / 2, t + 112, MUTED);
            else
                g.drawCenteredString(font,
                        "Core completes at the final stage's contributor count.",
                        width / 2, top() + 88, MUTED);
            g.drawCenteredString(font,
                    "Each contribution must come from a different player.",
                    width / 2, bottom() - 72, MUTED);
        }
        if (!notice.isBlank())
            g.drawCenteredString(font, font.plainSubstrByWidth(notice, w - 30),
                    width / 2, bottom() - 58, 0xFFFFD27C);
        for (var widget : children())
            if (widget instanceof net.minecraft.client.gui.components.AbstractWidget control && control.visible)
                control.render(g, mx, my, tick);
    }
    @Override public boolean isPauseScreen() { return false; }
}
