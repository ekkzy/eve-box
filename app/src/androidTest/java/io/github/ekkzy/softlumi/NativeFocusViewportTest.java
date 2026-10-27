package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WallpaperCatalog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native ScrollView arrowScroll must protect the future focus outline, not just direct requestFocus. */
@RunWith(AndroidJUnit4.class)
public final class NativeFocusViewportTest {
    @Test public void realDpadSmoothScrollKeepsGallerySettingsAndCatalogFocusFullyVisible() throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug",context.getPackageName());
        assertTrue("This regression uses the normal 4-column gallery",context.getResources().getConfiguration().fontScale<1.2f);
        context.getSharedPreferences(LauncherPrefs.FILE_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        LauncherPrefs prefs=new LauncherPrefs(context);prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);prefs.setBackground(0);prefs.setLanguage("ru");prefs.setReducedMotion(false);prefs.setSounds(false);prefs.setWeatherEnabled(false);
        List<String> ids=new ArrayList<>();for(AppEntry app:AppCatalog.load(context))if(ids.size()<8)ids.add(app.id);prefs.setFavorites(ids);
        List<WallpaperCatalog.Entry> gallery=WallpaperCatalog.selectable();
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();device.pressDPadDown();settle();focused(scene,"shortcut-launcher");
            for(int i=0;i<4;i++){device.pressDPadLeft();settle();}focused(scene,"shortcut-apps");
            scene.onActivity(a->assertEquals("The lower panel stays on Home","HOME",a.currentScreenForTest()));
            for(int i=0;i<3;i++){device.pressDPadRight();settle();}focused(scene,"shortcut-wallpapers");
            device.pressDPadCenter();settle();focused(scene,"background-0");
            device.pressDPadRight();settle();device.pressDPadDown();settle();focused(scene,"background-"+gallery.get(5).id);
            capture(context,device,"native-gallery-focused-"+gallery.get(5).id);inside(scene,"Native Down to middle gallery row");
            device.pressDPadDown();settle();focused(scene,"background-"+gallery.get(9).id);capture(context,device,"native-gallery-focused-"+gallery.get(9).id);inside(scene,"Native Down to later gallery row");
            int selected=9;
            while(selected+4<gallery.size()){
                selected+=4;
                device.pressDPadDown();settle();focused(scene,"background-"+gallery.get(selected).id);inside(scene,"Native Down to gallery "+gallery.get(selected).id);
            }
            while(selected+1<gallery.size()){
                selected++;
                device.pressDPadRight();settle();focused(scene,"background-"+gallery.get(selected).id);inside(scene,"Native Right to final gallery column");
            }
            capture(context,device,"native-gallery-focused-"+WallpaperCatalog.MAX_ID);
            device.pressBack();settle();focused(scene,"shortcut-wallpapers");
            device.pressDPadRight();settle();focused(scene,"shortcut-launcher");
            device.pressDPadCenter();settle();focused(scene,"settings-appearance");
            device.pressDPadCenter();settle();focused(scene,"appearance-cards");inside(scene,"Native appearance initial card format row");
            for(String tag:new String[]{"appearance-background","appearance-palette","appearance-dim","appearance-period","appearance-motion"}){
                device.pressDPadDown();settle();focused(scene,tag);inside(scene,"Native appearance Down to "+tag);
            }
            capture(context,device,"native-appearance-last-row");inside(scene,"Native Down to last settings row");
            device.pressBack();settle();focused(scene,"settings-appearance");
            device.pressBack();settle();focused(scene,"shortcut-launcher");
            for(int i=0;i<4;i++){device.pressDPadLeft();settle();}focused(scene,"shortcut-apps");device.pressDPadCenter();settle();
            for(int i=0;i<6;i++){device.pressDPadDown();settle();inside(scene,"Native catalog Down "+i);}
            capture(context,device,"native-catalog-bottom");
        }
    }
    private static void focused(ActivityScenario<MainActivity> scene,String tag){scene.onActivity(a->{assertNotNull(a.getCurrentFocus());assertEquals(tag,a.getCurrentFocus().getTag());});}
    private static void inside(ActivityScenario<MainActivity> scene,String phase){scene.onActivity(a->{
        View focus=a.getCurrentFocus();assertNotNull(focus);Matrix transform=new Matrix();focus.transformMatrixToGlobal(transform);
        RectF bounds=new RectF(0,0,focus.getWidth(),focus.getHeight());transform.mapRect(bounds);
        View window=focus.getRootView();Matrix windowMatrix=new Matrix();window.transformMatrixToGlobal(windowMatrix);
        RectF windowBounds=new RectF(0,0,window.getWidth(),window.getHeight());windowMatrix.mapRect(windowBounds);
        assertTrue(phase+": focused outline "+bounds+" outside window "+windowBounds,bounds.left>=windowBounds.left-2&&bounds.top>=windowBounds.top-2&&bounds.right<=windowBounds.right+2&&bounds.bottom<=windowBounds.bottom+2);
        for(ViewParent parent=focus.getParent();parent instanceof View;parent=parent.getParent())if(parent instanceof ScrollView||parent instanceof HorizontalScrollView){
            View scroll=(View)parent;Matrix matrix=new Matrix();scroll.transformMatrixToGlobal(matrix);
            RectF viewport=new RectF(0,0,scroll.getWidth(),scroll.getHeight());matrix.mapRect(viewport);
            assertTrue(phase+": focused outline "+bounds+" outside "+viewport,bounds.left>=viewport.left-2&&bounds.top>=viewport.top-2&&bounds.right<=viewport.right+2&&bounds.bottom<=viewport.bottom+2);
        }
    });}
    private static void capture(Context context,UiDevice device,String name){String run=InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun","screenshots");assertTrue(run.matches("[A-Za-z0-9_-]+"));File folder=new File(context.getExternalFilesDir(null),run);assertTrue(folder.isDirectory()||folder.mkdirs());assertTrue(device.takeScreenshot(new File(folder,name+".png")));}
    private static void settle(){SystemClock.sleep(750);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
}
