package com.yuanyang.camera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Center-fit photos with native Android pinch, pan and double-tap gestures. */
final class ZoomPhotoView extends ImageView {
    private static final float MAX_ZOOM=4f;
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private final Runnable previous,next;
    private float zoom=1f,offsetX,offsetY;
    private boolean multiTouch;
    private boolean navigated;
    ZoomPhotoView(Context context,Runnable previous,Runnable next) {
        super(context);
        this.previous=previous;
        this.next=next;
        setScaleType(ScaleType.MATRIX);
        scaler=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d) {
                return changeZoom(zoom*d.getScaleFactor(),d.getFocusX(),d.getFocusY());
            }
        });
        scaler.setQuickScaleEnabled(false); // Double tap toggles zoom instead.
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){return performClick();}
            @Override public boolean onDoubleTap(MotionEvent e) {
                return changeZoom(zoom>1.01f?1f:2.5f,e.getX(),e.getY());
            }
            @Override public boolean onScroll(MotionEvent from,MotionEvent to,float dx,float dy) {
                if(!multiTouch&&zoom>1.01f){
                    offsetX-=dx;offsetY-=dy;applyTransform();
                }
                return true;
            }
        });
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            multiTouch=false;navigated=false;
            startX=event.getX();startY=event.getY();
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN&&!multiTouch) {
            multiTouch=true;
            MotionEvent cancel=MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        scaler.onTouchEvent(event);
        if(!multiTouch) {
            gestures.onTouchEvent(event);
            if(action==MotionEvent.ACTION_UP&&!navigated&&zoom<=1.01f) {
                float dx=event.getX()-startX,dy=event.getY()-startY;
                if(Math.abs(dx)>45f*getResources().getDisplayMetrics().density &&
                        Math.abs(dx)>Math.abs(dy)*1.3f) {
                    navigated=true;
                    (dx<0?next:previous).run();
                }
            }
        }
        return true;
    }
    private float startX,startY;
    @Override public boolean performClick(){return super.performClick();}
    void resetViewport(){
        zoom=1f;offsetX=offsetY=0;
        Drawable d=getDrawable();
        if(d!=null && getWidth()>0 && getHeight()>0) {
            float scale=fitScale(d);
            offsetX=(getWidth()-d.getIntrinsicWidth()*scale)/2;
            offsetY=(getHeight()-d.getIntrinsicHeight()*scale)/2;
        }
        applyTransform();
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        super.onSizeChanged(w,h,ow,oh);resetViewport();
    }
    private boolean changeZoom(float level,float x,float y) {
        if(getDrawable()==null||getWidth()==0||getHeight()==0)return false;
        float next=Math.max(1f,Math.min(MAX_ZOOM,level));
        float factor=next/zoom;
        offsetX=x-(x-offsetX)*factor;
        offsetY=y-(y-offsetY)*factor;
        zoom=next;
        applyTransform();
        return true;
    }
    private float fitScale(Drawable d) {
        return Math.min((float)getWidth()/d.getIntrinsicWidth(),(float)getHeight()/d.getIntrinsicHeight());
    }
    private void applyTransform() {
        Drawable d=getDrawable();
        if(d==null||d.getIntrinsicWidth()<=0||d.getIntrinsicHeight()<=0||getWidth()==0||getHeight()==0) {
            transform.reset();setImageMatrix(transform);return;
        }
        float scale=fitScale(d)*zoom;
        offsetX=constrain(offsetX,getWidth(),d.getIntrinsicWidth()*scale);
        offsetY=constrain(offsetY,getHeight(),d.getIntrinsicHeight()*scale);
        transform.setScale(scale,scale);
        transform.postTranslate(offsetX,offsetY);
        setImageMatrix(transform);
    }
    static float constrain(float position,float viewport,float image) {
        return image<=viewport?(viewport-image)/2f:Math.max(viewport-image,Math.min(0f,position));
    }
}
