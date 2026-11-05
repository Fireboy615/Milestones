package net.fireboy.milestone.loot;

import com.google.gson.JsonObject;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One-shot in-world container inspector. Arm from the OP Config GUI,
 * then right-click a nearby container to view its native loot origin.
 * The action is never persisted and times out after 60 seconds. */
public final class MilestoneLootTargeting {
    private MilestoneLootTargeting(){}
    private static final Map<UUID,Long> ARMED=new HashMap<>();

    public static void arm(ServerPlayer player){
        if(!player.hasPermissions(2))return;
        ARMED.put(player.getUUID(),player.serverLevel().getGameTime()+1200L);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Loot Inspector armed: right-click a container within 6 blocks (60 seconds)."));
    }
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        Long deadline=ARMED.get(player.getUUID());
        if(deadline==null)return;
        ARMED.remove(player.getUUID());
        if(!player.hasPermissions(2)||player.serverLevel().getGameTime()>deadline)return;
        var pos=event.getPos();
        if(!player.serverLevel().hasChunkAt(pos) || player.distanceToSqr(pos.getCenter())>49)
            return;
        JsonObject result=MilestoneLootTools.inspectAt(player,pos);
        result.addProperty("action","inspect");
        PacketDistributor.sendToPlayer(player,
                new MilestoneLootNetwork.OpenLootInspector(result.toString()));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
