package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Provider/cache contracts: local dates, missing measurements and saved-response age. */
public class WeatherForecastTest {
    private static final long NOW = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;

    private static JSONObject fixture(long now, String timezone, int count) throws JSONException {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        int offset = zone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds();
        JSONObject daily = new JSONObject();
        String[] fields = {"time", "weather_code", "temperature_2m_min", "temperature_2m_max", "precipitation_sum",
                "precipitation_probability_max", "wind_speed_10m_max", "wind_gusts_10m_max", "uv_index_max", "sunrise", "sunset"};
        for (String field : fields) daily.put(field, new JSONArray());
        for (int index = 0; index < count; index++) {
            long start = first.plusDays(index).atStartOfDay().atOffset(ZoneOffset.ofTotalSeconds(offset)).toEpochSecond();
            daily.getJSONArray("time").put(start);
            daily.getJSONArray("weather_code").put(index == 1 ? 61 : 3);
            daily.getJSONArray("temperature_2m_min").put(5 + index);
            daily.getJSONArray("temperature_2m_max").put(15 + index);
            daily.getJSONArray("precipitation_sum").put(index * 1.5);
            daily.getJSONArray("precipitation_probability_max").put(index * 35);
            daily.getJSONArray("wind_speed_10m_max").put(10 + index);
            daily.getJSONArray("wind_gusts_10m_max").put(20 + index);
            daily.getJSONArray("uv_index_max").put(index + .5);
            daily.getJSONArray("sunrise").put(start + 6 * 3600);
            daily.getJSONArray("sunset").put(start + 18 * 3600);
        }
        return new JSONObject().put("timezone", timezone).put("utc_offset_seconds", offset)
                .put("current", new JSONObject().put("time", now / 1000).put("temperature_2m", 12.5)
                        .put("weather_code", 3).put("is_day", 1).put("wind_speed_10m", 8.4))
                .put("daily", daily).put("daily_units", new JSONObject().put("time", "unixtime")
                        .put("weather_code", "wmo code").put("temperature_2m_min", "°C").put("temperature_2m_max", "°C")
                        .put("precipitation_sum", "mm").put("precipitation_probability_max", "%")
                        .put("wind_speed_10m_max", "km/h").put("wind_gusts_10m_max", "km/h").put("uv_index_max", ""));
    }
    private static WeatherRepository.Weather parse(JSONObject fixture, long now) throws JSONException {
        return new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", now);
    }
    private static LauncherPrefs.Settings settings() {
        Map<String, Object> values = new HashMap<>();
        values.put("weather_enabled", true); values.put("city_name", "Fixture city");
        values.put("city_latitude", 55.75d); values.put("city_longitude", 37.62d); values.put("units", "celsius");
        return LauncherPrefs.decode(values);
    }
    private static final class SnapshotCache implements WeatherRepository.Cache {
        String snapshot;
        int writes;
        SnapshotCache(String snapshot) { this.snapshot = snapshot; }
        @Override public WeatherRepository.Weather read(String key) {
            try { return WeatherRepository.CacheCodec.decode(snapshot); }
            catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        @Override public void write(String key, WeatherRepository.Weather weather) {
            try { snapshot = WeatherRepository.CacheCodec.encode(weather); writes++; }
            catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
    }
    private static String legacySnapshot() throws JSONException {
        JSONObject snapshot = new JSONObject(WeatherRepository.CacheCodec.encode(parse(fixture(NOW, "Europe/Moscow", 3), NOW)));
        snapshot.remove("forecast_schema"); snapshot.remove("days"); snapshot.remove("city_timezone_known");
        return snapshot.toString();
    }

    @Test public void realDailyMeasuresKeepTheirDateUnitsAndZeroPrecipitation() throws Exception {
        WeatherRepository.Weather weather = parse(fixture(NOW, "Europe/Moscow", 3), NOW);
        List<WeatherRepository.Day> days = weather.forecastDays(NOW);
        assertEquals(3, days.size());
        WeatherRepository.Day day = days.get(1);
        assertEquals("2026-10-25", day.date);
        assertEquals(61, day.weatherCode);
        assertEquals(6, day.minTemperature, .001); assertEquals(16, day.maxTemperature, .001);
        assertEquals(1.5, day.precipitationMm, .001); assertEquals(35, day.precipitationProbabilityMax, .001);
        assertEquals(11, day.windSpeedMax, .001); assertEquals(21, day.windGustsMax, .001);
        assertEquals(1.5, day.uvIndexMax, .001);
        assertFalse(day.aggregated); assertFalse(day.partialDay);
        assertEquals(day.startMillis, day.coverageStartMillis); assertEquals(day.endMillis, day.coverageEndMillis);
        assertEquals(0, days.get(0).precipitationMm, 0);
        assertEquals(12.5, weather.temperature, .001);
        assertThrows(UnsupportedOperationException.class, () -> days.clear());
    }

    @Test public void nullInvalidAndMismatchedOptionalValuesDoNotTurnIntoZeros() throws Exception {
        JSONObject body = fixture(NOW, "Europe/Moscow", 3);
        JSONObject daily = body.getJSONObject("daily");
        daily.put("precipitation_probability_max", new JSONArray().put(80)); // Wrong parallel-array length.
        daily.put("precipitation_sum", new JSONArray("[null,-1,\"2\"]"));
        daily.put("weather_code", new JSONArray("[null,400,61]"));
        daily.put("temperature_2m_min", new JSONArray("[null,500,20]"));
        daily.put("temperature_2m_max", new JSONArray("[null,16,10]"));
        body.getJSONObject("daily_units").put("wind_speed_10m_max", "mph").put("uv_index_max", "W/m²");
        WeatherRepository.Weather weather = parse(body, NOW);
        assertTrue(weather.isValid()); assertEquals(3, weather.days.size());
        for (WeatherRepository.Day day : weather.days) {
            assertTrue(Double.isNaN(day.precipitationMm)); assertTrue(Double.isNaN(day.precipitationProbabilityMax));
            assertTrue(Double.isNaN(day.windSpeedMax)); assertTrue(Double.isNaN(day.uvIndexMax));
        }
        assertEquals(-1, weather.days.get(0).weatherCode); assertEquals(-1, weather.days.get(1).weatherCode);
        assertTrue(Double.isNaN(weather.days.get(0).minTemperature));
        assertTrue(Double.isNaN(weather.days.get(0).maxTemperature));
        assertEquals(20, weather.days.get(0).windGustsMax, .001); // This is still a real available forecast measurement.
        assertTrue(Double.isNaN(weather.days.get(2).minTemperature));
        assertTrue(Double.isNaN(weather.days.get(2).maxTemperature));
        daily.remove("wind_gusts_10m_max"); // Remove the first day's only remaining weather measurement.
        WeatherRepository.Weather withoutGust = parse(body, NOW);
        assertEquals(2, withoutGust.days.size());
        assertEquals("2026-10-25", withoutGust.days.get(0).date); // No empty placeholder or fabricated zero for the 24th.
    }

    @Test public void invalidTimezoneOrUnorderedDailyTimesLeaveCurrentWeatherAvailable() throws Exception {
        for (Object timezone : new Object[]{"Invalid/Timezone", JSONObject.NULL, 123}) {
            JSONObject body = fixture(NOW, "Europe/Moscow", 3).put("timezone", timezone);
            WeatherRepository.Weather weather = parse(body, NOW);
            assertTrue(weather.isValid()); assertFalse(weather.cityTimezoneKnown); assertTrue(weather.days.isEmpty());
        }
        JSONObject body = fixture(NOW, "Europe/Moscow", 3);
        JSONArray times = body.getJSONObject("daily").getJSONArray("time");
        times.put(1, times.getLong(0));
        WeatherRepository.Weather weather = parse(body, NOW);
        assertTrue(weather.isValid()); assertTrue(weather.days.isEmpty());
    }

    @Test public void cacheRoundTripPreservesMissingFieldsAndSourceCoverage() throws Exception {
        JSONObject body = fixture(NOW, "Europe/Moscow", 3);
        body.getJSONObject("daily").remove("precipitation_probability_max");
        WeatherRepository.Weather original = parse(body, NOW);
        WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(original));
        assertEquals(3, copy.days.size());
        assertTrue(Double.isNaN(copy.days.get(1).precipitationProbabilityMax));
        assertEquals(original.days.get(1).sunriseMillis, copy.days.get(1).sunriseMillis);
        assertEquals(original.days.get(1).coverageEndMillis, copy.days.get(1).coverageEndMillis);
        assertEquals(original.days.get(1).windGustsMax, copy.days.get(1).windGustsMax, 0);
        assertThrows(UnsupportedOperationException.class, () -> copy.days.clear());
    }

