package net.fireboy.milestone.client;

import com.simibubi.create.foundation.utility.CreatePaths;
import net.fireboy.milestone.network.*;
import net.fireboy.milestone.network.MilestoneConfigNetwork.*;
import net.fireboy.milestone.network.RecipeLockNetwork;
import net.fireboy.milestone.network.FoxyNetwork;
import net.fireboy.milestone.network.MilestoneLootNetwork.*;
import net.fireboy.milestone.network.MilestoneShopNetwork.*;
import net.fireboy.milestone.network.CreateSchematicNetwork.*;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs only on the physical client. Keeps GUI and Create client paths out of
 * common packet registration so dedicated server initialization is safe. */
public final class MilestoneClientPackets {
    private static final Logger LOGGER=LoggerFactory.getLogger(MilestoneClientPackets.class);
    private MilestoneClientPackets(){}
    public static void handle(CustomPacketPayload packet, IPayloadContext context) {
        if (packet instanceof FoxyNetwork.Jumpscare) {
            net.minecraft.client.Minecraft.getInstance().execute(
                    net.fireboy.milestone.client.FoxyJumpscareOverlay::start);
            return;
        }
        if (packet instanceof RecipeLockNetwork.Sync locked) {
            RecipeLockClientState.install(locked.json());
            return;
        }
        if (packet instanceof ConfigSnapshot typed1149) { snapshot(typed1149,context); return; }
        if (packet instanceof ConfigResult typed1246) { result(typed1246,context); return; }
        if (packet instanceof IdSearchResults typed1339) { idSearchResults(typed1339,context); return; }
        if (packet instanceof GenerationStatus generation) { generationStatus(generation, context); return; }
        if (packet instanceof Response typed1444) { receive(typed1444,context); return; }
        if (packet instanceof OpenLootInspector typed1534) { openInspector(typed1534,context); return; }
        if (packet instanceof OpenShop typed1639) { open(typed1639,context); return; }
        if (packet instanceof ShopView typed1726) { view(typed1726,context); return; }
        if (packet instanceof ShopIntelView typed1813) { intelView(typed1813,context); return; }
        if (packet instanceof BlueprintSync typed1910) { handleSync(typed1910,context); return; }
    }

    private static void snapshot(ConfigSnapshot payload, IPayloadContext context) {
        if (context.player().level().isClientSide()) {
            MilestoneContentConfig.installClient(payload.json());
            // If the Mods menu opened the editor, update the initial field from
            // the server's authoritative config rather than stale client defaults.
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof net.fireboy.milestone.client.screen.MilestoneConfigScreen config)
                config.refreshFromServer(payload.json());
        }
    }

    private static void result(ConfigResult payload, IPayloadContext context) {
        if (!context.player().level().isClientSide()) return;
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof net.fireboy.milestone.client.screen.MilestoneConfigScreen config)
            config.onSaveResult(payload.success(), payload.message());
    }

    private static void generationStatus(GenerationStatus result, IPayloadContext context) {
        if (!context.player().level().isClientSide()) return;
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof net.fireboy.milestone.client.screen.MilestoneGenerationScreen generation)
            generation.receive(result.json());
    }

    private static void idSearchResults(IdSearchResults result, IPayloadContext context) {
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneIdPickerScreen picker)
            picker.receiveServerResults(result.query(),result.kind(),result.offset(),
                    result.total(),result.ids(),result.names(),result.icons());
    }

    private static void receive(Response packet,IPayloadContext context) {
        if(!context.player().level().isClientSide())return;
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootScreen screen)
            screen.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootPoolScreen pool)
            pool.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootAddEntryScreen add)
            add.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootEditEntryScreen edit)
            edit.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootEnchantScreen enchants)
            enchants.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootEnchantRulesScreen rules)
            rules.receive(packet.json());
        else if(current instanceof net.fireboy.milestone.client.screen.MilestoneLootEnchantRuleEditScreen editor)
            editor.receive(packet.json());
    }

    private static void openInspector(OpenLootInspector packet,IPayloadContext context){
        if(!context.player().level().isClientSide())return;
        var client=net.minecraft.client.Minecraft.getInstance();
        var screen=new net.fireboy.milestone.client.screen.MilestoneLootScreen(null);
        client.setScreen(screen);
        screen.receive(packet.json());
    }

    private static void open(OpenShop packet,IPayloadContext context) {
        if (!context.player().level().isClientSide()) return;
        var client=net.minecraft.client.Minecraft.getInstance();
        // The server sends this only after a valid Resonant Exchange interaction.
        client.setScreen(new net.fireboy.milestone.client.screen.MilestoneShopScreen(packet.pos()));
    }

    private static void view(ShopView packet,IPayloadContext context) {
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneShopScreen screen)
            screen.onShopView(packet.catalogJson(),packet.message());
    }

    private static void intelView(ShopIntelView packet,IPayloadContext context) {
        var current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current instanceof net.fireboy.milestone.client.screen.MilestoneShopIntelScreen screen)
            screen.onIntelView(packet.json(),packet.message());
    }

    private static void handleSync(BlueprintSync payload, IPayloadContext context) {
        // NeoForge's main-thread registrar executes clientbound play handlers
        // on the client thread by default.
        if (!context.player().level().isClientSide()
                || !CreateSchematicNetwork.valid(payload.filename(), payload.compressedNbt())) return;
        try {
            Path directory = CreatePaths.SCHEMATICS_DIR.toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path file = directory.resolve(payload.filename()).normalize();
            if (!file.getParent().equals(directory)) return;
            Files.write(file, payload.compressedNbt());
        } catch (IOException exception) {
            LOGGER.error("Failed to install Create schematic {}", payload.filename(), exception);
        }
    }
}
