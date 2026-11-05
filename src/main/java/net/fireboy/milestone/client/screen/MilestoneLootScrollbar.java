package net.fireboy.milestone.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/** Small shared scrollbar for server-paged loot IDs and client-rendered item
 * previews. It is strictly visual: the owning screen checks the viewport
 * before handling scrolling and clamps all page offsets. */
public final class MilestoneLootScrollbar {
    private MilestoneLootScrollbar(){}
    public static int clamp(int offset,int total,int visible) {
        return Math.max(0,Math.min(offset,Math.max(0,total-visible)));
    }
    public static int jump(int pointerY,int y,int height,int total,int visible) {
        int max=Math.max(0,total-visible);
        int thumb=Math.max(18,Math.min(height,Math.round(height*visible/(float)Math.max(1,total))));
        int travel=Math.max(1,height-thumb);
        return clamp((int)Math.round((pointerY-y-thumb/2.0)*max/travel),total,visible);
    }
    public static void render(GuiGraphics g,int x,int y,int height,
                              int offset,int total,int visible) {
        if(total<=visible)return;
        g.fill(x,y,x+9,y+height,0xFF1C232A);
        g.fill(x,y,x+1,y+height,0xFF61717D);
        int thumb=Math.max(18,Math.min(height,Math.round(height*visible/(float)total)));
        int travel=Math.max(0,height-thumb);
        int start=y+(int)Math.round(travel*clamp(offset,total,visible)
                /(double)Math.max(1,total-visible));
        g.fill(x+2,start,x+8,start+thumb,0xFFCCD5DE);
        g.fill(x+3,start+2,x+7,start+thumb-2,0xFF8FA3B4);
    }
}
