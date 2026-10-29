package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Warm surfaces survive an actual external task; no artificial STARTED lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class HomeSurfaceContinuityTest {
    private static final String FIXTURE_PACKAGE = "io.github.ekkzy.evebox.fixture.alpha";
    private static final ComponentName FIXTURE = new ComponentName(FIXTURE_PACKAGE, "io.github.ekkzy.evebox.fixture.FixtureActivity");
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private int favoriteCount;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("The agreed emulator baseline is API 34", 34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals("Standard font only", 1f, context.getResources().getConfiguration().fontScale, .001f);
        assertTrue("The separate alpha fixture must be installed before instrumentation",
                context.getPackageManager().getActivityInfo(FIXTURE, 0).exported);
        List<AppEntry> apps = AppCatalog.load(context);
        assertTrue("At least two actual installed launchable apps required", apps.size() >= 2);
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : apps) if (favorites.size() < 8) favorites.add(app.id);
        favoriteCount = favorites.size();
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); preferences.setBackground(0); preferences.setPaletteMode("dark");
        preferences.setLanguage("en"); preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false);
        preferences.setSounds(false); preferences.setReducedMotion(false); preferences.setFavorites(favorites);
    }

    @After public void restoreRawPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
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
        assertTrue(editor.commit());
        assertEquals("Restore every original debug preference", original, snapshot(storage));
    }

    @Test public void frameworkHomeReturnKeepsTheSameReadySurfaceOnEveryObservedFrame() {
        ReturnTrace trace = new ReturnTrace();
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(trace);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(homeIntent())) {
            AtomicReference<MainActivity> owner = new AtomicReference<>();
            AtomicReference<SurfaceProbe> probe = new AtomicReference<>();
            scene.onActivity(current -> { owner.set(current); trace.owner = current; trace.task = current.getTaskId(); });
            MainActivity activity = owner.get();
            try {
                await(activity, "Initial Home has real ready artwork", this::ready);
                scene.onActivity(current -> {
                    assertWindow(current);
                    assertTrue(tag(current, "favorite-1").requestFocus());
                });
                await(activity, "Chosen second favorite settles", current -> ready(current) && tag(current, "favorite-1").isFocused());
                scene.onActivity(current -> {
                    SurfaceProbe observer = new SurfaceProbe(current, favoriteCount, trace);
                    probe.set(observer); observer.start(); observer.check();
                    current.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(FIXTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                });
                awaitFixtureForeground();
                await(activity, "Framework really paused Home for the separate fixture", current ->
                        trace.paused && !(Boolean) get(current, "resumed"));
                String marker = "surface-return-" + SystemClock.uptimeMillis();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    trace.returnRequested = true;
                    context.startActivity(homeIntent().putExtra("eveBoxSurfaceReturn", marker));
                });
                await(activity, "Framework resumes the original Home with its warm surface", current -> {
                    probe.get().assertNoFailure();
                    if (trace.returned == null) return false;
                    assertSame("This is app return, not a fresh Activity", current, trace.returned);
                    assertEquals("The established HOME task is reused", trace.task, current.getTaskId());
                    return ready(current) && tag(current, "favorite-1").isFocused();
                });
                scene.onActivity(current -> current.getWindow().getDecorView().postInvalidateOnAnimation());
                await(activity, "Observe several actual return draws without a blank icon frame", current -> {
                    probe.get().assertNoFailure(); return probe.get().returnFrames >= 3;
                });
                scene.onActivity(current -> {
                    SurfaceProbe observer = probe.get(); observer.check(); observer.assertNoFailure();
                    assertEquals(marker, current.getIntent().getStringExtra("eveBoxSurfaceReturn"));
                    assertSame("The second real favorite remains selected", observer.favorites.get(1), current.getCurrentFocus());
                    assertEquals("Exactly one View owns native focus", 1, countFocused(current.getWindow().getDecorView()));
                    assertEquals("No obsolete page root remains after HOME", 1, ((FrameLayout) get(current, "renderHost")).getChildCount());
                    assertWindow(current);
                });
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    if (probe.get() != null) probe.get().stop();
                });
                // Return our own task before ActivityScenario.close; never change
                // the default HOME or leave the external fixture foreground.
                if (FIXTURE_PACKAGE.equals(device.getCurrentPackageName())) {
                    context.startActivity(homeIntent());
                    await(activity, "Cleanup returns the existing debug Home", current -> (Boolean) get(current, "resumed"));
                }
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(trace);
        }
    }

    /** App callback policy on a real warm Activity; does not prove OS trim delivery or induce memory pressure. */
    @Test public void appTrimCallbackPolicyRetainsUiHiddenIconsAndEvictsBackgroundCache() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(homeIntent())) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            await(activity, "All favorite icons are decoded before trimming", current -> ready(current) && imageJobsIdle(current));
            AtomicReference<Map<String, Bitmap>> warm = new AtomicReference<>();
            scene.onActivity(current -> {
                assertWindow(current);
                LruCache<String, Bitmap> cache = cache(current);
                assertTrue("Decoded image cache has a finite budget", cache.maxSize() > 0 && cache.maxSize() <= 12 * 1024 * 1024);
                Map<String, Bitmap> icons = new LinkedHashMap<>();
                for (Map.Entry<String, Bitmap> entry : cache.snapshot().entrySet()) if (entry.getKey().startsWith("icon:")) icons.put(entry.getKey(), entry.getValue());
                assertEquals("Every ready favorite has a warm cache entry", favoriteCount, icons.size());
                warm.set(icons);
            });
            scene.onActivity(current -> {
                // Exercise the app's callback branch on main. A shell command
                // accepting HIDDEN did not reliably deliver it on this emulator.
                current.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                Map<String, Bitmap> retained = cache(current).snapshot();
                for (Map.Entry<String, Bitmap> entry : warm.get().entrySet()) {
                    assertSame("UI_HIDDEN policy keeps the ready icon " + entry.getKey(), entry.getValue(), retained.get(entry.getKey()));
                    assertFalse(entry.getValue().isRecycled());
                }
                assertTrue("UI_HIDDEN keeps all currently bound artwork ready", artworkReady(current));
            });
            scene.onActivity(current -> {
                current.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                assertEquals("The BACKGROUND callback policy evicts decoded cache entries", 0, cache(current).size());
                assertTrue("Cache eviction must not blank currently bound views", artworkReady(current));
                assertEquals(1, countFocused(current.getWindow().getDecorView()));
            });
        }
    }

    private final class SurfaceProbe implements ViewTreeObserver.OnPreDrawListener {
        final MainActivity activity;
        final View root, decor;
        final List<View> favorites = new ArrayList<>();
        final List<ImageView> icons = new ArrayList<>();
        final List<Drawable> drawables = new ArrayList<>();
        final ReturnTrace trace;
        final int generation;
        ViewTreeObserver observer;
        AssertionError failure;
        int returnFrames;
        SurfaceProbe(MainActivity activity, int count, ReturnTrace trace) {
            this.activity = activity; this.trace = trace;
            root = (View) get(activity, "root"); decor = activity.getWindow().getDecorView();
            generation = (Integer) get(activity, "renderGeneration");
            for (int i = 0; i < count; i++) {
                View favorite = tag(activity, "favorite-" + i); ImageView icon = icon(favorite);
                favorites.add(favorite); icons.add(icon); drawables.add(icon.getDrawable());
            }
        }
        void start() { observer = decor.getViewTreeObserver(); observer.addOnPreDrawListener(this); }
        void stop() { if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this); }
        @Override public boolean onPreDraw() {
            if (!trace.returnRequested || !(Boolean) get(activity, "resumed") || !activity.hasWindowFocus()) return true;
            try { check(); } catch (AssertionError error) { if (failure == null) failure = error; }
            returnFrames++;
            if (returnFrames < 3) decor.postInvalidateOnAnimation();
            return true;
        }
        void check() {
            assertSame("Warm HOME must retain its original ready root", root, get(activity, "root"));
            assertEquals("App return cannot hide a rebuild behind cached bitmap delivery", generation, ((Integer) get(activity, "renderGeneration")).intValue());
            assertTrue(root.isAttachedToWindow());
            assertEquals("No screen-entry fade on warm HOME", 1f, root.getAlpha(), .001f);
            for (int i = 0; i < favorites.size(); i++) {
                assertSame("Favorite surface " + i + " is retained", favorites.get(i), tag(activity, "favorite-" + i));
                assertSame("Favorite ImageView " + i + " is retained", icons.get(i), icon(favorites.get(i)));
                assertSame("Ready icon must never disappear or be rebound on return", drawables.get(i), icons.get(i).getDrawable());
                assertNotNull(drawables.get(i));
                assertTrue(drawables.get(i) instanceof BitmapDrawable);
                assertFalse(((BitmapDrawable) drawables.get(i)).getBitmap().isRecycled());
                Object fallback = icons.get(i).getTag();
                if (fallback instanceof View) assertEquals("No loading glyph over a ready icon", View.GONE, ((View) fallback).getVisibility());
            }
        }
        void assertNoFailure() { if (failure != null) throw failure; }
    }

    private static final class ReturnTrace implements ActivityLifecycleCallback {
        MainActivity owner, returned;
        int task;
        boolean paused, returnRequested;
        @Override public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (activity == owner && stage == Stage.PAUSED) paused = true;
            if (activity instanceof MainActivity && returnRequested && stage == Stage.RESUMED) returned = (MainActivity) activity;
        }
    }

    private Intent homeIntent() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private void awaitFixtureForeground() {
        long deadline = SystemClock.uptimeMillis() + 8000;
        do {
            if (FIXTURE_PACKAGE.equals(device.getCurrentPackageName())) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The actual separate fixture must be foreground; package=" + device.getCurrentPackageName());
    }
    private boolean ready(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus() && "HOME".equals(activity.currentScreenForTest())
                && (Boolean) get(activity, "catalogLoaded") && load != null && load.isDone()
                && !(Boolean) get(activity, "renderDirty") && root != null && root.isLaidOut()
                && !((UiMotion) get(activity, "uiMotion")).isRunning() && artworkReady(activity);
    }
    private boolean artworkReady(MainActivity activity) {
        View root = (View) get(activity, "root");
        if (root == null) return false;
        for (int i = 0; i < favoriteCount; i++) {
            View favorite = root.findViewWithTag("favorite-" + i); if (favorite == null) return false;
            Drawable drawable = icon(favorite).getDrawable();
            if (!(drawable instanceof BitmapDrawable) || ((BitmapDrawable) drawable).getBitmap().isRecycled()) return false;
        }
        return true;
    }
    private static void assertWindow(MainActivity activity) {
        View decor = activity.getWindow().getDecorView();
        assertEquals("Actual app window width", 3840, decor.getWidth()); assertEquals("Actual app window height", 2160, decor.getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static ImageView icon(View favorite) {
        assertTrue(favorite instanceof ViewGroup);
        for (int i = 0; i < ((ViewGroup) favorite).getChildCount(); i++) {
            View child = ((ViewGroup) favorite).getChildAt(i); if (child instanceof ImageView) return (ImageView) child;
        }
        throw new AssertionError("Favorite must own its artwork ImageView: " + favorite.getTag());
    }
    private static View tag(MainActivity activity, String key) {
        View target = ((View) get(activity, "root")).findViewWithTag(key); assertNotNull("Current Home target " + key, target); return target;
    }
    @SuppressWarnings("unchecked") private static LruCache<String, Bitmap> cache(MainActivity activity) {
        return (LruCache<String, Bitmap>) field(get(activity, "imageLoader"), "cache");
    }
    private static boolean imageJobsIdle(MainActivity activity) { return ((Map<?, ?>) field(get(activity, "imageLoader"), "jobs")).isEmpty(); }
    private static Object get(MainActivity activity, String name) { return field(activity, name); }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static int countFocused(View view) {
        int count = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countFocused(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 10000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> state = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> state.set("screen=" + activity.currentScreenForTest()
                + ",resumed=" + get(activity, "resumed") + ",task=" + activity.getTaskId() + ",generation=" + get(activity, "renderGeneration")
                + ",focus=" + (activity.getCurrentFocus() == null ? "null" : activity.getCurrentFocus().getTag()) + ",dirty=" + get(activity, "renderDirty")));
        fail(message + "; " + state.get());
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }
}
