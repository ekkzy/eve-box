package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.MainActivity;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WallpaperCatalog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The fallback follows the existing loader; glass must sample every source transition. */
@RunWith(AndroidJUnit4.class)
public final class WallpaperFallbackTest {
    private Context context;
    private SharedPreferences store;
    private Map<String,Object> original;

    @Before public void configureOwnedEmulator() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug",context.getPackageName());
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34,Build.VERSION.SDK_INT);assertEquals(3840,device.getDisplayWidth());assertEquals(2160,device.getDisplayHeight());
        assertEquals(1f,context.getResources().getConfiguration().fontScale,.001f);
        store=context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE);original=snapshot(store);
        LauncherPrefs prefs=new LauncherPrefs(context);
        prefs.setBackground(WallpaperCatalog.MOUNTAIN_PEAKS);prefs.setWallpaperPeriod("day");prefs.setWallpaperDim(0);
        prefs.setPaletteMode("dark");prefs.setWeatherEnabled(false);prefs.setWatchNextVisible(false);
        prefs.setRecommendationsEnabled(false);prefs.setReducedMotion(true);prefs.setSounds(false);
    }
    @After public void restorePreferences(){if(original!=null)restore(store,original);}

    @Test public void opaqueWallpaperHidesFallbackAndEveryTransitionRefreshesExistingLiveGlass() throws Exception {
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> host=new AtomicReference<>();activity.onActivity(host::set);
            UiImageLoader loader=onMain(()->(UiImageLoader)field(host.get(),"imageLoader"));
            await("Main backdrop and catalog are ready",()->onMain(()->{
                ImageView photo=(ImageView)field(host.get(),"wallpaperView");
                return Boolean.TRUE.equals(field(host.get(),"catalogLoaded"))&&!Boolean.TRUE.equals(field(host.get(),"renderDirty"))
                        &&photo!=null&&photo.getDrawable()!=null&&loader.diagnostics().jobs==0;
            }));
            main(()->{
                ImageView photo=(ImageView)field(host.get(),"wallpaperView");
                assertTrue("The real Main photo owns its procedural fallback",photo.getTag() instanceof SceneBackground);
                View fallback=(View)photo.getTag();assertSame(photo.getParent(),fallback.getParent());
                assertEquals("A successfully loaded Main wallpaper hides the covered fallback",View.GONE,fallback.getVisibility());
            });
            int generation=onMain(()->(Integer)field(host.get(),"renderGeneration"));
            AtomicReference<Fixture> value=new AtomicReference<>();
            main(()->{loader.pause();value.set(new Fixture(host.get()));});
            Fixture fixture=value.get();
            Bitmap fallbackPixels=null,photoPixels=null;
            try{
                int resource=WallpaperCatalog.resource(context,WallpaperCatalog.MOUNTAIN_PEAKS,false);
                main(()->loader.wallpaper(fixture.photo,resource,640,360,generation));
                await("Loading fallback through live glass",()->onMain(fixture.glass::isLiveBlurReady));
                assertEquals(View.VISIBLE,onMain(fixture.fallback::getVisibility).intValue());
                assertNull(onMain(fixture.photo::getDrawable));fallbackPixels=copy(fixture);
                int blurRecordings=onMain(fixture.scene::getSharedBlurRecordingCount);
                main(loader::resume);
                await("Opaque bundled photo applied",()->onMain(()->fixture.photo.getDrawable()!=null
                        &&fixture.fallback.getVisibility()==View.GONE));
                photoPixels=copy(fixture);
                assertTrue("The cached glass must change from vector to actual photo pixels",
                        glassDifference(fallbackPixels,photoPixels)>8f);
                main(()->fixture.fallback.setVisibility(View.VISIBLE));
                samePixels("The former opaque photo plus visible fallback is equivalent",photoPixels,fixture);
                main(()->{fixture.fallback.setVisibility(View.GONE);loader.pause();loader.forget(fixture.photo);loader.trim();});
                samePixels("Forget restores the vector through the same glass cache",fallbackPixels,fixture);
                main(()->{loader.wallpaper(fixture.photo,resource,640,360,generation);loader.resume();});
                await("Reload hides the fallback",()->onMain(()->fixture.photo.getDrawable()!=null
                        &&fixture.fallback.getVisibility()==View.GONE));
                samePixels("Reload updates the existing shared blur",photoPixels,fixture);
                main(()->{
                    loader.pause();
                    // The exact recursive path used by stopped memory-pressure cleanup.
                    try{Method clear=MainActivity.class.getDeclaredMethod("clearStoppedImages",View.class);
                        clear.setAccessible(true);clear.invoke(host.get(),fixture.scene);
                    }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
                    loader.trim();
                });
                assertNull(onMain(fixture.photo::getDrawable));
                assertEquals(View.VISIBLE,onMain(fixture.fallback::getVisibility).intValue());
                samePixels("Trim cleanup restores the fallback without a stale photo blur",fallbackPixels,fixture);
                AtomicInteger failures=new AtomicInteger();
                main(()->{loader.request(fixture.photo,"native-wallpaper-fallback-error",generation,()->{
                    failures.incrementAndGet();return null;
                });loader.resume();});
                await("Controlled failed decode has completed",()->failures.get()>0&&onMain(()->loader.diagnostics().jobs==0));
                assertNull(onMain(fixture.photo::getDrawable));
                assertEquals(View.VISIBLE,onMain(fixture.fallback::getVisibility).intValue());
                samePixels("Decode failure leaves the real fallback visible through glass",fallbackPixels,fixture);
                assertEquals("Content and fallback visibility changes reuse one shared blur source",blurRecordings,
                        onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
            }finally{
                if(fallbackPixels!=null)fallbackPixels.recycle();if(photoPixels!=null)photoPixels.recycle();
                main(()->{loader.forget(fixture.photo);fixture.owner.close();fixture.dialog.dismiss();loader.resume();});
            }
        }
    }
    private static float glassDifference(Bitmap first,Bitmap second){
        long difference=0;int samples=0;
        for(int y=110;y<230;y+=3)for(int x=110;x<460;x+=3){
            int a=first.getPixel(x,y),b=second.getPixel(x,y);
            for(int shift=0;shift<=16;shift+=8){difference+=Math.abs((a>>shift&255)-(b>>shift&255));samples++;}
        }return difference/(float)samples;
    }
    private static void samePixels(String message,Bitmap expected,Fixture fixture)throws Exception{
        Bitmap actual=copy(fixture);long difference=0;int maximum=0;
        try{
            for(int y=0;y<360;y++)for(int x=0;x<640;x++){
                int a=expected.getPixel(x,y),b=actual.getPixel(x,y);
                for(int shift=0;shift<=16;shift+=8){int delta=Math.abs((a>>shift&255)-(b>>shift&255));maximum=Math.max(maximum,delta);difference+=delta;}
            }
            float mean=difference/(640*360*3f);
            String diagnostics="";
            if(maximum>1||mean>.05f){
                File folder=fixture.root.getContext().getExternalFilesDir("wallpaper-fallback-diagnostics");
                try{
                    if(folder==null||!folder.isDirectory()&&!folder.mkdirs())throw new IOException("No diagnostic directory");
                    try(FileOutputStream output=new FileOutputStream(new File(folder,"expected.png"))){expected.compress(Bitmap.CompressFormat.PNG,100,output);}
                    try(FileOutputStream output=new FileOutputStream(new File(folder,"actual.png"))){actual.compress(Bitmap.CompressFormat.PNG,100,output);}
                    diagnostics=", images="+folder.getAbsolutePath();
                }catch(IOException failure){diagnostics=", diagnostic save failed: "+failure.getMessage();}
            }
            assertTrue(message+": max="+maximum+", mean="+mean+", rawTop="+Integer.toHexString(expected.getPixel(20,20))+"/"+Integer.toHexString(actual.getPixel(20,20))
                    +", glass="+Integer.toHexString(expected.getPixel(320,170))+"/"+Integer.toHexString(actual.getPixel(320,170))
                    +", rawBottom="+Integer.toHexString(expected.getPixel(620,340))+"/"+Integer.toHexString(actual.getPixel(620,340))
                    +", states="+fixture.states+diagnostics,maximum<=1&&mean<=.05f);
        }finally{actual.recycle();}
    }
    private static Bitmap copy(Fixture fixture)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(100);
        Bitmap result=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);CountDownLatch done=new CountDownLatch(1);int[] status={-1};
        main(()->{int[] origin=new int[2];fixture.root.getLocationInWindow(origin);
            PixelCopy.request(fixture.dialog.getWindow(),new Rect(origin[0],origin[1],origin[0]+640,origin[1]+360),result,
                    code->{status[0]=code;done.countDown();},new Handler(Looper.getMainLooper()));});
        assertTrue("PixelCopy callback",done.await(4,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status[0]);
        fixture.states.add(onMain(()->"capture"+fixture.states.size()+": root="+fixture.root.getWidth()+"x"+fixture.root.getHeight()
                +", fallback="+fixture.fallback.getVisibility()+"/shown"+fixture.fallback.isShown()+"/"+fixture.fallback.getWidth()+"x"+fixture.fallback.getHeight()
                +"/measured"+fixture.fallback.getMeasuredWidth()+"x"+fixture.fallback.getMeasuredHeight()+"/alpha"+fixture.fallback.getAlpha()
                +"/dirty"+fixture.fallback.isDirty()+"/layout"+fixture.fallback.isLayoutRequested()+"/opaque"+fixture.fallback.isOpaque()
                +", photo="+fixture.photo.getVisibility()+"/drawable"+(fixture.photo.getDrawable()!=null)+"/"+fixture.photo.getWidth()+"x"+fixture.photo.getHeight()
                +"/opaque"+fixture.photo.isOpaque()+"/alpha"+fixture.photo.getAlpha()
                +", source="+fixture.scene.getWidth()+"x"+fixture.scene.getHeight()+"/record"+fixture.scene.getRecordingCount()+"/live"+fixture.scene.isLiveSourceReady()
                +", blur="+fixture.scene.getSharedBlurRecordingCount()+"/nodes"+fixture.scene.getSharedBlurNodeCount()+"/panel"+fixture.glass.getRecordingCount()
                +"/live"+fixture.glass.isLiveBlurReady()));
        return result;
    }
    private static Object field(MainActivity host,String name){
        try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(host);}
        catch(ReflectiveOperationException failure){throw new AssertionError(name,failure);}
    }
    private static void await(String message,BooleanSupplier ready){long until=SystemClock.uptimeMillis()+6000;
        while(SystemClock.uptimeMillis()<until){if(ready.getAsBoolean())return;SystemClock.sleep(30);}fail(message);}
    private static void main(Runnable work){InstrumentationRegistry.getInstrumentation().runOnMainSync(work);}
    private static <T>T onMain(Supplier<T> work){AtomicReference<T> value=new AtomicReference<>();main(()->value.set(work.get()));return value.get();}
    private static Map<String,Object> snapshot(SharedPreferences prefs){Map<String,Object> result=new HashMap<>();
        for(Map.Entry<String,?> entry:prefs.getAll().entrySet())result.put(entry.getKey(),entry.getValue() instanceof Set?new HashSet<>((Set<?>)entry.getValue()):entry.getValue());return result;}
    private static void restore(SharedPreferences prefs,Map<String,Object> values){SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,Object> entry:values.entrySet()){String key=entry.getKey();Object v=entry.getValue();
            if(v instanceof String)editor.putString(key,(String)v);else if(v instanceof Integer)editor.putInt(key,(Integer)v);
            else if(v instanceof Long)editor.putLong(key,(Long)v);else if(v instanceof Float)editor.putFloat(key,(Float)v);else if(v instanceof Boolean)editor.putBoolean(key,(Boolean)v);
            else if(v instanceof Set){@SuppressWarnings("unchecked")Set<String> copy=new HashSet<>((Set<String>)v);editor.putStringSet(key,copy);}else throw new AssertionError(key);
        }assertTrue(editor.commit());assertEquals(values,snapshot(prefs));}
    private static final class Fixture{
        final Dialog dialog;final FrameLayout root;final GlassScene scene;final SceneBackground fallback;final ImageView photo;
        final GlassBackdrop owner=new GlassBackdrop();final GlassDrawable glass;
        final List<String> states=new ArrayList<>();
        Fixture(Context context){
            dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root=new FrameLayout(context);root.setBackgroundColor(Color.BLACK);scene=new GlassScene(context);
            root.addView(scene,new FrameLayout.LayoutParams(640,360));fallback=new SceneBackground(context);fallback.setScene(1,WallpaperCatalog.MOUNTAIN_PEAKS);
            scene.addView(fallback,new FrameLayout.LayoutParams(-1,-1));photo=new ImageView(context);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setTag(fallback);
            scene.addView(photo,new FrameLayout.LayoutParams(-1,-1));View panel=new View(context);glass=owner.drawable(panel,scene,20,false,false);panel.setBackground(glass);panel.setClipToOutline(true);
            FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(420,180);bounds.leftMargin=80;bounds.topMargin=80;root.addView(panel,bounds);
            dialog.setContentView(root);Window window=dialog.getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.show();window.setLayout(640,360);
        }
    }
}
