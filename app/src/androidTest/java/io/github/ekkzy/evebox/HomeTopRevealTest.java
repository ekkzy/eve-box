package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

/** Real D-pad regression for the nonfocusable clock above the first app row. */
@RunWith(AndroidJUnit4.class)
public final class HomeTopRevealTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("The agreed emulator baseline is API 34", 34, Build.VERSION.SDK_INT);
        assertEquals("Physical display width is not enough; verify the window below too", 3840, device.getDisplayWidth());
        assertEquals(2160, device.getDisplayHeight());
        assertEquals("Standard font only", 1f, context.getResources().getConfiguration().fontScale, .001f);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("Rapid direction reversal needs enabled animators", ValueAnimator.areAnimatorsEnabled());
            assertEquals("Use the normal motion baseline", 1f, ValueAnimator.getDurationScale(), .001f);
        });
        List<AppEntry> apps = AppCatalog.load(context);
        assertFalse("At least one installed launchable application is required", apps.isEmpty());
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : apps) if (favorites.size() < 8) favorites.add(app.id);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        preferences.setBackground(0);
        preferences.setPaletteMode("dark");
        preferences.setLanguage("en");
        preferences.setWeatherEnabled(false);
        preferences.setWatchNextVisible(false);
        preferences.setSounds(false);
        preferences.setReducedMotion(false);
        preferences.setFavorites(favorites);
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

    @Test public void realUpAtTopFocusBoundaryRevealsClockAndSurvivesRapidReversal() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>();
            scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            await(activity, "Home and the actual 4K window settle", HomeTopRevealTest::ready);
            scene.onActivity(current -> {
                assertWindow(current);
                assertSame("Begin on the first actual favorite", tag(current, "favorite-0"), current.getCurrentFocus());
                assertEquals(0, scroll(current).getScrollY());
                assertClockVisible(current);
            });

            assertTrue(device.pressDPadDown());
            await(activity, "Down really enters the lower shortcuts and scrolls", current ->
                    focused(current, "shortcut-launcher") && scroll(current).getScrollY() == scroll(current).getHeight()
                            && !scroll(current).isScrollAnimating() && !motion(current).isRunning());
            scene.onActivity(current -> {
                assertEquals("Shortcuts remain part of Home", "HOME", current.currentScreenForTest());
                assertFalse("This must first reproduce a clock above the visible viewport", clockFullyVisible(current));
            });

            assertTrue(device.pressDPadUp());
            await(activity, "Up leaves the shortcuts for the favorite row", current ->
                    focused(current, "favorite-0") && !scroll(current).isScrollAnimating() && !motion(current).isRunning());
            // The clock is intentionally not a focus stop. A further Up at the
            // top actionable row must reveal the informational space above it.
            assertTrue(device.pressDPadUp());
            await(activity, "Top-boundary Up reveals the whole header", HomeTopRevealTest::atTop);
            scene.onActivity(HomeTopRevealTest::assertRestoredTop);

            for (int cycle = 0; cycle < 3; cycle++) {
                assertTrue(device.pressDPadDown());
                await(activity, "Rapid cycle " + cycle + " really reaches shortcuts", current -> focused(current, "shortcut-launcher"));
                // Do not await the outgoing scroll: these keys retarget the same
                // channel while the previous direction may still be moving.
                assertTrue(device.pressDPadUp());
                assertTrue(device.pressDPadUp());
                assertTrue(device.pressDPadUp());
                await(activity, "Rapid cycle " + cycle + " returns to a stable header", HomeTopRevealTest::atTop);
                scene.onActivity(HomeTopRevealTest::assertRestoredTop);
            }
        }
    }

    private static void assertRestoredTop(MainActivity activity) {
        assertWindow(activity);
        assertEquals("HOME", activity.currentScreenForTest());
        assertEquals("The upper informational space is fully revealed", 0, scroll(activity).getScrollY());
        assertSame("Revealing the clock retains a real actionable focus", tag(activity, "favorite-0"), activity.getCurrentFocus());
        assertEquals("Exactly one native View owns focus", 1, countFocused(activity.getWindow().getDecorView()));
        assertTrue("The focused app is visible", activity.getCurrentFocus().getGlobalVisibleRect(new Rect()));
        assertClockVisible(activity);
    }

    private static boolean atTop(MainActivity activity) {
        return focused(activity, "favorite-0") && scroll(activity).getScrollY() == 0
                && !scroll(activity).isScrollAnimating() && !motion(activity).isRunning();
    }

    private static boolean ready(MainActivity activity) {
        View root = (View) get(activity, "root");
        Future<?> load = (Future<?>) get(activity, "loadFuture");
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus()
                && "HOME".equals(activity.currentScreenForTest()) && (Boolean) get(activity, "catalogLoaded")
                && load != null && load.isDone() && !(Boolean) get(activity, "renderDirty")
                && root != null && root.isLaidOut() && focused(activity, "favorite-0")
                && !scroll(activity).isScrollAnimating() && !motion(activity).isRunning();
    }

    private static void assertWindow(MainActivity activity) {
        View decor = activity.getWindow().getDecorView();
        assertEquals("Actual app window width", 3840, decor.getWidth());
        assertEquals("Actual app window height", 2160, decor.getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }

    private static boolean clockFullyVisible(MainActivity activity) {
        View clock = tag(activity, "clock"); Rect visible = new Rect();
        if (!clock.isShown() || !clock.getGlobalVisibleRect(visible)) return false;
        Matrix transform = new Matrix(); clock.transformMatrixToGlobal(transform);
        RectF bounds = new RectF(0, 0, clock.getWidth(), clock.getHeight()); transform.mapRect(bounds);
        return visible.left <= bounds.left + 2 && visible.top <= bounds.top + 2
                && visible.right >= bounds.right - 2 && visible.bottom >= bounds.bottom - 2;
    }

    private static void assertClockVisible(MainActivity activity) {
        View clock = tag(activity, "clock");
        assertFalse("Clock remains informational, without an artificial D-pad stop", clock.isFocusable());
        assertTrue("Clock must be fully visible through every scroll clip", clockFullyVisible(activity));
        for (ViewParent parent = clock.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (!(parent instanceof TvScrollView)) continue;
            assertSame("Clock belongs to the active Home viewport", scroll(activity), parent);
        }
    }

    private static boolean focused(MainActivity activity, String key) {
        View root = (View) get(activity, "root");
        View target = root == null ? null : root.findViewWithTag(key);
        return target != null && target == activity.getCurrentFocus();
    }
    private static TvScrollView scroll(MainActivity activity) { return (TvScrollView) get(activity, "homeScroll"); }
    private static UiMotion motion(MainActivity activity) { return (UiMotion) get(activity, "uiMotion"); }
    private static View tag(MainActivity activity, String key) {
        View target = ((View) get(activity, "root")).findViewWithTag(key);
        assertNotNull("Current Home target " + key, target); return target;
    }
    private static Object get(MainActivity activity, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static int countFocused(View view) {
        int count = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countFocused(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> state = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> state.set(
                "screen=" + activity.currentScreenForTest() + ",focus=" + (activity.getCurrentFocus() == null ? "null" : activity.getCurrentFocus().getTag())
                        + ",scroll=" + (scroll(activity) == null ? "null" : scroll(activity).getScrollY()) + ",dirty=" + get(activity, "renderDirty")));
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
