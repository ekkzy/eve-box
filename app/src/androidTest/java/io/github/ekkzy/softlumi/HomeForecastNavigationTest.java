package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WeatherRepository;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real D-pad navigation between two complete Home pages, with an informational forecast. */
@RunWith(AndroidJUnit4.class)
public final class HomeForecastNavigationTest {
    private static final String[] SHORTCUTS = {"shortcut-apps", "shortcut-wifi", "shortcut-bluetooth",
            "shortcut-wallpapers", "shortcut-launcher", "shortcut-tv"};
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final String CITY = "Controlled forecast navigation";
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        main(() -> { assertTrue(ValueAnimator.areAnimatorsEnabled()); assertEquals(1f, ValueAnimator.getDurationScale(), .001f); });
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : AppCatalog.load(context)) if (favorites.size() < 8) favorites.add(app.id);
        assertTrue("Two actual favorites are needed to verify upper focus restoration", favorites.size() >= 2);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE); original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); prefs.setBackground(0); prefs.setPaletteMode("dark"); prefs.setLanguage("en");
        prefs.setUnits("celsius"); prefs.setCity(CITY, 55.752040, 37.617810, "524901", ZONE.getId()); prefs.setWeatherEnabled(true);
        prefs.setWatchNextVisible(false); prefs.setSounds(false); prefs.setReducedMotion(false); prefs.setFavorites(favorites);
    }

    @After public void restoreAllPreferences() {
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
        assertTrue(editor.commit()); assertEquals(original, snapshot(storage));
    }

    @Test public void immediateBackCancelsLowerPageBeforeItsFirstAnimationFrame() throws Exception {
        new LauncherPrefs(context).setWeatherEnabled(false);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            awaitFrames(activity, "Upper Home is ready", current -> settled(current)
                    && ((Future<?>) get(current, "loadFuture")).isDone() && (Boolean) get(current, "catalogLoaded"));
            scene.onActivity(current -> assertTrue(tag(current, "favorite-1").requestFocus()));
            awaitFrames(activity, "Remember a real upper focus", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> {
                assertEquals(0, scroll(current).getScrollY());
                assertFocused(current, "favorite-1", "Upper origin before immediate Back");
                assertTrue(tag(current, "shortcut-launcher").requestFocus());
                assertEquals("No frame has advanced the new page spring", 0, scroll(current).getScrollY());
                assertTrue("Lower focus scheduled a real page animation", scroll(current).isScrollAnimating());
                current.onBackPressed();
            });
            awaitFrames(activity, "Back returns to the upper page", current -> settled(current)
                    && scroll(current).getScrollY() == 0 && tag(current, "favorite-1") == current.getCurrentFocus());
            AtomicInteger confirmedFrames = new AtomicInteger();
            awaitFrames(activity, "No stale spring can drag Home down after Back", current -> {
                assertEquals(0, scroll(current).getScrollY());
                assertFalse(scroll(current).isScrollAnimating());
                assertFocused(current, "favorite-1", "Back retains the upper origin on subsequent frames");
                return confirmedFrames.incrementAndGet() >= 4;
            });
        }
    }

    @Test public void remoteSwitchesCompletePagesKeepsForecastInformationalAndRestoresBothFocusOrigins() throws Exception {
        long now = System.currentTimeMillis(); String body = fullForecast(now).toString();
        AtomicInteger requests = new AtomicInteger(); AtomicBoolean injected = new AtomicBoolean();
        WeatherRepository.Cache noCache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather weather) { }
        };
        WeatherRepository controlled = new WeatherRepository(url -> {
            requests.incrementAndGet(); assertTrue(url.contains("latitude=55.752040&longitude=37.617810"));
            assertTrue(url.contains("forecast_days=3")); return body;
        }, noCache, new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("The controlled forecast never retries"));
        ActivityLifecycleCallback injection = (Activity owner, Stage stage) -> {
            if (owner instanceof MainActivity && stage == Stage.CREATED) {
                set(owner, "weatherRepository", controlled); injected.set(true);
            }
        };
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(injection);
        List<String> trace = new ArrayList<>(); AtomicReference<MainActivity> owner = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK))) {
            scene.onActivity(owner::set); MainActivity activity = owner.get();
            assertTrue("The controlled repository is installed before the first onResume", injected.get());
            awaitFrames(activity, "Initial Home has a complete controlled three-day forecast", current -> settled(current)
                    && ((Future<?>) get(current, "loadFuture")).isDone() && (Boolean) get(current, "catalogLoaded")
                    && countDays(tag(current, "three-day-forecast")) == 3);
            scene.onActivity(current -> {
                assertEquals("Actual UI window width", 3840, current.getWindow().getDecorView().getWidth());
                assertEquals("Actual UI window height", 2160, current.getWindow().getDecorView().getHeight());
                assertFocused(current, "favorite-0", "Initial native focus"); assertTrue(clockFullyVisible(current));
                assertEquals(0, scroll(current).getScrollY());
                assertEquals(3, ((WeatherRepository.WeatherResult) get(current, "weather")).weather.forecastDays(now).size());
                assertEquals(4, countTagged(tag(current, "three-day-forecast"), "forecast-hour-cell-"));
                assertFalse(tag(current, "three-day-forecast").isFocusable());
                assertNull("The complete lower page has no obsolete Configure focus stop", ((View) get(current, "root")).findViewWithTag("shortcut-weather"));
                assertEquals(1, requests.get()); record(current, "initial", trace);
            });
            assertTrue(device.pressDPadDown()); awaitFrames(activity, "The lower shortcut row settles", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> { assertFocused(current, "shortcut-launcher", "Initial lower-page entry"); assertLowerPage(current); });
            for (int index = 3; index >= 0; index--) {
                String previous = SHORTCUTS[index];
                assertTrue(device.pressDPadLeft()); awaitFrames(activity, "Left along shortcuts settles", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> { assertFocused(current, previous, "Remote selects the next origin"); assertLowerPage(current); });
            }

            for (int index = 0; index < SHORTCUTS.length; index++) {
                String origin = SHORTCUTS[index];
                scene.onActivity(current -> { assertFocused(current, origin, "Forecast entry origin"); record(current, "before Down " + origin, trace); });
                assertTrue(device.pressDPadDown()); awaitFrames(activity, "Down from " + origin + " settles", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> {
                    record(current, "after Down " + origin, trace);
                    assertFocused(current, origin, "Informational forecast must not consume remote focus");
                    assertLowerPage(current);
                });
                assertTrue(device.pressDPadUp()); awaitFrames(activity, "Up from forecast settles", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> {
                    assertFocused(current, "favorite-0", "Lower-page Up restores the actual upper focus");
                    assertEquals(0, scroll(current).getScrollY()); assertTrue(clockFullyVisible(current));
                });
                assertTrue(device.pressDPadDown()); awaitFrames(activity, "Return to the remembered lower origin", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> {
                    record(current, "round trip " + origin, trace);
                    assertFocused(current, origin, "Lower-page entry restores the previously selected shortcut"); assertLowerPage(current);
                });
                if (index + 1 < SHORTCUTS.length) {
                    String next = SHORTCUTS[index + 1];
                    assertTrue(device.pressDPadRight()); awaitFrames(activity, "Right along shortcuts settles", HomeForecastNavigationTest::settled);
                    scene.onActivity(current -> assertFocused(current, next, "Real Right selects the next entry origin"));
                }
            }
            assertTrue(device.pressBack()); awaitFrames(activity, "Back returns from the complete lower page", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> { assertFocused(current, "favorite-0", "Back restores upper focus"); assertEquals(0, scroll(current).getScrollY()); });
            assertTrue(device.pressDPadRight()); awaitFrames(activity, "Choose a different actual upper origin", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> assertFocused(current, "favorite-1", "Real Right changes the upper origin"));
            assertTrue(device.pressDPadDown()); awaitFrames(activity, "Enter lower page from the second app", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> { assertFocused(current, "shortcut-tv", "Lower origin survives Back"); assertLowerPage(current); });
            assertTrue(device.pressDPadUp()); awaitFrames(activity, "Up leaves shortcuts for favorites", HomeForecastNavigationTest::settled);
            scene.onActivity(current -> assertFocused(current, "favorite-1", "Real Up restores the second actual favorite"));
            assertTrue(device.pressDPadUp()); awaitFrames(activity, "Up at the top boundary reveals the clock", current -> settled(current) && scroll(current).getScrollY() == 0);
            scene.onActivity(current -> {
                assertFocused(current, "favorite-1", "The informational clock is not an artificial focus stop");
                assertFalse(tag(current, "clock").isFocusable()); assertTrue("Clock is fully visible after native Up", clockFullyVisible(current));
                assertEquals("Navigation makes no further provider requests", 1, requests.get()); record(current, "clock revealed", trace);
            });
            for (int cycle = 0; cycle < 3; cycle++) {
                assertTrue(device.pressDPadDown()); assertTrue(device.pressDPadUp()); assertTrue(device.pressDPadDown());
                awaitFrames(activity, "Rapid remote reversal settles at the full lower page", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> { assertFocused(current, "shortcut-tv", "Latest direction owns lower focus"); assertLowerPage(current); });
                assertTrue(device.pressDPadUp()); awaitFrames(activity, "Rapid-cycle upper return", HomeForecastNavigationTest::settled);
                scene.onActivity(current -> { assertFocused(current, "favorite-1", "Latest upper origin survives reversal"); assertEquals(0, scroll(current).getScrollY()); });
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(injection);
            Bundle evidence = new Bundle(); evidence.putString("stream", "\nHOME_FORECAST_DPAD " + trace + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
        }
    }

    private static JSONObject fullForecast(long now) throws Exception {
        LocalDate first = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate(); JSONObject daily = new JSONObject();
        for (String key : new String[]{"time", "weather_code", "temperature_2m_min", "temperature_2m_max", "precipitation_sum",
                "precipitation_probability_max", "wind_speed_10m_max", "wind_gusts_10m_max", "uv_index_max", "sunrise", "sunset"}) daily.put(key, new JSONArray());
        for (int index = 0; index < 3; index++) {
            long start = first.plusDays(index).atStartOfDay(ZONE).toEpochSecond();
            daily.getJSONArray("time").put(start); daily.getJSONArray("weather_code").put(index == 1 ? 61 : 3);
            daily.getJSONArray("temperature_2m_min").put(1 + index * 2); daily.getJSONArray("temperature_2m_max").put(11 + index * 2);
            daily.getJSONArray("precipitation_sum").put(.5 + index); daily.getJSONArray("precipitation_probability_max").put(20 + index * 15);
            daily.getJSONArray("wind_speed_10m_max").put(12 + index * 3); daily.getJSONArray("wind_gusts_10m_max").put(22 + index * 3);
            daily.getJSONArray("uv_index_max").put(1.5 + index);
            daily.getJSONArray("sunrise").put(start + 7 * 3600); daily.getJSONArray("sunset").put(start + 17 * 3600);
        }
        JSONObject hourly = new JSONObject().put("time", new JSONArray()).put("temperature_2m", new JSONArray())
                .put("weather_code", new JSONArray()).put("is_day", new JSONArray());
        long firstHour = first.atStartOfDay(ZONE).toEpochSecond();
        for (int index = 0; index < 72; index++) {
            hourly.getJSONArray("time").put(firstHour + index * 3600L); hourly.getJSONArray("temperature_2m").put(5 + index % 8);
            hourly.getJSONArray("weather_code").put(3); hourly.getJSONArray("is_day").put(index % 24 >= 6 && index % 24 < 18 ? 1 : 0);
        }
        return new JSONObject().put("timezone", ZONE.getId()).put("utc_offset_seconds", 10800).put("hourly", hourly)
                .put("current", new JSONObject().put("time", now / 1000).put("temperature_2m", 8)
                        .put("weather_code", 3).put("is_day", 1).put("wind_speed_10m", 8.4))
                .put("daily", daily).put("daily_units", new JSONObject().put("temperature_2m_min", "°C").put("temperature_2m_max", "°C")
                        .put("precipitation_sum", "mm").put("precipitation_probability_max", "%")
                        .put("wind_speed_10m_max", "km/h").put("wind_gusts_10m_max", "km/h").put("uv_index_max", ""));
    }
    private static boolean settled(MainActivity activity) {
        View root = (View) get(activity, "root"); TvScrollView scroll = scroll(activity);
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus() && "HOME".equals(activity.currentScreenForTest())
                && root != null && root.isLaidOut() && !root.isLayoutRequested() && root.getVisibility() == View.VISIBLE && root.getAlpha() > .999f
                && !(Boolean) get(activity, "renderDirty") && !((UiMotion) get(activity, "uiMotion")).isRunning()
                && scroll != null && !scroll.isScrollAnimating();
    }
    private static void assertFocused(MainActivity activity, String key, String reason) {
        View expected = tag(activity, key); View actual = activity.getCurrentFocus();
        assertSame(reason + "; actual=" + (actual == null ? "null" : actual.getTag()) + ",scroll=" + scroll(activity).getScrollY(), expected, actual);
        assertTrue(expected.isFocused()); assertTrue(expected.isShown()); assertFullyVisible(expected);
        assertEquals("Only one native View is focused", 1, focusedCount((View) get(activity, "root")));
    }
    private static void assertFullyVisible(View view) {
        assertTrue("Focused control must be fully visible through its real scroll clips: " + view.getTag(), fullyVisible(view));
    }
    private static boolean fullyVisible(View view) {
        Rect visible = new Rect(); if (!view.isShown() || !view.getGlobalVisibleRect(visible)) return false;
        Matrix transform = new Matrix(); view.transformMatrixToGlobal(transform);
        RectF full = new RectF(0, 0, view.getWidth(), view.getHeight()); transform.mapRect(full);
        return visible.left <= full.left + 2 && visible.top <= full.top + 2 && visible.right >= full.right - 2 && visible.bottom >= full.bottom - 2;
    }
    private static boolean clockFullyVisible(MainActivity activity) { return fullyVisible(tag(activity, "clock")); }
    private static void assertLowerPage(MainActivity activity) {
        assertEquals("Exactly one complete Home viewport, never a partial forecast scroll", scroll(activity).getHeight(), scroll(activity).getScrollY());
        assertFalse(clockFullyVisible(activity)); assertFullyVisible(tag(activity, "home-forecast-card"));
        assertEquals(3, countDays(tag(activity, "three-day-forecast")));
        assertEquals(4, countTagged(tag(activity, "three-day-forecast"), "forecast-hour-cell-"));
        assertFalse(tag(activity, "home-forecast-card").isFocusable());
    }
    private static int countTagged(View view, String prefix) {
        int count = view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countTagged(((ViewGroup) view).getChildAt(i), prefix);
        return count;
    }
    private static int focusedCount(View view) {
        int count = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += focusedCount(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static int countDays(View view) {
        int count = view.getTag() instanceof String && ((String) view.getTag()).startsWith("forecast-day-") ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countDays(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static void record(MainActivity activity, String action, List<String> trace) {
        View focused = activity.getCurrentFocus(); trace.add(action + ",focus=" + (focused == null ? "null" : focused.getTag())
                + ",scroll=" + scroll(activity).getScrollY() + ",days=" + countDays(tag(activity, "three-day-forecast")));
    }
    private static void awaitFrames(MainActivity activity, String message, Predicate<MainActivity> ready) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AssertionError> failure = new AtomicReference<>();
        AtomicReference<Choreographer.FrameCallback> callback = new AtomicReference<>();
        main(() -> {
            callback.set(time -> {
                try { if (ready.test(activity)) { done.countDown(); return; } }
                catch (AssertionError error) { failure.set(error); done.countDown(); return; }
                Choreographer.getInstance().postFrameCallback(callback.get());
            }); Choreographer.getInstance().postFrameCallback(callback.get());
        });
        boolean completed;
        try { completed = done.await(8, TimeUnit.SECONDS); }
        finally { main(() -> Choreographer.getInstance().removeFrameCallback(callback.get())); }
        if (failure.get() != null) throw failure.get();
        AtomicReference<String> state = new AtomicReference<>();
        main(() -> { View focus = activity.getCurrentFocus(); state.set("page=" + activity.currentScreenForTest() + ",focus="
                + (focus == null ? "null" : focus.getTag()) + ",scroll=" + (scroll(activity) == null ? "null" : scroll(activity).getScrollY())
                + ",dirty=" + get(activity, "renderDirty") + ",resumed=" + get(activity, "resumed")); });
        assertTrue(message + "; " + state.get(), completed);
    }
    private static TvScrollView scroll(MainActivity activity) { return (TvScrollView) get(activity, "homeScroll"); }
    private static View tag(MainActivity activity, String key) {
        View root = (View) get(activity, "root"); View view = root == null ? null : root.findViewWithTag(key); assertNotNull(key, view); return view;
    }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static void set(Object owner, String name, Object value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) { Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value); }
        return copy;
    }
}