    @Test public void legacyUnknownAndCorruptForecastSchemasNeverInventDailyData() throws Exception {
        JSONObject snapshot = new JSONObject(WeatherRepository.CacheCodec.encode(parse(fixture(NOW, "UTC", 3), NOW)));
        JSONObject nullTimezone = new JSONObject(snapshot.toString()).put("timezone", JSONObject.NULL);
        WeatherRepository.Weather unknownTimezone = WeatherRepository.CacheCodec.decode(nullTimezone.toString());
        assertTrue(unknownTimezone.isValid()); assertFalse(unknownTimezone.cityTimezoneKnown);
        assertTrue(unknownTimezone.days.isEmpty());
        snapshot.remove("forecast_schema");
        assertTrue(WeatherRepository.CacheCodec.decode(snapshot.toString()).days.isEmpty());
        snapshot.put("forecast_schema", 99);
        assertTrue(WeatherRepository.CacheCodec.decode(snapshot.toString()).isValid());
        assertTrue(WeatherRepository.CacheCodec.decode(snapshot.toString()).days.isEmpty());
        snapshot.put("forecast_schema", 1);
        snapshot.getJSONArray("days").getJSONObject(0).put("date", "2026-10-24junk");
        snapshot.getJSONArray("days").getJSONObject(1).put("aggregated", true); // Wrong provider semantics.
        snapshot.getJSONArray("days").getJSONObject(2).put("coverage_start", 0);
        WeatherRepository.Weather decoded = WeatherRepository.CacheCodec.decode(snapshot.toString());
        assertTrue(decoded.isValid()); assertTrue(decoded.days.isEmpty());
        assertThrows(JSONException.class, () -> WeatherRepository.CacheCodec.decode(" ".repeat(32_769)));
    }

