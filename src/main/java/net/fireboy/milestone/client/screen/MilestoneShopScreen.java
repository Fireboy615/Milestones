package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneShopNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Resonant Exchange. Icon-only item slots on the left, selected offer details on
 * the right, highlighted selection, a draggable scrollbar and a reserved strip.
 * All money, rotation, reservation and purchase checks are performed server-side.
 */
public final class MilestoneShopScreen extends Screen {
    private static final int FRAME = 0xFF141414;
    private static final int PANEL = 0xFFB5B5B5;
    private static final int INSIDE = 0xFFC9C9C9;
    private static final int INFO_PANEL = 0xFFE1E1E1;
    private static final int INFO_INK = 0xFF202020;
    private static final int INFO_MUTED = 0xFF383838;
    private static final int HEADER_BG = 0xFF33383D;
    private static final int HEADER_TEXT = 0xFFF5F5F5;
    private static final int FONT = 0xFF292929;
    private static final int MUTED = 0xFF646464;
    private static final int GOLD = 0xFFFFDF59;
    private static final int SLOT = 25;
    private static final int STEP = 28;
    // Narrow, consistent bands: dark label strip above each item row.
    private static final int HEADER_HEIGHT = 15;
    private static final int SECTION_PAD = 6;
    private static final int SCROLL_UNIT = 8;
    private static final String[] RARITY_IDS =
            {"common", "uncommon", "rare", "epic", "legendary"};
    private static final String[] RARITY_LABELS =
            {"Common", "Uncommon", "Rare", "Epic", "Legendary"};

    private static final int[] DEFAULT_LIMITS = {6,5,4,3,2};

    private final BlockPos exchange;
    private JsonArray catalog = new JsonArray();
    private List<List<JsonObject>> rarityRows = List.of();
    private List<JsonObject> reservedOffers = List.of();
    private JsonArray allOffers = new JsonArray();
    private final int[] slotLimits = DEFAULT_LIMITS.clone();
    // The whole stock area scrolls vertically when rarity limits use multiple
    // rows. Individual rarity categories do not scroll sideways.
    private final SmoothScroll verticalScroll = new SmoothScroll();
    private String selectedId;
    private int balance;
    private int lifetime;
    private boolean draggingScrollbar;
    private boolean waiting;
    private boolean firstLoad = true;
    private long resetSeconds;
    private long clockTicks;
    private String status = "Loading shop...";
    private Button reserveButton;
    private Button buyButton;
    private Button closeButton;
    private Button refreshButton;
    private Button catalogButton;
    private Button rerollButton;
    private boolean canReroll;

    public MilestoneShopScreen(BlockPos pos) {
        super(Component.literal("Resonant Exchange"));
        exchange = pos.immutable();
    }

