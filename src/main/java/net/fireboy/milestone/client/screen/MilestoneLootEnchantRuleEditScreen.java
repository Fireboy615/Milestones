package net.fireboy.milestone.client.screen;

import com.google.gson.*;
import net.fireboy.milestone.network.MilestoneLootNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;

/**
 * Safe, type-specific editor for vanilla enchantment loot functions.
 * Conditions and unrelated function properties are never submitted.
 */
public final class MilestoneLootEnchantRuleEditScreen extends Screen {
    private final MilestoneLootEnchantRulesScreen parent;
    private final String table,revision,scope,path,kind;
    private final int functionIndex;
    private final JsonObject rule;
    private final boolean editable;
    private String options="",minimum="1",maximum="1";
    private String message="Change the values, then confirm the datapack edit.";
    private boolean onlyCompatible=true,waiting,confirm;
    private String operation="";
    private EditBox optionField,minField,maxField;
    private Button applyButton,removeButton,compatibleButton;

    public MilestoneLootEnchantRuleEditScreen(
            MilestoneLootEnchantRulesScreen parent,String table,
            String revision,JsonObject selected) {
        super(Component.literal("Edit Enchantment Rule"));
        this.parent=parent;this.table=table;this.revision=revision;
        this.rule=selected.deepCopy();
        this.scope=read(selected,"scope");
        this.path=read(selected,"path");
        this.kind=read(selected,"kind");
        this.functionIndex=selected.has("function_index")?
                selected.get("function_index").getAsInt():-1;
        this.editable=selected.has("editable")&&selected.get("editable").getAsBoolean();
        this.options=read(selected,"options");
        if(kind.equals("set_enchantments")){
            JsonObject values=selected.has("fixed")&&selected.get("fixed").isJsonObject()
                    ?selected.getAsJsonObject("fixed"):new JsonObject();
            if(!values.entrySet().isEmpty()){
                var first=values.entrySet().iterator().next();
                this.options=first.getKey();this.minimum=first.getValue().getAsString();
            }else{this.options="minecraft:sharpness";this.minimum="1";}
            this.maximum="1";
        }else{
            this.minimum=read(selected,"min").isBlank()?"1":read(selected,"min");
            this.maximum=read(selected,"max").isBlank()?"1":read(selected,"max");
        }
        if(selected.has("only_compatible"))
            this.onlyCompatible=selected.get("only_compatible").getAsBoolean();
        if(!editable)this.message="This rule is read-only: "+read(selected,"edit_note");
    }
    private static String read(JsonObject value,String key) {
        return value.has(key)?value.get(key).getAsString():"";
    }
    private int pw(){return Math.min(478,width-12);}
    private int ph(){return Math.min(317,height-12);}
    private int l(){return (width-pw())/2;}
    private int t(){return (height-ph())/2;}
    private int b(){return t()+ph();}
    private boolean isFixed(){return kind.equals("set_enchantments");}
    private boolean isLevels(){return kind.equals("enchant_with_levels");}
    private boolean isRandom(){return kind.equals("enchant_randomly");}
    private int number(String text,int fallback){
        try{return Integer.parseInt(text.trim());}
        catch(NumberFormatException bad){return fallback;}
    }
    private boolean valid(){
        if(waiting||!editable)return false;
        if(isFixed()){
            ResourceLocation id=ResourceLocation.tryParse(options.trim());
            if(id==null||minecraft==null||minecraft.level==null)return false;
            var reg=minecraft.level.registryAccess().registry(Registries.ENCHANTMENT);
            if(reg.isEmpty()||!reg.get().containsKey(id))return false;
            int n=number(minimum,0);
            return n>=1&&n<=reg.get().get(id).getMaxLevel();
        }
        if(options.length()>512)return false;
        if(isLevels())
            return number(minimum,0)>=1&&number(maximum,101)<=100
                    &&number(maximum,0)>=number(minimum,0);
        return isRandom();
    }
    private void cancelConfirm(){
        confirm=false;operation="";
        if(applyButton!=null)applyButton.setMessage(Component.literal(
                isFixed()?"Set Enchant Level":"Stage Rule Settings"));
        if(removeButton!=null)removeButton.setMessage(Component.literal("Remove Enchant"));
        updateButtons();
    }
    private void updateButtons(){
        if(applyButton!=null)applyButton.active=valid();
        if(removeButton!=null)removeButton.active=editable&&!waiting
                &&isFixed()&&rule.has("fixed")
                &&hasFixed(options.trim());
        if(compatibleButton!=null)
            compatibleButton.setMessage(Component.literal("Compatible Only: "+
                    (onlyCompatible?"ON":"OFF")));
    }
    private boolean hasFixed(String id){
        if(!rule.has("fixed")||!rule.get("fixed").isJsonObject())return false;
        ResourceLocation wanted=ResourceLocation.tryParse(id);
        if(wanted==null)return false;
        for(String candidate:rule.getAsJsonObject("fixed").keySet())
            if(wanted.equals(ResourceLocation.tryParse(candidate)))return true;
        return false;
    }
    private void chooseEnchantment(){
        if(minecraft==null)return;
        minecraft.setScreen(new MilestoneIdPickerScreen(this,
                MilestoneIdPickerScreen.Kind.ENCHANTMENT,false,
                List.of(options),List.of(),ids->{
                    if(!ids.isEmpty())options=ids.get(0);
                    JsonObject fixed=rule.has("fixed")&&rule.get("fixed").isJsonObject()
                            ?rule.getAsJsonObject("fixed"):new JsonObject();
                    for(String key:fixed.keySet()){
                        if(ResourceLocation.tryParse(key)!=null
                                &&ResourceLocation.tryParse(key).equals(
                                        ResourceLocation.tryParse(options))){
                            minimum=fixed.get(key).getAsString();break;
                        }
                    }
                    confirm=false;
                }));
    }
    private void submit(boolean remove){
        if(waiting||!editable)return;
        if(remove&&!hasFixed(options.trim()))return;
        if(!remove&&!valid())return;
        String action=isFixed()?(remove?"fixed_remove":"fixed_set"):
                isLevels()?"levels":"random";
        if(!confirm||!action.equals(operation)){
            confirm=true;operation=action;
            message="Confirm: stage this enchantment rule change for /reload.";
            if(applyButton!=null)applyButton.setMessage(Component.literal(
                    remove?"Stage Rule Settings":"CONFIRM Settings"));
            if(removeButton!=null&&remove)removeButton.setMessage(
                    Component.literal("CONFIRM Remove"));
            if(!remove&&applyButton!=null)
                applyButton.setMessage(Component.literal("CONFIRM Settings"));
            return;
        }
        waiting=true;confirm=false;operation="";
        updateButtons();
        message="Writing a validated world datapack override...";
        PacketDistributor.sendToServer(new MilestoneLootNetwork.EditEnchantRuleRequest(
                table,revision,path,functionIndex,action,options.trim(),
                number(minimum,0),number(maximum,0),onlyCompatible));
    }
    @Override protected void init(){
        int x=l(),y=t(),w=pw(),inside=x+13,inner=w-26;
        addRenderableWidget(Button.builder(Component.literal("< Rules"),
                button->onClose()).bounds(x+10,y+9,81,20).build());
        int pickerWidth=isFixed()?110:0;
        int compact=ph()<280?-21:0;
        optionField=new EditBox(font,inside,y+104+compact,inner-pickerWidth-(pickerWidth==0?0:6),
                20,Component.literal(isFixed()?"Enchantment ID":"Allowed enchantments"));
        optionField.setMaxLength(512);
        optionField.setValue(options);
        optionField.setHint(Component.literal(isFixed()
                ?"modid:enchantment":"IDs, comma-separated, or #tag; blank = default"));
        optionField.setResponder(next->{options=next;cancelConfirm();});
        addRenderableWidget(optionField);
        if(isFixed())
            addRenderableWidget(Button.builder(Component.literal("Choose..."),
                    button->chooseEnchantment()).bounds(
                    x+w-13-pickerWidth,y+104+compact,pickerWidth,20).build());
        int half=(inner-8)/2;
        if(isLevels()||isFixed()){
            minField=new EditBox(font,inside,y+155+compact,half,20,
                    Component.literal(isFixed()?"Enchantment level":"Minimum power level"));
            minField.setMaxLength(3);minField.setValue(minimum);
            minField.setResponder(next->{minimum=next;cancelConfirm();});
            addRenderableWidget(minField);
            if(isLevels()){
                maxField=new EditBox(font,inside+half+8,y+155+compact,half,20,
                        Component.literal("Maximum power level"));
                maxField.setMaxLength(3);maxField.setValue(maximum);
                maxField.setResponder(next->{maximum=next;cancelConfirm();});
                addRenderableWidget(maxField);
            }
        }else if(isRandom()){
            compatibleButton=addRenderableWidget(Button.builder(
                    Component.literal("Compatible Only: "+(onlyCompatible?"ON":"OFF")),
                    button->{onlyCompatible=!onlyCompatible;cancelConfirm();})
                    .bounds(inside,y+152+compact,inner,20).build());
        }
        applyButton=addRenderableWidget(Button.builder(Component.literal(
                confirm?"CONFIRM Settings":isFixed()?"Set Enchant Level":"Stage Rule Settings"),
                button->submit(false)).bounds(inside,b()-53,
                isFixed()?(inner-7)/2:inner,20).build());
        if(isFixed()){
            removeButton=addRenderableWidget(Button.builder(Component.literal(
                    confirm&&operation.equals("fixed_remove")?"CONFIRM Remove":"Remove Enchant"),
                    button->submit(true)).bounds(
                    inside+(inner-7)/2+7,b()-53,(inner-7)/2,20).build());
        }
        updateButtons();
    }
    public void receive(String json){
        try{
            JsonObject reply=JsonParser.parseString(json).getAsJsonObject();
            if(!"edit_enchant_rule".equals(read(reply,"action")))return;
            waiting=false;
            message=read(reply,"message");
            if(reply.has("success")&&reply.get("success").getAsBoolean()){
                if(minecraft!=null){
                    minecraft.setScreen(parent);
                    parent.onRuleEdited(message);
                }
                return;
            }
        }catch(RuntimeException error){
            waiting=false;message="Invalid server response.";
        }
        confirm=false;operation="";clearWidgets();init();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);
        int x=l(),y=t(),w=pw(),inside=x+13,inner=w-26;
        g.fill(0,0,width,height,0xFF171717);
        g.fill(x,y,x+w,b(),0xFF30363D);
        g.fill(x,y,x+w,y+2,0xFF9EB3C3);
        g.drawString(font,"EDIT ENCHANT RULE",x+106,y+15,0xFFFFDFA5);
        g.drawString(font,font.plainSubstrByWidth(table,inner),inside,y+38,0xFFF1F1F1);
        g.drawString(font,font.plainSubstrByWidth(scope,inner),inside,y+52,0xFFC2D5E1);
        g.drawString(font,font.plainSubstrByWidth(
                "Function: "+kind,inner),inside,
                y+(ph()<280?62:70),0xFFE5E5E5);
        g.drawString(font,isFixed()?"Enchantment ID":isRandom()
                        ?"Allowed enchantments (optional)":"Allowed enchantments (optional)",
                inside,y+(ph()<280?72:92),0xFFDBE4EB);
        if(isFixed()){
            g.drawString(font,"Enchantment level (normal maximum)",inside,y+(ph()<280?113:141),0xFFDBE4EB);
            if(ph()>288)g.drawString(font,"Existing: "+(rule.has("fixed")?
                    font.plainSubstrByWidth(rule.get("fixed").toString(),inner-55):"none"),
                    inside,y+184,0xFFB8D5DF);
        }else if(isLevels()){
            g.drawString(font,"Minimum enchanting power",inside,y+(ph()<280?113:141),0xFFDBE4EB);
            g.drawString(font,"Maximum enchanting power",inside+inner/2+4,
                    y+(ph()<280?113:141),0xFFDBE4EB);
            if(ph()>288)g.drawString(font,
                    "Levels control enchanting power, not a fixed enchant level.",
                    inside,y+188,0xFFB8D5DF);
        }else if(isRandom()){
            if(ph()>288)g.drawString(font,
                    "One random enchant; level is selected by Minecraft.",
                    inside,y+189,0xFFB8D5DF);
        }
        if(!editable&&ph()>294)
            g.drawString(font,font.plainSubstrByWidth(
                    "Read-only: "+read(rule,"edit_note"),inner),
                    inside,y+204,0xFFFFBB9D);
        else if(ph()>294)
            g.drawString(font,"Existing conditions and unrelated JSON are preserved.",
                    inside,y+214,0xFFB8D5DF);
        if(ph()>268)
            g.drawString(font,"Changes apply to future loot after /reload.",
                    inside,b()-27,0xFFC4D5DF);
        g.drawString(font,font.plainSubstrByWidth("Status: "+message,inner),
                inside,b()-13,0xFFFFD6A3);
        for(var child:children())
            if(child instanceof AbstractWidget widget&&widget.visible)
                widget.render(g,mx,my,partial);
    }
    @Override public void onClose(){
        if(minecraft!=null)minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
