package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only full shop catalog, including items locked behind future Cores and
 * items currently outside the shuffled stock. Never grants items or credits.
 */
public final class MilestoneShopCatalogScreen extends Screen {
    private final Screen parent;
    private final List<JsonObject> catalog = new ArrayList<>();
    private final SmoothScroll scroll = new SmoothScroll();
    private List<JsonObject> results = List.of();
    private String search = "";
    private String selectedId;
    private boolean dragging;
    private EditBox searchBox;
    private Button back;

    public MilestoneShopCatalogScreen(Screen parent, JsonArray allOffers) {
        super(Component.literal("All Shop Items"));
        this.parent=parent;
        if(allOffers!=null)
            for(JsonElement entry:allOffers)catalog.add(entry.getAsJsonObject().deepCopy());
        filter();
    }
    private int pw(){return Math.min(445,width-14);}
    private int ph(){return Math.min(292,height-14);}
    private int left(){return (width-pw())/2;}
    private int top(){return (height-ph())/2;}
    private int right(){return left()+pw();}
    private int bottom(){return top()+ph();}
    private int split(){return left()+(pw()*57)/100;}
    private int listTop(){return top()+72;}
    private int listBottom(){return bottom()-17;}
    private int listH(){return Math.max(35,listBottom()-listTop());}
    private static final int ROW=27;
    private int rows(){return Math.max(1,listH()/ROW);}
    private int maximum(){return Math.max(0,results.size()-rows());}
    private int barX(){return split()-15;}
    private int trackH(){return rows()*ROW;}
    private int thumbH(){return Math.max(15,(int)Math.round(trackH()
            *Math.min(1.0,rows()/(double)Math.max(rows(),results.size()))));}
    private int thumbY(){return listTop()+(int)Math.round(
            (trackH()-thumbH())*scroll.value()/Math.max(1,maximum()));}
    private void filter(){
        String needle=search.toLowerCase(Locale.ROOT).trim();
        List<JsonObject> matched=new ArrayList<>();
        for(JsonObject offer:catalog){
            if(needle.isBlank()
                    || value(offer,"name").toLowerCase(Locale.ROOT).contains(needle)
                    || value(offer,"item").toLowerCase(Locale.ROOT).contains(needle)
                    || value(offer,"rarity").toLowerCase(Locale.ROOT).contains(needle)
                    || value(offer,"id").toLowerCase(Locale.ROOT).contains(needle))
                matched.add(offer);
        }
        results=List.copyOf(matched);
        scroll.jump(0,maximum());
    }
    private static String value(JsonObject entry,String key){
        return entry.has(key)?entry.get(key).getAsString():"";
    }
    private JsonObject selected(){
        for(JsonObject entry:catalog)
            if(value(entry,"id").equals(selectedId))return entry;
        return null;
    }
    private ItemStack stack(JsonObject row){
        ResourceLocation key=ResourceLocation.tryParse(value(row,"item"));
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return ItemStack.EMPTY;
        return new ItemStack(BuiltInRegistries.ITEM.get(key));
    }
    @Override protected void init(){
        back=addRenderableWidget(Button.builder(Component.literal("< Shop"),
                b->onClose()).bounds(left()+10,top()+9,74,19).build());
        searchBox=new EditBox(font,left()+12,top()+42,pw()-28,19,
                Component.literal("Search all shop items"));
        searchBox.setMaxLength(90);
        searchBox.setHint(Component.literal("Search names, rarity or mod IDs"));
        searchBox.setValue(search);
        searchBox.setResponder(value->{
            if(!value.equals(search)){search=value;filter();}
        });
        addRenderableWidget(searchBox);
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    private void scrollTo(double y){
        int travel=trackH()-thumbH();
        if(travel>0)scroll.jump((y-listTop()-thumbH()/2.0)*maximum()/travel,maximum());
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(super.mouseClicked(x,y,button))return true;
        if(button!=0)return false;
        if(maximum()>0&&x>=barX()-2&&x<barX()+12
                &&y>=listTop()&&y<=listTop()+trackH()){
            dragging=true;scrollTo(y);return true;
        }
        if(x>=left()+12&&x<barX()-2&&y>=listTop()&&y<listBottom()){
            int index=(int)Math.floor((y-listTop()+scroll.value()*ROW)/ROW);
            if(index>=0 && index<results.size()){
                selectedId=value(results.get(index),"id");
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&dragging){scrollTo(y);return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0&&dragging){dragging=false;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(y>=listTop()&&y<=listBottom()&&x<split()&&maximum()>0){
            scroll.wheel(dy,maximum());return true;
        }
        return super.mouseScrolled(x,y,dx,dy);
    }
    private void drawDetails(GuiGraphics graphics,JsonObject o){
        int x=split()+8,y=top()+76,w=right()-x-10;
        graphics.fill(x-4,y-4,right()-7,bottom()-16,0xFFC9C9C9);
        if(o==null){
            graphics.drawCenteredString(font,"Select an item",x+w/2,y+53,0xFF505050);
            return;
        }
        var item=stack(o);
        if(!item.isEmpty())graphics.renderItem(item,x+3,y+1);
        graphics.drawString(font,font.plainSubstrByWidth(value(o,"name"),w-30),
                x+24,y+3,0xFF232323);
        String rarity=value(o,"rarity");
        String rarityName=MilestoneRarityColors.label(rarity);
        graphics.drawString(font,"Rarity:",x+3,y+26,0xFF444444);
        int rx=x+43;
        graphics.fill(rx-2,y+23,rx+font.width(rarityName)+6,y+37,0xFF343434);
        graphics.drawString(font,rarityName,rx+2,y+26,MilestoneRarityColors.color(rarity));
        graphics.drawString(font,"Price: "+value(o,"price"),x+3,y+40,0xFF333333);
        graphics.drawString(font,"Amount: "+value(o,"count"),x+3,y+54,0xFF333333);

        int needed=o.has("requires_core")?o.get("requires_core").getAsInt():-1;
        boolean completed=o.has("unlocked")&&o.get("unlocked").getAsBoolean();
        graphics.drawString(font,"Unlock requirement:",x+3,y+77,0xFF333333);
        graphics.drawString(font,needed<0?"No Core required":
                "Complete Core "+(needed+1),x+3,y+91,0xFF333333);
        graphics.drawString(font,completed?"Unlocked":"Locked",
                x+3,y+105,completed?0xFF2C6B37:0xFF964132);
        boolean stocked=o.has("in_stock")&&o.get("in_stock").getAsBoolean();
        boolean bought=o.has("purchased")&&o.get("purchased").getAsBoolean();
        graphics.drawString(font,bought?"Bought this reset":
                    stocked?"In stock now":"Not in current stock",
                x+3,y+130,bought?0xFF6A5235:stocked?0xFF2C6B37:0xFF725B28);
        String desc=value(o,"description");
        if(!desc.isBlank()){
            int line=y+151;
            for(var part:font.split(Component.literal(desc),w-7)){
                if(line+10>=bottom()-18)break;
                graphics.drawString(font,part,x+3,line,0xFF555555);
                line+=11;
            }
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int l=left(),t=top(),w=pw(),h=ph();
        g.fill(0,0,width,height,0xFF171717);
        g.fill(l,t,l+w,t+h,0xFF333333);
        g.fill(l,t,l+w,t+1,0xFFA0A0A0);
        g.drawCenteredString(font,"All Shop Items",width/2,t+16,0xFFF4F4F4);
        scroll.update(maximum());
        g.enableScissor(l+10,listTop(),barX()-3,listBottom());
        for(int i=scroll.firstRow();i<Math.min(results.size(),
                scroll.firstRow()+rows()+2);i++){
            JsonObject o=results.get(i);
            int y=listTop()+i*ROW-(int)Math.round(scroll.value()*ROW);
            boolean chosen=value(o,"id").equals(selectedId);
            g.fill(l+12,y,barX()-4,y+ROW-2,chosen?0xFF83845B:0xFF6A6A6A);
            var item=stack(o);
            if(!item.isEmpty())g.renderItem(item,l+16,y+4);
            g.drawString(font,font.plainSubstrByWidth(value(o,"name"),barX()-l-43),
                    l+37,y+4,0xFFF5F5F5);
            String req=o.has("requires_core")&&o.get("requires_core").getAsInt()>=0
                    ?"Core "+(o.get("requires_core").getAsInt()+1):"No Core";
            String listRarity=value(o,"rarity");
            String listRarityName=MilestoneRarityColors.label(listRarity);
            g.fill(l+36,y+14,barX()-6,y+25,0xFF333333);
            g.drawString(font,listRarityName,l+39,y+16,
                    MilestoneRarityColors.color(listRarity));
            int reqX=l+43+font.width(listRarityName);
            if(reqX+font.width(req)<barX()-7)
                g.drawString(font,req,reqX,y+16,0xFFEAEAEA);
        }
        g.disableScissor();
        if(maximum()>0){
            g.fill(barX(),listTop(),barX()+8,listTop()+trackH(),0xFF191919);
            g.fill(barX()+1,thumbY(),barX()+7,thumbY()+thumbH(),0xFFDDDDDD);
        }
        drawDetails(g,selected());
        if(back!=null)back.render(g,mx,my,partial);
        if(searchBox!=null)searchBox.render(g,mx,my,partial);
        if(results.isEmpty())
            g.drawCenteredString(font,"No matching items",l+pw()/4,listTop()+18,0xFFDDDDDD);
    }
    @Override public boolean isPauseScreen(){return false;}
}
