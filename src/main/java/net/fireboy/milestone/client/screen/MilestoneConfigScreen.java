package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Collections;

/**
 * Vanilla-style admin editor. All fields are visible in a scrolling list.
 * Adding/removing profiles and editing fields change a draft until Save.
 */
public final class MilestoneConfigScreen extends Screen {
    private enum Page { HOME, CORES, CORE_EDIT, TASKS, TASK_EDIT, SCHEMATICS, SCHEMATIC_EDIT, SCHEMATIC_IMPORT, SHOP, CATEGORY_PICK, CATEGORY_ADD }
    private record TaskRow(int index, JsonObject value) {}
    private record Field(String key, String label) {}

    private static final Field[] CORE_FIELDS = {
            new Field("name", "Name"),
            new Field("enabled", "Spawn enabled"),
            new Field("profile", "Milestone type"),
            new Field("task_mode", "Task selection"),
            new Field("difficulty", "Difficulty"),
            new Field("categories", "Task categories"),
            new Field("task_ids", "Manual task IDs"),
            new Field("requires_completed_cores", "Required Core IDs"),
            new Field("dimension", "Dimension"),
            new Field("allowed_biomes", "Allowed biomes"),
            new Field("blocked_biomes", "Blocked biomes"),
            new Field("min_y", "Minimum Y"),
            new Field("max_y", "Maximum Y"),
            new Field("min_distance", "Min spawn distance"),
            new Field("max_distance", "Max spawn distance"),
            new Field("depth_fraction", "Depth (0 - 1)"),
            new Field("schematic", "Schematic"),
            new Field("color", "Core color hex"),
            new Field("reward_xp", "Reward XP"),
            new Field("reward_items", "Reward items"),
            new Field("reward_commands", "Reward commands")
    };
    private static final Field[] TASK_FIELDS = {
            new Field("id", "Task ID"),
            new Field("type", "Task type"),
            new Field("target", "Item / Entity / Advancement"),
            new Field("count", "Required amount"),
            new Field("category", "Category"),
            new Field("difficulty", "Difficulty"),
            new Field("clue", "Task description")
    };
    private static final Field[] SCHEMATIC_FIELDS = {
            new Field("id", "Schematic ID"),
            new Field("name", "Display name"),
            new Field("file", "NBT filename")
    };
    private static final int BG = 0xC8101010;
    private static final int PANEL = 0xFF333333;
    private static final int EDGE = 0xFF777777;
    private static final int TEXT = 0xFFF5F5F5;
    private static final int MUTED = 0xFFBBBBBB;

    private final Screen parent;
    private Page page = Page.HOME;
    private JsonObject draft;
    private final Map<String, String> pending = new HashMap<>();
    private int selectedTier;
    private int selectedTask;
    private int selectedSchematic;
    private int listScroll;
    private int fieldScroll;
    private final SmoothWidgetScroll smoothRows=new SmoothWidgetScroll();
    private Page lastSmoothPage;
    private int confirmDelete = -1;
    private int categoryFilter = 0;
    private int difficultyFilter = 0;
    private int categoryIndex = 0;
    private boolean draggingScrollbar;
    private Page categoryReturnPage = Page.TASK_EDIT;
    private EditBox newCategoryBox;
    private boolean dirty;
    private boolean waitingForServer;
    private boolean loadedServer;
    private boolean saving;
    private String status = "";

    public MilestoneConfigScreen() { this(null); }

    public MilestoneConfigScreen(Screen parent) {
        super(Component.literal("Milestone Configuration"));
        this.parent = parent;
        draft = JsonParser.parseString(MilestoneContentConfig.localBundle()).getAsJsonObject();
    }

    private boolean connected() {
        return minecraft != null && minecraft.getConnection() != null;
    }

    private boolean canEdit() {
        if (minecraft == null) return false;
        if (!connected()) return true;
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private int panelWidth() { return Math.min(430, width - 20); }
    private int panelHeight() { return Math.min(340, height - 12); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int bottom() { return top() + panelHeight(); }
    private int rowsFit(int rowHeight) {
        return Math.max(1, (panelHeight() - 127) / rowHeight);
    }
    private static final List<String> BASIC_DIFFICULTIES =
            List.of("easy", "medium", "hard", "extreme");
    private JsonObject tasksRoot() { return draft.getAsJsonObject("tasks"); }

    /** Category names are saved independently so an empty category can be selected. */
    private List<String> categories() {
        LinkedHashSet<String> names = new LinkedHashSet<>(
                List.of("general", "gathering", "combat", "exploration"));
        if (tasksRoot().has("categories"))
            for (JsonElement item : tasksRoot().getAsJsonArray("categories"))
                names.add(item.getAsString());
        for (JsonElement item : tasks())
            if (item.getAsJsonObject().has("category"))
                names.add(item.getAsJsonObject().get("category").getAsString());
        for (JsonElement item : cores())
            if (item.getAsJsonObject().has("categories"))
                for (JsonElement category : item.getAsJsonObject().getAsJsonArray("categories"))
                    names.add(category.getAsString());
        names.removeIf(String::isBlank);
        return new ArrayList<>(names);
    }

    private List<String> difficulties() {
        LinkedHashSet<String> names = new LinkedHashSet<>(BASIC_DIFFICULTIES);
        for (JsonElement entry : tasks())
            if (entry.getAsJsonObject().has("difficulty"))
                names.add(entry.getAsJsonObject().get("difficulty").getAsString());
        for (JsonElement entry : cores())
            if (entry.getAsJsonObject().has("difficulty"))
                names.add(entry.getAsJsonObject().get("difficulty").getAsString());
        return new ArrayList<>(names);
    }
    private int countInCategory(String category) {
        int count = 0;
        for (JsonElement task : tasks())
            if (category.equals(task.getAsJsonObject().get("category").getAsString())) count++;
        return count;
    }
    private List<TaskRow> filteredTasks() {
        List<String> cs = categories(), ds = difficulties();
        String category = categoryFilter == 0 ? "" : cs.get(Math.floorMod(categoryFilter - 1, cs.size()));
        String difficulty = difficultyFilter == 0 ? "" : ds.get(Math.floorMod(difficultyFilter - 1, ds.size()));
        List<TaskRow> filtered = new ArrayList<>();
        for (int i=0; i<tasks().size(); i++) {
            JsonObject t = tasks().get(i).getAsJsonObject();
            if (!category.isEmpty() && !category.equals(t.get("category").getAsString())) continue;
            if (!difficulty.isEmpty() && !difficulty.equals(t.get("difficulty").getAsString())) continue;
            filtered.add(new TaskRow(i,t));
        }
        filtered.sort(Comparator.comparing((TaskRow row) ->
                rarityRank(row.value().get("difficulty").getAsString()))
                .thenComparing(row -> row.value().get("id").getAsString()));
        return filtered;
    }
    private static int rarityRank(String rarity) {
        int found = BASIC_DIFFICULTIES.indexOf(rarity);
        return found < 0 ? 100 : found;
    }
    private JsonArray schematics() {
        JsonObject root = draft.getAsJsonObject("cores");
        if (!root.has("schematics")) root.add("schematics", new JsonArray());
        return root.getAsJsonArray("schematics");
    }
    private JsonObject schematic() {
        if (schematics().isEmpty()) return null;
        selectedSchematic = Math.max(0,Math.min(selectedSchematic,schematics().size()-1));
        return schematics().get(selectedSchematic).getAsJsonObject();
    }
    private List<String> schematicIds() {
        List<String> ids = new ArrayList<>();
        ids.add("generated");
        for (JsonElement item : schematics())
            ids.add(item.getAsJsonObject().get("id").getAsString());
        return ids;
    }
    private String schematicLabel(String id) {
        if (id.equals("generated")) return "Generated 5x5 Chamber";
        for (JsonElement item : schematics()) {
            JsonObject entry = item.getAsJsonObject();
            if (entry.get("id").getAsString().equals(id))
                return entry.get("name").getAsString();
        }
        return "(Missing) " + id;
    }
    private JsonArray cores() { return draft.getAsJsonObject("cores").getAsJsonArray("cores"); }
    private JsonArray tasks() { return draft.getAsJsonObject("tasks").getAsJsonArray("tasks"); }
    private List<JsonObject> sortedCores() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement core : cores()) result.add(core.getAsJsonObject());
        result.sort(Comparator.comparingInt(core -> core.get("tier").getAsInt()));
        return result;
    }
    private JsonObject core() {
        for (JsonObject candidate : sortedCores())
            if (candidate.get("tier").getAsInt() == selectedTier) return candidate;
        return null;
    }
    private JsonObject task() {
        if (tasks().isEmpty()) return null;
        selectedTask = Math.min(Math.max(0, selectedTask), tasks().size() - 1);
        return tasks().get(selectedTask).getAsJsonObject();
    }
    private boolean enabled() {
        return !draft.getAsJsonObject("cores").has("enabled")
                || draft.getAsJsonObject("cores").get("enabled").getAsBoolean();
    }

