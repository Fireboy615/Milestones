package net.fireboy.milestone.shop;

import com.google.gson.*;
import net.fireboy.milestone.milestone.MilestoneManager;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.fireboy.milestone.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/** Server-owned shop catalog, progression rules and atomic purchases. */
public final class MilestoneShop {
    public record Offer(String id,String name,ResourceLocation item,int count,int price,int requiresCore,String description,String rarity) {}
    public static final List<String> RARITIES =
            List.of("common","uncommon","rare","epic","legendary");
    public static final Map<String,Integer> DEFAULT_LIMITS =
            Map.of("common",6,"uncommon",5,"rare",4,"epic",3,"legendary",2);

    public record Settings(int creditsPerContribution,List<Offer> offers,Map<String,Integer> rarityLimits) {
        public Settings {
            offers=List.copyOf(offers);
            rarityLimits=Map.copyOf(rarityLimits);
        }
        public Offer byId(String id) {
            for(Offer offer:offers)if(offer.id().equals(id))return offer;
            return null;
        }
    }
    private MilestoneShop() {}

    public static Settings parse(JsonObject coresRoot, Set<Integer> coreIds) {
        JsonObject root=coresRoot.has("shop") ? coresRoot.getAsJsonObject("shop") : new JsonObject();
        int earnings=root.has("credits_per_contribution") ? root.get("credits_per_contribution").getAsInt() : 10;
        if(earnings<0 || earnings>10000)
            throw new IllegalArgumentException("Shop contribution reward must be 0-10000");
        Map<String,Integer> limits=new LinkedHashMap<>();
        JsonObject configuredLimits=root.has("max_per_rarity")
                ?root.getAsJsonObject("max_per_rarity"):new JsonObject();
        for(String rarity:RARITIES) {
            int limit=number(configuredLimits,rarity,DEFAULT_LIMITS.get(rarity));
            if(limit<0 || limit>64)
                throw new IllegalArgumentException("Shop "+rarity+" limit must be 0-64");
            limits.put(rarity,limit);
        }
        JsonArray entries=root.has("offers")?root.getAsJsonArray("offers"):new JsonArray();
        if(entries.size()>64)throw new IllegalArgumentException("Maximum 64 shop offers");
        List<Offer> offers=new ArrayList<>();
        Set<String> used=new HashSet<>();
        for(JsonElement element:entries) {
            JsonObject o=element.getAsJsonObject();
            String id=string(o,"id"), name=string(o,"name"), itemId=string(o,"item");
            if(!id.matches("[a-z0-9_-]{1,64}") || !used.add(id))
                throw new IllegalArgumentException("Invalid/duplicate shop offer ID "+id);
            if(name.isBlank() || name.length()>80)
                throw new IllegalArgumentException("Offer "+id+" needs a name (1-80 characters)");
            ResourceLocation item=ResourceLocation.tryParse(itemId);
            if(item==null || !BuiltInRegistries.ITEM.containsKey(item)
                    || BuiltInRegistries.ITEM.get(item)==Items.AIR)
                throw new IllegalArgumentException("Unknown shop item: "+itemId);
            int count=number(o,"count",1), price=number(o,"price",10),
                    required=number(o,"requires_core",-1);
            if(count<1 || count>64)throw new IllegalArgumentException(id+": count must be 1-64");
            if(price<1 || price>1000000)throw new IllegalArgumentException(id+": price must be 1-1000000");
            if(required!=-1 && !coreIds.contains(required))
                throw new IllegalArgumentException(id+": requires missing Core "+required);
            String description=string(o,"description");
            if(description.length()>240)
                throw new IllegalArgumentException(id+": description must be 240 characters or less");
            String rarity=string(o,"rarity");
            if(rarity.isBlank())rarity="common"; // Existing offers remain regular stock.
            if(!List.of("common","uncommon","rare","epic","legendary").contains(rarity))
                throw new IllegalArgumentException(id+": invalid rarity: "+rarity);
            offers.add(new Offer(id,name,item,count,price,required,description,rarity));
        }
        return new Settings(earnings,offers,limits);
    }
    private static String string(JsonObject root,String field) {
        return root.has(field)?root.get(field).getAsString():"";
    }
    private static int number(JsonObject root,String field,int fallback) {
        return root.has(field)?root.get(field).getAsInt():fallback;
    }
    public static Settings settings() {
        JsonObject cores=JsonParser.parseString(MilestoneContentConfig.server().payload())
                .getAsJsonObject().getAsJsonObject("cores");
        return parse(cores,MilestoneContentConfig.server().cores().keySet());
    }
    public static boolean isUnlocked(MinecraftServer server,Offer offer) {
        if(offer.requiresCore()<0)return true;
        return MilestoneManager.data(server).all().stream().anyMatch(record ->
                record.coreTier()==offer.requiresCore() && record.state()==MilestoneState.COMPLETED);
    }
    /** Buyers must be next to an actual Resonant Exchange block, not the Mission Station. */
    public static boolean atExchange(ServerPlayer player,BlockPos pos) {
        return pos!=null && player.serverLevel().hasChunkAt(pos)
                && player.distanceToSqr(pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5)<=64
                && player.serverLevel().getBlockState(pos).is(ModBlocks.RESONANT_EXCHANGE.get());
    }
    public static void rewardContribution(ServerPlayer player) {
        if(!MilestoneContentConfig.enabled())return;
        int reward=settings().creditsPerContribution();
        if(reward>0) {
            MilestoneShopAccounts wallet=MilestoneShopAccounts.get(player.getServer());
            wallet.earn(player.getUUID(),reward);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "+"+reward+" Shop credits (balance: "+wallet.balance(player.getUUID())+")")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }
    private static boolean isOnSale(ServerPlayer player,Offer offer,MilestoneShopStock stock) {
        return stock.isReserved(player.getUUID(),offer.id())
                || stock.active(settings().offers(),settings().rarityLimits()).stream()
                        .anyMatch(candidate -> candidate.id().equals(offer.id()));
    }

