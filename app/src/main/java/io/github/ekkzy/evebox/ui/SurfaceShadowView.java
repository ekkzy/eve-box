package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

/** A separate cast-shadow plane: it cannot be clipped by the glass surface's outline. */
public final class SurfaceShadowView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape=new RectF();
    private final float radius,blur;
    private final int halo,opacity;
    private Gpu gpu;
    private int recordingCount;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener preDraw=()->{
        if(gpu!=null&&!gpu.ready()){releaseGpu();invalidate();}
        return true;
    };
    public SurfaceShadowView(Context context,float radius,float blur,int opacity){
        super(context);this.radius=radius;this.blur=blur;this.opacity=opacity;halo=(int)Math.ceil(blur*2);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setFocusable(false);
    }
    public int halo(){return halo;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();observe();}
    @Override protected void onDetachedFromWindow(){unobserve();releaseGpu();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){
        super.onWindowVisibilityChanged(visibility);
        if(visibility==VISIBLE){observe();invalidate();}else{unobserve();releaseGpu();}
    }
    @Override protected void onVisibilityChanged(View changedView,int visibility){
        super.onVisibilityChanged(changedView,visibility);
        if(visibility==VISIBLE){observe();invalidate();}else{unobserve();releaseGpu();}
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        shape.set(halo,halo,w-halo,h-halo);releaseGpu();
    }
    @Override protected void onDraw(Canvas canvas){
        if(Build.VERSION.SDK_INT>=31&&canvas.isHardwareAccelerated()){
            if(gpu==null||!gpu.ready()){
                releaseGpu();gpu=new Api31(getWidth(),getHeight(),shape,radius,blur,opacity);recordingCount++;
            }
            gpu.draw(canvas);
        }else{
            // A bounded vector approximation on older/software canvases, no bitmap mask.
            for(int i=12;i>=0;i--){float spread=blur*i/12f;paint.setColor(Math.max(1,opacity/16)<<24);canvas.drawRoundRect(shape.left-spread,shape.top-spread,shape.right+spread,shape.bottom+spread,radius+spread,radius+spread,paint);}
        }
    }
    private void observe(){
        if(Build.VERSION.SDK_INT<31||!isAttachedToWindow()||getWindowVisibility()!=VISIBLE||!isShown())return;
        ViewTreeObserver current=getViewTreeObserver();if(observer==current)return;
        unobserve();observer=current;observer.addOnPreDrawListener(preDraw);
    }
    private void unobserve(){
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(preDraw);observer=null;
    }
    private void releaseGpu(){if(gpu!=null){gpu.release();gpu=null;}}
    int gpuNodeCount(){GlassBackdrop.checkMain();return gpu==null?0:2;}
    int getRecordingCount(){GlassBackdrop.checkMain();return recordingCount;}
    private interface Gpu{
        boolean ready();void draw(Canvas canvas);void release();
    }
    @android.annotation.TargetApi(31)
    private static final class Api31 implements Gpu{
        private final RenderNode filtered=new RenderNode("Eve Box shadow filter");
        private final RenderNode raster=new RenderNode("Eve Box shadow raster");
        Api31(int width,int height,RectF shape,float radius,float blur,int opacity){
            filtered.setPosition(0,0,width,height);filtered.setClipToBounds(true);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(opacity<<24);
            Canvas recording=filtered.beginRecording(width,height);
            try{recording.drawRoundRect(shape,radius,radius,paint);}finally{filtered.endRecording();}
            filtered.setRenderEffect(RenderEffect.createBlurEffect(blur,blur,Shader.TileMode.CLAMP));
            // Filtered snapshots are keyed by the caller's clip. Keep that clip
            // constant, and let moving parents sample an unfiltered raster layer.
            raster.setPosition(0,0,width,height);raster.setClipToBounds(true);raster.setUseCompositingLayer(true,null);
            recording=raster.beginRecording(width,height);
            try{recording.clipRect(0,0,width,height);recording.drawRenderNode(filtered);}finally{raster.endRecording();}
        }
        @Override public boolean ready(){return raster.hasDisplayList()&&filtered.hasDisplayList();}
        @Override public void draw(Canvas canvas){canvas.drawRenderNode(raster);}
        @Override public void release(){
            raster.discardDisplayList();raster.setUseCompositingLayer(false,null);
            filtered.discardDisplayList();filtered.setRenderEffect(null);
        }
    }
}
