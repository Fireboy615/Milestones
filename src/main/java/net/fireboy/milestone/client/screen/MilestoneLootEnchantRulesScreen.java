package net.fireboy.milestone.client.screen;

import com.google.gson.*;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;

/**
 * Table-wide, read-only enchantment rule viewer. Shows exactly which
 * function is present, where it applies, and its original JSON parameters.
 * Never generates loot or alters the datapack.
 */
public final class MilestoneLootEnchantRulesScreen extends Screen {
    private final Screen parent;
    private final String table;
    private final List<JsonObject> rules=new ArrayList<>();
    private final List<String> details=new ArrayList<>();
    private int page,offset,total,selected=-1,detailOffset;
    private boolean loading=true,dragging;
    private String status="Loading enchantment rules...";
    private String revision="",lastWrite="";
    private JsonObject selectedRule;

    public MilestoneLootEnchantRulesScreen(Screen parent,String table){
        super(Component.literal("View Loot Enchantment Rules"));
        this.parent=parent;this.table=table;
    }
    private int pw(){return Math.min(620,width-12);}
    private int ph(){return Math.min(420,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private int split(){return l()+pw()*45/100;}
    private int listY(){return t()+95;}
    private int listRows(){return Math.min(7,Math.max(2,(ph()-163)/25));}
    private int detailY(){return t()+161;}
    private int detailHeight(){return Math.max(16,b()-37-detailY());}
    private int detailVisible(){return Math.max(1,detailHeight()/11);}
    private int detailBarX(){return l()+pw()-21;}
    private static String val(JsonObject o,String key) {
        return o.has(key)?o.get(key).getAsString():"";
    }
    private String selectedSummary(){
        return selectedRule==null?"Choose a rule from the left.":val(selectedRule,"summary");
    }
    private void load(int requestedPage){
        page=Math.max(0,requestedPage);
        loading=true;
        if(minecraft==null||minecraft.getConnection()==null)return;
        PacketDistributor.sendToServer(new MilestoneLootNetwork.Request(
                "enchant_rules",table,"",page*listRows(),0,listRows(),0));
    }
    private void choose(int which){
        if(which<0||which>=rules.size())return;
        selected=which;
        selectedRule=rules.get(which);
        detailOffset=0;
        makeDetails();
    }
    /** Wrap raw JSON at the font's actual width; do not parse/modify it. */
    private void makeDetails(){
        details.clear();
        if(selectedRule==null)return;
        String raw=val(selectedRule,"details");
        int maxWidth=Math.max(60,pw()-((split()-l())+34));
        for(String line:raw.split("\n",-1)){
            if(line.isEmpty()){details.add("");continue;}
            String remaining=line.replace("\t","  ");
            while(!remaining.isEmpty()&&details.size()<300){
                String piece=font.plainSubstrByWidth(remaining,maxWidth);
                if(piece.isEmpty())break;
                details.add(piece);
                remaining=remaining.substring(piece.length());
            }
            if(details.size()>=300)break;
        }
        if(selectedRule.has("clipped")&&selectedRule.get("clipped").getAsBoolean())
            details.add("(Additional JSON omitted for safety.)");
    }
    public void onRuleEdited(String message){
        lastWrite=message;
        load(page);
        rebuild();
    }
    private void editRule(){
        if(loading||selectedRule==null||revision.isBlank()
                ||!selectedRule.has("editable")
                ||!selectedRule.get("editable").getAsBoolean()||minecraft==null)return;
        minecraft.setScreen(new MilestoneLootEnchantRuleEditScreen(
                this,table,revision,selectedRule));
    }
    private void rebuild(){clearWidgets();init();}
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),leftWidth=split()-x-24;
        addRenderableWidget(Button.builder(Component.literal("< Back"),
                button->onClose()).bounds(x+10,y+9,75,20).build());
        addRenderableWidget(Button.builder(Component.literal("Edit Rule"),
                button->editRule()).bounds(x+w-192,y+9,90,20).build())
                .active=!loading&&selectedRule!=null&&revision.length()==64
                    &&selectedRule.has("editable")
                    &&selectedRule.get("editable").getAsBoolean();
        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                button->{load(page);rebuild();})
                .bounds(x+w-94,y+9,83,20).build());
        for(int i=0;i<Math.min(listRows(),rules.size());i++){
            int which=i,yy=listY()+i*25;
            JsonObject row=rules.get(i);
            String name=val(row,"scope");
            addRenderableWidget(Button.builder(Component.literal(
                    font.plainSubstrByWidth(name,leftWidth-13)),
                    button->{choose(which);rebuild();})
                    .bounds(x+12,yy,leftWidth,21).build()).active=!loading;
        }
        int navY=listY()+listRows()*25+7;
        int buttonWidth=Math.max(30,(leftWidth-5)/2);
        addRenderableWidget(Button.builder(Component.literal("< Rules"),
                button->{load(page-1);rebuild();})
                .bounds(x+12,navY,buttonWidth,19).build()).active=!loading&&page>0;
        addRenderableWidget(Button.builder(Component.literal("Rules >"),
                button->{load(page+1);rebuild();})
                .bounds(x+17+buttonWidth,navY,buttonWidth,19).build())
                .active=!loading&&(page+1)*listRows()<total;
        if(loading&&rules.isEmpty())load(0);
    }
    public void receive(String json){
        try{
            JsonObject reply=JsonParser.parseString(json).getAsJsonObject();
            if(!"enchant_rules".equals(val(reply,"action"))
                    ||(reply.has("id")&&!table.equals(val(reply,"id"))))return;
            loading=false;
            if(reply.has("error")) {
                status=val(reply,"error");
                rules.clear();selectedRule=null;details.clear();
            }else{
                revision=reply.has("revision")?reply.get("revision").getAsString():"";
                total=reply.get("total").getAsInt();
                offset=reply.get("offset").getAsInt();
                page=offset/listRows();
                rules.clear();
                for(JsonElement item:reply.getAsJsonArray("rules"))
                    rules.add(item.getAsJsonObject());
                if(rules.isEmpty()){
                    selected=-1;selectedRule=null;details.clear();
                    status="No enchant-related functions in this table's JSON.";
                }else{
                    choose(0);
                    status=lastWrite.isBlank()
                            ?"Showing "+(offset+1)+"-"+(offset+rules.size())+
                                " of "+total+" rule(s)."
                            :lastWrite;
                    lastWrite="";
                }
                if(reply.has("truncated")&&reply.get("truncated").getAsBoolean())
                    status="First 1,000 rules only (inspection safety limit).";
            }
        }catch(RuntimeException ex){status="Could not read enchant rules.";loading=false;}
        rebuild();
    }
    private void scrollDetail(double mouseY){
        detailOffset=MilestoneLootScrollbar.jump((int)mouseY,detailY(),
                detailHeight(),details.size(),detailVisible());
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(button==0&&mx>=detailBarX()-2&&mx<=detailBarX()+11
                &&my>=detailY()&&my<=detailY()+detailHeight()
                &&details.size()>detailVisible()){
            dragging=true;
            scrollDetail(my);
            return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button==0&&dragging){
            scrollDetail(my);return true;
        }
        return super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button){
        if(button==0&&dragging){dragging=false;return true;}
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double delta){
        if(my>=listY()&&my<listY()+listRows()*25&&mx<split()){
            int requested=Math.max(0,Math.min(total==0?0:(total-1)/listRows(),
                    page+(delta>0?-1:1)));
            if(!loading&&requested!=page){load(requested);rebuild();}
            return true;
        }
        if(mx>=split()&&mx<l()+pw()&&my>=detailY()&&my<detailY()+detailHeight()){
            detailOffset=MilestoneLootScrollbar.clamp(detailOffset+
                    (delta>0?-3:3),details.size(),detailVisible());
            return true;
        }
        return super.mouseScrolled(mx,my,horizontal,delta);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw(),right=x+w,mid=split(),dx=mid+12,dw=right-dx-25;
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,right,b(),0xFF30363D);
        g.fill(x,y,right,y+2,0xFF94AABB);
        g.drawString(font,w>=450?"ENCHANTMENT RULES":"ENCHANT RULES",
                x+93,y+14,0xFFFFE1AC);
        g.drawString(font,font.plainSubstrByWidth(table,w-27),
                x+12,y+38,0xFFE4EFF5);
        g.drawString(font,"Table JSON - future loot generation",x+12,y+52,0xFFBBD0DE);
        g.fill(mid,y+76,mid+1,b()-30,0xFF607583);
        g.drawString(font,"ENCHANT FUNCTIONS",x+12,y+80,0xFFDAE6ED);
        g.drawString(font,"RULE DETAILS",dx,y+80,0xFFDAE6ED);
        g.drawString(font,selectedRule==null?"No rule selected":
                font.plainSubstrByWidth(val(selectedRule,"scope"),dw),
                dx,y+96,0xFFFFE3B2);
        if(selectedRule!=null){
            g.drawString(font,font.plainSubstrByWidth(val(selectedRule,"type"),dw),
                    dx,y+109,0xFFBDD7E2);
            String summary=selectedSummary();
            g.drawString(font,font.plainSubstrByWidth(summary,dw),
                    dx,y+124,0xFFFAFAFA);
            if(font.width(summary)>dw)
                g.drawString(font,font.plainSubstrByWidth(
                        summary.substring(font.plainSubstrByWidth(summary,dw).length()),dw),
                        dx,y+136,0xFFFAFAFA);
        }else
            g.drawString(font,"Nothing to display.",dx,y+114,0xFFBBD0DE);
        g.drawString(font,"EXACT LOOT FUNCTION JSON",dx,detailY()-11,0xFFCADDE8);
        g.fill(dx-2,detailY(),right-13,detailY()+detailHeight(),0xFF232B32);
        g.enableScissor(dx,detailY(),right-25,detailY()+detailHeight());
        for(int i=0;i<detailVisible()+1 &&detailOffset+i<details.size();i++){
            int yy=detailY()+3+i*11;
            g.drawString(font,details.get(detailOffset+i),dx+2,yy,
                    0xFFD5E3EC);
        }
        g.disableScissor();
        MilestoneLootScrollbar.render(g,detailBarX(),detailY(),detailHeight(),
                detailOffset,details.size(),detailVisible());
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-26),
                x+12,b()-17,0xFFFFD4A0);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
        for(int i=0;i<Math.min(listRows(),rules.size());i++){
            int yy=listY()+i*25;
            JsonObject row=rules.get(i);
            if(selected==i)
                g.fill(x+13,yy+1,mid-16,yy+20,0x448FAFCA);
            if(mx>=x+12&&mx<mid-23&&my>=yy&&my<yy+21)
                g.renderTooltip(font,Component.literal(
                        val(row,"scope")+" : "+val(row,"summary")),mx,my);
        }
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
