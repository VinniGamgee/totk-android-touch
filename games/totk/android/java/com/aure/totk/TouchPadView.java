package com.aure.totk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/** Transparent, multi-touch Switch-style controller with an in-game layout editor. */
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
    private final SparseArray<Finger> fingers = new SparseArray<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences preferences;
    private final float[] buttonX = new float[BUTTONS];
    private final float[] buttonY = new float[BUTTONS];
    private final float[] buttonScale = new float[BUTTONS];
    private float density;
    private float opacity = .34f;
    private float scale = 1f;
    private boolean editor;
    private int selectedButton = -1;
    private int editPointer = -1;

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
        preferences = context.getSharedPreferences("totk_touch_controls", Context.MODE_PRIVATE);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(false);
        setClickable(true);
        opacity = preferences.getFloat("opacity", .34f);
        loadLayout();
    }

    void setOpacity(float value) {
        opacity = Math.max(.12f, Math.min(.8f, value));
        preferences.edit().putFloat("opacity", opacity).apply();
        invalidate();
    }
    void setScale(float value) { scale = Math.max(.75f, Math.min(1.35f, value)); invalidate(); }

    private float dp(float value) { return value * density * scale; }
    private float diamond(float h) { return Math.min(dp(52), h * .14f); }
    private float[] defaultCenter(int index, float w, float h) {
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
    private void loadLayout() {
        for (int i=0;i<BUTTONS;i++) {
            buttonX[i] = preferences.getFloat("x_"+i, Float.NaN);
            buttonY[i] = preferences.getFloat("y_"+i, Float.NaN);
            buttonScale[i] = preferences.getFloat("size_"+i, 1f);
        }
    }
    private void initializeDefaults(float w, float h) {
        for (int i=0;i<BUTTONS;i++) {
            if (Float.isNaN(buttonX[i]) || Float.isNaN(buttonY[i])) {
                float[] p=defaultCenter(i,w,h);
                buttonX[i]=p[0]/w; buttonY[i]=p[1]/h;
            }
        }
    }
    private void saveButton(int index) {
        preferences.edit().putFloat("x_"+index,buttonX[index]).putFloat("y_"+index,buttonY[index])
                .putFloat("size_"+index,buttonScale[index]).apply();
    }
    private void resetLayout() {
        for(int i=0;i<BUTTONS;i++) {
            buttonX[i]=Float.NaN; buttonY[i]=Float.NaN; buttonScale[i]=1f;
            preferences.edit().remove("x_"+i).remove("y_"+i).remove("size_"+i).apply();
        }
        initializeDefaults(Math.max(1,getWidth()),Math.max(1,getHeight()));
        selectedButton=-1;
        invalidate();
    }
    private Button[] buttons(float w, float h) {
        initializeDefaults(w,h);
        Button[] out = new Button[BUTTONS];
        for (int i=0;i<BUTTONS;i++) {
            out[i]=new Button(buttonX[i]*w,buttonY[i]*h,dp(23)*buttonScale[i],i);
        }
        return out;
    }
    private int hitButton(float x,float y) {
        for (Button b:buttons(getWidth(),getHeight())) {
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
            if(f.kind==0) { buttons |= BITS[f.button]; continue; }
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
    private void enterEditor() {
        fingers.clear();
        TotkActivity.sendTouchPadState(0,0,0,0,0);
        editor=true; selectedButton=-1; invalidate();
    }
    private void leaveEditor() {
        editor=false; editPointer=-1; selectedButton=-1;
        TotkActivity.sendTouchPadState(0,0,0,0,0);
        invalidate();
    }
    private void editorDown(int id,float x,float y) {
        if(y<dp(58)) {
            if(x>getWidth()*.82f) { leaveEditor(); return; }
            if(x<getWidth()*.18f) { resetLayout(); return; }
            if(x<getWidth()*.38f) {
                if(selectedButton>=0) { buttonScale[selectedButton]=Math.max(.65f,buttonScale[selectedButton]-.1f); saveButton(selectedButton); }
            } else if(x<getWidth()*.56f) {
                if(selectedButton>=0) { buttonScale[selectedButton]=Math.min(1.8f,buttonScale[selectedButton]+.1f); saveButton(selectedButton); }
            } else if(x<getWidth()*.70f) setOpacity(opacity-.05f);
            else if(x<getWidth()*.82f) setOpacity(opacity+.05f);
            invalidate(); return;
        }
        int hit=hitButton(x,y);
        if(hit>=0) {
            selectedButton=hit; editPointer=id;
            buttonX[hit]=x/Math.max(1,getWidth()); buttonY[hit]=y/Math.max(1,getHeight());
            invalidate();
        }
    }
    private void editorMove(int id,float x,float y) {
        if(id!=editPointer || selectedButton<0)return;
        float w=Math.max(1,getWidth()),h=Math.max(1,getHeight());
        float margin=dp(28);
        buttonX[selectedButton]=Math.max(margin/w,Math.min(1-margin/w,x/w));
        buttonY[selectedButton]=Math.max(margin/h,Math.min(1-margin/h,y/h));
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked(), index=event.getActionIndex();
        int id=event.getPointerId(index);
        if(editor) {
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) editorDown(id,event.getX(index),event.getY(index));
            else if(action==MotionEvent.ACTION_MOVE) for(int p=0;p<event.getPointerCount();p++) editorMove(event.getPointerId(p),event.getX(p),event.getY(p));
            else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
                if(id==editPointer) { if(selectedButton>=0)saveButton(selectedButton); editPointer=-1; }
            } else if(action==MotionEvent.ACTION_CANCEL) editPointer=-1;
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            if(event.getX(index)>getWidth()*.43f && event.getX(index)<getWidth()*.57f && event.getY(index)<dp(48)) {
                enterEditor(); return true;
            }
            addFinger(event,index); sendState(); return true;
        }
        if(action==MotionEvent.ACTION_MOVE) {
            for(int i=0;i<event.getPointerCount();i++) {
                Finger f=fingers.get(event.getPointerId(i));
                if(f!=null) updateFinger(f,event,i);
            }
            sendState(); return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_CANCEL) {
            if(action==MotionEvent.ACTION_CANCEL) fingers.clear();
            else fingers.remove(id);
            sendState(); return true;
        }
        return true;
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
            paint.setStyle(editor && selectedButton==b.index ? Paint.Style.STROKE : Paint.Style.FILL);
            circle(canvas,b.x,b.y,b.r,pressed || (editor && selectedButton==b.index));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(LABELS[b.index],b.x,b.y+dp(5),paint);
        }
        float radius=dp(68);
        drawStick(canvas,getWidth()*.22f,getHeight()*.80f,radius,1);
        drawStick(canvas,getWidth()*.78f,getHeight()*.80f,radius,2);
        if(editor) drawEditor(canvas);
        else {
            paint.setAlpha(210); paint.setTextSize(dp(20)); paint.setStyle(Paint.Style.FILL);
            canvas.drawText("✎",getWidth()*.5f,dp(27),paint);
        }
    }
    private void drawEditor(Canvas canvas) {
        float h=dp(58);
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(220);
        canvas.drawRoundRect(dp(6),dp(5),getWidth()-dp(6),h,dp(10),dp(10),paint);
        paint.setAlpha(255); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("PADRÃO",getWidth()*.10f,dp(27),paint);
        canvas.drawText("−",getWidth()*.27f,dp(27),paint);
        canvas.drawText("+",getWidth()*.47f,dp(27),paint);
        canvas.drawText("OP−",getWidth()*.63f,dp(27),paint);
        canvas.drawText("OP+",getWidth()*.76f,dp(27),paint);
        canvas.drawText("OK",getWidth()*.91f,dp(27),paint);
        if(selectedButton>=0) {
            paint.setAlpha(220); paint.setTextSize(dp(10));
            canvas.drawText(LABELS[selectedButton]+" selecionado · arraste para mover",getWidth()*.5f,h+dp(16),paint);
        }
    }
    private void drawStick(Canvas c,float x,float y,float r,int kind) {
        Finger active=null;
        for(int i=0;i<fingers.size();i++){Finger f=fingers.valueAt(i);if(f.kind==kind){active=f;break;}}
        paint.setAlpha((int)(255*opacity)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        c.drawCircle(active==null?x:active.ox,active==null?y:active.oy,r,paint);
        float kx=active==null?x:active.x,ky=active==null?y:active.y;
        paint.setStyle(Paint.Style.FILL); c.drawCircle(kx,ky,dp(22),paint);
    }
}
