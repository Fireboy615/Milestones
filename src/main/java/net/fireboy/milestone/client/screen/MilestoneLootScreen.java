package net.fireboy.milestone.client.screen;

import com.google.gson.*;
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
import java.util.ArrayList;
import java.util.List;

/** OP loot browser, category filters, item icons and two independent scrollers.
 * All data is server-sourced; all edits remain server permission-checked. */
public final class MilestoneLootScreen extends Screen {
    private static final String[] CATEGORIES={"chests","blocks","entities","fishing","other","all"};
    private static final String[] LABELS={"Chests","Blocks","Mobs","Fishing","Other","All"};
    private final Screen parent;
    private final List<String> ids=new ArrayList<>();
    private final List<String> itemPreview=new ArrayList<>();
    private String query="",category="chests",selected="";
    private JsonObject detail=new JsonObject(),inspected=new JsonObject();
    private String status="Loading chest loot tables...";
    private boolean waiting,initial=true,lootrLoaded;
    private String lastEditResult="";
    private boolean tableDrag,previewDrag;
    private int offset,desiredOffset,total,previewOffset;
    private EditBox searchBox;

    public MilestoneLootScreen(Screen parent) {
        super(Component.literal("Milestone Loot Tables"));
        this.parent=parent;
    }
    private int pw(){return Math.min(600,width-12);}
    private int ph(){return Math.min(410,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private int split(){return l()+pw()*48/100;}
    private int listTop(){return t()+107;}
    private int listRows(){return Math.min(8,Math.max(2,(ph()-171)/22));}
    private int tableBarX(){return split()-13;}
    private int previewTop(){return t()+153;}
    private int previewRows(){
        return Math.max(1,(ph()-219)/19);
    }
    private int previewBarX(){return l()+pw()-22;}
    private int previewBarH(){return previewRows()*19;}

    private void request(String action,String arg,int index) {
        if(minecraft==null||minecraft.getConnection()==null)return;
        waiting=true;
        PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(
                action,arg,"",index,0,0,0));
    }
    private void browse(int requested) {
        if(minecraft==null||minecraft.getConnection()==null)return;
        int step=listRows();
        desiredOffset=Math.max(0,requested/step*step);
        waiting=true;
        PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(
                "browse",query,category,desiredOffset,step,0,0));
    }
    private void chooseCategory(String next) {
        if(next.equals(category))return;
        category=next;
        offset=0;desiredOffset=0;previewOffset=0;
        selected="";detail=new JsonObject();itemPreview.clear();
        ids.clear();
        status="Searching "+category+" loot tables...";
        browse(0);rebuild();
    }
    private void describe(String id) {
        selected=id;previewOffset=0;
        detail=new JsonObject();itemPreview.clear();
        status="Reading "+id+"...";
        request("describe",id,0);
        rebuild();
    }
    private void rebuild(){clearWidgets();init();}
    public void onBonusAdded(String result){
        lastEditResult=result;
        request("describe",selected,0);
    }
    private static ItemStack icon(String raw) {
        ResourceLocation id=ResourceLocation.tryParse(raw);
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))return ItemStack.EMPTY;
        Item item=BuiltInRegistries.ITEM.get(id);
        return item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
    }
    private ItemStack typeIcon() {
        return new ItemStack(switch(category){
            case "blocks" -> Items.GRASS_BLOCK;
            case "entities" -> Items.ZOMBIE_HEAD;
            case "fishing" -> Items.FISHING_ROD;
            case "other" -> Items.BOOK;
            case "all" -> Items.CHEST;
            default -> Items.CHEST;
        });
    }
    @Override protected void init() {
        int x=l(),y=t(),w=pw(),dx=split()+8,dw=x+w-dx-10;
        addRenderableWidget(Button.builder(Component.literal("< Config"),
                v->onClose()).bounds(x+8,y+8,78,19).build());
        addRenderableWidget(Button.builder(Component.literal("Select Chest In World"),
                v->{status="Right-click a chest in the next 60 seconds.";request("arm","",0);})
                .bounds(x+w-178,y+8,170,19).build());

        int tabW=(w-22)/CATEGORIES.length;
        for(int i=0;i<CATEGORIES.length;i++){
            String choice=CATEGORIES[i];
            Button tab=addRenderableWidget(Button.builder(Component.literal(LABELS[i]),
                    v->chooseCategory(choice)).bounds(x+11+i*tabW,y+36,tabW-2,20).build());
            tab.active=!choice.equals(category);
        }
        searchBox=new EditBox(font,x+12,y+65,w-24,19,Component.literal("Filter loot tables"));
        searchBox.setMaxLength(110);
        searchBox.setValue(query);
        searchBox.setHint(Component.literal("Search IDs within selected category..."));
        searchBox.setResponder(value->{
            if(value.equals(query))return;
            query=value;selected="";detail=new JsonObject();itemPreview.clear();
            previewOffset=0;offset=0;
            browse(0);
        });
        addRenderableWidget(searchBox);

        int listW=split()-x-30;
        for(int i=0;i<Math.min(listRows(),ids.size());i++){
            String id=ids.get(i);
            int rowY=listTop()+i*22;
            addRenderableWidget(Button.builder(Component.empty(),
                    v->describe(id)).bounds(x+10,rowY,listW,20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"),
                v->browse(Math.max(0,offset-listRows())))
                .bounds(x+10,b()-45,32,20).build()).active=offset>0;
        addRenderableWidget(Button.builder(Component.literal(">"),
                v->browse(offset+listRows()))
                .bounds(x+103,b()-45,32,20).build()).active=offset+listRows()<total;

        int actionWidth=Math.max(30,(dw-12)/3);
        addRenderableWidget(Button.builder(Component.literal("+ Bonus"),
                v->{if(minecraft!=null&&!selected.isBlank())
                    minecraft.setScreen(new MilestoneLootAddEntryScreen(this,selected));})
                .bounds(dx,b()-44,actionWidth,21).build()).active=!selected.isBlank();
        addRenderableWidget(Button.builder(Component.literal("Edit Pools"),
                v->{if(minecraft!=null&&!selected.isBlank())
                    minecraft.setScreen(new MilestoneLootPoolScreen(this,selected));})
                .bounds(dx+actionWidth+6,b()-44,actionWidth,21).build())
                .active=!selected.isBlank();
        addRenderableWidget(Button.builder(Component.literal(
                        actionWidth>=82?"Enchant Rules":"Enchants"),
                v->{if(minecraft!=null&&!selected.isBlank())
                    minecraft.setScreen(new MilestoneLootEnchantRulesScreen(this,selected));})
                .bounds(dx+(actionWidth+6)*2,b()-44,dw-2*(actionWidth+6),21).build())
                .active=!selected.isBlank();
        if(initial){initial=false;browse(0);}
    }

    public void receive(String json){
        waiting=false;
        boolean restoreSearchFocus=searchBox!=null&&searchBox.isFocused();
        try{
            JsonObject message=JsonParser.parseString(json).getAsJsonObject();
            String action=message.get("action").getAsString();
            switch(action){
                case "arm" -> {
                    if(message.has("armed")&&message.get("armed").getAsBoolean()
                            &&minecraft!=null){
                        minecraft.setScreen(null);return;
                    }
                }
                case "browse" -> {
                    if(!query.equals(value(message,"query")) ||
                            !category.equals(value(message,"category")))return;
                    int actual=message.get("offset").getAsInt();
                    // Ignore late replies for an older position.
                    if(actual!=desiredOffset&&desiredOffset<message.get("total").getAsInt())
                        return;
                    ids.clear();
                    for(JsonElement item:message.getAsJsonArray("tables"))ids.add(item.getAsString());
                    offset=actual;total=message.get("total").getAsInt();
                    lootrLoaded=message.get("lootr").getAsBoolean();
                    status=total==0?"No matching "+category+" loot tables.":
                            "Showing "+(offset+1)+"-"+(offset+ids.size())+" of "+total;
                }
                case "inspect" -> {
                    inspected=message;
                    if(message.has("id")){
                        selected=message.get("id").getAsString();
                        status="Inspected "+value(message,"source");
                        request("describe",selected,0);
                    }else status=value(message,"error");
                }
                case "describe" -> {
                    // Discard a late detail reply after changing category/selection.
                    if(message.has("id")&&!value(message,"id").equals(selected))return;
                    detail=message;previewOffset=0;itemPreview.clear();
                    if(message.has("entries"))
                        for(JsonElement item:message.getAsJsonArray("entries"))
                            itemPreview.add(item.getAsString());
                    status=message.has("error")?value(message,"error"):
                            (lastEditResult.isBlank()?"Loaded "+selected:lastEditResult);
                    lastEditResult="";
                }
                case "add_bonus" -> {
                    status=value(message,"message");
                    if(message.has("success")&&message.get("success").getAsBoolean())
                        request("describe",selected,0);
                }
            }
        }catch(RuntimeException ex){status="Invalid server reply.";}
        rebuild();
        if(restoreSearchFocus&&searchBox!=null)setFocused(searchBox);
    }
    private static String value(JsonObject o,String key) {
        return o.has(key)?o.get(key).getAsString():"";
    }
    private void jumpTable(double y) {
        int index=MilestoneLootScrollbar.jump((int)y,listTop(),
                listRows()*22,total,listRows());
        int maxPage=total==0?0:(total-1)/listRows();
        int page=(int)Math.round(index*maxPage/
                (double)Math.max(1,total-listRows()))*listRows();
        if(page!=desiredOffset)browse(page);
    }
    private void jumpPreview(double y) {
        previewOffset=MilestoneLootScrollbar.jump((int)y,previewTop(),previewBarH(),
                itemPreview.size(),previewRows());
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(button==0 && mx>=tableBarX()-2&&mx<=tableBarX()+11
                &&my>=listTop()&&my<=listTop()+listRows()*22&&total>listRows()){
            tableDrag=true;jumpTable(my);return true;
        }
        if(button==0&&mx>=previewBarX()-2&&mx<=previewBarX()+11
                &&my>=previewTop()&&my<=previewTop()+previewBarH()
                &&itemPreview.size()>previewRows()){
            previewDrag=true;jumpPreview(my);return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button==0&&tableDrag){jumpTable(my);return true;}
        if(button==0&&previewDrag){jumpPreview(my);return true;}
        return super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button){
        if(button==0&&(tableDrag||previewDrag)){
            tableDrag=false;previewDrag=false;return true;
        }
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double delta){
        if(mx>=l()+7&&mx<split()&&my>=listTop()-6&&my<=b()-48){
            int pages=delta>0?-1:1;
            int next=Math.max(0,Math.min((Math.max(0,total-1)/listRows())*listRows(),
                    desiredOffset+pages*listRows()));
            if(next!=desiredOffset)browse(next);
            return true;
        }
        if(mx>split()&&mx<=l()+pw()-9&&my>=previewTop()&&my<previewTop()+previewBarH()){
            previewOffset=MilestoneLootScrollbar.clamp(
                    previewOffset+(delta>0?-2:2),itemPreview.size(),previewRows());
            return true;
        }
        return super.mouseScrolled(mx,my,horizontal,delta);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float tick){
        // Minecraft's vanilla blur must render BEFORE our opaque UI.
        super.render(g,mx,my,tick);
        int x=l(),y=t(),w=pw(),h=ph(),dx=split()+8,dw=x+w-dx-10;
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,x+w,y+h,0xFF34383B);
        g.fill(x,y,x+w,y+2,0xFFACB4BC);
        g.fill(split(),y+90,split()+1,b()-28,0xFF77818A);
        // The world-inspection button occupies the upper right on compact GUIs.
        g.drawString(font,w<460?"LOOT":"LOOT TABLES - OP",
                x+96,y+13,0xFFF2F2F2);
        g.drawString(font,category.equals("chests")?"Chest and container loot tables":
                "Category: "+category,x+12,y+92,0xFFD5E4F2);
        g.drawString(font,lootrLoaded?"Lootr detected":"Lootr optional",
                dx,y+92,lootrLoaded?0xFF97DA9C:0xFFBBBBBB);
        g.drawString(font,(total==0?"0":offset+1)+"-"+Math.min(total,offset+ids.size())+
                " / "+total,x+49,b()-40,0xFFDDDDDD);
        g.drawString(font,"Selected:",dx,y+105,0xFFE0E0E0);
        g.drawString(font,font.plainSubstrByWidth(
                selected.isBlank()?"(select a table)":selected,dw),dx,y+117,0xFFFFE09A);
        g.drawString(font,"Pools: "+(detail.has("pools")?value(detail,"pools"):"-")
                +"  Unique items: "+itemPreview.size(),dx,y+128,0xFFBBCDD7);
        g.drawString(font,"ITEM PREVIEW",dx,y+140,0xFFD9E0E4);
        int rowW=dw-15;
        for(int i=0;i<previewRows()&&previewOffset+i<itemPreview.size();i++){
            String id=itemPreview.get(previewOffset+i);
            ItemStack stack=icon(id);
            int yy=previewTop()+i*19;
            g.fill(dx,yy-1,dx+rowW,yy+18,i%2==0?0xFF3D454D:0xFF3A4147);
            if(!stack.isEmpty())g.renderItem(stack,dx+2,yy);
            else g.renderItem(new ItemStack(Items.BARRIER),dx+2,yy);
            String label=stack.isEmpty()?id:stack.getHoverName().getString();
            g.drawString(font,font.plainSubstrByWidth(label,rowW-25),dx+23,yy+5,0xFFEBECEE);
            if(mx>=dx&&mx<dx+rowW&&my>=yy&&my<yy+19)
                g.renderTooltip(font,Component.literal(id),mx,my);
        }
        MilestoneLootScrollbar.render(g,previewBarX(),previewTop(),previewBarH(),
                previewOffset,itemPreview.size(),previewRows());
        if(itemPreview.isEmpty())g.drawString(font,"No direct item entries shown.",
                dx,previewTop()+5,0xFFAFB9C2);
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-22),
                x+12,b()-16,0xFFFFD6A0);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,tick);
        // Draw item icons and labels AFTER row button backgrounds.
        ItemStack tableIcon=typeIcon();
        int listW=split()-x-30;
        for(int i=0;i<Math.min(listRows(),ids.size());i++){
            String id=ids.get(i);
            int yy=listTop()+i*22;
            if(id.equals(selected))g.fill(x+11,yy+1,x+10+listW-1,yy+19,0x558DB6C2);
            g.renderItem(tableIcon,x+15,yy+2);
            g.drawString(font,font.plainSubstrByWidth(id,listW-32),
                    x+36,yy+6,id.equals(selected)?0xFFFFDE9A:0xFFFAFAFA);
        }
        MilestoneLootScrollbar.render(g,tableBarX(),listTop(),listRows()*22,
                offset,total,listRows());
        for(int i=0;i<Math.min(listRows(),ids.size());i++){
            int yy=listTop()+i*22;
            if(mx>=x+11&&mx<split()-18&&my>=yy&&my<yy+20){
                g.renderTooltip(font,Component.literal(ids.get(i)),mx,my);
                break;
            }
        }
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
