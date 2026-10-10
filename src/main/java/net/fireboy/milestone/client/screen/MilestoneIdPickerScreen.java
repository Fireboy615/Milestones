package net.fireboy.milestone.client.screen;

import net.fireboy.milestone.network.MilestoneConfigNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Searchable registry ID browser for all vanilla and currently installed mods.
 * Search finds names, namespaces and full resource IDs. Multi-select results
 * remain staged until Apply; Cancel makes no changes to the parent editor.
 */
public final class MilestoneIdPickerScreen extends Screen {
    public enum Kind { ENTITY, ITEM, BLOCK, RECIPE, ADVANCEMENT, BIOME, TASK }

    private record Entry(String id, String name) {
        boolean matches(String query) {
            return query.isBlank() || id.contains(query)
                    || name.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private final Screen parent;
    private final Kind kind;
    private final boolean multi;
    private final Consumer<List<String>> onApply;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Entry> registryEntries = new ArrayList<>();
    private final Map<String,List<String>> collections = new LinkedHashMap<>();
    private boolean showingCollections;
    private List<Entry> visible = List.of();
    private String query = "";
    private int offset;
    private final SmoothWidgetScroll smoothRows=new SmoothWidgetScroll();
    private boolean dragging;
    private EditBox searchBox;
    private int remoteTotal;
    private String sentQuery = null;
    private int sentPage = -1;
    private String remoteStatus = "Search server recipes";

    public MilestoneIdPickerScreen(Screen parent, Kind kind, boolean multi,
                                   Collection<String> existing, Collection<String> customIds,
                                   Consumer<List<String>> onApply) {
        super(Component.literal("Select " + kind.name().toLowerCase(Locale.ROOT) + " IDs"));
        this.parent = parent;
        this.kind = kind;
        this.multi = multi;
        this.onApply = onApply;
        selected.addAll(existing);
        if (kind == Kind.TASK) {
            for (String id : customIds) registryEntries.add(new Entry(id, id));
        } else if (kind == Kind.ITEM) {
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                String label = BuiltInRegistries.ITEM.get(id).getDescription().getString();
                registryEntries.add(new Entry(id.toString(), label));
            }
        } else if (kind == Kind.BLOCK) {
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
                String label = BuiltInRegistries.BLOCK.get(id).getName().getString();
                registryEntries.add(new Entry(id.toString(), label));
            }
        } else if (kind == Kind.ENTITY) {
            for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                String label = BuiltInRegistries.ENTITY_TYPE.get(id).getDescription().getString();
                registryEntries.add(new Entry(id.toString(), label));
            }
        }
        if(kind==Kind.BIOME && Minecraft.getInstance().level!=null) {
Minecraft.getInstance().level.registryAccess().registry(Registries.BIOME).ifPresent(reg->{for(ResourceLocation id:reg.keySet())registryEntries.add(new Entry(id.toString(),id.toString()));});
}
registryEntries.sort(Comparator.comparing(Entry::id));
        if (multi && kind==Kind.ITEM) {
            BuiltInRegistries.ITEM.getTags().forEach(pair -> {
                List<String> members=new ArrayList<>();
                pair.getSecond().stream().forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> members.add(key.location().toString())));
                if (!members.isEmpty())
                    collections.put("#"+pair.getFirst().location(), List.copyOf(members));
            });
        } else if (multi && kind==Kind.BLOCK) {
            BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
                List<String> members=new ArrayList<>();
                pair.getSecond().stream().forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> members.add(key.location().toString())));
                if (!members.isEmpty())
                    collections.put("#"+pair.getFirst().location(), List.copyOf(members));
            });
        }
    }

    private int pw() { return Math.min(478, width - 16); }
    private int ph() { return Math.min(346, height - 12); }
    private int left() { return (width - pw())/2; }
    private int top() { return (height - ph())/2; }
    private int firstY() { return top()+70; }
    private int rowHeight() { return 24; }
    // Apply now sits in the top toolbar. Use the former footer all the way
    // down to the panel's bottom margin (11 rows instead of 8 at full height).
    private int capacity() { return Math.max(1, (ph()-82)/rowHeight()); }
    private boolean remote() {return kind==Kind.RECIPE || kind==Kind.ADVANCEMENT;}
    private int maxOffset() {
        return Math.max(0, (remote() ? remoteTotal : visible.size())-capacity());
    }
    private int trackX() { return left()+pw()-19; }
    private int trackH() { return capacity()*rowHeight(); }
    private int thumbH() {
        int total = remote() ? remoteTotal : visible.size();
        return Math.max(16,(int)Math.round(trackH() *
                Math.min(1.0, capacity() / (double)Math.max(1,total))));
    }
    private int thumbY() {
        return firstY()+(int)Math.round((trackH()-thumbH())
                *smoothRows.value()/(double)Math.max(1,maxOffset()));
    }
    private void setOffset(double value) {
        smoothRows.drag(value,maxOffset());
        int next=smoothRows.first();
        if(next!=offset) {
            offset=next;
            if(remote())fetchServer();
            rebuild();
        }
    }

    private void filter() {
        if(remote())return;
        String key=query.trim().toLowerCase(Locale.ROOT);
        if (showingCollections) {
            List<Entry> groups=new ArrayList<>();
            for (var entry:collections.entrySet())
                groups.add(new Entry(entry.getKey(),"Collection ("+entry.getValue().size()+" IDs)"));
            visible=groups.stream().filter(entry->entry.matches(key)).toList();
        } else visible=registryEntries.stream().filter(entry -> entry.matches(key)).toList();
        offset=Math.min(smoothRows.first(),maxOffset());
    }

    @Override protected void init() {
        int l=left(), t=top(), w=pw();
        filter();
        addRenderableWidget(Button.builder(Component.literal("< Cancel"),
                b -> onClose()).bounds(l+12,t+10,92,20).build());
        if (multi) {
            addRenderableWidget(Button.builder(Component.literal("Add shown"),
                    b -> selectShown()).bounds(l+111,t+10,82,20).build());
            if(!collections.isEmpty()) {
                addRenderableWidget(Button.builder(Component.literal(
                        showingCollections?"View IDs":"Collections"),
                        b -> {
                            showingCollections=!showingCollections;
                            query="";offset=0;smoothRows.reset();
                            rebuild();
                        }).bounds(l+198,t+10,90,20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Apply ("+selected.size()+")"),
                b -> {
                    onApply.accept(List.copyOf(selected));
                    if(minecraft!=null)minecraft.setScreen(parent);
                }).bounds(l+w-110,t+10,98,20).build());
        searchBox=new EditBox(font,l+12,t+38,w-26,20,Component.literal("Search IDs"));
        searchBox.setMaxLength(90);
        searchBox.setValue(query);
        searchBox.setHint(Component.literal("Search name or modid:registry_name"));
        searchBox.setResponder(value -> {
            if(value.equals(query))return;
            query=value;offset=0;smoothRows.reset();
            if(remote())fetchServer();
            else filter();
            rebuild();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        if(remote() && visible.isEmpty() && sentQuery==null)fetchServer();
        List<Entry> displayed=displayedEntries();
        int i=0;
        for(Entry entry:displayed) {
            int y=firstY()+i++*rowHeight();
            boolean chosen=showingCollections
                    ? collections.getOrDefault(entry.id(),List.of()).stream().allMatch(selected::contains)
                    : selected.contains(entry.id());
            String label=(chosen ? "[x] " : "[ ] ")
                    + entry.name()+"   "+entry.id();
            // Reserve the left side of the full-width button for its item icon.
            addRenderableWidget(Button.builder(Component.literal(
                    font.plainSubstrByWidth(label,w-103)),
                    b -> {
                        if(showingCollections) {
                            List<String> group=collections.getOrDefault(entry.id(),List.of());
                            boolean all=group.stream().allMatch(selected::contains);
                            if(all)selected.removeAll(group);
                            else for(String id:group) {
                                if(selected.size()<256)selected.add(id);
                            }
                        }else {
                            if(!multi)selected.clear();
                            if(!selected.add(entry.id()))selected.remove(entry.id());
                        }
                        rebuild();
                    }).bounds(l+12,y,w-38,21).build());
        }
    }
    private List<Entry> displayedEntries() {
        if(remote())return visible;
        return visible.subList(Math.min(offset,visible.size()),
                Math.min(visible.size(),offset+capacity()+1));
    }
    private void selectShown() {
        if(!multi)return;
        for(Entry entry:displayedEntries()) {
            if(showingCollections) {
                for(String id:collections.getOrDefault(entry.id(),List.of())) {
                    if(selected.size()>=256)return;
                    selected.add(id);
                }
            } else if(selected.size()<256)selected.add(entry.id());
        }
        rebuild();
    }

    private void fetchServer() {
        if(!remote())return;
        if(minecraft==null || minecraft.getConnection()==null || minecraft.player==null || !minecraft.player.hasPermissions(2)) {
            remoteStatus="Join a world as an operator to browse server IDs";
            remoteTotal=0;visible=List.of();
            return;
        }
        String key=query.trim().toLowerCase(Locale.ROOT);
        int page=offset;
        if(key.equals(sentQuery) && page==sentPage)return;
        sentQuery=key;sentPage=page;
        remoteStatus="Searching server IDs...";
        PacketDistributor.sendToServer(new MilestoneConfigNetwork.IdSearchRequest(
                key,page,Math.min(30,capacity()),kind.name().toLowerCase(Locale.ROOT)));
    }
    public void receiveServerResults(String searched,int page,int total,List<String> result) {
        if(!remote() || !searched.equals(query.trim().toLowerCase(Locale.ROOT))
                || page!=offset)return;
        remoteTotal=total;
        visible=result.stream().map(id->new Entry(id,id)).toList();
        remoteStatus=total+" matching recipes";
        rebuild();
    }
    private ItemStack icon(Entry entry) {
        ResourceLocation id=ResourceLocation.tryParse(entry.id());
        if(id==null)return ItemStack.EMPTY;
        if(kind==Kind.ITEM && BuiltInRegistries.ITEM.containsKey(id))
            return new ItemStack(BuiltInRegistries.ITEM.get(id));
        if(kind==Kind.BLOCK && BuiltInRegistries.BLOCK.containsKey(id)) {
            var item=BuiltInRegistries.BLOCK.get(id).asItem();
            return item==Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }

    private void drawItemIcons(GuiGraphics graphics) {
        if(kind!=Kind.ITEM && kind!=Kind.BLOCK || showingCollections)return;
        List<Entry> rows=displayedEntries();
        graphics.enableScissor(left()+12,firstY(),left()+pw()-20,firstY()+trackH());
        int row=0;
        for(Entry entry:rows) {
            int y=firstY()+row++*rowHeight()-smoothRows.pixels();
            if(y+rowHeight()<=firstY() || y>=firstY()+trackH())continue;
            ItemStack icon=icon(entry);
            if(!icon.isEmpty())graphics.renderItem(icon,left()+19,y+2);
        }
        graphics.disableScissor();
    }

    private void rebuild() {clearWidgets();init();smoothRows.invalidate();}
    @Override public void onClose() {if(minecraft!=null)minecraft.setScreen(parent);}
    private void scrollTo(double y) {
        int travel=trackH()-thumbH();
        if(travel<=0)return;
        setOffset((y-firstY()-thumbH()/2.0)*maxOffset()/travel);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&maxOffset()>0&&x>=trackX()-2&&x<=trackX()+11
                &&y>=firstY()&&y<=firstY()+trackH()) {
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
        if(maxOffset()>0){smoothRows.wheel(dy,maxOffset());return true;}
        return super.mouseScrolled(x,y,dx,dy);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float tick) {
        int l=left(),t=top(),w=pw(),h=ph();
        smoothRows.configure(l+11,l+w-11,firstY(),rowHeight(),capacity());
        smoothRows.advance(maxOffset(),()->{
            offset=smoothRows.first();
            if(remote())fetchServer();
            rebuild();
        });
        smoothRows.captureIfChanged(children());
        smoothRows.beforeStatic();
        super.render(g,mx,my,tick);
        g.fill(0,0,width,height,0xFF171717);
        g.fill(l,t,l+w,t+h,0xFF343434);
        g.fill(l,t,l+w,t+1,0xFF999999);
        for(var child:children())
            if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.visible)widget.render(g,mx,my,tick);
        smoothRows.renderRows(g,mx,my,tick);
        drawItemIcons(g);
        int total=remote() ? remoteTotal : visible.size();
        if(total==0&&!remote())
            g.drawCenteredString(font,"No IDs match your search",width/2,firstY()+18,0xFFCCCCCC);
        if(maxOffset()>0) {
            g.fill(trackX(),firstY(),trackX()+9,firstY()+trackH(),0xFF151515);
            g.fill(trackX()+1,thumbY(),trackX()+8,thumbY()+thumbH(),0xFFBBBBBB);
        }
    }
    @Override public boolean isPauseScreen() {return false;}
}
