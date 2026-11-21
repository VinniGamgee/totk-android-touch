package com.aure.totk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Transparent, multi-touch Switch-style controller drawn over the NativeActivity surface. */
final class TouchPadView extends View {
    private static final int BUTTONS = 16;
    private static final int[] BITS = {
        1 << 0, 1 << 1, 1 << 2, 1 << 3, 1 << 6, 1 << 7, 1 << 8, 1 << 9,
        1 << 11, 1 << 10, 1 << 12, 1 << 13, 1 << 14, 1 << 15, 1 << 4, 1 << 5
    };
    private static final String[] LABELS = {
        "A", "B", "X", "Y", "ZL", "L", "R", "ZR", "−", "+",
        "◀", "▲", "▶", "▼", "L3", "R3"
    };
    private static final float DEAD_ZONE = .12f;
    private final SparseArray<Finger> fingers = new SparseArray<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;
    private float opacity = .34f;
    private float scale = 1f;

    private static final class Finger {
        int kind; // 0 button, 1 left stick, 2 right stick
        int button;
        float ox, oy, x, y;
    }
    private static final class Button {
        float x, y, r;
        int index;
        Button(float x, float y, float r, int index) { this.x=x; this.y=y; this.r=r; this.index=index; }
    }

    TouchPadView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(false);
        setClickable(true);
    }

    void setOpacity(float value) { opacity = Math.max(.12f, Math.min(.8f, value)); invalidate(); }
    void setScale(float value) { scale = Math.max(.75f, Math.min(1.35f, value)); invalidate(); }

    private float dp(float value) { return value * density * scale; }
    private float diamond(float h) { return Math.min(dp(52), h * .14f); }
    private float[] center(int index, float w, float h) {
        float inset = w * .14f, d = diamond(h), cx = w - inset, cy = h * .42f;
        float px = inset, py = cy;
        switch (index) {
            case 0: return new float[]{cx+d,cy}; case 1: return new float[]{cx,cy+d};
            case 2: return new float[]{cx,cy-d}; case 3: return new float[]{cx-d,cy};
            case 4: return new float[]{w*.05f,h*.12f}; case 5: return new float[]{w*.16f,h*.12f};
            case 6: return new float[]{w*.84f,h*.12f}; case 7: return new float[]{w*.95f,h*.12f};
            case 8: return new float[]{w*.40f,h*.12f}; case 9: return new float[]{w*.60f,h*.12f};
            case 10: return new float[]{px-d,py}; case 11: return new float[]{px,py-d};
            case 12: return new float[]{px+d,py}; case 13: return new float[]{px,py+d};
            case 14: return new float[]{w*.05f,h*.88f}; default: return new float[]{w*.95f,h*.88f};
        }
    }
    private Button[] buttons(float w, float h) {
        Button[] out = new Button[BUTTONS];
        float r = dp(23);
        for (int i=0;i<BUTTONS;i++) {
            float[] p=center(i,w,h);
            out[i]=new Button(p[0],p[1],r,i);
        }
        return out;
    }
    private int hitButton(float x,float y) {
        Button[] bs=buttons(getWidth(),getHeight());
        for (Button b:bs) {
            float dx=x-b.x,dy=y-b.y;
            if (dx*dx+dy*dy <= b.r*b.r*1.25f) return b.index;
        }
        return -1;
    }
    private void updateFinger(Finger f, MotionEvent e, int index) {
        f.x=e.getX(index); f.y=e.getY(index);
    }
    private void sendState() {
        long buttons=0; int[] axes=new int[4];
        float radius=dp(68);
        for(int i=0;i<fingers.size();i++) {
            Finger f=fingers.valueAt(i);
            if(f.kind==0) { buttons |= (1L << f.button); continue; }
            float dx=f.x-f.ox, dy=f.oy-f.y;
            float distance=(float)Math.hypot(dx,dy);
            if(distance>radius) { dx*=radius/distance; dy*=radius/distance; }
            int ax=Math.round(dx/radius*32767f), ay=Math.round(dy/radius*32767f);
            int base=f.kind==1?0:2;
            if(Math.abs(ax)>Math.abs(axes[base])) axes[base]=ax;
            if(Math.abs(ay)>Math.abs(axes[base+1])) axes[base+1]=ay;
        }
        TotkActivity.sendTouchPadState(buttons,axes[0],axes[1],axes[2],axes[3]);
        invalidate();
    }
    private void addFinger(MotionEvent e,int index) {
        int id=e.getPointerId(index); float x=e.getX(index),y=e.getY(index);
        Finger f=new Finger(); f.x=x; f.y=y;
        int button=hitButton(x,y);
        if(button>=0) { f.kind=0; f.button=button; }
        else { f.kind=x<getWidth()*.5f?1:2; f.ox=x; f.oy=y; }
        fingers.put(id,f);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked(), index=event.getActionIndex();
        switch(action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                addFinger(event,index); sendState(); return true;
            case MotionEvent.ACTION_MOVE:
                for(int i=0;i<event.getPointerCount();i++) {
                    Finger f=fingers.get(event.getPointerId(i));
                    if(f!=null) updateFinger(f,event,i);
                }
                sendState(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if(action==MotionEvent.ACTION_CANCEL) fingers.clear();
                else fingers.remove(event.getPointerId(index));
                sendState(); return true;
            default: return true;
        }
    }

    private void circle(Canvas c,float x,float y,float r,boolean fill) {
        paint.setStyle(fill?Paint.Style.FILL:Paint.Style.STROKE);
        c.drawCircle(x,y,r,paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(getWidth()==0||getHeight()==0)return;
        int alpha=(int)(255*opacity);
        paint.setColor(0xffffffff); paint.setAlpha(alpha); paint.setStrokeWidth(dp(2));
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(14)); paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        Button[] bs=buttons(getWidth(),getHeight());
        for(Button b:bs) {
            boolean pressed=false;
            for(int i=0;i<fingers.size();i++){Finger f=fingers.valueAt(i);if(f.kind==0&&f.button==b.index){pressed=true;break;}}
            paint.setAlpha(pressed?Math.min(255,alpha+55):alpha);
            circle(canvas,b.x,b.y,b.r,pressed);
            paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(b.x,b.y,b.r,nullSafePaint());
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(LABELS[b.index],b.x,b.y+dp(5),paint);
        }
        float radius=dp(68);
        drawStick(canvas,getWidth()*.22f,getHeight()*.80f,radius,1);
        drawStick(canvas,getWidth()*.78f,getHeight()*.80f,radius,2);
    }
    private Paint nullSafePaint(){ paint.setStyle(Paint.Style.STROKE); return paint; }
    private void drawStick(Canvas c,float x,float y,float r,int kind) {
        Finger active=null;
        for(int i=0;i<fingers.size();i++){Finger f=fingers.valueAt(i);if(f.kind==kind){active=f;break;}}
        paint.setAlpha((int)(255*opacity)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        c.drawCircle(active==null?x:active.ox,active==null?y:active.oy,r,paint);
        float kx=active==null?x:active.x,ky=active==null?y:active.y;
        paint.setStyle(Paint.Style.FILL); c.drawCircle(kx,ky,dp(22),paint);
    }
}
