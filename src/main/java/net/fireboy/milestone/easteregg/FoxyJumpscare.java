package net.fireboy.milestone.easteregg;

import net.fireboy.milestone.network.FoxyNetwork;
import net.fireboy.milestone.world.FoxySavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One server-wide roll per real second, never per-player. Disabled by default.
 * A single win sends one packet to every player connected at that instant.
 */
public final class FoxyJumpscare {
    private static final int CHANCE_PER_SECOND = 10_000;
    private static final long SECOND_NS = 1_000_000_000L;
    private static final SavedData.Factory<FoxySavedData> FACTORY =
            new SavedData.Factory<>(FoxySavedData::create, FoxySavedData::load);
    private static final Map<MinecraftServer, Long> lastRoll = new WeakHashMap<>();

    private FoxyJumpscare() { }

    private static FoxySavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(FACTORY, FoxySavedData.NAME);
    }

    public static boolean enabled(MinecraftServer server) {
        return data(server).enabled();
    }

    public static void setEnabled(MinecraftServer server, boolean enabled) {
        data(server).setEnabled(enabled);
        // Arm fresh when enabled; disabling also resets the previous clock.
        lastRoll.remove(server);
    }

    /** @return number of players receiving the synchronized event. */
    public static int trigger(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        var packet = new FoxyNetwork.Jumpscare();
        for (var player : players) PacketDistributor.sendToPlayer(player, packet);
        return players.size();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!enabled(server)) {
            lastRoll.remove(server);
            return;
        }

        long now = System.nanoTime();
        Long previous = lastRoll.get(server);
        if (previous == null) {
            lastRoll.put(server, now);
            return;
        }
        long elapsed = now - previous;
        if (elapsed < SECOND_NS) return;

        // Real-time intervals rather than 20 ticks, which slows down when a
        // busy modded server falls below 20 TPS. Cap catch-up work after stalls.
        int seconds = (int) Math.min(60L, elapsed / SECOND_NS);
        lastRoll.put(server, now - (elapsed % SECOND_NS));
        for (int i = 0; i < seconds; i++) {
            if (ThreadLocalRandom.current().nextInt(CHANCE_PER_SECOND) == 0) {
                // Roll regardless of player count. With nobody online the
                // result is simply discarded instead of queued for later.
                trigger(server);
                break;
            }
        }
    }
}
