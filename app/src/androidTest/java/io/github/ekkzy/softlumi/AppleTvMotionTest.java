package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WallpaperCatalog;
import io.github.ekkzy.softlumi.ui.UiMotion;
import io.github.ekkzy.softlumi.ui.UiSounds;
import io.github.ekkzy.softlumi.ui.UiTypography;
import io.github.ekkzy.softlumi.ui.GlassBackdrop;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AppleTvMotionTest {
    private static final float APP_FOCUS_SCALE = 1.20f;
    private UiDevice device;private Context context;private LauncherPrefs prefs;
    private SharedPreferences storage;private Map<String,?> savedPreferences;
    @Before public void prepare() throws Exception {
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug",context.getPackageName());
        assertEquals("This suite uses the agreed font1 layout",1f,context.getResources().getConfiguration().fontScale,.001f);
        storage=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        savedPreferences=new HashMap<>(storage.getAll());
        assertTrue(storage.edit().clear().commit());
        prefs=new LauncherPrefs(context);prefs.setWeatherEnabled(false);prefs.setSounds(true);prefs.setReducedMotion(false);
        List<String> ids=new ArrayList<>();for(AppEntry app:AppCatalog.load(context))if(ids.size()<8)ids.add(app.id);
        assertTrue(ids.size()>=2);prefs.setFavorites(ids);
    }

    @After public void restoreDebugPreferences(){
        if(savedPreferences==null)return;
        SharedPreferences.Editor restore=storage.edit().clear();
        for(Map.Entry<String,?> item:savedPreferences.entrySet()){
            String key=item.getKey();Object value=item.getValue();
            if(value instanceof String)restore.putString(key,(String)value);
            else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)restore.putInt(key,(Integer)value);
            else if(value instanceof Long)restore.putLong(key,(Long)value);
            else if(value instanceof Float)restore.putFloat(key,(Float)value);
            else if(value instanceof Set<?>){Set<String> copy=new HashSet<>();for(Object member:(Set<?>)value)copy.add((String)member);restore.putStringSet(key,copy);}
            else fail("Unsupported saved preference type: "+key);
        }
        assertTrue(restore.commit());assertEquals(savedPreferences,storage.getAll());
    }

    @Test public void focusRetargetsWithoutJumpAndLoadsOwnedSoundCues() throws Exception {
        String previous=device.executeShellCommand("settings get global animator_duration_scale").trim();
        device.executeShellCommand("settings put global animator_duration_scale 1");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            waitMillis(1200);focus(scene,"favorite-0");awaitSettled(scene,"initial favorite focus");
            scene.onActivity(a->{assertEquals(APP_FOCUS_SCALE,tile(a,0).getScaleX(),.002f);assertFalse(owned(a,UiMotion.class).isRunning());});
            java.util.concurrent.CountDownLatch reversed=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> reversalFailure=new java.util.concurrent.atomic.AtomicReference<>();
            scene.onActivity(a->{
                assertTrue(tile(a,1).requestFocus());
                // Probe inside the first rendered frame. waitForIdleSync would wait out
                // the entire spring on a renderer that keeps its queue busy.
                android.view.Choreographer.getInstance().postFrameCallback(time->{try{
                    View second=tile(a,1);float before=second.getScaleX();
                    assertTrue("Focus must visibly interpolate",before>1.0001f&&before<APP_FOCUS_SCALE);
                    assertTrue("Focus is still running at reversal",owned(a,UiMotion.class).isRunning());
                    assertTrue("Focus must own an in-flight spring channel",owned(a,UiMotion.class).runningChannelCount()>0);
                    assertTrue(tile(a,0).requestFocus());
                    assertEquals("Reversal must retain its current scale",before,second.getScaleX(),.0001f);
                }catch(Throwable failure){reversalFailure.set(failure);}finally{reversed.countDown();}});
            });
            assertTrue("First-frame reversal completed",reversed.await(5,java.util.concurrent.TimeUnit.SECONDS));
            if(reversalFailure.get()!=null)throw new AssertionError("Focus reversal",reversalFailure.get());
            awaitSettled(scene,"reversed favorite focus");
            scene.onActivity(a->{assertEquals(APP_FOCUS_SCALE,tile(a,0).getScaleX(),.002f);assertEquals(1f,tile(a,1).getScaleX(),.002f);assertFalse(owned(a,UiMotion.class).isRunning());});
            int[] surfaces={0};scene.onActivity(a->surfaces[0]=owned(a,GlassBackdrop.class).getLiveSurfaceCount());
            for(int i=0;i<20;i++){focus(scene,"favorite-"+(i%2));waitMillis(35);}
            focus(scene,"favorite-0");awaitSettled(scene,"repeated focus keeps bounded glass");
            scene.onActivity(a->assertTrue("Focus must reuse its glass rather than accumulate observers",owned(a,GlassBackdrop.class).getLiveSurfaceCount()<=surfaces[0]));
            InstrumentationRegistry.getInstrumentation().sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
            waitMillis(80);
            scene.onActivity(a->assertTrue("Pressed focus visibly compresses",tile(a,0).getScaleX()<APP_FOCUS_SCALE));
            // Cancel the key to avoid launching a fixture while inspecting feedback.
            long now=SystemClock.uptimeMillis();
            InstrumentationRegistry.getInstrumentation().sendKeySync(new KeyEvent(now,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0,0,-1,0,KeyEvent.FLAG_CANCELED));
            awaitSettled(scene,"cancelled press release");
            scene.onActivity(a->{assertFalse(owned(a,UiMotion.class).isRunning());assertSame(UiTypography.get(a).displayRegular,findClockTypeface(a));});
            long deadline=SystemClock.uptimeMillis()+4000;AtomicBoolean ready=new AtomicBoolean();
            while(!ready.get()&&SystemClock.uptimeMillis()<deadline){scene.onActivity(a->ready.set(owned(a,UiSounds.class).isReady()));waitMillis(100);}
            assertTrue("Three independently synthesized cues load into native SoundPool",ready.get());
            scene.onActivity(a->{UiSounds sounds=owned(a,UiSounds.class);long requested=sounds.getPlayRequestCount(),started=sounds.getStartedPlayCount();sounds.move();sounds.select();sounds.back();assertEquals(requested+3,sounds.getPlayRequestCount());assertEquals("Native pool accepts each loaded cue",started+3,sounds.getStartedPlayCount());sounds.configure(false);assertFalse(sounds.isReady());});
            scene.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scene.onActivity(a->{assertFalse(owned(a,UiMotion.class).isRunning());assertFalse(owned(a,UiSounds.class).isReady());});
        }finally{restoreAnimator(previous);}
    }

    @Test public void reducedMotionAndSystemZeroStopTransformFrames() throws Exception {
        String previous=device.executeShellCommand("settings get global animator_duration_scale").trim();
        device.executeShellCommand("settings put global animator_duration_scale 1");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            waitMillis(1000);focus(scene,"favorite-0");awaitSettled(scene,"focus before system scale change");
            device.executeShellCommand("settings put global animator_duration_scale 0");waitMillis(300);
            focus(scene,"favorite-1");waitMillis(120);
            scene.onActivity(a->{assertFalse(owned(a,UiMotion.class).isRunning());assertEquals(0,owned(a,UiMotion.class).runningChannelCount());assertEquals(1f,tile(a,1).getScaleX(),.0001f);});
            device.executeShellCommand("settings put global animator_duration_scale 1");
            prefs.setReducedMotion(true);scene.recreate();waitMillis(1000);focus(scene,"favorite-0");
            scene.onActivity(a->{assertFalse(owned(a,UiMotion.class).isRunning());assertEquals(0,owned(a,UiMotion.class).runningChannelCount());assertEquals(1f,tile(a,0).getScaleX(),.0001f);});
        }finally{restoreAnimator(previous);}
    }
    @Test public void navigationStaysResponsiveDuringRapidBackAndReentry() throws Exception {
        // Start from another pair so choosing the old mountain image's pair
        // below still verifies a real persisted change, rather than a no-op.
        prefs.setBackground(WallpaperCatalog.GRAPHITE);
        String previous=device.executeShellCommand("settings get global animator_duration_scale").trim();
        device.executeShellCommand("settings put global animator_duration_scale 1");
        phase("launch");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            List<String> favorites=new ArrayList<>(prefs.read().favoriteIds);
            waitMillis(1200);focus(scene,"favorite-0");awaitSettled(scene,"recording initial focus");
            phase("home settled");
            for(int i=0;i<5;i++){phase("right "+i);device.pressDPadRight();waitMillis(130);}
            for(int i=0;i<5;i++){phase("left "+i);device.pressDPadLeft();waitMillis(130);}
            phase("embedded lower panel");
            device.pressDPadDown();waitMillis(650);
            awaitHomeSettled(scene,"lower panel");
            scene.onActivity(a->{assertEquals("HOME",a.currentScreenForTest());assertEquals(homeScroll(a).getHeight(),homeScroll(a).getScrollY());assertEquals("shortcut-launcher",a.getCurrentFocus().getTag());});
            phase("settings reverse from lower Home");
            rapidBackDuringEntry(scene,"shortcut-launcher","SETTINGS","HOME",true);
            int[] homeOffset={0};
            scene.onActivity(a->homeOffset[0]=homeScroll(a).getScrollY());
            phase("settings");focus(scene,"shortcut-launcher");device.pressDPadCenter();waitMillis(700);
            scene.onActivity(a->assertEquals("SETTINGS",a.currentScreenForTest()));
            for(int i=0;i<3;i++){
                phase("appearance reverse "+i);
                rapidBackDuringEntry(scene,"settings-appearance","APPEARANCE","SETTINGS",false);
            }
            focus(scene,"settings-appearance");device.pressDPadCenter();waitMillis(700);
            scene.onActivity(a->assertEquals("APPEARANCE",a.currentScreenForTest()));
            phase("gallery");focus(scene,"appearance-background");device.pressDPadCenter();waitMillis(700);
            int chosenPair=WallpaperCatalog.pairId(WallpaperCatalog.MOUNTAIN_PEAKS);
            focus(scene,"background-"+chosenPair);waitMillis(500);device.pressDPadCenter();waitMillis(700);
            for(int i=0;i<3;i++){device.pressBack();waitMillis(400);}
            awaitSettled(scene,"rapid back and gallery return");
            awaitHomeSettled(scene,"gallery returns to lower Home");
            scene.onActivity(a->{assertEquals("HOME",a.currentScreenForTest());assertNotNull(a.getCurrentFocus());assertEquals("shortcut-launcher",a.getCurrentFocus().getTag());assertEquals("Nested pages preserve Home scroll",homeOffset[0],homeScroll(a).getScrollY());});
            assertEquals("Gallery pair choice persists",chosenPair,prefs.read().background);
            assertEquals("Navigation preserves favorite identity/order",favorites,prefs.read().favoriteIds);
            device.pressBack();awaitHomeSettled(scene,"Back restores upper Home");
            scene.onActivity(a->{assertEquals("HOME",a.currentScreenForTest());assertEquals(0,homeScroll(a).getScrollY());assertNotNull(a.getCurrentFocus());assertTrue(a.getCurrentFocus().isShown());});
            phase("complete");
        }finally{restoreAnimator(previous);}
    }
    private void rapidBackDuringEntry(ActivityScenario<MainActivity> scene,String control,String opened,String returned,boolean fromHome)throws Exception{
        focus(scene,control);
        if(fromHome)awaitHomeSettled(scene,control+" before reversal");else awaitSettled(scene,control+" before reversal");
        java.util.concurrent.CountDownLatch reversed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ViewTreeObserver> observer=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ViewTreeObserver.OnPreDrawListener> listener=new java.util.concurrent.atomic.AtomicReference<>();
        List<String> frames=new ArrayList<>();
        try{
            scene.onActivity(a->{
                View retained=(View)field(a,"root");TvScrollView viewport=fromHome?homeScroll(a):null;int offset=viewport==null?0:viewport.getScrollY();
                observer.set(a.getWindow().getDecorView().getViewTreeObserver());
                listener.set(new ViewTreeObserver.OnPreDrawListener(){
                    boolean dispatched;
                    @Override public boolean onPreDraw(){
                        if(dispatched)return true;
                        try{
                            View incoming=(View)field(a,"root");UiMotion motion=owned(a,UiMotion.class);
                            if(frames.size()<64)frames.add(a.currentScreenForTest()+",root="+Integer.toHexString(System.identityHashCode(incoming))
                                    +",visibility="+incoming.getVisibility()+",shown="+incoming.isShown()+",alpha="+incoming.getAlpha()
                                    +",channels="+motion.runningChannelCount());
                            if(incoming==retained)return true;
                            assertEquals(opened,a.currentScreenForTest());
                            // A queued frame may precede afterLayout's fade setup.
                            // Only actual visible interpolation proves entry is running.
                            float enteringAlpha=incoming.getAlpha();
                            if(incoming.getVisibility()!=View.VISIBLE||!incoming.isShown()||enteringAlpha<=.05f||enteringAlpha>=.95f)return true;
                            dispatched=true;
                            assertTrue("Back is dispatched during an observed entry fade, not before setup or after idle",motion.runningChannelCount()>0);
                            float alpha=retained.getAlpha();
                            press(a,KeyEvent.KEYCODE_BACK);
                            assertEquals(returned,a.currentScreenForTest());
                            assertSame("Rapid Back retains the actual previous page View",retained,field(a,"root"));
                            assertEquals("Reversal keeps current opacity",alpha,retained.getAlpha(),.0001f);
                            if(fromHome){assertSame("Rapid Back retains the embedded viewport",viewport,homeScroll(a));assertEquals("Opening a page does not reset Home scroll",offset,viewport.getScrollY());}
                            reversed.countDown();
                        }catch(RuntimeException|AssertionError error){dispatched=true;failure.set(error);reversed.countDown();}
                        return true;
                    }
                });
                observer.get().addOnPreDrawListener(listener.get());
                press(a,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(opened,a.currentScreenForTest());
            });
            assertTrue("A real partial "+opened+" entry fade must be observed before Back through "+control,reversed.await(5,java.util.concurrent.TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("Rapid page reversal through "+control+"; frames="+frames,failure.get());
        }finally{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                if(observer.get()!=null&&observer.get().isAlive()&&listener.get()!=null)observer.get().removeOnPreDrawListener(listener.get());
                android.os.Bundle evidence=new android.os.Bundle();evidence.putString("stream","\nAPPLE_ENTRY_PRE_DRAW "+control+" "+frames+"\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);
            });
        }
        if(fromHome)awaitHomeSettled(scene,"returned "+returned);else awaitSettled(scene,"returned "+returned);
        scene.onActivity(a->{assertEquals(returned,a.currentScreenForTest());assertNotNull(a.getCurrentFocus());assertTrue(a.getCurrentFocus().isShown());assertEquals(control,a.getCurrentFocus().getTag());assertEquals("No abandoned outgoing page",1,((android.view.ViewGroup)field(a,"renderHost")).getChildCount());});
    }
    private static void phase(String name){android.os.Bundle evidence=new android.os.Bundle();evidence.putString("stream","\nAPPLE_MOTION "+name+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);}

    private Object findClockTypeface(MainActivity a){
        // The main composition uses the regular display role and tabular numerals.
        return clock(a.getWindow().getDecorView());
    }
    private Object clock(View v){if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText().toString().matches("\\d{1,2}:\\d{2}"))return ((android.widget.TextView)v).getTypeface();
        if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){Object found=clock(((android.view.ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    private static <T>T owned(MainActivity a,Class<T> type){try{for(Field field:MainActivity.class.getDeclaredFields())if(field.getType()==type){field.setAccessible(true);return type.cast(field.get(a));}}catch(ReflectiveOperationException e){throw new AssertionError(e);}throw new AssertionError(type.getSimpleName()+" not owned by Activity");}
    private static Object field(MainActivity a,String name){try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(a);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static TvScrollView homeScroll(MainActivity a){View view=a.getWindow().getDecorView().findViewWithTag("home-scroll");assertTrue("Embedded Home viewport exists",view instanceof TvScrollView);return (TvScrollView)view;}
    private static void press(MainActivity a,int key){a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}
    private void awaitHomeSettled(ActivityScenario<MainActivity> scene,String phase){
        long deadline=SystemClock.uptimeMillis()+5000;AtomicBoolean settled=new AtomicBoolean();
        do{scene.onActivity(a->settled.set(!owned(a,UiMotion.class).isRunning()&&!homeScroll(a).isScrollAnimating()));if(settled.get())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<deadline);
        fail(phase+" did not settle Home motion/scroll");
    }
    private View tile(MainActivity a,int index){View v=a.getWindow().getDecorView().findViewWithTag("favorite-"+index);assertNotNull(v);return v;}
    private void focus(ActivityScenario<MainActivity> scene,String tag){scene.onActivity(a->{View v=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,v);assertTrue(v.requestFocus());});}
    private void restoreAnimator(String value)throws Exception{device.executeShellCommand("null".equals(value)?"settings delete global animator_duration_scale":"settings put global animator_duration_scale "+value);}
    private void awaitSettled(ActivityScenario<MainActivity> scene,String phase){
        long deadline=SystemClock.uptimeMillis()+5000;
        AtomicBoolean settled=new AtomicBoolean();
        int[] lastChannels={-1};boolean[] lastRunning={true};
        do{
            scene.onActivity(a->{UiMotion motion=owned(a,UiMotion.class);lastChannels[0]=motion.runningChannelCount();lastRunning[0]=motion.isRunning();settled.set(!lastRunning[0]&&lastChannels[0]==0);});
            if(settled.get())return;
            SystemClock.sleep(40);
        }while(SystemClock.uptimeMillis()<deadline);
        fail(phase+" did not settle; running="+lastRunning[0]+",channels="+lastChannels[0]);
    }
    private void waitMillis(long ms){SystemClock.sleep(ms);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
}