    private int pw() { return Math.min(438,width-14); }
    private int ph() { return Math.min(292,height-14); }
    private int l() { return (width-pw())/2; }
    private int t() { return (height-ph())/2; }
    private int b() { return t()+ph(); }
    private int leftX() { return l()+12; }
    private int detailW() { return Math.max(112,Math.min(175,pw()*40/100)); }
    private int detailX() { return l()+pw()-detailW()-18; }
    private int detailY() { return t()+58; }
    private int detailH() { return ph()-77; }
    private int columns() {
        // The stock area has room for eight 28px columns. The former
        // hardcoded six-column cap wasted two slots' worth of space.
        // Shrink automatically at narrow GUI resolutions.
        return Math.max(1,Math.min(8,(detailX()-leftX()-9)/STEP));
    }
    private int topY() { return t()+62; }
    private int gridEnd() { return b()-23; }
    private int viewportH() { return Math.max(35,gridEnd()-topY()); }
    private int sectionHeight(int rarity) {
        int count=slotLimits[rarity];
        int rows=(count+columns()-1)/columns();
        return HEADER_HEIGHT+rows*STEP+SECTION_PAD;
    }
    private int contentH() {
        int total=0;
        for(int rarity=0;rarity<RARITY_IDS.length;rarity++)
            total+=sectionHeight(rarity);
        return total;
    }
    private int maxScroll() {
        return Math.max(0,(int)Math.ceil((contentH()-viewportH())/(double)SCROLL_UNIT));
    }
    private int verticalPixels() {
        return Math.min(Math.max(0,contentH()-viewportH()),
                (int)Math.round(verticalScroll.value()*SCROLL_UNIT));
    }
    private int rowTop(int rarity) {
        int offset=0;
        for(int i=0;i<rarity;i++)offset+=sectionHeight(i);
        return topY()+offset-verticalPixels();
    }
    // The stock scrollbar belongs immediately LEFT of the item grid,
    // not outside the info panel on the shop's right edge.
    private int barX() { return l()+2; }
    private int barY() { return topY(); }
    private int barH() { return viewportH(); }
    private int thumbH() {
        return Math.max(17,(int)Math.round(barH()
                *Math.min(1.0,barH()/(double)Math.max(barH(),contentH()))));
    }
    private int thumbY() {
        return barY()+(int)Math.round((barH()-thumbH())
                *verticalScroll.value()/(double)Math.max(1,maxScroll()));
    }
    private static int rarityIndex(JsonObject offer) {
        String id=offer.has("rarity")?offer.get("rarity").getAsString():"common";
        for(int i=0;i<RARITY_IDS.length;i++)
            if(RARITY_IDS[i].equals(id))return i;
        return 0;
    }
    private void splitOffers() {
        List<List<JsonObject>> groups=new ArrayList<>();
        for(int i=0;i<RARITY_IDS.length;i++)groups.add(new ArrayList<>());
        List<JsonObject> holds=new ArrayList<>();
        for(JsonElement element:catalog) {
            JsonObject offer=element.getAsJsonObject();
            boolean reserved=offer.has("reserved")&&offer.get("reserved").getAsBoolean();
            // Held offers from previous rotations are shown only in the
            // reservation slots, not added above the stock rarity caps.
            boolean inStock=!offer.has("in_stock") || offer.get("in_stock").getAsBoolean();
            if(inStock)groups.get(rarityIndex(offer)).add(offer);
            if(reserved&&holds.size()<2)holds.add(offer);
        }
        rarityRows=groups.stream().map(List::copyOf).toList();
        reservedOffers=List.copyOf(holds);
        verticalScroll.update(maxScroll());
    }
    private JsonObject selected() {
        if(selectedId==null)return null;
        for(JsonElement element:catalog)
            if(selectedId.equals(element.getAsJsonObject().get("id").getAsString()))
                return element.getAsJsonObject();
        return null;
    }
    private ItemStack stack(JsonObject offer) {
        var key=ResourceLocation.tryParse(offer.get("item").getAsString());
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return ItemStack.EMPTY;
        return new ItemStack(BuiltInRegistries.ITEM.get(key),offer.get("count").getAsInt());
    }

