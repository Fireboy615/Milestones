package net.fireboy.milestone.client.screen;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared human-friendly shop costs and progress readouts. */
public final class MilestoneShopRequirementText {
    private MilestoneShopRequirementText(){}
    public static String cost(JsonObject row){
        ResourceLocation key=ResourceLocation.tryParse(row.get("item").getAsString());
        String itemName=key==null?row.get("item").getAsString():
                !BuiltInRegistries.ITEM.containsKey(key)
                ?row.get("item").getAsString():
                new ItemStack(BuiltInRegistries.ITEM.get(key)).getHoverName().getString();
        return row.get("count").getAsInt()+"x "+itemName+
                " ("+row.get("have").getAsInt()+"/"+row.get("count").getAsInt()+")";
    }
    public static String objective(JsonObject row) {
        String type=row.get("type").getAsString();
        String target=row.get("target").getAsString();
        String scope=row.get("scope").getAsString().equals("global")?"ALL":"YOU";
        String goal=target.contains(":")?target.substring(target.indexOf(':')+1):target;
        if(goal.length()>23)goal="..."+goal.substring(goal.length()-20);
        if(row.has("target_valid")&&!row.get("target_valid").getAsBoolean())
            return "INVALID ID: "+target;
        return scope+" "+type+" "+goal+" "+
                row.get("progress").getAsInt()+"/"+row.get("count").getAsInt();
    }
    public static boolean finished(JsonObject row){
        return row.get("progress").getAsInt()>=row.get("count").getAsInt();
    }
    public static boolean costSatisfied(JsonObject row){
        return row.get("have").getAsInt()>=row.get("count").getAsInt();
    }
}
