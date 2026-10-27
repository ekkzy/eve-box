package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import io.github.ekkzy.softlumi.ui.CompactWeatherView;
import io.github.ekkzy.softlumi.ui.ThreeDayForecastView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Owned 4K emulator: retired-theme migration and explicit remote card-format/dimming choices. */
@RunWith(AndroidJUnit4.class)
public final class SingleThemeAndDimTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original, legacy;
    private List<String> favorites;

    @Before public void prepareLegacySelection() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT); assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE); original = snapshot(storage);
        assertTrue(storage.edit().clear().commit());
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setBackground(27); prefs.setLanguage("en"); prefs.setWallpaperDim(15); prefs.setPaletteMode("dark");
        prefs.setWeatherEnabled(false); prefs.setWatchNextVisible(false); prefs.setReducedMotion(false); prefs.setSounds(false);
        favorites = new ArrayList<>();
        for (AppEntry app : AppCatalog.load(context)) if (favorites.size() < 8) favorites.add(app.id);
        assertFalse("Actual installed favorites are required", favorites.isEmpty()); prefs.setFavorites(favorites);
        assertTrue(storage.edit().putInt("theme", 4).putInt("schema_version", 3).commit()); legacy = snapshot(storage);
    }
    @After public void restoreOriginalDebugPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) { @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value; editor.putStringSet(key, new HashSet<>(strings)); }
            else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit()); assertEquals(original, snapshot(storage));
    }

    @Test public void legacyThemeFourMigratesWithoutChangingWallpaperOrFavoritesAndDimRequiresAnExplicitChoice() {
        assertEquals("The fixture really starts with a persisted retired theme", 4, storage.getInt("theme", -1));
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            await(scene, "Main composition settles after legacy migration", a -> settled(a) && focused(a, "favorite-0"));
            LauncherPrefs prefs = new LauncherPrefs(context);
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, prefs.read().cardFormat);
            assertFalse(storage.contains("theme"));
            Map<String, Object> expected = new HashMap<>(legacy); expected.remove("theme"); expected.put("card_format", LauncherPrefs.CARD_FORMAT_LANDSCAPE);
            expected.put("schema_version", LauncherPrefs.SCHEMA_VERSION);
            assertEquals("Migration updates the schema and retired composition while preserving existing choices", expected, snapshot(storage));
            assertEquals(27, prefs.read().background); assertEquals(favorites, prefs.read().favoriteIds);
            scene.onActivity(a -> { assertEquals(3840, a.getWindow().getDecorView().getWidth()); assertEquals(2160, a.getWindow().getDecorView().getHeight()); });

            assertTrue(device.pressDPadDown()); await(scene, "Remote enters the full lower page", a -> settled(a) && focused(a, "shortcut-launcher"));
            scene.onActivity(a -> assertEquals(scroll(a).getHeight(), scroll(a).getScrollY()));
            assertTrue(device.pressDPadCenter()); await(scene, "Open settings", a -> settled(a) && "SETTINGS".equals(a.currentScreenForTest()) && focused(a, "settings-appearance"));
            assertTrue(device.pressDPadCenter()); await(scene, "Open appearance", a -> settled(a) && "APPEARANCE".equals(a.currentScreenForTest()));
            appearanceRow(scene, "appearance-background");
            scene.onActivity(a -> {
                for (int retired = 1; retired <= 4; retired++)
                    assertNull("Retired theme controls are absent", a.getWindow().getDecorView().findViewWithTag("theme-" + retired));
                assertNotNull(tag(a, "appearance-cards"));
                assertNotNull(tag(a, "appearance-background")); assertNotNull(tag(a, "appearance-palette"));
                assertNotNull(tag(a, "appearance-dim")); assertNotNull(tag(a, "appearance-motion"));
            });
            assertTrue(device.pressDPadDown()); await(scene, "Palette row is reachable", a -> settled(a) && focused(a, "appearance-palette"));
            assertTrue(device.pressDPadDown()); await(scene, "Dim row is reachable", a -> settled(a) && focused(a, "appearance-dim"));
            assertTrue(device.pressDPadCenter()); await(scene, "Dim action opens its choice page", a -> settled(a) && "WALLPAPER_DIM".equals(a.currentScreenForTest()) && focused(a, "dim-15"));
            assertEquals("Opening the chooser does not increment the value", 15, prefs.read().wallpaperDim);
            for (int value = 20; value <= 30; value += 5) {
                final String wanted = "dim-" + value;
                assertTrue(device.pressDPadRight()); await(scene, "Remote reaches " + wanted, a -> settled(a) && focused(a, wanted));
                assertEquals("Merely moving focus does not apply dimming", 15, prefs.read().wallpaperDim);
            }
            assertTrue(device.pressDPadCenter()); await(scene, "Selected dim level is applied", a -> settled(a) && focused(a, "dim-30") && tag(a, "dim-30").isSelected());
            assertEquals(30, prefs.read().wallpaperDim); assertEquals(27, prefs.read().background); assertEquals(favorites, prefs.read().favoriteIds);
            scene.recreate(); await(scene, "Recreation restores the applied choice", a -> settled(a) && "WALLPAPER_DIM".equals(a.currentScreenForTest()) && focused(a, "dim-30"));
            assertEquals(30, prefs.read().wallpaperDim); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, prefs.read().cardFormat);
            assertTrue(device.pressBack()); await(scene, "Back restores dim action", a -> settled(a) && "APPEARANCE".equals(a.currentScreenForTest()) && focused(a, "appearance-dim"));
            assertEquals(favorites, prefs.read().favoriteIds); assertEquals(27, prefs.read().background);
        }
    }

    @Test public void remoteCardFormatChoicePersistsAcrossRecreationAndCancelKeepsAllOtherSettings() {
        LauncherPrefs prefs = new LauncherPrefs(context);
        List<AppEntry> apps = new ArrayList<>(); favorites = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AppEntry app = new AppEntry(new ComponentName(context.getPackageName(), context.getPackageName() + ".test.ThemeFavorite" + i), "TEST Theme favorite " + (i + 1));
            apps.add(app); favorites.add(app.id);
        }
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); prefs.setFavorites(favorites); prefs.setBackground(18);
        prefs.setReducedMotion(true); prefs.setUnits("celsius");
        prefs.setCity("TEST Theme City", 55.75, 37.62, "524901", "Europe/Moscow");
        // Both real weather components bind their disabled state; no network is needed.
        prefs.setWeatherEnabled(false);
        prefs.setRecommendations(false, Collections.singletonList("io.github.ekkzy.softlumi.test.alpha"),
                Collections.singletonList("io.github.ekkzy.softlumi.test.alpha"));
        Map<String, Object> unchanged = snapshot(storage); unchanged.remove("card_format");
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            injectCatalog(scene, apps);
            assertHome(scene, "home-unified"); assertOtherSettings(unchanged);
            awaitFavoriteArtwork(scene);
            capture(scene, "themes-classic-eight-fixture");
            openThemeChooserFromHome(scene);
            assertChooser(scene, "cards-landscape");
            capture(scene, "themes-picker");
            assertTrue(device.pressDPadDown()); await(scene, "Remote focuses Talkies without applying it", a -> settled(a) && focused(a, "cards-poster"));
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, prefs.read().cardFormat);
            assertTrue(device.pressDPadCenter()); awaitAppearanceTheme(scene, "Posters");
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, prefs.read().cardFormat); assertOtherSettings(unchanged);
            returnHome(scene, "home-unified");
            awaitFavoriteArtwork(scene);
            capture(scene, "themes-talkies-eight-fixture");

            scene.recreate();
            await(scene, "Recreated Activity finishes loading its installed catalog", SingleThemeAndDimTest::dataReady);
            assertEquals("The chosen theme survives a new Activity", LauncherPrefs.CARD_FORMAT_POSTER, prefs.read().cardFormat);
            assertOtherSettings(unchanged); // Check persistence before reinjecting the presentation-only catalog.
            injectCatalog(scene, apps); assertHome(scene, "home-unified");
            openThemeChooserFromHome(scene); assertChooser(scene, "cards-poster");
            assertTrue(device.pressDPadUp()); await(scene, "Focus moves to Classic without changing the selected theme", a -> settled(a) && focused(a, "cards-landscape"));
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, prefs.read().cardFormat);
            assertTrue(device.pressBack()); awaitAppearanceTheme(scene, "Posters");
            assertEquals("Back from the chooser cancels focused-only changes", LauncherPrefs.CARD_FORMAT_POSTER, prefs.read().cardFormat); assertOtherSettings(unchanged);

            assertTrue(device.pressDPadCenter()); await(scene, "Reopen the chooser", a -> settled(a) && "CARD_FORMAT".equals(a.currentScreenForTest()));
            assertChooser(scene, "cards-poster");
            assertTrue(device.pressDPadUp()); await(scene, "Classic is reachable again", a -> settled(a) && focused(a, "cards-landscape"));
            assertTrue(device.pressDPadCenter()); awaitAppearanceTheme(scene, "Horizontal");
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, prefs.read().cardFormat);
            returnHome(scene, "home-unified"); assertOtherSettings(unchanged);
        }
    }

    private void openThemeChooserFromHome(ActivityScenario<MainActivity> scene) {
        assertTrue(device.pressDPadDown()); await(scene, "Down reaches the launcher settings shortcut", a -> settled(a) && focused(a, "shortcut-launcher"));
        assertTrue(device.pressDPadCenter()); await(scene, "Open launcher settings", a -> settled(a) && "SETTINGS".equals(a.currentScreenForTest()) && focused(a, "settings-appearance"));
        assertTrue(device.pressDPadCenter()); await(scene, "Open appearance", a -> settled(a) && "APPEARANCE".equals(a.currentScreenForTest()));
        appearanceRow(scene, "appearance-cards");
        assertTrue(device.pressDPadCenter()); await(scene, "Open explicit theme chooser", a -> settled(a) && "CARD_FORMAT".equals(a.currentScreenForTest()));
    }
    private void appearanceRow(ActivityScenario<MainActivity> scene, String wanted) {
        List<String> order = Arrays.asList("appearance-cards", "appearance-background", "appearance-palette", "appearance-dim", "appearance-motion");
        for (int attempts = 0; attempts < 7; attempts++) {
            AtomicReference<String> current = new AtomicReference<>();
            scene.onActivity(a -> current.set(a.getCurrentFocus() == null ? "" : String.valueOf(a.getCurrentFocus().getTag())));
            if (wanted.equals(current.get())) return;
            int index = order.indexOf(current.get()), destination = order.indexOf(wanted);
            String next;
            if ("page-back".equals(current.get())) { assertTrue(device.pressDPadDown()); next = order.get(0); }
            else {
                assertTrue("Known appearance row must own focus: " + current.get(), index >= 0);
                int step = destination > index ? 1 : -1;
                assertTrue(step > 0 ? device.pressDPadDown() : device.pressDPadUp()); next = order.get(index + step);
            }
            await(scene, "Remote reaches " + next, a -> settled(a) && focused(a, next));
        }
        fail("Could not reach " + wanted);
    }
    private void awaitAppearanceTheme(ActivityScenario<MainActivity> scene, String label) {
        await(scene, "Appearance shows the applied " + label + " theme", a -> settled(a) && "APPEARANCE".equals(a.currentScreenForTest())
                && focused(a, "appearance-cards") && containsText(tag(a, "appearance-cards"), label));
    }
    private void returnHome(ActivityScenario<MainActivity> scene, String homeTag) {
        assertTrue(device.pressBack()); await(scene, "Back returns to settings", a -> settled(a) && "SETTINGS".equals(a.currentScreenForTest()));
        assertTrue(device.pressBack()); await(scene, "Back returns to the same Home settings shortcut", a -> settled(a) && "HOME".equals(a.currentScreenForTest())
                && a.getCurrentFocus() != null && String.valueOf(a.getCurrentFocus().getTag()).startsWith("shortcut-"));
        assertTrue(device.pressDPadUp()); await(scene, "Up returns to the favorite dock", a -> settled(a) && a.getCurrentFocus() != null
                && String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-"));
        assertHome(scene, homeTag);
    }
    private static void assertChooser(ActivityScenario<MainActivity> scene, String selected) {
        scene.onActivity(a -> {
            assertEquals("CARD_FORMAT", a.currentScreenForTest());
            assertTrue(tag(a, selected).isSelected());
            assertEquals("Exactly horizontal cards and posters are offered", 2, countPrefix((View) get(a, "root"), "cards-"));
            for (int retired = 1; retired <= 4; retired++) assertNull(a.getWindow().getDecorView().findViewWithTag("theme-" + retired));
            assertNotNull(tag(a, "cards-landscape")); assertNotNull(tag(a, "cards-poster"));
            assertEquals("Opening the chooser restores the selected theme's focus", selected, a.getCurrentFocus().getTag());
        });
    }
    private void assertHome(ActivityScenario<MainActivity> scene, String expected) {
        scene.onActivity(a -> {
            assertEquals(3840, a.getWindow().getDecorView().getWidth()); assertEquals(2160, a.getWindow().getDecorView().getHeight());
            assertEquals(1f, a.getResources().getConfiguration().fontScale, .001f);
            assertEquals("HOME", a.currentScreenForTest());
            View activeRoot = (View) get(a, "root");
            assertNotNull("The active tree uses the chosen composition", activeRoot.findViewWithTag(expected));
            assertNull("Classic composition is removed", activeRoot.findViewWithTag("home-classic"));
            assertNull("Talkies composition is removed", activeRoot.findViewWithTag("home-talkies"));
            for (int i = 0; i < 8; i++) assertNotNull(tag(a, "favorite-" + i));
            assertNull(a.getWindow().getDecorView().findViewWithTag("favorite-8"));
            assertTrue(tag(a, "weather-status") instanceof CompactWeatherView);
            assertTrue(tag(a, "three-day-forecast") instanceof ThreeDayForecastView);
        });
        assertEquals(favorites, new LauncherPrefs(context).read().favoriteIds);
    }
    private void assertOtherSettings(Map<String, Object> expected) {
        Map<String, Object> actual = snapshot(storage); actual.remove("card_format");
        assertEquals("Theme choice preserves favorites, weather, wallpaper and source preferences", expected, actual);
    }
    private void injectCatalog(ActivityScenario<MainActivity> scene, List<AppEntry> apps) {
        await(scene, "Initial real catalog callbacks complete before synthetic presentation", SingleThemeAndDimTest::dataReady);
        scene.onActivity(a -> {
            set(a, "catalog", Collections.unmodifiableList(new ArrayList<>(apps)));
            @SuppressWarnings("unchecked") Map<String, AppEntry> index = (Map<String, AppEntry>) get(a, "appsById");
            index.clear(); for (AppEntry app : apps) index.put(app.id, app);
            set(a, "prefs", new LauncherPrefs(context).read()); invoke(a, "renderPreservingFocus");
        });
        await(scene, "Eight synthetic favorites receive native layout", a -> settled(a) && a.getCurrentFocus() != null
                && String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-")
                && a.getWindow().getDecorView().findViewWithTag("favorite-7") != null);
    }
    private static boolean dataReady(MainActivity a) { Future<?> load = (Future<?>) get(a, "loadFuture");
        return settled(a) && load != null && load.isDone() && (Boolean) get(a, "catalogLoaded"); }
    private void awaitFavoriteArtwork(ActivityScenario<MainActivity> scene) {
        await(scene, "All eight visible synthetic favorites have decoded native artwork", a -> {
            if (!settled(a)) return false;
            for (int i = 0; i < 8; i++) {
                View tile = a.getWindow().getDecorView().findViewWithTag("favorite-" + i);
                ImageView icon = tile == null ? null : firstImage(tile);
                if (icon == null || !(icon.getDrawable() instanceof BitmapDrawable)) return false;
            }
            return true;
        });
    }
    private static ImageView firstImage(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ImageView result = firstImage(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private void capture(ActivityScenario<MainActivity> scene, String name) {
        CountDownLatch committed = new CountDownLatch(1);
        scene.onActivity(a -> {
            assertEquals("Capture the actual 4K UI", 3840, a.getWindow().getDecorView().getWidth());
            assertEquals(2160, a.getWindow().getDecorView().getHeight());
            View root = (View) get(a, "root"); assertTrue("Native frame composition is required", root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
        });
        try { assertTrue("Native frame reaches the renderer before capture", committed.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue(device.takeScreenshot(new File(folder, name + ".png")));
    }
    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (containsText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private static int countPrefix(View view, String prefix) {
        int count = String.valueOf(view.getTag()).startsWith(prefix) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countPrefix(((ViewGroup) view).getChildAt(i), prefix);
        return count;
    }
    private static void set(MainActivity a, String name, Object value) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(a, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity a, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(a); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }

    private static boolean settled(MainActivity a) {
        View root = (View) get(a, "root");
        if (root == null || !root.isLaidOut() || root.isLayoutRequested() || root.getVisibility() != View.VISIBLE || root.getAlpha() < .999f
                || !a.hasWindowFocus() || (Boolean) get(a, "renderDirty") || ((UiMotion) get(a, "uiMotion")).isRunning()) return false;
        TvScrollView scroll = scroll(a); return scroll == null || !scroll.isScrollAnimating();
    }
    private static boolean focused(MainActivity a, String key) { return a.getCurrentFocus() != null && key.equals(a.getCurrentFocus().getTag()); }
    private static View tag(MainActivity a, String key) { View view = a.getWindow().getDecorView().findViewWithTag(key); assertNotNull(key, view); return view; }
    private static TvScrollView scroll(MainActivity a) { return (TvScrollView) get(a, "homeScroll"); }
    private static Object get(MainActivity a, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(a); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void await(ActivityScenario<MainActivity> scene, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8000; AtomicBoolean done = new AtomicBoolean();
        do {
            scene.onActivity(a -> done.set(condition.test(a)));
            if (done.get()) {
                scene.onActivity(a -> { assertNotNull(a.getCurrentFocus()); assertTrue(a.getCurrentFocus().isShown()); assertEquals(1, focusedCount(a.getWindow().getDecorView())); });
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> state = new AtomicReference<>();
        scene.onActivity(a -> state.set("screen=" + a.currentScreenForTest() + ",focus=" + (a.getCurrentFocus() == null ? "null" : a.getCurrentFocus().getTag())));
        fail(message + "; " + state.get());
    }
    private static int focusedCount(View view) {
        int count = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += focusedCount(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
            values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
    }
}
