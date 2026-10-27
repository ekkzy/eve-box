package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;

/** Deterministic network/cache fixtures; no network or Android process is required. */
public class WeatherRetryTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final class Time implements WeatherRepository.Clock {
        long now = NOW;
        @Override public long nowMillis() { return now; }
    }
    private static final class Cache implements WeatherRepository.Cache {
        final Map<String, WeatherRepository.Weather> entries = new HashMap<>();
        int writes;
        @Override public WeatherRepository.Weather read(String key) { return entries.get(key); }
        @Override public void write(String key, WeatherRepository.Weather value) { entries.put(key, value); writes++; }
    }
    private static LauncherPrefs.Settings settings(String units) {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true);
        values.put("city_name", "Fixture city");
        values.put("city_latitude", 52.52d);
        values.put("city_longitude", 13.41d);
        values.put("units", units);
        return LauncherPrefs.decode(values);
    }
    private static WeatherRepository.Weather sample(long time, String units) {
        return new WeatherRepository.Weather(10, 3, true, 8, time, time, units);
    }
    private static WeatherRepository.Decoder decoder() {
        return new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long time) { return sample(time, units); }
        };
    }
    private static WeatherRepository repo(WeatherRepository.Transport transport, Cache cache, Time time) {
        return new WeatherRepository(transport, cache, decoder(), time, millis -> { });
    }
    @Test public void transientFailureCanRecoverOnceWithinRemainingBudget() {
        Time time = new Time(); Cache cache = new Cache();
        int[] calls = {0}; int[] budgets = {0, 0};
        WeatherRepository.Transport transport = new WeatherRepository.Transport() {
            @Override public String get(String url) { throw new AssertionError("Budget overload must be used"); }
            @Override public String get(String url, int timeoutMillis) throws IOException {
                budgets[calls[0]] = timeoutMillis;
                if (calls[0]++ == 0) throw new WeatherRepository.HttpFailure(503);
                return "fixture";
            }
        };
        WeatherRepository.WeatherResult result = repo(transport, cache, time).loadCurrent(settings("celsius"), false);
        assertEquals(2, calls[0]);
        assertTrue(budgets[0] <= WeatherRepository.HttpsTransport.TOTAL_TIMEOUT_MILLIS);
        assertTrue(budgets[1] > 0 && budgets[1] <= budgets[0]);
        assertEquals(WeatherRepository.Status.FRESH, result.status);
        assertEquals(WeatherRepository.Failure.NONE, result.failure);
        assertEquals(NOW + WeatherPolicy.FRESH_MILLIS, result.retryAtMillis);
        assertEquals(1, cache.writes);
    }
    @Test public void finalTimeoutBacksOffWithoutResumeRequestStormAndSuccessResetsFailures() {
        Time time = new Time(); Cache cache = new Cache(); int[] calls = {0}; boolean[] offline = {true};
        WeatherRepository repository = repo(url -> {
            calls[0]++;
            if (offline[0]) throw new SocketTimeoutException("fixture timeout");
            return "fixture";
        }, cache, time);
        WeatherRepository.WeatherResult first = repository.loadCurrent(settings("celsius"), false);
        assertEquals(2, calls[0]);
        assertEquals(WeatherRepository.Failure.TIMEOUT, first.failure);
        assertEquals(NOW + 60_000, first.retryAtMillis);
        assertNull(first.weather);
        assertEquals(first.retryAtMillis, repository.loadCurrent(settings("celsius"), false).retryAtMillis);
        assertEquals(2, calls[0]);
        time.now = first.retryAtMillis;
        WeatherRepository.WeatherResult second = repository.loadCurrent(settings("celsius"), false);
        assertEquals(4, calls[0]);
        assertEquals(time.now + 120_000, second.retryAtMillis);
        offline[0] = false;
        assertEquals(WeatherRepository.Status.FRESH, repository.loadCurrent(settings("celsius"), true).status);
        offline[0] = true;
        assertEquals(time.now + 60_000, repository.loadCurrent(settings("celsius"), true).retryAtMillis);
    }
    @Test public void rateLimitRespectsServerRetryAfterEvenForManualForceRefresh() {
        Time time = new Time(); int[] calls = {0};
        WeatherRepository repository = repo(url -> {
            calls[0]++;
            throw new WeatherRepository.HttpFailure(429, 300_000);
        }, new Cache(), time);
        WeatherRepository.WeatherResult result = repository.loadCurrent(settings("celsius"), false);
        assertEquals(1, calls[0]);
        assertEquals(WeatherRepository.Failure.RATE_LIMITED, result.failure);
        assertEquals(429, result.httpStatus);
        assertEquals(NOW + 300_000, result.retryAtMillis);
        time.now += 60_000;
        repository.loadCurrent(settings("celsius"), true);
        assertEquals(1, calls[0]);
        time.now = result.retryAtMillis;
        repository.loadCurrent(settings("celsius"), false);
        assertEquals(2, calls[0]);
    }
    @Test public void httpClientErrorsTlsAndServerDelayNeverCauseImmediateRetry() {
        IOException[] failures = {new WeatherRepository.HttpFailure(400),
                new SSLHandshakeException("fixture TLS"), new WeatherRepository.HttpFailure(503, 120_000)};
        for (IOException failure : failures) {
            int[] calls = {0}; Time time = new Time();
            WeatherRepository.WeatherResult result = repo(url -> { calls[0]++; throw failure; }, new Cache(), time)
                    .loadCurrent(settings("celsius"), false);
            assertEquals(1, calls[0]);
            assertEquals(WeatherRepository.Status.UNAVAILABLE, result.status);
            assertNull(result.weather);
            assertTrue(result.retryAtMillis > NOW);
            if (failure instanceof WeatherRepository.HttpFailure)
                assertEquals(((WeatherRepository.HttpFailure) failure).statusCode, result.httpStatus);
        }
    }
    @Test public void savedObservationIsNotRewrittenDuringFailureOrBackoff() {
        Time time = new Time(); Cache cache = new Cache();
        WeatherRepository.Weather original = sample(NOW - WeatherPolicy.FRESH_MILLIS, "celsius");
        cache.entries.put(WeatherRepository.cacheKey(52.52, 13.41, "celsius"), original);
        WeatherRepository repository = repo(url -> { throw new IOException("fixture offline"); }, cache, time);
        WeatherRepository.WeatherResult result = repository.loadCurrent(settings("celsius"), false);
        assertEquals(WeatherRepository.Status.STALE, result.status);
        assertEquals(WeatherRepository.Failure.NETWORK, result.failure);
        assertSame(original, result.weather);
        assertSame(original, repository.loadCurrent(settings("celsius"), false).weather);
        assertEquals(NOW - WeatherPolicy.FRESH_MILLIS, result.weather.fetchedAtMillis);
        assertEquals(0, cache.writes);
        time.now = original.observedAtMillis + WeatherPolicy.MAX_STALE_MILLIS + 1;
        assertNull(repository.loadCurrent(settings("celsius"), false).weather);
    }
    @Test public void unitsHaveIndependentRetryGatesAndClockCorrectionDoesNotStrandGate() {
        Time time = new Time(); int[] calls = {0};
        WeatherRepository repository = repo(url -> { calls[0]++; throw new IOException("fixture offline"); }, new Cache(), time);
        repository.loadCurrent(settings("celsius"), false);
        repository.loadCurrent(settings("fahrenheit"), false);
        assertEquals(4, calls[0]);
        repository.loadCurrent(settings("celsius"), false);
        assertEquals(4, calls[0]);
        time.now -= 1000;
        repository.loadCurrent(settings("celsius"), false);
        assertEquals(6, calls[0]);
    }
    @Test public void interruptionIsNotRetriedCachedOrBackedOff() {
        Time time = new Time(); Cache cache = new Cache(); int[] calls = {0};
        WeatherRepository repository = new WeatherRepository(url -> { calls[0]++; throw new IOException("fixture offline"); },
                cache, decoder(), time, millis -> { throw new InterruptedException("fixture cancellation"); });
        try {
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings("celsius"), false);
            assertEquals(1, calls[0]);
            assertEquals(WeatherRepository.Failure.CANCELLED, result.failure);
            assertEquals(0, result.retryAtMillis);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, cache.writes);
        } finally { Thread.interrupted(); }
        WeatherRepository immediatelyCancelled = repo(url -> { throw new InterruptedIOException("fixture cancellation"); }, cache, time);
        assertEquals(0, immediatelyCancelled.loadCurrent(settings("celsius"), false).retryAtMillis);
        assertEquals(0, cache.writes);
    }
    @Test public void preInterruptedCallerDoesNotOpenNetwork() {
        try {
            Thread.currentThread().interrupt();
            WeatherRepository repository = repo(url -> { fail("Cancelled caller must not open network"); return ""; }, new Cache(), new Time());
            assertEquals(WeatherRepository.Failure.CANCELLED, repository.loadCurrent(settings("celsius"), true).failure);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test public void invalidResponseAndFutureObservationDoNotReplaceValidCache() {
        for (boolean future : new boolean[]{false, true}) {
            Time time = new Time(); Cache cache = new Cache(); int[] calls = {0};
            WeatherRepository.Weather original = sample(NOW - WeatherPolicy.FRESH_MILLIS, "celsius");
            cache.entries.put(WeatherRepository.cacheKey(52.52, 13.41, "celsius"), original);
            WeatherRepository.Decoder invalid = new WeatherRepository.Decoder() {
                @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
                @Override public WeatherRepository.Weather current(String body, String units, long fetched) {
                    if (!future) throw new IllegalArgumentException("fixture invalid JSON");
                    return new WeatherRepository.Weather(10, 3, true, 8, NOW + 3_600_000, fetched, units);
                }
            };
            WeatherRepository repository = new WeatherRepository(url -> { calls[0]++; return "fixture"; }, cache, invalid, time, millis -> { });
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings("celsius"), false);
            assertEquals(1, calls[0]);
            assertEquals(WeatherRepository.Failure.INVALID_RESPONSE, result.failure);
            assertEquals(NOW + WeatherPolicy.MAX_RETRY_MILLIS, result.retryAtMillis);
            assertSame(original, result.weather);
            assertEquals(0, cache.writes);
        }
    }
    @Test public void freshCacheSchedulesItsActualExpiryRatherThanAnotherThirtyMinutes() {
        Time time = new Time(); Cache cache = new Cache();
        cache.entries.put(WeatherRepository.cacheKey(52.52, 13.41, "celsius"), sample(NOW - 29 * 60_000, "celsius"));
        WeatherRepository repository = repo(url -> { fail("Still fresh cache"); return ""; }, cache, time);
        assertEquals(NOW + 60_000, repository.loadCurrent(settings("celsius"), false).retryAtMillis);
    }
}
