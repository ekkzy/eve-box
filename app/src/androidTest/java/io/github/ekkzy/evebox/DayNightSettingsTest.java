package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ComponentCallbacks2;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.DayNightPolicy;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.SettingsBackup;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import io.github.ekkzy.evebox.ui.TvHorizontalScrollView;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
import org.json.JSONObject;

/** Owned native 4K only. No clock/timezone mutation, weather network, service grant or physical TV action. */
@RunWith(AndroidJUnit4.class)
public final class DayNightSettingsTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences store;
    private LauncherPrefs preferences;
    private Map<String,Object> original;
    private List<String> favorites;

    @Before public void prepareOwnedEmulator()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34,Build.VERSION.SDK_INT);assertEquals(3840,device.getDisplayWidth());assertEquals(2160,device.getDisplayHeight());
        assertEquals(1f,context.getResources().getConfiguration().fontScale,.001f);
        store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);original=snapshot(store);
        assertTrue(store.edit().clear().commit());preferences=new LauncherPrefs(context);
        preferences.setLanguage("en");preferences.setWeatherEnabled(false);preferences.setWatchNextVisible(false);
        preferences.setRecommendationsEnabled(false);preferences.setReducedMotion(true);preferences.setSounds(false);
        preferences.setPaletteMode("dark");preferences.setWallpaperDim(15);
        favorites=new ArrayList<>();for(AppEntry app:AppCatalog.load(context))if(favorites.size()<2)favorites.add(app.id);
        assertFalse("Real installed emulator apps seed focus",favorites.isEmpty());preferences.setFavorites(favorites);
        configure(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
    }
    @After public void restoreOriginalPreferences(){if(original!=null)restore(store,original);}

    @Test public void schemaFiveMigrationConvertsBothThemeChoicesAndPreservesFavoriteOrder(){
        for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
            configure(theme);
            assertTrue(store.edit().putInt("schema_version",5).remove("card_format")
                    .putInt("theme",LauncherPrefs.CARD_FORMAT_POSTER.equals(theme)?5:0).remove("wallpaper_period")
                    .remove("day_start_minutes").remove("night_start_minutes").commit());
            try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
                homeReady(scene);
                LauncherPrefs.Settings migrated=new LauncherPrefs(context).read();
                assertEquals(LauncherPrefs.SCHEMA_VERSION,store.getInt("schema_version",-1));assertEquals(theme,migrated.cardFormat);
                assertFalse("The retired theme preference is removed",store.contains("theme"));
                assertEquals(8,migrated.background);assertEquals(favorites,migrated.favoriteIds);
                assertEquals("auto",migrated.wallpaperPeriod);assertEquals(420,migrated.dayStartMinutes);assertEquals(1140,migrated.nightStartMinutes);
                scene.recreate();homeReady(scene);assertEquals(theme,new LauncherPrefs(context).read().cardFormat);
            }
        }
    }

    @Test public void dpadPeriodApplyAndScheduleCancelKeepBothCardFormatsAndSharedSettings(){
        for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
            configure(theme);
            try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
                openAppearance(scene);downTo(scene,"appearance-period",6);pressCenter(scene,"DAY_NIGHT","period-day");
                assertTrue(device.pressDPadDown());focus(scene,"DAY_NIGHT","period-night");
                assertEquals("Focusing another period does not apply it","day",preferences.read().wallpaperPeriod);
                assertTrue(device.pressBack());focus(scene,"APPEARANCE","appearance-period");
                assertEquals("day",preferences.read().wallpaperPeriod);
                pressCenter(scene,"DAY_NIGHT","period-day");assertTrue(device.pressDPadDown());focus(scene,"DAY_NIGHT","period-night");
                assertTrue(device.pressDPadCenter());focus(scene,"DAY_NIGHT","period-night");
                assertEquals("night",preferences.read().wallpaperPeriod);
                scene.onActivity(a->assertTrue(tag(a,"period-night").isSelected()));
                assertTrue(device.pressDPadUp());focus(scene,"DAY_NIGHT","period-day");
                assertTrue(device.pressDPadUp());focus(scene,"DAY_NIGHT","period-schedule");
                assertEquals("night",preferences.read().wallpaperPeriod);
                assertTrue(device.pressDPadCenter());focus(scene,"DAY_NIGHT","period-schedule");
                assertEquals("schedule",preferences.read().wallpaperPeriod);
                downTo(scene,"period-day-start",4);pressCenter(scene,"DAY_START","schedule-420");
                scene.onActivity(a->assertNull("Night's exact boundary is unavailable as a day choice",activeRoot(a).findViewWithTag("schedule-1140")));
                assertTrue(device.pressDPadDown());focus(scene,"DAY_START","schedule-450");
                assertEquals(420,preferences.read().dayStartMinutes);
                assertTrue(device.pressBack());focus(scene,"DAY_NIGHT","period-day-start");
                assertEquals("Back cancels an unapplied time",420,preferences.read().dayStartMinutes);
                pressCenter(scene,"DAY_START","schedule-420");assertTrue(device.pressDPadDown());focus(scene,"DAY_START","schedule-450");
                assertTrue(device.pressDPadCenter());focus(scene,"DAY_NIGHT","period-day-start");
                assertEquals(450,preferences.read().dayStartMinutes);assertEquals(1140,preferences.read().nightStartMinutes);
                assertTrue(device.pressBack());focus(scene,"APPEARANCE","appearance-period");
                scene.recreate();focus(scene,"APPEARANCE","appearance-period");
                assertEquals("schedule",preferences.read().wallpaperPeriod);assertEquals(450,preferences.read().dayStartMinutes);
                assertEquals(theme,preferences.read().cardFormat);assertEquals(favorites,preferences.read().favoriteIds);assertEquals(8,preferences.read().background);
            }
        }
    }

    @Test public void importedExactMinuteScheduleKeepsSelectedFocusAndSupportsDpadApply()throws Exception{
        JSONObject backup=new JSONObject(SettingsBackup.exportJson(preferences.read()));
        backup.getJSONObject("settings").put("wallpaperPeriod","schedule")
                .put("dayStartMinutes",421).put("nightStartMinutes",420);
        SettingsBackup.Preview preview=SettingsBackup.preview(backup.toString());
        assertTrue("Portable settings accept adjacent unequal minute boundaries: "+preview.errors,preview.valid);
        assertTrue(SettingsBackup.apply(preferences,preview));
        String imported=SettingsBackup.exportJson(preferences.read());
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            openAppearance(scene);downTo(scene,"appearance-period",6);pressCenter(scene,"DAY_NIGHT","period-schedule");
            downTo(scene,"period-day-start",4);pressCenter(scene,"DAY_START","schedule-421");
            scene.onActivity(a->{
                assertTrue("Imported 07:01 is the actual selected row",tag(a,"schedule-421").isSelected());
                assertTrue(tag(a,"schedule-421").getContentDescription().toString().contains("07:01"));
                assertNull("Night's 07:00 cannot be selected as the day boundary",activeRoot(a).findViewWithTag("schedule-420"));
            });
            assertTrue(device.pressDPadDown());focus(scene,"DAY_START","schedule-450");
            assertEquals("Focus movement does not round or apply the imported minute",421,preferences.read().dayStartMinutes);
            assertTrue(device.pressBack());focus(scene,"DAY_NIGHT","period-day-start");
            assertEquals("Back preserves every imported setting",imported,SettingsBackup.exportJson(preferences.read()));
            assertTrue(device.pressDPadDown());focus(scene,"DAY_NIGHT","period-night-start");
            pressCenter(scene,"NIGHT_START","schedule-420");
            scene.onActivity(a->{
                assertTrue("Night keeps its exact selected 07:00 row",tag(a,"schedule-420").isSelected());
                assertNull("Day's 07:01 cannot be selected as the night boundary",activeRoot(a).findViewWithTag("schedule-421"));
            });
            assertTrue(device.pressDPadDown());focus(scene,"NIGHT_START","schedule-450");
            assertEquals("Navigating before OK leaves the old night boundary intact",420,preferences.read().nightStartMinutes);
            pressCenter(scene,"DAY_NIGHT","period-night-start");
            assertEquals(421,preferences.read().dayStartMinutes);assertEquals(450,preferences.read().nightStartMinutes);
            assertEquals("schedule",preferences.read().wallpaperPeriod);
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE,preferences.read().cardFormat);assertEquals(favorites,preferences.read().favoriteIds);
        }
    }

    @Test public void pairedGalleryRestoresLegacySelectionAndAppliesAnotherPairWithoutChangingCardFormat(){
        for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
            configure(theme);
            try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
                openAppearance(scene);downTo(scene,"appearance-background",2);pressCenter(scene,"GALLERY","background-0");
                scene.onActivity(a->assertTrue("Legacy daytime ID8 belongs to the lake pair",tag(a,"background-0").isSelected()));
                await(scene,"Both legacy previews are loaded",a->pairImagesLoaded(tag(a,"background-0")));
                assertTrue(device.pressDPadRight());focus(scene,"GALLERY","background-2");
                assertEquals("Moving focus cannot replace the saved legacy image",8,preferences.read().background);
                assertTrue(device.pressBack());focus(scene,"APPEARANCE","appearance-background");
                assertEquals(8,preferences.read().background);
                pressCenter(scene,"GALLERY","background-0");moveGallery(scene,29);assertEquals(8,preferences.read().background);
                await(scene,"Another pair has independently loaded day/night previews",a->pairImagesLoaded(tag(a,"background-29")));
                assertTrue(device.pressDPadCenter());focus(scene,"GALLERY","background-29");
                assertEquals(29,preferences.read().background);scene.onActivity(a->assertTrue(tag(a,"background-29").isSelected()));
                assertEquals(theme,preferences.read().cardFormat);assertEquals(favorites,preferences.read().favoriteIds);
                assertTrue(device.pressBack());focus(scene,"APPEARANCE","appearance-background");
                scene.recreate();focus(scene,"APPEARANCE","appearance-background");
                pressCenter(scene,"GALLERY","background-29");assertEquals(29,preferences.read().background);
            }
        }
    }

    @Test public void minuteBoundaryRerenderKeepsClockSchedulingAliveAcrossPauseAndResume(){
        configure(LauncherPrefs.CARD_FORMAT_POSTER);preferences.setBackground(WallpaperCatalog.GRAPHITE);preferences.setWallpaperPeriod("night");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            homeReady(scene);
            // Preserve a consistent old night presentation and its real signature.
            // Supply the next policy snapshot, as a minute boundary would, without
            // moving the device clock or fabricating presentation bookkeeping.
            scene.onActivity(a->assertTrue((Boolean)get(a,"renderedNight")));
            preferences.setWallpaperPeriod("day");
            scene.onActivity(a->{
                Handler handler=(Handler)get(a,"main");Runnable tick=(Runnable)get(a,"clockTick");
                assertFalse(DayNightPolicy.isNight(preferences.read(),System.currentTimeMillis()));
                set(a,"prefs",preferences.read());handler.removeCallbacks(tick);tick.run();
                assertTrue("A period-change render must not stop subsequent minute ticks",handler.hasCallbacks(tick));
            });
            await(scene,"Period presentation catches up",a->settled(a)&&!((Boolean)get(a,"renderedNight")));
            scene.onActivity(a->{
                TextView clock=(TextView)tag(a,"clock");clock.setText("--:--");
                Handler handler=(Handler)get(a,"main");Runnable tick=(Runnable)get(a,"clockTick");handler.removeCallbacks(tick);tick.run();
                assertNotEquals("Clock text still receives its normal minute update","--:--",clock.getText().toString());
                assertTrue(handler.hasCallbacks(tick));
            });
            scene.moveToState(Lifecycle.State.STARTED);
            scene.onActivity(a->assertFalse("Paused Activity owns no clock timer",((Handler)get(a,"main")).hasCallbacks((Runnable)get(a,"clockTick"))));
            scene.moveToState(Lifecycle.State.RESUMED);homeReady(scene);
            scene.onActivity(a->assertTrue("Resume owns the next clock update",((Handler)get(a,"main")).hasCallbacks((Runnable)get(a,"clockTick"))));
        }
    }

    @Test public void stoppedMemoryPressureDropsPixelsAndResumeRebindsTheSameHome(){
        configure(LauncherPrefs.CARD_FORMAT_POSTER);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            homeReady(scene);
            await(scene,"Home's real wallpaper and favorite artwork are loaded",DayNightSettingsTest::homeImagesLoaded);
            scene.onActivity(a->{
                a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
                assertTrue("Visible Home must keep its pixels under pressure",homeImagesLoaded(a));
            });
            scene.moveToState(Lifecycle.State.CREATED);
            scene.onActivity(a->{
                assertFalse((Boolean)get(a,"resumed"));
                a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                assertTrue("An ordinary app switch does not discard retained pictures",homeImagesLoaded(a));
                a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
                assertNull(firstImage(tag(a,"favorite-0")).getDrawable());
                assertNull(((ImageView)get(a,"wallpaperView")).getDrawable());
                assertTrue("Resume must know that cleared pictures need a new binding",(Boolean)get(a,"renderDirty"));
            });
            scene.moveToState(Lifecycle.State.RESUMED);homeReady(scene);
            await(scene,"Resuming an unchanged Home rebinds both icons and wallpaper",a->settled(a)&&homeImagesLoaded(a));
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER,preferences.read().cardFormat);assertEquals(favorites,preferences.read().favoriteIds);
            assertEquals(8,preferences.read().background);assertEquals("day",preferences.read().wallpaperPeriod);
        }
    }

    private void configure(String format){preferences.setCardFormat(format);preferences.setBackground(8);preferences.setWallpaperPeriod("day");preferences.setDayNightSchedule(420,1140);}
    private void homeReady(ActivityScenario<MainActivity> scene){
        await(scene,"Home and its real app catalog are ready",a->{Future<?> load=(Future<?>)get(a,"loadFuture");
            return settled(a)&&"HOME".equals(a.currentScreenForTest())&&(Boolean)get(a,"catalogLoaded")&&load!=null&&load.isDone()
                    &&a.getCurrentFocus()!=null&&String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-");});
    }
    private void openAppearance(ActivityScenario<MainActivity> scene){
        homeReady(scene);assertTrue(device.pressDPadDown());focus(scene,"HOME","shortcut-launcher");
        pressCenter(scene,"SETTINGS","settings-appearance");pressCenter(scene,"APPEARANCE","appearance-cards");
    }
    private void pressCenter(ActivityScenario<MainActivity> scene,String screen,String key){assertTrue(device.pressDPadCenter());focus(scene,screen,key);}
    private void downTo(ActivityScenario<MainActivity> scene,String wanted,int limit){
        for(int i=0;i<limit&&!wanted.equals(focusedTag(scene));i++){
            String before=focusedTag(scene);assertTrue(device.pressDPadDown());await(scene,"Down leaves "+before,a->settled(a)&&!focused(a,before));
        }
        assertEquals(wanted,focusedTag(scene));
    }
    private void moveGallery(ActivityScenario<MainActivity> scene,int id){
        List<Integer> ids=new ArrayList<>();for(WallpaperCatalog.Entry entry:WallpaperCatalog.selectable())ids.add(entry.id);
        int destination=ids.indexOf(id);assertTrue(destination>=0);
        for(int moves=0;moves<ids.size()+4;moves++){
            String key=focusedTag(scene);if(key.equals("background-"+id))return;
            assertTrue("A gallery tile owns focus: "+key,key.startsWith("background-"));int current=ids.indexOf(Integer.parseInt(key.substring(11)));
            assertTrue(current>=0);int row=current/4,targetRow=destination/4;
            assertTrue(row<targetRow?device.pressDPadDown():row>targetRow?device.pressDPadUp():current<destination?device.pressDPadRight():device.pressDPadLeft());
            await(scene,"Gallery focus leaves "+key,a->settled(a)&&!focused(a,key));
        }
        fail("Could not reach scene "+id);
    }
    private static boolean pairImagesLoaded(View tile){
        List<ImageView> images=new ArrayList<>();collectImages(tile,images);
        return images.size()==2&&images.get(0).getDrawable() instanceof BitmapDrawable&&images.get(1).getDrawable() instanceof BitmapDrawable
                &&((BitmapDrawable)images.get(0).getDrawable()).getBitmap()!=((BitmapDrawable)images.get(1).getDrawable()).getBitmap();
    }
    private static void collectImages(View view,List<ImageView> images){if(view instanceof ImageView)images.add((ImageView)view);if(view instanceof ViewGroup)
        for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectImages(((ViewGroup)view).getChildAt(i),images);}
    private static ImageView firstImage(View view){List<ImageView> images=new ArrayList<>();collectImages(view,images);assertFalse(images.isEmpty());return images.get(0);}
    private static boolean homeImagesLoaded(MainActivity a){
        View favorite=activeRoot(a).findViewWithTag("favorite-0");ImageView wallpaper=(ImageView)get(a,"wallpaperView");
        return favorite!=null&&firstImage(favorite).getDrawable() instanceof BitmapDrawable&&wallpaper!=null&&wallpaper.getDrawable() instanceof BitmapDrawable;
    }
    private static void focus(ActivityScenario<MainActivity> scene,String screen,String key){await(scene,"Focus "+screen+"/"+key,a->settled(a)&&screen.equals(a.currentScreenForTest())&&focused(a,key));}
    private static boolean focused(MainActivity a,String key){return a.getCurrentFocus()!=null&&key.equals(a.getCurrentFocus().getTag());}
    private static View activeRoot(MainActivity a){return (View)get(a,"root");}
    private static View tag(MainActivity a,String key){View view=activeRoot(a).findViewWithTag(key);assertNotNull(key,view);return view;}
    private static String focusedTag(ActivityScenario<MainActivity> scene){AtomicReference<String> key=new AtomicReference<>();scene.onActivity(a->key.set(a.getCurrentFocus()==null?"null":String.valueOf(a.getCurrentFocus().getTag())));return key.get();}
    private static boolean settled(MainActivity a){View root=activeRoot(a);return root!=null&&root.isLaidOut()&&!root.isLayoutRequested()
            &&root.getVisibility()==View.VISIBLE&&root.getAlpha()>.999f&&a.hasWindowFocus()&&!(Boolean)get(a,"renderDirty")
            &&!((UiMotion)get(a,"uiMotion")).isRunning()&&scrollsIdle(root);}
    private static boolean scrollsIdle(View view){if(view instanceof TvScrollView&&((TvScrollView)view).isScrollAnimating())return false;
        if(view instanceof TvHorizontalScrollView&&((TvHorizontalScrollView)view).isScrollAnimating())return false;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(!scrollsIdle(((ViewGroup)view).getChildAt(i)))return false;return true;}
    private static void await(ActivityScenario<MainActivity> scene,String message,Predicate<MainActivity> predicate){
        AtomicBoolean ready=new AtomicBoolean();long deadline=SystemClock.uptimeMillis()+10000;
        do{scene.onActivity(a->ready.set(predicate.test(a)));if(ready.get()){
            scene.onActivity(a->{View focused=a.getCurrentFocus();assertNotNull(focused);assertTrue(focused.isShown());Rect bounds=new Rect();assertTrue(focused.getGlobalVisibleRect(bounds));
                assertTrue("Focused control has a visible viewport intersection",bounds.width()>0&&bounds.height()>0);assertEquals(1,focusedCount(a.getWindow().getDecorView()));});return;
        }SystemClock.sleep(20);}while(SystemClock.uptimeMillis()<deadline);
        AtomicReference<String> detail=new AtomicReference<>();scene.onActivity(a->detail.set(a.currentScreenForTest()+"/"+focusedTagValue(a)));fail(message+"; actual="+detail.get());
    }
    private static String focusedTagValue(MainActivity a){return a.getCurrentFocus()==null?"null":String.valueOf(a.getCurrentFocus().getTag());}
    private static int focusedCount(View view){int count=view.isFocused()?1:0;if(view instanceof ViewGroup)
        for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=focusedCount(((ViewGroup)view).getChildAt(i));return count;}
    private static Object get(MainActivity a,String name){try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(a);}catch(ReflectiveOperationException error){throw new AssertionError(name,error);}}
    private static void set(MainActivity a,String name,Object value){try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(a,value);}catch(ReflectiveOperationException error){throw new AssertionError(name,error);}}
    private static Map<String,Object> snapshot(SharedPreferences prefs){Map<String,Object> values=new HashMap<>();for(Map.Entry<String,?> entry:prefs.getAll().entrySet())values.put(entry.getKey(),entry.getValue() instanceof Set?new HashSet<>((Set<?>)entry.getValue()):entry.getValue());return values;}
    private static void restore(SharedPreferences prefs,Map<String,Object> values){SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,Object> entry:values.entrySet()){
        String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);
        else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
        else if(value instanceof Set){@SuppressWarnings("unchecked")Set<String> copy=new HashSet<>((Set<String>)value);editor.putStringSet(key,copy);}else throw new AssertionError(key);
    }assertTrue(editor.commit());assertEquals(values,snapshot(prefs));}
}
