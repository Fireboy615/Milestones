package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** OP shop editor: configurable item payments and up to eight independent
 * unlock requirements, each with its own server-wide or per-player scope. */
public final class MilestoneShopRequirementsScreen extends Screen {
    private final Screen parent;
    private final JsonObject offer;
    private final Runnable changed;
    private boolean unlocks;
    private int page;
    private static final int PAGE_SIZE=4;

    public MilestoneShopRequirementsScreen(Screen parent,JsonObject offer,Runnable changed) {
        super(Component.literal("Shop Requirements"));
        this.parent=parent;
        this.offer=offer;
        this.changed=changed;
        if(!offer.has("cost_items"))offer.add("cost_items",new JsonArray());
        if(!offer.has("unlock_conditions"))offer.add("unlock_conditions",new JsonArray());
    }
    private JsonArray rows() {
        return offer.getAsJsonArray(unlocks?"unlock_conditions":"cost_items");
    }
    private void touch() {changed.run();}
    private void build() {clearWidgets();init();}
    private int pw(){return Math.min(465,width-14);}
    private int ph(){return Math.min(303,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private void flip(boolean value){
        unlocks=value;page=0;build();
    }
    @Override protected void init() {
        int x=l(),y=t(),w=pw();
        addRenderableWidget(Button.builder(Component.literal("< Offer"),
                b->onClose()).bounds(x+10,y+9,72,19).build());
        addRenderableWidget(Button.builder(Component.literal("Item Payments"),
                b->flip(false)).bounds(x+88,y+9,107,19).build())
                .active=unlocks;
        addRenderableWidget(Button.builder(Component.literal("Unlock Conditions"),
                b->flip(true)).bounds(x+201,y+9,130,19).build())
                .active=!unlocks;
        JsonArray entries=rows();
        int maxPage=Math.max(0,(entries.size()-1)/PAGE_SIZE);
        page=Math.min(page,maxPage);
        Button add=addRenderableWidget(Button.builder(Component.literal("+ Add"),
                button->{
                    if(rows().size()>=8)return;
                    JsonObject row=new JsonObject();
                    if(unlocks){
                        row.addProperty("type","kill");
                        row.addProperty("target","minecraft:zombie");
                        row.addProperty("count",1);
                        row.addProperty("scope","individual");
                    }else {
                        row.addProperty("item","minecraft:diamond");
                        row.addProperty("count",1);
                    }
                    rows().add(row);
                    page=(rows().size()-1)/PAGE_SIZE;
                    touch();build();
                }).bounds(x+w-80,y+9,70,19).build());
        add.active=entries.size()<8;
        for(int i=page*PAGE_SIZE;i<Math.min(entries.size(),(page+1)*PAGE_SIZE);i++){
            final int index=i;
            JsonObject row=entries.get(i).getAsJsonObject();
            int rowY=y+70+(i%PAGE_SIZE)*46;
            if(unlocks) {
                String type=row.get("type").getAsString();
                addRenderableWidget(Button.builder(Component.literal(type.toUpperCase()),
                        button->{
                            String next=type.equals("kill")?"structure":
                                    type.equals("structure")?"advancement":"kill";
                            row.addProperty("type",next);
                            if(!next.equals("kill"))row.addProperty("count",1);
                            touch();build();
                        }).bounds(x+12,rowY,95,18).build());
                addRenderableWidget(Button.builder(Component.literal(
                        row.get("scope").getAsString().equals("global")?"GLOBAL":"INDIVIDUAL"),
                        button->{
                            row.addProperty("scope",row.get("scope").getAsString()
                                    .equals("global")?"individual":"global");
                            touch();build();
                        }).bounds(x+113,rowY,100,18).build());
            }else {
                // Costs are paid once per purchase, not progression unlocks.
                addRenderableWidget(Button.builder(Component.literal("Item payment"),
                        button->{}).bounds(x+12,rowY,201,18).build()).active=false;
            }
            addRenderableWidget(Button.builder(Component.literal("Delete"),
                    button->{rows().remove(index);touch();build();})
                    .bounds(x+w-77,rowY,65,18).build());
            int fieldY=rowY+20;
            EditBox id=new EditBox(font,x+12,fieldY,w-116,18,
                    Component.literal(unlocks?"Target registry ID":"Item registry ID"));
            id.setMaxLength(128);
            id.setValue(row.get(unlocks?"target":"item").getAsString());
            id.setHint(Component.literal(unlocks?"e.g. minecraft:ancient_city":"e.g. minecraft:diamond"));
            id.setResponder(value->{
                row.addProperty(unlocks?"target":"item",value.trim());
                touch();
            });
            addRenderableWidget(id);
            EditBox count=new EditBox(font,x+w-95,fieldY,84,18,
                    Component.literal(unlocks?"Required kills":"Items needed"));
            count.setMaxLength(6);
            count.setValue(row.get("count").getAsString());
            count.setResponder(value->{
                try {
                    int n=Integer.parseInt(value);
                    if(n>=1 && n<=(unlocks?100000:2304)
                            &&(!unlocks || row.get("type").getAsString().equals("kill") || n==1)){
                        row.addProperty("count",n);touch();
                    }
                }catch(NumberFormatException ignored){}
            });
            count.setHint(Component.literal("Count"));
            addRenderableWidget(count);
            if(unlocks && !row.get("type").getAsString().equals("kill"))count.active=false;
        }
        addRenderableWidget(Button.builder(Component.literal("<"),
                button->{page--;build();}).bounds(x+12,b()-30,26,18).build())
                .active=page>0;
        addRenderableWidget(Button.builder(Component.literal(">"),
                button->{page++;build();}).bounds(x+90,b()-30,26,18).build())
                .active=(page+1)*PAGE_SIZE<entries.size();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw();
        g.fill(0,0,width,height,0xE5101010);
        g.fill(x,y,x+w,b(),0xFF303030);
        g.fill(x,y,x+w,y+2,0xFF888888);
        g.drawString(font,unlocks
                ?"Each objective: type, scope, target, count"
                :"Each purchase consumes credits AND these inventory items.",
                x+12,y+38,0xFFF0F0F0);
        g.drawString(font,unlocks
                ?"Global means ANY player unlocks it for EVERYONE."
                :"Use full registry IDs; 1-2304 of each distinct item.",
                x+12,y+50,0xFFC4C4C4);
        if(rows().isEmpty())
            g.drawCenteredString(font,unlocks?"No extra unlock requirements."
                    :"No item payment (credits only).",width/2,y+112,0xFFCCCCCC);
        g.drawString(font,"Page "+(page+1)+" / "+Math.max(1,(rows().size()+PAGE_SIZE-1)/PAGE_SIZE),
                x+44,b()-26,0xFFEEEEEE);
        g.drawString(font,"Maximum 8 per category | Back to offer when finished",
                x+135,b()-26,0xFFBBBBBB);
        for(var child:children())
            if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.visible)widget.render(g,mx,my,partial);
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
