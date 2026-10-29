package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.MainActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated native paging fixture; app preferences and system motion policy are untouched. */
@RunWith(AndroidJUnit4.class)
public final class TvScrollViewPagingTest {
    @Before public void requireOwnedEmulator()throws Exception{
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("io.github.ekkzy.evebox.debug",InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    }

    @Test public void focusRectanglesAndAccessibilityLandOnWholePages(){
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            Fixture fixture=fixture(activity);
            try{
                main(()->{assertTrue(fixture.top.requestFocus());fixture.key(KeyEvent.KEYCODE_DPAD_DOWN);});
                settled(fixture,720);
                main(()->{assertSame(fixture.bottom,fixture.content.findFocus());assertTrue(fixture.last.requestFocus());});
                settled(fixture,720);
                main(()->assertTrue(fixture.scroll.requestChildRectangleOnScreen(fixture.content,new Rect(80,620,320,700),false)));
                settled(fixture,0);
                main(()->{
                    assertTrue(fixture.scroll.requestChildRectangleOnScreen(fixture.content,new Rect(80,780,320,880),true));
                    assertEquals(720,fixture.scroll.getScrollY());assertFalse(fixture.scroll.isScrollAnimating());
                    assertTrue(fixture.scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));
                });
                settled(fixture,0);
                main(()->assertTrue(fixture.scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null)));
                settled(fixture,720);
            }finally{main(fixture::close);}
        }
    }

    @Test public void restoreSmoothPositionsAndReducedMotionRespectPageModeOnly(){
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            Fixture fixture=fixture(activity);
            try{
                main(()->{
                    fixture.scroll.scrollTo(0,260);assertEquals(0,fixture.scroll.getScrollY());
                    fixture.scroll.scrollTo(0,480);assertEquals(720,fixture.scroll.getScrollY());
                    fixture.scroll.smoothScrollToPosition(260);
                    assertEquals("Starting the return must preserve the current presentation",720,fixture.scroll.getScrollY());
                });
                settled(fixture,0);
                main(()->{
                    fixture.scroll.smoothScrollToPosition(480);assertTrue(fixture.scroll.isScrollAnimating());
                    fixture.scroll.setMotionAllowed(false);
                    assertEquals("Policy-off settles the requested whole page",720,fixture.scroll.getScrollY());
                    assertFalse(fixture.scroll.isScrollAnimating());
                    fixture.scroll.scrollTo(0,260);assertEquals(0,fixture.scroll.getScrollY());
                    fixture.scroll.setPageHeight(0);fixture.scroll.scrollTo(0,260);
                    assertEquals("Ordinary scroll must keep a non-page offset",260,fixture.scroll.getScrollY());
                    fixture.scroll.setMotionAllowed(true);fixture.scroll.smoothScrollToPosition(350);
                });
                settled(fixture,350);
            }finally{main(fixture::close);}
        }
    }

    @Test public void realLayoutDuringRapidDownUpDownKeepsOneContinuousSpring()throws Exception{
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            Fixture fixture=fixture(activity);AtomicReference<PageProbe> probe=new AtomicReference<>();
            try{
                main(()->{assertTrue(fixture.top.requestFocus());PageProbe next=new PageProbe(fixture);probe.set(next);next.start();});
                assertTrue("Native moving/layout/reversal frames complete",probe.get().done.await(7,TimeUnit.SECONDS));
                if(probe.get().failure.get()!=null)throw new AssertionError("Paging frame probe",probe.get().failure.get());
            }finally{main(()->{if(probe.get()!=null)probe.get().cancel();fixture.close();});}
        }
    }

    private static final class PageProbe implements Choreographer.FrameCallback{
        final Fixture fixture;final CountDownLatch done=new CountDownLatch(1);final AtomicReference<Throwable> failure=new AtomicReference<>();
        final long deadline=SystemClock.uptimeMillis()+5000;
        boolean cancelled;int phase,layoutBefore,turningPosition;
        PageProbe(Fixture fixture){this.fixture=fixture;}
        void start(){fixture.key(KeyEvent.KEYCODE_DPAD_DOWN);Choreographer.getInstance().postFrameCallback(this);}
        @Override public void doFrame(long frameTime){
            if(cancelled)return;
            try{
                assertTrue("Frame probe deadline",SystemClock.uptimeMillis()<deadline);
                int current=fixture.scroll.getScrollY();
                assertTrue("Motion stays inside the two-page content",current>=0&&current<=720);
                if(phase==0&&current>0&&current<720){
                    assertTrue(fixture.scroll.isScrollAnimating());
                    fixture.scroll.scrollTo(0,current);
                    assertEquals(current,fixture.scroll.getScrollY());assertTrue(fixture.scroll.isScrollAnimating());
                    layoutBefore=fixture.content.layouts;fixture.content.requestLayout();
                    // Focus while layout is dirty also arms ScrollView's deferred descendant reveal.
                    assertTrue(fixture.top.requestFocus());assertTrue(fixture.bottom.requestFocus());
                    assertEquals("Retargets must not jump before drawing",current,fixture.scroll.getScrollY());phase=1;
                }else if(phase==1&&fixture.content.layouts>layoutBefore){
                    assertTrue("A real layout must not snap the in-flight page to its target",current>0&&current<720);
                    assertTrue(fixture.scroll.isScrollAnimating());
                    turningPosition=current;fixture.key(KeyEvent.KEYCODE_DPAD_UP);
                    assertEquals(current,fixture.scroll.getScrollY());assertSame(fixture.top,fixture.content.findFocus());phase=2;
                }else if(phase==2&&current<turningPosition-2){
                    fixture.key(KeyEvent.KEYCODE_DPAD_DOWN);
                    assertEquals(current,fixture.scroll.getScrollY());assertSame(fixture.bottom,fixture.content.findFocus());phase=3;
                }else if(phase==3&&!fixture.scroll.isScrollAnimating()){
                    assertEquals(720,current);assertSame(fixture.bottom,fixture.content.findFocus());done.countDown();return;
                }
                Choreographer.getInstance().postFrameCallback(this);
            }catch(Throwable error){failure.set(error);done.countDown();}
        }
        void cancel(){cancelled=true;Choreographer.getInstance().removeFrameCallback(this);}
    }

    private static Fixture fixture(ActivityScenario<MainActivity> activity){
        AtomicReference<Fixture> result=new AtomicReference<>();activity.onActivity(a->result.set(new Fixture(a)));
        Fixture fixture=result.get();long deadline=SystemClock.uptimeMillis()+4000;
        while(SystemClock.uptimeMillis()<deadline){
            if(onMain(()->fixture.scroll.getHeight()==720&&fixture.content.getHeight()==1440))return fixture;
            SystemClock.sleep(20);
        }
        main(fixture::close);throw new AssertionError("Paging fixture must have a 720 viewport and 1440 content");
    }
    private static void settled(Fixture fixture,int expected){
        long deadline=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<deadline){
            if(onMain(()->!fixture.scroll.isScrollAnimating()&&fixture.scroll.getScrollY()==expected))return;
            SystemClock.sleep(20);
        }
        main(()->{assertFalse(fixture.scroll.isScrollAnimating());assertEquals(expected,fixture.scroll.getScrollY());});
    }
    private interface Query<T>{T get();}
    private static <T>T onMain(Query<T> query){
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(query.get());}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Main-thread assertion",failure.get());return result.get();
    }
    private static void main(Runnable action){onMain(()->{action.run();return null;});}
    private static FrameLayout.LayoutParams position(int width,int height,int left,int top){
        FrameLayout.LayoutParams result=new FrameLayout.LayoutParams(width,height);result.leftMargin=left;result.topMargin=top;return result;
    }
    private static final class CountingContent extends FrameLayout{
        int layouts;
        CountingContent(Context context){super(context);setMinimumHeight(1440);}
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){super.onLayout(changed,left,top,right,bottom);layouts++;}
    }
    private static final class Fixture{
        final Dialog dialog;final FrameLayout root;final CountingContent content;final TvScrollView scroll;final Button top,bottom,last;
        Fixture(Context context){
            dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root=new FrameLayout(context);root.setBackgroundColor(Color.DKGRAY);
            scroll=new TvScrollView(context);scroll.setPageHeight(720);scroll.setPadding(0,0,0,0);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);root.addView(scroll,position(1200,720,0,0));
            content=new CountingContent(context);scroll.addView(content,new ScrollView.LayoutParams(1200,1440));
            top=button(context,"First page",80,540);bottom=button(context,"Second page",80,780);last=button(context,"Lower second page",430,1310);
            top.setNextFocusDownId(bottom.getId());bottom.setNextFocusUpId(top.getId());bottom.setNextFocusDownId(last.getId());last.setNextFocusUpId(bottom.getId());
            dialog.setContentView(root);Window window=dialog.getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show();window.setLayout(1200,720);
        }
        private Button button(Context context,String label,int left,int y){
            Button result=new Button(context);result.setId(View.generateViewId());result.setText(label);content.addView(result,position(260,100,left,y));return result;
        }
        void key(int keyCode){
            assertTrue("Native D-pad down must be consumed",dialog.getWindow().getDecorView().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,keyCode)));
            dialog.getWindow().getDecorView().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,keyCode));
        }
        void close(){dialog.dismiss();}
    }
}