    @Override
    protected void init() {
        if (connected() && canEdit() && !dirty && !waitingForServer && !loadedServer && !saving) {
            waitingForServer = true;
            PacketDistributor.sendToServer(new MilestoneConfigNetwork.ConfigRequest());
        }

        int l = left(), w = panelWidth(), t = top(), mid = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"),
                b -> onClose()).bounds(l + w - 82, bottom() - 29, 73, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"),
                b -> saveChanges()).bounds(l + w - 163, bottom() - 29, 73, 20).build())
                .active = canEdit() && !waitingForServer && !saving;

        if (page == Page.HOME) {
            addRenderableWidget(Button.builder(Component.literal(enabled() ? "Enabled: ON" : "Enabled: OFF"),
                    b -> {
                        if (!canEdit() || waitingForServer) return;
                        draft.getAsJsonObject("cores").addProperty("enabled", !enabled());
                        dirty = true;
                        status = "Unsaved changes";
                        refresh();
                    }).bounds(l + w - 115, t + 13, 105, 20).build()).active = canEdit();

            int bw = Math.min(240, w - 45);
            int by = t + 36;
            int step = Math.max(22,Math.min(31,(panelHeight()-111)/4));
            addRenderableWidget(Button.builder(Component.literal("Cores"),
                    b -> navigate(Page.CORES)).bounds(mid - bw / 2, by, bw, 23).build());
            addRenderableWidget(Button.builder(Component.literal("Tasks"),
                    b -> navigate(Page.TASKS)).bounds(mid - bw / 2, by + 1 * step, bw, 23).build());
            addRenderableWidget(Button.builder(Component.literal("Schematics"),
                    b -> navigate(Page.SCHEMATICS)).bounds(mid - bw / 2, by + 2 * step, bw, 23).build());
            addRenderableWidget(Button.builder(Component.literal("Unlocks"),
                    b -> {
                        if (minecraft != null) minecraft.setScreen(
                                new MilestoneUnlocksScreen(this, draft));
                    }).bounds(mid - bw / 2, by + 3 * step, bw, 23).build());
            addRenderableWidget(Button.builder(Component.literal("Shop"),
                    b -> {
                        if (minecraft != null)
                            minecraft.setScreen(new MilestoneShopConfigScreen(this,draft));
                    }).bounds(mid - bw / 2, by + 4 * step, bw, 23).build());
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("< Back"),
                b -> navigate(switch (page) {
                    case CORE_EDIT -> Page.CORES;
                    case TASK_EDIT -> Page.TASKS;
                    case SCHEMATIC_EDIT -> Page.SCHEMATICS;
                    case SCHEMATIC_IMPORT -> Page.SCHEMATICS;
                    case CATEGORY_PICK -> Page.CORE_EDIT;
                    case CATEGORY_ADD -> categoryReturnPage;
                    default -> Page.HOME;
                })).bounds(l + 10, t + 10, 77, 20).build());

        if (page == Page.SHOP) return;
        if (page == Page.SCHEMATICS || page == Page.SCHEMATIC_EDIT)
            addRenderableWidget(Button.builder(Component.literal("Open NBT folder"),
                b -> openSchematicFolder()).bounds(l+w-149,t+10,135,20).build());

        if (page == Page.CATEGORY_ADD) {
            newCategoryBox = new EditBox(font, l + 18, t + 91, w - 36, 20,
                    Component.literal("New category ID"));
            newCategoryBox.setMaxLength(40);
            newCategoryBox.setHint(Component.literal("e.g. farming"));
            newCategoryBox.setEditable(canEdit());
            addRenderableWidget(newCategoryBox);
            addRenderableWidget(Button.builder(Component.literal("Add category"),
                    b -> addCategory()).bounds(mid - 95, t + 123, 190, 20).build())
                    .active = canEdit() && !waitingForServer;
            return;
        }

        if (page == Page.CATEGORY_PICK) {
            addRenderableWidget(Button.builder(Component.literal("+ New category"),
                    b -> openCategoryAdd(Page.CATEGORY_PICK))
                    .bounds(l + 12, t + 42, w - 34, 22).build()).active = canEdit();
            List<String> options = categories();
            int visible = rowsFit(25);
            listScroll = Math.min(smoothRows.first(),Math.max(0, options.size()-visible));
            for (int i=listScroll; i<Math.min(options.size(), listScroll+visible+1); i++) {
                String cat = options.get(i);
                int y = t + 69 + (i-listScroll)*25;
                boolean chosen = core().getAsJsonArray("categories").asList().stream()
                        .anyMatch(el -> el.getAsString().equals(cat));
                addRenderableWidget(Button.builder(Component.literal((chosen ? "[x] " : "[ ] ")
                            + cat + "  (" + countInCategory(cat) + " tasks)"),
                        b -> toggleCoreCategory(cat)).bounds(l + 12,y,w - 34,21).build())
                        .active = canEdit();
            }
            return;
        }

        if (page == Page.CORES || page == Page.TASKS || page == Page.SCHEMATICS) {
            boolean isCore = page == Page.CORES;
            boolean isSchematic = page == Page.SCHEMATICS;
            addRenderableWidget(Button.builder(Component.literal(isCore ? "+ Add Core" : isSchematic ? "+ Add Schematic" : "+ Add Task"),
                    b -> { if (isCore) addCore(); else if (isSchematic) addSchematic(); else addTask(); })
                    .bounds(l + 12, t + 42, w - 34, 22).build())
                    .active = canEdit() && !waitingForServer &&
                            (isCore ? cores().size() < 32 : isSchematic ? schematics().size() < 64 : tasks().size() < 80);

            if (!isCore && !isSchematic) {
                String selectedDifficulty = difficultyFilter == 0 ? "All difficulties"
                        : difficulties().get(Math.floorMod(difficultyFilter-1, difficulties().size()));
                String selectedCategory = categoryFilter == 0 ? "All categories"
                        : categories().get(Math.floorMod(categoryFilter-1, categories().size()));
                addRenderableWidget(Button.builder(Component.literal(selectedDifficulty),
                        b -> { difficultyFilter = (difficultyFilter+1)%(difficulties().size()+1);
                            listScroll=0;refresh(); })
                        .bounds(l+12,t+68,(w-33)/2,20).build());
                addRenderableWidget(Button.builder(Component.literal(selectedCategory),
                        b -> {categoryFilter=(categoryFilter+1)%(categories().size()+1);
                            listScroll=0;refresh();})
                        .bounds(l+16+(w-33)/2,t+68,(w-33)/2,20).build());
            }

            if (isSchematic)
                addRenderableWidget(Button.builder(Component.literal("Import from Create"),
                    b -> {page=Page.SCHEMATIC_IMPORT;listScroll=0;refresh();})
                    .bounds(l+12,t+68,w-34,20).build())
                    .active=canEdit() && canImportLocal();

            List<JsonObject> coreEntries = isCore ? sortedCores() : List.of();
            List<TaskRow> taskEntries = (!isCore && !isSchematic) ? filteredTasks() : List.of();
            int visible = visibleRows();
            int length = isCore ? coreEntries.size() : isSchematic ? schematics().size() : taskEntries.size();
            listScroll = Math.min(smoothRows.first(),Math.max(0,length-visible));
            for(int i=listScroll;i<Math.min(length,listScroll+visible+1);i++){
                JsonObject entry = isCore ? coreEntries.get(i)
                        : isSchematic ? schematics().get(i).getAsJsonObject() : taskEntries.get(i).value();
                int id = isCore ? entry.get("tier").getAsInt()
                        : isSchematic ? i : taskEntries.get(i).index();
                String name = isCore
                    ? "Core " + (id+1) + " - " + entry.get("name").getAsString()
                    : isSchematic ? entry.get("name").getAsString() + " (" + entry.get("file").getAsString() + ")"
                    : "[" + entry.get("difficulty").getAsString() + "] " + entry.get("id").getAsString();
                int y = t + contentTopOffset() + (i-listScroll)*25;
                addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name,w-149)),
                        b -> openEditor(id)).bounds(l+12,y,w-115,21).build());
                addRenderableWidget(Button.builder(Component.literal(confirmDelete==id?"Confirm?":"Delete"),
                        b -> {
                            if (!canEdit()) return;
                            if (confirmDelete==id) deleteEntry(id);
                            else {confirmDelete=id;status="Click Confirm? to delete";refresh();}
                        }).bounds(l+w-99,y,77,21).build())
                        .active=canEdit()&&!waitingForServer;
            }
            return;
        }

        if (page == Page.SCHEMATIC_IMPORT) {
            List<java.nio.file.Path> files = importFiles();
            int visible=visibleRows();
            listScroll=Math.min(smoothRows.first(),Math.max(0,files.size()-visible));
            for(int i=listScroll;i<Math.min(files.size(),listScroll+visible+1);i++) {
                java.nio.file.Path file=files.get(i);
                int y=t+contentTopOffset()+(i-listScroll)*25;
                addRenderableWidget(Button.builder(Component.literal(
                    font.plainSubstrByWidth(file.getFileName().toString(),w-75)),
                    b -> importSchematic(file)).bounds(l+12,y,w-34,21).build());
            }
            return;
        }

        // Edit all Core/Task settings in rows, with mouse wheel scrolling.
        Field[] fields = page == Page.CORE_EDIT ? CORE_FIELDS
                : page == Page.SCHEMATIC_EDIT ? SCHEMATIC_FIELDS : TASK_FIELDS;
        int fit = visibleRows();
        fieldScroll = Math.min(smoothRows.first(),Math.max(0,fields.length-fit));
        int labelWidth = Math.max(82, Math.min(147, (w - 32) * 45 / 100));
        int inputX = l + 15 + labelWidth;
        int inputW = w - labelWidth - 30;
        for (int i = fieldScroll; i < Math.min(fields.length, fieldScroll + fit + 1); i++) {
            Field field = fields[i];
            int y = t + 65 + (i - fieldScroll) * 24;
            if (isToggle(field.key()) || field.key().equals("category")
                    || field.key().equals("difficulty") || field.key().equals("categories")
                    || field.key().equals("schematic")) {
                String label = field.key().equals("categories")
                        ? field.label() + ": select (" + core().getAsJsonArray("categories").size() + ")"
                        : field.key().equals("schematic")
                        ? field.label() + ": " + schematicLabel(core().get("schematic").getAsString())
                        : field.label() + ": " + fieldValue(field);
                addRenderableWidget(Button.builder(Component.literal(
                                font.plainSubstrByWidth(label,w-45)),
                        b -> {
                            if (!collectFields()) return;
                            if (field.key().equals("categories")) {
                                page=Page.CATEGORY_PICK;listScroll=0;refresh();
                            } else if (field.key().equals("schematic")) {
                                cycleSchematic();
                            } else if (field.key().equals("category")) {
                                cycleTaskCategory();
                            } else if (field.key().equals("difficulty")) {
                                cycleDifficulty();
                            } else toggle(field.key());
                        }).bounds(l+16,y,w-45,20).build())
                        .active = canEdit() && !waitingForServer;
            } else {
                boolean browsable = pickerKind(field.key()) != null;
                EditBox input = new EditBox(font, inputX, y, browsable ? inputW-73 : inputW, 19,
                        Component.literal(field.label()));
                input.setMaxLength(3000);
                input.setValue(fieldValue(field));
                input.setEditable(canEdit());
                input.setResponder(value -> { pending.put(field.key(), value); });
                addRenderableWidget(input);
                if (browsable) {
                    String fieldKey = field.key();
                    addRenderableWidget(Button.builder(Component.literal("Browse"),
                            button -> openPicker(fieldKey))
                            .bounds(inputX+inputW-69,y,68,19).build())
                            .active = canEdit() && !waitingForServer;
                }
            }
        }
        if (page == Page.TASK_EDIT)
            addRenderableWidget(Button.builder(Component.literal("+ New category"),
                b -> { if(collectFields())openCategoryAdd(Page.TASK_EDIT); })
                .bounds(l+w-135,t+36,112,19).build()).active=canEdit();
    }

    private int contentTopOffset() {
        return page == Page.TASKS || page == Page.SCHEMATICS ? 94 : 69;
    }
    private int visibleRows() {
        int rowHeight = (page == Page.CORE_EDIT || page == Page.TASK_EDIT) ? 24 : 25;
        int top = (page == Page.CORE_EDIT || page == Page.TASK_EDIT) ? 65 : contentTopOffset();
        return Math.max(1,(panelHeight() - top - 70)/rowHeight);
    }

    private List<JsonObject> taskList() {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement el : tasks()) result.add(el.getAsJsonObject());
        return result;
    }

    /** Pickers are offered only for fields that really accept registry IDs. */
    private MilestoneIdPickerScreen.Kind pickerKind(String field) {
        if (page == Page.TASK_EDIT && field.equals("target")) {
            if (task()==null)return null;
            return switch(targetKey(task())) {
                case "entity" -> MilestoneIdPickerScreen.Kind.ENTITY;
                case "item" -> MilestoneIdPickerScreen.Kind.ITEM;
                case "advancement" -> MilestoneIdPickerScreen.Kind.ADVANCEMENT;
                default -> null;
            };
        }
        if (page == Page.CORE_EDIT) {
            if (field.equals("task_ids"))return MilestoneIdPickerScreen.Kind.TASK;
            if (field.equals("reward_items"))return MilestoneIdPickerScreen.Kind.ITEM;
            if(field.equals("allowed_biomes") || field.equals("blocked_biomes"))
                return MilestoneIdPickerScreen.Kind.BIOME;
        }
        return null;
    }

    private List<String> availableTaskIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (JsonElement item : tasks())
            ids.add(item.getAsJsonObject().get("id").getAsString());
        for (var type : net.fireboy.milestone.milestone.MilestoneType.values())
            for (var definition : net.fireboy.milestone.task.MilestoneTaskCatalog.tasksFor(type))
                ids.add(definition.id());
        return new ArrayList<>(ids);
    }

    private void openPicker(String field) {
        if(!canEdit() || !collectFields() || minecraft==null)return;
        var kind=pickerKind(field);
        if(kind==null)return;
        String current=fieldValue(new Field(field,field));
        java.util.LinkedHashSet<String> original=new java.util.LinkedHashSet<>();
        Map<String,Integer> rewardAmounts=new java.util.HashMap<>();
        for(String segment:current.split(",")) {
            String token=segment.trim();
            if(token.isEmpty())continue;
            if(field.equals("reward_items")) {
                String[] parts=token.split("\\*");
                if(parts.length!=2)continue;
                try {
                    rewardAmounts.put(parts[0].trim(),Integer.parseInt(parts[1].trim()));
                } catch(NumberFormatException ignored) {}
                original.add(parts[0].trim());
            } else original.add(token);
        }
        boolean many=!field.equals("target");
        minecraft.setScreen(new MilestoneIdPickerScreen(this,kind,many,original,
                kind==MilestoneIdPickerScreen.Kind.TASK?availableTaskIds():List.of(),
                chosen -> {
                    String updated;
                    if(field.equals("reward_items")) {
                        java.util.List<String> values=new ArrayList<>();
                        for(String id:chosen)
                            values.add(id+"*"+rewardAmounts.getOrDefault(id,1));
                        updated=String.join(", ",values);
                    }else updated=String.join(", ",chosen);
                    pending.put(field,updated);
                    dirty=true;
                    status="Selected IDs added to draft - press Save";
                }));
    }

    private static boolean isToggle(String field) {
        return field.equals("enabled") || field.equals("task_mode") || field.equals("profile")
                || field.equals("type");
    }

    private static String targetKey(JsonObject item) {
        String type = item.has("type") ? item.get("type").getAsString() : "item";
        return switch (type) {
            case "kill" -> "entity";
            case "advancement" -> "advancement";
            default -> "item";
        };
    }

    private String fieldValue(Field field) {
        if (pending.containsKey(field.key())) return pending.get(field.key());
        JsonObject record = page == Page.CORE_EDIT ? core()
                : page == Page.SCHEMATIC_EDIT ? schematic() : task();
        if (record == null) return "";
        String key = field.key().equals("target") ? targetKey(record) : field.key();
        JsonElement el = record.get(key);
        if (el == null) return "";
        if (!el.isJsonArray()) return el.getAsString();
        StringBuilder value = new StringBuilder();
        for (JsonElement part : el.getAsJsonArray()) {
            if (!value.isEmpty()) value.append(key.equals("reward_commands") ? ";;" : ", ");
            if (part.isJsonObject()) {
                var item = part.getAsJsonObject();
                value.append(item.get("item").getAsString()).append("*")
                        .append(item.get("count").getAsInt());
            } else value.append(part.getAsString());
        }
        return value.toString();
    }

    private void toggle(String key) {
        JsonObject record = page == Page.CORE_EDIT ? core() : task();
        if (record == null) return;
        switch (key) {
            case "enabled" -> record.addProperty("enabled",
                    !record.get("enabled").getAsBoolean());
            case "task_mode" -> record.addProperty("task_mode",
                    record.get("task_mode").getAsString().equals("preset") ? "manual" : "preset");
            case "profile" -> record.addProperty("profile",
                    record.get("profile").getAsString().equals("generic") ? "nether_gate" : "generic");
            case "type" -> {
                String next = switch (record.get("type").getAsString()) {
                    case "item" -> "kill";
                    case "kill" -> "advancement";
                    default -> "item";
                };
                record.remove("item"); record.remove("entity"); record.remove("advancement");
                record.addProperty("type", next);
                record.addProperty(targetKey(record), switch (next) {
                    case "kill" -> "minecraft:zombie";
                    case "advancement" -> "minecraft:story/mine_diamond";
                    default -> "minecraft:stone";
                });
            }
        }
        dirty = true;
        status = "Unsaved changes";
        refresh();
    }

    private boolean collectFields() {
        if (pending.isEmpty()) return true;
        JsonObject record = page == Page.CORE_EDIT ? core()
                : page == Page.SCHEMATIC_EDIT ? schematic() : task();
        if (record == null) return true;
        try {
            JsonObject edited = record.deepCopy();
            for (var entry : pending.entrySet()) {
                String key = entry.getKey(), value = entry.getValue().trim();
                if (key.equals("target")) key = targetKey(edited);
                if (key.equals("min_y") || key.equals("max_y")
                        || key.equals("min_distance") || key.equals("max_distance")
                        || key.equals("reward_xp") || key.equals("count")) {
                    edited.addProperty(key, Integer.parseInt(value));
                } else if (key.equals("depth_fraction")) {
                    edited.addProperty(key, Double.parseDouble(value));
                } else if (isList(key)) {
                    JsonArray list = new JsonArray();
                    String[] values = key.equals("reward_commands") ? value.split(";;") : value.split(",");
                    for (String part : values) {
                        part = part.trim();
                        if (part.isEmpty()) continue;
                        if (key.equals("requires_completed_cores")) {
                            list.add(Integer.parseInt(part));
                        } else if (key.equals("reward_items")) {
                            String[] pieces = part.split("\\*");
                            if (pieces.length != 2) throw new IllegalArgumentException("Use item_id*count");
                            JsonObject reward = new JsonObject();
                            reward.addProperty("item", pieces[0].trim());
                            reward.addProperty("count", Integer.parseInt(pieces[1].trim()));
                            list.add(reward);
                        } else list.add(part);
                    }
                    edited.add(key, list);
                } else edited.addProperty(key, value);
            }
            for (var entry : edited.entrySet())
                record.add(entry.getKey(), entry.getValue().deepCopy());
            pending.clear();
            dirty = true;
            status = "Unsaved changes";
            return true;
        } catch (Exception exception) {
            status = "Invalid value: " + exception.getMessage();
            return false;
        }
    }

    private static boolean isList(String key) {
        return key.equals("categories") || key.equals("task_ids")
                || key.equals("requires_completed_cores")
                || key.equals("allowed_biomes") || key.equals("blocked_biomes")
                || key.equals("reward_items") || key.equals("reward_commands");
    }

    private void cycleTaskCategory() {
        JsonObject selected = task();
        if (selected == null) return;
        List<String> options = categories();
        if (options.isEmpty()) return;
        String current = selected.has("category") ? selected.get("category").getAsString() : "";
        int index = options.indexOf(current);
        selected.addProperty("category", options.get((index+1) % options.size()));
        dirty = true;
        status = "Category assigned (unsaved)";
        refresh();
    }

    private void cycleDifficulty() {
        JsonObject selected = page == Page.CORE_EDIT ? core() : task();
        if (selected == null) return;
        List<String> options = difficulties();
        if (page == Page.CORE_EDIT) options.add("*");
        String previous = selected.get("difficulty").getAsString();
        int index = options.indexOf(previous);
        selected.addProperty("difficulty", options.get((index+1) % options.size()));
        dirty = true;
        status = "Difficulty updated (unsaved)";
        refresh();
    }

    private void openCategoryAdd(Page returnPage) {
        categoryReturnPage = returnPage;
        page = Page.CATEGORY_ADD;
        refresh();
    }

    private void addCategory() {
        if (!canEdit() || newCategoryBox == null) return;
        String name = newCategoryBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            status = "Use 1-32 lowercase letters, numbers, - or _";
            return;
        }
        JsonArray catalog = tasksRoot().has("categories")
                ? tasksRoot().getAsJsonArray("categories") : new JsonArray();
        if (!categories().contains(name)) catalog.add(name);
        tasksRoot().add("categories", catalog);
        if (categoryReturnPage == Page.TASK_EDIT && task() != null)
            task().addProperty("category", name);
        if (categoryReturnPage == Page.CATEGORY_PICK && core() != null) {
            JsonArray selected = core().getAsJsonArray("categories");
            if (!selected.asList().stream().anyMatch(el->el.getAsString().equals(name)))
                selected.add(name);
        }
        dirty = true;
        status = "Category " + name + " added (unsaved)";
        page = categoryReturnPage;
        listScroll = 0;
        refresh();
    }

    private void toggleCoreCategory(String name) {
        if (!canEdit() || core() == null) return;
        JsonArray old = core().getAsJsonArray("categories");
        JsonArray updated = new JsonArray();
        boolean removed = false;
        for (JsonElement item : old) {
            if (item.getAsString().equals(name)) removed = true;
            else updated.add(item.getAsString());
        }
        if (!removed) updated.add(name);
        core().add("categories", updated);
        dirty = true;
        status = "Core categories updated (unsaved)";
        refresh();
    }

    private void openEditor(int id) {
        selectedTier = page == Page.CORES ? id : selectedTier;
        selectedTask = page == Page.TASKS ? id : selectedTask;
        selectedSchematic = page == Page.SCHEMATICS ? id : selectedSchematic;
        pending.clear(); fieldScroll = 0; confirmDelete = -1;
        page = page == Page.CORES ? Page.CORE_EDIT
                : page == Page.SCHEMATICS ? Page.SCHEMATIC_EDIT : Page.TASK_EDIT;
        refresh();
    }

    private void navigate(Page dest) {
        if ((page == Page.CORE_EDIT || page == Page.TASK_EDIT || page == Page.SCHEMATIC_EDIT)
                && !collectFields()) return;
        pending.clear(); confirmDelete = -1;
        page = dest; listScroll = 0; fieldScroll = 0;
        refresh();
    }

    private void addCore() {
        if (!canEdit() || cores().size() >= 32) return;
        int next = 0;
        for (JsonObject candidate : sortedCores())
            next = Math.max(next, candidate.get("tier").getAsInt() + 1);
        if (next >= 32) {
            status = "Maximum Core ID (31) reached";
            return;
        }
        JsonObject profile = cores().size() > 0 ? cores().get(0).getAsJsonObject().deepCopy() : new JsonObject();
        profile.addProperty("tier", next);
        profile.addProperty("name", "New Milestone " + (next + 1));
        profile.addProperty("enabled", true);
        profile.addProperty("profile", "generic");
        profile.addProperty("task_mode", "preset");
        profile.addProperty("difficulty", "easy");
        profile.add("task_ids", new JsonArray());
        profile.add("requires_completed_cores", new JsonArray());
        profile.add("categories", new JsonArray());
        profile.add("allowed_biomes", new JsonArray());
        profile.add("blocked_biomes", new JsonArray());
        profile.add("reward_items", new JsonArray());
        profile.add("reward_commands", new JsonArray());
        profile.addProperty("reward_xp", 0);
        profile.addProperty("dimension", "minecraft:overworld");
        profile.addProperty("min_y", -54);
        profile.addProperty("max_y", 120);
        profile.addProperty("min_distance", 0);
        profile.addProperty("max_distance", 0);
        profile.addProperty("depth_fraction", 0.5);
        profile.addProperty("schematic", "generated");
        profile.addProperty("color", "#FFFFFF");
        cores().add(profile);
        dirty = true;
        status = "New Core added to draft";
        selectedTier = next;
        page = Page.CORE_EDIT;
        fieldScroll = 0;
        refresh();
    }

    private boolean canImportLocal() {
        return minecraft!=null && (!connected() || minecraft.hasSingleplayerServer());
    }

    private List<java.nio.file.Path> importFiles() {
        try (var files=java.nio.file.Files.list(
                com.simibubi.create.foundation.utility.CreatePaths.SCHEMATICS_DIR
                    .toAbsolutePath().normalize())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".nbt")
                            && java.nio.file.Files.isRegularFile(path)
                            && !java.nio.file.Files.isSymbolicLink(path))
                    .sorted().limit(128).toList();
        } catch (Exception ex) { return List.of(); }
    }

    private void importSchematic(java.nio.file.Path source) {
        if (!canEdit() || !canImportLocal() || schematics().size()>=64) return;
        try {
            var root=com.simibubi.create.foundation.utility.CreatePaths.SCHEMATICS_DIR
                    .toAbsolutePath().normalize();
            source=source.toAbsolutePath().normalize();
            if (!source.getParent().equals(root) || java.nio.file.Files.isSymbolicLink(source)
                    || java.nio.file.Files.size(source)>262144) {
                status="Unsafe/oversized file";
                return;
            }
            String stem=source.getFileName().toString();
            stem=stem.substring(0,stem.length()-4).toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9_-]","_");
            if(stem.isBlank())stem="imported";
            if(stem.length()>40)stem=stem.substring(0,40);
            String id=stem;
            int suffix=2;
            while(schematicIds().contains(id))
                id=stem+"_"+suffix++;
            java.nio.file.Path folder=net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("milestone").resolve("schematics").toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Path output=folder.resolve(id+".nbt");
            java.nio.file.Files.copy(source,output);
            try {
                net.fireboy.milestone.milestone.ConfiguredSchematic.validateFile(id+".nbt");
            } catch(Exception invalid) {
                java.nio.file.Files.deleteIfExists(output);
                throw invalid;
            }
            JsonObject entry=new JsonObject();
            entry.addProperty("id",id);
            entry.addProperty("name",stem.replace('_',' '));
            entry.addProperty("file",id+".nbt");
            schematics().add(entry);
            selectedSchematic=schematics().size()-1;
            dirty=true;
            pending.clear();
            page=Page.SCHEMATIC_EDIT;
            fieldScroll=0;
            status="Imported structure; you can edit its name";
            refresh();
        } catch(Exception e) {
            status="Import failed: "+e.getMessage();
        }
    }

    private void addSchematic() {
        if (!canEdit() || schematics().size()>=64) return;
        int next=1;
        while (schematicIds().contains("schematic_"+next)) next++;
        String id="schematic_"+next;
        JsonObject e=new JsonObject();
        e.addProperty("id",id);
        e.addProperty("name","New Schematic "+next);
        e.addProperty("file",id+".nbt");
        schematics().add(e);
        selectedSchematic=schematics().size()-1;
        pending.clear();
        dirty=true;
        status="Copy the .nbt file to the schematic folder before Save";
        page=Page.SCHEMATIC_EDIT;
        fieldScroll=0;
        refresh();
    }
    private void cycleSchematic() {
        if (!canEdit() || core()==null) return;
        List<String> ids=schematicIds();
        int current=ids.indexOf(core().get("schematic").getAsString());
        core().addProperty("schematic",ids.get((current+1)%ids.size()));
        dirty=true;
        status="Schematic selected (unsaved)";
        refresh();
    }
    private void openSchematicFolder() {
        try {
            var folder=net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("milestone").resolve("schematics");
            java.nio.file.Files.createDirectories(folder);
            net.minecraft.Util.getPlatform().openFile(folder.toFile());
            status="Put compressed .nbt structure files in this folder";
        } catch(Exception ex) {
            status="Could not open folder: "+ex.getMessage();
        }
    }

    private void addTask() {
        if (!canEdit() || tasks().size() >= 80) return;
        int number = 1;
        while (hasTaskId("custom_task_" + number)) number++;
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "custom_task_" + number);
        entry.addProperty("type", "kill");
        entry.addProperty("entity", "minecraft:zombie");
        entry.addProperty("count", 1);
        entry.addProperty("category", "combat");
        entry.addProperty("difficulty", "easy");
        entry.addProperty("clue", "Defeat a zombie");
        tasks().add(entry);
        dirty = true;
        selectedTask = tasks().size() - 1;
        page = Page.TASK_EDIT;
        fieldScroll = 0;
        refresh();
    }

    private boolean hasTaskId(String id) {
        for (JsonObject candidate : taskList())
            if (candidate.get("id").getAsString().equals(id)) return true;
        return false;
    }

    private void deleteEntry(int id) {
        if (!canEdit()) return;
        if (page == Page.CORES) {
            for (int i = 0; i < cores().size(); i++) {
                if (cores().get(i).getAsJsonObject().get("tier").getAsInt() == id) {
                    cores().remove(i);
                    break;
                }
            }
            status = "Core removed from draft. Existing placed Cores cannot be deleted.";
        } else if (page == Page.SCHEMATICS && id >= 0 && id < schematics().size()) {
            String removed = schematics().get(id).getAsJsonObject().get("id").getAsString();
            for (JsonElement entry : cores()) {
                if (entry.getAsJsonObject().get("schematic").getAsString().equals(removed)) {
                    status = "Still used by a Core - choose another schematic first";
                    confirmDelete = -1; refresh(); return;
                }
            }
            schematics().remove(id);
            status = "Schematic entry removed (the .nbt file is not deleted)";
        } else if (id >= 0 && id < tasks().size()) {
            tasks().remove(id);
            status = "Task removed from draft. Check manual task lists before saving.";
        }
        dirty = true;
        confirmDelete = -1;
        refresh();
    }

    private void saveChanges() {
        if (!canEdit() || waitingForServer || saving || !collectFields()) return;
        String json = draft.toString();
        if (json.length() > 64000) { status = "Configuration exceeds 64 KiB"; return; }
        if (connected()) {
            saving = true;
            status = "Saving server config...";
            refresh();
            PacketDistributor.sendToServer(new MilestoneConfigNetwork.ConfigSave(json));
        } else {
            status = MilestoneContentConfig.saveBundle(json);
            if (status.startsWith("Saved")) {
                MilestoneContentConfig.installClient(json);
                dirty = false;
                onClose();
            }
        }
    }

    public void refreshFromServer(String json) {
        loadedServer = true;
        waitingForServer = false;
        if (!dirty && !saving) {
            try {
                draft = JsonParser.parseString(json).getAsJsonObject();
                status = "Server settings loaded";
                refresh();
            } catch (Exception e) {
                status = "Could not read server settings";
            }
        }
    }

    public void onSaveResult(boolean success, String message) {
        saving = false;
        status = message;
        if (success) {
            dirty = false;
            onClose();
        } else refresh();
    }

    /** Sub-pages modify the draft only; Save/Cancel remain authoritative here. */
    public void markConfigDirty() {
        dirty = true;
        status = "Unsaved feature changes";
    }

    private void refresh() {
        if (lastSmoothPage != page) {
            smoothRows.reset();
            lastSmoothPage = page;
        }
        clearWidgets();
        init();
        smoothRows.invalidate();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private boolean scrollablePage() {
        return page == Page.CORES || page == Page.TASKS || page == Page.SCHEMATICS
                || page == Page.SCHEMATIC_IMPORT
                || page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT
                || page == Page.CATEGORY_PICK;
    }

    private int scrollbarCount() {
        return switch (page) {
            case CORES -> cores().size();
            case TASKS -> filteredTasks().size();
            case SCHEMATICS -> schematics().size();
            case SCHEMATIC_IMPORT -> importFiles().size();
            case SCHEMATIC_EDIT -> SCHEMATIC_FIELDS.length;
            case CORE_EDIT -> CORE_FIELDS.length;
            case TASK_EDIT -> TASK_FIELDS.length;
            case CATEGORY_PICK -> categories().size();
            default -> 0;
        };
    }

    private int scrollbarVisible() {
        return (page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT)
                ? visibleRows() : (page == Page.CATEGORY_PICK ? rowsFit(25) : visibleRows());
    }

    private int scrollbarOffset() {
        return (page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT) ? fieldScroll : listScroll;
    }

    private void setScrollbarOffset(double newOffset) {
        smoothRows.drag(newOffset,Math.max(0,scrollbarCount()-scrollbarVisible()));
        int row=smoothRows.first();
        if (page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT) {
            if(row!=fieldScroll){fieldScroll=row;refresh();}
        } else if(row!=listScroll){listScroll=row;refresh();}
    }

    private int scrollbarX() { return left()+panelWidth()-17; }
    private int scrollbarY() {
        return top() + ((page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT) ? 65 : contentTopOffset());
    }
    private int scrollbarHeight() {
        return scrollbarVisible() * ((page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT) ? 24 : 25);
    }
    private int scrollbarThumb() {
        return Math.max(16, (int)Math.round(scrollbarHeight()*
                (scrollbarVisible()/(double)Math.max(1,scrollbarCount()))));
    }
    private void scrollFromMouse(double mouseY) {
        int max = scrollbarCount()-scrollbarVisible();
        int travel = scrollbarHeight()-scrollbarThumb();
        if (max <= 0 || travel <= 0) return;
        setScrollbarOffset((mouseY-scrollbarY()-scrollbarThumb()/2.0)*max/travel);
    }

    @Override
    public boolean mouseClicked(double mouseX,double mouseY,int button) {
        if (button == 0 && scrollablePage()
                && scrollbarCount()>scrollbarVisible()
                && mouseX>=scrollbarX()-2 && mouseX<=scrollbarX()+11
                && mouseY>=scrollbarY() && mouseY<=scrollbarY()+scrollbarHeight()) {
            draggingScrollbar=true;
            scrollFromMouse(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX,mouseY,button);
    }

    @Override
    public boolean mouseDragged(double mouseX,double mouseY,int button,double dx,double dy) {
        if (button==0 && draggingScrollbar) {
            scrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX,mouseY,button,dx,dy);
    }

    @Override
    public boolean mouseReleased(double mouseX,double mouseY,int button) {
        if (button==0 && draggingScrollbar) {
            draggingScrollbar=false;
            return true;
        }
        return super.mouseReleased(mouseX,mouseY,button);
    }

    @Override
    public boolean mouseScrolled(double x,double y,double dx,double dy) {
        if (scrollablePage() && scrollbarCount()>scrollbarVisible()) {
            smoothRows.wheel(dy,Math.max(0,scrollbarCount()-scrollbarVisible()));
            return true;
        }
        return super.mouseScrolled(x,y,dx,dy);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (!scrollablePage() || scrollbarCount()<=scrollbarVisible()) return;
        int x=scrollbarX(), y=scrollbarY(), h=scrollbarHeight();
        int travel = h-scrollbarThumb();
        int max = scrollbarCount()-scrollbarVisible();
        int thumbY = y + (int)Math.round(travel*smoothRows.value()/(double)Math.max(1,max));
        graphics.fill(x,y,x+9,y+h,0xFF161616);
        graphics.fill(x+1,thumbY,x+8,thumbY+scrollbarThumb(),0xFFBDBDBD);
        graphics.fill(x+1,thumbY,x+8,thumbY+1,0xFFEAEAEA);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        int l = left(), t = top(), w = panelWidth(), h = panelHeight();
        if(lastSmoothPage!=page) {
            smoothRows.reset();
            lastSmoothPage=page;
        }
        if(scrollablePage()) {
            int step=(page==Page.CORE_EDIT || page==Page.TASK_EDIT
                    || page==Page.SCHEMATIC_EDIT)?24:25;
            smoothRows.configure(l+11,l+w-13,scrollbarY(),step,scrollbarVisible());
            smoothRows.advance(Math.max(0,scrollbarCount()-scrollbarVisible()),
                ()->{
                    if(page==Page.CORE_EDIT || page==Page.TASK_EDIT
                            || page==Page.SCHEMATIC_EDIT)fieldScroll=smoothRows.first();
                    else listScroll=smoothRows.first();
                    refresh();
                });
            smoothRows.captureIfChanged(children());
            smoothRows.beforeStatic();
        }
        // Vanilla can blur the frame when rendering a Screen. It must run
        // BEFORE our fully opaque backdrop and ALL custom UI pixels.
        super.render(graphics, mouseX, mouseY, tick);
        graphics.fill(0, 0, width, height, 0xFF171717);
        graphics.fill(l, t, l + w, t + h, PANEL);
        graphics.fill(l, t, l + w, t + 1, EDGE);
        graphics.fill(l, t + h - 1, l + w, t + h, EDGE);
        graphics.fill(l, t, l + 1, t + h, EDGE);
        graphics.fill(l + w - 1, t, l + w, t + h, EDGE);
        for(var child:children())
            if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.visible)
                widget.render(graphics,mouseX,mouseY,tick);
        if(scrollablePage())smoothRows.renderRows(graphics,mouseX,mouseY,tick);
        drawReferencedItemIcons(graphics);
        if (page == Page.HOME) {
            graphics.drawString(font, "Milestone Configuration", l + 14, t + 19, TEXT);
        } else {
            String title = switch (page) {
                case CORES -> "Cores (" + cores().size() + ")";
                case CORE_EDIT -> "Edit Core " + (selectedTier + 1);
                case TASKS -> "Tasks (" + tasks().size() + ")";
                case TASK_EDIT -> "Edit Task";
                case SCHEMATICS -> "Schematics (" + schematics().size() + ")";
                case SCHEMATIC_IMPORT -> "Import from Create";
                case SCHEMATIC_EDIT -> "Edit Schematic";
                case CATEGORY_PICK -> "Core categories";
                case CATEGORY_ADD -> "Create category";
                case SHOP -> "Shop";
                default -> "";
            };
            graphics.drawCenteredString(font, title, width / 2, t + 18, TEXT);
        }
        if (page == Page.CORE_EDIT || page == Page.TASK_EDIT
                || page == Page.SCHEMATIC_EDIT) {
            Field[] fields = page == Page.CORE_EDIT ? CORE_FIELDS
                : page == Page.SCHEMATIC_EDIT ? SCHEMATIC_FIELDS : TASK_FIELDS;
            int labelW = Math.max(82, Math.min(147, (w - 32) * 45 / 100));
            graphics.enableScissor(l+11,scrollbarY(),l+w-13,
                    scrollbarY()+scrollbarVisible()*24);
            for (int i = fieldScroll; i < Math.min(fields.length, fieldScroll + visibleRows()+1); i++) {
                Field field = fields[i];
                if (isToggle(field.key()) || field.key().equals("category")
                        || field.key().equals("difficulty") || field.key().equals("categories")
                        || field.key().equals("schematic")) continue;
                graphics.drawString(font, font.plainSubstrByWidth(field.label(), labelW - 4),
                        l + 16, t + 72 + (i - fieldScroll)*24 - smoothRows.pixels(), MUTED);
            }
            graphics.disableScissor();
            graphics.drawCenteredString(font,
                    "Scroll for more settings. Changes apply when you press Save.",
                    width / 2, bottom() - 71, MUTED);
        }
        if (page == Page.SCHEMATIC_IMPORT) {
            graphics.drawCenteredString(font,"Select an .nbt in Create's schematic folder.",
                    width/2,bottom()-82,MUTED);
        }
        if (page == Page.SCHEMATIC_EDIT) {
            graphics.drawCenteredString(font,"NBT location: config/milestone/schematics/",
                    width/2,bottom()-92,MUTED);
            graphics.drawCenteredString(font,"Save requires the file on the server.",
                    width/2,bottom()-81,MUTED);
        }
        if (page == Page.TASKS) {
            String label = categoryFilter == 0 ? "All categories"
                    : categories().get(Math.floorMod(categoryFilter-1,categories().size()));
            graphics.drawCenteredString(font,
                label + " - " + filteredTasks().size() + " tasks",width/2,t+32,MUTED);
            if(filteredTasks().isEmpty())
                graphics.drawCenteredString(font,"No tasks match these filters.",width/2,t+133,MUTED);
        }
        if (page == Page.CATEGORY_ADD) {
            graphics.drawCenteredString(font,"Enter a name for the new category.",width/2,t+64,MUTED);
        }
        if (page == Page.SHOP) {
            graphics.drawCenteredString(font, "Shop settings are coming later.",
                    width / 2, t + 92, TEXT);
            graphics.drawCenteredString(font, "No items or purchases are available yet.",
                    width / 2, t + 111, MUTED);
        }
        if (!canEdit()) {
            graphics.drawCenteredString(font, "Server operator access required to edit.",
                    width / 2, bottom() - 68, 0xFFFFCC88);
        } else if (waitingForServer) {
            graphics.drawCenteredString(font, "Loading server settings...",
                    width / 2, bottom() - 68, MUTED);
        } else if (!status.isBlank()) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(status, w - 24),
                    width / 2, bottom() - 68, dirty ? 0xFFFFD27C : TEXT);
        }
        renderScrollbar(graphics);
    }

    /** Show item sprites anywhere this editor is displaying item-valued data.
     * Position uses the same fractional offset as animated list widgets. */
    private void drawReferencedItemIcons(GuiGraphics graphics) {
        int l=left(),t=top(),w=panelWidth();
        if(page == Page.TASKS) {
            List<TaskRow> matches=filteredTasks();
            graphics.enableScissor(l+11,scrollbarY(),l+w-13,
                    scrollbarY()+scrollbarVisible()*25);
            for(int i=listScroll;i<Math.min(matches.size(),listScroll+visibleRows()+1);i++) {
                JsonObject entry=matches.get(i).value();
                if(!targetKey(entry).equals("item") || !entry.has("item"))continue;
                var stack=MilestoneItemIcons.item(entry.get("item").getAsString());
                if(stack.isEmpty())continue;
                int y=t+contentTopOffset()+(i-listScroll)*25-smoothRows.pixels();
                graphics.renderItem(stack,l+18,y+2);
            }
            graphics.disableScissor();
            return;
        }
        if(page!=Page.CORE_EDIT && page!=Page.TASK_EDIT)return;
        int labelWidth=Math.max(82,Math.min(147,(w-32)*45/100));
        int inputX=l+15+labelWidth;
        Field[] fields=page==Page.CORE_EDIT?CORE_FIELDS:TASK_FIELDS;
        graphics.enableScissor(l+11,scrollbarY(),l+w-13,
                scrollbarY()+scrollbarVisible()*24);
        for(int i=fieldScroll;i<Math.min(fields.length,fieldScroll+visibleRows()+1);i++) {
            Field field=fields[i];
            net.minecraft.world.item.ItemStack stack=net.minecraft.world.item.ItemStack.EMPTY;
            if(page==Page.TASK_EDIT && field.key().equals("target")
                    && task()!=null && targetKey(task()).equals("item"))
                stack=MilestoneItemIcons.item(fieldValue(field));
            else if(page==Page.CORE_EDIT && field.key().equals("reward_items"))
                stack=MilestoneItemIcons.firstReward(fieldValue(field));
            if(!stack.isEmpty()) {
                int y=t+65+(i-fieldScroll)*24-smoothRows.pixels();
                graphics.renderItem(stack,inputX-20,y+2);
            }
        }
        graphics.disableScissor();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
