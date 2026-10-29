package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real external-task return; the framework owns pause, HOME delivery and resume. */
@RunWith(AndroidJUnit4.class)
public final class HomeIntentFocusLifecycleTest {
    private static final String FIXTURE_PACKAGE="io.github.ekkzy.evebox.fixture.alpha";
    private static final ComponentName FIXTURE=new ComponentName(FIXTURE_PACKAGE,"io.github.ekkzy.evebox.fixture.FixtureActivity");
    private Context context;
    private SharedPreferences storage;
    private Map<String,Object> original;
    private LauncherPrefs preferences;
    private UiDevice device;

    @Before public void prepareOwnedPrimaryHome() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug",context.getPackageName());
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("The agreed main composition uses standard font scale",1f,context.getResources().getConfiguration().fontScale,.001f);
        List<AppEntry> apps=AppCatalog.load(context);
        assertTrue("Two actually installed launchable apps required",apps.size()>=2);
        assertTrue("Install the separate exported alpha fixture APK before this test",
                context.getPackageManager().getActivityInfo(FIXTURE,0).exported);
        storage=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        original=copy(storage);
        assertTrue(storage.edit().clear().putInt("schema_version",LauncherPrefs.SCHEMA_VERSION).commit());
        preferences=new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);preferences.setBackground(18);preferences.setPaletteMode("dark");
        preferences.setLanguage("en");preferences.setWeatherEnabled(false);preferences.setWatchNextVisible(false);
        preferences.setSounds(false);preferences.setReducedMotion(true);
        preferences.setFavorites(Arrays.asList(apps.get(0).id,apps.get(1).id));
    }

    @After public void restoreRawPreferences() {
        if(original==null)return;
        SharedPreferences.Editor editor=storage.edit().clear();
        for(Map.Entry<String,Object> entry:original.entrySet()){
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);
            else if(value instanceof Set){
                @SuppressWarnings("unchecked") Set<String> strings=(Set<String>)value;
                editor.putStringSet(key,new HashSet<>(strings));
            }else throw new AssertionError("Unsupported preference type: "+key);
        }
        assertTrue(editor.commit());assertEquals(original,copy(storage));
    }

    @Test public void frameworkHomeReturnRestoresChosenFavoriteInSameActivity() {
        exerciseHomeReturn(false);
    }

    @Test public void frameworkHomeReturnKeepsFavoriteAcrossPreferenceRender() {
        exerciseHomeReturn(true);
    }
    @Test public void frameworkHomeReturnFallsBackWhenThePreviouslyChosenFavoriteWasRemoved() {
        exerciseHomeReturn(false, true);
    }

    private void exerciseHomeReturn(boolean changePalette) {
        exerciseHomeReturn(changePalette, false);
    }
    private void exerciseHomeReturn(boolean changePalette, boolean removeChosenFavorite) {
        HomeTrace trace=new HomeTrace();
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(trace);
        // Initial and return intents have the same explicit HOME filter. Changing
        // it behind ActivityScenario's back can detach its lifecycle observer.
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(homeIntent())){
            AtomicReference<MainActivity> activity=new AtomicReference<>();
            scene.onActivity(current->{activity.set(current);trace.owner=current;trace.initialTask=current.getTaskId();});
            try{
                await(activity.get(),"Initial HOME task settles",HomeIntentFocusLifecycleTest::ready,10000);
                scene.onActivity(current->{
                    assertTrue(tag(current,"favorite-1").requestFocus());
                    assertSame("Start from the second favorite, not the default first one",tag(current,"favorite-1"),current.getCurrentFocus());
                    // A lifecycle fixture may retain the lower viewport independently of
                    // its remembered upper focus. Partial offsets are no longer valid Home pages.
                    ScrollView scroll=(ScrollView)get(current,"homeScroll");scroll.scrollTo(0,scroll.getHeight());
                    assertEquals("Prepare a complete lower-page viewport",scroll.getHeight(),scroll.getScrollY());
                    trace.record("before-external",current);
                    current.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(FIXTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                });
                await(activity.get(),"Launching the real fixture pauses Main",current->
                        !(Boolean)get(current,"resumed"),8000);
                awaitFixtureForeground();
                if(changePalette)preferences.setPaletteMode("light");
                if(removeChosenFavorite)preferences.setFavorites(Collections.singletonList(preferences.read().favoriteIds.get(0)));
                String expectedFavorite=removeChosenFavorite?"favorite-0":"favorite-1";
                String marker="home-return-"+SystemClock.uptimeMillis();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    trace.record("before-HOME-delivery",activity.get());
                    trace.returnRequested=true;
                    context.startActivity(homeIntent().putExtra("eveBoxTestHomeReturn",marker));
                });
                // No focus/arrow input after the HOME intent. Observe the framework
                // resumed instance, then assert the actual native focus endpoint.
                await(activity.get(),"Framework HOME return restores the available favorite in the same Main instance",current->{
                    if(trace.returned==null)return false;
                    assertSame("A new Activity is a fresh launch, not restoration of this instance",current,trace.returned);
                    assertEquals("Return must reuse the established HOME task",trace.initialTask,current.getTaskId());
                    return ready(current) && tag(current,expectedFavorite).isFocused();
                },10000);
                scene.onActivity(current->{
                    assertSame("ActivityScenario still owns the same returned Main",activity.get(),current);
                    assertEquals("Framework delivered onNewIntent",marker,current.getIntent().getStringExtra("eveBoxTestHomeReturn"));
                    assertSame(tag(current,expectedFavorite),current.getCurrentFocus());
                    if(removeChosenFavorite){
                        assertNull("The removed control cannot retain native focus",((View)get(current,"root")).findViewWithTag("favorite-1"));
                        assertEquals(1,preferences.read().favoriteIds.size());
                    }
                    assertEquals("The explicit HOME request returns to the upper page",0,((ScrollView)get(current,"homeScroll")).getScrollY());
                    assertEquals("One native app view owns focus",1,countFocused(current.getWindow().getDecorView()));
                    assertEquals("No obsolete root retained after HOME",1,((FrameLayout)get(current,"renderHost")).getChildCount());
                    trace.record("restored",current);
                });
            }finally{
                emitTrace(trace,activity.get());
                // Close only after bringing our already-owned HOME back from the
                // fixture if an earlier assertion failed. Never change default HOME.
                if(FIXTURE_PACKAGE.equals(device.getCurrentPackageName())){
                    context.startActivity(homeIntent());
                    await(activity.get(),"Cleanup returns the owned Main before scenario close",current->
                            (Boolean)get(current,"resumed"),6000);
                }
            }
        }finally{ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(trace);}
    }

    private Intent homeIntent(){
        return new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void awaitFixtureForeground(){
        long deadline=SystemClock.uptimeMillis()+8000;
        do{
            if(FIXTURE_PACKAGE.equals(device.getCurrentPackageName()))return;
            SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<deadline);
        fail("The separate fixture must really be foreground; package="+device.getCurrentPackageName());
    }

    private static final class HomeTrace implements ActivityLifecycleCallback {
        MainActivity owner,returned;int initialTask;boolean returnRequested;
        final List<String> events=new ArrayList<>();
        @Override public void onActivityLifecycleChanged(Activity activity,Stage stage){
            if(!(activity instanceof MainActivity))return;
            MainActivity main=(MainActivity)activity;
            if(returnRequested&&stage==Stage.RESUMED)returned=main;
            record(stage.name(),main);
        }
        void record(String event,MainActivity activity){
            if(events.size()==32)events.remove(0);
            events.add(event+" "+describe(activity));
        }
    }

    private static void emitTrace(HomeTrace trace,MainActivity activity){
        AtomicReference<String> text=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            trace.record("final-observation",activity);text.set(String.join("\n",trace.events));
        });
        Bundle evidence=new Bundle();evidence.putString("stream","\nHOME_RETURN_TRACE\n"+text.get()+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);
    }

    private static String describe(MainActivity activity){
        View root=(View)get(activity,"root"),focused=activity.getCurrentFocus();
        Intent intent=activity.getIntent();
        return "instance="+Integer.toHexString(System.identityHashCode(activity))+",task="+activity.getTaskId()
                +",resumed="+get(activity,"resumed")+",screen="+get(activity,"screen")
                +",rootAttached="+(root!=null&&root.isAttachedToWindow())+",rootLaidOut="+(root!=null&&root.isLaidOut())
                +",windowFocus="+activity.hasWindowFocus()+",rootVisibility="+(root==null?-1:root.getWindowVisibility())
                +",focus="+(focused==null?"null":focused.getTag())+",lastHome="+get(activity,"lastHomeFocus")
                +",pending="+get(activity,"pendingRestoredFocus")+",requested="+get(activity,"requestedFocus")
                +",intentAction="+(intent==null?"null":intent.getAction());
    }

    @Test public void primaryInformationalHeaderKeepsTheUpperBoundaryOnActualFavorites() {
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> activity=new AtomicReference<>();scene.onActivity(activity::set);
            await(activity.get(),"The primary composition settles",current->ready(current)&&tag(current,"favorite-0").isFocused(),10000);
            scene.onActivity(current->{
                assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE,preferences.read().cardFormat);
                assertFalse("The compact clock is informational",tag(current,"clock").isFocusable());
                View root=(View)get(current,"root");
                assertNull("No retired applications header",root.findViewWithTag("all-apps"));
                assertNull("No retired settings header",root.findViewWithTag("settings-quick"));
            });
            for(int index=0;index<2;index++){
                if(index>0){
                    assertTrue(device.pressDPadRight());
                    await(activity.get(),"The real Right reaches the second favorite",current->
                            ready(current)&&tag(current,"favorite-1").isFocused(),4000);
                }
                String favorite="favorite-"+index;
                assertTrue(device.pressDPadUp());
                await(activity.get(),"Up stays at the informational upper boundary for "+favorite,current->
                        ready(current)&&tag(current,favorite).isFocused(),4000);
                scene.onActivity(current->{
                    assertSame(tag(current,favorite),current.getCurrentFocus());
                    assertEquals(0,((ScrollView)get(current,"homeScroll")).getScrollY());
                    assertEquals(1,countFocused(current.getWindow().getDecorView()));
                    assertTrue("The actual native focus is visible",current.getCurrentFocus().getGlobalVisibleRect(new android.graphics.Rect()));
                });
            }
        }
    }

    @Test public void primaryFavoritesRoundTripThroughTheRememberedLowerShortcutByRealDpad() {
        assertEquals("The agreed owned emulator baseline",34,android.os.Build.VERSION.SDK_INT);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> activity=new AtomicReference<>();scene.onActivity(activity::set);
            await(activity.get(),"The primary composition settles before real setup input",current->
                    ready(current)&&tag(current,"favorite-0").isFocused(),10000);
            scene.onActivity(current->{
                View decor=current.getWindow().getDecorView();
                assertEquals("Actual UI window width",3840,decor.getWidth());
                assertEquals("Actual UI window height",2160,decor.getHeight());
                assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE,preferences.read().cardFormat);
                assertFalse(preferences.read().watchNextVisible);
                assertTrue("The empty watch row cannot provide an intermediate Up target",((List<?>)get(current,"watchTiles")).isEmpty());
            });
            assertTrue(device.pressDPadRight());
            await(activity.get(),"Right supplies a nondefault upper origin",current->
                    ready(current)&&tag(current,"favorite-1").isFocused(),4000);
            assertTrue(device.pressDPadDown());
            await(activity.get(),"Down enters the remembered lower origin",current->
                    ready(current)&&tag(current,"shortcut-launcher").isFocused(),4000);
            assertPageAndNativeFocus(scene,"shortcut-launcher",true);
            for(String destination:Arrays.asList("shortcut-wallpapers","shortcut-bluetooth","shortcut-wifi","shortcut-apps")){
                assertTrue(device.pressDPadLeft());
                await(activity.get(),"The real Left reaches "+destination,current->
                        ready(current)&&tag(current,destination).isFocused(),4000);
            }
            assertPageAndNativeFocus(scene,"shortcut-apps",true);
            assertTrue(device.pressDPadUp());
            await(activity.get(),"Up restores the actual second favorite",current->
                    ready(current)&&tag(current,"favorite-1").isFocused(),4000);
            assertPageAndNativeFocus(scene,"favorite-1",false);
            assertTrue(device.pressDPadLeft());
            await(activity.get(),"Left supplies the first favorite as a new upper origin",current->
                    ready(current)&&tag(current,"favorite-0").isFocused(),4000);
            assertTrue(device.pressDPadDown());
            await(activity.get(),"Down restores the actual lower shortcut",current->
                    ready(current)&&tag(current,"shortcut-apps").isFocused(),4000);
            assertPageAndNativeFocus(scene,"shortcut-apps",true);
            assertTrue(device.pressDPadUp());
            await(activity.get(),"The final Up restores the changed upper origin",current->
                    ready(current)&&tag(current,"favorite-0").isFocused(),4000);
            assertPageAndNativeFocus(scene,"favorite-0",false);
        }
    }

    private static void assertPageAndNativeFocus(ActivityScenario<MainActivity> scene,String focusedTag,boolean lower){
        scene.onActivity(current->{
            assertEquals("The entire round trip stays on Home","HOME",current.currentScreenForTest());
            assertSame(tag(current,focusedTag),current.getCurrentFocus());
            TvScrollView scroll=(TvScrollView)get(current,"homeScroll");
            assertEquals("A complete Home page owns the viewport",lower?scroll.getHeight():0,scroll.getScrollY());
            assertEquals("One native View owns focus",1,countFocused(current.getWindow().getDecorView()));
            assertTrue("The actual native focus is visible",current.getCurrentFocus().getGlobalVisibleRect(new android.graphics.Rect()));
        });
    }

    private static boolean ready(MainActivity activity){
        Future<?> load=(Future<?>)get(activity,"loadFuture");
        TvScrollView scroll=(TvScrollView)get(activity,"homeScroll");
        return "HOME".equals(get(activity,"screen").toString()) && (Boolean)get(activity,"catalogLoaded")
                && load!=null&&load.isDone() && !(Boolean)get(activity,"renderDirty")
                && (Boolean)get(activity,"resumed") && activity.hasWindowFocus()
                && scroll!=null&&!scroll.isScrollAnimating()
                && !((UiMotion)get(activity,"uiMotion")).isRunning() && ((View)get(activity,"root")).isLaidOut();
    }
    private static void await(MainActivity activity,String message,Predicate<MainActivity> predicate,long timeout){
        long deadline=SystemClock.uptimeMillis()+timeout;AtomicBoolean complete=new AtomicBoolean();
        do{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->complete.set(predicate.test(activity)));
            if(complete.get())return;SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<deadline);
        AtomicReference<String> diagnostics=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->diagnostics.set(describe(activity)));
        fail(message+"; "+diagnostics.get());
    }
    private static View tag(MainActivity activity,String key){
        View view=((View)get(activity,"root")).findViewWithTag(key);assertNotNull("Current Home target "+key,view);return view;
    }
    private static Object get(MainActivity activity,String name){
        try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
        catch(ReflectiveOperationException failure){throw new AssertionError(name,failure);}
    }
    private static int countFocused(View view){
        int count=view.isFocused()?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countFocused(((ViewGroup)view).getChildAt(i));
        return count;
    }
    private static Map<String,Object> copy(SharedPreferences preferences){
        Map<String,Object> copy=new HashMap<>();
        for(Map.Entry<String,?> entry:preferences.getAll().entrySet()){
            Object value=entry.getValue();copy.put(entry.getKey(),value instanceof Set?new HashSet<>((Set<?>)value):value);
        }
        return copy;
    }
}
