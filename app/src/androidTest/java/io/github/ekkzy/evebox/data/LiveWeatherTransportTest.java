package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.core.WeatherPolicy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit opt-in network diagnostic. Fixed public Moscow coordinates; no preferences or cache. */
@RunWith(AndroidJUnit4.class)
public class LiveWeatherTransportTest {
    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=55.752040&longitude=37.617810"
                    + "&current=temperature_2m,weather_code,is_day,wind_speed_10m"
                    + "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset&forecast_days=3&precipitation_unit=mm"
                    + "&temperature_unit=celsius&wind_speed_unit=kmh&timeformat=unixtime&timezone=auto";

    @Test public void liveMoscowForecastUsesProductionTransportAndDecoder() {
        Assume.assumeTrue("Live network access requires explicit opt-in",
                "true".equalsIgnoreCase(InstrumentationRegistry.getArguments()
                        .getString("eveBoxLiveWeatherTest", "false")));
        Diagnostic diagnostic = new Diagnostic();
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evebox-live-weather-test");
            thread.setDaemon(true); diagnostic.worker = thread; return thread;
        });
        Future<?> request = null;
        try {
            request = worker.submit(() -> {
                probe(diagnostic);
                return null;
            });
            // Production budgets stay unchanged. This contains the diagnostic's caller wait
            // if platform DNS/native I/O does not honor the production connection budgets.
            request.get(15, TimeUnit.SECONDS);
        } catch (Exception | AssertionError failure) {
            String report = diagnostic.report(failure); // Capture before cancellation changes flags.
            if (request != null) request.cancel(true);
            // The bounded report already contains the cause chain; do not attach a
            // potentially unbounded native exception message/body to runner output.
            throw new AssertionError(report);
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            worker.shutdownNow();
        }
    }

    private static void probe(Diagnostic diagnostic) throws Exception {
        diagnostic.interruptedBefore = Thread.currentThread().isInterrupted();
        diagnostic.phase = "HTTPS_TRANSPORT";
        String body;
        long transportStarted = SystemClock.elapsedRealtime();
        try {
            body = new WeatherRepository.HttpsTransport().get(FORECAST_URL);
            diagnostic.httpStatus = 200; // Production transport returns a body only for HTTP_OK.
        } finally { diagnostic.transportMillis = SystemClock.elapsedRealtime() - transportStarted; }
        diagnostic.bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
        diagnostic.bodyPrefix = bounded(body, 320);
        diagnostic.phase = "JSON_DECODER";
        diagnostic.fetchedAtMillis = System.currentTimeMillis();
        long decodeStarted = SystemClock.elapsedRealtime();
        try {
            diagnostic.weather = new WeatherRepository.JsonDecoder().current(body, "celsius", diagnostic.fetchedAtMillis);
        } finally { diagnostic.decodeMillis = SystemClock.elapsedRealtime() - decodeStarted; }
        diagnostic.phase = "MODEL_AND_CLOCK_VALIDATION";
        WeatherRepository.Weather weather = diagnostic.weather;
        long now = System.currentTimeMillis();
        assertNotNull("The decoder must return actual current weather", weather);
        assertTrue("Required current fields must pass the production model validation", weather.isValid());
        assertEquals("celsius", weather.units);
        assertTrue("Fetched time must not be in the future", weather.fetchedAtMillis <= now);
        assertTrue("Observed weather exceeds the repository's five-minute future allowance",
                weather.observedAtMillis <= now + 5 * 60 * 1000L);
        assertNotEquals("Actual response must be usable under the repository's cache-age policy",
                WeatherPolicy.CacheAge.EXPIRED,
                WeatherPolicy.cacheAge(Math.min(weather.fetchedAtMillis, weather.observedAtMillis), now));
        assertEquals("The real daily3 request must provide three local forecast days",3,weather.forecastDays(now).size());
        for(WeatherRepository.Day day:weather.forecastDays(now)){
            assertTrue("Actual daily minimum is available",Double.isFinite(day.minTemperature));
            assertTrue("Actual daily maximum is available",Double.isFinite(day.maxTemperature));
            assertTrue("Daily temperature range is ordered",day.minTemperature<=day.maxTemperature);
        }
        diagnostic.phase = "COMPLETE";
    }

    private static final class Diagnostic {
        final long startedElapsed = SystemClock.elapsedRealtime();
        final long startedWallMillis = System.currentTimeMillis();
        volatile String phase = "SUBMITTED", bodyPrefix = "";
        volatile int httpStatus = -1, bodyBytes;
        volatile long transportMillis = -1, decodeMillis = -1, fetchedAtMillis;
        volatile boolean interruptedBefore;
        volatile Thread worker;
        volatile WeatherRepository.Weather weather;

        String report(Throwable failure) {
            int status = httpStatus;
            StringBuilder chain = new StringBuilder();
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof WeatherRepository.HttpFailure)
                    status = ((WeatherRepository.HttpFailure) cause).statusCode;
                if (depth > 0) chain.append(" <- ");
                chain.append(cause.getClass().getName()).append(':').append(bounded(cause.getMessage(), 240));
                StackTraceElement[] stack = cause.getStackTrace();
                if (stack.length > 0) chain.append('@').append(bounded(stack[0].toString(), 140));
            }
            WeatherRepository.Weather current = weather;
            String model = current == null ? "none" : "observed=" + current.observedAtMillis
                    + ",fetched=" + current.fetchedAtMillis + ",hourCount=" + current.hours.size()
                    + ",timezone=" + bounded(current.timezone, 64) + ",valid=" + current.isValid();
            String report = "LIVE_WEATHER phase=" + phase + ",httpStatus=" + (status < 0 ? "unknown" : status)
                    + ",elapsedMs=" + (SystemClock.elapsedRealtime() - startedElapsed)
                    + ",transportMs=" + transportMillis + ",decodeMs=" + decodeMillis
                    + ",androidWallStartMs=" + startedWallMillis + ",androidWallNowMs=" + System.currentTimeMillis()
                    + ",sdk=" + Build.VERSION.SDK_INT + ",interruptedBefore=" + interruptedBefore
                    + ",workerInterrupted=" + (worker != null && worker.isInterrupted())
                    + ",workerState=" + (worker == null ? "not-started" : worker.getState().name())
                    + ",bodyBytes=" + bodyBytes + ",model=" + model
                    + ",chain=" + chain + ",publicBodyPrefix=" + bodyPrefix;
            return bounded(report, 4096);
        }
    }

    private static String bounded(String text, int maxLength) {
        if (text == null) return "<null>";
        String clean = text.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength) + "…";
    }
}
