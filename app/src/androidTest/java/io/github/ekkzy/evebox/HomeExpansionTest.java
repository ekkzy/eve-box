package io.github.ekkzy.evebox;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import io.github.ekkzy.evebox.ui.UiImageLoader;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Owned-emulator-only fixtures: these titles are never a user's playback history. */
@RunWith(AndroidJUnit4.class)
public final class HomeExpansionTest {
    private Context context; private UiDevice device; private LauncherPrefs prefs;
    @Before public void prepare() throws Exception {
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=new LauncherPrefs(context);prefs.setLanguage("ru");prefs.setSounds(false);prefs.setWeatherEnabled(false);prefs.setBackground(19);
        List<String> favorites=new ArrayList<>();for(AppEntry app:AppCatalog.load(context))if(favorites.size()<8)favorites.add(app.id);prefs.setFavorites(favorites);
    }
    @Test public void realDpadReachesEveryCardAndProtectsFocusInTheMainLayout() throws Exception {
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();
            for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
                trace(scene,"before theme "+theme);
                prefs.setCardFormat(theme);prefs.setBackground(19);int count=36;
                inject(scene,cards(count));trace(scene,"injected "+theme);focus(scene,"watch-test-0");trace(scene,"focused first "+theme);
                assertEquals("Both card formats must be exercised",theme,prefs.read().cardFormat);
                device.pressDPadLeft();settle();focused(scene,"watch-test-0");
                for(int i=1;i<count;i++){device.pressDPadRight();settle();focused(scene,"watch-test-"+i);if(i==1)trace(scene,"first right "+theme);inside(scene);}
                awaitPoster(scene,"watch-test-"+(count-1));
                scene.onActivity(a->{
                    HorizontalScrollView rail=a.findViewById(android.R.id.content).findViewWithTag("watch-rail");
                    assertNotNull(rail);assertTrue("Rail actually scrolled",rail.getScrollX()>0);
                    // Fixed 160-wide posters fit more cards in the same viewport
                    // than landscape cards. With 36 items, the first half is far
                    // beyond the visible row plus one viewport of prefetch in both formats.
                    assertTrue("Poster retention remains bounded to the near half of the long rail",posterCount(rail)<=count/2);
                    for(int distant=0;distant<count/2;distant++){
                        ViewGroup first=rail.findViewWithTag("watch-test-"+distant);assertNotNull(first);
                        assertTrue(first.getChildAt(0) instanceof ImageView);
                        assertNull("Distant poster "+distant+" releases pixels at the far end of the rail",((ImageView)first.getChildAt(0)).getDrawable());
                    }
                });
                capture("carousel-test-theme-"+theme+"-end");device.pressDPadRight();settle();focused(scene,"watch-test-"+(count-1));
                device.pressDPadLeft();settle();focused(scene,"watch-test-"+(count-2));inside(scene);
                device.pressDPadDown();settle();scene.onActivity(a->assertFalse("Down must leave rail",String.valueOf(a.getCurrentFocus().getTag()).startsWith("watch-test-")));
                // Normal-font Home keeps its lower panel in the same root. The large-font
                // compatibility composition may use a separate shortcuts page.
                scene.onActivity(a->{if(!"HOME".equals(a.currentScreenForTest()))a.onBackPressed();});settle();
            }
        }
    }
    @Test public void missingArtworkPlaceholdersOnlyDrawInsideTheViewportWithoutHidingCards() throws Exception {
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(WallpaperCatalog.GRAPHITE);
        prefs.setReducedMotion(true);prefs.setWatchNextVisible(true);prefs.setRecommendationsEnabled(false);
        assertFalse("A real favorite is needed for the two-page D-pad trip",prefs.read().favoriteIds.isEmpty());
        List<WatchNextRepository.Entry> missing=new ArrayList<>();long now=System.currentTimeMillis();
        for(int i=0;i<24;i++)missing.add(new WatchNextRepository.Entry("test-"+i,"TEST missing artwork "+i,"Synthetic fixture",
                "io.github.ekkzy.evebox.fixture.alpha","https://example.invalid/unused",
                i%2==0?"":"android.resource://"+context.getPackageName()+"/0",-1,0,now-i,true,true,WatchNextRepository.Kind.UNKNOWN));
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            awaitArtworkIdle(scene);inject(scene,missing);focus(scene,"watch-test-0");awaitArtworkIdle(scene);
            scene.onActivity(a->{
                assertEquals(3840,a.getWindow().getDecorView().getWidth());assertEquals(2160,a.getWindow().getDecorView().getHeight());
                assertEquals(1f,a.getResources().getConfiguration().fontScale,.001f);
            });
            assertPlaceholderPolicy(scene,24,true,true);
            // Put the first artwork completely outside the real viewport by one
            // local pixel. Its 1.06 prefetch envelope still reaches inside it.
            scene.onActivity(a->{
                HorizontalScrollView rail=a.getWindow().getDecorView().findViewWithTag("watch-rail");
                ImageView art=(ImageView)((ViewGroup)rail.findViewWithTag("watch-test-0")).getChildAt(0);
                Matrix matrix=new Matrix();art.transformMatrixToGlobal(matrix);
                RectF actual=new RectF(0,0,art.getWidth(),art.getHeight());matrix.mapRect(actual);
                Rect viewport=new Rect();assertTrue(rail.getGlobalVisibleRect(viewport));
                try{float scale=((View)field("designRoot").get(a)).getScaleX();
                    rail.scrollTo(rail.getScrollX()+(int)Math.ceil((actual.right-viewport.left)/scale)+1,0);
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
            settle();
            scene.onActivity(a->{
                HorizontalScrollView rail=a.getWindow().getDecorView().findViewWithTag("watch-rail");
                ImageView art=(ImageView)((ViewGroup)rail.findViewWithTag("watch-test-0")).getChildAt(0);
                Rect viewport=new Rect(),actual=new Rect();assertTrue(rail.getGlobalVisibleRect(viewport));
                assertFalse("Artwork is actually outside the viewport",art.getGlobalVisibleRect(actual)&&Rect.intersects(viewport,actual));
                assertNotEquals("Prefetch inflation cannot keep an offscreen placeholder drawing",View.VISIBLE,((View)art.getTag()).getVisibility());
                rail.scrollTo(0,0);
            });
            settle();assertPlaceholderPolicy(scene,24,true,true);
            for(int i=1;i<=3;i++){assertTrue(device.pressDPadRight());settle();focused(scene,"watch-test-"+i);}
            inside(scene);assertPlaceholderPolicy(scene,24,true,true);
            scene.onActivity(a->{HorizontalScrollView rail=a.getWindow().getDecorView().findViewWithTag("watch-rail");assertTrue("D-pad really scrolled the long row",rail.getScrollX()>0);});
            assertTrue(device.pressDPadLeft());settle();focused(scene,"watch-test-2");inside(scene);
            assertTrue(device.pressDPadDown());settle();
            scene.onActivity(a->assertTrue(String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-")));
            assertTrue(device.pressDPadDown());settle();
            scene.onActivity(a->assertTrue(String.valueOf(a.getCurrentFocus().getTag()).startsWith("shortcut-")));
            assertPlaceholderPolicy(scene,24,false,false);
            assertTrue(device.pressDPadUp());settle();assertTrue(device.pressDPadUp());settle();
            focused(scene,"watch-test-2");inside(scene);assertPlaceholderPolicy(scene,24,true,true);
        }
    }
    private static void awaitArtworkIdle(ActivityScenario<MainActivity> scene){
        long deadline=SystemClock.uptimeMillis()+5000;java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
        do{scene.onActivity(a->{try{
            Future<?> load=(Future<?>)field("loadFuture").get(a);UiImageLoader.Diagnostics images=((UiImageLoader)field("imageLoader").get(a)).diagnostics();
            ready.set(load!=null&&load.isDone()&&field("catalogLoaded").getBoolean(a)&&images.jobs==0&&images.running==0&&images.queued==0);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}});if(ready.get())return;SystemClock.sleep(20);}while(SystemClock.uptimeMillis()<deadline);
        fail("Local missing-artwork attempts and initial catalog work must finish");
    }
    private static void assertPlaceholderPolicy(ActivityScenario<MainActivity> scene,int count,boolean expectedVisible,boolean checkBothHiddenZones){
        scene.onActivity(a->{try{
            HorizontalScrollView rail=a.getWindow().getDecorView().findViewWithTag("watch-rail");assertNotNull(rail);
            Rect viewport=new Rect(),actual=new Rect();boolean railVisible=rail.getGlobalVisibleRect(viewport);
            @SuppressWarnings("unchecked")Set<ImageView> pending=(Set<ImageView>)field("pendingPosters").get(a);
            int shown=0,empty=0,failed=0,hiddenPrefetched=0,hiddenFar=0;
            for(int i=0;i<count;i++){
                ViewGroup card=rail.findViewWithTag("watch-test-"+i);assertNotNull(card);
                assertEquals("Cards remain present for native D-pad",View.VISIBLE,card.getVisibility());assertTrue(card.isFocusable());
                assertEquals(576,card.getWidth());assertEquals(196,card.getHeight());
                ImageView art=(ImageView)card.getChildAt(0);assertEquals(View.VISIBLE,art.getVisibility());assertNull(art.getDrawable());
                View placeholder=(View)art.getTag();boolean visible=railVisible&&art.getGlobalVisibleRect(actual)&&Rect.intersects(viewport,actual);
                if(visible){assertEquals("Visible missing artwork has an honest placeholder",View.VISIBLE,placeholder.getVisibility());shown++;if(i%2==0)empty++;else failed++;}
                else{assertNotEquals("Unused placeholders cannot draw outside the actual viewport",View.VISIBLE,placeholder.getVisibility());if(pending.contains(art))hiddenFar++;else hiddenPrefetched++;}
            }
            if(expectedVisible){assertTrue(shown>0);assertTrue("Empty URI remains visible when needed",empty>0);assertTrue("Failed local URI remains visible when needed",failed>0);}
            else assertEquals("The complete lower Home page draws no upper placeholders",0,shown);
            if(checkBothHiddenZones){assertTrue("Offscreen prefetched cards also hide their placeholders",hiddenPrefetched>0);assertTrue("Far cards keep their placeholders hidden",hiddenFar>0);}
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}});
    }
    @Test public void oneUnfilteredRailSurvivesRefreshRemovalAndReturnFromApps() throws Exception {
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(18);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();List<WatchNextRepository.Entry> entries=cards(9);inject(scene,entries);focus(scene,"watch-test-0");
            scene.onActivity(a->{
                for(String key:new String[]{"all","movie","show"})assertNull("No redundant category tabs",a.getWindow().getDecorView().findViewWithTag("watch-tab-"+key));
                for(int i=0;i<9;i++)assertNotNull("Movie, show and unknown items share one rail",a.getWindow().getDecorView().findViewWithTag("watch-test-"+i));
            });
            for(int i=1;i<8;i++){device.pressDPadRight();settle();}focused(scene,"watch-test-7");
            inject(scene,new ArrayList<>(entries));focused(scene,"watch-test-7");inside(scene);
            entries.remove(7);inject(scene,entries);focused(scene,"watch-test-8");inside(scene);
            focus(scene,"shortcut-apps");device.pressDPadCenter();settle();
            scene.onActivity(a->assertEquals("ALL_APPS",a.currentScreenForTest()));
            device.pressBack();settle();focused(scene,"shortcut-apps");
            device.pressDPadUp();settle();focused(scene,"favorite-0");
            device.pressDPadUp();settle();focused(scene,"watch-test-8");inside(scene);capture("main-test-rail-restored");
        }
    }
    @Test public void everyCatalogAssetExistsAndCuratedWallpapersRender() throws Exception {
        for(WallpaperCatalog.Entry entry:WallpaperCatalog.all())if(!entry.vector){int exact=context.getResources().getIdentifier(entry.drawableName,"drawable",context.getPackageName());assertTrue(entry.drawableName,exact!=0);assertEquals(exact,WallpaperCatalog.resource(context,entry.id));}
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();
            for(WallpaperCatalog.Entry entry:WallpaperCatalog.selectable())if(entry.id>=29){
                prefs.setBackground(entry.id);inject(scene,cards(3));focus(scene,"watch-test-0");SystemClock.sleep(300);capture("curated-wallpaper-"+entry.id+"-test");
            }
            prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(18);prefs.setWatchNextVisible(false);inject(scene,new ArrayList<>());capture("main-empty-clock");
            prefs.setWatchNextVisible(true);prefs.setPaletteMode("light");inject(scene,cards(8));focus(scene,"watch-test-0");SystemClock.sleep(500);capture("main-light-test");
            prefs.setPaletteMode("dark");inject(scene,cards(8));focus(scene,"watch-test-0");SystemClock.sleep(500);capture("main-dark-test");
        }
    }
    private List<WatchNextRepository.Entry> cards(int count){
        List<WatchNextRepository.Entry> list=new ArrayList<>();long now=System.currentTimeMillis();
        for(int i=0;i<count;i++)list.add(new WatchNextRepository.Entry("test-"+i,"ТЕСТ · Карточка "+(i+1)+" — продолжение просмотра","Демонстрационные данные","io.github.ekkzy.evebox.fixture.alpha","https://example.invalid/"+i,"android.resource://"+context.getPackageName()+"/"+(i%2==0?R.drawable.background_mist_forest:R.drawable.background_moon_coast),400,1000,now-i,true,true,i%3==0?WatchNextRepository.Kind.MOVIE:i%3==1?WatchNextRepository.Kind.SHOW:WatchNextRepository.Kind.UNKNOWN));return list;
    }
    private void inject(ActivityScenario<MainActivity> scene,List<WatchNextRepository.Entry> entries){
        scene.onActivity(a->{try{
            Field generation=field("loadGeneration");generation.setInt(a,generation.getInt(a)+1);
            field("prefs").set(a,prefs.read());field("continuation").set(a,entries);field("watchStatus").set(a,entries.isEmpty()?WatchNextRepository.Status.EMPTY:WatchNextRepository.Status.ITEMS);
            Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}});settle();
    }
    private static Field field(String name)throws ReflectiveOperationException{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void trace(ActivityScenario<MainActivity> scene,String stage){scene.onActivity(a->{
        View focus=a.getCurrentFocus();int[] position=new int[2];if(focus!=null)focus.getLocationOnScreen(position);
        View scroll=a.getWindow().getDecorView().findViewWithTag("home-scroll");
        android.os.Bundle report=new android.os.Bundle();report.putString("stream","\nHOME_RAIL "+stage+" focus="+(focus==null?"none":focus.getTag())+" y="+position[1]+" scroll="+(scroll==null?-1:scroll.getScrollY())+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2,report);
    });}
    private void focus(ActivityScenario<MainActivity> scene,String tag){scene.onActivity(a->{View target=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,target);assertTrue(tag,target.requestFocus());});settle();focused(scene,tag);}
    private void focused(ActivityScenario<MainActivity> scene,String tag){scene.onActivity(a->{assertNotNull(a.getCurrentFocus());assertEquals(tag,a.getCurrentFocus().getTag());});}
    private void inside(ActivityScenario<MainActivity> scene){scene.onActivity(a->{View focus=a.getCurrentFocus();Matrix m=new Matrix();focus.transformMatrixToGlobal(m);RectF rect=new RectF(0,0,focus.getWidth(),focus.getHeight());m.mapRect(rect);for(ViewParent p=focus.getParent();p instanceof View;p=p.getParent())if(p instanceof HorizontalScrollView||p instanceof android.widget.ScrollView){View v=(View)p;Matrix t=new Matrix();v.transformMatrixToGlobal(t);RectF viewport=new RectF(0,0,v.getWidth(),v.getHeight());t.mapRect(viewport);assertTrue("Focus outline "+rect+" outside "+viewport,rect.left>=viewport.left-2&&rect.right<=viewport.right+2&&rect.top>=viewport.top-2&&rect.bottom<=viewport.bottom+2);}View decor=a.getWindow().getDecorView();assertTrue("Focused card outside display "+rect,rect.left>=-2&&rect.top>=-2&&rect.right<=decor.getWidth()+2&&rect.bottom<=decor.getHeight()+2);});}
    private static void assertNoEllipsis(View view){assertNotNull(view);if(view instanceof android.widget.TextView){android.widget.TextView label=(android.widget.TextView)view;assertNotNull(label.getLayout());for(int i=0;i<label.getLayout().getLineCount();i++)assertEquals("Tab label must fit: "+label.getText(),0,label.getLayout().getEllipsisCount(i));}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)assertNoEllipsis(group.getChildAt(i));}}
    private static int posterCount(View view){
        if(view instanceof ViewGroup&&String.valueOf(view.getTag()).startsWith("watch-test-")){
            View art=((ViewGroup)view).getChildAt(0);assertTrue("A watch card owns a separate poster image",art instanceof ImageView);
            return ((ImageView)art).getDrawable() instanceof BitmapDrawable?1:0;
        }
        int count=0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=posterCount(group.getChildAt(i));}return count;
    }
    private static void awaitPoster(ActivityScenario<MainActivity> scene,String key){
        long deadline=SystemClock.uptimeMillis()+5000;java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
        do{
            scene.onActivity(a->{ViewGroup card=a.getWindow().getDecorView().findViewWithTag(key);assertNotNull(key,card);
                View art=card.getChildAt(0);assertTrue(art instanceof ImageView);loaded.set(((ImageView)art).getDrawable() instanceof BitmapDrawable);});
            if(loaded.get())return;SystemClock.sleep(40);
        }while(SystemClock.uptimeMillis()<deadline);
        fail("The focused card must load its real local poster before retention is checked: "+key);
    }
    private void capture(String name){String run=InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun","screenshots");assertTrue(run.matches("[A-Za-z0-9_-]+"));File folder=new File(context.getExternalFilesDir(null),run);assertTrue(folder.isDirectory()||folder.mkdirs());assertTrue(device.takeScreenshot(new File(folder,name+".png")));}
    private static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(650);}
}
