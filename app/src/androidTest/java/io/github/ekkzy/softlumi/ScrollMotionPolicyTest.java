package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run alone on an owned emulator; restores raw debug preferences and the system scale. */
@RunWith(AndroidJUnit4.class)
@android.annotation.TargetApi(33)
public final class ScrollMotionPolicyTest {
    @Test public void nativeScrollFollowsLiveSystemPolicyAndReducedMotionAcrossResume() throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator required before changing settings","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug",context.getPackageName());
        assertTrue("Effective policy restoration requires the owned API 33+ emulator",Build.VERSION.SDK_INT>=33);
        SharedPreferences store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        Map<String,Object> original=new HashMap<>(store.getAll());
        String oldScale=device.executeShellCommand("settings get global animator_duration_scale").trim();
        assertTrue("Only a numeric or absent animator scale may be restored",oldScale.equals("null")||oldScale.matches("-?\\d+(\\.\\d+)?"));
        float[] oldEffective={1};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->oldEffective[0]=ValueAnimator.getDurationScale());
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1");
            awaitEffectiveScale(1);
            LauncherPrefs prefs=new LauncherPrefs(context);
            prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(0);prefs.setLanguage("en");prefs.setWeatherEnabled(false);
            prefs.setWatchNextVisible(false);prefs.setSounds(false);prefs.setReducedMotion(false);prefs.setFavorites(Collections.emptyList());
            try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
                awaitScreen(scene,"HOME");device.pressDPadDown();awaitScreen(scene,"HOME");
                scene.onActivity(a->{assertNotNull(a.getCurrentFocus());assertEquals("The empty-state action enters the remembered lower-page origin","shortcut-launcher",a.getCurrentFocus().getTag());
                    TvScrollView home=(TvScrollView)a.getWindow().getDecorView().findViewWithTag("home-scroll");assertNotNull(home);assertEquals(home.getHeight(),home.getScrollY());});
                click(scene,"shortcut-wallpapers");awaitScreen(scene,"GALLERY");
                awaitPolicy(scene,true);
                assertNativeArrowSmooth(scene);
                assertDisableThenImmediateArrowSameTurn(scene);

                // Change policy while the same Activity and ScrollView remain attached.
                disableSystemScaleDuringNativeScroll(scene);
                awaitPolicy(scene,false);assertNativeArrowImmediate(scene);
                device.executeShellCommand("settings put global animator_duration_scale 1");
                awaitPolicy(scene,true);assertNativeArrowSmooth(scene);

                // Resume must re-read the preference, even though the page had already been bound.
                scene.moveToState(Lifecycle.State.CREATED);prefs.setReducedMotion(true);
                scene.moveToState(Lifecycle.State.RESUMED);awaitScreen(scene,"GALLERY");
                awaitPolicy(scene,false);assertNativeArrowImmediate(scene);

                scene.moveToState(Lifecycle.State.CREATED);prefs.setReducedMotion(false);
                device.executeShellCommand("settings put global animator_duration_scale 0");
                scene.moveToState(Lifecycle.State.RESUMED);awaitScreen(scene,"GALLERY");
                awaitPolicy(scene,false);assertNativeArrowImmediate(scene);
                device.executeShellCommand("settings put global animator_duration_scale 1");
                awaitPolicy(scene,true);assertNativeArrowSmooth(scene);
            }
        } finally {
            try{restorePreferences(store,original);assertEquals("Raw preferences restored",original,store.getAll());}
            finally{
                device.executeShellCommand("settings put global animator_duration_scale "+(oldScale.equals("null")?oldEffective[0]:oldScale));
                awaitEffectiveScale(oldEffective[0]);
                if(oldScale.equals("null"))device.executeShellCommand("settings delete global animator_duration_scale");
            }
        }
    }

    private static TvScrollView gallery(MainActivity activity) {
        View tile=activity.getWindow().getDecorView().findViewWithTag("background-0");
        assertNotNull("Gallery must be bound",tile);
        for(ViewParent parent=tile.getParent();parent instanceof View;parent=parent.getParent())if(parent instanceof TvScrollView)return (TvScrollView)parent;
        throw new AssertionError("Gallery does not use TvScrollView");
    }
    private static void prepareArrow(TvScrollView scroll) {
        // Isolate native OverScroller behavior from a descendant's separate focus reveal.
        // This changes only the test's current view, never production data or navigation.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.requestFocus();scroll.fling(0);scroll.computeScroll();scroll.scrollTo(0,0);
        assertTrue("The real gallery must have scrollable content",scroll.canScrollVertically(1));
    }
    private static void assertNativeArrowSmooth(ActivityScenario<MainActivity> scene) {
        // Native arrow-scroll destination planning is rendered through the interruptible
        // TvScrollView channel; observe a real frame rather than an arbitrary target value.
        SystemClock.sleep(300);
        scene.onActivity(a->{TvScrollView scroll=gallery(a);assertTrue(scroll.isSmoothScrollingEnabled());prepareArrow(scroll);
            assertTrue(scroll.arrowScroll(View.FOCUS_DOWN));
            assertEquals("Normal native arrow scrolling starts an animation instead of jumping",0,scroll.getScrollY());});
        long deadline=SystemClock.uptimeMillis()+3000;AtomicBoolean moved=new AtomicBoolean();
        do{scene.onActivity(a->moved.set(gallery(a).getScrollY()>0));if(moved.get())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<deadline);
        fail("Enabled native arrow-scroll channel never moved");
    }
    private static void disableSystemScaleDuringNativeScroll(ActivityScenario<MainActivity> scene) throws Exception {
        // Use the shell permission only for this causal frame probe; no permissions are granted to the app.
        android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        CountDownLatch changed=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        try{
            SystemClock.sleep(300);
            scene.onActivity(a->{TvScrollView scroll=gallery(a);prepareArrow(scroll);
                int target=Math.min(scroll.getMaxScrollAmount(),Math.max(0,scroll.getChildAt(0).getBottom()-scroll.getHeight()+scroll.getPaddingBottom()));
                assertTrue("Native target must be scrollable",target>0);assertTrue(scroll.arrowScroll(View.FOCUS_DOWN));
                long deadline=SystemClock.uptimeMillis()+3000;
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback(){
                    private boolean scaleChangeRequested;
                    @Override public void doFrame(long time){
                        try{
                            int offset=scroll.getScrollY();
                            assertTrue("Native scroll policy must switch before the frame deadline",SystemClock.uptimeMillis()<deadline);
                            if(!scaleChangeRequested){
                                if(offset==0){Choreographer.getInstance().postFrameCallback(this);return;}
                                assertTrue("Scale zero must be applied before the actual clamped native target",offset>0&&offset<target);
                                assertTrue(Settings.Global.putFloat(a.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0));
                                scaleChangeRequested=true;Choreographer.getInstance().postFrameCallback(this);return;
                            }
                            assertTrue("Native scroll must stop before its old target",offset>0&&offset<target);
                            if(scroll.isSmoothScrollingEnabled()){Choreographer.getInstance().postFrameCallback(this);return;}
                            int stoppedOffset=offset;
                            // Continue on the UI thread without idle-synchronized onActivity:
                            // a missing stop would otherwise finish naturally before sampling.
                            scroll.postDelayed(()->{
                                try{assertFalse(scroll.isSmoothScrollingEnabled());assertEquals("Policy-off native scroll remains at its in-flight offset",stoppedOffset,scroll.getScrollY());}
                                catch(Throwable error){failure.set(error);}finally{changed.countDown();}
                            },350);
                        }catch(Throwable error){failure.set(error);changed.countDown();}
                    }
                });
            });
            assertTrue("Moving frame, policy-off frame and stable follow-up reached",changed.await(5,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("In-flight system-scale switch",failure.get());
        }finally{automation.dropShellPermissionIdentity();}
    }
    private static void assertDisableThenImmediateArrowSameTurn(ActivityScenario<MainActivity> scene) throws Exception {
        CountDownLatch checked=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        scene.onActivity(a->{
            TvScrollView scroll=gallery(a);assertTrue(scroll.isSmoothScrollingEnabled());prepareArrow(scroll);
            int range=Math.max(0,scroll.getChildAt(0).getBottom()-scroll.getHeight()+scroll.getPaddingBottom());
            int start=Math.min(64,range/3);assertTrue("Regression needs a nonzero starting offset",start>0);scroll.scrollTo(0,start);
            // The setter and next D-pad action deliberately share a callback, before
            // any frame can consume the native zero-velocity fling on our behalf.
            scroll.setMotionAllowed(false);assertTrue(scroll.arrowScroll(View.FOCUS_DOWN));
            int destination=scroll.getScrollY();assertTrue("Immediate input must advance from the old offset",destination>start);
            scroll.postDelayed(()->{
                try{assertEquals("A queued native compute must not restore the pre-input offset",destination,scroll.getScrollY());}
                catch(Throwable error){failure.set(error);}finally{checked.countDown();}
            },350);
        });
        assertTrue("Same-turn native scroll regression completed",checked.await(3,TimeUnit.SECONDS));
        if(failure.get()!=null)throw new AssertionError("Disable followed by immediate input",failure.get());
        scene.onActivity(a->gallery(a).setMotionAllowed(true));
    }
    private static void assertNativeArrowImmediate(ActivityScenario<MainActivity> scene) {
        int[] offset={0};
        scene.onActivity(a->{TvScrollView scroll=gallery(a);assertFalse(scroll.isSmoothScrollingEnabled());prepareArrow(scroll);
            assertTrue(scroll.arrowScroll(View.FOCUS_DOWN));offset[0]=scroll.getScrollY();
            assertTrue("Reduced native arrow scrolling must finish in the input callback",offset[0]>0);});
        SystemClock.sleep(350);
        scene.onActivity(a->assertEquals("No scroll drift after reduced-motion input",offset[0],gallery(a).getScrollY()));
    }
    private static void click(ActivityScenario<MainActivity> scene,String tag) {
        scene.onActivity(a->{View view=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,view);assertTrue(view.performClick());});
    }
    private static void awaitScreen(ActivityScenario<MainActivity> scene,String screen) {
        long deadline=SystemClock.uptimeMillis()+6000;AtomicBoolean ready=new AtomicBoolean();
        do{scene.onActivity(a->ready.set(screen.equals(a.currentScreenForTest())));if(ready.get()){SystemClock.sleep(650);return;}SystemClock.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
        fail("Expected screen "+screen);
    }
    private static void awaitPolicy(ActivityScenario<MainActivity> scene,boolean allowed) {
        long deadline=SystemClock.uptimeMillis()+4000;AtomicBoolean ready=new AtomicBoolean();
        do{scene.onActivity(a->ready.set(gallery(a).isSmoothScrollingEnabled()==allowed));if(ready.get())return;SystemClock.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
        fail("Native smooth-scroll policy did not become "+allowed);
    }
    private static void awaitEffectiveScale(float expected){
        long deadline=SystemClock.uptimeMillis()+4000;AtomicBoolean ready=new AtomicBoolean();
        do{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ready.set(Math.abs(ValueAnimator.getDurationScale()-expected)<.0001f));if(ready.get())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<deadline);
        fail("Effective animator scale did not become "+expected);
    }
    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences store,Map<String,Object> original) {
        SharedPreferences.Editor editor=store.edit().clear();
        for(Map.Entry<String,Object> entry:original.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);
            else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));
            else throw new AssertionError("Unexpected raw preference type for "+key);
        }
        assertTrue("Raw preferences committed",editor.commit());
    }
}
