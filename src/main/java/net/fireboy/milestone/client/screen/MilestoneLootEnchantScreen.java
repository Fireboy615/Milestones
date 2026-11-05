package net.fireboy.milestone.client.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Operator-only fixed enchant editor. Registry IDs come from the client's
 * synced enchantment registry; edits are revalidated against the server. */
public final class MilestoneLootEnchantScreen extends Screen {
    private record Choice(String id,String name,int maximum) {
        boolean matches(String text) {
            String lower=text.toLowerCase(Locale.ROOT);
            return id.contains(lower)||name.toLowerCase(Locale.ROOT).contains(lower);
        }
    }
    private final MilestoneLootEditEntryScreen parent;
    private final String table,revision;
    private final int pool,entry;
    private final JsonObject fixed;
    private final boolean editable;
    private final String reason;
    private final List<Choice> all=new ArrayList<>();
    private String selected="",query="",level="1";
    private String status="Select an enchantment; changes require confirmation.";
    private String pending="";
    private boolean waiting;
    private int candidatePage,existingPage;
    private EditBox searchBox,levelBox;
    private Button setButton,removeButton;

    public MilestoneLootEnchantScreen(MilestoneLootEditEntryScreen parent,String table,
            String revision,int pool,int entry,JsonObject row) {
        super(Component.literal("Edit Loot Enchantments"));
        this.parent=parent;this.table=table;this.revision=revision;
        this.pool=pool;this.entry=entry;
        this.fixed=row.has("enchantments")&&row.get("enchantments").isJsonObject()
                ?row.getAsJsonObject("enchantments").deepCopy():new JsonObject();
        this.editable=row.has("enchant_editable")&&row.get("enchant_editable").getAsBoolean();
        this.reason=row.has("enchant_note")?row.get("enchant_note").getAsString():"";
        if(!editable)status="Read-only: "+reason;
        var game=net.minecraft.client.Minecraft.getInstance();
        if(game.level!=null)
            game.level.registryAccess().registry(Registries.ENCHANTMENT).ifPresent(reg->{
                for(ResourceLocation id:reg.keySet()){
                    var enchant=reg.get(id);
                    if(enchant!=null)
                        all.add(new Choice(id.toString(),
                                enchant.description().getString(),enchant.getMaxLevel()));
                }
            });
        all.sort(Comparator.comparing(Choice::name));
        if(!fixed.entrySet().isEmpty())
            pick(fixed.entrySet().iterator().next().getKey());
        else if(!all.isEmpty())
            pick(all.get(0).id());
    }
    private int pw(){return Math.min(550,width-12);}
    private int ph(){return Math.min(350,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private int half(){return (pw()-32)/2;}
    private int listY(){return t()+91;}
    private int rows(){return Math.max(2,Math.min(7,(ph()-164)/23));}
    private List<Choice> results(){
        List<Choice> list=new ArrayList<>();
        for(Choice choice:all)if(choice.matches(query))list.add(choice);
        return list;
    }
    private List<String> existing(){return new ArrayList<>(fixed.keySet());}
    private Choice chosen(){
        for(Choice choice:all)if(choice.id().equals(selected))return choice;
        return null;
    }
    private void pick(String id) {
        selected=id;
        Choice chosen=chosen();
        int old=fixed.has(id)?fixed.get(id).getAsInt():1;
        level=Integer.toString(Math.max(1,Math.min(old,chosen==null?255:chosen.maximum())));
        pending="";
    }
    private boolean validLevel(){
        Choice choice=chosen();
        if(choice==null)return false;
        try{
            int n=Integer.parseInt(level.trim());
            return n>=1&&n<=choice.maximum();
        }catch(NumberFormatException ex){return false;}
    }
    private void updateButtons(){
        if(setButton!=null)setButton.active=!waiting&&editable&&validLevel();
        if(removeButton!=null)removeButton.active=!waiting&&editable&&fixed.has(selected);
    }
    private void rebuild(){clearWidgets();init();}
    private void execute(boolean remove){
        if(waiting||!editable||(remove?!fixed.has(selected):!validLevel()))return;
        String next=remove?"remove":"set";
        if(!next.equals(pending)){
            pending=next;
            status=remove?"Confirm removing "+selected+" from this loot entry.":
                    "Confirm setting "+selected+" to level "+level+".";
            rebuild();return;
        }
        waiting=true;pending="";updateButtons();
        status="Saving fixed enchantments...";
        PacketDistributor.sendToServer(new MilestoneLootNetwork.EnchantRequest(
                table,revision,selected,pool,entry,
                validLevel()?Integer.parseInt(level.trim()):1,remove));
    }
    private void changePage(boolean available,int change){
        int count=available?results().size():existing().size();
        int maximum=count==0?0:(count-1)/rows();
        if(available)candidatePage=Math.max(0,Math.min(maximum,candidatePage+change));
        else existingPage=Math.max(0,Math.min(maximum,existingPage+change));
        rebuild();
    }
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),inside=x+12,part=half(),r=rows();
        addRenderableWidget(Button.builder(Component.literal("< Edit Item"),
                button->onClose()).bounds(inside,y+8,95,19).build());
        searchBox=new EditBox(font,x+part+18,y+56,part-13,19,
                Component.literal("Find enchantment"));
        searchBox.setMaxLength(80);
        searchBox.setValue(query);
        searchBox.setHint(Component.literal("Search enchantments..."));
        searchBox.setResponder(text->{
            if(text.equals(query))return;
            query=text;candidatePage=0;pending="";rebuild();
            if(searchBox!=null)setFocused(searchBox);
        });
        addRenderableWidget(searchBox);
        List<String> have=existing();
        int first=existingPage*r;
        for(int i=first;i<Math.min(first+r,have.size());i++){
            String id=have.get(i);
            String title=displayName(id)+" "+fixed.get(id).getAsInt();
            addRenderableWidget(Button.builder(Component.literal(
                    font.plainSubstrByWidth(title,part-19)),
                    button->{pick(id);rebuild();})
                    .bounds(inside,listY()+(i-first)*23,part-7,20).build());
        }
        List<Choice> matches=results();
        int from=candidatePage*r;
        for(int i=from;i<Math.min(from+r,matches.size());i++){
            Choice choice=matches.get(i);
            addRenderableWidget(Button.builder(Component.literal(
                    font.plainSubstrByWidth(choice.name(),part-18)),
                    button->{pick(choice.id());rebuild();})
                    .bounds(x+part+18,listY()+(i-from)*23,part-12,20).build());
        }
        int navY=listY()+r*23+2;
        addRenderableWidget(Button.builder(Component.literal("< Saved"),
                button->changePage(false,-1)).bounds(inside,navY,(part-11)/2,18).build())
                .active=existingPage>0;
        addRenderableWidget(Button.builder(Component.literal("Saved >"),
                button->changePage(false,1))
                .bounds(inside+(part-11)/2+3,navY,(part-11)/2,18).build())
                .active=(existingPage+1)*r<have.size();
        addRenderableWidget(Button.builder(Component.literal("< More"),
                button->changePage(true,-1))
                .bounds(x+part+18,navY,(part-16)/2,18).build())
                .active=candidatePage>0;
        addRenderableWidget(Button.builder(Component.literal("More >"),
                button->changePage(true,1))
                .bounds(x+part+21+(part-16)/2,navY,(part-16)/2,18).build())
                .active=(candidatePage+1)*r<matches.size();
        levelBox=new EditBox(font,x+53,b()-68,58,19,Component.literal("Enchantment level"));
        levelBox.setMaxLength(3);levelBox.setValue(level);
        levelBox.setResponder(text->{level=text;pending="";updateButtons();});
        addRenderableWidget(levelBox);
        setButton=addRenderableWidget(Button.builder(Component.literal(
                pending.equals("set")?"CONFIRM Level":"Set / Add Enchant"),
                button->execute(false)).bounds(inside,b()-41,part-7,20).build());
        removeButton=addRenderableWidget(Button.builder(Component.literal(
                pending.equals("remove")?"CONFIRM Remove":"Remove Enchant"),
                button->execute(true)).bounds(x+part+18,b()-41,part-12,20).build());
        updateButtons();
    }
    private String displayName(String id){
        for(Choice choice:all)if(choice.id().equals(id))return choice.name();
        return id;
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double delta){
        if(my>=listY()&&my<listY()+rows()*23){
            changePage(mx>=l()+pw()/2,delta>0?-1:1);
            return true;
        }
        return super.mouseScrolled(mx,my,horizontal,delta);
    }
    public void receive(String json){
        try{
            JsonObject reply=JsonParser.parseString(json).getAsJsonObject();
            if(!reply.has("action")||!"edit_enchant".equals(
                    reply.get("action").getAsString()))return;
            waiting=false;
            status=reply.has("message")?reply.get("message").getAsString():
                    "No server response.";
            if(reply.has("success")&&reply.get("success").getAsBoolean()){
                if(minecraft!=null){
                    minecraft.setScreen(parent);
                    parent.onEnchantChanged(status);
                }
                return;
            }
        }catch(RuntimeException error){
            waiting=false;status="Invalid response.";
        }
        pending="";rebuild();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw(),part=half(),inside=x+12;
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,x+w,b(),0xFF30363D);
        g.fill(x,y,x+w,y+2,0xFF94AABB);
        g.drawString(font,"FIXED ENCHANTMENTS",x+108,y+13,0xFFFFE1AC);
        g.drawString(font,font.plainSubstrByWidth(table,w-25),
                inside,y+32,0xFFE3EDF3);
        g.drawString(font,"EXISTING ("+fixed.size()+")",inside,y+78,0xFFDDE8EF);
        g.drawString(font,"AVAILABLE ("+results().size()+")",
                x+part+18,y+45,0xFFDDE8EF);
        g.fill(x+part+7,y+79,x+part+8,b()-91,0xFF697783);
        if(!editable)
            g.drawString(font,font.plainSubstrByWidth("Read-only: "+reason,part-14),
                    inside,y+62,0xFFFFBB9B);
        else{
            Choice choice=chosen();
            g.drawString(font,font.plainSubstrByWidth("Selected: "+
                    (choice==null?selected:choice.name()),part-14),
                    inside,y+62,0xFFE4DBB5);
        }
        g.drawString(font,"Level",inside,b()-62,0xFFE2E2E2);
        Choice choice=chosen();
        g.drawString(font,choice==null?"Unknown enchantment":
                "Maximum level: "+choice.maximum(),x+121,b()-62,0xFFBDCFDB);
        g.drawString(font,font.plainSubstrByWidth("Status: "+status,w-24),
                inside,b()-14,0xFFFFD4A0);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
        if(!selected.isBlank()){
            int rowStart=listY(),r=rows();
            List<String> have=existing();
            for(int i=existingPage*r;i<Math.min(have.size(),(existingPage+1)*r);i++){
                if(have.get(i).equals(selected)){
                    int yy=rowStart+(i-existingPage*r)*23;
                    g.fill(inside+2,yy+2,x+part-1,yy+18,0x4489B4D0);
                }
            }
        }
        if(mx>=x+part+18&&mx<x+w-12&&my>=listY()
                &&my<listY()+rows()*23){
            int i=candidatePage*rows()+(my-listY())/23;
            List<Choice> shown=results();
            if(i>=0&&i<shown.size())
                g.renderTooltip(font,Component.literal(shown.get(i).id()),mx,my);
        }
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
