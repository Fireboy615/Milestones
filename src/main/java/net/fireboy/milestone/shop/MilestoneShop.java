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
    public record ItemCost(ResourceLocation item,int count) {}
    public record UnlockCondition(String type,ResourceLocation target,int count,String scope) {
        public String key() { return type+"|"+target+"|"+scope; }
        public boolean global() { return scope.equals("global"); }
    }
    public record Offer(String id,String name,ResourceLocation item,int count,int price,
            int requiresCore,String description,String rarity,List<ItemCost> itemCosts,
            List<UnlockCondition> conditions,String rewardType,String schematicId) {
        public Offer {
            itemCosts=List.copyOf(itemCosts);
            conditions=List.copyOf(conditions);
        }
    }
    public static final List<String> RARITIES =
            List.of("common","uncommon","rare","epic","legendary");
    public static final Map<String,Integer> DEFAULT_LIMITS =
            Map.of("common",6,"uncommon",5,"rare",4,"epic",3,"legendary",2);

    public record Settings(int creditsPerContribution,int playerIntelPrice,
            List<Offer> offers,Map<String,Integer> rarityLimits) {
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
        int intelPrice=number(root,"player_intel_price",100);
        if(intelPrice<0 || intelPrice>1000000)
            throw new IllegalArgumentException("Player Intel price must be 0-1000000 (0 disables it)");
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
            // A schematic is a genuine filled Create item. It must reference
            // an existing registered, validated Milestone schematic, never an
            // arbitrary client-provided filename or filesystem path.
            String rewardType=string(o,"reward_type");
            if(rewardType.isBlank())rewardType="item"; // backwards compatibility
            if(!List.of("item","schematic").contains(rewardType))
                throw new IllegalArgumentException(id+": reward_type must be item or schematic");
            String schematicId=string(o,"schematic");
            if(rewardType.equals("schematic")) {
                boolean found=false;
                if(coresRoot.has("schematics"))
                    for(JsonElement entry:coresRoot.getAsJsonArray("schematics"))
                        if(entry.getAsJsonObject().get("id").getAsString().equals(schematicId))
                            found=true;
                if(!found)
                    throw new IllegalArgumentException(id+": unknown schematic: "+schematicId+
                            " (import it under Config > Schematics first)");
            }
            ResourceLocation item=rewardType.equals("schematic")
                    ?ResourceLocation.fromNamespaceAndPath("create","schematic")
                    :ResourceLocation.tryParse(itemId);
            if(item==null || !BuiltInRegistries.ITEM.containsKey(item)
                    || BuiltInRegistries.ITEM.get(item)==Items.AIR)
                throw new IllegalArgumentException("Unknown shop item: "+itemId);
            int count=number(o,"count",1), price=number(o,"price",10),
                    required=number(o,"requires_core",-1);
            if(count<1 || count>(rewardType.equals("schematic")?1:64))
                throw new IllegalArgumentException(id+": count must be "+
                        (rewardType.equals("schematic")?"1 for schematics":"1-64"));
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
            List<ItemCost> costs=new ArrayList<>();
            JsonArray costRows=o.has("cost_items")?o.getAsJsonArray("cost_items"):new JsonArray();
            if(costRows.size()>8)throw new IllegalArgumentException(id+": maximum 8 item costs");
            Set<ResourceLocation> usedCosts=new HashSet<>();
            for(JsonElement costRow:costRows) {
                JsonObject cost=costRow.getAsJsonObject();
                ResourceLocation costId=ResourceLocation.tryParse(string(cost,"item"));
                if(costId==null || !BuiltInRegistries.ITEM.containsKey(costId)
                        || BuiltInRegistries.ITEM.get(costId)==Items.AIR || !usedCosts.add(costId))
                    throw new IllegalArgumentException(id+": invalid or duplicate cost item");
                int amount=number(cost,"count",1);
                if(amount<1 || amount>2304)
                    throw new IllegalArgumentException(id+": cost count must be 1-2304");
                costs.add(new ItemCost(costId,amount));
            }
            List<UnlockCondition> conditions=new ArrayList<>();
            JsonArray unlockRows=o.has("unlock_conditions")
                    ?o.getAsJsonArray("unlock_conditions"):new JsonArray();
            if(unlockRows.size()>8)
                throw new IllegalArgumentException(id+": maximum 8 unlock conditions");
            Set<String> usedConditions=new HashSet<>();
            for(JsonElement unlockRow:unlockRows) {
                JsonObject entry=unlockRow.getAsJsonObject();
                String kind=string(entry,"type");
                if(!List.of("kill","structure","advancement").contains(kind))
                    throw new IllegalArgumentException(id+": unlock type must be kill, structure or advancement");
                ResourceLocation target=ResourceLocation.tryParse(string(entry,"target"));
                if(target==null)
                    throw new IllegalArgumentException(id+": invalid unlock target");
                if(kind.equals("kill") && !BuiltInRegistries.ENTITY_TYPE.containsKey(target))
                    throw new IllegalArgumentException(id+": unknown mob "+target);
                String scope=string(entry,"scope");
                if(!scope.equals("global") && !scope.equals("individual"))
                    throw new IllegalArgumentException(id+": unlock scope must be global or individual");
                int needed=number(entry,"count",1);
                if(needed<1 || needed>100000 || !kind.equals("kill") && needed!=1)
                    throw new IllegalArgumentException(id+": invalid unlock count");
                UnlockCondition condition=new UnlockCondition(kind,target,needed,scope);
                if(!usedConditions.add(condition.key()))
                    throw new IllegalArgumentException(id+": duplicate unlock condition");
                conditions.add(condition);
            }
            offers.add(new Offer(id,name,item,count,price,required,description,rarity,
                    costs,conditions,rewardType,schematicId));
        }
        return new Settings(earnings,intelPrice,offers,limits);
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
    public static int playerIntelPrice() { return settings().playerIntelPrice(); }
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
    public static boolean conditionsMet(ServerPlayer player,Offer offer) {
        MilestoneShopUnlocks progress=MilestoneShopUnlocks.get(player.getServer());
        for(UnlockCondition condition:offer.conditions())
            if(progress.progress(player,condition)<condition.count())return false;
        return true;
    }
    /** Only the normal inventory is used: curios/bags are not silently charged. */
    private static int inventoryCount(ServerPlayer player,ItemCost cost) {
        int found=0;
        for(ItemStack stack:player.getInventory().items)
            if(stack.is(BuiltInRegistries.ITEM.get(cost.item()))) found+=stack.getCount();
        return found;
    }
    private static boolean canPayItems(ServerPlayer player,Offer offer) {
        for(ItemCost cost:offer.itemCosts())
            if(inventoryCount(player,cost)<cost.count())return false;
        return true;
    }
    private static void consumeItems(ServerPlayer player,Offer offer) {
        for(ItemCost cost:offer.itemCosts()) {
            int left=cost.count();
            Item item=BuiltInRegistries.ITEM.get(cost.item());
            for(ItemStack stack:player.getInventory().items) {
                if(left==0)break;
                if(stack.is(item)) {
                    int n=Math.min(left,stack.getCount());
                    stack.shrink(n);
                    left-=n;
                }
            }
            if(left!=0)throw new IllegalStateException("Item costs changed mid-purchase");
        }
        player.getInventory().setChanged();
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
        if(!conditionsMet(player,offer))
            return "Complete this offer's unlock requirements first.";
        MilestoneShopStock stock=MilestoneShopStock.get(player.getServer());
        if(stock.boughtThisCycle(player.getUUID(),offer.id()))
            return "Already purchased this rotation. Check back after the reset.";
        if(!isOnSale(player,offer,stock))
            return "This offer has rotated out. Reserve it next time.";
        Item item=BuiltInRegistries.ITEM.get(offer.item());
        if(item==Items.AIR)return "The item is unavailable.";
        // Prepare the filled Create schematic before charging any resources.
        // Missing/unreadable/invalid files must never consume credits/items.
        ItemStack reward;
        MilestoneShopSchematicRewards.PreparedReward prepared=null;
        if(offer.rewardType().equals("schematic")) {
            try {
                prepared=MilestoneShopSchematicRewards.prepare(player,offer);
                reward=prepared.stack();
            }catch(java.io.IOException | RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(MilestoneShop.class)
                        .warn("Cannot prepare schematic reward for offer {}",offer.id(),failure);
                return "Schematic is unavailable. Ask an OP to check its .nbt file.";
            }
        }else reward=new ItemStack(item,offer.count());
        MilestoneShopAccounts account=MilestoneShopAccounts.get(player.getServer());
        if(account.balance(player.getUUID())<offer.price())
            return "Not enough credits. Need "+offer.price()+".";
        if(!canPayItems(player,offer))
            return "Missing required item payment. Check the item costs.";
        // On the server thread, both resources are validated before either
        // one is charged. No client-provided price, counts or item IDs.
        if(!account.spend(player.getUUID(),offer.price()))
            return "Not enough credits.";
        consumeItems(player,offer);
        stock.recordPurchase(player.getUUID(),offer.id());
        stock.consume(player.getUUID(),offer.id());
        // Only send client-side Create structure data after successful payment.
        if(prepared!=null)prepared.sendTo(player);
        // Offer IDs are unique and count/price are validated at config load.
        player.getInventory().add(reward);
        if(!reward.isEmpty())player.drop(reward,false);
        return "Purchased "+offer.count()+" x "+offer.name()+" for "+offer.price()+" credits.";
    }
    public static String snapshot(ServerPlayer player) {
        Settings settings=settings();
        MilestoneShopStock stock=MilestoneShopStock.get(player.getServer());
        JsonObject result=new JsonObject();
        result.addProperty("balance",MilestoneShopAccounts.get(player.getServer()).balance(player.getUUID()));
        result.addProperty("earned",MilestoneShopAccounts.get(player.getServer()).lifetime(player.getUUID()));
        result.addProperty("hourly_pay",MilestonePassiveIncome.get(player.getServer()).hourlyPay());
        result.addProperty("first_join_ms",
                MilestoneShopAccounts.get(player.getServer()).firstJoinMs(player.getUUID()));
        result.addProperty("reset_seconds",(stock.remainingMs()+999L)/1000L);
        result.addProperty("player_intel_price",settings.playerIntelPrice());
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
        row.addProperty("reward_type",offer.rewardType());
        if(offer.rewardType().equals("schematic"))
            row.addProperty("schematic",offer.schematicId());
        row.addProperty("count",offer.count());
        row.addProperty("price",offer.price());
        row.addProperty("requires_core",offer.requiresCore());
        boolean coreUnlocked=isUnlocked(player.getServer(),offer);
        boolean conditionsUnlocked=conditionsMet(player,offer);
        row.addProperty("unlocked",coreUnlocked && conditionsUnlocked);
        row.addProperty("core_unlocked",coreUnlocked);
        row.addProperty("conditions_unlocked",conditionsUnlocked);
        JsonArray costs=new JsonArray();
        boolean itemsAffordable=true;
        for(ItemCost cost:offer.itemCosts()) {
            JsonObject requirement=new JsonObject();
            int have=inventoryCount(player,cost);
            requirement.addProperty("item",cost.item().toString());
            requirement.addProperty("count",cost.count());
            requirement.addProperty("have",have);
            if(have<cost.count())itemsAffordable=false;
            costs.add(requirement);
        }
        row.add("cost_items",costs);
        row.addProperty("items_affordable",itemsAffordable);
        JsonArray requirements=new JsonArray();
        MilestoneShopUnlocks progress=MilestoneShopUnlocks.get(player.getServer());
        for(UnlockCondition condition:offer.conditions()) {
            JsonObject requirement=new JsonObject();
            requirement.addProperty("type",condition.type());
            requirement.addProperty("target",condition.target().toString());
            requirement.addProperty("scope",condition.scope());
            boolean targetValid=switch(condition.type()) {
                case "structure" -> player.serverLevel().registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                        .containsKey(condition.target());
                case "advancement" -> player.getServer().getAdvancements()
                        .get(condition.target())!=null;
                default -> true;
            };
            requirement.addProperty("target_valid",targetValid);
            requirement.addProperty("count",condition.count());
            requirement.addProperty("progress",Math.min(condition.count(),
                    progress.progress(player,condition)));
            requirements.add(requirement);
        }
        row.add("unlock_conditions",requirements);
        row.addProperty("in_stock",inStock);
        row.addProperty("reserved",reserved);
        row.addProperty("purchased",MilestoneShopStock.get(player.getServer())
                .boughtThisCycle(player.getUUID(),offer.id()));
        row.addProperty("rarity",offer.rarity());
        return row;
    }
}
