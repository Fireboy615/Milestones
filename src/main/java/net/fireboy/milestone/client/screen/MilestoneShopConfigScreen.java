package net.fireboy.milestone.client.screen;

import com.google.gson.*;
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

import java.util.*;

/**
 * Shop offer editor: compact searchable rows, auto-filled item display names,
 * smooth scrolling, and an opaque sharp background. Save/Cancel belongs to the
 * parent Milestone config screen; nothing is written until the parent Save.
 */
public final class MilestoneShopConfigScreen extends Screen {
    private static final String[] FIELDS={
            "id","name","description","item","count","price","rarity","requires_core"
    };
    private static final List<String> RARITIES=
            List.of("common","uncommon","rare","epic","legendary");
    private static final Map<String,Integer> DEFAULT_LIMITS=
            Map.of("common",6,"uncommon",5,"rare",4,"epic",3,"legendary",2);
    private static final String[] LABELS={
            "Offer ID","Display name","Description","Item","Amount","Price","Rarity","Unlock after Core"
    };
    private final MilestoneConfigScreen parent;
    private final JsonObject draft;
    private final JsonObject shop;
    private final JsonArray offers;
    private final SmoothWidgetScroll smoothRows=new SmoothWidgetScroll();
    private boolean editing;
    private boolean editingLimits;
    private int selected=-1;
    private int scroll;
    private int confirmDelete=-1;
    private boolean dragging;
    private EditBox earnBox;
    private EditBox searchBox;
    private String search="";
    private String status="";

    public MilestoneShopConfigScreen(MilestoneConfigScreen parent,JsonObject draft) {
        super(Component.literal("Configure Shop"));
        this.parent=parent;
        this.draft=draft;
        JsonObject root=draft.getAsJsonObject("cores");
        if(!root.has("shop")) {
            JsonObject created=new JsonObject();
            created.addProperty("credits_per_contribution",10);
            created.add("offers",new JsonArray());
            root.add("shop",created);
        }
        shop=root.getAsJsonObject("shop");
        if(!shop.has("offers"))shop.add("offers",new JsonArray());
        if(!shop.has("credits_per_contribution"))shop.addProperty("credits_per_contribution",10);
        if(!shop.has("max_per_rarity")) {
            JsonObject limits=new JsonObject();
            for(String rarity:RARITIES)
                limits.addProperty(rarity,DEFAULT_LIMITS.get(rarity));
            shop.add("max_per_rarity",limits);
        }
        offers=shop.getAsJsonArray("offers");
    }

    private JsonObject offer() {
        return selected>=0&&selected<offers.size()?offers.get(selected).getAsJsonObject():null;
    }
    private int pw(){return Math.min(462,width-18);}
    private int ph(){return Math.min(340,height-12);}
    private int left(){return (width-pw())/2;}
    private int top(){return (height-ph())/2;}
    private int rowTop(){return top()+(editing?48:86);}
    private int rowHeight(){return editing?24:23;}
    private int rowsFit(){return Math.max(1,(top()+ph()-rowTop()-10)/rowHeight());}
    private List<Integer> filtered() {
        String needle=search.trim().toLowerCase(Locale.ROOT);
        List<Integer> matching=new ArrayList<>();
        for(int i=0;i<offers.size();i++) {
            JsonObject o=offers.get(i).getAsJsonObject();
            if(needle.isEmpty() || contains(o,"name",needle) || contains(o,"id",needle)
                    || contains(o,"item",needle) || contains(o,"rarity",needle))
                matching.add(i);
        }
        return matching;
    }
    private static boolean contains(JsonObject o,String key,String query){
        return o.has(key)&&o.get(key).getAsString().toLowerCase(Locale.ROOT).contains(query);
    }
    private int length(){return editingLimits?0:editing?FIELDS.length:filtered().size();}
    private int maxScroll(){return Math.max(0,length()-rowsFit());}
    private void mark(){parent.markConfigDirty();status="";}
    private void jump(double index){
        smoothRows.drag(index,maxScroll());
        if(scroll!=smoothRows.first()){scroll=smoothRows.first();rebuild();}
    }
    private void rebuild(){clearWidgets();init();smoothRows.invalidate();}
    private int cap(String rarity) {
        JsonObject limits=shop.getAsJsonObject("max_per_rarity");
        return limits.has(rarity)?limits.get(rarity).getAsInt():DEFAULT_LIMITS.get(rarity);
    }
    private void setCap(String rarity,int value) {
        if(value<0||value>64)return;
        shop.getAsJsonObject("max_per_rarity").addProperty(rarity,value);
        mark();
    }
    private void changeCap(String rarity,int offset) {
        setCap(rarity,Math.max(0,Math.min(64,cap(rarity)+offset)));
        rebuild();
    }

