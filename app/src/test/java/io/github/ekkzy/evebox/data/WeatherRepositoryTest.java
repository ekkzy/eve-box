package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import io.github.ekkzy.evebox.core.WeatherPolicy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.junit.Test;

public class WeatherRepositoryTest {
    private static final long NOW = 1800000000000L;
    private static final class MemoryCache implements WeatherRepository.Cache {
        String key;
        WeatherRepository.Weather weather;
        int writes;
        @Override public WeatherRepository.Weather read(String requested) { return requested.equals(key) ? weather : null; }
        @Override public void write(String requested, WeatherRepository.Weather value) { key = requested; weather = value; writes++; }
    }
    private static WeatherRepository.Weather weather(long time, String units) {
        return new WeatherRepository.Weather(12.5, 3, true, 8.4, time, time, units);
    }
    private static LauncherPrefs.Settings settings(boolean enabled, boolean city) {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", enabled);
        values.put("units", "celsius");
        if (city) {
            values.put("city_name", "Moscow");
            values.put("city_latitude", 55.75d);
            values.put("city_longitude", 37.62d);
        }
        return LauncherPrefs.decode(values);
    }
    private static WeatherRepository.Decoder decoder() {
        return new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) {
                return Arrays.asList(new WeatherRepository.City("1", "Moscow", "Russia", "", 55.75, 37.62));
            }
            @Override public WeatherRepository.Weather current(String body, String units, long time) {
                return weather(time, units);
            }
        };
    }
    private static MemoryCache cache(long time) {
        MemoryCache cache = new MemoryCache();
        cache.write(WeatherRepository.cacheKey(55.75, 37.62, "celsius"), weather(time, "celsius"));
        cache.writes = 0;
        return cache;
    }
    @Test public void disabledAndMissingCityNeverUseTransport() {
        WeatherRepository repository = new WeatherRepository(url -> { fail("No implicit location request"); return ""; },
                new MemoryCache(), decoder(), () -> NOW);
        assertEquals(WeatherRepository.Status.DISABLED, repository.loadCurrent(settings(false, true), true).status);
        assertEquals(WeatherRepository.Status.NEEDS_CITY, repository.loadCurrent(settings(true, false), true).status);
    }
    @Test public void freshCacheAvoidsNetworkAndForceRefreshReplacesCache() {
        MemoryCache cache = cache(NOW - 1000);
        int[] calls = {0};
        WeatherRepository repository = new WeatherRepository(url -> { calls[0]++; return "fixture"; }, cache, decoder(), () -> NOW);
        assertEquals(WeatherRepository.Status.FRESH, repository.loadCurrent(settings(true, true), false).status);
        assertEquals(0, calls[0]);
        assertEquals(WeatherRepository.Status.FRESH, repository.loadCurrent(settings(true, true), true).status);
        assertEquals(1, calls[0]);
        assertEquals(1, cache.writes);
        assertEquals(NOW, cache.weather.fetchedAtMillis);
    }
    @Test public void offlineUsesOnlyStillEligibleSavedWeather() {
        MemoryCache cache = cache(NOW - WeatherPolicy.FRESH_MILLIS);
        WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("offline"); }, cache, decoder(), () -> NOW);
        WeatherRepository.WeatherResult result = repository.loadCurrent(settings(true, true), false);
        assertEquals(WeatherRepository.Status.STALE, result.status);
        assertNotNull(result.weather);
        assertEquals(NOW - WeatherPolicy.FRESH_MILLIS, result.weather.fetchedAtMillis);
        assertEquals(0, cache.writes);
    }
    @Test public void timeoutWithoutCacheIsUnavailable() {
        WeatherRepository repository = new WeatherRepository(url -> { throw new SocketTimeoutException("timeout"); },
                new MemoryCache(), decoder(), () -> NOW);
        WeatherRepository.WeatherResult result = repository.loadCurrent(settings(true, true), false);
        assertEquals(WeatherRepository.Status.UNAVAILABLE, result.status);
        assertNull(result.weather);
        assertTrue(result.message.contains("timed out"));
    }
    @Test public void expiredAndFutureCachesAreNeverDisplayed() {
        for (long time : new long[]{NOW - WeatherPolicy.MAX_STALE_MILLIS - 1, NOW + 1}) {
            WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("offline"); }, cache(time), decoder(), () -> NOW);
            assertNull(repository.loadCurrent(settings(true, true), false).weather);
        }
    }
    @Test public void cancelledCityLookupCannotReturnInvalidOrWrongUnitCache() {
        for (WeatherRepository.Weather invalid : Arrays.asList(
                new WeatherRepository.Weather(12, 3, true, -1, NOW, NOW, "celsius"),
                new WeatherRepository.Weather(54, 3, true, 5, NOW, NOW, "fahrenheit"))) {
            MemoryCache cache = cache(NOW);
            cache.weather = invalid;
            WeatherRepository.Decoder cityDecoder = new WeatherRepository.Decoder() {
                @Override public List<WeatherRepository.City> cities(String body) {
                    return Collections.singletonList(new WeatherRepository.City("1", "Moscow", "Russia", "",
                            55.75, 37.62, "Europe/Moscow"));
                }
                @Override public WeatherRepository.Weather current(String body, String units, long time) {
                    fail("A changed city must stop before a weather response is decoded"); return null;
                }
            };
            WeatherRepository repository = new WeatherRepository(url -> {
                assertTrue("Only the explicit city metadata lookup is allowed", url.contains("geocoding-api"));
                return "city fixture";
            }, cache, cityDecoder, () -> NOW, millis -> { }, null,
                    (expected, id, timezone) -> false);
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings(true, true), true);
            assertEquals(WeatherRepository.Failure.CANCELLED, result.failure);
            assertEquals(WeatherRepository.Status.UNAVAILABLE, result.status);
            assertNull("No early return may expose a corrupt or wrong-unit cache", result.weather);
            assertEquals(0, cache.writes);
        }
    }
    @Test public void changedCityOrUnitsCannotReusePreviousCache() {
        MemoryCache cache = cache(NOW);
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true);
        values.put("city_name", "Other city");
        values.put("city_latitude", 50);
        values.put("city_longitude", 30);
        WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("offline"); }, cache, decoder(), () -> NOW);
        assertNull(repository.loadCurrent(LauncherPrefs.decode(values), false).weather);
        values.put("city_latitude", 55.75);
        values.put("city_longitude", 37.62);
        values.put("units", "fahrenheit");
        assertNull(repository.loadCurrent(LauncherPrefs.decode(values), false).weather);
    }
    @Test public void httpErrorsHaveUnavailableStateAndDoNotWriteCache() {
        MemoryCache cache = new MemoryCache();
        WeatherRepository repository = new WeatherRepository(url -> { throw new WeatherRepository.HttpFailure(503); }, cache, decoder(), () -> NOW);
        assertEquals(WeatherRepository.Status.UNAVAILABLE, repository.loadCurrent(settings(true, true), false).status);
        assertEquals(0, cache.writes);
    }
    @Test public void malformedResponsesAreErrorsAndCanKeepEligibleCache() {
        WeatherRepository.Decoder bad = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { throw new IllegalArgumentException("bad JSON"); }
            @Override public WeatherRepository.Weather current(String body, String units, long time) { throw new IllegalArgumentException("bad JSON"); }
        };
        WeatherRepository empty = new WeatherRepository(url -> "invalid", new MemoryCache(), bad, () -> NOW);
        assertEquals(WeatherRepository.Status.ERROR, empty.loadCurrent(settings(true, true), false).status);
        WeatherRepository saved = new WeatherRepository(url -> "invalid", cache(NOW - 4000000), bad, () -> NOW);
        assertEquals(WeatherRepository.Status.STALE, saved.loadCurrent(settings(true, true), false).status);
        assertEquals(WeatherRepository.CityStatus.ERROR, empty.searchCities("Moscow", "en").status);
    }
    @Test public void citySearchRequiresExplicitMeaningfulQueryAndEscapesItsText() {
        String[] address = {null};
        WeatherRepository repository = new WeatherRepository(url -> { address[0] = url; return "fixture"; }, new MemoryCache(), decoder(), () -> NOW);
        assertEquals(WeatherRepository.CityStatus.EMPTY, repository.searchCities("M", "en").status);
        assertNull(address[0]);
        assertEquals(WeatherRepository.CityStatus.ITEMS, repository.searchCities("New York&count=100", "en").status);
        assertTrue(address[0].contains("New+York%26count%3D100"));
        assertTrue(address[0].contains("&count=8"));
    }
    @Test public void staleObservationCannotBecomeFreshByRedownloading() {
        WeatherRepository.Decoder delayed = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long time) {
                return new WeatherRepository.Weather(12, 1, true, 5, NOW - 7200000, time, units);
            }
        };
        WeatherRepository repository = new WeatherRepository(url -> "fixture", new MemoryCache(), delayed, () -> NOW);
        assertEquals(WeatherRepository.Status.STALE, repository.loadCurrent(settings(true, true), true).status);
    }
    @Test public void automaticUnitsUseRegionAndExplicitChoiceWins() {
        assertEquals("fahrenheit", WeatherRepository.resolveUnits("auto", Locale.US));
        assertEquals("celsius", WeatherRepository.resolveUnits("auto", new Locale("ru", "RU")));
        assertEquals("celsius", WeatherRepository.resolveUnits("celsius", Locale.US));
        assertEquals("fahrenheit", WeatherRepository.resolveUnits("fahrenheit", Locale.UK));
    }
}
