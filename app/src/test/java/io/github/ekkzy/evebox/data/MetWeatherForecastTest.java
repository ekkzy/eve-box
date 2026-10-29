package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** MET's period sums cannot be treated as hourly values or split over a local calendar boundary. */
public class MetWeatherForecastTest {
    private static final long HOUR = 3_600_000L;
    private static final long START = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();

    private static JSONObject period(String symbol, double rain, double probability) throws JSONException {
        return new JSONObject().put("summary", new JSONObject().put("symbol_code", symbol))
                .put("details", new JSONObject().put("precipitation_amount", rain).put("probability_of_precipitation", probability));
    }
    private static JSONObject row(long time, double temperature, boolean hourly, boolean longPeriod) throws JSONException {
        JSONObject data = new JSONObject().put("instant", new JSONObject().put("details", new JSONObject()
                .put("air_temperature", temperature).put("wind_speed", 2.5).put("wind_speed_of_gust", 5)
                .put("ultraviolet_index_clear_sky", 20)));
        if (hourly) data.put("next_1_hours", period("rain", .1, 35));
        if (longPeriod) {
            JSONObject period = period("heavyrain", 2, 60);
            period.getJSONObject("details").put("air_temperature_min", 5).put("air_temperature_max", 40);
            data.put("next_6_hours", period);
        }
        return new JSONObject().put("time", Instant.ofEpochMilli(time).toString()).put("data", data);
    }
    private static JSONObject body(JSONArray rows) throws JSONException {
        return new JSONObject().put("properties", new JSONObject().put("meta", new JSONObject().put("units", new JSONObject()
                .put("air_temperature", "celsius").put("wind_speed", "m/s").put("wind_speed_of_gust", "m/s")
                .put("precipitation_amount", "mm").put("probability_of_precipitation", "%")
                .put("air_temperature_min", "celsius").put("air_temperature_max", "celsius")
                .put("ultraviolet_index_clear_sky", "1"))).put("timeseries", rows));
    }
    private static JSONObject hourlyBody(long start, int count, boolean hourly, boolean sixHours) throws JSONException {
        JSONArray rows = new JSONArray();
        for (int index = 0; index < count; index++) rows.put(row(start + index * HOUR, 10 + index % 24, hourly, sixHours));
        return body(rows);
    }
    private static WeatherRepository.Weather parse(JSONObject body, long now, String timezone, String units) throws JSONException {
        return MetWeatherRepository.parse(body.toString(), units, now, now + HOUR, now, timezone);
    }
    private static LauncherPrefs.Settings settings() {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true); values.put("city_name", "Fixture city");
        values.put("city_latitude", 55.75d); values.put("city_longitude", 37.62d); values.put("units", "celsius");
        return LauncherPrefs.decode(values);
    }
    private static final class RawStore implements MetWeatherRepository.Store {
        String key; MetWeatherRepository.Snapshot snapshot;
        @Override public MetWeatherRepository.Snapshot read(String requested) { return requested.equals(key) ? snapshot : null; }
        @Override public void write(String requested, MetWeatherRepository.Snapshot value) { key = requested; snapshot = value; }
    }
    private static final class NormalCache implements WeatherRepository.Cache {
        String key, snapshot;
        @Override public WeatherRepository.Weather read(String requested) {
            try { return requested.equals(key) ? WeatherRepository.CacheCodec.decode(snapshot) : null; }
            catch (JSONException failure) { throw new IllegalArgumentException(failure); }
        }
        @Override public void write(String requested, WeatherRepository.Weather value) {
            try { key = requested; snapshot = WeatherRepository.CacheCodec.encode(value); }
            catch (JSONException failure) { throw new IllegalArgumentException(failure); }
        }
    }

    @Test public void hourlyAndSixHourPeriodsNeverDoubleCountAndAvailableExtremaRemainSourceData() throws Exception {
        long now = START + 12 * HOUR;
        WeatherRepository.Weather weather = parse(hourlyBody(START, 73, true, true), now, "UTC", "celsius");
        assertEquals(3, weather.days.size());
        WeatherRepository.Day day = weather.days.get(1);
        assertTrue(day.aggregated); assertFalse(day.partialDay);
        assertEquals(24 * HOUR, day.coverageEndMillis - day.coverageStartMillis);
        assertEquals(2.4, day.precipitationMm, .0001); // 24 hourly values, never the overlapping 6-hour sums.
        assertEquals(35, day.precipitationProbabilityMax, .001);
        assertEquals(5, day.minTemperature, .001); assertEquals(40, day.maxTemperature, .001);
        assertEquals(9, day.windSpeedMax, .001); assertEquals(18, day.windGustsMax, .001);
        assertEquals(63, day.weatherCode);
        assertTrue(Double.isNaN(day.uvIndexMax)); // Clear-sky UV is not the actual daily UV maximum.
        assertEquals(0, day.sunriseMillis); assertEquals(0, day.sunsetMillis);
        WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(weather));
        assertTrue(copy.days.get(1).aggregated); assertEquals(day.precipitationMm, copy.days.get(1).precipitationMm, 0);
    }

    @Test public void overlappingSixHourRowsAreCountedOnceAndFahrenheitConversionIsAppliedToExtrema() throws Exception {
        WeatherRepository.Weather weather = parse(hourlyBody(START, 73, false, true), START + 12 * HOUR, "UTC", "fahrenheit");
        WeatherRepository.Day day = weather.days.get(1);
        assertEquals(8, day.precipitationMm, .001); // Four disjoint periods, rather than 24 overlapping ones.
        assertEquals(60, day.precipitationProbabilityMax, .001);
        assertEquals(41, day.minTemperature, .001); assertEquals(104, day.maxTemperature, .001);
        assertEquals(65, day.weatherCode); assertFalse(day.partialDay);
        assertEquals(71.6, weather.temperature, .001); // Current sample at +12h is still the instant value.
    }

    @Test public void halfHourTimezoneDoesNotProrateOrCarryRainAcrossLocalMidnight() throws Exception {
        long now = Instant.parse("2026-10-24T18:00:00Z").toEpochMilli(); // 23:30 in Kolkata.
        JSONArray rows = new JSONArray();
        for (int index = 0; index < 14; index++) rows.put(row(now + index * 6 * HOUR, 10, false, true));
        WeatherRepository.Weather weather = parse(body(rows), now, "Asia/Kolkata", "celsius");
        assertEquals(3, weather.days.size());
        WeatherRepository.Day today = weather.days.get(0), tomorrow = weather.days.get(1);
        assertEquals("2026-10-24", today.date); assertTrue(today.partialDay);
        assertTrue(Double.isNaN(today.precipitationMm)); // The first full six-hour period crosses midnight.
        assertEquals(-1, today.weatherCode);
        assertEquals("2026-10-25", tomorrow.date); assertTrue(tomorrow.partialDay);
        assertEquals(6, tomorrow.precipitationMm, .001); // Only 00–06, 06–12, 12–18 UTC fit this local day.
        assertEquals(18 * HOUR, tomorrow.coverageEndMillis - tomorrow.coverageStartMillis);
        assertEquals(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), tomorrow.coverageStartMillis);
    }

    @Test public void missingOptionalMeasurementsAndUnusableIntervalsRemainUnavailable() throws Exception {
        JSONObject body = hourlyBody(START, 73, true, false);
        JSONObject meta = body.getJSONObject("properties").getJSONObject("meta").getJSONObject("units");
        meta.remove("probability_of_precipitation"); meta.put("wind_speed_of_gust", "km/h");
        JSONArray rows = body.getJSONObject("properties").getJSONArray("timeseries");
        for (int index = 0; index < rows.length(); index++)
            rows.getJSONObject(index).getJSONObject("data").getJSONObject("next_1_hours")
                    .getJSONObject("details").put("precipitation_amount", JSONObject.NULL);
        WeatherRepository.Weather weather = parse(body, START + 12 * HOUR, "UTC", "celsius");
        for (WeatherRepository.Day day : weather.days) {
            assertTrue(Double.isNaN(day.precipitationMm)); assertTrue(Double.isNaN(day.precipitationProbabilityMax));
            assertTrue(Double.isNaN(day.windGustsMax)); assertTrue(Double.isNaN(day.uvIndexMax));
        }
        assertEquals(9, weather.windSpeed, .001); assertTrue(weather.isValid());
    }

    @Test public void anInternalMissingRainIntervalMarksTheAggregatePartialWithoutFillingItWithZero() throws Exception {
        JSONObject body = hourlyBody(START, 73, true, false);
        body.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(30)
                .getJSONObject("data").getJSONObject("next_1_hours").getJSONObject("details")
                .put("precipitation_amount", JSONObject.NULL);
        WeatherRepository.Day tomorrow = parse(body, START + 12 * HOUR, "UTC", "celsius").days.get(1);
        assertEquals(tomorrow.startMillis, tomorrow.coverageStartMillis);
        assertEquals(tomorrow.endMillis, tomorrow.coverageEndMillis);
        assertTrue(tomorrow.partialDay); assertEquals(2.3, tomorrow.precipitationMm, .0001);
    }

    @Test public void localDstDayAggregatesItsActual23Hours() throws Exception {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        long start = LocalDate.parse("2026-03-29").atStartOfDay(zone).toInstant().toEpochMilli();
        WeatherRepository.Day day = parse(hourlyBody(start, 73, true, false), start + 12 * HOUR, zone.getId(), "celsius").days.get(0);
        assertEquals(23 * HOUR, day.endMillis - day.startMillis);
        assertEquals(2.3, day.precipitationMm, .0001); assertFalse(day.partialDay);
    }

    @Test public void verifiedCityTimezoneOverridesDeviceZoneAndSurvivesSubsequentMetCache() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            long[] now = {START + 12 * HOUR};
            Map<String, Object> values = new HashMap<>();
            values.put("weather_enabled", true); values.put("city_name", "Los Angeles");
            values.put("city_latitude", 34.05d); values.put("city_longitude", -118.24d); values.put("units", "celsius");
            LauncherPrefs.Settings selected = LauncherPrefs.decode(values);
            JSONObject primaryBody = new JSONObject().put("timezone", "America/Los_Angeles")
                    .put("current", new JSONObject().put("time", (now[0] - 2 * HOUR) / 1000)
                            .put("temperature_2m", 12).put("weather_code", 3).put("is_day", 1).put("wind_speed_10m", 5));
            NormalCache cache = new NormalCache();
            cache.write(WeatherRepository.cacheKey(34.05, -118.24, "celsius"),
                    new WeatherRepository.JsonDecoder().current(primaryBody.toString(), "celsius", now[0] - 2 * HOUR));
            String raw = hourlyBody(START, 73, true, false).toString();
            MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) ->
                    new MetWeatherRepository.Response(200, raw, "", now[0] + HOUR), new RawStore(), () -> now[0]);
            WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("offline primary"); }, cache,
                    new WeatherRepository.JsonDecoder(), () -> now[0], millis -> fail("One primary attempt"), met);
            for (int attempt = 0; attempt < 2; attempt++) {
                WeatherRepository.WeatherResult result = repository.loadCurrent(selected, false);
                assertEquals(WeatherRepository.Provider.MET_NORWAY, result.weather.provider);
                assertTrue(result.weather.cityTimezoneKnown); assertEquals("America/Los_Angeles", result.weather.timezone);
                assertEquals(WeatherRepository.ForecastTimeBasis.CITY, result.weather.forecastTimeBasis);
                assertEquals("America/Los_Angeles", result.weather.forecastTimezone);
                assertEquals(3, result.weather.forecastDays(now[0]).size());
                assertEquals(Instant.parse("2026-10-24T07:00:00Z").toEpochMilli(), result.weather.days.get(0).startMillis);
                assertTrue(WeatherRepository.CacheCodec.decode(cache.snapshot).cityTimezoneKnown);
                now[0] += 2 * HOUR;
            }
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void unknownOrDifferentCityTimezoneKeepsCurrentAndHoursAndUsesExplicitUtcDays() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            long now = START + 12 * HOUR;
            String raw = hourlyBody(START, 73, true, false).toString();
            for (boolean otherCity : new boolean[]{false, true}) {
                NormalCache cache = new NormalCache();
                if (otherCity) {
                    JSONObject old = new JSONObject().put("timezone", "America/Los_Angeles")
                            .put("current", new JSONObject().put("time", (now - 2 * HOUR) / 1000)
                                    .put("temperature_2m", 12).put("weather_code", 3).put("is_day", 1).put("wind_speed_10m", 5));
                    cache.write(WeatherRepository.cacheKey(34.05, -118.24, "celsius"),
                            new WeatherRepository.JsonDecoder().current(old.toString(), "celsius", now - 2 * HOUR));
                } else {
                    cache.write(WeatherRepository.cacheKey(55.75, 37.62, "celsius"),
                            new WeatherRepository.Weather(12, 3, true, 5, now - 2 * HOUR, now - 2 * HOUR, "celsius"));
                }
                MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) ->
                        new MetWeatherRepository.Response(200, raw, "", now + HOUR), new RawStore(), () -> now);
                WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("offline primary"); }, cache,
                        new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("One primary attempt"), met);
                WeatherRepository.Weather weather = repository.loadCurrent(settings(), false).weather;
                assertNotNull(weather); assertEquals(WeatherRepository.Provider.MET_NORWAY, weather.provider);
                assertTrue(weather.isValid()); assertFalse(weather.hours.isEmpty());
                assertFalse(weather.cityTimezoneKnown); assertEquals("UTC", weather.timezone);
                assertEquals(WeatherRepository.ForecastTimeBasis.UTC, weather.forecastTimeBasis);
                assertEquals("UTC", weather.forecastTimezone);
                assertEquals(3, weather.days.size()); assertEquals(3, weather.forecastDays(now).size());
                assertEquals("2026-10-24", weather.days.get(0).date); assertEquals(START, weather.days.get(0).startMillis);
                WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(weather));
                assertFalse(copy.cityTimezoneKnown); assertEquals("UTC", copy.timezone);
                assertEquals(WeatherRepository.ForecastTimeBasis.UTC, copy.forecastTimeBasis);
                assertEquals("UTC", copy.forecastTimezone); assertEquals(3, copy.forecastDays(now).size());
            }
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void utcAdmissionRequiresExplicitMetBasisAndCannotConvertAnUnknownCityIntoAKnownOne() throws Exception {
        long now = START + 12 * HOUR; String raw = hourlyBody(START, 73, true, false).toString();
        MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) ->
                new MetWeatherRepository.Response(200, raw, "", now + HOUR), new RawStore(), () -> now);
        WeatherRepository.Weather utc = met.load(settings(), 10_000);
        String snapshot = WeatherRepository.CacheCodec.encode(utc);
        assertFalse(utc.cityTimezoneKnown); assertEquals(3, utc.days.size());
        for (int invalid = 0; invalid < 3; invalid++) {
            JSONObject value = new JSONObject(snapshot);
            if (invalid == 0) value.put("provider", "OPEN_METEO");
            if (invalid == 1) value.put("forecast_timezone", "Asia/Tokyo");
            if (invalid == 2) value.remove("forecast_time_basis");
            WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(value.toString());
            assertTrue(copy.isValid()); assertFalse(copy.cityTimezoneKnown);
            assertEquals(WeatherRepository.ForecastTimeBasis.UNKNOWN, copy.forecastTimeBasis);
            assertEquals("", copy.forecastTimezone); assertTrue(copy.days.isEmpty());
        }
        JSONObject legacy = new JSONObject(snapshot).put("forecast_schema", 1);
        legacy.remove("forecast_time_basis"); legacy.remove("forecast_timezone");
        WeatherRepository.Weather old = WeatherRepository.CacheCodec.decode(legacy.toString());
        assertTrue(old.needsForecastUpgrade); assertFalse(old.cityTimezoneKnown); assertTrue(old.days.isEmpty());
    }

    @Test public void freshSchemaOneUnknownCacheReparsesRawMetAcrossMidnightWithoutNetworkOrTimestampRewrite() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            long firstSample = START + 24 * HOUR, fetched = firstSample - 10 * 60_000, now = firstSample + 10 * 60_000;
            long expires = fetched + HOUR;
            WeatherRepository.Weather old = new WeatherRepository.Weather(10, 63, false, 9, firstSample, fetched, "celsius",
                    null, 0, 0, "Asia/Tokyo", WeatherRepository.Provider.MET_NORWAY, expires);
            JSONObject legacy = new JSONObject(WeatherRepository.CacheCodec.encode(old)).put("forecast_schema", 1);
            legacy.remove("forecast_time_basis"); legacy.remove("forecast_timezone");
            NormalCache cache = new NormalCache(); cache.key = WeatherRepository.cacheKey(55.75, 37.62, "celsius");
            cache.snapshot = legacy.toString();
            assertEquals(io.github.ekkzy.evebox.core.WeatherPolicy.CacheAge.FRESH, WeatherRepository.cacheAge(old, now));
            RawStore raw = new RawStore(); raw.key = MetWeatherRepository.coordinates(55.75, 37.62);
            String body = hourlyBody(firstSample, 73, true, false).toString();
            raw.snapshot = new MetWeatherRepository.Snapshot(body, "unchanged-validator", fetched, expires, expires);
            MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) -> {
                fail("A raw MET response before Expires must be re-parsed without network"); return null;
            }, raw, () -> now);
            int[] primary = {0};
            WeatherRepository repository = new WeatherRepository(url -> { primary[0]++; throw new IOException("primary unavailable"); },
                    cache, new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("Only one primary attempt with MET fallback"), met);
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings(), false);
            assertEquals(WeatherRepository.Status.FRESH, result.status); assertEquals(0, primary[0]);
            assertFalse(result.weather.cityTimezoneKnown); assertFalse(result.weather.needsForecastUpgrade);
            assertEquals(WeatherRepository.ForecastTimeBasis.UTC, result.weather.forecastTimeBasis);
            assertEquals("UTC", result.weather.timezone); assertEquals("UTC", result.weather.forecastTimezone);
            assertEquals(fetched, result.weather.fetchedAtMillis); assertEquals(firstSample, result.weather.observedAtMillis);
            assertEquals(expires, result.weather.sourceExpiresAtMillis);
            assertEquals(3, result.weather.forecastDays(now).size());
            assertEquals("2026-10-25", result.weather.days.get(0).date); assertEquals("2026-10-27", result.weather.days.get(2).date);
            assertEquals(3, new JSONObject(cache.snapshot).getInt("forecast_schema"));
            assertEquals(fetched, raw.snapshot.fetchedAtMillis); assertEquals(expires, raw.snapshot.expiresAtMillis);
            assertEquals(body, raw.snapshot.body); assertEquals("unchanged-validator", raw.snapshot.lastModified);
            assertEquals(3, repository.loadCurrent(settings(), false).weather.forecastDays(now).size());
            assertEquals("The migrated cache is reused without a primary retry loop", 0, primary[0]);
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void oneFallbackResponsePersistsThreeDaysAndLaterOfflineFailureKeepsItsTimestampAndStaleStatus() throws Exception {
        long[] now = {START + 12 * HOUR};
        WeatherRepository.Weather met = parse(hourlyBody(START, 73, true, false), now[0], "UTC", "celsius");
        String[] saved = {null}; int[] fallbackCalls = {0}, primaryCalls = {0};
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) {
                try { return saved[0] == null ? null : WeatherRepository.CacheCodec.decode(saved[0]); }
                catch (JSONException failure) { throw new IllegalArgumentException(failure); }
            }
            @Override public void write(String key, WeatherRepository.Weather weather) {
                try { saved[0] = WeatherRepository.CacheCodec.encode(weather); }
                catch (JSONException failure) { throw new IllegalArgumentException(failure); }
            }
        };
        WeatherRepository repository = new WeatherRepository(url -> { primaryCalls[0]++; throw new IOException("offline"); },
                cache, new WeatherRepository.JsonDecoder(), () -> now[0], millis -> fail("One primary attempt with fallback"),
                (settings, timeout) -> {
                    fallbackCalls[0]++;
                    if (fallbackCalls[0] > 1) throw new IOException("offline");
                    return met;
                });
        WeatherRepository.WeatherResult first = repository.loadCurrent(settings(), false);
        assertEquals(WeatherRepository.Status.FRESH, first.status); assertEquals(3, first.weather.days.size());
        now[0] += 2 * HOUR;
        WeatherRepository.WeatherResult offline = repository.loadCurrent(settings(), false);
        assertEquals(WeatherRepository.Status.STALE, offline.status);
        assertEquals(WeatherRepository.Provider.MET_NORWAY, offline.weather.provider);
        assertEquals(met.fetchedAtMillis, offline.weather.fetchedAtMillis);
        assertEquals(met.observedAtMillis, offline.weather.observedAtMillis);
        assertEquals(3, offline.weather.forecastDays(now[0]).size());
        assertEquals(2, primaryCalls[0]); assertEquals(2, fallbackCalls[0]);
    }
}
