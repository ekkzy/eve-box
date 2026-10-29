package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Three short emulator-only 4K/font1 regressions. TEST cards never enter a TV provider. */
@RunWith(AndroidJUnit4.class)
public final class Candidate12HomeTest {
    private Context context;
    private UiDevice device;
    private LauncherPrefs preferences;
    private SharedPreferences storage;
    private Map<String,?> original;
    private List<AppEntry> apps;

    @Before public void prepare() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never inject fixtures into the production package","io.github.ekkzy.evebox.debug",context.getPackageName());
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Never inject fixtures on physical hardware","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("Standard font only; this test never changes global font settings",1f,context.getResources().getConfiguration().fontScale,.001f);
        apps=AppCatalog.load(context);assertTrue("At least two actual emulator apps are required",apps.size()>=2);
        storage=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);
        original=new HashMap<>(storage.getAll());
        assertTrue(storage.edit().clear().putInt("schema_version",LauncherPrefs.SCHEMA_VERSION).commit());
        preferences=new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);preferences.setBackground(19);preferences.setPaletteMode("dark");
        preferences.setLanguage("en");preferences.setWeatherEnabled(false);preferences.setSounds(false);
        preferences.setReducedMotion(false);preferences.setWatchNextVisible(true);
        List<String> ids=new ArrayList<>();for(AppEntry app:apps)if(ids.size()<8)ids.add(app.id);
        preferences.setFavorites(ids);
    }

    @After public void restorePreferences(){
        if(original==null)return;
        SharedPreferences.Editor editor=storage.edit().clear();
        for(Map.Entry<String,?> item:original.entrySet()){
            String key=item.getKey();Object value=item.getValue();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);
            else if(value instanceof Set){Set<String> copy=new HashSet<>();for(Object member:(Set<?>)value)copy.add((String)member);editor.putStringSet(key,copy);}
            else fail("Unsupported preference type: "+key);
        }
        assertTrue("Restore the exact debug favorites/settings snapshot",editor.commit());
        assertEquals(original,storage.getAll());
    }

    @Test public void normalHomeDescendsAndReturnsWithoutOpeningDrawer() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            require4k(activity);inject(activity);focus(activity,"favorite-0");
            capture("test-candidate12-home-top");
            device.pressDPadDown();
            await("Down reaches the default SL settings shortcut on the same page",()->onActivity(activity,a->{View focus=a.getCurrentFocus();return focus!=null&&"shortcut-launcher".equals(focus.getTag());}),4000);
            settle();
            activity.onActivity(a->{
                assertEquals("Down must retain the Home screen","HOME",a.currentScreenForTest());
                ScrollView home=a.getWindow().getDecorView().findViewWithTag("home-scroll");assertNotNull(home);
                assertTrue("The normal Home content must actually scroll downward",home.getScrollY()>0);
                assertInside(a.getCurrentFocus(),home);
            });
            capture("test-candidate12-home-shortcuts");
            device.pressDPadUp();
            await("Up returns to favorites on the same page",()->onActivity(activity,a->{View focus=a.getCurrentFocus();return focus!=null&&"favorite-0".equals(focus.getTag());}),4000);
            settle();
            activity.onActivity(a->{assertEquals("HOME",a.currentScreenForTest());assertInside(a.getCurrentFocus(),a.getWindow().getDecorView().findViewWithTag("home-scroll"));});
            capture("test-candidate12-home-returned");
        }
    }

    @Test public void bothSupportedThemesKeepAppTilesCaptionFree() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            require4k(activity);
            for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
                preferences.setCardFormat(theme);preferences.setBackground(19);preferences.setWatchNextVisible(true);inject(activity);focus(activity,"favorite-0");
                assertEquals("Each supported sparse theme ID must remain selected",theme,preferences.read().cardFormat);
                await("Visible app artwork replaces its temporary letter",()->onActivity(activity,a->{
                    View tile=a.getWindow().getDecorView().findViewWithTag("favorite-0");ImageView icon=firstImage(tile);
                    return icon!=null&&icon.getDrawable() instanceof BitmapDrawable&&(!(icon.getTag() instanceof View)||((View)icon.getTag()).getVisibility()!=View.VISIBLE);
                }),5000);
                activity.onActivity(a->{
                    assertEquals("HOME",a.currentScreenForTest());assertNotNull(a.getWindow().getDecorView().findViewWithTag("home-scroll"));
                    TextView clock=a.getWindow().getDecorView().findViewWithTag("clock");assertNotNull("Each clock composition retains its clock",clock);
                    if(android.os.Build.VERSION.SDK_INT>=28)assertEquals("The large clock uses regular weight, not medium",400,clock.getTypeface().getWeight());
                    for(int index=0;index<Math.min(8,apps.size());index++){
                        View tile=a.getWindow().getDecorView().findViewWithTag("favorite-"+index);assertNotNull("Favorite "+index,tile);
                        assertNoAppCaption(tile,firstImage(tile));assertNotNull("The caption-free tile retains its accessible name",tile.getContentDescription());
                    }
                });
                capture("test-candidate12-home-theme-"+theme);
            }
        }
    }

    @Test public void fixtureProviderBadgesUseExactPackageAndOfflineArtwork() throws Exception {
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);preferences.setBackground(18);
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            require4k(activity);inject(activity);focus(activity,"watch-candidate12-0");
            for(int index=0;index<2;index++){
                final int position=index;
                await("Provider badge loads its actual installed-app artwork",()->onActivity(activity,a->{
                    View card=a.getWindow().getDecorView().findViewWithTag("watch-candidate12-"+position);
                    View badge=card==null?null:card.findViewWithTag("provider:"+apps.get(position).packageName);
                    ImageView logo=firstImage(badge);return logo!=null&&logo.getDrawable() instanceof BitmapDrawable;
                }),5000);
                activity.onActivity(a->{
                    View card=a.getWindow().getDecorView().findViewWithTag("watch-candidate12-"+position);
                    View badge=card.findViewWithTag("provider:"+apps.get(position).packageName);assertNotNull(badge);
                    ImageView logo=firstImage(badge);Bitmap pixels=((BitmapDrawable)logo.getDrawable()).getBitmap();
                    assertEquals("Provider artwork remains transparent around its mark",0,Color.alpha(pixels.getPixel(0,0)));
                    int ink=0;for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)if(Color.alpha(pixels.getPixel(x,y))>128)ink++;
                    assertTrue("The exact-package badge must contain a visible real logo",ink>8);
                    assertNoText(badge);assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,badge.getImportantForAccessibility());
                });
            }
            capture("test-candidate12-provider-badges");
        }
    }

    private void inject(ActivityScenario<MainActivity> activity){
        // Do not invalidate the Activity's first asynchronous catalog request.
        await("Actual emulator catalog is ready",()->onActivity(activity,a->{try{return field("catalogLoaded").getBoolean(a);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}),5000);
        List<WatchNextRepository.Entry> cards=new ArrayList<>();long now=System.currentTimeMillis();
        for(int index=0;index<2;index++)cards.add(new WatchNextRepository.Entry("candidate12-"+index,
                "TEST · Provider fixture "+(index+1),"TEST only — not viewing history",apps.get(index).packageName,
                "https://example.invalid/test/"+index,"android.resource://"+context.getPackageName()+"/"+R.drawable.background_mist_forest,
                400,1000,now-index,true,true,WatchNextRepository.Kind.MOVIE));
        activity.onActivity(a->{try{
            Field generation=field("loadGeneration");generation.setInt(a,generation.getInt(a)+1);
            field("prefs").set(a,preferences.read());field("continuation").set(a,cards);field("watchStatus").set(a,WatchNextRepository.Status.ITEMS);
            Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
        await("Favorite catalog becomes available",()->onActivity(activity,a->a.getWindow().getDecorView().findViewWithTag("favorite-0")!=null),5000);
        settle();
    }
    private static Field field(String name)throws ReflectiveOperationException{Field value=MainActivity.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static void require4k(ActivityScenario<MainActivity> activity){activity.onActivity(a->{Rect bounds=a.getWindowManager().getCurrentWindowMetrics().getBounds();assertEquals("Actual 4K app window required",3840,bounds.width());assertEquals(2160,bounds.height());});}
    private static void focus(ActivityScenario<MainActivity> activity,String tag){activity.onActivity(a->{View target=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,target);assertTrue(tag,target.requestFocus());});settle();}
    private static void assertNoAppCaption(View node,ImageView icon){
        assertNotNull("App artwork view",icon);View fallback=icon.getTag() instanceof View?(View)icon.getTag():null;
        assertNoTextExcept(node,fallback);
    }
    private static void assertNoTextExcept(View node,View fallback){
        if(node instanceof TextView)assertSame("App tile may contain only its temporary fallback letter, never a caption",fallback,node);
        if(node instanceof ViewGroup){ViewGroup group=(ViewGroup)node;for(int index=0;index<group.getChildCount();index++)assertNoTextExcept(group.getChildAt(index),fallback);}
    }
    private static void assertNoText(View node){if(node instanceof TextView)fail("Provider badge must use the logo, not a caption");if(node instanceof ViewGroup){ViewGroup group=(ViewGroup)node;for(int i=0;i<group.getChildCount();i++)assertNoText(group.getChildAt(i));}}
    private static ImageView firstImage(View node){if(node instanceof ImageView)return(ImageView)node;if(node instanceof ViewGroup){ViewGroup group=(ViewGroup)node;for(int i=0;i<group.getChildCount();i++){ImageView match=firstImage(group.getChildAt(i));if(match!=null)return match;}}return null;}
    private static void assertInside(View focus,View viewport){
        assertNotNull(focus);assertNotNull(viewport);Matrix transform=new Matrix(),inverse=new Matrix();viewport.transformMatrixToGlobal(transform);assertTrue(transform.invert(inverse));
        Matrix focusMatrix=new Matrix();focus.transformMatrixToGlobal(focusMatrix);RectF bounds=new RectF(0,0,focus.getWidth(),focus.getHeight());focusMatrix.mapRect(bounds);inverse.mapRect(bounds);
        assertTrue("Focused surface must fit the Home viewport: "+bounds,bounds.left>=-2&&bounds.top>=-2&&bounds.right<=viewport.getWidth()+2&&bounds.bottom<=viewport.getHeight()+2);
    }
    private void capture(String name){String run=InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun","screenshots");assertTrue(run.matches("[A-Za-z0-9_-]+"));File folder=new File(context.getExternalFilesDir(null),run);assertTrue(folder.isDirectory()||folder.mkdirs());assertTrue(device.takeScreenshot(new File(folder,name+".png")));}
    private interface ActivityQuery<T>{T get(MainActivity activity);}
    private static <T>T onActivity(ActivityScenario<MainActivity> activity,ActivityQuery<T> query){AtomicReference<T> value=new AtomicReference<>();activity.onActivity(a->value.set(query.get(a)));return value.get();}
    private static void await(String message,BooleanSupplier ready,long timeout){long until=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<until){if(ready.getAsBoolean())return;SystemClock.sleep(40);}fail(message);}
    private static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(450);}
}
