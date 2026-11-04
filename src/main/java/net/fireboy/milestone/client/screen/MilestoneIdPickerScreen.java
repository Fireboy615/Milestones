package net.fireboy.milestone.client.screen;

import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.fireboy.milestone.network.MilestonePickerSearch;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One searchable selector for local registries, custom tasks and remote recipes.
 *
 * Filters: name/ID/both, mod namespace, selected-only, and entity categories.
 * Syntax: multiple words (AND), -exclude, @namespace, id:term, name:term.
 * Search results update without rebuilding widgets or stealing keyboard focus.
 * Entity spawn eggs appear in rows; a safe, on-demand 3D mob preview is shown
 * for the hovered row when the entity supports an inventory renderer.
 */
public final class MilestoneIdPickerScreen extends Screen {
    public enum Kind { ENTITY, ITEM, BLOCK, ENCHANTMENT, RECIPE, ADVANCEMENT, DIMENSION, BIOME, TASK }
    private enum MobFilter { ALL, HOSTILE, PASSIVE, AQUATIC, OTHER }
    private enum Order { RELEVANCE, ALPHABETICAL, SELECTED_FIRST }

    private record Entry(String id, String name) {}
    private final Screen parent;
    private final Kind kind;
    private final boolean multi;
    private final Consumer<List<String>> onApply;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Entry> registryEntries = new ArrayList<>();
    private final Map<String,List<String>> collections = new LinkedHashMap<>();
    private final Map<String,String> names = new HashMap<>();
    /** Actual recipe output or a representative output from the mod's recipes. */
    private final Map<String,String> recipeItemIcons = new HashMap<>();
    private final List<Button> rowButtons = new ArrayList<>();

    private List<Entry> filtered = List.of();
    private List<Entry> remoteRows = List.of();
    private List<Entry> rows = List.of();
    private String query = "";
    private String namespace = "";
    private boolean showingCollections;
    /** Recipe mode uses a one-rule-per-mod list instead of thousands of IDs. */
    private boolean browsingRecipeMods;
    private boolean selectedOnly;
    private MilestonePickerSearch.Field matchField = MilestonePickerSearch.Field.BOTH;
    private MobFilter mobFilter = MobFilter.ALL;
    private Order order = Order.RELEVANCE;
    private int offset;
    private boolean dragging;
    private EditBox searchBox, namespaceBox;
    private Button matchButton, selectedButton, typeButton, sortButton, applyButton, browseModsButton;
    private String sentQuery, sentKind;
    private int sentOffset = -1, debounce = 0;
    private int remoteTotal;
    private String remoteStatus = "Search server IDs";
    private String previewId;
    private LivingEntity previewLiving;
    private boolean previewFailed;
    private String highlightedId;

