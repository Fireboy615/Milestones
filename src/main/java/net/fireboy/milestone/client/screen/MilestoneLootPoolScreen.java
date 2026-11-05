package net.fireboy.milestone.client.screen;

import com.google.gson.*;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;

/** One job per screen: browse pools here, edit existing entries in a
 * dedicated form, or add entries with the existing item-picker form. */
public final class MilestoneLootPoolScreen extends Screen {
    private final Screen parent;
    private final String table;
    private final List<JsonObject> rows=new ArrayList<>();
    private String revision="",status="Loading pool entries...",lastWrite="";
    private String rolls="";
    private int pool,page,poolTotal,entryTotal,selectedEntry=-1;
    private JsonObject selectedRow;
    private boolean loading=true,staged,confirmRestore,dragging;

    public MilestoneLootPoolScreen(Screen parent,String table){
        super(Component.literal("Loot Pool Editor"));
        this.parent=parent;this.table=table;
    }
    private int pw(){return Math.min(565,width-12);}
    private int ph(){return Math.min(354,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private int listTop(){return t()+101;}
    private int visibleRows(){return Math.max(2,Math.min(6,(ph()-166)/24));}
    private int barX(){return l()+pw()-24;}
    private int barH(){return visibleRows()*24;}
    private static ItemStack icon(JsonObject row){
        if(!row.has("direct_item")||!row.get("direct_item").getAsBoolean())
            return new ItemStack(Items.BOOK);
        ResourceLocation id=ResourceLocation.tryParse(row.get("name").getAsString());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))
            return new ItemStack(Items.BARRIER);
        ItemStack stack=new ItemStack(BuiltInRegistries.ITEM.get(id));
        return stack.isEmpty()?new ItemStack(Items.BARRIER):stack;
    }
    private void send(String action,String token,int targetPool,int arg){
        if(minecraft==null||minecraft.getConnection()==null)return;
        loading=true;
        PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(
                action,table,token,targetPool,arg,visibleRows(),0));
    }
    private void load(){
        confirmRestore=false;
        selectedEntry=-1;selectedRow=null;
        send("pool_page","",pool,page);
        if(!revision.isBlank())rebuild();
    }
    private void rebuild(){clearWidgets();init();}
    private void select(JsonObject row){
        selectedEntry=row.get("index").getAsInt();
        selectedRow=row;
        confirmRestore=false;rebuild();
    }
    private void editSelected(){
        if(loading||selectedRow==null||minecraft==null)return;
        minecraft.setScreen(new MilestoneLootEditEntryScreen(this,table,revision,pool,
                selectedRow.deepCopy(),entryTotal>1));
    }
    private void addEntry(){
        if(loading||revision.isBlank()||poolTotal==0||minecraft==null)return;
        minecraft.setScreen(new MilestoneLootAddEntryScreen(this,table,revision,pool));
    }
    private void restore(){
        if(loading||!staged||revision.isBlank())return;
        if(!confirmRestore){
            confirmRestore=true;
            status="Click Restore again to remove Milestone's override.";
            rebuild();return;
        }
        confirmRestore=false;
        status="Restoring original loot table...";
        send("restore_override",revision,0,0);
        rebuild();
    }
    public void onEntryAdded(String message){
        lastWrite=message;
        page=Math.max(0,entryTotal/visibleRows());
        load();
    }
    public void onEntryEdited(String message){
        lastWrite=message;
        load();
    }
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),inside=x+12,inner=w-24;
        addRenderableWidget(Button.builder(Component.literal("< Tables"),
                v->onClose()).bounds(inside,y+8,78,19).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                v->load()).bounds(x+w-87,y+8,74,19).build());

        addRenderableWidget(Button.builder(Component.literal("< Pool"),
                v->{pool=Math.max(0,pool-1);page=0;load();})
                .bounds(inside,y+65,76,20).build()).active=!loading&&pool>0;
        addRenderableWidget(Button.builder(Component.literal("Pool >"),
                v->{pool=Math.min(poolTotal-1,pool+1);page=0;load();})
                .bounds(inside+80,y+65,76,20).build())
                .active=!loading&&pool+1<poolTotal;
        addRenderableWidget(Button.builder(Component.literal("< Page"),
                v->{page=Math.max(0,page-1);load();})
                .bounds(x+w-173,y+65,76,20).build())
                .active=!loading&&page>0;
        addRenderableWidget(Button.builder(Component.literal("Page >"),
                v->{page++;load();})
                .bounds(x+w-92,y+65,78,20).build())
                .active=!loading&&(page+1)*visibleRows()<entryTotal;

        for(int i=0;i<Math.min(visibleRows(),rows.size());i++){
            JsonObject row=rows.get(i);
            int yy=listTop()+i*24;
            addRenderableWidget(Button.builder(Component.empty(),
                    v->select(row)).bounds(inside,yy,inner-13,21).build())
                    .active=!loading;
        }
        int half=(inner-6)/2;
        addRenderableWidget(Button.builder(Component.literal("+ Add Item"),
                v->addEntry()).bounds(inside,b()-60,half,20).build())
                .active=!loading&&poolTotal>0;
        addRenderableWidget(Button.builder(Component.literal("Edit Selected"),
                v->editSelected()).bounds(inside+half+6,b()-60,inner-half-6,20).build())
                .active=!loading&&selectedRow!=null;
        addRenderableWidget(Button.builder(Component.literal(confirmRestore
                        ?"CONFIRM Restore Original":"Restore Original Table"),
                v->restore()).bounds(inside,b()-35,inner,19).build())
                .active=!loading&&staged;
        if(revision.isBlank()&&loading)send("pool_page","",pool,page);
    }
    public void receive(String json){
        try{
            JsonObject reply=JsonParser.parseString(json).getAsJsonObject();
            String action=reply.get("action").getAsString();
            if("pool_page".equals(action)){
                loading=false;
                if(reply.has("error")){
                    status=reply.get("error").getAsString();
                }else{
                    rows.clear();
                    for(JsonElement item:reply.getAsJsonArray("rows"))
                        rows.add(item.getAsJsonObject());
                    revision=reply.get("revision").getAsString();
                    poolTotal=reply.get("pool_total").getAsInt();
                    pool=reply.has("pool")?reply.get("pool").getAsInt():0;
                    entryTotal=reply.get("entry_total").getAsInt();
                    page=reply.get("entry_offset").getAsInt()/visibleRows();
                    staged=reply.has("staged")&&reply.get("staged").getAsBoolean();
                    rolls=reply.has("rolls")?reply.get("rolls").getAsString():"";
                    status=lastWrite.isBlank()
                            ?"Select an item to edit; complex entries stay read-only."
                            :lastWrite;
                    lastWrite="";
                }
            }else if("restore_override".equals(action)){
                loading=false;
                status=reply.has("message")?reply.get("message").getAsString():"No result.";
                if(reply.has("success")&&reply.get("success").getAsBoolean()){
                    lastWrite=status;
                    load();
                    return;
                }
            }else return;
        }catch(RuntimeException error){
            loading=false;status="Invalid server response.";
        }
        rebuild();
    }
    private void jump(double y){
        int index=MilestoneLootScrollbar.jump((int)y,listTop(),barH(),
                entryTotal,visibleRows());
        int maxPage=entryTotal==0?0:(entryTotal-1)/visibleRows();
        int target=(int)Math.round(index*maxPage/
                (double)Math.max(1,entryTotal-visibleRows()));
        if(target!=page){page=target;load();}
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(button==0&&!loading&&entryTotal>visibleRows()
                &&mx>=barX()-2&&mx<=barX()+11
                &&my>=listTop()&&my<=listTop()+barH()){
            dragging=true;jump(my);return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button==0&&dragging){jump(my);return true;}
        return super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button){
        if(button==0&&dragging){dragging=false;return true;}
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double delta){
        if(!loading&&mx>=l()+12&&mx<l()+pw()-12
                &&my>=listTop()&&my<listTop()+barH()){
            int maxPage=entryTotal==0?0:(entryTotal-1)/visibleRows();
            int target=Math.max(0,Math.min(maxPage,page+(delta>0?-1:1)));
            if(target!=page){page=target;load();}
            return true;
        }
        return super.mouseScrolled(mx,my,horizontal,delta);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw();
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,x+w,b(),0xFF30363D);
        g.fill(x,y,x+w,y+2,0xFF9AACBD);
        g.drawString(font,"LOOT POOLS",x+103,y+14,0xFFFFE0AC);
        g.drawString(font,font.plainSubstrByWidth(table,w-25),
                x+12,y+38,0xFFF2F2F2);
        g.drawString(font,"Pool "+(poolTotal==0?0:pool+1)+"/"+poolTotal+
                " | Rolls: "+font.plainSubstrByWidth(rolls,120),
                x+12,y+53,0xFFBDCFDB);
        g.drawString(font,"ENTRIES ("+entryTotal+")",x+12,y+91,0xFFD9E4EE);
        g.drawString(font,(page+1)+"/"+
                Math.max(1,(entryTotal+visibleRows()-1)/visibleRows()),
                x+w-72,y+91,0xFFD9E4EE);
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-25),
                x+12,b()-11,0xFFFFD5A2);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
        for(int i=0;i<Math.min(rows.size(),visibleRows());i++){
            JsonObject row=rows.get(i);
            int yy=listTop()+i*24;
            int idx=row.get("index").getAsInt();
            if(selectedEntry==idx)
                g.fill(x+13,yy+1,x+w-29,yy+20,0x5583B3C9);
            ItemStack stack=icon(row);
            g.renderItem(stack,x+17,yy+2);
            String id=row.get("name").getAsString();
            boolean direct=row.has("direct_item")&&row.get("direct_item").getAsBoolean();
            String label=direct&&stack.getItem()!=Items.BARRIER
                    ?stack.getHoverName().getString():id;
            g.drawString(font,font.plainSubstrByWidth(label,w-132),
                    x+41,yy+6,selectedEntry==idx?0xFFFFE2AB:0xFFEAEAEA);
            int enchanted=row.has("enchantments")&&row.get("enchantments").isJsonObject()
                    ?row.getAsJsonObject("enchantments").size():0;
            g.drawString(font,direct?"w:"+row.get("weight").getAsString()
                    +(enchanted>0?"  E:"+enchanted:""):"Complex",
                    x+w-103,yy+6,0xFFC0CBD3);
            if(mx>=x+13&&mx<x+w-32&&my>=yy&&my<yy+21)
                g.renderTooltip(font,Component.literal(id),mx,my);
        }
        MilestoneLootScrollbar.render(g,barX(),listTop(),barH(),
                page*visibleRows(),entryTotal,visibleRows());
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