    @Test public void offlineMidnightRemovesYesterdayWithoutRelabelingTomorrowAndExpiredCacheHasNoDays() throws Exception {
        long beforeMidnight = Instant.parse("2026-10-24T20:30:00Z").toEpochMilli(); // 23:30 Moscow.
        WeatherRepository.Weather saved = parse(fixture(beforeMidnight, "Europe/Moscow", 3), beforeMidnight);
        List<WeatherRepository.Day> visible = saved.forecastDays(beforeMidnight + HOUR);
        assertEquals(2, visible.size()); assertEquals("2026-10-25", visible.get(0).date);
        assertEquals(WeatherPolicy.CacheAge.STALE, WeatherRepository.cacheAge(saved, beforeMidnight + HOUR));
        assertTrue(saved.forecastDays(beforeMidnight - 1).isEmpty());
        assertTrue(saved.forecastDays(beforeMidnight + WeatherPolicy.MAX_STALE_MILLIS + 1).isEmpty());
    }

    @Test public void schemaOneKnownCityForecastRemainsAvailableDuringTheBoundedContractUpgrade() throws Exception {
        WeatherRepository.Weather original = parse(fixture(NOW, "Europe/Moscow", 3), NOW);
        JSONObject snapshot = new JSONObject(WeatherRepository.CacheCodec.encode(original)).put("forecast_schema", 1);
        snapshot.remove("forecast_time_basis"); snapshot.remove("forecast_timezone");
        WeatherRepository.Weather copy = WeatherRepository.CacheCodec.decode(snapshot.toString());
        assertTrue(copy.needsForecastUpgrade); assertTrue(copy.cityTimezoneKnown);
        assertEquals(WeatherRepository.ForecastTimeBasis.CITY, copy.forecastTimeBasis);
        assertEquals("Europe/Moscow", copy.forecastTimezone); assertEquals(3, copy.forecastDays(NOW).size());
        assertEquals(original.fetchedAtMillis, copy.fetchedAtMillis); assertEquals(original.observedAtMillis, copy.observedAtMillis);
    }

    @Test public void dstKeepsProvider24HourCoverageSeparateFromTheLocalCalendarDay() throws Exception {
        long now = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli();
        WeatherRepository.Weather weather = parse(fixture(now, "Europe/Berlin", 3), now);
        WeatherRepository.Day spring = weather.days.get(1);
        assertEquals("2026-03-29", spring.date);
        assertEquals(23 * HOUR, spring.endMillis - spring.startMillis);
        assertEquals(24 * HOUR, spring.coverageEndMillis - spring.coverageStartMillis);
        assertTrue(spring.partialDay);
        assertEquals("2026-03-30", weather.days.get(2).date);
        assertTrue(weather.days.get(2).partialDay); // The request-time source offset remains CET.
    }

