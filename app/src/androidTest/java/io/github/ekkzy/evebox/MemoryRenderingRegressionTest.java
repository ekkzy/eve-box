package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.RecommendationsSession;
import io.github.ekkzy.evebox.ui.AppArtworkCatalog;
import io.github.ekkzy.evebox.ui.SharedRowsSheet;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiImageLoader;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native ownership/navigation checks. Bitmap observations are not a resident-RAM measurement. */
@RunWith(AndroidJUnit4.class)
public final class MemoryRenderingRegressionTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private LauncherPrefs preferences;
    private final List<AppEntry> favorites = new ArrayList<>();

    @Before public void isolateOwned4kFixture() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug", context.getPackageName());
        assertTrue("Owned emulator only", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertEquals(34, Build.VERSION.SDK_INT);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue("Exercise native motion", ValueAnimator.areAnimatorsEnabled()));
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setLanguage("ru"); preferences.setSounds(false); preferences.setReducedMotion(false);
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false); preferences.setRecommendationsEnabled(false);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        preferences.setBackground(29); preferences.setWallpaperPeriod("night"); preferences.setPaletteMode("dark");
        preferences.setWallpaperDim(15); preferences.setFavorites(Collections.emptyList());
        for (String pkg : new String[] {"ru.kinopoisk.tv", "com.vk.tv", "ru.rutube.app", "com.netflix.ninja",
                "com.plexapp.android", "com.spotify.tv.android", "org.xbmc.kodi", "org.videolan.vlc"}) {
            AppArtworkCatalog.Entry artwork = AppArtworkCatalog.find(pkg);
            assertNotNull(pkg, artwork); assertTrue(artwork.hasCuratedArtwork());
            favorites.add(new AppEntry(new ComponentName(pkg, pkg + ".MemoryFixture"), artwork.title, "memory-fixture"));
        }
    }

    @After public void restoreExactPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, Object> item : original.entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(strings));
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit()); assertEquals("No fixture preference may survive", original, snapshot(storage));
    }

    @Test public void largeCatalogUnbindsDistantIconsAndBackReusesTheNearbyCache() throws Exception {
        List<AppEntry> apps = new ArrayList<>(favorites);
        // Enough real rows to leave a distant half beyond the viewport buffer.
        // Each identity owns an ordinary renderer result; no image-loader test hook.
        for (int i = apps.size(); i < 96; i++) {
            String pkg = "io.github.evebox.memory.fixture" + i;
            apps.add(new AppEntry(new ComponentName(pkg, pkg + ".Main"), "Приложение " + i, "memory-fixture"));
        }
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial local catalog", MemoryRenderingRegressionTest::stable);
            installCatalog(scene, apps);
            scene.onActivity(current -> push(current, "MANAGE_APPS"));
            await(activity, "Managed catalog is decoded", current -> ready(current) && "MANAGE_APPS".equals(current.currentScreenForTest()));
            scene.onActivity(current -> assertTrue(target(current, "app-" + apps.get(0).id).requestFocus()));
            await(activity, "First grid icon is focused", current -> ready(current) && focused(current, "app-" + apps.get(0).id));
            AtomicReference<Integer> columns = new AtomicReference<>();
            scene.onActivity(current -> columns.set((Integer) get(current, "catalogColumns")));
            int last = 0;
            for (int index = columns.get(); index < apps.size(); index += columns.get()) {
                final String key = "app-" + apps.get(index).id;
                assertTrue("Real Down reaches another grid row", device.pressDPadDown());
                await(activity, "Native row focus " + index, current -> ready(current) && focused(current, key));
                last = index;
            }
            final String lastKey = "app-" + apps.get(last).id;
            scene.onActivity(current -> {
                assertEquals(apps.size(), grid(current).size());
                assertTrue("The native scroller really moved", gridScroll(current).getScrollY() > gridScroll(current).getHeight());
                int bound = 0;
                for (int i = 0; i < apps.size(); i++) {
                    View card = target(current, "app-" + apps.get(i).id); ImageView image = firstImage(card);
                    assertNotNull(image); assertEquals("Far cards remain D-pad participants", View.VISIBLE, card.getVisibility());
                    assertTrue("Far cards retain their focus contract", card.isFocusable());
                    if (bitmap(image) != null) bound++;
                    if (i < apps.size() / 2) assertNull("The actually distant half releases its pixels: " + i, image.getDrawable());
                }
                assertTrue("Some current artwork must be decoded", bound > 0);
                assertTrue("The full visited catalog must not remain bound", bound <= apps.size() / 2);
                assertTrue("The focused final icon stays usable", bitmap(firstImage(target(current, lastKey))) != null);
            });
            AtomicReference<Integer> hits = new AtomicReference<>();
            scene.onActivity(current -> hits.set(loader(current).diagnostics().cacheHits));
            assertTrue("Real Center opens a local app-options page, never the synthetic app", device.pressDPadCenter());
            await(activity, "App options settle", current -> ready(current) && "APP_OPTIONS".equals(current.currentScreenForTest()));
            assertTrue("Real Back returns to the same nearby catalog position", device.pressBack());
            await(activity, "Back restores the last native grid focus", current -> ready(current)
                    && "MANAGE_APPS".equals(current.currentScreenForTest()) && focused(current, lastKey));
            scene.onActivity(current -> {
                assertTrue("Returning visible icons reuse the retained bitmap cache", loader(current).diagnostics().cacheHits > hits.get());
                assertNotNull(bitmap(firstImage(target(current, lastKey))));
                assertTrue("Back preserves actual grid scrolling", gridScroll(current).getScrollY() > gridScroll(current).getHeight());
                assertNoPageLayers((View) get(current, "renderHost"));
            });
        }
    }

    @Test public void dayNightSettingsBackPreservesVisibleHomeArtworkAndReleasesPageLayers() throws Exception {
        for (boolean night : new boolean[] {false, true}) {
            preferences.setWallpaperPeriod(night ? "night" : "day"); preferences.setPaletteMode(night ? "dark" : "light");
            String label = night ? "night" : "day";
            try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
                MainActivity activity = owner(scene); await(activity, "Initial local Home", MemoryRenderingRegressionTest::stable);
                installCatalog(scene, favorites);
                await(activity, label + " Home is decoded", MemoryRenderingRegressionTest::ready);
                final String first = "favorite:" + favorites.get(0).id;
                scene.onActivity(current -> assertTrue(target(current, first).requestFocus()));
                await(activity, "Favorite is focused", current -> ready(current) && focused(current, first));
                assertTrue(device.pressDPadRight()); assertTrue(device.pressDPadLeft());
                assertTrue(device.pressDPadRight()); assertTrue(device.pressDPadLeft());
                await(activity, "Rapid focus retargeting settles", current -> ready(current) && focused(current, first));
                AtomicReference<View> home = new AtomicReference<>();
                AtomicReference<Map<ImageView, Bitmap>> artwork = new AtomicReference<>();
                scene.onActivity(current -> {
                    assertEquals(night, (Boolean) get(current, "renderedNight"));
                    home.set((View) get(current, "root")); artwork.set(boundIcons(current));
                    assertEquals("All eight visible brand identities are decoded", favorites.size(), artwork.get().size());
                });
                capture(activity, label + "-home-before");
                assertTrue(device.pressDPadDown());
                await(activity, "Real Down reveals the lower Home", current -> ready(current)
                        && focused(current, "shortcut-launcher") && ((TvScrollView) get(current, "homeScroll")).getScrollY() > 0);
                capture(activity, label + "-home-lower");
                assertTrue(device.pressDPadCenter());
                await(activity, "Real Center opens Settings", current -> ready(current) && "SETTINGS".equals(current.currentScreenForTest()));
                scene.onActivity(current -> {
                    assertNull("A settled page has no full-window transition layer", get(current, "layeredHome"));
                    assertNoPageLayers(home.get()); assertNoPageLayers((View) get(current, "renderHost"));
                    assertNull("The retained Home is detached after its exit", home.get().getParent());
                });
                capture(activity, label + "-settings");
                assertTrue(device.pressBack());
                await(activity, "Settings Back restores lower Home", current -> ready(current)
                        && "HOME".equals(current.currentScreenForTest()) && focused(current, "shortcut-launcher"));
                assertTrue(device.pressBack());
                await(activity, "Home Back restores the upper favorite", current -> ready(current) && focused(current, first)
                        && ((TvScrollView) get(current, "homeScroll")).getScrollY() == 0);
                scene.onActivity(current -> {
                    assertSame("Fast Back restores the existing Home tree", home.get(), get(current, "root"));
                    assertEquals("No extra hidden page remains attached", 1, ((ViewGroup) get(current, "renderHost")).getChildCount());
                    assertNull(get(current, "retainedPage")); assertNull(get(current, "layeredHome"));
                    for (Map.Entry<ImageView, Bitmap> item : artwork.get().entrySet())
                        assertSame("Nearby favorites never reload during Settings Back", item.getValue(), bitmap(item.getKey()));
                    assertNoPageLayers(home.get());
                    @SuppressWarnings("unchecked") Map<View, ?> bindings = (Map<View, ?>) get(get(current, "uiMotion"), "bindings");
                    for (View view : bindings.keySet()) assertTrue("Motion must not retain a detached obsolete page", view.isAttachedToWindow());
                });
                capture(activity, label + "-home-return");
            }
        }
    }

    private void installCatalog(ActivityScenario<MainActivity> scene, List<AppEntry> apps) {
        scene.onActivity(current -> {
            Future<?> future = (Future<?>) get(current, "loadFuture"); assertNotNull(future); assertTrue(future.isDone());
            set(current, "loadGeneration", (Integer) get(current, "loadGeneration") + 1);
            set(current, "cityGeneration", (Integer) get(current, "cityGeneration") + 1);
            ((RecommendationsSession) get(current, "recommendationsSession")).close();
            ((ExecutorService) get(current, "repositoryIo")).shutdownNow(); ((ExecutorService) get(current, "cityIo")).shutdownNow();
            Handler main = (Handler) get(current, "main");
            for (String callback : new String[] {"clockTick", "uiRender", "dataRender"}) main.removeCallbacks((Runnable) get(current, callback));
            List<String> ids = new ArrayList<>(); for (AppEntry favorite : favorites) ids.add(favorite.id); preferences.setFavorites(ids);
            set(current, "catalog", Collections.unmodifiableList(new ArrayList<>(apps))); set(current, "catalogLoaded", true);
            @SuppressWarnings("unchecked") Map<String, AppEntry> byId = (Map<String, AppEntry>) get(current, "appsById");
            byId.clear(); for (AppEntry app : apps) byId.put(app.id, app);
            set(current, "requestedFocus", "favorite:" + favorites.get(0).id); invoke(current, "render");
        });
    }

    private void capture(MainActivity activity, String name) throws InterruptedException {
        CountDownLatch committed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(3840, activity.getWindow().getDecorView().getWidth()); assertEquals(2160, activity.getWindow().getDecorView().getHeight());
            View root = (View) get(activity, "root"); assertTrue(root.isHardwareAccelerated());
            @SuppressWarnings("unchecked") List<TextView> clocks = (List<TextView>) get(activity, "liveClock");
            for (TextView clock : clocks) clock.setText("21:44");
            root.setContentDescription("Синтетические данные для проверки памяти; не личные приложения или история");
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
        });
        assertTrue("Native frame committed", committed.await(3, TimeUnit.SECONDS));
        File directory = new File(context.getExternalFilesDir(null), "memory-rendering-run");
        assertTrue(directory.isDirectory() || directory.mkdirs()); assertTrue(device.takeScreenshot(new File(directory, name + ".png")));
    }

    private static boolean stable(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        if (!(Boolean) get(activity, "resumed") || !activity.hasWindowFocus() || root == null || !root.isLaidOut() || root.isLayoutRequested()
                || root.getVisibility() != View.VISIBLE || root.getAlpha() < .999f || !(Boolean) get(activity, "catalogLoaded")
                || load == null || !load.isDone() || (Boolean) get(activity, "renderDirty")
                || ((UiMotion) get(activity, "uiMotion")).isRunning() || ((SharedRowsSheet) get(activity, "rowsSheet")).isAnimating()) return false;
        Object home = get(activity, "homeScroll"); if (home instanceof TvScrollView && ((TvScrollView) home).isScrollAnimating()) return false;
        if (!grid(activity).isEmpty() && gridScroll(activity).isScrollAnimating()) return false;
        View rows = (View) get(activity, "settingsRows");
        return rows == null || !(rows.getParent() instanceof TvScrollView) || !((TvScrollView) rows.getParent()).isScrollAnimating();
    }
    private static boolean ready(MainActivity activity) {
        if (!stable(activity) || activity.getCurrentFocus() == null) return false;
        UiImageLoader.Diagnostics state = loader(activity).diagnostics();
        if (state.jobs != 0 || state.running != 0 || state.queued != 0) return false;
        if (bitmap((ImageView) get(activity, "wallpaperView")) == null) return false;
        Rect viewport = new Rect(), visible = new Rect(); View owner = !grid(activity).isEmpty() ? gridScroll(activity)
                : get(activity, "homeScroll") instanceof View ? (View) get(activity, "homeScroll") : (View) get(activity, "root");
        if (!owner.getGlobalVisibleRect(viewport)) return false;
        for (ImageView image : icons(activity).keySet())
            if (image.isShown() && image.getGlobalVisibleRect(visible) && Rect.intersects(viewport, visible) && bitmap(image) == null) return false;
        return true;
    }
    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long end = SystemClock.uptimeMillis() + 15_000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return; SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < end);
        fail(message + "; native fixture did not settle");
    }
    private static Map<ImageView, Bitmap> boundIcons(MainActivity activity) {
        Map<ImageView, Bitmap> result = new IdentityHashMap<>();
        for (ImageView image : icons(activity).keySet()) { Bitmap bitmap = bitmap(image); if (bitmap != null) result.put(image, bitmap); }
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<ImageView, AppEntry> icons(MainActivity activity) { return (Map<ImageView, AppEntry>) get(activity, "iconBindings"); }
    @SuppressWarnings("unchecked") private static List<View> grid(MainActivity activity) { return (List<View>) get(activity, "catalogGrid"); }
    private static TvScrollView gridScroll(MainActivity activity) {
        for (android.view.ViewParent parent = grid(activity).get(0).getParent(); parent instanceof View; parent = parent.getParent())
            if (parent instanceof TvScrollView) return (TvScrollView) parent;
        throw new AssertionError("Grid has no native scroller");
    }
    private static UiImageLoader loader(MainActivity activity) { return (UiImageLoader) get(activity, "imageLoader"); }
    private static Bitmap bitmap(ImageView view) { return view != null && view.getDrawable() instanceof BitmapDrawable ? ((BitmapDrawable) view.getDrawable()).getBitmap() : null; }
    private static ImageView firstImage(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ImageView image = firstImage(((ViewGroup) view).getChildAt(i)); if (image != null) return image;
        }
        return null;
    }
    private static void assertNoPageLayers(View view) {
        assertEquals("No retained page hardware layer: " + view.getClass().getSimpleName(), View.LAYER_TYPE_NONE, view.getLayerType());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertNoPageLayers(((ViewGroup) view).getChildAt(i));
    }
    private static MainActivity owner(ActivityScenario<MainActivity> scene) { AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set); return owner.get(); }
    private static boolean focused(MainActivity activity, String key) { return target(activity, key) == activity.getCurrentFocus(); }
    @SuppressWarnings("unchecked") private static View target(MainActivity activity, String key) { View view = ((Map<String, View>) get(activity, "targets")).get(key); assertNotNull(key, view); return view; }
    private static void push(MainActivity activity, String name) {
        Object screen = get(activity, "screen");
        for (Object value : screen.getClass().getEnumConstants()) if (name.equals(value.toString())) {
            try { Method method = MainActivity.class.getDeclaredMethod("push", screen.getClass()); method.setAccessible(true); method.invoke(activity, value); return; }
            catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
        }
        throw new AssertionError(name);
    }
    private static Object get(Object owner, String name) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); } }
    private static void set(Object owner, String name, Object value) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); } catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); } }
    private static void invoke(MainActivity activity, String name) { try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); } catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); } }
    private static Map<String, Object> snapshot(SharedPreferences storage) { Map<String, Object> result = new HashMap<>(); for (Map.Entry<String, ?> item : storage.getAll().entrySet()) result.put(item.getKey(), item.getValue() instanceof Set ? new HashSet<>((Set<?>) item.getValue()) : item.getValue()); return result; }
}
