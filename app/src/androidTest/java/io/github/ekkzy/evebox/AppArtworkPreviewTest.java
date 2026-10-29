package io.github.ekkzy.evebox;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import io.github.ekkzy.evebox.ui.AppArtworkCatalog;
import io.github.ekkzy.evebox.ui.AppArtworkCatalogTest;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Labeled fixture: four licensed bundled marks and four native/generic identities. No service app is installed or launched. */
@RunWith(AndroidJUnit4.class)
public final class AppArtworkPreviewTest {
    @Test public void eightKnownFavoritesRenderInBothPalettesAndKeepDpadFocus() throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        LauncherPrefs prefs=new LauncherPrefs(context);prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(18);
        prefs.setLanguage("ru");prefs.setWeatherEnabled(false);prefs.setSounds(false);prefs.setReducedMotion(false);
        List<AppEntry> apps=new ArrayList<>();List<String> ids=new ArrayList<>();
        for(String pkg:new String[]{"com.vk.tv","ru.kinopoisk.tv","com.google.android.youtube.tv","net.gtvbox.vimuhd","com.plexapp.android","com.yablio.sendfilestotv","ru.ivi.client","ru.rutube.app"}){
            AppArtworkCatalog.Entry artwork=AppArtworkCatalog.find(pkg);assertNotNull(pkg,artwork);
            assertEquals(pkg+": only independently sourced bundled marks may bypass native fallback",
                    java.util.Arrays.asList("vk","kinopoisk","plex","rutube").contains(artwork.key),artwork.hasCuratedArtwork());
            AppEntry app=new AppEntry(new ComponentName(pkg,"preview.Activity"),artwork.title);apps.add(app);ids.add(app.id);
        }
        prefs.setFavorites(ids);
        List<WatchNextRepository.Entry> preview=new ArrayList<>();
        preview.add(new WatchNextRepository.Entry("art-preview","ПРЕДПРОСМОТР ОФОРМЛЕНИЯ","Тестовые данные · логотипы восьми приложений","preview.offline","https://example.invalid/preview","android.resource://"+context.getPackageName()+"/"+R.drawable.background_mist_forest,400,1000,System.currentTimeMillis(),true,true,WatchNextRepository.Kind.UNKNOWN));
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            SystemClock.sleep(1800);
            ImageSnapshot[] firstDark=null;
            int round=0;
            for(String palette:new String[]{"dark","light","dark","auto","auto"}){
                boolean light=round==1||round==4;
                prefs.setPaletteMode(palette);
                prefs.setBackground(round==3?19:round==4?20:18);
                LauncherPrefs.Settings requested=prefs.read();
                AtomicInteger previousRender=new AtomicInteger();
                AtomicReference<String> requestedSignature=new AtomicReference<>();
                scene.onActivity(activity->{try{
                    previousRender.set(field("renderGeneration").getInt(activity));
                    Field generation=field("loadGeneration");generation.setInt(activity,generation.getInt(activity)+1);
                    field("catalog").set(activity,apps);field("catalogLoaded").setBoolean(activity,true);
                    @SuppressWarnings("unchecked") Map<String,AppEntry> map=(Map<String,AppEntry>)field("appsById").get(activity);map.clear();for(AppEntry app:apps)map.put(app.id,app);
                    field("prefs").set(activity,requested);field("continuation").set(activity,preview);field("watchStatus").set(activity,WatchNextRepository.Status.ITEMS);
                    Method signature=MainActivity.class.getDeclaredMethod("settingsSignature",LauncherPrefs.Settings.class);signature.setAccessible(true);
                    requestedSignature.set((String)signature.invoke(activity,requested));
                    Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(activity);
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
                ImageSnapshot[] rendered=awaitCards(scene,apps,light,previousRender.get(),requestedSignature.get());
                if(round==0)firstDark=rendered;
                else if(round==1){
                    for(int i=0;i<apps.size();i++){
                        assertEquals(apps.get(i).packageName+": no inner plate in either palette",0,Color.alpha(rendered[i].base()));
                    }
                    // Brand RGB may legitimately stay identical between palettes.
                    // UiImageLoader's palette separation is exercised with our own
                    // achromatic settings glyph in AppIconRendererTest.
                }else if(round==2){
                    for(int i=0;i<apps.size();i++){
                        assertEquals(apps.get(i).packageName+": return width",firstDark[i].width,rendered[i].width);
                        assertEquals(apps.get(i).packageName+": return height",firstDark[i].height,rendered[i].height);
                        assertArrayEquals(apps.get(i).packageName+": dark→light→dark returns the same real artwork",firstDark[i].pixels,rendered[i].pixels);
                    }
                }
                scene.onActivity(a->{View first=a.getWindow().getDecorView().findViewWithTag("favorite-0");assertNotNull(first);assertTrue(first.requestFocus());});
                SystemClock.sleep(650);
                for(int i=0;i<8;i++){
                    final int selected=i;
                    scene.onActivity(a->{assertEquals("favorite-"+selected,a.getCurrentFocus().getTag());Bitmap bitmap=image(a.getCurrentFocus());assertNotNull("Tile image loaded",bitmap);assertNull(apps.get(selected).packageName,problem(new ImageSnapshot(bitmap),light));});
                    if(i<7){device.pressDPadRight();SystemClock.sleep(420);}
                }
                scene.onActivity(a->a.getWindow().getDecorView().findViewWithTag("favorite-1").requestFocus());SystemClock.sleep(650);
                String run=InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun","screenshots");assertTrue(run.matches("[A-Za-z0-9_-]+"));
                File folder=new File(context.getExternalFilesDir(null),run);assertTrue(folder.isDirectory()||folder.mkdirs());
                String name=round==2?"dark-return":round==3?"auto-dark":round==4?"auto-light":palette;
                assertTrue(device.takeScreenshot(new File(folder,"app-artwork-home-"+name+"-preview.png")));
                round++;
            }
        }
    }
    private static Field field(String name)throws ReflectiveOperationException{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}

    private static ImageSnapshot[] awaitCards(ActivityScenario<MainActivity> scene,List<AppEntry> apps,boolean light,int previousRender,String requestedSignature){
        AtomicReference<ImageSnapshot[]> ready=new AtomicReference<>();
        AtomicReference<String> last=new AtomicReference<>("No rendered favorites");
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline&&ready.get()==null){
            scene.onActivity(activity->{try{
                if(field("renderGeneration").getInt(activity)<=previousRender
                        ||!requestedSignature.equals(field("renderedPrefsSignature").get(activity))){
                    last.set("Requested settings have not rendered yet");return;
                }
                Object palette=field("palette").get(activity);
                Field paletteLight=palette.getClass().getDeclaredField("light");paletteLight.setAccessible(true);
                if(paletteLight.getBoolean(palette)!=light){last.set("Rendered palette is not the requested palette");return;}
                @SuppressWarnings("unchecked") Map<String,View> targets=(Map<String,View>)field("targets").get(activity);
                ImageSnapshot[] snapshots=new ImageSnapshot[apps.size()];Integer common=null;
                for(int i=0;i<apps.size();i++){
                    AppEntry app=apps.get(i);View tile=targets.get("favorite:"+app.id);
                    if(tile==null){last.set("Missing exact favorite "+app.id);return;}
                    Bitmap bitmap=image(tile);
                    if(bitmap==null||bitmap.isRecycled()){last.set("Image not loaded for "+app.packageName);return;}
                    ImageSnapshot snapshot=new ImageSnapshot(bitmap);String problem=problem(snapshot,light);
                    if(problem!=null){last.set(app.packageName+": "+problem);return;}
                    if(common==null)common=snapshot.base();
                    if(common.intValue()!=snapshot.base()){last.set(app.packageName+": card base differs from other favorites");return;}
                    snapshots[i]=snapshot;
                }
                ready.set(snapshots);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
            if(ready.get()==null)SystemClock.sleep(80);
        }
        assertNotNull("All eight actual bitmaps must load in the requested "+(light?"light":"dark")+" palette: "+last.get(),ready.get());
        return ready.get();
    }

    private static final class ImageSnapshot{
        final int width,height;final int[] pixels;
        ImageSnapshot(Bitmap image){width=image.getWidth();height=image.getHeight();pixels=new int[width*height];image.getPixels(pixels,0,width,0,0,width,height);}
        int base(){return pixels[(height/2)*width+Math.max(1,width/20)];}
    }

    private static String problem(ImageSnapshot image,boolean light){
        if(image.width>384||image.height>384)return "Unbounded icon bitmap";
        int base=image.base();if(Color.alpha(base)!=0)return "Artwork painted another inner card base";
        int detail=0;
        for(int pixel:image.pixels){
            if(AppArtworkCatalogTest.visibleOnNeutralFace(pixel,light))detail++;
        }
        if(detail<Math.max(12,image.pixels.length/900))return "Missing native or bundled glyph";
        return null;
    }

    private static Bitmap image(View view){
        if(view instanceof ImageView&&((ImageView)view).getDrawable() instanceof BitmapDrawable)return ((BitmapDrawable)((ImageView)view).getDrawable()).getBitmap();
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Bitmap bitmap=image(group.getChildAt(i));if(bitmap!=null)return bitmap;}}
        return null;
    }
}