    @Test public void onlyThreeDaysAreRetainedAndPlainCurrentOnlyModelDoesNotTriggerAnExtraRequest() throws Exception {
        assertEquals(3, parse(fixture(NOW, "UTC", 8), NOW).days.size());
        int[] requests = {0};
        WeatherRepository.Weather legacy = new WeatherRepository.Weather(12, 3, true, 4, NOW, NOW, "celsius");
        assertFalse("Existing public models do not opt into cache migration", legacy.needsForecastUpgrade);
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return legacy; }
            @Override public void write(String key, WeatherRepository.Weather weather) { }
        };
        WeatherRepository repository = new WeatherRepository(url -> { requests[0]++; return "invalid"; }, cache,
                new WeatherRepository.JsonDecoder(), () -> NOW);
        WeatherRepository.WeatherResult result = repository.loadCurrent(settings(), false);
        assertEquals(WeatherRepository.Status.FRESH, result.status);
        assertTrue(result.weather.forecastDays(NOW).isEmpty()); assertEquals(0, requests[0]);
    }

    @Test public void freshLegacyCacheRequestsTheNewContractOnceAndSuccessfulCurrentOnlyResponseDoesNotPoll() throws Exception {
        for (boolean hasDaily : new boolean[]{true, false}) {
            SnapshotCache cache = new SnapshotCache(legacySnapshot());
            WeatherRepository.Weather original = cache.read("fixture");
            assertTrue(original.needsForecastUpgrade); assertTrue(original.days.isEmpty());
            assertEquals(WeatherPolicy.CacheAge.FRESH, WeatherRepository.cacheAge(original, NOW));
            JSONObject next = fixture(NOW, "Europe/Moscow", 3);
            if (!hasDaily) next.remove("daily");
            int[] requests = {0};
            WeatherRepository repository = new WeatherRepository(url -> { requests[0]++; return next.toString(); }, cache,
                    new WeatherRepository.JsonDecoder(), () -> NOW, millis -> fail("The successful migration needs no retry"));
            WeatherRepository.WeatherResult result = repository.loadCurrent(settings(), false);
            assertEquals(1, requests[0]); assertEquals(1, cache.writes);
            assertEquals(WeatherRepository.Status.FRESH, result.status); assertFalse(result.weather.needsForecastUpgrade);
            assertEquals(hasDaily ? 3 : 0, result.weather.days.size());
            assertFalse(cache.read("fixture").needsForecastUpgrade);
            WeatherRepository.WeatherResult reused = repository.loadCurrent(settings(), false);
            assertEquals(WeatherRepository.Status.FRESH, reused.status); assertEquals(hasDaily ? 3 : 0, reused.weather.days.size());
            assertEquals("The new contract, including honest missing daily data, is reusable", 1, requests[0]);
            assertEquals(1, cache.writes);
        }
    }

    @Test public void failedLegacyUpgradeKeepsCurrentWeatherAndUsesTheExistingCooldownBeforeRetry() throws Exception {
        SnapshotCache cache = new SnapshotCache(legacySnapshot());
        long[] now = {NOW}; int[] requests = {0}; boolean[] offline = {true};
        String successful = fixture(NOW, "Europe/Moscow", 3).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            requests[0]++; if (offline[0]) throw new IOException("controlled offline upgrade"); return successful;
        }, cache, new WeatherRepository.JsonDecoder(), () -> now[0], millis -> { });
        WeatherRepository.WeatherResult delayed = repository.loadCurrent(settings(), false);
        assertEquals(WeatherRepository.Status.STALE, delayed.status);
        assertEquals(12.5, delayed.weather.temperature, .001); assertEquals(NOW, delayed.weather.fetchedAtMillis);
        assertTrue(delayed.weather.needsForecastUpgrade); assertTrue(delayed.weather.days.isEmpty());
        assertEquals("The normal bounded primary retry policy is preserved", 2, requests[0]); assertEquals(0, cache.writes);
        now[0] += 1000;
        assertEquals(WeatherRepository.Status.STALE, repository.loadCurrent(settings(), false).status);
        assertEquals("Repeated resume/data calls respect cooldown", 2, requests[0]);
        now[0] = delayed.retryAtMillis; offline[0] = false;
        WeatherRepository.WeatherResult recovered = repository.loadCurrent(settings(), false);
        assertEquals(WeatherRepository.Status.FRESH, recovered.status); assertEquals(3, recovered.weather.days.size());
        assertFalse(recovered.weather.needsForecastUpgrade); assertEquals(3, requests[0]); assertEquals(1, cache.writes);
        repository.loadCurrent(settings(), false);
        assertEquals("Recovery is cached without a migration polling loop", 3, requests[0]);
    }

    @Test public void primaryRefreshRequestsThreeDaysWithExplicitMeasurementUnitsOnlyOnce() throws Exception {
        int[] requests = {0};
        WeatherRepository.Cache empty = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather weather) { assertEquals(3, weather.days.size()); }
        };
        String response = fixture(NOW, "Europe/Moscow", 3).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            requests[0]++;
            assertTrue(url.contains("forecast_days=3")); assertTrue(url.contains("precipitation_unit=mm"));
            assertTrue(url.contains("precipitation_probability_max")); assertTrue(url.contains("wind_speed_unit=kmh"));
            return response;
        }, empty, new WeatherRepository.JsonDecoder(), () -> NOW);
        assertEquals(WeatherRepository.Status.FRESH, repository.loadCurrent(settings(), false).status);
        assertEquals(1, requests[0]);
    }
}
