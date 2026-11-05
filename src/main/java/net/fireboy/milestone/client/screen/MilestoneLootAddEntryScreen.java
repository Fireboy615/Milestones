package net.fireboy.milestone.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Adds a direct item entry to the SELECTED loot pool, not a new bonus
 * pool. The server rechecks the revision, pool, item and numeric limits. */
public final class MilestoneLootAddEntryScreen extends Screen {
    private final Screen parent;
    private final boolean bonus;
    private final String table;
    private final String revision;
    private final int poolIndex;
    private String itemId="minecraft:diamond",weight="10",minimum="1",maximum="1";
    private String status="Select an item and choose its weight and stack amount.";
    private boolean confirming,submitting;
    private EditBox itemBox,weightBox,minBox,maxBox;
    private Button writeButton;

    public MilestoneLootAddEntryScreen(MilestoneLootPoolScreen parent,
                                        String table,String revision,int poolIndex) {
        super(Component.literal("Add Loot Pool Entry"));
        this.parent=parent;
        this.table=table;
        this.revision=revision;
        this.poolIndex=poolIndex;
        this.bonus=false;
    }
    public MilestoneLootAddEntryScreen(MilestoneLootScreen parent,String table){
        super(Component.literal("Add Bonus Drop"));
        this.parent=parent;
        this.table=table;
        this.revision="";
        this.poolIndex=-1;
        this.bonus=true;
    }
    private int pw(){return Math.min(445,width-12);}
    private int ph(){return Math.min(302,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private static int parse(String value,int fallback) {
        try{return Integer.parseInt(value.trim());}
        catch(NumberFormatException bad){return fallback;}
    }
    private ItemStack selectedStack(){
        ResourceLocation id=ResourceLocation.tryParse(itemId);
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))
            return ItemStack.EMPTY;
        Item item=BuiltInRegistries.ITEM.get(id);
        return item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
    }
    private boolean valid(){
        return !submitting&&!selectedStack().isEmpty()&&itemId.length()<=128
                &&parse(weight,0)>=1&&parse(weight,1001)<=(bonus?100:1000)
                &&parse(minimum,0)>=1
                &&parse(maximum,0)>=parse(minimum,0)&&parse(maximum,65)<=64;
    }
    private void changed(){
        confirming=false;
        if(writeButton!=null){
            writeButton.setMessage(Component.literal("Stage New Entry"));
            writeButton.active=valid();
        }
    }
    private void chooseItem(){
        if(minecraft==null)return;
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                MilestoneIdPickerScreen.Kind.ITEM,false,
                List.of(itemId),List.of(),selected -> {
                    if(!selected.isEmpty())itemId=selected.get(0);
                    confirming=false;
                }));
    }
    private void save() {
        if(!valid())return;
        if(!confirming) {
            confirming=true;
            status=bonus?"Confirm separate bonus pool in the world datapack.":
                    "Confirm: add this item to pool "+(poolIndex+1)+".";
            writeButton.setMessage(Component.literal("CONFIRM Add Entry"));
            return;
        }
        confirming=false;submitting=true;
        status="Staging item entry on the server...";
        writeButton.active=false;
        if(bonus)PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(
                "add_bonus",table,itemId,0,parse(weight,0),
                parse(minimum,0),parse(maximum,0)));
        else PacketDistributor.sendToServer(new MilestoneLootNetwork.AddEntryRequest(
                table,revision,itemId,poolIndex,parse(weight,0),
                parse(minimum,0),parse(maximum,0)));
    }
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),right=x+w;
        addRenderableWidget(Button.builder(Component.literal("< Back"),
                button->onClose()).bounds(x+10,y+10,91,20).build());
        itemBox=new EditBox(font,x+18,y+78,w-154,20,Component.literal("Item registry ID"));
        itemBox.setMaxLength(128);
        itemBox.setValue(itemId);
        itemBox.setHint(Component.literal("modid:item_id"));
        itemBox.setResponder(next->{itemId=next;changed();});
        addRenderableWidget(itemBox);
        addRenderableWidget(Button.builder(Component.literal("Choose Item..."),
                button->chooseItem()).bounds(right-129,y+78,112,20).build());
        int third=(w-47)/3;
        weightBox=new EditBox(font,x+18,y+156,third,20,Component.literal("Relative weight"));
        weightBox.setMaxLength(4);weightBox.setValue(weight);
        weightBox.setResponder(next->{weight=next;changed();});
        addRenderableWidget(weightBox);
        minBox=new EditBox(font,x+24+third,y+156,third,20,Component.literal("Min count"));
        minBox.setMaxLength(2);minBox.setValue(minimum);
        minBox.setResponder(next->{minimum=next;changed();});
        addRenderableWidget(minBox);
        maxBox=new EditBox(font,x+30+third*2,y+156,third,20,Component.literal("Max count"));
        maxBox.setMaxLength(2);maxBox.setValue(maximum);
        maxBox.setResponder(next->{maximum=next;changed();});
        addRenderableWidget(maxBox);
        writeButton=addRenderableWidget(Button.builder(
                Component.literal(confirming?"CONFIRM Add":
                        bonus?"Stage Bonus Pool":"Stage Pool Entry"),
                button->save()).bounds(x+18,b()-45,w-36,21).build());
        writeButton.active=valid();
    }
    public void receive(String json){
        try{
            JsonObject message=JsonParser.parseString(json).getAsJsonObject();
            if(!message.has("action")
                    ||!(bonus?"add_bonus":"add_entry")
                    .equals(message.get("action").getAsString()))return;
            submitting=false;
            status=message.has("message")?message.get("message").getAsString():
                    "No response from server.";
            if(message.has("success")&&message.get("success").getAsBoolean()){
                if(minecraft!=null){
                    minecraft.setScreen(parent);
                    if(bonus && parent instanceof MilestoneLootScreen browser)
                        browser.onBonusAdded(status);
                    else if(parent instanceof MilestoneLootPoolScreen pools)
                        pools.onEntryAdded(status);
                }
                return;
            }
        }catch(RuntimeException bad){
            status="Invalid server reply.";
            submitting=false;
        }
        confirming=false;
        clearWidgets();init();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        // Paint our contents after Minecraft's world-background blur.
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw(),right=x+w;
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,right,b(),0xFF30363D);
        g.fill(x,y,right,y+2,0xFF9EAAB7);
        g.drawString(font,font.plainSubstrByWidth(bonus?"ADD BONUS DROP":
                "ADD TO POOL",w-115),x+113,y+16,0xFFF7E5BD);
        g.drawString(font,font.plainSubstrByWidth(table,w-38),x+18,y+40,0xFFF5F5F5);
        g.drawString(font,font.plainSubstrByWidth(
                bonus?"Separate pool, own chance":
                "Pool "+(poolIndex+1)+" (shared conditions)",w-36),
                x+18,y+53,0xFFB9CDD8);
        g.drawString(font,"Item ID",x+18,y+66,0xFFE4E4E4);
        ItemStack stack=selectedStack();
        g.fill(x+18,y+107,right-18,y+135,0xFF424951);
        g.renderItem(stack.isEmpty()?new ItemStack(Items.BARRIER):stack,x+23,y+112);
        g.drawString(font,font.plainSubstrByWidth(stack.isEmpty()
                ?"Unknown item ID":stack.getHoverName().getString(),w-73),
                x+46,y+116,stack.isEmpty()?0xFFFFB9B9:0xFFF3E9CF);
        int third=(w-47)/3;
        g.drawString(font,bonus?"Chance %":"Weight",x+18,y+144,0xFFDADADA);
        g.drawString(font,"Min count",x+24+third,y+144,0xFFDADADA);
        g.drawString(font,"Max count",x+30+third*2,y+144,0xFFDADADA);
        if(ph()>273)
            g.drawString(font,bonus?"Chance controls a separate extra roll":
                    "Weight competes with other entries in this pool",
                    x+18,y+191,0xFFB9CBD6);
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-35),
                x+18,b()-19,0xFFFFD49B);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