    public MilestoneIdPickerScreen(Screen parent, Kind kind, boolean multi,
                                   Collection<String> existing, Collection<String> customIds,
                                   Consumer<List<String>> onApply) {
        super(Component.literal("Select " + kind.name().toLowerCase(Locale.ROOT) + " IDs"));
        this.parent = parent;
        this.kind = kind;
        this.multi = multi;
        this.onApply = onApply;
        // Reward editing normally starts with whole-mod selection, not an
        // unmanageable list of thousands of individual recipes.
        this.browsingRecipeMods = kind == Kind.RECIPE && multi;
        selected.addAll(existing);
        if (kind == Kind.TASK) {
            for (String id : customIds) registryEntries.add(new Entry(id, prettify(id)));
        } else if (kind == Kind.ITEM) {
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet())
                registryEntries.add(new Entry(id.toString(),
                        BuiltInRegistries.ITEM.get(id).getDescription().getString()));
        } else if (kind == Kind.BLOCK) {
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet())
                registryEntries.add(new Entry(id.toString(),
                        BuiltInRegistries.BLOCK.get(id).getName().getString()));
        } else if (kind == Kind.ENTITY) {
            for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet())
                registryEntries.add(new Entry(id.toString(),
                        BuiltInRegistries.ENTITY_TYPE.get(id).getDescription().getString()));
        } else if (kind == Kind.ENCHANTMENT && Minecraft.getInstance().level != null) {
            Minecraft.getInstance().level.registryAccess().registry(Registries.ENCHANTMENT).ifPresent(reg -> {
                for (ResourceLocation id : reg.keySet()) {
                    var enchant = reg.get(id);
                    if (enchant != null)
                        registryEntries.add(new Entry(id.toString(), enchant.description().getString()));
                }
            });
        } else if (kind == Kind.DIMENSION) {
            // Title-screen fallback when there is no server registry to query.
            for (String dimension : List.of("minecraft:overworld",
                    "minecraft:the_nether", "minecraft:the_end"))
                registryEntries.add(new Entry(dimension, prettify(dimension)));
        } else if (kind == Kind.BIOME && Minecraft.getInstance().level != null) {
            Minecraft.getInstance().level.registryAccess().registry(Registries.BIOME).ifPresent(reg -> {
                for (ResourceLocation id : reg.keySet())
                    registryEntries.add(new Entry(id.toString(), prettify(id.toString())));
            });
        }
        registryEntries.sort(Comparator.comparing(Entry::id));
        for (Entry entry : registryEntries) names.put(entry.id(), entry.name());
        for (String id : selected) names.putIfAbsent(id,
                id.endsWith(":*") ? "All " + prettify(id.substring(0, id.length() - 2))
                        + " recipes" : prettify(id));

        if (multi && kind == Kind.ITEM) {
            BuiltInRegistries.ITEM.getTags().forEach(pair -> {
                List<String> members = new ArrayList<>();
                pair.getSecond().stream().forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> members.add(key.location().toString())));
                if (!members.isEmpty())
                    collections.put("#" + pair.getFirst().location(), List.copyOf(members));
            });
        } else if (multi && kind == Kind.BLOCK) {
            BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
                List<String> members = new ArrayList<>();
                pair.getSecond().stream().forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> members.add(key.location().toString())));
                if (!members.isEmpty())
                    collections.put("#" + pair.getFirst().location(), List.copyOf(members));
            });
        }
    }

    private static String prettify(String id) {
        String part = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder text = new StringBuilder();
        for (String token : part.replace('_', ' ').replace('/', ' ').split(" ")) {
            if (token.isEmpty()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return text.toString();
    }

    private int pw() { return Math.min(520, width - 16); }
    private int ph() { return Math.min(372, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int firstY() { return top() + 104; }
    private int rowHeight() { return 25; }
    private int capacity() { return Math.max(1, (ph() - 157) / rowHeight()); }
    private boolean remote() {
        return kind == Kind.RECIPE || kind == Kind.ADVANCEMENT
                || kind == Kind.DIMENSION && minecraft != null
                    && minecraft.getConnection() != null;
    }
    private boolean previewPane() { return kind == Kind.ENTITY && pw() >= 395; }
    private int paneWidth() { return previewPane() ? 128 : 0; }
    private int listWidth() { return pw() - paneWidth() - 34; }
    private int count() { return selectedOnly ? filtered.size() : remote() ? remoteTotal : filtered.size(); }
    private int maxOffset() { return Math.max(0, count() - capacity()); }
    private int trackX() { return left() + 12 + listWidth() + 2; }
    private int trackH() { return capacity() * rowHeight(); }
    private int thumbH() { return Math.max(16, (int)(trackH()
            * Math.min(1.0, capacity() / (double)Math.max(1, count())))); }
    private int thumbY() {
        return firstY() + (int)((trackH() - thumbH())
                * offset / (double)Math.max(1, maxOffset()));
    }
    private String requestQuery() {
        String result = query.trim();
        if (!namespace.isBlank()) result = "@" + namespace.trim().toLowerCase(Locale.ROOT) + " " + result;
        return result.trim();
    }
    private String requestKind() {
        String searchKind = kind == Kind.RECIPE && browsingRecipeMods
                ? "recipe_mod" : kind.name().toLowerCase(Locale.ROOT);
        return searchKind + "/" + matchField.name().toLowerCase(Locale.ROOT);
    }
    private boolean mobMatches(Entry entry) {
        if (kind != Kind.ENTITY || mobFilter == MobFilter.ALL) return true;
        ResourceLocation key = ResourceLocation.tryParse(entry.id());
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        MobCategory category = BuiltInRegistries.ENTITY_TYPE.get(key).getCategory();
        return switch (mobFilter) {
            case HOSTILE -> category == MobCategory.MONSTER;
            case PASSIVE -> category == MobCategory.CREATURE || category == MobCategory.AMBIENT;
            case AQUATIC -> category == MobCategory.WATER_CREATURE
                    || category == MobCategory.WATER_AMBIENT
                    || category == MobCategory.UNDERGROUND_WATER_CREATURE
                    || category == MobCategory.AXOLOTLS;
            case OTHER -> category == MobCategory.MISC;
            default -> true;
        };
    }
    private boolean matches(Entry entry, MilestonePickerSearch.Query parsed) {
        // The selected-only list is a dedicated review of ALL selected rules,
        // regardless of the current search/mod filter.
        if (selectedOnly) return selected.contains(entry.id());
        if (!mobMatches(entry)) return false;
        return parsed.matches(entry.id(), entry.name(), matchField);
    }
    private void filterLocal() {
        if (remote() && !selectedOnly) return;
        List<Entry> source = new ArrayList<>();
        if (selectedOnly) {
            for (String id : selected) source.add(new Entry(id, names.getOrDefault(id, prettify(id))));
        } else if (showingCollections) {
            for (var group : collections.entrySet())
                source.add(new Entry(group.getKey(), "Collection of " + group.getValue().size() + " IDs"));
        } else source.addAll(registryEntries);

        var parsed = MilestonePickerSearch.parse(requestQuery());
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : source)
            if (matches(entry, parsed)) matches.add(entry);
        Comparator<Entry> alphabetical = Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::id);
        Comparator<Entry> comparator = switch (order) {
            case ALPHABETICAL -> alphabetical;
            case SELECTED_FIRST -> Comparator.comparing((Entry e) -> !selected.contains(e.id()))
                    .thenComparing(alphabetical);
            case RELEVANCE -> Comparator.comparingInt(
                    (Entry e) -> parsed.rank(e.id(), e.name())).thenComparing(alphabetical);
        };
        matches.sort(comparator);
        filtered = List.copyOf(matches);
    }

    private void refreshResults(boolean changedSearch) {
        if (changedSearch) offset = 0;
        if (remote() && !selectedOnly) {
            if (changedSearch) {
                sentQuery = null;
                remoteRows = List.of();
                remoteTotal = 0;
                remoteStatus = "Searching...";
                debounce = 5;
            }
        } else filterLocal();
        updateRows();
    }

    @Override protected void init() {
        rowButtons.clear();
        int l = left(), t = top(), w = pw();
        boolean compact = w < 400;
        addRenderableWidget(Button.builder(Component.literal("< Cancel"),
                b -> onClose()).bounds(l + 12, t + 9, compact ? 60 : 81, 20).build());
        if (multi && kind == Kind.RECIPE) {
            // This is the primary fast path for large modpacks: each mod is
            // selected as one modid:* wildcard, not as thousands of recipe IDs.
            int modeX = l + (compact ? 75 : 99);
            int modeW = compact ? 96 : 126;
            browseModsButton = addRenderableWidget(Button.builder(Component.literal(
                    browsingRecipeMods ? "Browse Recipes" : "Browse Mods"),
                    b -> {
                        browsingRecipeMods = !browsingRecipeMods;
                        selectedOnly = false;
                        offset = 0;
                        refreshResults(true);
                        updateControls();
                    }).bounds(modeX, t + 9, modeW, 20).build());
            int pageX = modeX + modeW + 6;
            int applyX = l + w - (compact ? 87 : 111);
            if (pageX + 72 + 6 < applyX)
                addRenderableWidget(Button.builder(Component.literal("Add page"),
                        b -> selectShown()).bounds(pageX, t + 9, 72, 20).build());
        } else if (multi) {
            addRenderableWidget(Button.builder(Component.literal(compact ? "+ Page" : "Add page"),
                    b -> selectShown()).bounds(l + (compact ? 75 : 99), t + 9,
                    compact ? 60 : 80, 20).build());
        }
        if (!collections.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal(
                    showingCollections ? "View IDs" : "Tags"),
                    b -> {
                        showingCollections = !showingCollections;
                        selectedOnly = false;
                        offset = 0;
                        refreshResults(false);
                        updateControls();
                    }).bounds(l + (compact ? 138 : 185), t + 9,
                    compact ? 40 : 67, 20).build());
        }
        applyButton = addRenderableWidget(Button.builder(Component.literal("Apply"),
                b -> {
                    onApply.accept(List.copyOf(selected));
                    if (minecraft != null) minecraft.setScreen(parent);
                }).bounds(l + w - (compact ? 87 : 111), t + 9,
                        compact ? 75 : 99, 20).build());

        searchBox = new EditBox(font, l + 12, t + 36, w - 24, 20,
                Component.literal("Search by name, id or mod"));
        searchBox.setMaxLength(80);
        searchBox.setValue(query);
        searchBox.setHint(Component.literal("e.g. iron, @mekanism, -dust, id:gear"));
        searchBox.setResponder(value -> {
            if (value.equals(query)) return;
            query = value;
            refreshResults(true);
        });
        addRenderableWidget(searchBox);

        namespaceBox = new EditBox(font, l + 12, t + 65, Math.min(126, w / 4), 19,
                Component.literal("Mod namespace filter"));
        namespaceBox.setMaxLength(36);
        namespaceBox.setValue(namespace);
        namespaceBox.setHint(Component.literal("Mod ID (all)"));
        namespaceBox.setResponder(value -> {
            if (value.equals(namespace)) return;
            namespace = value;
            refreshResults(true);
        });
        addRenderableWidget(namespaceBox);

        int modWidth = Math.min(126, w / 4);
        int next = l + 18 + modWidth;
        int room = l + w - 12 - next;
        int matchWidth = multi ? Math.max(52, Math.min(85, room / 3))
                : Math.max(60, Math.min(85, room / 2));
        int selectedWidth = multi ? Math.max(61, Math.min(94, room / 3)) : 0;
        matchButton = addRenderableWidget(Button.builder(Component.literal("Both"),
                b -> {
                    matchField = switch (matchField) {
                        case BOTH -> MilestonePickerSearch.Field.NAME;
                        case NAME -> MilestonePickerSearch.Field.ID;
                        case ID -> MilestonePickerSearch.Field.BOTH;
                    };
                    refreshResults(true);
                    updateControls();
                }).bounds(next, t + 65, matchWidth, 19).build());
        next += matchWidth + 5;

        if (multi) {
            selectedButton = addRenderableWidget(Button.builder(Component.literal("Selected"),
                    b -> {
                        selectedOnly = !selectedOnly;
                        refreshResults(true);
                        if (remote() && !selectedOnly) debounce = 0;
                        updateControls();
                    }).bounds(next, t + 65, selectedWidth, 19).build());
            next += selectedWidth + 5;
        }

        if (kind == Kind.ENTITY) {
            typeButton = addRenderableWidget(Button.builder(Component.literal("All mobs"),
                    b -> {
                        MobFilter[] values = MobFilter.values();
                        mobFilter = values[(mobFilter.ordinal() + 1) % values.length];
                        refreshResults(true);
                        updateControls();
                    }).bounds(next, t + 65, Math.max(38, l + w - 12 - next), 19).build());
        } else if (!remote()) {
            sortButton = addRenderableWidget(Button.builder(Component.literal("Sort"),
                    b -> {
                        Order[] values = Order.values();
                        order = values[(order.ordinal() + 1) % values.length];
                        refreshResults(false);
                        updateControls();
                    }).bounds(next, t + 65, Math.max(38, l + w - 12 - next), 19).build());
        }

        if (w >= 360)
            addRenderableWidget(Button.builder(Component.literal("Reset filters"),
                    b -> resetFilters()).bounds(l + w - 107, t + 84, 94, 17).build());

        for (int i = 0; i < capacity(); i++) {
            final int index = i;
            int y = firstY() + i * rowHeight();
            Button row = addRenderableWidget(Button.builder(Component.empty(),
                    b -> toggleRow(index)).bounds(l + 36, y, listWidth() - 24, 22).build());
            rowButtons.add(row);
        }
        // The rows were rebuilt only on screen resize/reopen. Search changes update
        // the existing buttons and preserve the search box focus/caret.
        if (!remote() || selectedOnly) filterLocal();
        updateRows();
        updateControls();
        if (remote() && !selectedOnly && sentQuery == null) debounce = 0;
        setInitialFocus(searchBox);
    }

    private void resetFilters() {
        query = "";
        namespace = "";
        showingCollections = false;
        selectedOnly = false;
        matchField = MilestonePickerSearch.Field.BOTH;
        mobFilter = MobFilter.ALL;
        order = Order.RELEVANCE;
        if (searchBox != null) searchBox.setValue("");
        if (namespaceBox != null) namespaceBox.setValue("");
        refreshResults(true);
        updateControls();
    }

    private void updateControls() {
        if (applyButton != null) applyButton.setMessage(Component.literal("Apply (" + selected.size() + ")"));
        if (matchButton != null) matchButton.setMessage(Component.literal(
                switch (matchField) { case BOTH -> "Name + ID"; case NAME -> "Name only"; case ID -> "ID only"; }));
        if (browseModsButton != null) browseModsButton.setMessage(
                Component.literal(browsingRecipeMods ? "Browse Recipes" : "Browse Mods"));
        if (selectedButton != null) selectedButton.setMessage(
                Component.literal(selectedOnly ? "Review (" + selected.size() + ")" : "All results"));
        if (typeButton != null) typeButton.setMessage(Component.literal(
                switch(mobFilter) {
                    case ALL -> "All mobs"; case HOSTILE -> "Hostile";
                    case PASSIVE -> "Passive"; case AQUATIC -> "Aquatic"; case OTHER -> "Other";
                }));
        if (sortButton != null) sortButton.setMessage(Component.literal(
                switch(order) {
                    case RELEVANCE -> "Relevance";
                    case ALPHABETICAL -> "Name A-Z";
                    case SELECTED_FIRST -> "Selected first";
                }));
    }

    private void updateRows() {
        if (!remote() || selectedOnly) filterLocal();
        int total = count();
        offset = Math.min(offset, Math.max(0, total - capacity()));
        List<Entry> source = selectedOnly || !remote() ? filtered : remoteRows;
        if (selectedOnly || !remote()) {
            rows = source.subList(Math.min(offset, source.size()),
                    Math.min(source.size(), offset + capacity()));
        } else rows = source;
        for (int i = 0; i < rowButtons.size(); i++) {
            Button button = rowButtons.get(i);
            boolean present = i < rows.size();
            button.visible = present;
            button.active = present;
            if (!present) continue;
            Entry entry = rows.get(i);
            boolean checked = showingCollections && !selectedOnly
                    ? collections.getOrDefault(entry.id(), List.of()).stream().allMatch(selected::contains)
                    : selected.contains(entry.id());
            boolean includedByMod = kind == Kind.RECIPE && !entry.id().endsWith(":*")
                    && selected.contains(entry.id().substring(0, entry.id().indexOf(':')) + ":*");
            // Individual recipes covered by a wildcard are already selected,
            // and cannot be unchecked without removing the owning mod rule.
            button.active = !includedByMod;
            String label = (checked ? "[x] " : includedByMod ? "[all] " : "[ ] ")
                    + entry.name();
            if (kind == Kind.RECIPE && entry.id().endsWith(":*"))
                label += "  [" + entry.id() + "]";
            else if (kind != Kind.RECIPE || !selectedOnly)
                label += "  " + entry.id();
            button.setMessage(Component.literal(font.plainSubstrByWidth(
                    label, Math.max(30, listWidth() - 35))));
        }
        updateControls();
    }

    private int selectionLimit() {
        return kind == Kind.RECIPE || kind == Kind.DIMENSION ? 80 : 256;
    }

    private void toggleRow(int index) {
        if (index < 0 || index >= rows.size()) return;
        Entry entry = rows.get(index);
        if (kind == Kind.RECIPE && !entry.id().endsWith(":*")
                && selected.contains(entry.id().substring(0, entry.id().indexOf(':')) + ":*")) {
            remoteStatus = "Included through its selected mod. Use Browse Mods to remove that rule.";
            return;
        }
        if (showingCollections && !selectedOnly) {
            List<String> group = collections.getOrDefault(entry.id(), List.of());
            if (group.stream().allMatch(selected::contains)) selected.removeAll(group);
            else for (String id : group) {
                if (selected.size() < selectionLimit()) selected.add(id);
                names.putIfAbsent(id, prettify(id));
            }
        } else {
            if (!multi) selected.clear();
            if (!selected.remove(entry.id())) {
                if (kind == Kind.RECIPE && entry.id().endsWith(":*")) {
                    String prefix = entry.id().substring(0, entry.id().length() - 1);
                    // One wildcard subsumes separately selected recipes from
                    // this namespace. No need for 2,817 individual entries.
                    selected.removeIf(id -> id.startsWith(prefix));
                }
                if (selected.size() >= selectionLimit()) {
                    remoteStatus = "Maximum " + selectionLimit() + " selections";
                    return;
                }
                selected.add(entry.id());
            }
        }
        refreshResults(false);
    }

    private void selectShown() {
        if (!multi) return;
        for (Entry entry : rows) {
            if (showingCollections && !selectedOnly) {
                for (String id : collections.getOrDefault(entry.id(), List.of())) {
                    if (selected.size() >= selectionLimit()) break;
                    selected.add(id);
                    names.putIfAbsent(id, prettify(id));
                }
            } else if (selected.size() < selectionLimit()) {
                if (kind == Kind.RECIPE && entry.id().endsWith(":*")) {
                    String prefix = entry.id().substring(0, entry.id().length() - 1);
                    selected.removeIf(id -> id.startsWith(prefix));
                }
                if (kind != Kind.RECIPE || entry.id().endsWith(":*")
                        || !selected.contains(entry.id().substring(0, entry.id().indexOf(':')) + ":*"))
                    selected.add(entry.id());
            }
        }
        refreshResults(false);
    }

    private void requestServer() {
        if (!remote() || selectedOnly || minecraft == null
                || minecraft.getConnection() == null || minecraft.player == null
                || !minecraft.player.hasPermissions(2)) {
            if (remote() && !selectedOnly) remoteStatus = "Join as an operator to browse server IDs";
            return;
        }
        String request = requestQuery().toLowerCase(Locale.ROOT);
        if (request.length() > 90) {
            remoteStatus = "Search too long (maximum 90 characters)";
            return;
        }
        String type = requestKind();
        if (request.equals(sentQuery) && type.equals(sentKind) && offset == sentOffset) return;
        sentQuery = request;
        sentKind = type;
        sentOffset = offset;
        remoteStatus = "Searching...";
        PacketDistributor.sendToServer(new MilestoneConfigNetwork.IdSearchRequest(
                request, offset, Math.min(30, capacity()), type));
    }

    /** Server replies may arrive out of order. Ignore stale pages and filters. */
    public void receiveServerResults(String request, String type, int page, int total,
                                     List<String> ids, List<String> labels,
                                     List<String> icons) {
        if (!remote() || selectedOnly || !type.equals(requestKind())
                || !request.equals(requestQuery().toLowerCase(Locale.ROOT))
                || page != offset || ids.size() != labels.size()
                || icons.size() != ids.size()) return;
        remoteTotal = total;
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            result.add(new Entry(ids.get(i), labels.get(i)));
            names.put(ids.get(i), labels.get(i));
            if (!icons.get(i).isBlank()) recipeItemIcons.put(ids.get(i), icons.get(i));
        }
        remoteRows = List.copyOf(result);
        remoteStatus = total + (browsingRecipeMods
                ? " mods with recipes (click once to add all)" :
                " matching " + kind.name().toLowerCase(Locale.ROOT) + " IDs");
        updateRows();
    }

    @Override public void tick() {
        super.tick();
        if (remote() && !selectedOnly) {
            if (debounce > 0) debounce--;
            else requestServer();
        }
    }

    private ItemStack icon(Entry entry) {
        if (kind == Kind.RECIPE || kind == Kind.DIMENSION) {
            // A wildcard (create:*) isn't a valid ResourceLocation itself.
            // Its representative output item comes from the server catalog.
            String itemId = recipeItemIcons.get(entry.id());
            ResourceLocation output = itemId == null ? null : ResourceLocation.tryParse(itemId);
            if (output != null && BuiltInRegistries.ITEM.containsKey(output))
                return new ItemStack(BuiltInRegistries.ITEM.get(output));
            if (kind == Kind.DIMENSION)
                return new ItemStack(entry.id().equals("minecraft:the_nether")
                        ? Items.OBSIDIAN : entry.id().equals("minecraft:the_end")
                        ? Items.END_STONE : Items.GRASS_BLOCK);
            return new ItemStack(Items.KNOWLEDGE_BOOK);
        }
        if (kind == Kind.ADVANCEMENT) return new ItemStack(Items.BOOK);
        ResourceLocation id = ResourceLocation.tryParse(entry.id());
        if (id == null) return ItemStack.EMPTY;
        if (kind == Kind.ITEM && BuiltInRegistries.ITEM.containsKey(id))
            return new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (kind == Kind.BLOCK && BuiltInRegistries.BLOCK.containsKey(id)) {
            var item = BuiltInRegistries.BLOCK.get(id).asItem();
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
        if (kind == Kind.ENTITY && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            SpawnEggItem egg = SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.get(id));
            return egg == null ? ItemStack.EMPTY : new ItemStack(egg);
        }
        return ItemStack.EMPTY;
    }

    private void preview(Entry entry) {
        if (entry.id().equals(previewId)) return;
        previewId = entry.id();
        previewLiving = null;
        previewFailed = false;
        if (minecraft == null || minecraft.level == null) return;
        ResourceLocation id = ResourceLocation.tryParse(entry.id());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return;
        try {
            var candidate = BuiltInRegistries.ENTITY_TYPE.get(id).create(minecraft.level);
            if (candidate instanceof LivingEntity mob) previewLiving = mob;
        } catch (RuntimeException | LinkageError error) {
            previewFailed = true;
        }
    }

    private void drawEntityPreview(GuiGraphics g, int mx, int my) {
        if (!previewPane()) return;
        int x = left() + pw() - 137, y = firstY();
        g.fill(x, y, x + 126, y + 166, 0xFF252525);
        g.drawCenteredString(font, "ENTITY PREVIEW", x + 63, y + 7, 0xFFDDDDDD);
        Entry focus = null;
        if (mx >= left() + 10 && mx <= trackX()
                && my >= firstY() && my < firstY() + rows.size() * rowHeight()) {
            focus = rows.get((my - firstY()) / rowHeight());
            highlightedId = focus.id();
        } else if (highlightedId != null) {
            for (Entry entry : rows)
                if (entry.id().equals(highlightedId)) { focus = entry; break; }
        }
        if (focus == null && !rows.isEmpty()) focus = rows.get(0);
        if (focus == null) return;
        preview(focus);
        String name = focus.name();
        g.drawCenteredString(font, font.plainSubstrByWidth(name, 120),
                x + 63, y + 147, 0xFFE6E6E6);
        if (previewLiving == null) {
            g.drawCenteredString(font, previewFailed ? "Preview unavailable" : "No living model",
                    x + 63, y + 78, 0xFFB8B8B8);
            return;
        }
        try {
            float height = Math.max(previewLiving.getBbHeight(), previewLiving.getBbWidth());
            int size = Math.max(9, Math.min(42, (int)(70.0f / Math.max(0.6f, height))));
            InventoryScreen.renderEntityInInventoryFollowsAngle(
                    g, x + 3, y + 22, x + 123, y + 139, size, 0.12f, 0.04f, 0.0f, previewLiving);
        } catch (RuntimeException | LinkageError error) {
            previewFailed = true;
            previewLiving = null;
        }
    }

    private void drawIcons(GuiGraphics g) {
        if (showingCollections && !selectedOnly) return;
        g.enableScissor(left() + 12, firstY(), trackX(), firstY() + trackH());
        for (int i = 0; i < rows.size(); i++) {
            Entry entry = rows.get(i);
            int x = left() + 17, y = firstY() + i * rowHeight() + 3;
            ItemStack item = icon(entry);
            if (!item.isEmpty()) g.renderItem(item, x, y);
            else if (kind == Kind.ENTITY) g.drawString(font, "?", x + 5, y + 5, 0xFFFFD078);
        }
        g.disableScissor();
    }

    private void setOffset(int value) {
        int next = Math.max(0, Math.min(maxOffset(), value));
        if (next == offset) return;
        offset = next;
        if (remote() && !selectedOnly) {
            remoteRows = List.of();
            remoteStatus = "Loading page...";
            debounce = 0;
            requestServer();
        }
        updateRows();
    }

    private void scrollTo(double y) {
        int travel = trackH() - thumbH();
        if (travel <= 0) return;
        setOffset((int)Math.round((y - firstY() - thumbH() / 2.0)
                * maxOffset() / travel));
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && maxOffset() > 0
                && x >= trackX() - 2 && x <= trackX() + 11
                && y >= firstY() && y <= firstY() + trackH()) {
            dragging = true;
            scrollTo(y);
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { scrollTo(y); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging) { dragging = false; return true; }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (maxOffset() > 0) {
            setOffset(offset - (int)Math.signum(dy) * Math.max(1, (int)Math.abs(dy) * 2));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        // Ctrl+F focuses the search box from anywhere in the picker.
        if (key == 70 && (mods & 2) != 0 && searchBox != null) {
            setFocused(searchBox);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        int l = left(), t = top(), w = pw(), h = ph();
        g.fill(0, 0, width, height, 0xFF171717);
        g.fill(l, t, l + w, t + h, 0xFF343434);
        g.fill(l, t, l + w, t + 1, 0xFF999999);
        for (var child : children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.visible)
                widget.render(g, mx, my, partialTick);
        g.drawString(font, "MOD", l + 13, t + 59, 0xFFBBBBBB);
        g.drawString(font, (browsingRecipeMods && !selectedOnly ? "Mods: " : "Results: ")
                + count() + "  |  Selected rules: " + selected.size(),
                l + 12, t + 87, 0xFFDDDDDD);
        drawIcons(g);
        drawEntityPreview(g, mx, my);
        if (rows.isEmpty()) {
            g.drawCenteredString(font,
                    remote() && !selectedOnly && remoteStatus.equals("Searching...")
                            ? "Searching server..."
                            : "No matching IDs. Try clearing a filter.",
                    l + 12 + listWidth() / 2, firstY() + 20, 0xFFCCCCCC);
        }
        if (maxOffset() > 0) {
            int x = trackX();
            g.fill(x, firstY(), x + 9, firstY() + trackH(), 0xFF171717);
            g.fill(x + 1, thumbY(), x + 8, thumbY() + thumbH(), 0xFFC8C8C8);
        }
        String hint = remote() && !selectedOnly ? remoteStatus
                : kind == Kind.RECIPE && selectedOnly
                    ? "Click a selected rule to remove it. Mod rules include future recipes."
                    : "Search: words, @modid, -exclude, id:term or name:term";
        g.drawCenteredString(font, font.plainSubstrByWidth(hint, w - 30),
                width / 2, t + h - 20, 0xFFBBBBBB);
        if (!rows.isEmpty()) {
            int index = (my - firstY()) / rowHeight();
            if (my >= firstY() && index >= 0 && index < rows.size()
                    && mx >= l + 34 && mx <= trackX()) {
                Entry entry = rows.get(index);
                String tooltip = entry.name() + "  (" + entry.id() + ")";
                if (kind == Kind.RECIPE && entry.id().endsWith(":*"))
                    tooltip = "ALL current and future " + entry.id()
                            .substring(0, entry.id().length() - 2) + " recipes";
                g.renderTooltip(font, Component.literal(tooltip)
                        .withStyle(ChatFormatting.GRAY), mx, my);
            }
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
