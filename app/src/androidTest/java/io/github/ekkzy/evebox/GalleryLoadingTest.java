package io.github.ekkzy.evebox;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiImageLoader;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Cold-gallery work and selection regressions on the agreed owned 4K/font1 emulator. */
@RunWith(AndroidJUnit4.class)
public final class GalleryLoadingTest {
    private Context context;
    private SharedPreferences storage;
    private Map<String,?> original;

    @Before public void prepareOwnedEmulator()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Debug package only","io.github.ekkzy.evebox.debug",context.getPackageName());
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Never alter the physical TV preferences","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("Agreed font1 configuration",1f,context.getResources().getConfiguration().fontScale,.001f);
        storage=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);original=new HashMap<>(storage.getAll());
        assertTrue(storage.edit().clear().putInt("schema_version",LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs prefs=new LauncherPrefs(context);prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(WallpaperCatalog.NIGHT_LANDSCAPE);
        prefs.setPaletteMode("auto");prefs.setWallpaperPeriod("night");prefs.setWallpaperDim(0);prefs.setLanguage("en");
        prefs.setWeatherEnabled(false);prefs.setWatchNextVisible(false);prefs.setSounds(false);prefs.setReducedMotion(false);
        prefs.setFavorites(Collections.emptyList());
    }

    @After public void restoreExactPreferences(){
        if(original==null)return;
        SharedPreferences.Editor editor=storage.edit().clear();
        for(Map.Entry<String,?> item:original.entrySet()){
            String key=item.getKey();Object value=item.getValue();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);
            else if(value instanceof Set<?>){Set<String> copy=new HashSet<>();for(Object entry:(Set<?>)value)copy.add((String)entry);editor.putStringSet(key,copy);}
            else fail("Unsupported preference type: "+key);
        }
        assertTrue(editor.commit());assertEquals("Restore raw debug preferences",original,storage.getAll());
    }

    @Test public void firstViewportBoundsRequestsAndScrollingCancelsFarReceivers()throws Exception{
        try(ActivityScenario<MainActivity> scene=launchCold()){
            MainActivity activity=homeReady(scene);UiImageLoader loader=onMain(()->loader(activity));
            try(BusyWorkers busy=new BusyWorkers(loader)){
                openGallery(activity);
                AtomicReference<ImageView> first=new AtomicReference<>(),last=new AtomicReference<>();
                main(()->{
                    Map<ImageView,Object> images=images(activity);assertTrue("Fixture needs several wallpaper rows",images.size()>=12);
                    int requested=loading(images),all=images.size();
                    assertTrue("The visible page must request its previews",requested>0);
                    assertTrue("Opening must not request every raster wallpaper",requested<all);
                    assertTrue("Day/night preview pairs stay bounded to the first viewport and nearby row: "+requested,requested<=24);
                    UiImageLoader.Diagnostics state=loader.diagnostics();
                    assertTrue("Only the paired viewport subset is waiting: "+state.targets+"/"+state.jobs+"/"+state.queued,state.targets<=24&&state.jobs<=24&&state.queued<=24);
                    assertTrue("The frozen workers make cancellation observable",state.queued>0);
                    first.set(images.keySet().iterator().next());
                    for(ImageView target:images.keySet())last.set(target);
                    assertTrue((Boolean)field(images.get(first.get()),"loading"));
                    assertFalse("The far last preview has no receiver yet",(Boolean)field(images.get(last.get()),"loading"));
                    View lastTile=(View)last.get().getParent();
                    assertTrue("The final wallpaper is reachable by native TV focus",lastTile.requestFocus());
                });
                await(activity,"Focused final gallery row is visible while image workers are held",current->{
                    Rect visible=new Rect();View lastTile=(View)last.get().getParent();
                    return !galleryScroll(current).isScrollAnimating()&&current.getCurrentFocus()==lastTile
                            &&lastTile.getGlobalVisibleRect(visible)&&visible.width()>4&&visible.height()>4
                            &&(Boolean)field(images(current).get(last.get()),"loading");
                });
                main(()->{
                    Map<ImageView,Object> images=images(activity);
                    assertFalse("Scrolling far must revoke the first pending preview",(Boolean)field(images.get(first.get()),"loading"));
                    assertNull(first.get().getDrawable());
                    UiImageLoader.Diagnostics state=loader.diagnostics();
                    assertTrue("The last paired viewport owns a bounded receiver subset",state.targets>0&&state.targets<=32&&state.jobs<=32&&state.queued<=32);
                });
                busy.release();
                await(activity,"The newly visible final preview is decoded",current->last.get().getDrawable()!=null&&loader(current).diagnostics().jobs==0);
                main(()->assertNull("A cancelled far preview must not receive its queued result later",first.get().getDrawable()));
            }
        }
    }

    @Test public void samePaletteSelectionPreservesPageAndPersistedLightingChangeRestoresVisibleFocus(){
        try(ActivityScenario<MainActivity> scene=launchCold()){
            MainActivity activity=homeReady(scene);openGallery(activity);
            await(activity,"Initial gallery images are ready",current->loader(current).diagnostics().jobs==0);
            focus(activity,"background-"+WallpaperCatalog.MOONLIT_COAST);
            AtomicReference<View> rootBefore=new AtomicReference<>(),focusBefore=new AtomicReference<>();AtomicReference<TvScrollView> scrollBefore=new AtomicReference<>();int[] offset={0};
            main(()->{
                rootBefore.set(root(activity));scrollBefore.set(galleryScroll(activity));focusBefore.set(activity.getCurrentFocus());offset[0]=scrollBefore.get().getScrollY();
                assertTrue(focusBefore.get().performClick());
                assertSame("Same-palette selection must preserve the existing root",rootBefore.get(),root(activity));
                assertSame(scrollBefore.get(),galleryScroll(activity));assertSame(focusBefore.get(),activity.getCurrentFocus());
                assertEquals("Selection must preserve the gallery viewport",offset[0],galleryScroll(activity).getScrollY());
                assertFalse(tag(activity,"background-"+WallpaperCatalog.NIGHT_LANDSCAPE).isSelected());
                assertTrue(tag(activity,"background-"+WallpaperCatalog.MOONLIT_COAST).isSelected());
                assertEquals(WallpaperCatalog.MOONLIT_COAST,new LauncherPrefs(context).read().background);
            });
            // A gallery tile now represents a pair. Lighting policy, rather than
            // an invisible daytime ID, changes the automatic palette.
            main(()->new LauncherPrefs(context).setWallpaperPeriod("day"));
            focus(activity,"background-"+WallpaperCatalog.AMBER_GALLERY);
            main(()->assertTrue(activity.getCurrentFocus().performClick()));
            await(activity,"A new light palette page restores its selected wallpaper focus",current->
                    root(current)!=rootBefore.get()&&settled(current)&&current.getCurrentFocus()!=null
                    &&("background-"+WallpaperCatalog.AMBER_GALLERY).equals(current.getCurrentFocus().getTag()));
            main(()->{
                View selected=tag(activity,"background-"+WallpaperCatalog.AMBER_GALLERY);Rect visible=new Rect();
                assertTrue(selected.isSelected());assertSame(selected,activity.getCurrentFocus());
                assertTrue("Restored selection is visibly reachable",selected.getGlobalVisibleRect(visible)&&visible.width()>4&&visible.height()>4);
                assertEquals(WallpaperCatalog.AMBER_GALLERY,new LauncherPrefs(context).read().background);
                assertTrue(galleryScroll(activity).getScrollY()>=0);
            });
        }
    }

    @Test public void backBeforeDecodeDropsGalleryReceiversAndNeverWritesOldViews()throws Exception{
        try(ActivityScenario<MainActivity> scene=launchCold()){
            MainActivity activity=homeReady(scene);UiImageLoader loader=onMain(()->loader(activity));
            try(BusyWorkers busy=new BusyWorkers(loader)){
                openGallery(activity);List<ImageView> oldImages=new ArrayList<>();View[] galleryRoot={null};
                main(()->{
                    galleryRoot[0]=root(activity);oldImages.addAll(images(activity).keySet());
                    assertTrue("Back must happen with real queued gallery jobs",loader.diagnostics().queued>0);
                    for(ImageView target:oldImages)assertNull("No decode may have completed before Back",target.getDrawable());
                    activity.onBackPressed();
                    assertEquals("HOME",activity.currentScreenForTest());
                    assertEquals("Old receivers are removed synchronously with the route change",0,loader.diagnostics().targets);
                });
                busy.release();
                await(activity,"Home settles after the queued-gallery cancellation",current->
                        "HOME".equals(current.currentScreenForTest())&&root(current)!=galleryRoot[0]&&settled(current)
                        &&loader(current).diagnostics().jobs==0&&((ViewGroup)field(current,"renderHost")).getChildCount()==1);
                main(()->{
                    for(ImageView target:oldImages)assertNull("An obsolete Gallery View cannot receive a late image",target.getDrawable());
                    assertEquals(0,loader.diagnostics().queued);assertEquals(0,loader.diagnostics().targets);
                    assertEquals("shortcut-wallpapers",activity.getCurrentFocus().getTag());
                });
            }
        }
    }

    private ActivityScenario<MainActivity> launchCold(){
        return ActivityScenario.launch(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private static MainActivity homeReady(ActivityScenario<MainActivity> scene){
        AtomicReference<MainActivity> owner=new AtomicReference<>();scene.onActivity(owner::set);MainActivity activity=owner.get();
        main(()->{Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();assertEquals("Real 4K UI window",3840,bounds.width());assertEquals(2160,bounds.height());});
        await(activity,"Cold Home is stable before recording gallery work",current->{
            WeatherRepository.WeatherResult weather=(WeatherRepository.WeatherResult)field(current,"weather");
            UiImageLoader.Diagnostics state=loader(current).diagnostics();
            return "HOME".equals(current.currentScreenForTest())&&(Boolean)field(current,"catalogLoaded")
                    &&weather!=null&&weather.status==WeatherRepository.Status.DISABLED&&settled(current)
                    &&!(Boolean)field(current,"renderDirty")&&state.jobs==0&&state.running==0&&state.queued==0;
        });
        return activity;
    }
    private static void openGallery(MainActivity activity){
        focus(activity,"shortcut-wallpapers");main(()->assertTrue(activity.getCurrentFocus().performClick()));
        await(activity,"Gallery is shown with bounded requests",current->"GALLERY".equals(current.currentScreenForTest())
                &&field(current,"galleryBindings")!=null&&root(current).getVisibility()==View.VISIBLE&&settled(current)&&loading(images(current))>0);
    }
    private static void focus(MainActivity activity,String name){
        main(()->{View target=tag(activity,name);assertNotNull(name,target);assertTrue(target.requestFocus());});
        await(activity,"Focus and viewport settle on "+name,current->settled(current)&&current.getCurrentFocus()!=null&&name.equals(current.getCurrentFocus().getTag()));
    }
    private static boolean settled(MainActivity activity){
        View root=root(activity);return root!=null&&root.getVisibility()==View.VISIBLE&&!((UiMotion)field(activity,"uiMotion")).isRunning()&&!scrolling(root);
    }
    private static boolean scrolling(View view){
        if(view instanceof TvScrollView&&((TvScrollView)view).isScrollAnimating())return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(scrolling(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private static UiImageLoader loader(MainActivity activity){return (UiImageLoader)field(activity,"imageLoader");}
    private static View root(MainActivity activity){return (View)field(activity,"root");}
    private static View tag(MainActivity activity,String name){return root(activity).findViewWithTag(name);}
    private static TvScrollView galleryScroll(MainActivity activity){return (TvScrollView)field(field(activity,"galleryBindings"),"scroll");}
    @SuppressWarnings("unchecked") private static Map<ImageView,Object> images(MainActivity activity){return new LinkedHashMap<>((Map<ImageView,Object>)field(field(activity,"galleryBindings"),"images"));}
    private static int loading(Map<ImageView,Object> images){int count=0;for(Object preview:images.values())if((Boolean)field(preview,"loading"))count++;return count;}
    private static Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(ReflectiveOperationException error){throw new AssertionError(name,error);}}
    private static void await(MainActivity activity,String message,Predicate<MainActivity> condition){
        long deadline=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<deadline){if(onMain(()->condition.test(activity)))return;SystemClock.sleep(20);}
        assertTrue(message,onMain(()->condition.test(activity)));
    }
    private interface Query<T>{T get();}
    private static <T>T onMain(Query<T> query){
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(query.get());}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Main-thread check",failure.get());return result.get();
    }
    private static void main(Runnable action){onMain(()->{action.run();return null;});}
    private static final class BusyWorkers implements AutoCloseable{
        final CountDownLatch release=new CountDownLatch(1);
        BusyWorkers(UiImageLoader loader)throws Exception{
            CountDownLatch started=new CountDownLatch(2);ThreadPoolExecutor io=(ThreadPoolExecutor)onMain(()->field(loader,"io"));
            Runnable blocker=()->{started.countDown();try{release.await(45,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}};
            try{io.execute(blocker);io.execute(blocker);assertTrue("Hold both workers before gallery decode",started.await(5,TimeUnit.SECONDS));}
            catch(Exception|Error failure){release();throw failure;}
        }
        void release(){release.countDown();}
        @Override public void close(){release();}
    }
}
