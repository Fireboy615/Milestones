package net.fireboy.milestone.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fireboy.milestone.network.MilestoneShopNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Buy an approximate server-authenticated location report on an online or
 * previously seen player; never displays or receives true player coordinates. */
public final class MilestoneShopIntelScreen extends Screen {
    private record Known(String uuid,String name,boolean online){}
    private final Screen parent;
    private final BlockPos exchange;
    private final List<Known> players=new ArrayList<>();
    private List<Known> matches=List.of();
    private String search="";
    private String selected="";
    private String report="";
    private String status="Loading players...";
    private int price,balance,page;
    private boolean firstLoad=true,waiting;
    private EditBox searchBox;
    private Button buyButton,previous,next;
    private static final int PAGE_SIZE=7;

    public MilestoneShopIntelScreen(Screen parent,BlockPos exchange,int price,int balance) {
        super(Component.literal("Resonant Exchange - Player Intel"));
        this.parent=parent;
        this.exchange=exchange.immutable();
        this.price=price;
        this.balance=balance;
    }
    private int pw(){return Math.min(440,width-14);}
    private int ph(){return Math.min(305,height-14);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private void request(String id,String action) {
        if(minecraft==null||minecraft.getConnection()==null||waiting)return;
        waiting=true;
        status=action.equals("intel_buy")?"Purchasing approximate report...":"Updating player list...";
        PacketDistributor.sendToServer(new MilestoneShopNetwork.ShopRequest(exchange,id,action));
        refreshControls();
    }
    private void filter() {
        matches=players.stream().filter(p->p.name().toLowerCase(Locale.ROOT)
                .contains(search.trim().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing((Known p)->!p.online())
                        .thenComparing(Known::name,String.CASE_INSENSITIVE_ORDER))
                .toList();
        page=Math.max(0,Math.min(page,Math.max(0,(matches.size()-1)/PAGE_SIZE)));
    }
    private Known selected() {
        for(Known item:players)if(item.uuid().equals(selected))return item;
        return null;
    }
    private void refreshControls() {
        if(buyButton!=null)
            buyButton.active=!waiting && selected()!=null && price>0 && balance>=price;
        if(previous!=null)previous.active=!waiting && page>0;
        if(next!=null)next.active=!waiting && (page+1)*PAGE_SIZE<matches.size();
    }
    private void rebuild(){clearWidgets();init();}
    @Override protected void init() {
        int x=l(),y=t(),w=pw();
        addRenderableWidget(Button.builder(Component.literal("< Shop"),
                b->onClose()).bounds(x+8,y+8,72,19).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh Players"),
                b->request("","intel_list")).bounds(x+w-125,y+8,116,19).build());
        searchBox=new EditBox(font,x+12,y+52,w-24,20,
                Component.literal("Search online / last-seen players"));
        searchBox.setMaxLength(64);
        searchBox.setValue(search);
        searchBox.setHint(Component.literal("Filter by player name..."));
        searchBox.setResponder(value->{
            if(value.equals(search))return;
            search=value;page=0;filter();rebuild();
            searchBox.setCursorPosition(search.length());
        });
        addRenderableWidget(searchBox);
        int start=page*PAGE_SIZE;
        for(int i=start;i<Math.min(matches.size(),start+PAGE_SIZE);i++) {
            Known player=matches.get(i);
            int cy=y+81+(i-start)*22;
            addRenderableWidget(Button.builder(
                    Component.literal((player.online()?"[Online] ":"[Last seen] ")
                            +player.name()),
                    b->{selected=player.uuid();report="";status="";rebuild();})
                    .bounds(x+12,cy,w-24,20).build());
        }
        previous=addRenderableWidget(Button.builder(Component.literal("<"),
                b->{page--;rebuild();}).bounds(x+12,b()-49,25,17).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),
                b->{page++;rebuild();}).bounds(x+85,b()-49,25,17).build());
        buyButton=addRenderableWidget(Button.builder(Component.literal(
                        price<=0?"Intel Disabled":"Buy Report: "+price+" Credits"),
                b->{if(selected()!=null)request(selected,"intel_buy");})
                .bounds(x+w-181,b()-49,170,18).build());
        refreshControls();
        if(firstLoad){
            firstLoad=false;
            request("","intel_list");
        }
    }
    public void onIntelView(String json,String message) {
        try {
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            price=root.get("price").getAsInt();
            balance=root.get("balance").getAsInt();
            players.clear();
            JsonArray rows=root.getAsJsonArray("players");
            for(JsonElement raw:rows) {
                JsonObject row=raw.getAsJsonObject();
                players.add(new Known(row.get("id").getAsString(),
                        row.get("name").getAsString(),row.get("online").getAsBoolean()));
            }
            filter();
            if(!message.isBlank()){
                boolean success=root.has("purchase_success")
                        &&root.get("purchase_success").getAsBoolean();
                report=success?message:"";
                status=success?"Report sent to your Minecraft chat.":message;
            }else status="Select a player, then purchase a 100-block approximate location.";
            waiting=false;
            rebuild();
        }catch(Exception exception){
            waiting=false;
            status="Could not load Player Intel.";
            rebuild();
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw();
        g.fill(0,0,width,height,0xDB101010);
        g.fill(x,y,x+w,b(),0xFF34383D);
        g.fill(x,y,x+w,y+2,0xFF858E99);
        g.fill(x+7,y+29,x+w-7,y+49,0xFF20272D);
        g.drawString(font,"Player Intel   |   Credits: "+balance,x+14,y+35,0xFFF4E0B0);
        g.drawString(font,"Estimated X/Z only; actual coordinates and Y are hidden.",
                x+12,b()-27,0xFFD6D6D6);
        if(matches.isEmpty())g.drawCenteredString(font,"No recorded players found",
                width/2,y+98,0xFFE5E5E5);
        Known chosen=selected();
        if(chosen!=null) {
            String name="Selected: "+chosen.name()+
                    (chosen.online()?" (current location)":" (last known)");
            g.drawString(font,font.plainSubstrByWidth(name,w-25),x+12,b()-74,0xFFFFE09C);
        }
        if(!status.isBlank())g.drawString(font,font.plainSubstrByWidth(status,w-24),
                x+12,b()-64,0xFFC9D7F0);
        // The exact report is never on a public list. The full text goes
        // only to the buyer's own chat, so it remains available after closing.
        for(var child:children())
            if(child instanceof AbstractWidget widget && widget.visible)
                widget.render(g,mx,my,partial);
    }
    @Override public void onClose() {
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
