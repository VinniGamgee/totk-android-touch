package com.aure.totk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/** Transparent, multi-touch Switch-style controller with a persistent in-game layout editor. */
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
    private final float[] stickX = new float[]{.22f, .78f};
    private final float[] stickY = new float[]{.80f, .80f};
    private final float[] stickScale = new float[]{1f, 1f};
    private float density;
    private float opacity = .34f;
    private float scale = 1f;
    private boolean editor;
    private int selectedControl = -1; // 0..15 buttons, 16 left stick, 17 right stick
    private int editPointer = -1;

    private static final class Finger {
        int kind; // 0 button, 1 left stick, 2 right stick
        int button;
        float ox, oy, x, y;
    }
    private static final class Button {
        float x, y, r;
        int index;
        Button(float x, float y, float r, int index) {
            this.x=x; this.y=y; this.r=r; this.index=index;
        }
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

    private float dp(float value) { return value * density * scale; }
    void setOpacity(float value) {
        opacity=Math.max(.12f,Math.min(.8f,value));
        preferences.edit().putFloat("opacity",opacity).apply();
        invalidate();
    }
    void setScale(float value) {
        scale=Math.max(.75f,Math.min(1.35f,value));
        invalidate();
    }
    private float diamond(float h) { return Math.min(dp(52), h * .14f); }

    private void loadLayout() {
        for (int i=0;i<BUTTONS;i++) {
            buttonX[i] = preferences.getFloat("x_"+i, Float.NaN);
            buttonY[i] = preferences.getFloat("y_"+i, Float.NaN);
            buttonScale[i] = preferences.getFloat("size_"+i, 1f);
        }
        for (int i=0;i<2;i++) {
            stickX[i] = preferences.getFloat("stick_x_"+i, i==0?.22f:.78f);
            stickY[i] = preferences.getFloat("stick_y_"+i, .80f);
            stickScale[i] = preferences.getFloat("stick_size_"+i, 1f);
        }
    }

    private float[] defaultCenter(int index, float w, float h) {
        float inset=w*.14f, d=diamond(h), cx=w-inset, cy=h*.42f;
        float px=inset, py=cy;
        switch(index) {
            case 0: return new float[]{cx+d,cy};
            case 1: return new float[]{cx,cy+d};
            case 2: return new float[]{cx,cy-d};
            case 3: return new float[]{cx-d,cy};
            case 4: return new float[]{w*.05f,h*.12f};
            case 5: return new float[]{w*.16f,h*.12f};
            case 6: return new float[]{w*.84f,h*.12f};
            case 7: return new float[]{w*.95f,h*.12f};
            case 8: return new float[]{w*.40f,h*.12f};
            case 9: return new float[]{w*.60f,h*.12f};
            case 10: return new float[]{px-d,py};
            case 11: return new float[]{px,py-d};
            case 12: return new float[]{px+d,py};
            case 13: return new float[]{px,py+d};
            case 14: return new float[]{w*.05f,h*.88f};
            default: return new float[]{w*.95f,h*.88f};
        }
    }

    private void initializeDefaults(float w,float h) {
        for(int i=0;i<BUTTONS;i++) {
            if(Float.isNaN(buttonX[i]) || Float.isNaN(buttonY[i])) {
                float[] p=defaultCenter(i,w,h);
                buttonX[i]=p[0]/w; buttonY[i]=p[1]/h;
            }
        }
    }

    private void saveControl(int index) {
        if(index>=BUTTONS) {
            int stick=index-BUTTONS;
            preferences.edit().putFloat("stick_x_"+stick,stickX[stick])
                    .putFloat("stick_y_"+stick,stickY[stick])
                    .putFloat("stick_size_"+stick,stickScale[stick]).apply();
        } else {
            preferences.edit().putFloat("x_"+index,buttonX[index])
                    .putFloat("y_"+index,buttonY[index])
                    .putFloat("size_"+index,buttonScale[index]).apply();
        }
    }

    private void resetLayout() {
        SharedPreferences.Editor edit=preferences.edit().putFloat("opacity",.34f);
        opacity=.34f;
        for(int i=0;i<BUTTONS;i++) {
            buttonX[i]=Float.NaN; buttonY[i]=Float.NaN; buttonScale[i]=1f;
            edit.remove("x_"+i).remove("y_"+i).remove("size_"+i);
        }
        for(int i=0;i<2;i++) {
            stickX[i]=i==0?.22f:.78f; stickY[i]=.80f; stickScale[i]=1f;
            edit.remove("stick_x_"+i).remove("stick_y_"+i).remove("stick_size_"+i);
        }
        edit.apply();
        initializeDefaults(Math.max(1,getWidth()),Math.max(1,getHeight()));
        selectedControl=-1;
        invalidate();
    }

    private Button[] buttons(float w,float h) {
        initializeDefaults(w,h);
        Button[] out=new Button[BUTTONS];
        for(int i=0;i<BUTTONS;i++)
            out[i]=new Button(buttonX[i]*w,buttonY[i]*h,dp(23)*buttonScale[i],i);
        return out;
    }

    private int hitButton(float x,float y) {
        for(Button b:buttons(getWidth(),getHeight())) {
            float dx=x-b.x,dy=y-b.y;
            if(dx*dx+dy*dy<=b.r*b.r*1.25f)return b.index;
        }
        return -1;
    }

    private int hitStick(float x,float y) {
        for(int i=0;i<2;i++) {
            float dx=x-stickX[i]*getWidth(),dy=y-stickY[i]*getHeight();
            float r=dp(68)*stickScale[i];
            if(dx*dx+dy*dy<=r*r)return BUTTONS+i;
        }
        return -1;
    }

    private void updateFinger(Finger f,MotionEvent e,int index) {
        f.x=e.getX(index); f.y=e.getY(index);
    }

    private void sendState() {
        long buttons=0;
        int[] axes=new int[4];
        for(int i=0;i<fingers.size();i++) {
            Finger f=fingers.valueAt(i);
            if(f.kind==0) { buttons|=BITS[f.button]; continue; }
            int stick=f.kind-1;
            float radius=dp(68)*stickScale[stick];
            float dx=f.x-f.ox,dy=f.oy-f.y;
            float distance=(float)Math.hypot(dx,dy);
            if(distance>radius) { dx*=radius/distance; dy*=radius/distance; }
            int ax=Math.round(dx/radius*32767f),ay=Math.round(dy/radius*32767f);
            int base=f.kind==1?0:2;
            if(Math.abs(ax)>Math.abs(axes[base]))axes[base]=ax;
            if(Math.abs(ay)>Math.abs(axes[base+1]))axes[base+1]=ay;
        }
        TotkActivity.sendTouchPadState(buttons,axes[0],axes[1],axes[2],axes[3]);
        invalidate();
    }

    private void addFinger(MotionEvent e,int index) {
        int id=e.getPointerId(index);
        float x=e.getX(index),y=e.getY(index);
        Finger f=new Finger(); f.x=x; f.y=y;
        int button=hitButton(x,y);
        if(button>=0) {
            f.kind=0; f.button=button;
        } else {
            int stick=hitStick(x,y);
            if(stick>=BUTTONS) {
                int s=stick-BUTTONS;
                f.kind=s+1; f.ox=stickX[s]*getWidth(); f.oy=stickY[s]*getHeight();
            } else {
                // Preserve the original floating-stick behavior outside the two configured zones.
                f.kind=x<getWidth()*.5f?1:2; f.ox=x; f.oy=y;
            }
        }
        fingers.put(id,f);
    }

    private void enterEditor() {
        fingers.clear();
        TotkActivity.sendTouchPadState(0,0,0,0,0);
        editor=true; selectedControl=-1; invalidate();
    }

    private void leaveEditor() {
        editor=false; editPointer=-1; selectedControl=-1;
        TotkActivity.sendTouchPadState(0,0,0,0,0);
        invalidate();
    }

    private void adjustSelectedSize(float delta) {
        if(selectedControl<0)return;
        if(selectedControl<BUTTONS)
            buttonScale[selectedControl]=Math.max(.65f,Math.min(1.8f,buttonScale[selectedControl]+delta));
        else {
            int stick=selectedControl-BUTTONS;
            stickScale[stick]=Math.max(.65f,Math.min(1.8f,stickScale[stick]+delta));
        }
        saveControl(selectedControl);
        invalidate();
    }

    private void editorDown(int id,float x,float y) {
        // Compact toolbar stays above the shoulder buttons in landscape orientation.
        if(y<dp(40)) {
            if(x>getWidth()*.82f) { leaveEditor(); return; }
            if(x<getWidth()*.18f) { resetLayout(); return; }
            if(x<getWidth()*.38f) adjustSelectedSize(-.1f);
            else if(x<getWidth()*.56f) adjustSelectedSize(.1f);
            else if(x<getWidth()*.70f) setOpacity(opacity-.05f);
            else if(x<getWidth()*.82f) setOpacity(opacity+.05f);
            return;
        }
        int hit=hitButton(x,y);
        if(hit<0)hit=hitStick(x,y);
        if(hit<0)return;
        selectedControl=hit; editPointer=id;
        if(hit<BUTTONS) {
            buttonX[hit]=x/Math.max(1,getWidth());
            buttonY[hit]=y/Math.max(1,getHeight());
        } else {
            int stick=hit-BUTTONS;
            stickX[stick]=x/Math.max(1,getWidth());
            stickY[stick]=y/Math.max(1,getHeight());
        }
        invalidate();
    }

    private void editorMove(int id,float x,float y) {
        if(id!=editPointer || selectedControl<0)return;
        float w=Math.max(1,getWidth()),h=Math.max(1,getHeight()),margin=dp(34);
        if(selectedControl<BUTTONS) {
            buttonX[selectedControl]=Math.max(margin/w,Math.min(1-margin/w,x/w));
            buttonY[selectedControl]=Math.max(margin/h,Math.min(1-margin/h,y/h));
        } else {
            int stick=selectedControl-BUTTONS;
            stickX[stick]=Math.max(margin/w,Math.min(1-margin/w,x/w));
            stickY[stick]=Math.max(margin/h,Math.min(1-margin/h,y/h));
        }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked(),index=event.getActionIndex();
        int id=event.getPointerId(index);
        if(editor) {
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN)
                editorDown(id,event.getX(index),event.getY(index));
            else if(action==MotionEvent.ACTION_MOVE)
                for(int p=0;p<event.getPointerCount();p++)
                    editorMove(event.getPointerId(p),event.getX(p),event.getY(p));
            else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
                if(id==editPointer) {
                    if(selectedControl>=0)saveControl(selectedControl);
                    editPointer=-1;
                }
            } else if(action==MotionEvent.ACTION_CANCEL)editPointer=-1;
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            if(event.getX(index)>getWidth()*.43f && event.getX(index)<getWidth()*.57f
                    && event.getY(index)<dp(48)) {
                enterEditor(); return true;
            }
            addFinger(event,index); sendState(); return true;
        }
        if(action==MotionEvent.ACTION_MOVE) {
            for(int i=0;i<event.getPointerCount();i++) {
                Finger f=fingers.get(event.getPointerId(i));
                if(f!=null)updateFinger(f,event,i);
            }
            sendState(); return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP
                || action==MotionEvent.ACTION_CANCEL) {
            if(action==MotionEvent.ACTION_CANCEL)fingers.clear();
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
        if(getWidth()==0 || getHeight()==0)return;
        int alpha=(int)(255*opacity);
        paint.setColor(0xffffffff); paint.setAlpha(alpha); paint.setStrokeWidth(dp(2));
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(14));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        for(Button b:buttons(getWidth(),getHeight())) {
            boolean pressed=false;
            for(int i=0;i<fingers.size();i++) {
                Finger f=fingers.valueAt(i);
                if(f.kind==0 && f.button==b.index) { pressed=true; break; }
            }
            paint.setAlpha(pressed?Math.min(255,alpha+55):alpha);
            paint.setStyle(Paint.Style.FILL);
            circle(canvas,b.x,b.y,b.r,pressed);
            if(editor && selectedControl==b.index) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                canvas.drawCircle(b.x,b.y,b.r+dp(3),paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(LABELS[b.index],b.x,b.y+dp(5),paint);
        }
        float radius=dp(68);
        drawStick(canvas,stickX[0]*getWidth(),stickY[0]*getHeight(),radius*stickScale[0],1);
        drawStick(canvas,stickX[1]*getWidth(),stickY[1]*getHeight(),radius*stickScale[1],2);
        if(editor)drawEditor(canvas);
        else {
            paint.setAlpha(210); paint.setTextSize(dp(20)); paint.setStyle(Paint.Style.FILL);
            canvas.drawText("✎",getWidth()*.5f,dp(27),paint);
        }
    }

    private void drawEditor(Canvas canvas) {
        float h=dp(40);
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(220);
        canvas.drawRoundRect(dp(6),dp(5),getWidth()-dp(6),h,dp(10),dp(10),paint);
        paint.setAlpha(255); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("PADRÃO",getWidth()*.10f,dp(27),paint);
        canvas.drawText("−",getWidth()*.27f,dp(27),paint);
        canvas.drawText("+",getWidth()*.47f,dp(27),paint);
        canvas.drawText("OP−",getWidth()*.63f,dp(27),paint);
        canvas.drawText("OP+",getWidth()*.76f,dp(27),paint);
        canvas.drawText("OK",getWidth()*.91f,dp(27),paint);
        if(selectedControl>=0) {
            paint.setAlpha(220); paint.setTextSize(dp(10));
            String label=selectedControl<BUTTONS?LABELS[selectedControl]
                    :(selectedControl==BUTTONS?"ANALÓGICO ESQ.":"ANALÓGICO DIR.");
            canvas.drawText(label+" selecionado · arraste para mover",getWidth()*.5f,h+dp(16),paint);
        }
    }

    private void drawStick(Canvas c,float x,float y,float r,int kind) {
        Finger active=null;
        for(int i=0;i<fingers.size();i++) {
            Finger f=fingers.valueAt(i);
            if(f.kind==kind) { active=f; break; }
        }
        paint.setAlpha((int)(255*opacity)); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(editor && selectedControl==BUTTONS+kind-1?dp(4):dp(2));
        c.drawCircle(active==null?x:active.ox,active==null?y:active.oy,r,paint);
        float kx=active==null?x:active.x,ky=active==null?y:active.y;
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(kx,ky,dp(22)*stickScale[kind-1],paint);
    }
}
