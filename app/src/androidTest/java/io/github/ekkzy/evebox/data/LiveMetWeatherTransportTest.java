package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.core.WeatherPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in real MET HTTPS/parser probe. Public generic Moscow coordinates; no settings/cache changes. */
@RunWith(AndroidJUnit4.class)
public class LiveMetWeatherTransportTest {
    @Test public void liveMetCompactUsesActualApplicationIdentityAndRealForecast() {
        Assume.assumeTrue("Live MET network access requires explicit opt-in",
                "true".equalsIgnoreCase(InstrumentationRegistry.getArguments().getString("eveBoxLiveMetWeatherTest", "false")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evebox-live-met-test"); thread.setDaemon(true); return thread;
        });
        long started = SystemClock.elapsedRealtime();
        Future<?> request = worker.submit(() -> {
            MetWeatherRepository.Response response = new MetWeatherRepository.HttpsTransport(MetWeatherRepository.userAgent(context))
                    .get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=55.7500&lon=37.6200", "", 10_000);
            assertTrue("Actual successful MET response", response.status == 200 || response.status == 203);
            long now = System.currentTimeMillis();
            AtomicInteger responseUses = new AtomicInteger();
            AtomicReference<MetWeatherRepository.Snapshot> raw = new AtomicReference<>();
            MetWeatherRepository repository = new MetWeatherRepository((url, validator, timeout) -> {
                assertEquals("The single live response is consumed once", 1, responseUses.incrementAndGet());
                return response;
            }, new MetWeatherRepository.Store() {
                @Override public MetWeatherRepository.Snapshot read(String key) { return raw.get(); }
                @Override public void write(String key, MetWeatherRepository.Snapshot snapshot) { raw.set(snapshot); }
            }, () -> now);
            Map<String, Object> values = new HashMap<>();
            values.put("weather_enabled", true); values.put("city_name", "Public test Moscow");
            values.put("city_latitude", 55.75d); values.put("city_longitude", 37.62d); values.put("units", "celsius");
            LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
            WeatherRepository.Weather weather = repository.load(settings, 10_000, "");
            assertTrue("Actual forecast values must validate", weather.isValid());
            assertEquals(WeatherRepository.Provider.MET_NORWAY, weather.provider);
            assertFalse("Unknown city timezone must stay unknown", weather.cityTimezoneKnown);
            assertEquals(WeatherRepository.ForecastTimeBasis.UTC, weather.forecastTimeBasis);
            assertEquals("UTC", weather.forecastTimezone);
            assertEquals("Unknown city current/hour labels must not use the device timezone", "UTC", weather.timezone);
            List<WeatherRepository.Day> days = weather.forecastDays(now);
            assertEquals("The actual unknown-city MET load provides three explicit UTC days", 3, days.size());
            LocalDate first = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate();
            for (int index = 0; index < days.size(); index++) {
                WeatherRepository.Day day = days.get(index);
                LocalDate date = first.plusDays(index);
                assertEquals(date.toString(), day.date);
                assertEquals(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), day.startMillis);
                assertEquals(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), day.endMillis);
                assertTrue(day.aggregated);
            }
            assertTrue("Actual hourly forecast must exist", !weather.hours.isEmpty());
            assertTrue("No remote future sample", weather.observedAtMillis <= now + 3_600_000);
            assertNotEquals(WeatherPolicy.CacheAge.EXPIRED, WeatherRepository.cacheAge(weather, now));
            assertEquals(0, weather.sunriseMillis); assertEquals(0, weather.sunsetMillis);
            assertNotNull(raw.get()); assertEquals(response.body, raw.get().body);
            assertEquals(now, weather.fetchedAtMillis);
            assertEquals(raw.get().expiresAtMillis, weather.sourceExpiresAtMillis);
            if (response.expiresAtMillis > 0) assertEquals(response.expiresAtMillis, weather.sourceExpiresAtMillis);
            WeatherRepository.Weather cached = repository.load(settings, 10_000, "");
            assertEquals("A fresh raw response is reused without a second request", 1, responseUses.get());
            assertEquals(weather.fetchedAtMillis, cached.fetchedAtMillis);
            assertEquals(weather.observedAtMillis, cached.observedAtMillis);
            assertEquals(weather.sourceExpiresAtMillis, cached.sourceExpiresAtMillis);
            assertFalse(cached.cityTimezoneKnown); assertEquals(WeatherRepository.ForecastTimeBasis.UTC, cached.forecastTimeBasis);
            assertEquals(3, cached.forecastDays(now).size());
            Bundle status = new Bundle();
            status.putString("stream", "LIVE_MET http=" + response.status + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                    + " provider=" + weather.provider + " observed=" + weather.observedAtMillis + " fetched=" + weather.fetchedAtMillis
                    + " expires=" + response.expiresAtMillis + " hourCount=" + weather.hours.size()
                    + " lastModifiedPresent=" + !response.lastModified.isEmpty() + " timezone=" + weather.timezone
                    + " cityTimezoneKnown=" + weather.cityTimezoneKnown + " forecastBasis=" + weather.forecastTimeBasis
                    + " forecastTimezone=" + weather.forecastTimezone + " dayCount=" + days.size()
                    + " firstDay=" + days.get(0).date + " lastDay=" + days.get(days.size() - 1).date
                    + " liveRequests=1 responseUses=" + responseUses.get() + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
            return null;
        });
        try { request.get(15, TimeUnit.SECONDS); }
        catch (Exception | AssertionError failure) {
            StringBuilder report = new StringBuilder("LIVE_MET failed elapsedMs=").append(SystemClock.elapsedRealtime() - started);
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                report.append(" <- ").append(cause.getClass().getName()).append(':').append(bounded(cause.getMessage(), 200));
                StackTraceElement[] trace = cause.getStackTrace();
                for (int index = 0; index < Math.min(5, trace.length); index++) report.append('@').append(bounded(trace[index].toString(), 140));
            }
            throw new AssertionError(bounded(report.toString(), 4096));
        } finally { request.cancel(true); worker.shutdownNow(); }
    }
    private static String bounded(String value, int limit) {
        if (value == null) return "<null>";
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
