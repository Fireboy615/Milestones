package net.fireboy.milestone.client.screen;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * Vanilla-style feature unlock editor. Changes mutate the parent config draft
 * only; parent Save/Cancel commits/discards the complete configuration.
 */
public final class MilestoneUnlocksScreen extends Screen {
    private static final int PANEL = 0xFF333333;
    private static final int DIM = 0xC8101010;
    private static final int TEXT = 0xFFEAEAEA;
    private final MilestoneConfigScreen parent;
    private final JsonObject draft;
    private final JsonArray features;
    private boolean editing;
    private int selected = -1;
    private int scroll;
    private int confirmDelete = -1;
    private boolean dragging;
    private final SmoothWidgetScroll smoothRows=new SmoothWidgetScroll();
    private String notice = "";
    private final List<EditBox> inputs = new ArrayList<>();

    public MilestoneUnlocksScreen(MilestoneConfigScreen parent, JsonObject draft) {
        super(Component.literal("Milestone Unlocks"));
        this.parent=parent;
        this.draft=draft;
        JsonObject cores=draft.getAsJsonObject("cores");
        if(!cores.has("features"))cores.add("features",new JsonArray());
        features=cores.getAsJsonArray("features");
    }
    private JsonObject feature() {
        return selected>=0 && selected<features.size()
                ? features.get(selected).getAsJsonObject():null;
    }
    private int pw() { return Math.min(450,width-20); }
    private int ph() { return Math.min(344,height-12); }
    private int left() { return (width-pw())/2; }
    private int top() { return (height-ph())/2; }
    private int bottom() { return top()+ph(); }
    private int rowY() { return top()+80; }
    private int rowHeight() { return 27; }
    private int capacity() { return Math.max(1,(ph()-146)/rowHeight()); }
    private int count() { return editing ? 7 : features.size(); }
    private int maxScroll() { return Math.max(0,count()-capacity()); }
    private void setScroll(double value) {
        smoothRows.drag(value,maxScroll());
        if(scroll!=smoothRows.first()){scroll=smoothRows.first();rebuild();}
    }
    private void mark() { parent.markConfigDirty(); notice="Unsaved changes - Save on main page"; }

