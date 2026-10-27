package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.github.ekkzy.softlumi.MainActivity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in observation of the real MainActivity repository executor and UI callback path. */
@RunWith(AndroidJUnit4.class)
public class ActivityWeatherPathTest {
    private static final String EXPECTED_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=55.752040&longitude=37.617810"
                    + "&current=temperature_2m,weather_code,is_day,wind_speed_10m"
                    + "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset&forecast_days=3&precipitation_unit=mm"
                    + "&temperature_unit=celsius&wind_speed_unit=kmh&timeformat=unixtime&timezone=auto";

    @Test public void selectedMoscowUsesActualActivityWeatherPath() throws Exception {
        Assume.assumeTrue("Live network access requires explicit opt-in",
                "true".equalsIgnoreCase(InstrumentationRegistry.getArguments()
                        .getString("softlumiLiveWeatherTest", "false")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LauncherPrefs.Settings selected = new LauncherPrefs(context).read();
        assertTrue("Weather must already be enabled by the owner", selected.weatherEnabled);
        assertEquals("The diagnostic requires the already selected public Moscow city", "Москва", selected.cityName);
        assertEquals(55.752040, selected.cityLatitude, .0000005);
        assertEquals(37.617810, selected.cityLongitude, .0000005);
        assertEquals("Choose Celsius in the UI before this diagnostic", "celsius", selected.units);
        String beforeSettings = SettingsBackup.exportJson(selected);

        Trace trace = new Trace("true".equalsIgnoreCase(InstrumentationRegistry.getArguments()
                .getString("softlumiPreserveWeatherCache", "false")));
        trace.targetPackage = context.getPackageName();
        AtomicReference<MainActivity> activityRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch result = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        ActivityLifecycleCallback lifecycle = (Activity activity, Stage stage) -> {
            if (!(activity instanceof MainActivity)) return;
            trace.lifecycle = bounded(trace.lifecycle + ">" + stage.name(), 160);
            if (stage == Stage.CREATED) {
                // CREATED is after onCreate but before the first onResume. Injecting here
                // makes normal onResume invoke refreshData once, avoiding a test-induced
                // second refresh/cancel. Persistent weather cache is used only when explicitly opted in.
                MainActivity home = (MainActivity) activity;
                activityRef.set(home);
                trace.activityPackage = home.getPackageName();
                Object original = get(home, "weatherRepository");
                trace.originalTransport = get(original, "transport").getClass().getName();
                WeatherRepository.Cache originalCache = (WeatherRepository.Cache) get(original, "cache");
                trace.originalCache = originalCache.getClass().getName();
                set(home, "weatherRepository", new WeatherRepository(trace::get, trace.wrapCache(originalCache),
                        new WeatherRepository.JsonDecoder(), System::currentTimeMillis));
                trace.injected = true;
            }
        };
        Runnable observe = new Runnable() {
            @Override public void run() {
                if (finished.get()) return;
                MainActivity home = activityRef.get();
                if (home != null) {
                    try {
                        UiState state = new UiState(home);
                        trace.ui = state;
                        if (state.resultPresent) { result.countDown(); return; }
                    } catch (RuntimeException | AssertionError failure) {
                        trace.observationFailure = failure; result.countDown(); return;
                    }
                }
                main.postDelayed(this, 50);
            }
        };
        ActivityScenario<MainActivity> scenario = null;
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle);
        try {
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            scenario = ActivityScenario.launch(intent);
            assertTrue("CREATED injection must precede MainActivity.onResume", trace.injected);
            main.post(observe);
            long remaining = Math.max(1, 15000 - (SystemClock.elapsedRealtime() - trace.started));
            assertTrue("The Activity must publish a real weather result within the diagnostic wait",
                    result.await(remaining, TimeUnit.MILLISECONDS));
            assertNull("UI observation must remain readable", trace.observationFailure);
            UiState ui = trace.ui;
            assertNotNull(ui);
            int calls = trace.calls.get();
            assertTrue("At most one caller request is expected; no diagnostic retry is allowed", calls <= 1);
            if (calls == 0) {
                assertTrue("Only the opted-in production cache can satisfy a request without HTTP", trace.preserveCache);
                assertTrue("A cache hit must return an actual saved weather model", trace.cacheReadReturned);
                assertEquals("An early production cache hit must be fresh", "FRESH", ui.status);
            } else {
                assertTrue("Actual URL parameters must match the fixed public Moscow/Celsius request", trace.paramsEqual);
            }
            assertTrue("The real UI callback must be resumed", ui.resumed);
            assertTrue("Actual weather must be available to MainActivity", ui.available);
            assertTrue("The repository must publish real weather", ui.modelPresent);
            assertTrue("The repository must return FRESH or STALE, not a synthetic result",
                    "FRESH".equals(ui.status) || "STALE".equals(ui.status));
            assertTrue("The diagnostic must not change launcher settings",
                    beforeSettings.equals(SettingsBackup.exportJson(new LauncherPrefs(context).read())));
            Bundle progress = new Bundle();
            progress.putString("stream", "\n" + trace.report(null) + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, progress);
        } catch (AssertionError failure) {
            // The most recent state was captured while the Activity was alive, before close.
            throw new AssertionError(trace.report(failure));
        } finally {
            finished.set(true); main.removeCallbacks(observe);
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle);
            if (scenario != null) scenario.close();
        }
    }

