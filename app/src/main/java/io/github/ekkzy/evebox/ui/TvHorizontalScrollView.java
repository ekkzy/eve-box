package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

/** Native D-pad shelf scrolling reserves the final 1.06 focus outline. */
public final class TvHorizontalScrollView extends HorizontalScrollView implements Choreographer.FrameCallback {
    private final Rect focusEnvelope=new Rect();
    private final RectF finalFocus=new RectF();
    private final Matrix focusMatrix=new Matrix(),matrixStep=new Matrix();
    private final UiSpring offset=new UiSpring(.30f,.90f,0);
    private boolean planning,frameScheduled,stoppingNative;
    private long lastFrameNanos;
    public TvHorizontalScrollView(Context context){this(context,null);}
    public TvHorizontalScrollView(Context context,AttributeSet attrs){this(context,attrs,android.R.attr.horizontalScrollViewStyle);}
    public TvHorizontalScrollView(Context context,AttributeSet attrs,int style){super(context,attrs,style);}
    public void setMotionAllowed(boolean allowed){
        if(isSmoothScrollingEnabled()==allowed)return;
        setSmoothScrollingEnabled(allowed);
        if(!allowed){
            stopSpring();
            // HorizontalScrollView.fling(0) can request a different child focus on
            // API 26. Two zero-distance calls enter the native <=250 ms abort path
            // without moving the current offset or changing focus.
            stopNative();
        }
    }
    @Override public boolean arrowScroll(int direction){return plan(()->super.arrowScroll(direction));}
    @Override public boolean pageScroll(int direction){return plan(()->super.pageScroll(direction));}
    @Override public boolean fullScroll(int direction){return plan(()->super.fullScroll(direction));}
    @Override public void requestChildFocus(View child,View focused){plan(()->{super.requestChildFocus(child,focused);return true;});}
    @Override public boolean requestChildRectangleOnScreen(View child,Rect rectangle,boolean immediate){
        if(immediate){stopSpring();stopNative();return super.requestChildRectangleOnScreen(child,rectangle,true);}
        return plan(()->super.requestChildRectangleOnScreen(child,rectangle,true));
    }
    private interface NativeAction{boolean run();}
    public boolean isScrollAnimating(){return frameScheduled;}
    private boolean plan(NativeAction action){
        if(planning||!isSmoothScrollingEnabled()||!isLaidOut())return action.run();
        int from=getScrollX(),y=getScrollY();float velocity=frameScheduled?offset.velocity:0;long previousFrame=frameScheduled?lastFrameNanos:0;
        stopSpring();stopNative();planning=true;super.setSmoothScrollingEnabled(false);boolean result;
        int destination;try{result=action.run();destination=getScrollX();super.scrollTo(from,y);}finally{super.setSmoothScrollingEnabled(true);planning=false;}
        offset.snap(from);offset.target=destination;offset.velocity=velocity;
        lastFrameNanos=previousFrame;
        offset.tolerance(Math.max(1,Math.abs(destination-from)));if(!offset.settled())schedule();return result;
    }
    @Override protected void onScrollChanged(int l,int t,int oldl,int oldt){if(!planning)super.onScrollChanged(l,t,oldl,oldt);}
    private void stopNative(){if(stoppingNative)return;stoppingNative=true;try{super.smoothScrollBy(0,0);super.smoothScrollBy(0,0);super.computeScroll();}finally{stoppingNative=false;}}
    private void schedule(){if(frameScheduled||!isAttachedToWindow())return;frameScheduled=true;Choreographer.getInstance().postFrameCallback(this);}
    private void stopSpring(){if(frameScheduled)Choreographer.getInstance().removeFrameCallback(this);frameScheduled=false;lastFrameNanos=0;}
    @Override public void doFrame(long time){
        frameScheduled=false;if(!isAttachedToWindow()||!isSmoothScrollingEnabled()){lastFrameNanos=0;return;}
        float dt=lastFrameNanos==0?1/60f:Math.max(0,(time-lastFrameNanos)/1e9f);lastFrameNanos=time;
        boolean moving=offset.step(dt);super.scrollTo(Math.round(offset.value),getScrollY());
        if(!moving)moving=revealFinalFocus();
        if(moving)schedule();else lastFrameNanos=0;
    }
    @Override public void scrollTo(int x,int y){
        if(getChildCount()>0){View content=getChildAt(0);
            x=Math.max(0,Math.min(x,Math.max(0,content.getWidth()-getWidth()+getPaddingLeft()+getPaddingRight())));
            y=Math.max(0,Math.min(y,Math.max(0,content.getHeight()-getHeight()+getPaddingTop()+getPaddingBottom())));
        }
        if(x==getScrollX()&&y==getScrollY())return;
        if(!planning&&!stoppingNative&&offset!=null){stopSpring();stopNative();}super.scrollTo(x,y);
    }
    private boolean revealFinalFocus(){
        View focused=findFocus();if(focused==null||focused==this||!focused.isAttachedToWindow())return false;
        if(!TvScrollView.descendantBounds(this,focused,finalFocus,focusMatrix,matrixStep))return false;
        if(finalFocus.width()>getWidth())return false;
        int delta=finalFocus.right>getWidth()?(int)Math.ceil(finalFocus.right-getWidth()):finalFocus.left<0?(int)Math.floor(finalFocus.left):0;
        int maximum=getChildCount()==0?0:Math.max(0,getChildAt(0).getWidth()-getWidth()+getPaddingLeft()+getPaddingRight());
        int destination=Math.max(0,Math.min(maximum,getScrollX()+delta));if(destination==getScrollX())return false;
        offset.snap(getScrollX());offset.target=destination;offset.tolerance(Math.abs(delta));return true;
    }
    @Override public void fling(int velocityX){stopSpring();if(isSmoothScrollingEnabled())super.fling(velocityX);else stopNative();}
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)stopSpring();return super.onTouchEvent(event);}
    @Override protected void onDetachedFromWindow(){stopSpring();super.onDetachedFromWindow();}
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect){
        if(rect.isEmpty()||getChildCount()==0)return 0;
        int margin=(int)Math.ceil(rect.width()*.03f)+8;
        focusEnvelope.set(rect);focusEnvelope.inset(-margin,0);
        int left=getScrollX(),right=left+getWidth(),delta=0;
        if(focusEnvelope.right>right&&focusEnvelope.left>left){
            delta=focusEnvelope.width()>getWidth()?focusEnvelope.left-left:focusEnvelope.right-right;
        }else if(focusEnvelope.left<left&&focusEnvelope.right<right){
            delta=focusEnvelope.width()>getWidth()?focusEnvelope.right-right:focusEnvelope.left-left;
        }
        // The framework rectangle helper caps its rightward delta at child.right.
        // That drops this outline margin on the last card, even though the real
        // native scroll range includes our trailing padding. Use that same padded
        // range as HorizontalScrollView.scrollTo/smoothScrollBy instead.
        int viewport=Math.max(0,getWidth()-getPaddingLeft()-getPaddingRight());
        int maximum=Math.max(0,getChildAt(0).getWidth()-viewport);
        int target=Math.max(0,Math.min(maximum,left+delta));
        return target-left;
    }
}
