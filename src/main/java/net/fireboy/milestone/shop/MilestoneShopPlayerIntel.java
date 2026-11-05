package net.fireboy.milestone.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Paid, approximate Player Intel. Exact last-seen positions live only in server
 * SavedData; the network sends names, online status and paid fuzzy reports.
 *
 * Each target has ONE persistent approximate coordinate, shared by all buyers.
 * It is reused while the target is within 100 horizontal blocks; buying again
 * never samples fresh points around a stationary player for triangulation.
 */
public final class MilestoneShopPlayerIntel extends SavedData {
    private static final SavedData.Factory<MilestoneShopPlayerIntel> FACTORY =
            new SavedData.Factory<>(MilestoneShopPlayerIntel::new, MilestoneShopPlayerIntel::load);
    private static final String FILE = "milestone_shop_player_intel";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<UUID,Seen> known=new HashMap<>();

    private record Seen(String name,String dimension,int x,int y,int z,long seenMs,
                        String approximateDimension,int approximateX,int approximateZ,
                        boolean hasApproximate) {}
    public record Report(String message,int price,int balance,boolean success) {}

    private static MilestoneShopPlayerIntel get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,FILE);
    }
    private static MilestoneShopPlayerIntel load(CompoundTag data,HolderLookup.Provider provider) {
        MilestoneShopPlayerIntel saved=new MilestoneShopPlayerIntel();
        for(Tag raw:data.getList("Players",Tag.TAG_COMPOUND)) {
            CompoundTag row=(CompoundTag)raw;
            try {
                UUID id=row.getUUID("UUID");
                String name=row.getString("Name"),dimension=row.getString("Dimension");
                if(name.isBlank() || dimension.isBlank())continue;
                saved.known.put(id,new Seen(name,dimension,
                        row.getInt("X"),row.getInt("Y"),row.getInt("Z"),
                        Math.max(0L,row.getLong("Seen")),
                        row.getString("ApproximateDimension"),
                        row.getInt("ApproximateX"),row.getInt("ApproximateZ"),
                        row.getBoolean("HasApproximate")));
            }catch(RuntimeException ignored){}
        }
        return saved;
    }
    @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider provider) {
        ListTag rows=new ListTag();
        for(var entry:known.entrySet()) {
            Seen seen=entry.getValue();
            CompoundTag row=new CompoundTag();
            row.putUUID("UUID",entry.getKey());
            row.putString("Name",seen.name());
            row.putString("Dimension",seen.dimension());
            row.putInt("X",seen.x());row.putInt("Y",seen.y());row.putInt("Z",seen.z());
            row.putLong("Seen",seen.seenMs());
            row.putBoolean("HasApproximate",seen.hasApproximate());
            row.putString("ApproximateDimension",seen.approximateDimension());
            row.putInt("ApproximateX",seen.approximateX());
            row.putInt("ApproximateZ",seen.approximateZ());
            rows.add(row);
        }
        root.put("Players",rows);
        return root;
    }
    private void observe(ServerPlayer player) {
        UUID id=player.getUUID();
        BlockPos at=player.blockPosition();
        String name=player.getGameProfile().getName();
        String dim=player.serverLevel().dimension().location().toString();
        Seen previous=known.get(id);
        Seen next=new Seen(name,dim,at.getX(),at.getY(),at.getZ(),
                System.currentTimeMillis(),
                previous==null?"":previous.approximateDimension(),
                previous==null?0:previous.approximateX(),
                previous==null?0:previous.approximateZ(),
                previous!=null&&previous.hasApproximate());
        known.put(id,next);
        setDirty();
    }
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if(event.getEntity() instanceof ServerPlayer player&&player.tickCount%200==0)
            get(player.getServer()).observe(player);
    }
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player)
            get(player.getServer()).observe(player);
    }

    /** Only UUIDs are client-selectable. Actual positions are never included. */
    public static JsonObject listing(ServerPlayer buyer,int price) {
        MilestoneShopPlayerIntel saved=get(buyer.getServer());
        for(ServerPlayer online:buyer.getServer().getPlayerList().getPlayers())
            if(!saved.known.containsKey(online.getUUID()))saved.observe(online);
        JsonObject response=new JsonObject();
        response.addProperty("price",price);
        response.addProperty("balance",MilestoneShopAccounts.get(buyer.getServer())
                .balance(buyer.getUUID()));
        JsonArray rows=new JsonArray();
        for(var entry:saved.known.entrySet()) {
            Seen seen=entry.getValue();
            JsonObject row=new JsonObject();
            row.addProperty("id",entry.getKey().toString());
            row.addProperty("name",seen.name());
            row.addProperty("online",buyer.getServer().getPlayerList()
                    .getPlayer(entry.getKey())!=null);
            rows.add(row);
        }
        response.add("players",rows);
        return response;
    }

    private Seen withApproximation(UUID id,Seen seen) {
        if(seen.hasApproximate() && seen.dimension().equals(seen.approximateDimension())) {
            long dx=(long)seen.approximateX()-seen.x();
            long dz=(long)seen.approximateZ()-seen.z();
            // Reuse the last paid estimate for as long as it's valid, even
            // across logout, restarts and purchases by different buyers.
            if(dx*dx+dz*dz<=10000L)return seen;
        }
        double angle=RANDOM.nextDouble()*Math.PI*2.0;
        double radius=55.0+RANDOM.nextDouble()*35.0;
        int dx=(int)Math.round(Math.cos(angle)*radius);
        int dz=(int)Math.round(Math.sin(angle)*radius);
        Seen with=new Seen(seen.name(),seen.dimension(),seen.x(),seen.y(),seen.z(),
                seen.seenMs(),seen.dimension(),
                seen.x()+dx,seen.z()+dz,true);
        known.put(id,with);
        setDirty();
        return with;
    }

    public static Report purchase(ServerPlayer buyer,String requestedId,BlockPos station) {
        if(!MilestoneShop.atExchange(buyer,station))
            return new Report("Move closer to the Resonant Exchange.",0,0,false);
        if(!net.fireboy.milestone.server.config.MilestoneContentConfig.enabled())
            return new Report("Milestones are disabled.",0,0,false);
        int price=MilestoneShop.playerIntelPrice();
        if(price<=0)return new Report("Player Intel is disabled by the server.",0,0,false);
        UUID targetId;
        try {targetId=UUID.fromString(requestedId);}
        catch(IllegalArgumentException ex) {
            return new Report("Invalid player selection.",price,0,false);
        }
        MilestoneShopPlayerIntel data=get(buyer.getServer());
        ServerPlayer active=buyer.getServer().getPlayerList().getPlayer(targetId);
        if(active!=null)data.observe(active);
        Seen known=data.known.get(targetId);
        if(known==null)return new Report("No location recorded for that player yet.",price,0,false);
        MilestoneShopAccounts credits=MilestoneShopAccounts.get(buyer.getServer());
        if(credits.balance(buyer.getUUID())<price)
            return new Report("Not enough credits (need "+price+").",
                    price,credits.balance(buyer.getUUID()),false);
        // The same stored approximation is shared with all buyers, rather
        // than sampling anew for each repeated purchase.
        Seen sample=data.withApproximation(targetId,known);
        if(!credits.spend(buyer.getUUID(),price))
            return new Report("Insufficient credits.",price,credits.balance(buyer.getUUID()),false);
        String state=active==null
                ?"LAST KNOWN ("+Instant.ofEpochMilli(sample.seenMs())+")":"CURRENT (online)";
        String result=sample.name()+" — "+state+" | "+sample.dimension()+
                " | near X="+sample.approximateX()+", Z="+sample.approximateZ()+
                " (within 100 blocks; Y hidden)";
        buyer.sendSystemMessage(Component.literal("[Player Intel] "+result)
                .withStyle(ChatFormatting.AQUA));
        return new Report(result,price,credits.balance(buyer.getUUID()),true);
    }
}
