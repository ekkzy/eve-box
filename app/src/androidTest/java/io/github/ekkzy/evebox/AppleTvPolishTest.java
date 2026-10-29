package io.github.ekkzy.evebox;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Emulator-only text, outline and gallery regressions on the host's recorded configuration. */
@RunWith(AndroidJUnit4.class)
public final class AppleTvPolishTest {
    private Context context;
    private UiDevice device;
    private LauncherPrefs prefs;
    private final java.util.LinkedHashSet<String> issues=new java.util.LinkedHashSet<>();
    @Before public void prepare() throws Exception {
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Only an owned emulator", "1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug",context.getPackageName());
        context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=new LauncherPrefs(context);prefs.setLanguage("ru");prefs.setWeatherEnabled(false);
        prefs.setSounds(false);prefs.setReducedMotion(false);prefs.setBackground(5);
        prefs.setCity("Очень длинное название — Петропавловск-Камчатский",53.04,158.68);
        List<String> favorites=new ArrayList<>();
        for(AppEntry app:AppCatalog.load(context))if(favorites.size()<8){favorites.add(app.id);prefs.setCustomLabel(app.packageName,"Длинное название приложения "+favorites.size()+" · ТЕСТ");}
        prefs.setFavorites(favorites);
    }

    @Test public void allLayoutsAndSettingsKeepTextInsideAndGalleryCornersClipped() throws Exception {
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();check(scene,"home");
            openLowerPanel(scene);check(scene,"home-shortcuts");
            activate(scene,"shortcut-launcher");activate(scene,"settings-appearance");
            for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}){
                activate(scene,"appearance-cards");check(scene,"theme-picker-"+theme);
                scene.onActivity(a->{assertEquals("CARD_FORMAT",a.currentScreenForTest());
                    assertNotNull(a.getWindow().getDecorView().findViewWithTag("cards-landscape"));
                    assertNotNull(a.getWindow().getDecorView().findViewWithTag("cards-poster"));});
                activate(scene,LauncherPrefs.CARD_FORMAT_LANDSCAPE.equals(theme)?"cards-landscape":"cards-poster");
                assertEquals(theme,prefs.read().cardFormat);
                scene.onActivity(a->{assertEquals("APPEARANCE",a.currentScreenForTest());assertEquals("appearance-cards",a.getCurrentFocus().getTag());});
                check(scene,"appearance-"+theme);
                device.pressBack();settle();device.pressBack();settle();check(scene,"home-"+theme);
                openLowerPanel(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-appearance");
            }
            activate(scene,"appearance-background");
            // Gallery exposes day/night pairs; the persisted catalog also keeps
            // legacy individual IDs that no longer own a separate tile.
            for(WallpaperCatalog.Entry entry:WallpaperCatalog.selectable()){
                focus(scene,"background-"+entry.id);
                final int selected=entry.id;
                scene.onActivity(a->{View tile=a.getWindow().getDecorView().findViewWithTag("background-"+selected);
                    assertTrue("Gallery card must clip artwork to its outline",tile.getClipToOutline());
                    assertNotNull(tile.getOutlineProvider());});
                check(scene,"gallery-"+entry.id);
            }
            device.pressBack();settle();activate(scene,"appearance-palette");check(scene,"light-appearance");
            activate(scene,"appearance-palette");check(scene,"dark-appearance");
            device.pressBack();settle();check(scene,"settings");
            activate(scene,"settings-weather");
            focus(scene,"weather-city");check(scene,"weather-long-city");
            activate(scene,"weather-city");check(scene,"city");device.pressBack();settle();device.pressBack();settle();
            scene.onActivity(a->assertNull("The removed screensaver is absent",a.getWindow().getDecorView().findViewWithTag("settings-screensaver")));
            activate(scene,"settings-advanced");
            String[] pages={"advanced-language","advanced-favorites","advanced-manage-apps","advanced-backup","advanced-remote","advanced-about"};
            for(String page:pages){activate(scene,page);check(scene,page);device.pressBack();settle();}
            focus(scene,"advanced-watch-permission");check(scene,"advanced-watch-permission");
            assertTrue("Geometry findings:\n"+String.join("\n",issues),issues.isEmpty());
        }
    }

    private void openLowerPanel(ActivityScenario<MainActivity> scene){
        for(int i=0;i<20;i++){
            AtomicBoolean found=new AtomicBoolean();
            scene.onActivity(a->{
                View current=a.getCurrentFocus();
                Rect visible=new Rect();
                found.set(current!=null&&String.valueOf(current.getTag()).startsWith("shortcut-")
                        &&current.getGlobalVisibleRect(visible)&&!visible.isEmpty());
                if(found.get()&&context.getResources().getConfiguration().fontScale<1.2f)
                    assertEquals("The lower panel belongs to the same Home screen","HOME",a.currentScreenForTest());
            });
            if(found.get())return;device.pressDPadDown();settle();
        }fail("Home shortcuts unreachable with D-pad Down");
    }
    private void focus(ActivityScenario<MainActivity> scene,String tag){
        scene.onActivity(a->{View target=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,target);assertTrue(tag,target.requestFocus());});
        settle();scene.onActivity(a->assertEquals(tag,a.getCurrentFocus().getTag()));
    }
    private void activate(ActivityScenario<MainActivity> scene,String tag){focus(scene,tag);device.pressDPadCenter();settle();}
    private void check(ActivityScenario<MainActivity> scene,String label){
        scene.onActivity(a->{assertNotNull(a.getCurrentFocus());assertTrue(a.getCurrentFocus().isShown());checkFocusViewport(a.getCurrentFocus(),label);checkText(a.getWindow().getDecorView(),label);});
        String directory=InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun","screenshots");assertTrue(directory.matches("[A-Za-z0-9_-]+"));
        File folder=new File(context.getExternalFilesDir(null),directory);assertTrue(folder.isDirectory()||folder.mkdirs());
        assertTrue(device.takeScreenshot(new File(folder,"apple-"+label+".png")));
    }
    private void checkText(View view,String label){
        Rect visible=new Rect();
        if(!view.isShown()||!view.getGlobalVisibleRect(visible)||visible.isEmpty())return;
        if(view instanceof TextView){
            TextView text=(TextView)view;Layout layout=text.getLayout();
            if(layout!=null&&text.getText().length()>0){
                int available=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
                int lines=Math.min(layout.getLineCount(),text.getMaxLines());
                int needed=lines==0?0:layout.getLineBottom(lines-1);
                if(needed>available+2)issues.add(label+": clipped text height "+needed+">"+available+" ["+text.getText()+"]");
                // getLineWidth includes invisible trailing spaces at a wrap boundary.
                // getLineMax measures visible extent, including any leading margin.
                for(int line=0;line<lines;line++)if(layout.getEllipsisCount(line)==0&&layout.getLineMax(line)>layout.getWidth()+2)
                    issues.add(label+": unbounded text width ["+text.getText()+"]");
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkText(group.getChildAt(i),label);}
    }
    private void checkFocusViewport(View view,String label){
        android.graphics.Matrix target=new android.graphics.Matrix();view.transformMatrixToGlobal(target);
        android.graphics.RectF focused=new android.graphics.RectF(0,0,view.getWidth(),view.getHeight());target.mapRect(focused);
        View window=view.getRootView();android.graphics.Matrix windowTransform=new android.graphics.Matrix();window.transformMatrixToGlobal(windowTransform);
        android.graphics.RectF windowBounds=new android.graphics.RectF(0,0,window.getWidth(),window.getHeight());windowTransform.mapRect(windowBounds);
        if(focused.left<windowBounds.left-2||focused.top<windowBounds.top-2||focused.right>windowBounds.right+2||focused.bottom>windowBounds.bottom+2)
            issues.add(label+": focused rounded border outside window "+focused+" outside "+windowBounds);
        for(android.view.ViewParent p=view.getParent();p instanceof View;p=p.getParent())if(p instanceof android.widget.ScrollView||p instanceof android.widget.HorizontalScrollView){
            View scroll=(View)p;android.graphics.Matrix transform=new android.graphics.Matrix();scroll.transformMatrixToGlobal(transform);
            android.graphics.RectF viewport=new android.graphics.RectF(0,0,scroll.getWidth(),scroll.getHeight());transform.mapRect(viewport);
            if(focused.left<viewport.left-2||focused.top<viewport.top-2||focused.right>viewport.right+2||focused.bottom>viewport.bottom+2)
                issues.add(label+": focused rounded border clipped by viewport "+focused+" outside "+viewport);
        }
    }
    private void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(650);}
}