    private static final class Trace {
        final long started = SystemClock.elapsedRealtime();
        final AtomicInteger calls = new AtomicInteger();
        final boolean preserveCache;
        volatile boolean injected, paramsEqual, interruptedBefore, interruptedAfter;
        volatile boolean cacheReadReturned;
        volatile String lifecycle = "", originalTransport = "", originalCache = "", url = "", threadName = "", targetPackage = "", activityPackage = "", cachedUnits = "";
        volatile int httpStatus = -1, bodyLength, cacheReads, cacheWriteAttempts, delegatedCacheWrites;
        volatile long cachedObserved, cachedFetched;
        volatile long transportMillis = -1;
        volatile Throwable transportFailure, observationFailure, cacheReadFailure, cacheWriteFailure;
        volatile UiState ui;
        Trace(boolean preserveCache) { this.preserveCache = preserveCache; }
        WeatherRepository.Cache wrapCache(WeatherRepository.Cache original) {
            return new WeatherRepository.Cache() {
                @Override public WeatherRepository.Weather read(String key) {
                    cacheReads++;
                    if (!preserveCache) return null;
                    try {
                        WeatherRepository.Weather weather = original.read(key);
                        cacheReadReturned = weather != null;
                        if (weather != null) {
                            cachedObserved = weather.observedAtMillis;
                            cachedFetched = weather.fetchedAtMillis;
                            cachedUnits = weather.units;
                        }
                        return weather;
                    } catch (RuntimeException failure) {
                        cacheReadFailure = failure; throw failure;
                    }
                }
                @Override public void write(String key, WeatherRepository.Weather weather) {
                    cacheWriteAttempts++;
                    if (!preserveCache) return;
                    try {
                        original.write(key, weather);
                        delegatedCacheWrites++;
                    } catch (RuntimeException failure) {
                        cacheWriteFailure = failure; throw failure;
                    }
                }
            };
        }
        String get(String address) throws IOException {
            calls.incrementAndGet(); url = address; paramsEqual = sameParameters(EXPECTED_URL, address);
            interruptedBefore = Thread.currentThread().isInterrupted(); threadName = Thread.currentThread().getName();
            long start = SystemClock.elapsedRealtime();
            try {
                String body = new WeatherRepository.HttpsTransport().get(address);
                httpStatus = 200; bodyLength = body.length(); return body;
            } catch (IOException | RuntimeException failure) {
                transportFailure = failure;
                if (failure instanceof WeatherRepository.HttpFailure)
                    httpStatus = ((WeatherRepository.HttpFailure) failure).statusCode;
                throw failure;
            } finally {
                interruptedAfter = Thread.currentThread().isInterrupted();
                transportMillis = SystemClock.elapsedRealtime() - start;
            }
        }
        String report(Throwable assertion) {
            UiState state = ui;
            boolean cacheHit = preserveCache && calls.get() == 0 && cacheReadReturned
                    && state != null && "FRESH".equals(state.status) && state.available;
            return bounded("ACTIVITY_WEATHER elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                    + ",androidWallMs=" + System.currentTimeMillis() + ",injected=" + injected
                    + ",targetPackage=" + targetPackage + ",activityPackage=" + activityPackage
                    + ",originalTransport=" + originalTransport + ",lifecycle=" + lifecycle
                    + ",preserveCache=" + preserveCache + ",originalCache=" + originalCache
                    + ",cacheReads=" + cacheReads + ",cacheReadReturned=" + cacheReadReturned + ",cacheHit=" + cacheHit
                    + ",cachedObserved=" + cachedObserved + ",cachedFetched=" + cachedFetched + ",cachedUnits=" + cachedUnits
                    + ",cacheWriteAttempts=" + cacheWriteAttempts + ",delegatedCacheWrites=" + delegatedCacheWrites
                    + ",calls=" + calls + ",urlEqualsExpected=" + (calls.get() == 0 ? "not-requested" : EXPECTED_URL.equals(url))
                    + ",paramsEqual=" + (calls.get() == 0 ? "not-requested" : paramsEqual)
                    + ",actualUrl=" + bounded(url, 512) + ",httpStatus=" + (calls.get() == 0 ? "not-requested" : httpStatus < 0 ? "unknown" : httpStatus)
                    + ",transportMs=" + transportMillis + ",bodyLength=" + bodyLength
                    + ",thread=" + bounded(threadName, 80) + ",interruptedBefore=" + interruptedBefore
                    + ",interruptedAfter=" + interruptedAfter
                    + ",ui=" + (state == null ? "not-captured" : state.describe())
                    + ",transportChain=" + chain(transportFailure) + ",observationChain=" + chain(observationFailure)
                    + ",cacheReadChain=" + chain(cacheReadFailure) + ",cacheWriteChain=" + chain(cacheWriteFailure)
                    + ",assertion=" + (assertion == null ? "none" : bounded(assertion.getMessage(), 160)), 4096);
        }
    }
    private static final class UiState {
        final boolean resumed, destroyed, resultPresent, modelPresent, available, futureDone, futureCancelled, weatherEnabled;
        final int loadGeneration, renderGeneration;
        final long capturedAt = SystemClock.elapsedRealtime(), observed, fetched;
        final String status, message, uiStatus, units;
        UiState(MainActivity home) {
            resumed = (Boolean) get(home, "resumed"); destroyed = (Boolean) get(home, "destroyed");
            LauncherPrefs.Settings prefs = (LauncherPrefs.Settings) get(home, "prefs");
            weatherEnabled = prefs.weatherEnabled; units = prefs.units;
            loadGeneration = (Integer) get(home, "loadGeneration"); renderGeneration = (Integer) get(home, "renderGeneration");
            WeatherRepository.WeatherResult weather = (WeatherRepository.WeatherResult) get(home, "weather");
            resultPresent = weather != null; modelPresent = weather != null && weather.weather != null;
            status = weather == null ? "null" : weather.status.name(); message = weather == null ? "" : weather.message;
            observed = modelPresent ? weather.weather.observedAtMillis : 0;
            fetched = modelPresent ? weather.weather.fetchedAtMillis : 0;
            available = (Boolean) invoke(home, "weatherAvailable");
            uiStatus = (String) invoke(home, "weatherStatus");
            Future<?> future = (Future<?>) get(home, "loadFuture");
            futureDone = future != null && future.isDone(); futureCancelled = future != null && future.isCancelled();
        }
        String describe() {
            return "{status=" + status + ",message=" + bounded(message, 160) + ",modelPresent=" + modelPresent
                    + ",weatherAvailable=" + available + ",uiStatus=" + bounded(uiStatus, 160)
                    + ",observed=" + observed + ",fetched=" + fetched + ",resumed=" + resumed + ",destroyed=" + destroyed
                    + ",prefsWeatherEnabled=" + weatherEnabled + ",prefsUnits=" + units
                    + ",loadGeneration=" + loadGeneration + ",renderGeneration=" + renderGeneration
                    + ",futureDone=" + futureDone + ",futureCancelled=" + futureCancelled
                    + ",snapshotAgeMs=" + (SystemClock.elapsedRealtime() - capturedAt) + "}";
        }
    }
    private static boolean sameParameters(String expectedText, String actualText) {
        try {
            Uri expected = Uri.parse(expectedText), actual = Uri.parse(actualText);
            if (!expected.getScheme().equals(actual.getScheme()) || !expected.getHost().equals(actual.getHost())
                    || !expected.getPath().equals(actual.getPath())
                    || !expected.getQueryParameterNames().equals(actual.getQueryParameterNames())) return false;
            for (String key : expected.getQueryParameterNames())
                if (!expected.getQueryParameters(key).equals(actual.getQueryParameters(key))) return false;
            return true;
        } catch (RuntimeException malformed) { return false; }
    }
    private static Object get(Object instance, String name) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Missing diagnostic field " + name); }
    }
    private static void set(Object instance, String name, Object value) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inject diagnostic field " + name); }
    }
    private static Object invoke(Object instance, String name) {
        try { Method method = instance.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot read diagnostic method " + name); }
    }
    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (int depth = 0; failure != null && depth < 6; depth++, failure = failure.getCause()) {
            if (depth > 0) text.append(" <- ");
            text.append(failure.getClass().getName()).append(':').append(bounded(failure.getMessage(), 180));
            StackTraceElement[] stack = failure.getStackTrace();
            if (stack.length > 0) text.append('@').append(bounded(stack[0].toString(), 120));
        }
        return text.length() == 0 ? "none" : text.toString();
    }
    private static String bounded(String text, int length) {
        if (text == null) return "<null>";
        String clean = text.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= length ? clean : clean.substring(0, length) + "…";
    }
}