    private static String itemName(String itemId) {
        ResourceLocation key=ResourceLocation.tryParse(itemId);
        if(key==null || !BuiltInRegistries.ITEM.containsKey(key))return null;
        var item=BuiltInRegistries.ITEM.get(key);
        if(item==Items.AIR)return null;
        return new ItemStack(item).getHoverName().getString();
    }
    private void selectItem(String itemId){
        JsonObject o=offer();
        if(o==null)return;
        o.addProperty("item",itemId);
        String title=itemName(itemId);
        if(title!=null)o.addProperty("name",title);
        mark();
    }

    @Override protected void init() {
        int l=left(),t=top(),w=pw();
        addRenderableWidget(Button.builder(Component.literal(
                        (editing || editingLimits)?"< Offers":"< Config"),
                b->onClose()).bounds(l+12,t+10,84,20).build());

        if(editingLimits) {
            for(int i=0;i<RARITIES.size();i++) {
                String rarity=RARITIES.get(i);
                int y=t+67+i*38;
                EditBox amount=new EditBox(font,l+238,y,56,20,
                        Component.literal(rarity+" offer limit"));
                amount.setMaxLength(2);
                amount.setValue(Integer.toString(cap(rarity)));
                amount.setResponder(value->{
                    try {
                        int number=Integer.parseInt(value);
                        if(number>=0&&number<=64)setCap(rarity,number);
                        else status="Maximum must be between 0 and 64";
                    }catch(NumberFormatException ignored) { }
                });
                addRenderableWidget(amount);
                addRenderableWidget(Button.builder(Component.literal("-"),
                        b->changeCap(rarity,-1)).bounds(l+301,y,25,20).build());
                addRenderableWidget(Button.builder(Component.literal("+"),
                        b->changeCap(rarity,1)).bounds(l+332,y,25,20).build());
            }
            return;
        }

        if(!editing){
            earnBox=new EditBox(font,l+w-90,t+37,76,19,
                    Component.literal("Credits per contribution"));
            earnBox.setMaxLength(5);
            earnBox.setValue(shop.get("credits_per_contribution").getAsString());
            earnBox.setResponder(value->{
                try{
                    int amount=Integer.parseInt(value);
                    if(amount<0||amount>10000){
                        status="Credit reward must be 0-10000";return;
                    }
                    shop.addProperty("credits_per_contribution",amount);
                    mark();
                }catch(NumberFormatException ex){status="Enter a whole number";}
            });
            addRenderableWidget(earnBox);
            addRenderableWidget(Button.builder(Component.literal("Stock Limits"),
                    b->{
                        editingLimits=true;status="";
                        smoothRows.reset();rebuild();
                    }).bounds(l+w-203,t+37,99,19).build());

            searchBox=new EditBox(font,l+12,t+61,w-162,19,
                    Component.literal("Search offers"));
            searchBox.setMaxLength(90);
            searchBox.setHint(Component.literal("Search name, item ID, rarity..."));
            searchBox.setValue(search);
            searchBox.setResponder(value->{
                if(value.equals(search))return;
                search=value;
                smoothRows.reset();
                scroll=0;
                confirmDelete=-1;
                rebuild();
                if(searchBox!=null)searchBox.setCursorPosition(search.length());
            });
            addRenderableWidget(searchBox);
            setInitialFocus(searchBox);
            addRenderableWidget(Button.builder(Component.literal("+ Add Offer"),
                    b->addOffer()).bounds(l+w-145,t+61,130,19).build())
                    .active=offers.size()<64;

            List<Integer> matches=filtered();
            scroll=Math.min(smoothRows.first(),maxScroll());
            for(int pos=scroll;pos<Math.min(matches.size(),scroll+rowsFit()+1);pos++){
                int index=matches.get(pos);
                JsonObject o=offers.get(index).getAsJsonObject();
                int y=rowTop()+(pos-scroll)*rowHeight();
                String label=o.get("name").getAsString()+"  •  "
                        +o.get("price").getAsInt()+" credits";
                addRenderableWidget(Button.builder(Component.literal(
                        font.plainSubstrByWidth(label,w-138)),
                        b->{
                            selected=index;editing=true;scroll=0;smoothRows.reset();rebuild();
                        }).bounds(l+12,y,w-113,19).build());
                addRenderableWidget(Button.builder(Component.literal(
                        confirmDelete==index?"Confirm?":"Delete"),
                        b->{
                            if(confirmDelete==index){
                                offers.remove(index);confirmDelete=-1;mark();
                                smoothRows.drag(Math.min(smoothRows.value(),maxScroll()),maxScroll());
                            } else {
                                confirmDelete=index;
                            }
                            rebuild();
                        }).bounds(l+w-97,y,80,19).build());
            }
            return;
        }

        JsonObject o=offer();
        if(o==null){editing=false;smoothRows.reset();rebuild();return;}
        scroll=Math.min(smoothRows.first(),maxScroll());
        for(int i=scroll;i<Math.min(FIELDS.length,scroll+rowsFit()+1);i++){
            int y=rowTop()+(i-scroll)*rowHeight();
            String key=FIELDS[i];
            if(key.equals("rarity")){
                String value=o.has("rarity")?o.get("rarity").getAsString():"common";
                addRenderableWidget(Button.builder(
                        Component.literal("Rarity: ").append(MilestoneRarityColors.styled(value)),
                        b->cycleRarity()).bounds(l+149,y,w-175,19).build());
            }else if(key.equals("requires_core")){
                int tier=o.get("requires_core").getAsInt();
                addRenderableWidget(Button.builder(Component.literal(
                        tier<0?"Available immediately":"Requires Core "+(tier+1)),
                        b->cycleCore()).bounds(l+149,y,w-175,19).build());
            }else{
                int x=l+149,inputW=w-176;
                boolean picker=key.equals("item");
                EditBox box=new EditBox(font,x,y,picker?inputW-78:inputW,19,
                        Component.literal(LABELS[i]));
                box.setMaxLength(key.equals("description")?240:picker?128:80);
                box.setValue(o.has(key)?o.get(key).getAsString():"");
                box.setResponder(value->{
                    if(key.equals("count") || key.equals("price")){
                        try{
                            int count=Integer.parseInt(value);
                            if(count<1||count>(key.equals("count")?64:1000000)){
                                status="Invalid "+key;return;
                            }
                            o.addProperty(key,count);mark();
                        }catch(NumberFormatException ex){status="Enter a whole number";}
                    }else if(picker) {
                        selectItem(value);
                    } else {
                        o.addProperty(key,value);mark();
                    }
                });
                addRenderableWidget(box);
                if(picker)
                    addRenderableWidget(Button.builder(Component.literal("Browse"),
                            b->browseItems()).bounds(x+inputW-75,y,73,19).build());
            }
        }
    }

