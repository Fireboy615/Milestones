package net.fireboy.milestone.client.screen;

import com.google.gson.JsonObject;
import net.fireboy.milestone.milestone.MilestonePortalAccess;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

/**
 * Generic portal reward: destination, portal block/type, access tier.
 * Existing Nether mechanics are active; unknown portal types record a
 * progression flag for adapters supplied by their mods in the future.
 */
public final class MilestonePortalRewardScreen extends Screen {
    private final Screen parent;
    private final Consumer<JsonObject> apply;
    private String dimension;
    private String portalType;
    private MilestonePortalAccess access;
    private EditBox portalBox;
    private String notice = "";

    public MilestonePortalRewardScreen(Screen parent, JsonObject initial,
                                       Consumer<JsonObject> apply) {
        super(Component.literal("Portal Unlock Reward"));
        this.parent = parent;
        this.apply = apply;
        dimension = initial == null ? "minecraft:the_nether"
                : initial.get("dimension").getAsString();
        portalType = initial == null ? "minecraft:nether_portal"
                : initial.get("portal_type").getAsString();
        access = initial == null ? MilestonePortalAccess.SHARED_GATEWAY
                : MilestonePortalAccess.valueOf(initial.get("access").getAsString());
    }

    private int pw() { return Math.min(440, width - 20); }
    private int ph() { return Math.min(326, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int bottom() { return top() + ph(); }
    private boolean canEdit() {
        return minecraft != null && (minecraft.getConnection() == null
                || minecraft.player != null && minecraft.player.hasPermissions(2));
    }

    @Override protected void init() {
        int l = left(), t = top(), w = pw();
        addRenderableWidget(Button.builder(Component.literal("< Rewards"),
                b -> onClose()).bounds(l + 15, t + 12, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal(
                font.plainSubstrByWidth("Destination: " + dimension, w - 52)),
                b -> pickDimension()).bounds(l + 23, t + 78, w - 46, 23).build())
                .active = canEdit();

        portalBox = new EditBox(font, l + 23, t + 146, w - 161, 21,
                Component.literal("Portal block / type ID"));
        portalBox.setMaxLength(125);
        portalBox.setValue(portalType);
        portalBox.setEditable(canEdit());
        portalBox.setResponder(value -> portalType = value);
        addRenderableWidget(portalBox);
        addRenderableWidget(Button.builder(Component.literal("Browse blocks"),
                b -> pickBlock()).bounds(l + w - 131, t + 146, 108, 21).build())
                .active = canEdit();
        addRenderableWidget(Button.builder(Component.literal(
                "Access: " + access.label()),
                b -> {
                    MilestonePortalAccess[] options = MilestonePortalAccess.values();
                    access = options[(access.ordinal() + 1) % options.length];
                    rebuild();
                }).bounds(l + 23, t + 203, w - 46, 23).build())
                .active = canEdit();
        addRenderableWidget(Button.builder(Component.literal("Add Portal Reward"),
                b -> save()).bounds(l + (w - 173) / 2, bottom() - 40, 173, 22).build())
                .active = canEdit();
    }

    private void rebuild() { clearWidgets(); init(); }

    private void pickDimension() {
        if (minecraft == null) return;
        if (portalBox != null) portalType = portalBox.getValue().trim();
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                MilestoneIdPickerScreen.Kind.DIMENSION, false,
                List.of(dimension), List.of(), ids -> {
                    if (!ids.isEmpty()) dimension = ids.get(0);
                }));
    }

    private void pickBlock() {
        if (minecraft == null) return;
        if (portalBox != null) portalType = portalBox.getValue().trim();
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                MilestoneIdPickerScreen.Kind.BLOCK, false,
                List.of(portalType), List.of(), ids -> {
                    if (!ids.isEmpty()) portalType = ids.get(0);
                }));
    }

    private void save() {
        if (!canEdit() || portalBox == null) return;
        portalType = portalBox.getValue().trim();
        ResourceLocation destinationId = ResourceLocation.tryParse(dimension);
        ResourceLocation portalId = ResourceLocation.tryParse(portalType);
        if (destinationId == null || portalId == null
                || !destinationId.toString().equals(dimension)
                || !portalId.toString().equals(portalType)) {
            notice = "Select valid namespaced dimension and portal IDs.";
            return;
        }
        JsonObject reward = new JsonObject();
        reward.addProperty("type", "portal");
        reward.addProperty("dimension", dimension);
        reward.addProperty("portal_type", portalType);
        reward.addProperty("access", access.name());
        apply.accept(reward);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        int l = left(), t = top(), w = pw();
        g.fill(0, 0, width, height, 0xFF171717);
        g.fill(l, t, l + w, t + ph(), 0xFF333333);
        g.drawCenteredString(font, "Portal Progression Reward", width / 2, t + 19, 0xFFF5F5F5);
        g.renderItem(new ItemStack(dimension.equals("minecraft:the_nether")
                ? Items.OBSIDIAN : dimension.equals("minecraft:the_end")
                ? Items.END_STONE : Items.ENDER_EYE), l + 23, t + 51);
        g.drawString(font, "Target dimension (from server registry)",
                l + 44, t + 55, 0xFFCCCCCC);
        g.drawString(font, "Portal block/type identifier", l + 23, t + 128, 0xFFCCCCCC);
        g.drawString(font, "Portal permission to unlock", l + 23, t + 184, 0xFFCCCCCC);
        g.drawCenteredString(font, font.plainSubstrByWidth(
                dimension.equals("minecraft:the_nether")
                && portalType.equals("minecraft:nether_portal")
                        ? "Vanilla Nether portals: access is enforced now."
                        : "Modded portals: saves flag for a future compatibility adapter.",
                w - 40), width / 2, bottom() - 74, 0xFFFFD27C);
        if (!notice.isBlank())
            g.drawCenteredString(font, font.plainSubstrByWidth(notice, w - 40),
                    width / 2, bottom() - 57, 0xFFFFBBBB);
        for (var control : children())
            if (control instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible)
                widget.render(g, mx, my, partialTick);
    }
    @Override public boolean isPauseScreen() { return false; }
}
