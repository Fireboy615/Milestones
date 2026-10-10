package net.fireboy.milestone.client.screen;

/**
 * Lightweight eased scrollbar in logical rows. Wheel input changes a target;
 * frames interpolate toward it, whereas dragging positions the thumb exactly.
 * Does not require a new screen, timers, or scrolling in fixed row jumps.
 */
public final class SmoothScroll {
    private double value;
    private double target;

    public double value() { return value; }
    public int firstRow() { return Math.max(0,(int)Math.floor(value)); }
    public int pixelRemainder(int rowHeight) {
        return (int)Math.round((value-firstRow())*rowHeight);
    }

    public void jump(double rows,int maximum) {
        value=clamp(rows,maximum);
        target=value;
    }
    public void reset() { value=0; target=0; }
    public void wheel(double direction,int maximum) {
        if(maximum<=0)return;
        target=clamp(target-direction*1.65,maximum);
    }
    public boolean update(int maximum) {
        double old=value;
        value=clamp(value,maximum);
        target=clamp(target,maximum);
        double diff=target-value;
        if(Math.abs(diff)<0.003)value=target;
        else value+=diff*0.27;
        return Math.abs(value-old)>0.0001;
    }
    private static double clamp(double rows,int maximum) {
        return Math.max(0,Math.min(Math.max(0,maximum),rows));
    }
}
