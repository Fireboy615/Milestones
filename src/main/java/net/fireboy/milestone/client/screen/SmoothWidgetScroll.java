package net.fireboy.milestone.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.util.ArrayList;
import java.util.List;

/** Smooth pixel-positioned scrolling for vanilla widgets in a bounded list.
 * Rows are rendered in a scissor viewport, while fixed menu buttons remain
 * rendered normally. Interpolates each frame and keeps widget hitboxes aligned.
 */
public final class SmoothWidgetScroll {
    private record Row(AbstractWidget widget,int index,boolean enabled) {}
    private final SmoothScroll smooth = new SmoothScroll();
    private final List<Row> rows = new ArrayList<>();
    private GuiEventListener firstChild;
    private int childCount = -1;
    private int top, rowHeight, capacity, left, right;

    public double value() { return smooth.value(); }
    public int first() { return smooth.firstRow(); }
    public int pixels() { return smooth.pixelRemainder(rowHeight); }
    public void reset() { smooth.reset(); invalidate(); }
    public void wheel(double delta,int max){ smooth.wheel(delta,max); }
    public void drag(double value,int max){ smooth.jump(value,max); }
    public void invalidate() { rows.clear(); childCount=-1; firstChild=null; }

    public void configure(int left,int right,int top,int rowHeight,int capacity) {
        this.left=left;this.right=right;this.top=top;
        this.rowHeight=Math.max(1,rowHeight);this.capacity=Math.max(1,capacity);
    }

    /** Detect Screen.init/clearWidgets rebuilds without requiring all code paths to notify. */
    public void captureIfChanged(List<? extends GuiEventListener> widgets) {
        GuiEventListener first=widgets.isEmpty()?null:widgets.get(0);
        if(first==firstChild && widgets.size()==childCount)return;
        firstChild=first;childCount=widgets.size();
        rows.clear();
        int begin=first();
        for(GuiEventListener child:widgets) {
            if(!(child instanceof AbstractWidget widget))continue;
            int y=widget.getY();
            // Fixed buttons are outside the scrolling region, and aren't moved.
            if(y<top || y>=top+(capacity+1)*rowHeight)continue;
            int index=begin+Math.max(0,(int)Math.round((y-top)/(double)rowHeight));
            rows.add(new Row(widget,index,widget.active));
        }
    }

    public boolean advance(int maximum,Runnable rebuild) {
        int before=first();
        smooth.update(maximum);
        if(first()!=before) {
            invalidate();
            rebuild.run();
            return true;
        }
        return false;
    }

    private void layout() {
        for(Row row:rows) {
            int y=top+(int)Math.round((row.index()-smooth.value())*rowHeight);
            AbstractWidget widget=row.widget();
            widget.setY(y);
            boolean intersect=y+widget.getHeight()>top && y<top+capacity*rowHeight;
            widget.visible=intersect;
            // Do not let a partially clipped row catch a click outside viewport.
            widget.active=row.enabled() && y>=top && y+widget.getHeight()<=top+capacity*rowHeight;
        }
    }

    /** Call just before Screen.super.render() to defer scrollable rows. */
    public void beforeStatic() {
        layout();
        for(Row row:rows)row.widget().visible=false;
    }

    /** Call immediately after super.render; clips animated rows to list bounds. */
    public void renderRows(GuiGraphics graphics,int mx,int my,float partial) {
        graphics.enableScissor(left,top,right,top+capacity*rowHeight);
        for(Row row:rows) {
            AbstractWidget widget=row.widget();
            int y=widget.getY();
            if(y+widget.getHeight()<=top || y>=top+capacity*rowHeight)continue;
            widget.visible=true;
            widget.render(graphics,mx,my,partial);
            // Preserve correct click handling even when visually clipped.
            widget.active=row.enabled() && y>=top && y+widget.getHeight()<=top+capacity*rowHeight;
        }
        graphics.disableScissor();
    }
}