    /** Reserve a currently displayed offer for one further shop rotation.
     * Reservations are free, capped at six, and never grant Core unlocks. */
    public static String reserve(ServerPlayer player, BlockPos pos, String offerId) {
        if(!atExchange(player,pos))return "Move closer to the Resonant Exchange.";
        if(!MilestoneContentConfig.enabled())return "Milestones are currently disabled.";
        Offer offer=settings().byId(offerId);
        if(offer==null)return "Offer not found.";
        MilestoneShopStock stock=MilestoneShopStock.get(player.getServer());
        if(!isUnlocked(player.getServer(),offer) && !stock.isReserved(player.getUUID(),offer.id()))
            return "Complete Core "+(offer.requiresCore()+1)+" first.";
        if(!isOnSale(player,offer,stock))return "This offer is no longer in stock.";
        if(stock.boughtThisCycle(player.getUUID(),offer.id())
                && !stock.isReserved(player.getUUID(),offer.id()))
            return "You already bought this offer this rotation.";
        return stock.reserve(player.getUUID(),offer.id());
    }

    /** Invoked only on server main thread; client supplies offer ID, never item/price. */
    public static String buy(ServerPlayer player,BlockPos pos,String offerId) {
        if(!atExchange(player,pos))return "Move closer to the Resonant Exchange.";
        if(!MilestoneContentConfig.enabled())return "Milestones are currently disabled.";
        Offer offer=settings().byId(offerId);
        if(offer==null)return "This offer is no longer available.";
        if(!isUnlocked(player.getServer(),offer))
            return "Complete Core "+(offer.requiresCore()+1)+" to unlock this offer.";
        MilestoneShopStock stock=MilestoneShopStock.get(player.getServer());
        if(stock.boughtThisCycle(player.getUUID(),offer.id()))
            return "Already purchased this rotation. Check back after the reset.";
        if(!isOnSale(player,offer,stock))
            return "This offer has rotated out. Reserve it next time.";
        Item item=BuiltInRegistries.ITEM.get(offer.item());
        if(item==Items.AIR)return "The item is unavailable.";
        MilestoneShopAccounts account=MilestoneShopAccounts.get(player.getServer());
        if(!account.spend(player.getUUID(),offer.price()))
            return "Not enough credits. Need "+offer.price()+".";
        stock.recordPurchase(player.getUUID(),offer.id());
        stock.consume(player.getUUID(),offer.id());
        // Offer IDs are unique and count/price are validated at config load.
        ItemStack stack=new ItemStack(item,offer.count());
        player.getInventory().add(stack);
        if(!stack.isEmpty())player.drop(stack,false);
        return "Purchased "+offer.count()+" x "+offer.name()+" for "+offer.price()+" credits.";
    }
    public static String snapshot(ServerPlayer player) {
        Settings settings=settings();
        MilestoneShopStock stock=MilestoneShopStock.get(player.getServer());
        JsonObject result=new JsonObject();
        result.addProperty("balance",MilestoneShopAccounts.get(player.getServer()).balance(player.getUUID()));
        result.addProperty("earned",MilestoneShopAccounts.get(player.getServer()).lifetime(player.getUUID()));
        result.addProperty("reset_seconds",(stock.remainingMs()+999L)/1000L);
        // The client only uses this to show the button. The server validates
        // operator status again whenever a reroll is requested.
        result.addProperty("can_reroll",player.hasPermissions(2));
        List<Offer> active=stock.active(settings.offers(),settings.rarityLimits());
        List<String> reserved=stock.reserved(player.getUUID());
        JsonArray visible=new JsonArray();
        JsonArray entireCatalog=new JsonArray();
        Set<String> seen=new HashSet<>();
        Set<String> stockIds=new HashSet<>();
        for(Offer offer:active)stockIds.add(offer.id());
        // Rotated stock is what the shop renders, independent of catalog size.
        for(Offer offer:active) {
            visible.add(snapshotOffer(player,offer,true,reserved.contains(offer.id())));
            seen.add(offer.id());
        }
        // Reserved items remain accessible through the Held slots even after
        // the current rotation would otherwise have excluded them.
        for(String id:reserved) {
            Offer offer=settings.byId(id);
            if(offer!=null&&!seen.contains(id)) {
                visible.add(snapshotOffer(player,offer,false,true));
                seen.add(id);
            }
        }
        // The All Items browser has the full server-side catalog, including
        // locked and out-of-rotation offers. It is informational only.
        for(Offer offer:settings.offers())
            entireCatalog.add(snapshotOffer(player,offer,
                    stockIds.contains(offer.id()),reserved.contains(offer.id())));
        JsonObject counts=new JsonObject();
        for(String rarity:RARITIES)
            counts.addProperty(rarity,settings.rarityLimits().get(rarity));
        result.add("offers",visible);
        result.add("all_offers",entireCatalog);
        result.add("rarity_limits",counts);
        return result.toString();
    }

    private static JsonObject snapshotOffer(ServerPlayer player,Offer offer,
                                            boolean inStock,boolean reserved) {
        JsonObject row=new JsonObject();
        row.addProperty("id",offer.id());
        row.addProperty("name",offer.name());
        row.addProperty("description",offer.description());
        row.addProperty("item",offer.item().toString());
        row.addProperty("count",offer.count());
        row.addProperty("price",offer.price());
        row.addProperty("requires_core",offer.requiresCore());
        row.addProperty("unlocked",isUnlocked(player.getServer(),offer));
        row.addProperty("in_stock",inStock);
        row.addProperty("reserved",reserved);
        row.addProperty("purchased",MilestoneShopStock.get(player.getServer())
                .boughtThisCycle(player.getUUID(),offer.id()));
        row.addProperty("rarity",offer.rarity());
        return row;
    }
}
