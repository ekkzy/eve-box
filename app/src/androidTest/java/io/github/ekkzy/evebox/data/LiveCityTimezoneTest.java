package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in public city diagnostic. No preferences, user-selected location, or persisted cache is read. */
@RunWith(AndroidJUnit4.class)
public final class LiveCityTimezoneTest {
    @Test public void livePublicMoscowSearchReturnsItsActualGeonameIdAndIanaTimezone() throws Exception {
        Assume.assumeTrue("Live geocoder access requires explicit opt-in", "true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("eveBoxLiveCityTimezoneTest", "false")));
        assertEquals("Owned debug emulator only", "io.github.ekkzy.evebox.debug",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Diagnostic diagnostic = new Diagnostic();
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evebox-live-city-test"); thread.setDaemon(true);
            diagnostic.worker = thread; return thread;
        });
        Future<?> request = null;
        try {
            request = worker.submit(() -> {
                assertNotSame("Network and decoding must run outside the main looper", Looper.getMainLooper(), Looper.myLooper());
                WeatherRepository.Transport live = url -> {
                    diagnostic.phase = "HTTPS_TRANSPORT";
                    long started = SystemClock.elapsedRealtime();
                    try {
                        String body = new WeatherRepository.HttpsTransport().get(url);
                        diagnostic.httpStatus = 200;
                        diagnostic.bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
                        diagnostic.phase = "JSON_DECODER";
                        return body;
                    } catch (IOException failure) {
                        diagnostic.transportFailure = failure;
                        if (failure instanceof WeatherRepository.HttpFailure)
                            diagnostic.httpStatus = ((WeatherRepository.HttpFailure) failure).statusCode;
                        throw failure;
                    } finally { diagnostic.transportMillis = SystemClock.elapsedRealtime() - started; }
                };
                WeatherRepository.Cache noCache = new WeatherRepository.Cache() {
                    @Override public WeatherRepository.Weather read(String key) { throw new AssertionError("City search must not read weather cache"); }
                    @Override public void write(String key, WeatherRepository.Weather value) { throw new AssertionError("City search must not write weather cache"); }
                };
                // Inject boundaries directly: the Context constructor would create a persisted preferences adapter.
                WeatherRepository repository = new WeatherRepository(live, noCache, new WeatherRepository.JsonDecoder(), System::currentTimeMillis);
                WeatherRepository.CityResult result = repository.searchCities("Москва", "ru");
                diagnostic.status = result.status.name(); diagnostic.cityCount = result.items.size();
                assertEquals("Real city search must return actual results", WeatherRepository.CityStatus.ITEMS, result.status);
                WeatherRepository.City moscow = null;
                for (WeatherRepository.City city : result.items) if ("524901".equals(city.id)) {
                    assertNull("GeoNames ID must identify one result", moscow); moscow = city;
                }
                assertNotNull("The real response must include public Moscow GeoNames ID 524901", moscow);
                diagnostic.id = moscow.id; diagnostic.timezone = moscow.timezone;
                diagnostic.latitude = moscow.latitude; diagnostic.longitude = moscow.longitude;
                assertEquals("Europe/Moscow", moscow.timezone);
                assertEquals("Provider timezone must be recognized as an IANA ID", "Europe/Moscow", LauncherPrefs.ianaTimezone(moscow.timezone));
                diagnostic.phase = "COMPLETE";
                return null;
            });
            // Contains caller wait even when platform DNS/native I/O ignores production network budgets.
            request.get(15, TimeUnit.SECONDS);
            Bundle status = new Bundle(); status.putString("stream", diagnostic.report(null) + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        } catch (Exception | AssertionError failure) {
            String report = diagnostic.report(failure);
            if (request != null) request.cancel(true);
            throw new AssertionError(report);
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            worker.shutdownNow();
        }
    }
    private static final class Diagnostic {
        final long started = SystemClock.elapsedRealtime();
        volatile String phase = "SUBMITTED", status = "unknown", id = "", timezone = "";
        volatile int httpStatus = -1, bodyBytes, cityCount;
        volatile long transportMillis = -1;
        volatile double latitude = Double.NaN, longitude = Double.NaN;
        volatile IOException transportFailure;
        volatile Thread worker;
        String report(Throwable failure) {
            return bounded("LIVE_CITY publicQuery=Moscow-ru phase=" + phase + " status=" + status + " http=" + httpStatus
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " transportMs=" + transportMillis
                    + " bodyBytes=" + bodyBytes + " cityCount=" + cityCount + " id=" + id + " timezone=" + timezone
                    + " latitude=" + latitude + " longitude=" + longitude + " sdk=" + Build.VERSION.SDK_INT
                    + " workerState=" + (worker == null ? "not-started" : worker.getState())
                    + " networkFailure=" + chain(transportFailure) + " failure=" + chain(failure), 3000);
        }
    }
    private static String chain(Throwable failure) {
        StringBuilder value = new StringBuilder();
        for (int depth = 0; failure != null && depth < 5; depth++, failure = failure.getCause()) {
            if (value.length() > 0) value.append(" <- ");
            value.append(failure.getClass().getSimpleName()).append(':').append(bounded(failure.getMessage(), 180));
        }
        return value.toString();
    }
    private static String bounded(String value, int limit) {
        if (value == null) return "<null>";
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
