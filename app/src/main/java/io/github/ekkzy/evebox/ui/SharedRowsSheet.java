package io.github.ekkzy.evebox.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import java.util.Objects;

/** One live material behind all list pages; page text never owns or fades its blur. */
public final class SharedRowsSheet extends FrameLayout implements AutoCloseable {
    public static final class Spec {
        public final int x,y,width,height,radius;
        public final boolean light;
        public Spec(int x,int y,int width,int height,int radius,boolean light){
            this.x=x;this.y=y;this.width=width;this.height=height;this.radius=radius;this.light=light;
        }
        @Override public boolean equals(Object other){
            if(!(other instanceof Spec))return false;Spec s=(Spec)other;
            return x==s.x&&y==s.y&&width==s.width&&height==s.height&&radius==s.radius&&light==s.light;
        }
        @Override public int hashCode(){return Objects.hash(x,y,width,height,radius,light);}
    }
    private final GlassBackdrop backdrop;
    private final GlassScene scene;
    private final FrameLayout design,material;
    private final int duration;
    private Spec spec;
    private View face;
    private GlassDrawable glass;
    private boolean wanted,active=true,animating,closed;
    private int generation;

    public SharedRowsSheet(Context context,GlassBackdrop backdrop,GlassScene scene){
        super(context);this.backdrop=backdrop;this.scene=scene;
        setTag("shared-rows-sheet");setClipChildren(false);setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        design=new FrameLayout(context);design.setClipChildren(false);design.setClipToPadding(false);
        addView(design,new LayoutParams(1280,720,Gravity.CENTER));
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            float scale=Math.min((r-l)/1280f,(b-t)/720f);design.setScaleX(scale);design.setScaleY(scale);
        });
        material=new FrameLayout(context);material.setClipChildren(false);material.setClipToPadding(false);
        material.setVisibility(INVISIBLE);design.addView(material,new LayoutParams(1,1));
        duration=getResources().getInteger(android.R.integer.config_shortAnimTime);
    }
    public void present(Spec next,boolean animate){
        if(closed)return;
        boolean nextWanted=next!=null,changed=nextWanted&&!next.equals(spec);
        if(changed)rebuild(next);
        if(nextWanted==wanted){
            if(animating&&(!animate||!active||!ValueAnimator.areAnimatorsEnabled()))settle();
            else if(changed)updateLayer();
            return;
        }
        wanted=nextWanted;int token=++generation;
        material.animate().setListener(null);material.animate().cancel();animating=false;
        if(!animate||!active||!ValueAnimator.areAnimatorsEnabled()){settle();return;}
        if(wanted&&material.getVisibility()!=VISIBLE){material.setAlpha(0);material.setVisibility(VISIBLE);}
        animating=true;updateLayer();
        material.animate().alpha(wanted?1:0).setDuration(duration).setListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animator){
                if(token!=generation||closed)return;animating=false;
                if(!wanted){material.setVisibility(INVISIBLE);updateLayer();}
            }
        }).start();
    }
    private void rebuild(Spec next){
        if(glass!=null){glass.dispose();glass=null;}
        if(face!=null)face.setLayerType(LAYER_TYPE_NONE,null);
        material.removeAllViews();spec=next;
        SurfaceShadowView shadow=new SurfaceShadowView(getContext(),next.radius,14f,next.light?0x14:0x24);
        int halo=shadow.halo();LayoutParams group=new LayoutParams(next.width+2*halo,next.height+2*halo+4);
        group.leftMargin=next.x-halo;group.topMargin=next.y-halo;material.setLayoutParams(group);
        LayoutParams shade=new LayoutParams(next.width+2*halo,next.height+2*halo);shade.topMargin=4;material.addView(shadow,shade);
        face=new View(getContext());face.setTag("shared-rows-glass-face");
        glass=backdrop.drawable(face,scene,next.radius,next.light,true);face.setBackground(glass);
        face.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),next.radius);}});
        face.setClipToOutline(true);
        LayoutParams content=new LayoutParams(next.width,next.height);content.leftMargin=halo;content.topMargin=halo;material.addView(face,content);
    }
    private void updateLayer(){
        // Only the bounded face is cached. Source invalidation still redraws this
        // hardware texture; neither text nor wallpaper is baked into a bitmap.
        if(face!=null)face.setLayerType(active&&(wanted||animating)?LAYER_TYPE_HARDWARE:LAYER_TYPE_NONE,null);
    }
    public boolean isAnimating(){return animating;}
    public void pause(){active=false;settle();updateLayer();}
    public void resume(){active=true;updateLayer();}
    private void settle(){
        generation++;material.animate().setListener(null);material.animate().cancel();animating=false;
        material.setAlpha(wanted?1:0);material.setVisibility(wanted?VISIBLE:INVISIBLE);updateLayer();
    }
    @Override public void close(){
        if(closed)return;closed=true;wanted=false;settle();
        if(glass!=null){glass.dispose();glass=null;}material.removeAllViews();face=null;
    }
    @Override protected void onDetachedFromWindow(){pause();super.onDetachedFromWindow();}
}
