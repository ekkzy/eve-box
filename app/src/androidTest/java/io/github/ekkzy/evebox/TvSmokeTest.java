package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import io.github.ekkzy.evebox.data.SettingsBackup;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real TV UI tests. Fixture data is test-only and never bundled as production history. */
@RunWith(AndroidJUnit4.class)
public class TvSmokeTest {
    private Context context;
    private UiDevice device;

    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Preference-mutating smoke runs only on the owned debug emulator", "io.github.ekkzy.evebox.debug", context.getPackageName());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setLanguage("ru"); prefs.setReducedMotion(true); prefs.setWeatherEnabled(false);
        prefs.setFavorites(Collections.emptyList());
    }

    @Test public void emptyHomeDrawerSettingsAndBackHaveOneFocus() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            settle(); assertSingleFocus(scene);
            screenshot("01-night-empty");
            openDrawer(scene);
            String[] tags = {"shortcut-apps", "shortcut-wifi", "shortcut-bluetooth", "shortcut-wallpapers", "shortcut-launcher", "shortcut-tv"};
            for (String tag : tags) focus(scene, tag);
            focus(scene,"shortcut-apps");
            int direction=context.getResources().getConfiguration().fontScale>=1.2f?20:22;
            device.executeShellCommand("input keyevent "+direction+" "+direction+" "+direction);settle();assertFocus(scene,"shortcut-wallpapers");
            device.pressKeyCode(direction);settle();assertFocus(scene,"shortcut-launcher");
            device.pressKeyCode(direction);settle();assertFocus(scene,"shortcut-tv");
            device.pressKeyCode(direction);settle();assertFocus(scene,"shortcut-tv");
            screenshot("05-bottom-panel");
            activate(scene, "shortcut-launcher");
            screenshot("06-settings");
            activate(scene, "settings-weather");
            screenshot("07-weather-settings");
            device.pressBack(); settle(); assertFocus(scene, "settings-weather");
            activate(scene, "settings-advanced");
            screenshot("08-additional-settings");
            activate(scene, "advanced-remote");
            device.pressBack(); settle(); assertFocus(scene, "advanced-remote");
            device.pressBack(); settle(); assertFocus(scene, "settings-advanced");
            device.pressBack(); settle(); assertFocus(scene, "shortcut-launcher");
            device.pressBack(); settle(); assertSingleFocus(scene);
        }
    }

    @Test public void bothThemesKeepWallpaperAndFavoritesAcrossAppearanceAndRemoteNavigation() throws Exception {
        List<AppEntry> apps = AppCatalog.load(context);
        List<String> favoriteIds = new ArrayList<>();
        for (int i=0;i<Math.min(8,apps.size());i++) favoriteIds.add(apps.get(i).id);
        LauncherPrefs preferences = new LauncherPrefs(context); preferences.setFavorites(favoriteIds);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            settle();
            for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}) {
                openAppearance(scene);
                assertFocus(scene,"appearance-cards");activate(scene,"appearance-cards");
                scene.onActivity(a->{assertEquals("CARD_FORMAT",a.currentScreenForTest());
                    assertNotNull("Classic remains available",a.getWindow().getDecorView().findViewWithTag("cards-landscape"));
                    assertNotNull("Talkies remains available",a.getWindow().getDecorView().findViewWithTag("cards-poster"));});
                activate(scene,LauncherPrefs.CARD_FORMAT_LANDSCAPE.equals(theme)?"cards-landscape":"cards-poster");
                scene.onActivity(a->assertEquals("Selecting a theme returns to Appearance","APPEARANCE",a.currentScreenForTest()));
                assertEquals(theme, preferences.read().cardFormat);
                assertEquals(0,preferences.read().background);
                assertEquals(favoriteIds, preferences.read().favoriteIds);
                assertFocus(scene,"appearance-cards");
                device.pressBack(); settle(); device.pressBack(); settle(); device.pressBack(); settle();
                screenshot("theme-"+theme);
                assertSingleFocus(scene);
                for (int n=0;n<4;n++) {device.pressDPadRight();settle();assertSingleFocus(scene);}
                for (int n=0;n<4;n++) {device.pressDPadLeft();settle();assertSingleFocus(scene);}
            }
        }
    }

    @Test public void configurationAndOrderSurviveActivityRecreation() throws Exception {
        LauncherPrefs preferences = new LauncherPrefs(context);
        List<AppEntry> apps=AppCatalog.load(context);
        List<String> ids=new ArrayList<>();
        for(int i=0;i<Math.min(8,apps.size());i++)ids.add(apps.get(i).id);
        Collections.reverse(ids);preferences.setFavorites(ids);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);preferences.setBackground(2);preferences.setSounds(false);preferences.setReducedMotion(true);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();scene.recreate();settle();
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER,preferences.read().cardFormat);assertEquals(2,preferences.read().background);
            assertEquals(ids,preferences.read().favoriteIds);assertFalse(preferences.read().sounds);
            assertTrue(preferences.read().reducedMotion);assertSingleFocus(scene);
            screenshot("process-state-recreated");
        }
    }

    @Test public void longRussianCityRemainsNavigable() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);
        preferences.setCity("Очень длинное русское название города — Петропавловск-Камчатский",53.04,158.68);
        preferences.setWeatherEnabled(true);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-weather");
            for(int i=0;i<6;i++){device.pressDPadDown();settle();assertSingleFocus(scene);}
            screenshot("long-russian-weather");
            activate(scene,"weather-city");focus(scene,"city-query");
            device.pressBack();settle();assertSingleFocus(scene);
        }
    }

    @Test public void eightFavoritesRealLaunchAndDpadReorder() throws Exception {
        List<AppEntry> apps=AppCatalog.load(context);
        List<String> ids=new ArrayList<>();
        for(AppEntry app:apps)if(app.packageName.startsWith("io.github.ekkzy.evebox.fixture."))ids.add(app.id);
        assertEquals("Install the two separate test fixture APKs",2,ids.size());
        for(AppEntry app:apps)if(ids.size()<8&&!ids.contains(app.id))ids.add(app.id);
        assertEquals(8,ids.size());
        LauncherPrefs preferences=new LauncherPrefs(context);preferences.setFavorites(ids);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();
            for(int i=0;i<8;i++)focus(scene,"favorite-"+i);
            focus(scene,"favorite-0");device.pressDPadCenter();settle();
            assertTrue("A real explicit intent must launch the test app",device.getCurrentPackageName().startsWith("io.github.ekkzy.evebox.fixture."));
            device.pressBack();settle();assertSingleFocus(scene);
            focus(scene,"favorite-0");
            device.executeShellCommand("input keyevent --longpress 23");settle();
            device.pressDPadRight();settle();device.pressDPadCenter();settle();
            assertEquals(ids.get(0),preferences.read().favoriteIds.get(1));
            assertEquals(ids.get(1),preferences.read().favoriteIds.get(0));
            assertSingleFocus(scene);screenshot("eight-favorites-reordered");
        }
    }

    @Test public void nestedBackStackSurvivesRecreation() throws Exception {
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-advanced");activate(scene,"advanced-language");
            scene.recreate();settle();device.pressBack();settle();assertFocus(scene,"advanced-language");
            device.pressBack();settle();assertFocus(scene,"settings-advanced");
            device.pressBack();settle();assertFocus(scene,"shortcut-launcher");
        }
    }

    @Test public void mainReorderPreviewKeepsFavoritesOnTheirShelfAndSupportsCancel() throws Exception {
        List<AppEntry> apps=AppCatalog.load(context);List<String> ids=new ArrayList<>();
        LauncherPrefs preferences=new LauncherPrefs(context);
        for(AppEntry app:apps)if(ids.size()<8){ids.add(app.id);preferences.setCustomLabel(app.packageName,"ТЕСТ — избранное "+ids.size());}
        assertEquals("Eight installed test favorites are required",8,ids.size());
        preferences.setFavorites(ids);preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);preferences.setBackground(WallpaperCatalog.CONTENT_AMBIENT);
        preferences.setWatchNextVisible(false);preferences.setSounds(false);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            settle();focus(scene,"favorite-0");
            RectF first=tagBounds(scene,"favorite-0"),second=tagBounds(scene,"favorite-1");
            float tolerance=Math.max(2f,first.height()*.02f);
            device.executeShellCommand("input keyevent --longpress 23");settle();
            device.pressDPadRight();settle();assertFocus(scene,"favorite-1");
            RectF preview=tagBounds(scene,"favorite-1");
            assertEquals("Reorder must keep the focused favorite on the main shelf",first.centerY(),preview.centerY(),tolerance);
            assertEquals("Preview must occupy its neighbor's slot",second.centerX(),preview.centerX(),tolerance);
            assertEquals("Preview must not commit the order",ids,preferences.read().favoriteIds);
            screenshot("test-fixture-main-reorder-preview");
            device.pressBack();settle();assertFocus(scene,"favorite-0");
            assertEquals("Back cancels the preview",ids,preferences.read().favoriteIds);
            device.executeShellCommand("input keyevent --longpress 23");settle();
            device.pressDPadRight();settle();device.pressDPadCenter();settle();
            assertEquals(ids.get(0),preferences.read().favoriteIds.get(1));
            assertEquals(ids.get(1),preferences.read().favoriteIds.get(0));
            assertFocus(scene,"favorite-1");
            assertEquals("Committed favorite remains on the same shelf",first.centerY(),tagBounds(scene,"favorite-1").centerY(),tolerance);
            screenshot("test-fixture-main-reorder-committed");
        }
    }

    @Test public void missingFavoritesCanBeRemovedWithoutErasingInstalledApps() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);List<String> missing=new ArrayList<>();
        for(int i=0;i<8;i++)missing.add("invalid.evebox.missing"+i+"/.GoneActivity");
        preferences.setFavorites(missing);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-advanced");activate(scene,"advanced-favorites");
            activate(scene,"favorites-remove-missing");assertEquals(0,preferences.read().favoriteIds.size());assertSingleFocus(scene);
            screenshot("missing-favorites-recovered");
        }
    }

    @Test public void offlineAndProviderFailureFixturesRemainHonest() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);preferences.setCity("Тестовый город",55.75,37.62);preferences.setWeatherEnabled(true);
        WeatherRepository.Cache noCache=new WeatherRepository.Cache(){public WeatherRepository.Weather read(String k){return null;}public void write(String k,WeatherRepository.Weather w){}};
        WeatherRepository fixture=new WeatherRepository(url->{throw new IOException("offline fixture");},noCache,new WeatherRepository.JsonDecoder(),System::currentTimeMillis);
        WeatherRepository.WeatherResult unavailable=fixture.loadCurrent(preferences.read(),true);
        assertEquals(WeatherRepository.Status.UNAVAILABLE,unavailable.status);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();
            scene.onActivity(a->{
                try {
                    Field generation=MainActivity.class.getDeclaredField("loadGeneration");generation.setAccessible(true);generation.setInt(a,generation.getInt(a)+1);
                    Field weatherField=MainActivity.class.getDeclaredField("weather");weatherField.setAccessible(true);weatherField.set(a,unavailable);
                    Field watch=MainActivity.class.getDeclaredField("watchMessage");watch.setAccessible(true);watch.set(a,"ТЕСТ: доступ к Watch Next запрещён");
                    Field status=MainActivity.class.getDeclaredField("watchStatus");status.setAccessible(true);status.set(a,WatchNextRepository.Status.DENIED);
                    Field entries=MainActivity.class.getDeclaredField("continuation");entries.setAccessible(true);entries.set(a,Collections.emptyList());
                    Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
                } catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
            settle();assertSingleFocus(scene);screenshot("test-fixture-offline-provider-denied");
            openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-weather");
            screenshot("test-fixture-offline-settings");assertSingleFocus(scene);
        }
    }

    @Test public void explicitlyLabeledFixturesRenderWatchCardsAndHourlyWeatherInTheMainLayout() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);
        preferences.setCity("ТЕСТОВЫЕ ДАННЫЕ",55.75,37.62);preferences.setWeatherEnabled(true);preferences.setUnits("celsius");
        List<String> ids=new ArrayList<>();for(AppEntry app:AppCatalog.load(context))if(ids.size()<8)ids.add(app.id);
        preferences.setFavorites(ids);
        long now=System.currentTimeMillis();
        List<WeatherRepository.Hour> hours=new ArrayList<>();
        int[] codes={0,3,61,71,95,45};for(int i=0;i<6;i++)hours.add(new WeatherRepository.Hour(now+(i+1)*3600000L,7-i,codes[i]));
        WeatherRepository.Weather sample=new WeatherRepository.Weather(7,3,false,5,now,now,"celsius",hours,now-10*3600000L,now-2*3600000L,"Europe/Moscow");
        WeatherRepository.Cache cache=new WeatherRepository.Cache(){public WeatherRepository.Weather read(String k){return sample;}public void write(String k,WeatherRepository.Weather w){}};
        WeatherRepository fixture=new WeatherRepository(url->{throw new AssertionError("A fresh fixture must not use the network");},cache,new WeatherRepository.JsonDecoder(),()->now);
        WeatherRepository.WeatherResult forecast=fixture.loadCurrent(preferences.read(),false);
        assertEquals(WeatherRepository.Status.FRESH,forecast.status);
        List<WatchNextRepository.Entry> cards=new ArrayList<>();
        String artwork="android.resource://"+context.getPackageName()+"/";
        cards.add(new WatchNextRepository.Entry("test-alpha","ТЕСТ — Длинное русское название первого эпизода","Тестовая карточка, не история пользователя","io.github.ekkzy.evebox.fixture.alpha","https://example.invalid/test-alpha",artwork+R.drawable.background_mist_forest,400,1000,now,true,true));
        cards.add(new WatchNextRepository.Entry("test-beta","ТЕСТ — Вторая карточка","Только instrumentation fixture","io.github.ekkzy.evebox.fixture.beta","https://example.invalid/test-beta",artwork+R.drawable.background_moon_coast,700,1000,now,true,true));
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();SystemClock.sleep(400);
            for (String theme : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE,LauncherPrefs.CARD_FORMAT_POSTER}) {
                preferences.setCardFormat(theme);preferences.setBackground(WallpaperCatalog.DEFAULT_ID);
                scene.onActivity(a->{try {
                    Field generation=MainActivity.class.getDeclaredField("loadGeneration");generation.setAccessible(true);generation.setInt(a,generation.getInt(a)+1);
                    Field prefsField=MainActivity.class.getDeclaredField("prefs");prefsField.setAccessible(true);prefsField.set(a,preferences.read());
                    Field w=MainActivity.class.getDeclaredField("weather");w.setAccessible(true);w.set(a,forecast);
                    Field entries=MainActivity.class.getDeclaredField("continuation");entries.setAccessible(true);entries.set(a,cards);
                    Field status=MainActivity.class.getDeclaredField("watchStatus");status.setAccessible(true);status.set(a,WatchNextRepository.Status.ITEMS);
                    Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}});
                settle();focus(scene,"watch-test-alpha");awaitPoster(scene,"watch-test-alpha");assertSingleFocus(scene);screenshot("test-fixture-full-theme-"+theme);
                if(LauncherPrefs.CARD_FORMAT_LANDSCAPE.equals(theme)&&"true".equals(InstrumentationRegistry.getArguments().getString("eveBoxMaterialReview"))){
                    // Same labelled weather over real, detailed wallpaper; compare the
                    // live panel with the unobstructed underlay, not a generated mockup.
                    for(int background:new int[]{0,5,10})for(boolean show:new boolean[]{true,false}){
                        preferences.setBackground(background);preferences.setWeatherEnabled(show);
                        scene.onActivity(a->{try{
                            Field p=MainActivity.class.getDeclaredField("prefs");p.setAccessible(true);p.set(a,preferences.read());
                            Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
                        }catch(ReflectiveOperationException e){throw new AssertionError(e);}});
                        settle();SystemClock.sleep(350);screenshot("material-weather-background-"+background+(show?"-glass":"-underlay"));
                    }
                    preferences.setWeatherEnabled(true);
                }
            }
            openDrawer(scene);
            if(context.getResources().getConfiguration().fontScale>=1.2f){
                for(int i=0;i<6;i++){device.pressDPadDown();settle();}
                assertFocus(scene,"shortcut-weather");
                scene.onActivity(a->{View forecastView=a.getWindow().getDecorView().findViewWithTag("weather-forecast");assertNotNull(forecastView);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(forecastView.getGlobalVisibleRect(visible));android.graphics.Matrix matrix=new android.graphics.Matrix();forecastView.transformMatrixToGlobal(matrix);android.graphics.RectF expected=new android.graphics.RectF(0,0,forecastView.getWidth(),forecastView.getHeight());matrix.mapRect(expected);assertTrue("The full hourly forecast must be visible: "+visible+" / "+expected,visible.height()>=expected.height()-4);});
            }
            screenshot("test-fixture-six-hour-weather");
        }
    }

    @Test public void wallpaperGalleryPaletteAndDimPersistAcrossRecreation() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);
        preferences.setWallpaperDim(0);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openAppearance(scene);activate(scene,"appearance-background");
            for(WallpaperCatalog.Entry entry:WallpaperCatalog.selectable()) {activate(scene,"background-"+entry.id);assertEquals(entry.id,preferences.read().background);}
            screenshot("polish-wallpaper-gallery");scene.recreate();settle();assertFocus(scene,"background-"+WallpaperCatalog.MAX_ID);
            device.pressBack();settle();assertFocus(scene,"appearance-background");
            activate(scene,"appearance-palette");assertEquals("light",preferences.read().paletteMode);screenshot("polish-light-settings");
            activate(scene,"appearance-palette");assertEquals("dark",preferences.read().paletteMode);screenshot("polish-dark-settings");
            activate(scene,"appearance-dim");assertEquals(0,preferences.read().wallpaperDim);
            scene.onActivity(a->assertEquals("WALLPAPER_DIM",a.currentScreenForTest()));
            activate(scene,"dim-30");assertEquals(30,preferences.read().wallpaperDim);
            scene.recreate();settle();assertEquals("dark",preferences.read().paletteMode);assertEquals(30,preferences.read().wallpaperDim);assertFocus(scene,"dim-30");assertSingleFocus(scene);
        }
    }

    @Test public void hiddenApplicationCanBeRenamedFoundAndRestored() throws Exception {
        AppEntry selected=null;for(AppEntry app:AppCatalog.load(context))if(app.packageName.equals("io.github.ekkzy.evebox.fixture.alpha")){selected=app;break;}
        assertNotNull("Install alpha fixture",selected);final AppEntry app=selected;
        LauncherPrefs preferences=new LauncherPrefs(context);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-advanced");activate(scene,"advanced-manage-apps");
            activate(scene,"app-"+app.id);activate(scene,"app-visibility");assertTrue(preferences.read().hiddenPackages.contains(app.packageName));
            activate(scene,"app-rename");focus(scene,"app-name-input");scene.onActivity(a->((EditText)a.getWindow().getDecorView().findViewWithTag("app-name-input")).setText("Моё кино — ТЕСТ"));
            device.pressDPadDown();settle();assertFocus(scene,"app-name-save");device.pressDPadCenter();settle();assertEquals("Моё кино — ТЕСТ",preferences.read().customLabels.get(app.packageName));
            device.pressBack();settle();activate(scene,"manage-hidden");activate(scene,"app-"+app.id);screenshot("polish-hidden-app-recovery");
            activate(scene,"app-visibility");activate(scene,"app-reset-name");assertFalse(preferences.read().hiddenPackages.contains(app.packageName));assertFalse(preferences.read().customLabels.containsKey(app.packageName));
            assertNotNull(context.getPackageManager().getPackageInfo(app.packageName,0));assertSingleFocus(scene);
        }
    }

    @Test public void validatedBackupRequiresApplyAndInvalidPreviewHasNoApply() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);preferences.setBackground(10);preferences.setPaletteMode("light");
        String exported=SettingsBackup.exportJson(preferences.read());preferences.setBackground(0);preferences.setPaletteMode("auto");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-advanced");activate(scene,"advanced-backup");
            injectBackupPreview(scene,SettingsBackup.preview(exported));assertEquals(0,preferences.read().background);screenshot("polish-backup-review");
            activate(scene,"backup-apply");assertEquals(10,preferences.read().background);assertEquals("light",preferences.read().paletteMode);
            injectBackupPreview(scene,SettingsBackup.preview("{\"schemaVersion\":999}"));
            scene.onActivity(a->assertNull(a.getWindow().getDecorView().findViewWithTag("backup-apply")));assertEquals(10,preferences.read().background);screenshot("polish-invalid-backup");
            device.pressBack();settle();assertSingleFocus(scene);
        }
    }

    @Test public void removedScreensaverIsAbsentAndWatchNextVisibilityRemainsNavigable() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");
            scene.onActivity(a->assertNull("Removed screensaver has no settings action",a.getWindow().getDecorView().findViewWithTag("settings-screensaver")));
            activate(scene,"settings-advanced");activate(scene,"advanced-watch-next");assertFalse(preferences.read().watchNextVisible);
            scene.recreate();settle();assertFalse(preferences.read().watchNextVisible);assertSingleFocus(scene);
        }
    }

    @Test public void cityInputKeepsCursorKeysAndMovesToSearchResultWithDpad() throws Exception {
        LauncherPrefs preferences=new LauncherPrefs(context);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
            settle();openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-weather");activate(scene,"weather-city");
            scene.onActivity(a->{try{
                EditText input=a.getWindow().getDecorView().findViewWithTag("city-query");input.setText("x");input.setSelection(0);
                Field results=MainActivity.class.getDeclaredField("cityResults");results.setAccessible(true);results.set(a,Collections.singletonList(new WeatherRepository.City("test-city","ТЕСТ — Город","Тестовая страна","",55.75,37.62)));
                Field message=MainActivity.class.getDeclaredField("cityMessage");message.setAccessible(true);message.set(a,"");
                Method update=MainActivity.class.getDeclaredMethod("updateCityResults");update.setAccessible(true);update.invoke(a);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}});settle();focus(scene,"city-query");
            device.pressDPadRight();settle();scene.onActivity(a->assertEquals(1,((EditText)a.getWindow().getDecorView().findViewWithTag("city-query")).getSelectionStart()));
            device.pressDPadLeft();settle();scene.onActivity(a->assertEquals(0,((EditText)a.getWindow().getDecorView().findViewWithTag("city-query")).getSelectionStart()));
            device.pressDPadUp();settle();assertFocus(scene,"page-back");focus(scene,"city-query");
            device.pressDPadDown();settle();assertFocus(scene,"city-result-0");screenshot("final-city-dpad-result");
            device.pressDPadCenter();settle();assertEquals("ТЕСТ — Город",preferences.read().cityName);assertTrue(preferences.read().weatherEnabled);assertFocus(scene,"weather-city");
        }
    }

    private void injectBackupPreview(ActivityScenario<MainActivity> scene,SettingsBackup.Preview preview) {
        scene.onActivity(a->{try{
            Field f=MainActivity.class.getDeclaredField("backupPreview");f.setAccessible(true);f.set(a,preview);
            Field screen=MainActivity.class.getDeclaredField("screen");screen.setAccessible(true);
            Object value=null;for(Object e:screen.getType().getEnumConstants())if(e.toString().equals("BACKUP_PREVIEW"))value=e;
            assertNotNull(value);screen.set(a,value);
            Method render=MainActivity.class.getDeclaredMethod("renderPreservingFocus");render.setAccessible(true);render.invoke(a);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}});settle();assertSingleFocus(scene);
    }

    private void awaitPoster(ActivityScenario<MainActivity> scene,String tag){
        java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();long end=SystemClock.uptimeMillis()+5000;
        while(!ready.get()&&SystemClock.uptimeMillis()<end){scene.onActivity(a->{ViewGroup card=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(card);View art=card.getChildAt(0);ready.set(art instanceof android.widget.ImageView&&((android.widget.ImageView)art).getDrawable() instanceof android.graphics.drawable.BitmapDrawable);});if(!ready.get())SystemClock.sleep(80);}
        assertTrue("Actual poster bitmap must load before visual evidence",ready.get());
    }

    private void openAppearance(ActivityScenario<MainActivity> scene) {
        openDrawer(scene);activate(scene,"shortcut-launcher");activate(scene,"settings-appearance");
    }
    private void openDrawer(ActivityScenario<MainActivity> scene) {
        for(int attempt=0;attempt<12;attempt++) {
            AtomicReference<Boolean> exists=new AtomicReference<>(false);
            scene.onActivity(a -> exists.set(a.getWindow().getDecorView().findViewWithTag("shortcut-apps") != null));
            if(exists.get())return;
            device.pressDPadDown();settle();
        }
        fail("D-pad Down never reached the six-shortcut panel");
    }
    private void focus(ActivityScenario<MainActivity> scene,String tag) {
        scene.onActivity(a -> {View v=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,v);assertTrue(tag,v.requestFocus());});
        settle();assertFocus(scene,tag);
    }
    private void activate(ActivityScenario<MainActivity> scene,String tag) {focus(scene,tag);device.pressDPadCenter();settle();assertSingleFocus(scene);}
    private void assertFocus(ActivityScenario<MainActivity> scene,String tag) {
        scene.onActivity(a -> {assertNotNull(a.getCurrentFocus());assertEquals(tag,a.getCurrentFocus().getTag());});assertSingleFocus(scene);
    }
    private void assertSingleFocus(ActivityScenario<MainActivity> scene) {
        scene.onActivity(a -> {
            View focused=a.getCurrentFocus();assertNotNull("A remote focus must remain visible",focused);
            assertTrue("Focused target must be shown",focused.isShown());
            Rect r=new Rect();assertTrue("Focused target must be within the visible viewport",focused.getGlobalVisibleRect(r));
            assertTrue(r.width()>0&&r.height()>0);assertEquals(1,countFocused(a.getWindow().getDecorView()));
            RectF bounds=globalBounds(focused),window=globalBounds(focused.getRootView());
            assertTrue("Complete focused outline must remain inside Home/window: "+bounds+" outside "+window,
                    bounds.left>=window.left-2&&bounds.top>=window.top-2&&bounds.right<=window.right+2&&bounds.bottom<=window.bottom+2);
        });
    }
    private static RectF globalBounds(View view){Matrix matrix=new Matrix();view.transformMatrixToGlobal(matrix);RectF bounds=new RectF(0,0,view.getWidth(),view.getHeight());matrix.mapRect(bounds);return bounds;}
    private static RectF tagBounds(ActivityScenario<MainActivity> scene,String tag){AtomicReference<RectF> result=new AtomicReference<>();scene.onActivity(a->{View view=a.getWindow().getDecorView().findViewWithTag(tag);assertNotNull(tag,view);result.set(globalBounds(view));});return result.get();}
    private int countFocused(View view) {
        int count=view.isFocused()?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countFocused(((ViewGroup)view).getChildAt(i));
        return count;
    }
    private void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(170);}
    private void screenshot(String name) {
        String directory=InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun","screenshots");assertTrue(directory.matches("[A-Za-z0-9_-]+"));
        File folder=new File(context.getExternalFilesDir(null),directory);assertTrue(folder.isDirectory()||folder.mkdirs());
        assertTrue("screenshot "+name,device.takeScreenshot(new File(folder,name+".png")));
    }
}
