package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

/** Explicit API/cache fixtures exercise the production decoder with the test-only org.json runtime. */
public class WeatherJsonTest {
    private static final long NOW = 1800000000000L;
    private static JSONObject currentFixture() throws JSONException {
        return new JSONObject("{\"timezone\":\"Europe/Moscow\",\"current\":{\"time\":1800000000,"
                + "\"temperature_2m\":12.5,\"weather_code\":3,\"is_day\":1,\"wind_speed_10m\":8.4},"
                + "\"hourly\":{\"time\":[1799996400,1800000000,1800003600,1800007200,1800010800,1800014400,1800018000,1800021600],"
                + "\"temperature_2m\":[9,10,11,12,13,14,15,16],\"weather_code\":[0,1,2,3,45,61,71,80]},"
                + "\"daily\":{\"sunrise\":[1799978400,1800064800],\"sunset\":[1800021600,1800108000]}}");
    }
    @Test public void parsesActualCurrentProviderHoursAndSunTimes() throws Exception {
        WeatherRepository.Weather weather = new WeatherRepository.JsonDecoder().current(currentFixture().toString(), "celsius", NOW);
        assertEquals(12.5, weather.temperature, 0.001);
        assertEquals(8, weather.hours.size());
        assertEquals(NOW - 3600000, weather.hours.get(0).timeMillis);
        assertEquals(9, weather.hours.get(0).temp, 0.001);
        assertEquals(0, weather.hours.get(0).weatherCode);
        assertEquals(NOW + 3600000, weather.upcomingHours(NOW).get(0).timeMillis);
        assertEquals(1799978400000L, weather.sunriseMillis);
        assertEquals(1800021600000L, weather.sunsetMillis);
        assertEquals("Europe/Moscow", weather.timezone);
    }
    @Test public void malformedOrMissingForecastDoesNotInventForecastData() throws Exception {
        JSONObject fixture = currentFixture();
        fixture.remove("hourly"); fixture.remove("daily");
        WeatherRepository.Weather missing = new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", NOW);
        assertTrue(missing.hours.isEmpty());
        assertEquals(0, missing.sunriseMillis);
        assertEquals(0, missing.sunsetMillis);
        fixture.put("hourly", new JSONObject("{\"time\":[1800000000,1800003600],\"temperature_2m\":[12],\"weather_code\":[0,1]}"));
        assertTrue(new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", NOW).hours.isEmpty());
    }
    @Test public void nullAndInvalidHourlyValuesAreOmitted() throws Exception {
        JSONObject fixture = currentFixture();
        fixture.put("hourly", new JSONObject("{\"time\":[1800000000,1800003600,1800007200],"
                + "\"temperature_2m\":[null,500,7],\"weather_code\":[0,1,61]}"));
        WeatherRepository.Weather weather = new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", NOW);
        assertEquals(1, weather.hours.size());
        assertEquals(NOW + 7200000, weather.hours.get(0).timeMillis);
        assertEquals(7, weather.hours.get(0).temp, 0.001);
    }
    @Test public void hourlyCollectionIsSortedUniqueBoundedAndImmutable() {
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        for (int index = 20; index >= 0; index--) hours.add(new WeatherRepository.Hour(NOW + index * 3600000L, index, 0));
        hours.add(new WeatherRepository.Hour(NOW, 99, 0));
        WeatherRepository.Weather weather = new WeatherRepository.Weather(1, 0, true, 0, NOW, NOW,
                "celsius", hours, 0, 0, "GMT");
        hours.clear();
        assertEquals(21, weather.hours.size());
        assertEquals(NOW, weather.hours.get(0).timeMillis);
        assertEquals(NOW + 20 * 3600000L, weather.hours.get(20).timeMillis);
        try { weather.hours.clear(); fail("Forecast must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void completeForecastCacheRoundTripPreservesReturnedValues() throws Exception {
        WeatherRepository.Weather original = new WeatherRepository.JsonDecoder().current(currentFixture().toString(), "fahrenheit", NOW);
        WeatherRepository.Weather decoded = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(original));
        assertEquals(original.temperature, decoded.temperature, 0.001);
        assertEquals(original.units, decoded.units);
        assertEquals(original.fetchedAtMillis, decoded.fetchedAtMillis);
        assertEquals(original.observedAtMillis, decoded.observedAtMillis);
        assertEquals(original.sunriseMillis, decoded.sunriseMillis);
        assertEquals(original.sunsetMillis, decoded.sunsetMillis);
        assertEquals(original.timezone, decoded.timezone);
        assertEquals(original.hours.size(), decoded.hours.size());
        assertEquals(original.hours.get(5).temp, decoded.hours.get(5).temp, 0.001);
    }
    @Test public void hourlyDayNightPhaseComesOnlyFromTheProviderAndSurvivesCache() throws Exception {
        JSONObject fixture = currentFixture();
        fixture.getJSONObject("hourly").put("is_day", new org.json.JSONArray("[0,1,null,2,\"1\",0,1,0]"));
        WeatherRepository.Weather weather = new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", NOW);
        assertEquals(Boolean.FALSE, weather.hours.get(0).isDay); assertEquals(Boolean.TRUE, weather.hours.get(1).isDay);
        assertNull(weather.hours.get(2).isDay); assertNull(weather.hours.get(3).isDay); assertNull(weather.hours.get(4).isDay);
        WeatherRepository.Weather restored = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(weather));
        assertEquals(Boolean.FALSE, restored.hours.get(0).isDay); assertNull(restored.hours.get(2).isDay);
    }
    @Test public void legacyCurrentOnlyCacheRemainsValidWithoutForecast() throws Exception {
        WeatherRepository.Weather current = new WeatherRepository.Weather(12, 1, true, 5, NOW, NOW, "celsius");
        JSONObject snapshot = new JSONObject(WeatherRepository.CacheCodec.encode(current));
        snapshot.remove("hours"); snapshot.remove("sunrise"); snapshot.remove("sunset"); snapshot.remove("timezone");
        snapshot.remove("provider"); snapshot.remove("source_expires");
        WeatherRepository.Weather decoded = WeatherRepository.CacheCodec.decode(snapshot.toString());
        assertTrue(decoded.isValid());
        assertTrue(decoded.hours.isEmpty());
        assertEquals(0, decoded.sunriseMillis);
        assertEquals("GMT", decoded.timezone);
        assertEquals(WeatherRepository.Provider.OPEN_METEO, decoded.provider);
        assertEquals(0, decoded.sourceExpiresAtMillis);
    }
    @Test public void malformedRequiredCurrentValuesDoNotBecomeFakeWeather() throws Exception {
        JSONObject fixture = currentFixture();
        fixture.getJSONObject("current").put("temperature_2m", JSONObject.NULL);
        try {
            new WeatherRepository.JsonDecoder().current(fixture.toString(), "celsius", NOW);
            fail("Missing current temperature must fail rather than becoming zero");
        } catch (JSONException expected) { }
        assertTrue(new WeatherRepository.JsonDecoder().cities("{\"results\":[]}").isEmpty());
    }
}
