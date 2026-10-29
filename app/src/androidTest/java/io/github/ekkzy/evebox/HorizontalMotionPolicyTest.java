package io.github.ekkzy.evebox;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.ui.TvHorizontalScrollView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated native focus/OverScroller regression; never changes preferences or system animator scale. */
@RunWith(AndroidJUnit4.class)
public final class HorizontalMotionPolicyTest {
    @Before public void requireOwnedEmulator() throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("io.github.ekkzy.evebox.debug",InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    }

    @Test public void policyOffKeepsFocusOnControlOutsideRail() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture=fixture(activity);
            try {
                int offset=onMain(()->{
                    assertTrue(fixture.outside.requestFocus());fixture.rail.scrollTo(96,0);
                    int before=fixture.rail.getScrollX();assertTrue(before>0);
                    fixture.rail.setMotionAllowed(false);
                    assertSame("Stopping an unrelated rail must not steal header focus",fixture.outside,fixture.root.findFocus());
                    assertEquals(before,fixture.rail.getScrollX());return before;
                });
                SystemClock.sleep(350);
                main(()->{assertSame(fixture.outside,fixture.root.findFocus());assertEquals(offset,fixture.rail.getScrollX());});
            } finally { main(fixture::close); }
        }
    }

    @Test public void policyOffThenImmediateArrowCannotRestoreOldOffset() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture=fixture(activity);CountDownLatch checked=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean cancelled=new AtomicBoolean();
            try {
                main(()->{
                    fixture.rail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    assertTrue(fixture.rail.requestFocus());fixture.rail.scrollTo(64,0);
                    fixture.rail.setMotionAllowed(false);
                    assertTrue(fixture.rail.arrowScroll(View.FOCUS_RIGHT));
                    int destination=fixture.rail.getScrollX();assertTrue("Immediate arrow advances",destination>64);
                    fixture.rail.postDelayed(()->{
                        try { if(!cancelled.get())assertEquals("Queued native compute must not roll back newer input",destination,fixture.rail.getScrollX()); }
                        catch(Throwable error){failure.set(error);}finally{checked.countDown();}
                    },350);
                });
                assertTrue("Immediate-input regression completed",checked.await(3,TimeUnit.SECONDS));
                if(failure.get()!=null)throw new AssertionError("Horizontal immediate input",failure.get());
            } finally { cancelled.set(true);main(fixture::close); }
        }
    }

    @Test public void policyOffStopsGenuinelyMovingNativeRailBeforeItsTarget() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture=fixture(activity);CountDownLatch checked=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean cancelled=new AtomicBoolean();
            try {
                main(()->{
                    fixture.rail.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    assertTrue(fixture.rail.requestFocus());assertEquals(0,fixture.rail.getScrollX());
                    int target=Math.min(fixture.rail.getMaxScrollAmount(),fixture.content.getWidth()-fixture.rail.getWidth()+fixture.rail.getPaddingLeft()+fixture.rail.getPaddingRight());
                    assertTrue(target>0);assertTrue(fixture.rail.arrowScroll(View.FOCUS_RIGHT));
                    assertEquals("Enabled native arrow starts without jumping",0,fixture.rail.getScrollX());
                    long deadline=SystemClock.uptimeMillis()+3000;
                    Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback(){
                        @Override public void doFrame(long frameTime){
                            if(cancelled.get())return;
                            try {
                                assertTrue("First moving frame deadline",SystemClock.uptimeMillis()<deadline);
                                int offset=fixture.rail.getScrollX();
                                if(offset==0){Choreographer.getInstance().postFrameCallback(this);return;}
                                assertTrue("Stop while native motion is in flight",offset>0&&offset<target);
                                fixture.rail.setMotionAllowed(false);assertEquals(offset,fixture.rail.getScrollX());
                                fixture.rail.postDelayed(()->{
                                    try {if(!cancelled.get()){assertFalse(fixture.rail.isSmoothScrollingEnabled());assertEquals("No drift after in-flight stop",offset,fixture.rail.getScrollX());}}
                                    catch(Throwable error){failure.set(error);}finally{checked.countDown();}
                                },350);
                            } catch(Throwable error){failure.set(error);checked.countDown();}
                        }
                    });
                });
                assertTrue("Moving-frame stop and stable follow-up completed",checked.await(5,TimeUnit.SECONDS));
                if(failure.get()!=null)throw new AssertionError("Horizontal in-flight stop",failure.get());
            } finally { cancelled.set(true);main(fixture::close); }
        }
    }

    private static Fixture fixture(ActivityScenario<MainActivity> activity){
        AtomicReference<Fixture> created=new AtomicReference<>();activity.onActivity(a->created.set(new Fixture(a)));
        Fixture fixture=created.get();long deadline=SystemClock.uptimeMillis()+4000;
        while(SystemClock.uptimeMillis()<deadline){if(onMain(()->fixture.rail.getWidth()>0&&fixture.content.getWidth()>fixture.rail.getWidth()))return fixture;SystemClock.sleep(30);}
        main(fixture::close);throw new AssertionError("Native horizontal fixture was not laid out");
    }
    private interface Query<T>{T get();}
    private static <T>T onMain(Query<T> query){
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{value.set(query.get());}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Main-thread assertion",failure.get());return value.get();
    }
    private static void main(Runnable action){onMain(()->{action.run();return null;});}
    private static FrameLayout.LayoutParams position(int width,int height,int left,int top){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,height);p.leftMargin=left;p.topMargin=top;return p;}
    private static final class Fixture {
        final Dialog dialog;final FrameLayout root,content;final Button outside;final TvHorizontalScrollView rail;
        Fixture(Context context){
            dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root=new FrameLayout(context);root.setBackgroundColor(Color.DKGRAY);
            outside=new Button(context);outside.setText("Outside rail");root.addView(outside,position(220,56,20,14));
            rail=new TvHorizontalScrollView(context);rail.setPadding(20,20,20,20);rail.setOverScrollMode(View.OVER_SCROLL_NEVER);root.addView(rail,position(600,180,20,94));
            content=new FrameLayout(context);rail.addView(content,new android.widget.HorizontalScrollView.LayoutParams(2400,140));
            for(int i=0;i<6;i++){Button card=new Button(context);card.setText("Native card "+i);content.addView(card,position(300,130,i*360,0));}
            dialog.setContentView(root);Window window=dialog.getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show();window.setLayout(640,360);
        }
        void close(){dialog.dismiss();}
    }
}
