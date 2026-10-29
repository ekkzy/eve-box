package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** One real Home/key-dispatch regression. No provider fixtures or system-setting writes. */
@RunWith(AndroidJUnit4.class)
public final class RapidHomeScrollTest {
    private SharedPreferences storage;
    private Map<String, ?> original;

    @Before public void prepareOwnedEmulator() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Debug package only", "io.github.ekkzy.evebox.debug", context.getPackageName());
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Never run this preference fixture on physical TV", "1",
                device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("This regression requires the existing font1 configuration", 1f,
                context.getResources().getConfiguration().fontScale, .001f);
        float scale = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
        assertTrue("System motion must already be enabled; this test does not change it", !Float.isFinite(scale) || scale != 0);
        List<AppEntry> apps = AppCatalog.load(context);
        assertTrue("Two actual installed emulator apps required", apps.size() >= 2);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = new HashMap<>(storage.getAll());
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); prefs.setBackground(19); prefs.setPaletteMode("dark");
        prefs.setLanguage("en"); prefs.setWeatherEnabled(false); prefs.setWatchNextVisible(false);
        prefs.setSounds(false); prefs.setReducedMotion(false);
        List<String> ids = new ArrayList<>();
        for (AppEntry app : apps) if (ids.size() < 8) ids.add(app.id);
        prefs.setFavorites(ids);
    }

    @After public void restoreExactPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, ?> item : original.entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                Set<String> copy = new HashSet<>();
                for (Object member : (Set<?>) value) copy.add((String) member);
                editor.putStringSet(key, copy);
            } else fail("Unsupported saved preference type: " + key);
        }
        assertTrue("Restore the exact debug preference snapshot", editor.commit());
        assertEquals(original, storage.getAll());
    }

    @Test public void rapidDownUpDownRetargetsWithoutJumpOrReplacingHome() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(a -> {
                Rect window = a.getWindowManager().getCurrentWindowMetrics().getBounds();
                assertEquals("Actual native 4K window required", 3840, window.width());
                assertEquals(2160, window.height());
            });
            await("Enabled catalog, disabled weather and Home layout are bound", () -> query(activity, a -> {
                LauncherPrefs.Settings prefs = (LauncherPrefs.Settings) field(a, "prefs");
                WeatherRepository.WeatherResult weather = (WeatherRepository.WeatherResult) field(a, "weather");
                View scroll = a.getWindow().getDecorView().findViewWithTag("home-scroll");
                // refreshData deliberately leaves watchLoaded false when Watch Next
                // is disabled. Wait for this fixture's actual enabled work instead.
                return (Boolean) field(a, "catalogLoaded") && !prefs.watchNextVisible && !prefs.weatherEnabled
                        && weather != null && weather.status == WeatherRepository.Status.DISABLED
                        && !(Boolean) field(a, "renderDirty") && scroll != null && scroll.isLaidOut()
                        && a.getWindow().getDecorView().findViewWithTag("favorite-0") != null;
            }), 8000);
            activity.onActivity(a -> assertTrue(a.getWindow().getDecorView().findViewWithTag("favorite-0").requestFocus()));
            await("Initial focus is settled before measurement", () -> query(activity, a -> {
                TvScrollView scroll = homeScroll(a);
                return !scroll.isScrollAnimating() && !((UiMotion) field(a, "uiMotion")).isRunning();
            }), 5000);

            AtomicReference<Probe> owned = new AtomicReference<>();
            activity.onActivity(a -> { Probe probe = new Probe(a); owned.set(probe); probe.start(); });
            Probe probe = owned.get();
            try {
                assertTrue("Frame-chain completes without idle synchronization", probe.done.await(7, TimeUnit.SECONDS));
                if (probe.failure.get() != null) throw new AssertionError("Rapid Home scroll frame probe", probe.failure.get());
            } finally {
                activity.onActivity(a -> probe.cancel());
            }
        }
    }

    private static final class Probe implements Choreographer.FrameCallback {
        final MainActivity activity;
        final TvScrollView scroll;
        final Object board;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final StringBuilder samples = new StringBuilder();
        long startedAt, upAt, secondDownAt;
        int phase, frames, intermediateFrames, firstMovingOffset, lastOffset;
        boolean cancelled;

        Probe(MainActivity activity) {
            this.activity = activity; scroll = homeScroll(activity); board = field(activity, "board");
        }
        void start() {
            try {
                assertEquals("HOME", activity.currentScreenForTest());
                assertEquals("favorite-0", activity.getCurrentFocus().getTag());
                assertEquals("Begin from the original Home viewport", 0, scroll.getScrollY());
                assertTrue(scroll.isSmoothScrollingEnabled());
                startedAt = SystemClock.uptimeMillis(); lastOffset = scroll.getScrollY();
                dispatchWithoutJump(KeyEvent.KEYCODE_DPAD_DOWN, "shortcut-apps");
                assertTrue("Down must start a scroll channel", scroll.isScrollAnimating());
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable error) { finish(error); }
        }
        @Override public void doFrame(long nanos) {
            if (cancelled) return;
            try {
                long now = SystemClock.uptimeMillis();
                assertTrue("Scroll must settle within six seconds", now - startedAt < 6000);
                assertSame("D-pad descent must retain the exact Home board", board, field(activity, "board"));
                assertSame("The exact same scroll host remains attached", scroll, homeScroll(activity));
                assertTrue(scroll.isAttachedToWindow());
                assertEquals("HOME", activity.currentScreenForTest());
                int current = scroll.getScrollY();
                if (current != lastOffset) intermediateFrames++;
                lastOffset = current;
                assertTrue("Bounded per-frame evidence", frames++ < 240);
                samples.append(now - startedAt).append(':').append(current).append(':').append(phase).append('\n');
                if (phase == 0 && current > 0) {
                    firstMovingOffset = current;
                    assertTrue("Up retarget must be inside native's 250 ms gap", now - startedAt < 250);
                    upAt = now;
                    dispatchWithoutJump(KeyEvent.KEYCODE_DPAD_UP, "favorite-0"); phase = 1;
                } else if (phase == 1) {
                    assertTrue("Second Down must still be inside 250 ms", now - startedAt < 250);
                    assertTrue("Up and second Down also occur within 250 ms", now - upAt < 250);
                    secondDownAt = now;
                    dispatchWithoutJump(KeyEvent.KEYCODE_DPAD_DOWN, "shortcut-apps"); phase = 2;
                } else if (phase == 2 && !scroll.isScrollAnimating()
                        && !((UiMotion) field(activity, "uiMotion")).isRunning()) {
                    assertTrue("All three D-pad inputs occurred within 250 ms", secondDownAt - startedAt < 250);
                    assertTrue("An actual moving frame preceded reversal", firstMovingOffset > 0);
                    assertTrue("Several distinct rendered offsets, not a final jump", intermediateFrames >= 3);
                    assertTrue("First moving sample was before the final destination", firstMovingOffset < current);
                    assertEquals("shortcut-apps", activity.getCurrentFocus().getTag());
                    assertInside(activity.getCurrentFocus(), scroll);
                    finish(null); return;
                }
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable error) { finish(error); }
        }
        void dispatchWithoutJump(int key, String expectedTag) {
            int before = scroll.getScrollY(); long time = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD);
            KeyEvent up = new KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD);
            assertTrue("Native Activity D-pad dispatch consumes the direction", activity.dispatchKeyEvent(down));
            activity.dispatchKeyEvent(up);
            assertEquals("No synchronous offset jump during native key dispatch", before, scroll.getScrollY());
            assertNotNull(activity.getCurrentFocus());
            assertEquals("Native focus graph chooses the expected destination", expectedTag, activity.getCurrentFocus().getTag());
            assertSame(board, field(activity, "board"));
        }
        void finish(Throwable error) {
            if (cancelled) return;
            failure.set(error); cancelled = true;
            Choreographer.getInstance().removeFrameCallback(this);
            Bundle evidence = new Bundle();
            evidence.putString("stream", "\nRAPID_HOME_SCROLL milliseconds:offset:phase\n" + samples
                    + "keys=Down@0,Up@" + (upAt - startedAt) + ",Down@" + (secondDownAt - startedAt) + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
            done.countDown();
        }
        void cancel() { cancelled = true; Choreographer.getInstance().removeFrameCallback(this); }
    }

    private static TvScrollView homeScroll(MainActivity activity) {
        TvScrollView scroll = activity.getWindow().getDecorView().findViewWithTag("home-scroll");
        assertNotNull("Same-page Home scroll host", scroll); return scroll;
    }
    private static Object field(MainActivity activity, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void assertInside(View focused, View viewport) {
        Matrix global = new Matrix(), inverse = new Matrix(), child = new Matrix();
        viewport.transformMatrixToGlobal(global); assertTrue(global.invert(inverse));
        focused.transformMatrixToGlobal(child);
        RectF bounds = new RectF(0, 0, focused.getWidth(), focused.getHeight()); child.mapRect(bounds); inverse.mapRect(bounds);
        assertTrue("Final enlarged focused surface is wholly inside the Home viewport: " + bounds,
                bounds.left >= -.5f && bounds.top >= -.5f
                        && bounds.right <= viewport.getWidth() + .5f && bounds.bottom <= viewport.getHeight() + .5f);
    }
    private interface Query<T> { T get(MainActivity activity); }
    private static <T> T query(ActivityScenario<MainActivity> activity, Query<T> query) {
        AtomicReference<T> result = new AtomicReference<>(); activity.onActivity(a -> result.set(query.get(a))); return result.get();
    }
    private static void await(String message, BooleanSupplier condition, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < until) { if (condition.getAsBoolean()) return; SystemClock.sleep(30); }
        fail(message);
    }
}
