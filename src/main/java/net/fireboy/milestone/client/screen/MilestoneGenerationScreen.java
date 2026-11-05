package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Server-authoritative world generation controls. The world is never modified
 * by the GUI itself; requests are permission-checked and queued server-side.
 */
public final class MilestoneGenerationScreen extends Screen {
    private record CoreRow(int tier, String name, String dimension, String status) {}

    private final MilestoneConfigScreen parent;
    private final List<CoreRow> cores = new ArrayList<>();
    private String shop = "Checking";
    private String station = "Checking";
    private String notice = "Checking generated structures...";
    private boolean received;
    private int first;

    public MilestoneGenerationScreen(MilestoneConfigScreen parent) {
        super(Component.literal("Milestone World Generation"));
        this.parent = parent;
    }

    private int pw() { return Math.min(490, width - 18); }
    private int ph() { return Math.min(416, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int bottom() { return top() + ph(); }
    private int capacity() { return Math.max(1, (ph() - 215) / 26); }
    private boolean canGenerate() {
        return minecraft != null && minecraft.getConnection() != null
                && minecraft.player != null && minecraft.player.hasPermissions(2)
                && !parent.hasPendingChanges();
    }

    private void request(String action, int tier) {
        if (!canGenerate()) {
            notice = "Save config first. Operator access is required.";
            return;
        }
        notice = action.equals("status") ? "Refreshing status..." : "Request sent to server...";
        PacketDistributor.sendToServer(new MilestoneConfigNetwork.GenerationRequest(action, tier));
    }

    @Override protected void init() {
        int l = left(), t = top(), w = pw();
        addRenderableWidget(Button.builder(Component.literal("< Config"),
                b -> onClose()).bounds(l + 13, t + 10, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                b -> request("status", -1)).bounds(l + w - 95, t + 10, 82, 20).build())
                .active = canGenerate();

        int each = (w - 56) / 3;
        addRenderableWidget(Button.builder(Component.literal("Generate All Cores"),
                b -> request("all", -1)).bounds(l + 15, t + 50, each, 22).build())
                .active = canGenerate() && cores.stream().anyMatch(c -> c.status().equals("Reserved"));
        addRenderableWidget(Button.builder(Component.literal("Generate Shop"),
                b -> request("shop", -1)).bounds(l + 22 + each, t + 50, each, 22).build())
                .active = canGenerate() && !shop.equals("Placed") && !shop.equals("Queued");
        addRenderableWidget(Button.builder(Component.literal("Generate Station"),
                b -> request("station", -1)).bounds(l + 29 + 2 * each, t + 50, each, 22).build())
                .active = canGenerate() && !station.equals("Placed") && !station.equals("Queued");

        int visible = capacity();
        first = Math.max(0, Math.min(first, Math.max(0, cores.size() - visible)));
        int listY = t + 140;
        for (int i = first; i < Math.min(cores.size(), first + visible); i++) {
            CoreRow row = cores.get(i);
            int y = listY + (i - first) * 26;
            boolean valid = row.status().equals("Reserved");
            addRenderableWidget(Button.builder(Component.literal(
                    valid ? "Generate" : row.status()),
                    b -> request("core", row.tier())).bounds(l + w - 105, y, 91, 21).build())
                    .active = canGenerate() && valid;
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),
                b -> { first = Math.max(0, first - visible); rebuild(); })
                .bounds(l + 15, bottom() - 91, 95, 20).build()).active = first > 0;
        addRenderableWidget(Button.builder(Component.literal("Next"),
                b -> { first += visible; rebuild(); })
                .bounds(l + w - 110, bottom() - 91, 95, 20).build())
                .active = first + visible < cores.size();

        if (!received && canGenerate())
            request("status", -1);
    }

    private void rebuild() { clearWidgets(); init(); }

    /** Server response; includes queue and already-placed world-save state. */
    public void receive(String json) {
        try {
            JsonObject status = JsonParser.parseString(json).getAsJsonObject();
            shop = status.get("shop").getAsString();
            station = status.get("station").getAsString();
            notice = status.get("message").getAsString();
            cores.clear();
            JsonArray entries = status.getAsJsonArray("cores");
            for (JsonElement entry : entries) {
                JsonObject row = entry.getAsJsonObject();
                cores.add(new CoreRow(row.get("tier").getAsInt(),
                        row.get("name").getAsString(), row.get("dimension").getAsString(),
                        row.get("state").getAsString()));
            }
            cores.sort(Comparator.comparingInt(CoreRow::tier));
            received = true;
            rebuild();
        } catch (RuntimeException error) {
            notice = "Could not read server world-generation status.";
        }
    }

    @Override public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        super.render(g, mx, my, tick);
        int l = left(), t = top(), w = pw();
        g.fill(0, 0, width, height, 0xFF171717);
        g.fill(l, t, l + w, bottom(), 0xFF333333);
        g.drawCenteredString(font, "World Generation", width / 2, t + 16, 0xFFFFFFFF);
        g.drawString(font, "Shop: " + shop, l + 17, t + 83, 0xFFCCCCCC);
        g.drawString(font, "Mission Station: " + station, l + 17, t + 98, 0xFFCCCCCC);
        g.drawString(font, "Cores  (" + cores.size() + " configured)", l + 17, t + 120, 0xFFE6E6E6);

        for (int i = first; i < Math.min(cores.size(), first + capacity()); i++) {
            CoreRow row = cores.get(i);
            int y = t + 140 + (i - first) * 26;
            int color = row.status().equals("Placed") ? 0xFF9EDEAB
                    : row.status().equals("Queued") ? 0xFFFFD27C : 0xFFDDDDDD;
            g.drawString(font, font.plainSubstrByWidth(
                    "Core " + (row.tier() + 1) + "  " + row.name(), w - 143),
                    l + 18, y + 6, color);
            if (mx >= l + 15 && mx < l + w - 111 && my >= y && my < y + 23)
                g.renderTooltip(font, Component.literal(
                        row.name() + "  [" + row.dimension() + "]  " + row.status()), mx, my);
        }

        g.drawCenteredString(font, font.plainSubstrByWidth(notice, w - 34),
                width / 2, bottom() - 63, 0xFFFFD27C);
        g.drawCenteredString(font, font.plainSubstrByWidth(
                parent.hasPendingChanges()
                        ? "Save pending configuration edits before generating."
                        : "Generated sites are permanent. No duplicates or forced regeneration.",
                w - 34), width / 2, bottom() - 43, 0xFFBBBBBB);
        g.drawCenteredString(font, "Generation uses safe terrain checks; distant chunks load gradually.",
                width / 2, bottom() - 27, 0xFFBBBBBB);
        for (var child : children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible)
                widget.render(g, mx, my, tick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
