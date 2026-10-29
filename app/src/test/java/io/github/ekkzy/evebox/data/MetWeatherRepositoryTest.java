package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import io.github.ekkzy.evebox.core.WeatherPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class MetWeatherRepositoryTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000;
    private static final class Store implements MetWeatherRepository.Store {
        String key; MetWeatherRepository.Snapshot value; int writes;
        @Override public MetWeatherRepository.Snapshot read(String requested) { return requested.equals(key) ? value : null; }
        @Override public void write(String requested, MetWeatherRepository.Snapshot saved) { key = requested; value = saved; writes++; }
    }
    private static LauncherPrefs.Settings settings(String units, double latitude) {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true); values.put("city_name", "Fixture city");
        values.put("city_latitude", latitude); values.put("city_longitude", 13.41d); values.put("units", units);
        return LauncherPrefs.decode(values);
    }
    private static JSONObject row(long time, double temperature, String symbol) throws JSONException {
        return new JSONObject().put("time", Instant.ofEpochMilli(time).toString()).put("data", new JSONObject()
                .put("instant", new JSONObject().put("details", new JSONObject().put("air_temperature", temperature).put("wind_speed", 2.5)))
                .put("next_1_hours", new JSONObject().put("summary", new JSONObject().put("symbol_code", symbol))));
    }
    private static String fixture(long first) throws JSONException {
        JSONArray rows = new JSONArray();
        for (int index = 0; index < 8; index++) rows.put(row(first + index * HOUR, 10 + index, index % 2 == 0 ? "partlycloudy_day" : "rain_night"));
        return new JSONObject().put("properties", new JSONObject()
                .put("meta", new JSONObject().put("units", new JSONObject().put("air_temperature", "celsius").put("wind_speed", "m/s")))
                .put("timeseries", rows)).toString();
    }
    @Test public void parsesActualSampleTimestampTemperatureWindAndHoursWithoutSolarInventing() throws Exception {
        long sample = NOW - HOUR / 2;
        WeatherRepository.Weather weather = MetWeatherRepository.parse(fixture(sample), "celsius", NOW, NOW + HOUR, NOW, "Europe/Moscow");
        assertEquals(sample, weather.observedAtMillis);
        assertEquals(10, weather.temperature, .001);
        assertEquals(9, weather.windSpeed, .001);
        assertEquals(2, weather.weatherCode);
        assertEquals(WeatherRepository.Provider.MET_NORWAY, weather.provider);
        assertEquals("Europe/Moscow", weather.timezone);
        assertEquals(0, weather.sunriseMillis); assertEquals(0, weather.sunsetMillis);
        assertEquals(8, weather.hours.size());
        assertEquals(sample, weather.hours.get(0).timeMillis);
        assertEquals(Boolean.TRUE, weather.hours.get(0).isDay); assertEquals(Boolean.FALSE, weather.hours.get(1).isDay);
        assertEquals(sample + HOUR, weather.upcomingHours(NOW).get(0).timeMillis);
        assertEquals(WeatherPolicy.CacheAge.FRESH, WeatherRepository.cacheAge(weather, NOW));
    }
    @Test public void fahrenheitAndCacheProviderRoundTripUseRealSourceUnits() throws Exception {
        WeatherRepository.Weather weather = MetWeatherRepository.parse(fixture(NOW), "fahrenheit", NOW, NOW + HOUR, NOW, "UTC");
        assertEquals(50, weather.temperature, .001); assertEquals(51.8, weather.hours.get(1).temp, .001);
        WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(weather));
        assertEquals(weather.provider, copy.provider);
        assertEquals(weather.sourceExpiresAtMillis, copy.sourceExpiresAtMillis);
        assertEquals(weather.observedAtMillis, copy.observedAtMillis);
        assertEquals(weather.temperature, copy.temperature, .001);
    }
    @Test public void unknownSymbolsAndMissingRequiredValuesNeverBecomeClearOrZero() throws Exception {
        for (String invalid : new String[]{"unknown_day", ""}) {
            JSONObject fixture = new JSONObject(fixture(NOW));
            fixture.getJSONObject("properties").put("timeseries", new JSONArray().put(row(NOW, 10, invalid)));
            try { MetWeatherRepository.parse(fixture.toString(), "celsius", NOW, NOW + HOUR, NOW, "UTC"); fail("Unknown condition must not become clear sky"); }
            catch (JSONException expected) { }
        }
        JSONObject fixture = new JSONObject(fixture(NOW));
        fixture.getJSONObject("properties").getJSONObject("meta").getJSONObject("units").put("wind_speed", "km/h");
        try { MetWeatherRepository.parse(fixture.toString(), "celsius", NOW, NOW + HOUR, NOW, "UTC"); fail("Unrecognized units must not be silently converted"); }
        catch (JSONException expected) { }
    }
    @Test public void boundedFutureSampleIsPreservedAndTooOldOrTooFarFutureSamplesFail() throws Exception {
        WeatherRepository.Weather forecast = MetWeatherRepository.parse(fixture(NOW + HOUR), "celsius", NOW, NOW + HOUR, NOW, "UTC");
        assertEquals(NOW + HOUR, forecast.observedAtMillis);
        for (long first : new long[]{NOW + HOUR + 1, NOW - 20 * HOUR}) {
            try { MetWeatherRepository.parse(fixture(first), "celsius", NOW, NOW + HOUR, NOW, "UTC"); fail("No usable actual sample"); }
            catch (JSONException expected) { }
        }
    }
    @Test public void expiresStopsNetworkEvenForRepeatedExplicitFallbackAnd304UsesExactValidator() throws Exception {
        long[] now = {NOW}; int[] calls = {0}; Store store = new Store(); String body = fixture(NOW - HOUR / 2);
        String validator = "Sat, 24 Oct 2026 19:17:00 GMT";
        MetWeatherRepository repository = new MetWeatherRepository((url, modified, timeout) -> {
            calls[0]++;
            if (calls[0] == 1) { assertEquals("", modified); return new MetWeatherRepository.Response(200, body, validator, NOW + HOUR); }
            assertEquals(validator, modified);
            return new MetWeatherRepository.Response(304, "", "", now[0] + HOUR);
        }, store, () -> now[0]);
        WeatherRepository.Weather first = repository.load(settings("celsius", 52.52), 10_000);
        now[0] += 1000;
        WeatherRepository.Weather cached = repository.load(settings("fahrenheit", 52.52), 10_000);
        assertEquals(1, calls[0]); assertEquals(NOW, cached.fetchedAtMillis);
        assertEquals(first.temperature * 9 / 5 + 32, cached.temperature, .001);
        now[0] = NOW + HOUR + 120_001;
        WeatherRepository.Weather revalidated = repository.load(settings("celsius", 52.52), 10_000);
        assertEquals(2, calls[0]); assertEquals(now[0], revalidated.fetchedAtMillis);
        assertEquals(NOW + HOUR / 2, revalidated.observedAtMillis);
        assertEquals(11, revalidated.temperature, .001);
        assertEquals(body, store.value.body); // A 304 did not create a replacement response.
    }
    @Test public void coordinateTruncationAndRawCacheCannotCrossCities() throws Exception {
        assertEquals("lat=55.7520&lon=37.6178", MetWeatherRepository.coordinates(55.752049, 37.617819));
        assertEquals("lat=-33.1234&lon=-70.9876", MetWeatherRepository.coordinates(-33.123499, -70.987699));
        int[] calls = {0}; String body = fixture(NOW); Store store = new Store();
        MetWeatherRepository repository = new MetWeatherRepository((url, modified, timeout) -> {
            calls[0]++; return new MetWeatherRepository.Response(200, body, "", NOW + HOUR);
        }, store, () -> NOW);
        repository.load(settings("celsius", 52.52), 10_000);
        repository.load(settings("celsius", 53.52), 10_000);
        assertEquals(2, calls[0]);
    }
    @Test public void coordinateContractKeepsFourDecimalsAndTruncatesTowardZeroAtBoundaries() {
        assertEquals("lat=90.0000&lon=-180.0000", MetWeatherRepository.coordinates(90, -180));
        assertEquals("lat=0.0000&lon=0.0000", MetWeatherRepository.coordinates(-0.000099, 0.000099));
        assertEquals("lat=12.0001&lon=-12.0001", MetWeatherRepository.coordinates(12.0001, -12.0001));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -90.0001, 90.0001}) {
            assertThrows(IllegalArgumentException.class, () -> MetWeatherRepository.coordinates(invalid, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> MetWeatherRepository.coordinates(0, 180.0001));
    }
    @Test public void documentedSymbolVariantsAndHistoricalSpellingsKeepTheirDisplayMeaning() {
        for (String suffix : new String[]{"", "_day", "_night", "_polartwilight"}) {
            assertEquals(0, MetWeatherRepository.wmoCode("clearsky" + suffix));
            assertEquals(63, MetWeatherRepository.wmoCode("rain" + suffix));
            assertEquals(73, MetWeatherRepository.wmoCode("snow" + suffix));
            assertEquals(95, MetWeatherRepository.wmoCode("lightssleetshowersandthunder" + suffix));
            assertEquals(95, MetWeatherRepository.wmoCode("lightssnowshowersandthunder" + suffix));
        }
        assertEquals(2, MetWeatherRepository.wmoCode("PARTLYCLOUDY_DAY"));
        for (String unknown : new String[]{null, "", "unrecognized_day", "clearsky_future", "clearsky_day_extra"}) {
            assertEquals("Unknown symbol=" + unknown, -1, MetWeatherRepository.wmoCode(unknown));
        }
    }
    @Test public void missing304CacheAndMalformedSuccessAreNotStored() throws Exception {
        for (int status : new int[]{200, 304}) {
            Store store = new Store();
            MetWeatherRepository repository = new MetWeatherRepository((url, modified, timeout) ->
                    new MetWeatherRepository.Response(status, "invalid", "", NOW + HOUR), store, () -> NOW);
            try { repository.load(settings("celsius", 52.52), 10_000); fail("Invalid network response"); }
            catch (JSONException expected) { }
            assertEquals(0, store.writes);
        }
    }
    @Test public void corruptRawCacheIsRefetchedWithoutSendingItsValidator() throws Exception {
        Store store = new Store(); store.key = MetWeatherRepository.coordinates(52.52, 13.41);
        store.value = new MetWeatherRepository.Snapshot("invalid", "old-validator", NOW, NOW + HOUR, NOW + HOUR);
        int[] calls = {0}; String body = fixture(NOW);
        MetWeatherRepository repository = new MetWeatherRepository((url, modified, timeout) -> {
            calls[0]++; assertEquals("", modified);
            return new MetWeatherRepository.Response(200, body, "", NOW + HOUR);
        }, store, () -> NOW);
        assertEquals(10, repository.load(settings("celsius", 52.52), 10_000).temperature, .001);
        assertEquals(1, calls[0]); assertEquals("", store.value.lastModified);
    }
    @Test public void new200WithoutLastModifiedDoesNotReuseOlderBodyValidator() throws Exception {
        Store store = new Store(); long[] now = {NOW}; int[] calls = {0}; String body = fixture(NOW);
        MetWeatherRepository repository = new MetWeatherRepository((url, modified, timeout) -> {
            calls[0]++;
            return new MetWeatherRepository.Response(200, body, calls[0] == 1 ? "old-validator" : "", now[0] + HOUR);
        }, store, () -> now[0]);
        repository.load(settings("celsius", 52.52), 10_000);
        assertEquals("old-validator", store.value.lastModified);
        now[0] += 2 * HOUR;
        repository.load(settings("celsius", 52.52), 10_000);
        assertEquals("", store.value.lastModified);
    }
    @Test public void pastHourlySampleAndExpiredSavedBodyCannotBecomeFreshByMetadataAlone() {
        WeatherRepository.Weather old = new WeatherRepository.Weather(10, 3, false, 9, NOW - 2 * HOUR,
                NOW, "celsius", null, 0, 0, "UTC", WeatherRepository.Provider.MET_NORWAY, NOW + HOUR);
        assertEquals(WeatherPolicy.CacheAge.STALE, WeatherRepository.cacheAge(old, NOW));
        WeatherRepository.Weather expired = new WeatherRepository.Weather(10, 3, false, 9, NOW,
                NOW - WeatherPolicy.MAX_STALE_MILLIS - 1, "celsius", null, 0, 0, "UTC",
                WeatherRepository.Provider.MET_NORWAY, NOW + HOUR);
        assertEquals(WeatherPolicy.CacheAge.EXPIRED, WeatherRepository.cacheAge(expired, NOW));
    }
}
