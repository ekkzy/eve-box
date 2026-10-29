package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.DayNightPolicy;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Owned-emulator regressions; all weather/geocoding responses are local fixtures. */
@RunWith(AndroidJUnit4.class)
public final class CityAuditRegressionTest {
    private SharedPreferences storage;
    private Map<String, ?> original;

    @Before public void prepareOwnedEmulator() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Debug package only", "io.github.ekkzy.evebox.debug", context.getPackageName());
        assertTrue("Owned emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertEquals("Agreed font scale", 1f, context.getResources().getConfiguration().fontScale, .001f);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = new HashMap<>(storage.getAll());
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION)
                .putBoolean("weather_enabled", false).putBoolean("watch_next_visible", false)
                .putBoolean("recommendations_enabled", false).putBoolean("sounds", false)
                .putBoolean("reduced_motion", true).commit());
    }

    @After public void restoreExactPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                Set<String> copy = new HashSet<>();
                for (Object item : (Set<?>) value) copy.add((String) item);
                editor.putStringSet(key, copy);
            } else fail("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit());
        assertEquals("Restore raw debug preferences", original, storage.getAll());
    }

    @Test public void replacingCityResultsReleasesDetachedRowsFromActivityBindings() {
        AtomicInteger requests = new AtomicInteger();
        WeatherRepository repository = new WeatherRepository(url -> {
            assertTrue("Only fake geocoding", url.startsWith("https://geocoding-api.open-meteo.com/"));
            requests.incrementAndGet(); return "fixture";
        }, emptyCache(), cityDecoder(), System::currentTimeMillis);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitHome(scenario);
            scenario.onActivity(activity -> { set(activity, "weatherRepository", repository); push(activity, "CITY"); });
            await(scenario, "City page settles", activity -> "CITY".equals(get(activity, "renderedScreen").toString()) && settled(activity));
            List<TextView> retiredLabels = new ArrayList<>();
            int[] baseBindings = {0};
            scenario.onActivity(activity -> baseBindings[0] = tints(activity).size());
            for (String query : Arrays.asList("Alpha", "Bravo", "Charlie", "Delta")) {
                scenario.onActivity(activity -> {
                    LinearLayout list = (LinearLayout) get(activity, "cityList");
                    collectText(list, retiredLabels);
                    ((EditText) tag(activity, "city-query")).setText(query);
                    for (TextView retired : retiredLabels)
                        assertFalse("Replacing results must release old label bindings", tints(activity).containsKey(retired));
                });
                await(scenario, query + " results arrive", activity -> !((Boolean) get(activity, "citySearchPending"))
                        && ((LinearLayout) get(activity, "cityList")).getChildCount() == 2);
                scenario.onActivity(activity -> {
                    for (TextView retired : retiredLabels)
                        assertFalse("Detached labels must remain unreferenced by the Activity map", tints(activity).containsKey(retired));
                    assertEquals("Only the two current labels add bindings", baseBindings[0] + 2, tints(activity).size());
                });
            }
            assertEquals("One bounded request for each input", 4, requests.get());
        }
    }

    @Test public void cityInputStartsSearchWhileTheGeneralRepositoryWorkerIsBusy() throws Exception {
        CountDownLatch repositoryStarted = new CountDownLatch(1), releaseRepository = new CountDownLatch(1);
        CountDownLatch searchStarted = new CountDownLatch(1); AtomicInteger requests = new AtomicInteger();
        WeatherRepository repository = new WeatherRepository(url -> {
            assertTrue("Only the entered fake query", url.contains("name=Independent"));
            requests.incrementAndGet(); searchStarted.countDown(); return "fixture";
        }, emptyCache(), cityDecoder(), System::currentTimeMillis);
        Future<?>[] blocked = {null};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitHome(scenario);
            scenario.onActivity(activity -> {
                set(activity, "weatherRepository", repository);
                blocked[0] = ((ExecutorService) get(activity, "repositoryIo")).submit(() -> {
                    repositoryStarted.countDown();
                    try { releaseRepository.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                });
                push(activity, "CITY");
            });
            assertTrue("The unrelated worker is actually blocked", repositoryStarted.await(3, TimeUnit.SECONDS));
            scenario.onActivity(activity -> ((EditText) tag(activity, "city-query")).setText("Independent"));
            assertTrue("Geocoding must start before the general worker is released", searchStarted.await(3, TimeUnit.SECONDS));
            assertFalse("The test must not accidentally free the old shared queue", blocked[0].isDone());
            await(scenario, "The city result is published while the other task remains blocked", activity ->
                    !((Boolean) get(activity, "citySearchPending")) && ((LinearLayout) get(activity, "cityList")).getChildCount() == 2);
            assertEquals(1, requests.get());
        } finally {
            releaseRepository.countDown();
            if (blocked[0] != null) blocked[0].cancel(true);
        }
    }

    @Test public void weatherCallbackRebindsThePersistedCityTimezoneAndScheduledLighting() throws Exception {
        CountDownLatch metadataStarted = new CountDownLatch(1), releaseMetadata = new CountDownLatch(1);
        long now = System.currentTimeMillis();
        ZonedDateTime device = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());
        int deviceMinute = device.getHour() * 60 + device.getMinute();
        String zone = "Asia/Tokyo";
        int cityMinute = minuteAt(now, zone);
        if (Math.min(Math.floorMod(cityMinute - deviceMinute, 1440), Math.floorMod(deviceMinute - cityMinute, 1440)) < 60)
            zone = "America/Los_Angeles";
        final String resolvedZone = zone;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LauncherPrefs preferences = new LauncherPrefs(context);
        WeatherRepository.Decoder decoder = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) {
                return Collections.singletonList(new WeatherRepository.City("1850147", "Fixture", "Fixture", "", 35, 139, resolvedZone));
            }
            @Override public WeatherRepository.Weather current(String body, String units, long fetchedAt) {
                return new WeatherRepository.Weather(12, 0, true, 2, fetchedAt, fetchedAt, units,
                        Collections.emptyList(), 0, 0, resolvedZone, WeatherRepository.Provider.OPEN_METEO, 0, Collections.emptyList());
            }
        };
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.startsWith("https://geocoding-api.open-meteo.com/")) {
                metadataStarted.countDown();
                try {
                    if (!releaseMetadata.await(6, TimeUnit.SECONDS)) throw new InterruptedIOException("Fixture metadata was not released");
                } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Fixture cancelled"); }
            } else assertTrue("Only fixture forecast", url.startsWith("https://api.open-meteo.com/"));
            return "fixture";
        }, emptyCache(), decoder, System::currentTimeMillis, Thread::sleep, null, preferences::rememberCityMetadata);
        AtomicReference<View> before = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitHome(scenario);
            scenario.onActivity(activity -> {
                preferences.setCity("Fixture", 35, 139);
                preferences.setWeatherEnabled(true); preferences.setUnits("celsius");
                preferences.setWallpaperPeriod("schedule");
                preferences.setDayNightSchedule(Math.floorMod(deviceMinute - 15, 1440), (deviceMinute + 15) % 1440);
                set(activity, "weatherRepository", repository); invoke(activity, "render");
                assertFalse("Before metadata the device-time schedule is in daytime", DayNightPolicy.isNight(preferences.read(), now));
                invoke(activity, "refreshData");
            });
            assertTrue("Real loadCurrent reaches the controlled metadata boundary", metadataStarted.await(4, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                assertEquals("The catalog callback precedes metadata persistence", "", ((LauncherPrefs.Settings) get(activity, "prefs")).cityTimezone);
                before.set((View) get(activity, "root"));
            });
            releaseMetadata.countDown();
            await(scenario, "The weather callback rebinds current persisted settings", activity ->
                    resolvedZone.equals(((LauncherPrefs.Settings) get(activity, "prefs")).cityTimezone)
                            && Boolean.TRUE.equals(get(activity, "renderedNight")) && settled(activity));
            scenario.onActivity(activity -> {
                assertEquals(resolvedZone, preferences.read().cityTimezone);
                assertTrue("The resolved city schedule is nighttime", DayNightPolicy.isNight(preferences.read(), now));
                assertNotSame("A changed lighting signature rebuilds the existing Home", before.get(), get(activity, "root"));
            });
        } finally { releaseMetadata.countDown(); }
    }

    @Test public void manualWeatherRefreshKeepsAnAlreadyPendingFullCatalogRefresh() throws Exception {
        CountDownLatch repositoryStarted = new CountDownLatch(1), releaseRepository = new CountDownLatch(1);
        Future<?>[] blocked = {null}; AtomicReference<String> installedId = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitHome(scenario);
            scenario.onActivity(activity -> {
                List<?> previous = (List<?>) get(activity, "catalog");
                assertFalse("The owned emulator needs an installed launcher Activity for this fixture", previous.isEmpty());
                installedId.set(((AppEntry) previous.get(0)).id);
                assertFalse("Fixture weather stays disabled", ((LauncherPrefs.Settings) get(activity, "prefs")).weatherEnabled);
                blocked[0] = ((ExecutorService) get(activity, "repositoryIo")).submit(() -> {
                    repositoryStarted.countDown();
                    try { releaseRepository.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                });
            });
            assertTrue("The general worker is actually occupied", repositoryStarted.await(3, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                // Loaded flags describe the previous cycle, not the full refresh now in flight.
                set(activity, "catalogLoaded", true); set(activity, "watchLoaded", true);
                set(activity, "catalog", Collections.emptyList()); ((Map<?, ?>) get(activity, "appsById")).clear();
                invoke(activity, "refreshData");
                Future<?> full = (Future<?>) get(activity, "loadFuture");
                assertFalse("The pending full refresh is still behind the held worker", full.isDone());
                invoke(activity, "refreshData", true);
                assertFalse("Neither request is allowed to release unrelated work", blocked[0].isDone());
                assertTrue("No cached catalog can accidentally satisfy the assertion", ((List<?>) get(activity, "catalog")).isEmpty());
            });
            releaseRepository.countDown();
            await(scenario, "Manual weather refresh must replace the pending full refresh with another full refresh", activity -> {
                Future<?> latest = (Future<?>) get(activity, "loadFuture");
                return latest != null && latest.isDone() && ((Map<?, ?>) get(activity, "appsById")).containsKey(installedId.get())
                        && !((List<?>) get(activity, "catalog")).isEmpty() && settled(activity);
            });
            scenario.onActivity(activity -> assertFalse("Even disabled Watch Next must complete the full-refresh gate",
                    (Boolean) get(activity, "fullRefreshPending")));
        } finally {
            releaseRepository.countDown();
            if (blocked[0] != null) blocked[0].cancel(true);
        }
    }

    private static int minuteAt(long now, String zone) {
        ZonedDateTime time = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone));
        return time.getHour() * 60 + time.getMinute();
    }
    private static WeatherRepository.Cache emptyCache() {
        return new WeatherRepository.Cache() {
            public WeatherRepository.Weather read(String key) { return null; }
            public void write(String key, WeatherRepository.Weather value) { }
        };
    }
    private static WeatherRepository.Decoder cityDecoder() {
        return new WeatherRepository.Decoder() {
            public List<WeatherRepository.City> cities(String body) {
                return Arrays.asList(new WeatherRepository.City("1", "Fixture one", "", "", 35, 139),
                        new WeatherRepository.City("2", "Fixture two", "", "", 36, 140));
            }
            public WeatherRepository.Weather current(String body, String units, long fetchedAt) {
                throw new AssertionError("City fixture must never request weather");
            }
        };
    }
    private static void awaitHome(ActivityScenario<MainActivity> scenario) {
        await(scenario, "Initial local catalog settles", activity -> {
            Future<?> future = (Future<?>) get(activity, "loadFuture");
            return Boolean.TRUE.equals(get(activity, "catalogLoaded")) && future != null && future.isDone() && settled(activity);
        });
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            assertEquals("Actual agreed UI width", 3840, decor.getWidth());
            assertEquals("Actual agreed UI height", 2160, decor.getHeight());
        });
    }
    private static boolean settled(MainActivity activity) {
        View root = (View) get(activity, "root");
        return root.isLaidOut() && !((Boolean) get(activity, "renderDirty")) && !((UiMotion) get(activity, "uiMotion")).isRunning();
    }
    private static void await(ActivityScenario<MainActivity> scenario, String message, Predicate<MainActivity> ready) {
        long deadline = SystemClock.uptimeMillis() + 8000; AtomicReference<Boolean> complete = new AtomicReference<>(false);
        do {
            scenario.onActivity(activity -> complete.set(ready.test(activity)));
            if (complete.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }
    @SuppressWarnings("unchecked") private static Map<View, Integer> tints(MainActivity activity) { return (Map<View, Integer>) get(activity, "idleTints"); }
    private static void collectText(View view, List<TextView> result) {
        if (view instanceof TextView) result.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectText(((ViewGroup) view).getChildAt(i), result);
    }
    private static View tag(MainActivity activity, String key) {
        View view = ((View) get(activity, "root")).findViewWithTag(key); assertNotNull(key, view); return view;
    }
    private static void push(MainActivity activity, String name) {
        try {
            Class<?> type = field("screen").getType(); Object target = null;
            for (Object value : type.getEnumConstants()) if (name.equals(value.toString())) target = value;
            assertNotNull(name, target);
            Method method = MainActivity.class.getDeclaredMethod("push", type); method.setAccessible(true); method.invoke(activity, target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object get(MainActivity activity, String name) {
        try { return field(name).get(activity); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void set(MainActivity activity, String name, Object value) {
        try { field(name).set(activity, value); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void invoke(MainActivity activity, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void invoke(MainActivity activity, String name, boolean value) {
        try { Method method = MainActivity.class.getDeclaredMethod(name, boolean.class); method.setAccessible(true); method.invoke(activity, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Field field(String name) throws NoSuchFieldException {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