    @Override protected void init() {
        splitOffers();
        int x=detailX(),y=detailY(),w=detailW();
        int buttonY=y+detailH()-26;
        int half=Math.max(43,(w-22)/2);
        closeButton=addRenderableWidget(Button.builder(Component.literal("Close"),
                button->onClose()).bounds(l()+7,b()-19,44,16).build());
        refreshButton=addRenderableWidget(Button.builder(Component.literal("Refresh"),
                button->request("","refresh")).bounds(l()+55,b()-19,52,16).build());
        refreshButton.active=!waiting;
        catalogButton=addRenderableWidget(Button.builder(Component.literal("All Items"),
                button->openCatalog()).bounds(l()+113,b()-19,62,16).build());
        catalogButton.active=!waiting;
        // This is a server-provided permission flag, not a local-client guess.
        // The reroll packet is separately checked by the server for OP level 2.
        rerollButton=canReroll
                ?addRenderableWidget(Button.builder(Component.literal("Reroll Shop"),
                        button->request("","reroll"))
                        .bounds(l()+179,b()-19,92,16).build())
                :null;
        if(rerollButton!=null)rerollButton.active=!waiting;

        JsonObject offer=selected();
        if(offer!=null) {
            boolean unlocked=offer.get("unlocked").getAsBoolean();
            boolean reserved=offer.has("reserved")&&offer.get("reserved").getAsBoolean();
            int price=offer.get("price").getAsInt();
            boolean bought=offer.has("purchased")&&offer.get("purchased").getAsBoolean();
            reserveButton=addRenderableWidget(Button.builder(
                    Component.literal(reserved?"Unreserve":"Reserve"),
                    button->request(selectedId,"reserve"))
                    .bounds(x+7,buttonY,half,18).build());
            reserveButton.active=(unlocked||reserved)&&!waiting&&!bought;
            buyButton=addRenderableWidget(Button.builder(
                    Component.literal(bought?"Bought":"Buy"),
                    button->request(selectedId,"buy"))
                    .bounds(x+w-half-7,buttonY,half,18).build());
            buyButton.active=unlocked&&balance>=price&&!waiting&&!bought;
        }else {
            reserveButton=null;
            buyButton=null;
        }
        if(firstLoad && !waiting && minecraft!=null && minecraft.getConnection()!=null) {
            firstLoad=false;
            request("","refresh");
        }
    }
    private void openCatalog() {
        if(minecraft!=null)
            minecraft.setScreen(new MilestoneShopCatalogScreen(this,allOffers));
    }
    private void rebuild() {clearWidgets();init();}
    private void request(String id,String action) {
        if(waiting||minecraft==null||minecraft.getConnection()==null)return;
        waiting=true;
        status=action.equals("buy")?"Processing purchase..."
                :action.equals("reserve")?"Updating reservation..."
                :action.equals("reroll")?"Resetting shop stock...":"Refreshing stock...";
        PacketDistributor.sendToServer(new MilestoneShopNetwork.ShopRequest(exchange,id,action));
        rebuild();
    }
    public void onShopView(String json,String message) {
        try {
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            catalog=root.getAsJsonArray("offers");
            allOffers=root.has("all_offers")?root.getAsJsonArray("all_offers"):new JsonArray();
            if(root.has("rarity_limits")) {
                JsonObject limits=root.getAsJsonObject("rarity_limits");
                for(int i=0;i<RARITY_IDS.length;i++) {
                    int cap=limits.has(RARITY_IDS[i])?limits.get(RARITY_IDS[i]).getAsInt():
                            DEFAULT_LIMITS[i];
                    slotLimits[i]=Math.max(0,Math.min(64,cap));
                }
            }
            canReroll=root.has("can_reroll")&&root.get("can_reroll").getAsBoolean();
            balance=root.get("balance").getAsInt();
            lifetime=root.get("earned").getAsInt();
            resetSeconds=root.has("reset_seconds")?root.get("reset_seconds").getAsLong():0L;
            clockTicks=0L;
            waiting=false;
            status=message.isBlank()?(catalog.isEmpty()
                    ?"No shop offers configured yet.":"Click an item icon to view details."):message;
            rebuild();
        }catch(Exception ex) {
            waiting=false;
            status="Failed to load shop stock.";
            rebuild();
        }
    }

    @Override public void tick() {
        super.tick();
        if(waiting)return;
        clockTicks++;
        if(resetSeconds>0 && clockTicks/20 >= resetSeconds) {
            resetSeconds=0;
            clockTicks=0;
            request("","refresh");
        }
    }
    private long timeLeft() {return Math.max(0,resetSeconds-clockTicks/20);}
    private String timer() {
        long s=timeLeft();
        return String.format("%d:%02d:%02d",s/3600,(s/60)%60,s%60);
    }

