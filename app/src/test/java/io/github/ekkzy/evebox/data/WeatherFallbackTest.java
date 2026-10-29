package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;

public class WeatherFallbackTest {
    private static final long NOW = 1_800_000_000_000L;
    private static LauncherPrefs.Settings settings() {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true); values.put("city_name", "Fixture city");
        values.put("city_latitude", 52.52); values.put("city_longitude", 13.41); values.put("units", "celsius");
        return LauncherPrefs.decode(values);
    }
    private static final WeatherRepository.Cache CACHE = new WeatherRepository.Cache() {
        @Override public WeatherRepository.Weather read(String key) { return null; }
        @Override public void write(String key, WeatherRepository.Weather weather) { }
    };
    private static final WeatherRepository.Decoder DECODER = new WeatherRepository.Decoder() {
        @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
        @Override public WeatherRepository.Weather current(String body, String units, long time) {
            return new WeatherRepository.Weather(12, 1, true, 5, time, time, units);
        }
    };
    private static WeatherRepository.Weather met(long time) {
        return new WeatherRepository.Weather(10, 3, true, 9, time, NOW, "celsius", null, 0, 0, "UTC",
                WeatherRepository.Provider.MET_NORWAY, NOW + 3_600_000);
    }
    @Test public void transientPrimaryFailureUsesOnePrimaryAndOneActualFallback() {
        for (IOException failure : new IOException[]{new SocketTimeoutException("fixture connect"),
                new IOException("fixture network"), new WeatherRepository.HttpFailure(503)}) {
            int[] primary = {0}, secondary = {0};
            WeatherRepository.Weather expected = met(NOW - 1_800_000);
            WeatherRepository repository = new WeatherRepository(url -> { primary[0]++; throw failure; }, CACHE, DECODER,
                    () -> NOW, millis -> fail("No second primary retry when fallback is configured"), (settings, budget) -> {
                secondary[0]++; assertTrue(budget > 0 && budget <= 15_000); return expected;
            });
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings(), false);
            assertEquals(1, primary[0]); assertEquals(1, secondary[0]);
            assertEquals(WeatherRepository.Status.FRESH, result.status);
            assertEquals(WeatherRepository.Failure.NONE, result.failure);
            assertSame(expected, result.weather);
        }
    }
    @Test public void authRateLimitTlsAndCancellationNeverSwitchProvider() {
        IOException[] failures = {new WeatherRepository.HttpFailure(401), new WeatherRepository.HttpFailure(403),
                new WeatherRepository.HttpFailure(429), new WeatherRepository.HttpFailure(503, 60_000),
                new SSLHandshakeException("fixture TLS"), new InterruptedIOException("fixture cancellation")};
        for (IOException failure : failures) {
            int[] calls = {0};
            WeatherRepository repository = new WeatherRepository(url -> { calls[0]++; throw failure; }, CACHE, DECODER,
                    () -> NOW, millis -> fail("No primary retry"), (settings, budget) -> { fail("Provider switch prohibited"); return null; });
            assertNull(repository.loadCurrent(settings(), false).weather);
            assertEquals(1, calls[0]);
        }
    }
    @Test public void primarySuccessAndMalformedJsonNeverInvokeBackup() {
        WeatherRepository repository = new WeatherRepository(url -> "fixture", CACHE, DECODER, () -> NOW,
                millis -> { }, (settings, budget) -> { fail("Primary succeeded"); return null; });
        assertEquals(WeatherRepository.Provider.OPEN_METEO, repository.loadCurrent(settings(), false).weather.provider);
        WeatherRepository.Decoder invalid = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long time) { throw new IllegalArgumentException("fixture JSON"); }
        };
        repository = new WeatherRepository(url -> "fixture", CACHE, invalid, () -> NOW,
                millis -> { }, (settings, budget) -> { fail("Invalid primary data must not be hidden by backup"); return null; });
        assertEquals(WeatherRepository.Failure.INVALID_RESPONSE, repository.loadCurrent(settings(), false).failure);
    }
    @Test public void boundedMetFutureAllowanceDoesNotAlterTimestampOrPermitRemoteFuture() {
        for (long offset : new long[]{3_600_000, 3_600_001}) {
            WeatherRepository repository = new WeatherRepository(url -> { throw new SocketTimeoutException(); }, CACHE, DECODER,
                    () -> NOW, millis -> { }, (settings, budget) -> met(NOW + offset));
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings(), false);
            if (offset == 3_600_000) { assertNotNull(result.weather); assertEquals(NOW + offset, result.weather.observedAtMillis); }
            else { assertNull(result.weather); assertEquals(WeatherRepository.Failure.INVALID_RESPONSE, result.failure); }
        }
    }
}
