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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** One focused edit form for an existing direct item entry. The server
 * preserves unrelated functions/conditions and validates the revision. */
public final class MilestoneLootEditEntryScreen extends Screen {
    private final MilestoneLootPoolScreen parent;
    private final String table,revision;
    private final JsonObject row;
    private final int pool,entry;
    private final boolean direct,countEditable,canRemove;
    private String weight,minimum,maximum;
    private String status="Change a value, then click its action to confirm.";
    private String pending="";
    private boolean loading;
    private EditBox weightBox,minBox,maxBox;
    private Button weightButton,countButton,removeButton;

    public MilestoneLootEditEntryScreen(MilestoneLootPoolScreen parent,String table,
                                        String revision,int pool,JsonObject row,
                                        boolean canRemove) {
        super(Component.literal("Edit Loot Entry"));
        this.parent=parent;this.table=table;this.revision=revision;
        this.pool=pool;this.row=row;this.entry=row.get("index").getAsInt();
        this.canRemove=canRemove;
        this.direct=row.has("direct_item")&&row.get("direct_item").getAsBoolean();
        this.countEditable=row.has("count_editable")&&row.get("count_editable").getAsBoolean();
        this.weight=row.get("weight").getAsString();
        this.minimum=row.has("min")?row.get("min").getAsString():"1";
        this.maximum=row.has("max")?row.get("max").getAsString():"1";
    }
    private int pw(){return Math.min(450,width-12);}
    private int ph(){return Math.min(307,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private int col(){return (pw()-36)/3;}
    private static int number(String v,int fallback){
        try{return Integer.parseInt(v.trim());}
        catch(NumberFormatException bad){return fallback;}
    }
    private ItemStack icon(){
        ResourceLocation id=ResourceLocation.tryParse(row.get("name").getAsString());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))
            return new ItemStack(Items.BOOK);
        ItemStack stack=new ItemStack(BuiltInRegistries.ITEM.get(id));
        return stack.isEmpty()?new ItemStack(Items.BARRIER):stack;
    }
    private boolean validWeight(){
        return !loading&&direct&&number(weight,0)>=1&&number(weight,1001)<=1000;
    }
    private boolean validCount(){
        int min=number(minimum,0),max=number(maximum,0);
        return !loading&&direct&&countEditable&&min>=1&&max>=min&&max<=64;
    }
    private void refresh(){
        pending="";
        if(weightButton!=null)weightButton.setMessage(Component.literal("Set Weight"));
        if(countButton!=null)countButton.setMessage(Component.literal("Set Count"));
        if(removeButton!=null)removeButton.setMessage(Component.literal("Remove Entry"));
        updateButtons();
    }
    private void updateButtons(){
        if(weightButton!=null)weightButton.active=validWeight();
        if(countButton!=null)countButton.active=validCount();
        if(removeButton!=null)removeButton.active=!loading&&direct&&canRemove;
    }
    private void execute(String action){
        if(loading)return;
        if(action.equals("set_weight")&&!validWeight() ||
                action.equals("set_count")&&!validCount() ||
                action.equals("remove_entry")&&(!direct||!canRemove))return;
        if(!pending.equals(action)){
            pending=action;
            status="Click the same action again to confirm the datapack edit.";
            weightButton.setMessage(Component.literal(action.equals("set_weight")
                    ?"CONFIRM Weight":"Set Weight"));
            countButton.setMessage(Component.literal(action.equals("set_count")
                    ?"CONFIRM Count":"Set Count"));
            removeButton.setMessage(Component.literal(action.equals("remove_entry")
                    ?"CONFIRM Remove":"Remove Entry"));
            return;
        }
        pending="";loading=true;updateButtons();
        status="Staging change to world datapack...";
        PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(action,table,
                revision,pool,entry,action.equals("set_weight")
                ?number(weight,0):number(minimum,0),number(maximum,0)));
    }
    public void onEnchantChanged(String message){
        if(minecraft!=null){
            minecraft.setScreen(parent);
            parent.onEntryEdited(message);
        }
    }
    private void openEnchantments(){
        if(loading||!direct||minecraft==null)return;
        minecraft.setScreen(new MilestoneLootEnchantScreen(
                this,table,revision,pool,entry,row.deepCopy()));
    }
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),c=col(),ix=x+12;
        addRenderableWidget(Button.builder(Component.literal("< Pools"),
                v->onClose()).bounds(ix,y+8,76,19).build());
        weightBox=new EditBox(font,ix,y+151,c-4,20,Component.literal("Weight"));
        weightBox.setMaxLength(4);weightBox.setValue(weight);
        weightBox.setResponder(value->{weight=value;refresh();});
        addRenderableWidget(weightBox);
        minBox=new EditBox(font,ix+c,y+151,c-4,20,Component.literal("Min count"));
        minBox.setMaxLength(2);minBox.setValue(minimum);
        minBox.setResponder(value->{minimum=value;refresh();});
        addRenderableWidget(minBox);
        maxBox=new EditBox(font,ix+c*2,y+151,c-4,20,Component.literal("Max count"));
        maxBox.setMaxLength(2);maxBox.setValue(maximum);
        maxBox.setResponder(value->{maximum=value;refresh();});
        addRenderableWidget(maxBox);
        weightButton=addRenderableWidget(Button.builder(Component.literal("Set Weight"),
                v->execute("set_weight")).bounds(ix,y+182,c-4,20).build());
        countButton=addRenderableWidget(Button.builder(Component.literal("Set Count"),
                v->execute("set_count")).bounds(ix+c,y+182,c-4,20).build());
        removeButton=addRenderableWidget(Button.builder(Component.literal("Remove Entry"),
                v->execute("remove_entry")).bounds(ix+c*2,y+182,c-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("Enchantments..."),
                v->openEnchantments()).bounds(ix,y+235,w-24,20).build())
                .active=!loading&&direct;
        updateButtons();
    }
    public void receive(String json){
        try{
            JsonObject response=JsonParser.parseString(json).getAsJsonObject();
            if(!response.has("action"))return;
            String action=response.get("action").getAsString();
            if(!action.equals("set_weight")&&!action.equals("set_count")
                    &&!action.equals("remove_entry"))return;
            loading=false;
            status=response.has("message")?response.get("message").getAsString():
                    "No response.";
            if(response.has("success")&&response.get("success").getAsBoolean()){
                if(minecraft!=null){
                    minecraft.setScreen(parent);
                    parent.onEntryEdited(status);
                }
                return;
            }
        }catch(RuntimeException error){loading=false;status="Invalid server response.";}
        refresh();
        clearWidgets();init();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw(),ix=x+12,c=col();
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,x+w,b(),0xFF30363D);
        g.fill(x,y,x+w,y+2,0xFF92A6BA);
        g.drawString(font,"EDIT ITEM ENTRY",x+101,y+14,0xFFFFE1B2);
        g.drawString(font,font.plainSubstrByWidth(table,w-26),
                ix,y+35,0xFFEEEEEE);
        g.drawString(font,"Pool "+(pool+1)+" | Entry "+(entry+1),
                ix,y+50,0xFFBDD1DC);
        g.fill(ix,y+72,x+w-12,y+111,0xFF414950);
        g.renderItem(icon(),ix+9,y+83);
        g.drawString(font,font.plainSubstrByWidth(icon().getHoverName().getString(),w-67),
                ix+33,y+84,0xFFFFE7B5);
        if(row.has("conditions")&&row.get("conditions").getAsBoolean())
            g.drawString(font,"Entry has extra conditions",ix+33,y+98,0xFFFFC99B);
        g.drawString(font,"Weight",ix,y+139,0xFFE2E2E2);
        g.drawString(font,"Min count",ix+c,y+139,0xFFE2E2E2);
        g.drawString(font,"Max count",ix+c*2,y+139,0xFFE2E2E2);
        if(ph()>273)
            g.drawString(font,countEditable?
                    "Change item properties below, then confirm to save.":
                    "Complex count functions are read-only.",
                    ix,y+215,0xFFBCD1DC);
        if(row.has("enchant_note"))
            g.drawString(font,font.plainSubstrByWidth(
                    "Enchants: "+row.get("enchant_note").getAsString(),w-26),
                    ix,y+267,0xFFBBD1E2);
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-26),
                ix,b()-15,0xFFFFD4A0);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