    private void browseItems(){
        if(minecraft==null || offer()==null)return;
        String current=offer().get("item").getAsString();
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                MilestoneIdPickerScreen.Kind.ITEM,false,List.of(current),List.of(),
                chosen->{
                    if(!chosen.isEmpty())selectItem(chosen.get(0));
                }));
    }
    private void addOffer(){
        if(offers.size()>=64)return;
        int next=1;
        Set<String> used=new HashSet<>();
        for(JsonElement e:offers)used.add(e.getAsJsonObject().get("id").getAsString());
        while(used.contains("offer_"+next))next++;
        JsonObject o=new JsonObject();
        o.addProperty("id","offer_"+next);
        o.addProperty("item","minecraft:iron_ingot");
        o.addProperty("name",Objects.requireNonNullElse(itemName("minecraft:iron_ingot"),"Iron Ingot"));
        o.addProperty("description","");
        o.addProperty("count",1);
        o.addProperty("price",10);
        o.addProperty("rarity","common");
        o.addProperty("requires_core",-1);
        offers.add(o);
        selected=offers.size()-1;
        editing=true;scroll=0;smoothRows.reset();mark();rebuild();
    }
    private void cycleRarity(){
        JsonObject o=offer();
        if(o==null)return;
        List<String> values=List.of("common","uncommon","rare","epic","legendary");
        String now=o.has("rarity")?o.get("rarity").getAsString():"common";
        o.addProperty("rarity",values.get((values.indexOf(now)+1)%values.size()));
        mark();rebuild();
    }
    private void cycleCore(){
        JsonObject o=offer();
        if(o==null)return;
        List<Integer> cores=new ArrayList<>();
        cores.add(-1);
        for(JsonElement core:draft.getAsJsonObject("cores").getAsJsonArray("cores"))
            cores.add(core.getAsJsonObject().get("tier").getAsInt());
        Collections.sort(cores);
        int current=o.get("requires_core").getAsInt();
        o.addProperty("requires_core",cores.get((cores.indexOf(current)+1)%cores.size()));
        mark();rebuild();
    }

    @Override public void onClose(){
        if(editingLimits){
            editingLimits=false;status="";scroll=0;smoothRows.reset();rebuild();
        }else if(editing){
            editing=false;selected=-1;scroll=0;smoothRows.reset();rebuild();
        }else if(minecraft!=null)minecraft.setScreen(parent);
    }
    private int trackX(){return left()+pw()-16;}
    private int trackH(){return rowsFit()*rowHeight();}
    private int thumbH(){return Math.max(15,(int)Math.round(trackH()*rowsFit()
            /(double)Math.max(1,length())));}
    private int thumbY(){return rowTop()+(int)Math.round((trackH()-thumbH())
            *smoothRows.value()/Math.max(1,maxScroll()));}
    private void dragTo(double y){
        int available=trackH()-thumbH();
        if(available>0)jump((y-rowTop()-thumbH()/2.0)*maxScroll()/available);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0&&maxScroll()>0&&x>=trackX()-2&&x<=trackX()+11
                &&y>=rowTop()&&y<=rowTop()+trackH()){
            dragging=true;dragTo(y);return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&dragging){dragTo(y);return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0&&dragging){dragging=false;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(maxScroll()>0){smoothRows.wheel(dy,maxScroll());return true;}
        return super.mouseScrolled(x,y,dx,dy);
    }

    /** Item thumbnails are drawn after the animated row buttons at their true
     * on-screen positions; scissor matches the smooth list viewport. */
    private void renderOfferIcons(GuiGraphics graphics) {
        graphics.enableScissor(left()+11,rowTop(),left()+pw()-13,
                rowTop()+rowsFit()*rowHeight());
        if(!editing) {
            List<Integer> matches=filtered();
            int first=smoothRows.first();
            for(int position=first;position<Math.min(matches.size(),first+rowsFit()+1);position++) {
                JsonObject entry=offers.get(matches.get(position)).getAsJsonObject();
                if(!entry.has("item"))continue;
                var stack=MilestoneItemIcons.item(entry.get("item").getAsString());
                if(!stack.isEmpty()) {
                    int y=rowTop()+(position-first)*rowHeight()-smoothRows.pixels();
                    graphics.renderItem(stack,left()+18,y+2);
                }
            }
        } else if(offer()!=null) {
            var o=offer();
            var stack=MilestoneItemIcons.item(o.get("item").getAsString());
            if(!stack.isEmpty()) {
                for(int row: new int[]{1,3}) { // Display name and Item ID
                    int first=smoothRows.first();
                    if(row<first || row>=first+rowsFit()+1)continue;
                    int y=rowTop()+(row-first)*rowHeight()-smoothRows.pixels();
                    graphics.renderItem(stack,left()+126,y+2);
                }
            }
        }
        graphics.disableScissor();
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        int l=left(),t=top(),w=pw(),h=ph();
        if(!editingLimits) {
            smoothRows.configure(l+11,l+w-13,rowTop(),rowHeight(),rowsFit());
            smoothRows.advance(maxScroll(),()->{
                scroll=smoothRows.first();rebuild();
            });
            smoothRows.captureIfChanged(children());
            smoothRows.beforeStatic();
        }

        // Render vanilla widgets first; the dark world cover and our whole
        // interface are drawn afterward so the game background cannot blur them.
        super.render(g,mx,my,partial);
        g.fill(0,0,width,height,0xFF171717);
        g.fill(l,t,l+w,t+h,0xFF303030);
        g.fill(l,t,l+w,t+1,0xFF888888);

        for(var child:children())
            if(child instanceof AbstractWidget widget && widget.visible)
                widget.render(g,mx,my,partial);
        if(!editingLimits) {
            smoothRows.renderRows(g,mx,my,partial);
            renderOfferIcons(g);
        }

        String heading=editingLimits?"Stock Limits":editing?"Edit Shop Offer":"Shop Offers";
        g.drawCenteredString(font,status.isBlank()?heading:
                font.plainSubstrByWidth(status,w-204),width/2,t+16,
                status.isBlank()?0xFFFFFFFF:0xFFFFCC88);
        if(editingLimits){
            g.drawCenteredString(font,"Maximum offers per rarity each reset",width/2,t+42,0xFFCCCCCC);
            for(int i=0;i<RARITIES.size();i++){
                String rarity=RARITIES.get(i);
                int y=t+67+i*38;
                g.drawString(font,MilestoneRarityColors.label(rarity),
                        l+41,y+6,MilestoneRarityColors.color(rarity));
            }
            g.drawCenteredString(font,"0 = none  |  Stock shuffles every reset",
                    width/2,t+h-32,0xFFBDBDBD);
        }else if(!editing){
            g.drawString(font,"Credits per contribution",l+13,t+41,0xFFCCCCCC);
            if(filtered().isEmpty())
                g.drawCenteredString(font,offers.isEmpty()?"No offers yet":
                        "No offers match your search.",width/2,rowTop()+12,0xFFBEBEBE);
        }else{
            g.enableScissor(l+11,rowTop(),l+w-13,rowTop()+rowsFit()*rowHeight());
            for(int i=scroll;i<Math.min(FIELDS.length,scroll+rowsFit()+1);i++)
                g.drawString(font,LABELS[i],l+15,
                        rowTop()+(i-scroll)*rowHeight()-smoothRows.pixels()+5,0xFFCCCCCC);
            g.disableScissor();
        }
        if(!editingLimits && maxScroll()>0){
            g.fill(trackX(),rowTop(),trackX()+9,rowTop()+trackH(),0xFF151515);
            g.fill(trackX()+1,thumbY(),trackX()+8,thumbY()+thumbH(),0xFFBDBDBD);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
}
