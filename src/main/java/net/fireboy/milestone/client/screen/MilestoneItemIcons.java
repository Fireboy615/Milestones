package net.fireboy.milestone.client.screen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Common icon lookup for item/block references in Milestone configuration UIs. */
public final class MilestoneItemIcons {
    private MilestoneItemIcons() {}

    public static ItemStack item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if(key==null || !BuiltInRegistries.ITEM.containsKey(key))return ItemStack.EMPTY;
        var item=BuiltInRegistries.ITEM.get(key);
        return item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
    }
    public static ItemStack block(String id) {
        ResourceLocation key=ResourceLocation.tryParse(id);
        if(key==null || !BuiltInRegistries.BLOCK.containsKey(key))return ItemStack.EMPTY;
        var item=BuiltInRegistries.BLOCK.get(key).asItem();
        return item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
    }
    public static ItemStack firstReward(String text) {
        if(text==null || text.isBlank())return ItemStack.EMPTY;
        String entry=text.split(",",2)[0].trim();
        String name=entry.split("\\*",2)[0].trim();
        return item(name);
    }
}