    @Override protected void init() {
        inputs.clear();
        int l=left(),t=top(),w=pw(),b=bottom(),m=width/2;
        addRenderableWidget(Button.builder(Component.literal(editing ? "< Features" : "< Config"),
                button -> onClose()).bounds(l+11,t+11,90,20).build());
        if(!editing) {
            addRenderableWidget(Button.builder(Component.literal("+ Add Feature"),
                    button -> addFeature()).bounds(l+12,t+46,w-33,22).build())
                    .active=features.size()<80;
            scroll=smoothRows.first();
            for(int i=scroll;i<Math.min(features.size(),scroll+capacity()+1);i++){
                int index=i, y=rowY()+(i-scroll)*rowHeight();
                JsonObject item=features.get(i).getAsJsonObject();
                String label=item.get("name").getAsString()+" (Core "+
                        (item.get("unlock_core").getAsInt()+1)+")";
                addRenderableWidget(Button.builder(Component.literal(
                        font.plainSubstrByWidth(label,w-128)),
                        button->{selected=index;editing=true;scroll=0;smoothRows.reset();confirmDelete=-1;rebuild();})
                        .bounds(l+12,y,w-113,22).build());
                addRenderableWidget(Button.builder(Component.literal(
                        confirmDelete==index?"Confirm?":"Delete"),
                        button -> {
                            if(confirmDelete==index) {
                                features.remove(index);mark();
                                confirmDelete=-1;scroll=Math.min(scroll,maxScroll());
                            } else {confirmDelete=index;notice="Click Confirm? again to remove";}
                            rebuild();
                        }).bounds(l+w-95,y,74,22).build());
            }
            return;
        }
        JsonObject f=feature();
        if(f==null) {editing=false;rebuild();return;}
        String[] labels={"Feature ID","Display name","Unlock after Core","Lock recipes",
                "Recipe IDs (comma-separated)","Lock machine use","Machine block IDs"};
        // Seven rows; rows 2/3/5 are buttons and the rest use text fields.
        int visible=capacity();
        int fields=labels.length;
        scroll=smoothRows.first();
        for(int i=scroll;i<Math.min(fields,scroll+visible+1);i++){
            int row=i,y=rowY()+(i-scroll)*rowHeight();
            if(row==2){
                int coreId=f.get("unlock_core").getAsInt();
                addRenderableWidget(Button.builder(Component.literal("Unlock after Core "+(coreId+1)),
                        button -> cycleCore()).bounds(l+15,y,w-48,22).build());
            }else if(row==3 || row==5) {
                String key=row==3?"lock_recipes":"lock_machine_use";
                boolean on=f.get(key).getAsBoolean();
                String label=(row==3?"Lock recipes":"Lock machine use")+": "+(on?"ON":"OFF");
                addRenderableWidget(Button.builder(Component.literal(label),
                    button->{f.addProperty(key,!on);mark();rebuild();})
                        .bounds(l+15,y,w-48,22).build());
            }else{
                String key=switch(row){case 0->"id";case 1->"name";case 4->"recipes";default->"machine_blocks";};
                int labelSize=Math.min(130,(w-35)/3);
                boolean canBrowse=key.equals("recipes") || key.equals("machine_blocks");
                int boxW=w-labelSize-(canBrowse?135:55);
                EditBox box=new EditBox(font,l+18+labelSize,y,boxW,20,
                        Component.literal(labels[row]));
                box.setMaxLength(row>=4?3000:80);
                box.setValue(fieldValue(f,key));
                box.setResponder(value->{setField(f,key,value);mark();});
                addRenderableWidget(box);
                inputs.add(box);
                if(canBrowse) {
                    String chosenKey=key;
                    addRenderableWidget(Button.builder(Component.literal("Browse"),
                        button->browseIds(chosenKey))
                        .bounds(l+w-97,y,78,20).build());
                }
            }
        }
    }
    private String fieldValue(JsonObject f,String key) {
        JsonElement el=f.get(key);
        if(el==null)return "";
        if(!el.isJsonArray())return el.getAsString();
        StringJoiner joiner=new StringJoiner(", ");
        for(JsonElement value:el.getAsJsonArray())joiner.add(value.getAsString());
        return joiner.toString();
    }
    private void setField(JsonObject f,String key,String value) {
        if(key.equals("recipes") || key.equals("machine_blocks")) {
            JsonArray array=new JsonArray();
            for(String str:value.split(",")){
                String v=str.trim();
                if(!v.isEmpty())array.add(v);
            }
            f.add(key,array);
        }else f.addProperty(key,value);
    }
    private void browseIds(String key) {
        if(minecraft==null || feature()==null)return;
        var kind=key.equals("recipes")?MilestoneIdPickerScreen.Kind.RECIPE
                :MilestoneIdPickerScreen.Kind.BLOCK;
        List<String> ids=new ArrayList<>();
        if(feature().has(key)) for(JsonElement entry:feature().getAsJsonArray(key))
            ids.add(entry.getAsString());
        minecraft.setScreen(new MilestoneIdPickerScreen(this,kind,true,ids,List.of(),
                chosen -> {
                    JsonArray array=new JsonArray();
                    for(String id:chosen)array.add(id);
                    feature().add(key,array);
                    mark();
                }));
    }