    private void scrollbarMouse(double y) {
        int travel=barH()-thumbH();
        if(travel>0)
            verticalScroll.jump((y-barY()-thumbH()/2.0)*maxScroll()/travel,maxScroll());
    }
    private int slotsY(int rarity,int index) {
        return rowTop(rarity)+HEADER_HEIGHT+(index/columns())*STEP;
    }
    private int slotsX(int index) {
        return leftX()+(index%columns())*STEP;
    }
    private void blankSlot(GuiGraphics g,int x,int y) {
        outline(g,x,y,SLOT,SLOT,0xFF626262,0xFF969696);
        g.fill(x+3,y+3,x+SLOT-3,y+SLOT-3,0x55909090);
    }
    private boolean clickSlot(double mx,double my,JsonObject offer,int x,int y) {
        if(mx>=x && mx<x+SLOT && my>=y && my<y+SLOT) {
            selectedId=offer.get("id").getAsString();
            status="";
            rebuild();
            return true;
        }
        return false;
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(super.mouseClicked(mx,my,button))return true;
        if(button!=0)return false;
        if(maxScroll()>0 && mx>=barX()-1 && mx<barX()+9
                && my>=barY() && my<=barY()+barH()) {
            draggingScrollbar=true;
            scrollbarMouse(my);
            return true;
        }
        if(mx>=leftX()-2 && mx<detailX()-8 && my>=topY() && my<gridEnd()) {
            for(int rarity=0;rarity<RARITY_IDS.length;rarity++) {
                List<JsonObject> row=rarityRows.get(rarity);
                for(int index=0;index<Math.min(slotLimits[rarity],row.size());index++) {
                    int xx=slotsX(index),yy=slotsY(rarity,index);
                    if(yy<topY() || yy+SLOT>gridEnd())continue;
                    if(clickSlot(mx,my,row.get(index),xx,yy))return true;
                }
            }
        }
        for(int i=0;i<reservedOffers.size();i++)
            if(clickSlot(mx,my,reservedOffers.get(i),reservedX(i),reservedY()))return true;
        return false;
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(button==0&&draggingScrollbar){scrollbarMouse(my);return true;}
        return super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button) {
        if(button==0&&draggingScrollbar){draggingScrollbar=false;return true;}
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy) {
        if(mx>=barX() && mx<detailX()-8 && my>=topY() && my<gridEnd()
                && maxScroll()>0) {
            verticalScroll.wheel(dy*3.5,maxScroll());
            return true;
        }
        return super.mouseScrolled(mx,my,dx,dy);
    }
    private int reservedX(int slot) { return l()+pw()-93+slot*STEP; }
    private int reservedY() { return t()+18; }
    private static void outline(GuiGraphics g,int x,int y,int w,int h,int dark,int light) {
        g.fill(x,y,x+w,y+h,light);
        g.fill(x,y,x+w,y+2,dark);
        g.fill(x,y,x+2,y+h,dark);
        g.fill(x+w-2,y,x+w,y+h,dark);
        g.fill(x,y+h-2,x+w,y+h,dark);
    }
    private void slot(GuiGraphics g,JsonObject offer,int x,int y,boolean muted) {
        String id=offer.get("id").getAsString();
        boolean current=id.equals(selectedId);
        outline(g,x,y,SLOT,SLOT,current?GOLD:0xFF383838,0xFF8D8D8D);
        if(current) {
            g.fill(x-1,y-1,x+SLOT+1,y+1,GOLD);
            g.fill(x-1,y-1,x+1,y+SLOT+1,GOLD);
            g.fill(x+SLOT-1,y,x+SLOT+1,y+SLOT+1,GOLD);
            g.fill(x,y+SLOT-1,x+SLOT+1,y+SLOT+1,GOLD);
        }
        ItemStack item=stack(offer);
        if(!item.isEmpty()) {
            int inset=(SLOT-16)/2;
            g.renderItem(item,x+inset,y+inset);
            if(offer.get("count").getAsInt()>1)
                g.renderItemDecorations(font,item,x+inset,y+inset);
        }
        if(offer.has("reserved")&&offer.get("reserved").getAsBoolean())
            g.fill(x+SLOT-7,y+2,x+SLOT-2,y+7,0xFFE6CD52);
        // Only a purchased offer is greyed out. Empty slots are neutral;
        // future stock previews are never displayed in the buying menu.
        if(offer.has("purchased")&&offer.get("purchased").getAsBoolean()){
            g.fill(x+2,y+2,x+SLOT-2,y+SLOT-2,0xB0464646);
            g.drawCenteredString(font,"x",x+SLOT/2,y+SLOT/2-4,0xFFF0F0F0);
        }
    }
    private void details(GuiGraphics g,JsonObject offer, boolean textPass) {
        int x=detailX(),y=detailY(),w=detailW(),h=detailH();
        if(!textPass) {
            outline(g,x,y,w,h,0xFF454545,INFO_PANEL);
            // High-contrast item header and price/footer sections.
            g.fill(x+5,y+5,x+w-5,y+38,HEADER_BG);
            g.fill(x+5,y+h-60,x+w-5,y+h-30,HEADER_BG);
            return;
        }
        if(offer==null) {
            g.drawCenteredString(font,"Select an item",x+w/2,y+h/2-9,INFO_INK);
            g.drawCenteredString(font,"to view details",x+w/2,y+h/2+5,INFO_MUTED);
            return;
        }
        ItemStack item=stack(offer);
        outline(g,x+8,y+8,25,25,0xFF1F1F1F,0xFF949494);
        if(!item.isEmpty())g.renderItem(item,x+12,y+12);
        g.drawString(font,font.plainSubstrByWidth(offer.get("name").getAsString(),w-48),
                x+39,y+10,HEADER_TEXT);
        g.drawString(font,"x"+offer.get("count").getAsInt(),x+39,y+23,0xFFE8E8E8);
        String rarity=offer.has("rarity")?offer.get("rarity").getAsString():"common";
        String label=MilestoneRarityColors.label(rarity);
        int rarityW=font.width(label)+10;
        g.fill(x+7,y+40,x+7+rarityW,y+56,0xFF333333);
        g.drawString(font,label,x+12,y+44,MilestoneRarityColors.color(rarity));
        boolean unlocked=offer.get("unlocked").getAsBoolean();
        boolean bought=offer.has("purchased")&&offer.get("purchased").getAsBoolean();
        if(bought) {
            g.drawString(font,"Purchased this reset",x+8,y+65,0xFF624318);
            g.drawString(font,"Available next reset",x+8,y+79,INFO_INK);
        } else if(unlocked) {
            String description=offer.has("description")
                    ?offer.get("description").getAsString():"";
            if(description.isBlank()) description="Available this rotation";
            int lineY=y+65;
            for(var line:font.split(Component.literal(description),w-18)) {
                if(lineY>y+h-74) break;
                g.drawString(font,line,x+9,lineY,INFO_INK);
                lineY+=11;
            }
            if(offer.has("reserved")&&offer.get("reserved").getAsBoolean())
                g.drawString(font,"Reserved for you",x+8,y+h-68,0xFF705010);
        } else {
            g.drawString(font,"LOCKED",x+8,y+59,0xFF9B2929);
            g.drawString(font,"Complete Core "+(offer.get("requires_core").getAsInt()+1),
                    x+8,y+73,INFO_INK);
        }
        int price=offer.get("price").getAsInt();
        g.drawString(font,"Price: "+price+" credits",x+9,y+h-53,HEADER_TEXT);
        if(balance<price && !bought)
            g.drawString(font,"Need "+(price-balance)+" more",x+9,y+h-41,0xFFFFC4A4);
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        int x=l(),y=t(),w=pw(),h=ph();
        // Vanilla Screen.render may run the blurred background pass. Let it
        // finish before drawing ANY part of our UI; buttons are rendered again
        // at the end so they stay above the freshly drawn sharp background.
        super.render(g,mx,my,partial);
        g.fill(0,0,width,height,0xCC101010);
        outline(g,x,y,w,h,0xFF1B1B1B,PANEL);
        outline(g,x+7,y+7,w-29,43,0xFF575757,INSIDE);
        // Narrow scroll track is attached to the left of the slot list.
        // Rarity bands and item positions remain on the right of the track.
        g.fill(barX(),barY(),barX()+8,barY()+barH(),0xFF343434);
        verticalScroll.update(maxScroll());
        if(maxScroll()>0) {
            g.fill(barX()+1,thumbY(),barX()+7,thumbY()+thumbH(),0xFFE7E7E7);
            g.fill(barX()+1,thumbY(),barX()+7,thumbY()+2,0xFFFFFFFF);
        } else {
            g.fill(barX()+1,barY(),barX()+7,barY()+barH(),0xFF777777);
        }
        details(g,selected(),false);
        // Separate dark information strips maintain clear contrast regardless
        // of the player's GUI scale or the light stone shop texture.
        g.fill(x+12,y+10,x+180,y+44,HEADER_BG);
        g.drawString(font,"Credits",x+19,y+15,HEADER_TEXT);
        g.drawString(font,Integer.toString(balance),x+91,y+15,0xFFFFE49A);
        g.drawString(font,"Next reset",x+19,y+30,HEADER_TEXT);
        g.drawString(font,timer(),x+91,y+30,HEADER_TEXT);
        details(g,selected(),true);
        // Rarity headings and their fixed-size item grids scroll together.
        // Empty positions represent unfilled stock capacity, never previews.
        g.enableScissor(leftX()-2,topY(),detailX()-8,gridEnd());
        for(int rarity=0;rarity<RARITY_IDS.length;rarity++) {
            int top=rowTop(rarity);
            if(top+sectionHeight(rarity)<=topY() || top>=gridEnd())continue;
            g.fill(leftX()-2,top+1,detailX()-9,top+14,0xFF333333);
            if(rarity>0)g.fill(leftX()-2,top,detailX()-9,top+1,0xFFE3E3E3);
            if(top>=topY() && top+HEADER_HEIGHT<=gridEnd())
                g.drawString(font,RARITY_LABELS[rarity],leftX()+3,top+3,
                        MilestoneRarityColors.color(RARITY_IDS[rarity]));

            List<JsonObject> available=rarityRows.get(rarity);
            for(int index=0;index<slotLimits[rarity];index++) {
                int xx=slotsX(index),yy=slotsY(rarity,index);
                if(yy+SLOT<=topY() || yy>=gridEnd())continue;
                if(index<available.size())slot(g,available.get(index),xx,yy,false);
                else blankSlot(g,xx,yy);
            }
        }
        g.disableScissor();

        // Top-right held section shares the same high-contrast header style.
        g.fill(reservedX(0)-43,y+12,reservedX(1)+SLOT+6,y+45,HEADER_BG);
        g.drawString(font,"Held",reservedX(0)-37,reservedY()+8,HEADER_TEXT);
        for(int i=0;i<2;i++) {
            int xx=reservedX(i);
            if(i<reservedOffers.size())slot(g,reservedOffers.get(i),xx,reservedY(),false);
            else blankSlot(g,xx,reservedY());
        }
        // Empty catalog notice is shown in footer; never overlap icon slots.
        // Keep status above the footer controls (including the OP button).
        if(!status.equals("Click an item icon to view details.") && !status.isBlank())
            g.drawString(font,font.plainSubstrByWidth(status,
                    Math.max(15,detailX()-leftX()-12)),
                    leftX()+2,t()+51,FONT);
        // These were rendered in super.render before the background was drawn;
        // re-render above the interface, always crisp.
        if(closeButton!=null)closeButton.render(g,mx,my,partial);
        if(refreshButton!=null)refreshButton.render(g,mx,my,partial);
        if(catalogButton!=null)catalogButton.render(g,mx,my,partial);
        if(rerollButton!=null)rerollButton.render(g,mx,my,partial);
        if(reserveButton!=null)reserveButton.render(g,mx,my,partial);
        if(buyButton!=null)buyButton.render(g,mx,my,partial);
        if(hovered(mx,my)!=null) {
            JsonObject row=hovered(mx,my);
            g.renderTooltip(font,Component.literal(row.get("name").getAsString()),mx,my);
        }
    }
    private JsonObject hovered(int mx,int my) {
        for(int i=0;i<reservedOffers.size();i++) {
            int xx=reservedX(i);
            if(mx>=xx && mx<xx+SLOT && my>=reservedY() && my<reservedY()+SLOT)
                return reservedOffers.get(i);
        }
        if(mx<leftX()-2 || mx>=detailX()-8 || my<topY() || my>=gridEnd())
            return null;
        for(int rarity=0;rarity<RARITY_IDS.length;rarity++) {
            List<JsonObject> row=rarityRows.get(rarity);
            for(int i=0;i<Math.min(slotLimits[rarity],row.size());i++) {
                int xx=slotsX(i),yy=slotsY(rarity,i);
                if(yy<topY() || yy+SLOT>gridEnd())continue;
                if(mx>=xx && mx<xx+SLOT && my>=yy && my<yy+SLOT)
                    return row.get(i);
            }
        }
        return null;
    }
    @Override public void onClose() { if(minecraft!=null)minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
}
