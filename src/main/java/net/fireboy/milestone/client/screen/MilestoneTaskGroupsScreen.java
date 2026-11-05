package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Named task pools live under Tasks, independent of Core setup.
 * Only the parent config's Save writes anything to disk/server.
 */
public final class MilestoneTaskGroupsScreen extends Screen {
    private final MilestoneConfigScreen parent;
    private final JsonObject draft;
    private final JsonArray groups;
    private int editing = -1;
    private int first;
    private EditBox nameBox;
    private String notice = "";
    private int confirmDelete = -1;

    public MilestoneTaskGroupsScreen(MilestoneConfigScreen parent, JsonObject draft) {
        super(Component.literal("Task Groups"));
        this.parent = parent;
        this.draft = draft;
        JsonObject tasks = draft.getAsJsonObject("tasks");
        if (!tasks.has("groups")) tasks.add("groups", new JsonArray());
        groups = tasks.getAsJsonArray("groups");
    }

    private int pw() { return Math.min(445, width - 20); }
    private int ph() { return Math.min(336, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private JsonObject current() {
        return editing >= 0 && editing < groups.size() ? groups.get(editing).getAsJsonObject() : null;
    }
    private void changed() { parent.markConfigDirty(); notice = "Unsaved changes - Save in main Config"; }
    private int capacity() { return Math.max(1, (ph() - 147) / 26); }
    private void rebuild() { clearWidgets(); init(); }
    private boolean canEdit() {
        if (minecraft == null) return false;
        return minecraft.getConnection() == null
                || minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    @Override protected void init() {
        int l = left(), t = top(), w = pw();
        addRenderableWidget(Button.builder(Component.literal(editing >= 0 ? "< Groups" : "< Tasks"),
                b -> onClose()).bounds(l + 12, t + 12, 102, 20).build());
        if (editing >= 0) {
            JsonObject group = current();
            if (group == null) { editing = -1; rebuild(); return; }
            nameBox = new EditBox(font, l + 111, t + 75, w - 135, 20,
                    Component.literal("Group display name"));
            nameBox.setMaxLength(80);
            nameBox.setValue(group.get("name").getAsString());
            nameBox.setEditable(canEdit());
            nameBox.setResponder(value -> {
                group.addProperty("name", value);
                changed();
            });
            addRenderableWidget(nameBox);
            addRenderableWidget(Button.builder(Component.literal("Select tasks in this group"),
                    b -> selectTasks()).bounds(l + 25, t + 116, w - 50, 24).build()).active = canEdit();
            addRenderableWidget(Button.builder(Component.literal("Done"),
                    b -> onClose()).bounds(l + (w - 125) / 2, t + 186, 125, 23).build());
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("+ New Task Group"),
                b -> addGroup()).bounds(l + 22, t + 47, w - 44, 23).build())
                .active = canEdit() && groups.size() < 64;
        first = Math.max(0, Math.min(first, Math.max(0, groups.size() - capacity())));
        for (int i = first; i < Math.min(groups.size(), first + capacity()); i++) {
            int index = i;
            JsonObject group = groups.get(i).getAsJsonObject();
            int taskCount = group.getAsJsonArray("task_ids").size();
            String name = group.get("name").getAsString() + " (" + taskCount + " tasks)";
            int y = t + 84 + (i - first) * 26;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name, w - 145)),
                    b -> openGroup(index)).bounds(l + 22, y, w - 128, 22).build());
            addRenderableWidget(Button.builder(Component.literal(confirmDelete == index ? "Confirm?" : "Delete"),
                    b -> {
                        if (confirmDelete == index) deleteGroup(index);
                        else { confirmDelete = index; notice = "Press Confirm? to delete"; rebuild(); }
                    }).bounds(l + w - 99, y, 76, 22).build()).active = canEdit();
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),
                b -> { first = Math.max(0, first - capacity()); rebuild(); })
                .bounds(l + 22, t + ph() - 43, 98, 20).build()).active = first > 0;
        addRenderableWidget(Button.builder(Component.literal("Next"),
                b -> { first += capacity(); rebuild(); })
                .bounds(l + w - 120, t + ph() - 43, 98, 20).build())
                .active = first + capacity() < groups.size();
    }

    private void addGroup() {
        if (!canEdit() || groups.size() >= 64) return;
        int number = 1;
        LinkedHashSet<String> existing = new LinkedHashSet<>();
        for (JsonElement row : groups) existing.add(row.getAsJsonObject().get("id").getAsString());
        while (existing.contains("group_" + number)) number++;
        JsonObject group = new JsonObject();
        group.addProperty("id", "group_" + number);
        group.addProperty("name", "Task Group " + number);
        group.add("task_ids", new JsonArray());
        groups.add(group);
        changed();
        openGroup(groups.size() - 1);
    }

    private void openGroup(int index) {
        editing = index;
        confirmDelete = -1;
        rebuild();
    }

    private void deleteGroup(int index) {
        if (!canEdit() || index < 0 || index >= groups.size()) return;
        String id = groups.get(index).getAsJsonObject().get("id").getAsString();
        for (JsonElement element : draft.getAsJsonObject("cores").getAsJsonArray("cores")) {
            JsonObject core = element.getAsJsonObject();
            if (core.has("task_group") && core.get("task_group").getAsString().equals(id)) {
                notice = "Group is used by Core " + (core.get("tier").getAsInt() + 1);
                confirmDelete = -1;
                rebuild();
                return;
            }
        }
        groups.remove(index);
        confirmDelete = -1;
        changed();
        rebuild();
    }

    private void selectTasks() {
        if (!canEdit() || minecraft == null || current() == null) return;
        if (nameBox != null) current().addProperty("name", nameBox.getValue().trim());
        JsonObject group = current();
        List<String> selected = new ArrayList<>();
        for (JsonElement task : group.getAsJsonArray("task_ids"))
            selected.add(task.getAsString());
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (JsonElement task : draft.getAsJsonObject("tasks").getAsJsonArray("tasks"))
            available.add(task.getAsJsonObject().get("id").getAsString());
        // Only the Tasks section defines grouped task pools.
        minecraft.setScreen(new MilestoneIdPickerScreen(this, MilestoneIdPickerScreen.Kind.TASK,
                true, selected, available, chosen -> {
                    JsonArray entries = new JsonArray();
                    for (String id : chosen) entries.add(id);
                    group.add("task_ids", entries);
                    changed();
                }));
    }

    @Override public void onClose() {
        if (editing >= 0) {
            editing = -1;
            rebuild();
        } else if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        super.render(graphics, mouseX, mouseY, tick);
        int l = left(), t = top(), w = pw();
        graphics.fill(0, 0, width, height, 0xFF171717);
        graphics.fill(l, t, l + w, t + ph(), 0xFF333333);
        graphics.drawCenteredString(font, editing >= 0 ? "Edit Task Group" : "Reusable Task Groups",
                width / 2, t + 18, 0xFFFFFFFF);
        if (editing >= 0 && current() != null) {
            graphics.drawString(font, "Group name", l + 25, t + 81, 0xFFCCCCCC);
            graphics.drawCenteredString(font, "ID: " + current().get("id").getAsString(),
                    width / 2, t + 50, 0xFFBBBBBB);
            graphics.drawCenteredString(font,
                    current().getAsJsonArray("task_ids").size() + " tasks selected",
                    width / 2, t + 156, 0xFFCCCCCC);
            graphics.drawCenteredString(font, "Different cores can share a group.",
                    width / 2, t + 225, 0xFFBBBBBB);
        } else {
            graphics.drawCenteredString(font, "Define pools here, then assign a group to each Core.",
                    width / 2, t + ph() - 69, 0xFFCCCCCC);
        }
        if (!notice.isBlank())
            graphics.drawCenteredString(font, font.plainSubstrByWidth(notice, w - 38),
                    width / 2, t + ph() - 58, 0xFFFFD27C);
        for (var child : children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible)
                widget.render(graphics, mouseX, mouseY, tick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