    private void addFeature() {
        int n=1;Set<String> used=new HashSet<>();
        for(JsonElement e:features)used.add(e.getAsJsonObject().get("id").getAsString());
        while(used.contains("feature_"+n))n++;
        JsonObject item=new JsonObject();
        item.addProperty("id","feature_"+n);
        item.addProperty("name","New Feature "+n);
        JsonArray cores=draft.getAsJsonObject("cores").getAsJsonArray("cores");
        int tier=cores.isEmpty()?0:cores.get(0).getAsJsonObject().get("tier").getAsInt();
        item.addProperty("unlock_core",tier);
        item.addProperty("lock_recipes",false);
        item.addProperty("lock_machine_use",false);
        item.add("recipes",new JsonArray());
        item.add("machine_blocks",new JsonArray());
        features.add(item);
        selected=features.size()-1;editing=true;scroll=0;smoothRows.reset();mark();rebuild();
    }
    private void cycleCore() {
        JsonObject f=feature();
        if(f==null)return;
        List<Integer> tiers=new ArrayList<>();
        for(JsonElement e:draft.getAsJsonObject("cores").getAsJsonArray("cores"))
            tiers.add(e.getAsJsonObject().get("tier").getAsInt());
        Collections.sort(tiers);
        if(tiers.isEmpty())return;
        int at=tiers.indexOf(f.get("unlock_core").getAsInt());
        f.addProperty("unlock_core",tiers.get((at+1)%tiers.size()));
        mark();rebuild();
    }
    private void rebuild() {clearWidgets();init();smoothRows.invalidate();}
    @Override public void onClose() {
        if(editing){editing=false;scroll=0;smoothRows.reset();confirmDelete=-1;rebuild();}
        else if(minecraft!=null)minecraft.setScreen(parent);
    }
    private int barX(){return left()+pw()-17;}
    private int barHeight(){return capacity()*rowHeight();}
    private int thumbSize(){return Math.max(16,(int)Math.round(barHeight()*capacity()/(double)Math.max(1,count())));}
    private int thumbY(){return rowY()+(int)Math.round((barHeight()-thumbSize())*smoothRows.value()/(double)Math.max(1,maxScroll()));}
    private void scrollTo(double y){
        int track=barHeight()-thumbSize();
        if(track>0)setScroll((y-rowY()-thumbSize()/2.0)*maxScroll()/track);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&maxScroll()>0&&x>=barX()-2&&x<=barX()+11
                &&y>=rowY()&&y<=rowY()+barHeight()) {
            dragging=true;scrollTo(y);return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(button==0&&dragging){scrollTo(y);return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(button==0&&dragging){dragging=false;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy) {
        if(maxScroll()>0){smoothRows.wheel(dy,maxScroll());return true;}
        return super.mouseScrolled(x,y,dx,dy);
    }
    @Override public void render(GuiGraphics gfx,int mouseX,int mouseY,float partial) {
        int l=left(),t=top(),w=pw(),h=ph();
        smoothRows.configure(l+11,l+w-13,rowY(),rowHeight(),capacity());
        smoothRows.advance(maxScroll(),this::rebuild);
        smoothRows.captureIfChanged(children());
        smoothRows.beforeStatic();
        super.render(gfx,mouseX,mouseY,partial);
        gfx.fill(0,0,width,height,0xFF171717);
        gfx.fill(l,t,l+w,t+h,PANEL);
        gfx.fill(l,t,l+w,t+1,0xFF999999);
        for(var child:children())
            if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.visible)widget.render(gfx,mouseX,mouseY,partial);
        smoothRows.renderRows(gfx,mouseX,mouseY,partial);
        renderMachineBlockIcon(gfx);
        gfx.drawCenteredString(font,editing?"Edit Feature":"Feature Unlocks",width/2,t+18,TEXT);
        if(editing) {
            String[] label={"ID","Name","Linked Core","Recipes","Recipe IDs","Machines","Block IDs"};
            gfx.enableScissor(l+11,rowY(),l+w-13,rowY()+capacity()*rowHeight());
            for(int i=scroll;i<Math.min(label.length,scroll+capacity()+1);i++) {
                if(i==2||i==3||i==5)continue;
                gfx.drawString(font,label[i],l+18,
                    rowY()+(i-scroll)*rowHeight()-smoothRows.pixels()+6,0xFFCCCCCC);
            }
            gfx.disableScissor();
            gfx.drawCenteredString(font,"IDs: minecraft:name or modid:name",
                    width/2,bottom()-77,0xFFBBBBBB);
        } else {
            gfx.drawCenteredString(font,"Both locks can be ON or OFF independently.",
                    width/2,bottom()-77,0xFFBBBBBB);
        }
        gfx.drawCenteredString(font,font.plainSubstrByWidth(notice,w-30),
                width/2,bottom()-48,0xFFFFD18C);
        gfx.drawCenteredString(font,"Back, then Save on the main config menu.",
                width/2,bottom()-33,0xFFBBBBBB);
        if(maxScroll()>0) {
            gfx.fill(barX(),rowY(),barX()+9,rowY()+barHeight(),0xFF171717);
            gfx.fill(barX()+1,thumbY(),barX()+8,thumbY()+thumbSize(),0xFFC2C2C2);
        }
    }
    private void renderMachineBlockIcon(GuiGraphics graphics) {
        if(!editing || feature()==null)return;
        JsonObject f=feature();
        if(!f.has("machine_blocks") || f.getAsJsonArray("machine_blocks").isEmpty())return;
        String first=f.getAsJsonArray("machine_blocks").get(0).getAsString();
        var stack=MilestoneItemIcons.block(first);
        if(stack.isEmpty())return;
        int index=6; // The machine block ID list.
        if(index<scroll || index>=scroll+capacity()+1)return;
        int labelSize=Math.min(130,(pw()-35)/3);
        int x=left()+labelSize;
        int y=rowY()+(index-scroll)*rowHeight()-smoothRows.pixels()+3;
        graphics.enableScissor(left()+11,rowY(),left()+pw()-13,
                rowY()+capacity()*rowHeight());
        graphics.renderItem(stack,x,y);
        graphics.disableScissor();
    }
    @Override public boolean isPauseScreen(){return false;}
}
