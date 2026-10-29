package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import io.github.ekkzy.evebox.core.WeatherPolicy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CityTimezoneMetadataTest {
    private static final long HOUR = 3_600_000L;
    private static final long NOW = Instant.parse("2026-10-25T09:00:00Z").toEpochMilli();
    private static final double LAT = 55.75222, LON = 37.61556;
    private static LauncherPrefs preferences(SettingsBackupTest.MemoryPreferences store) {
        store.values.put("weather_enabled", true); store.values.put("city_name", "Moscow");
        store.values.put("city_latitude", Double.toString(LAT)); store.values.put("city_longitude", Double.toString(LON));
        store.values.put("units", "celsius");
        return new LauncherPrefs(store);
    }
    private static JSONObject city(String id, double latitude, String timezone) throws Exception {
        return new JSONObject().put("id", Integer.parseInt(id)).put("name", "Moscow")
                .put("latitude", latitude).put("longitude", LON).put("timezone", timezone);
    }
    private static WeatherRepository.Weather unknown(long now) {
        return new WeatherRepository.Weather(8, 3, true, 9, now, now, "celsius", null, 0, 0, "UTC",
                WeatherRepository.Provider.MET_NORWAY, now + HOUR, Collections.emptyList(), false,
                WeatherRepository.ForecastTimeBasis.UTC);
    }
    private static final class Cache implements WeatherRepository.Cache {
        String snapshot;
        @Override public WeatherRepository.Weather read(String key) {
            try { return snapshot == null ? null : WeatherRepository.CacheCodec.decode(snapshot); }
            catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
        }
        @Override public void write(String key, WeatherRepository.Weather weather) {
            try { snapshot = WeatherRepository.CacheCodec.encode(weather); }
            catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
        }
    }
    @Test public void selectedMetadataIsAtomicBoundToCityAndExcludedFromPortableBackup() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        String before = SettingsBackup.exportJson(prefs.read());
        prefs.setCity("Moscow", LAT, LON, "524901", "Europe/Moscow");
        assertEquals(1, store.applyCount); assertEquals("524901", prefs.read().cityId);
        assertEquals("Europe/Moscow", prefs.read().cityTimezone);
        assertEquals(before, SettingsBackup.exportJson(prefs.read()));
        JSONObject exported=new JSONObject(before).getJSONObject("settings");
        assertEquals(21, exported.length());
        assertTrue(exported.has("recommendationsEnabled"));assertTrue(exported.has("recommendationSourceOrder"));
        assertFalse(exported.has("cityId"));assertFalse(exported.has("cityTimezone"));
        store.values.put("city_latitude", Double.toString(LAT + .01));
        assertTrue(prefs.read().hasCity()); assertEquals("", prefs.read().cityId); assertEquals("", prefs.read().cityTimezone);
        prefs.setCity("Moscow", LAT, LON, "524901", "+03:00");
        assertEquals("", prefs.read().cityTimezone); assertTrue(prefs.read().hasCity());
        prefs.setCity("Moscow", LAT, LON, "524901", "Europe/Moscow");
        prefs.setCity("Other city", LAT + 1, LON);
        assertEquals("", prefs.read().cityId); assertEquals("", prefs.read().cityTimezone);
    }
    @Test public void oldSettingsAndImportsKeepTheCityAndCannotReuseAnotherCitysMetadata() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        assertTrue(prefs.read().hasCity()); assertEquals("", prefs.read().cityTimezone);
        prefs.setCity("Moscow", LAT, LON, "524901", "Europe/Moscow");
        LauncherPrefs.Settings original = prefs.read();
        assertTrue(SettingsBackup.apply(prefs, SettingsBackup.preview(SettingsBackup.exportJson(original))));
        assertEquals("Europe/Moscow", prefs.read().cityTimezone);
        Map<String, Object> imported = new HashMap<>(store.values);
        imported.put("city_name", "Other city"); imported.put("city_latitude", LAT + 1);
        assertTrue(SettingsBackup.apply(prefs, SettingsBackup.preview(SettingsBackup.exportJson(LauncherPrefs.decode(imported)))));
        assertEquals("Other city", prefs.read().cityName); assertEquals("", prefs.read().cityTimezone);
        int writes = store.applyCount;
        assertFalse(prefs.rememberCityMetadata(original, "524901", "Europe/Moscow"));
        assertEquals(writes, store.applyCount);
    }
    @Test public void legacyLookupUsesTheUniqueSameNameRecordRatherThanTheFirstRemoteResult() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        String body = new JSONObject().put("results", new JSONArray()
                .put(city("1", LAT + .01, "Asia/Tokyo")).put(city("524901", LAT, "Europe/Moscow"))).toString();
        int[] lookup = {0}; String[] hint = {null};
        WeatherRepository.Transport transport = new WeatherRepository.Transport() {
            @Override public String get(String url) throws IOException { throw new IOException("Use bounded request"); }
            @Override public String get(String url, int timeout) throws IOException {
                if (!url.contains("geocoding-api")) throw new IOException("Primary offline");
                assertEquals("The city lookup gets four seconds within the shared request deadline",4_000,timeout);
                assertTrue(url.contains("count=100"));
                lookup[0]++; return body;
            }
        };
        WeatherRepository.Fallback fallback = new WeatherRepository.Fallback() {
            @Override public WeatherRepository.Weather load(LauncherPrefs.Settings s, int timeout) { return unknown(NOW); }
            @Override public WeatherRepository.Weather load(LauncherPrefs.Settings s, int timeout, String zone) {
                hint[0] = zone; return unknown(NOW);
            }
        };
        WeatherRepository repository = new WeatherRepository(transport, new Cache(), new WeatherRepository.JsonDecoder(),
                () -> NOW, millis -> fail("Single primary attempt"), fallback, prefs::rememberCityMetadata);
        assertNotNull(repository.loadCurrent(prefs.read(), true).weather);
        assertEquals(1, lookup[0]); assertEquals("Europe/Moscow", hint[0]);
        assertEquals("524901", prefs.read().cityId); assertEquals("Europe/Moscow", prefs.read().cityTimezone);
    }
    @Test public void knownGeonameIdToleratesAnUpdatedCentroidAndName() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        prefs.setCity("Moscow", LAT, LON, "524901", "");
        String body = city("524901", LAT + .002, "Europe/Moscow").put("name", "Updated place name").toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.contains("geocoding-api")) { assertTrue(url.contains("get?id=524901")); return body; }
            throw new IOException("Primary offline");
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
        assertNotNull(repository.loadCurrent(prefs.read(), true).weather);
        assertEquals("Europe/Moscow", prefs.read().cityTimezone); assertEquals(LAT, prefs.read().cityLatitude, 0);
    }
    @Test public void legacyCentroidDriftWithinOneKilometreRequiresTheSameNormalizedName() throws Exception {
        for (boolean sameName : new boolean[]{true, false}) {
            SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
            LauncherPrefs prefs = preferences(store);
            JSONObject candidate = city("524901", LAT + .002, "Europe/Moscow")
                    .put("name", sameName ? "  MoScOw  " : "Another place");
            String body = new JSONObject().put("results", new JSONArray().put(candidate)).toString();
            WeatherRepository repository = new WeatherRepository(url -> {
                if (url.contains("geocoding-api")) return body;
                throw new IOException("Primary offline");
            }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                    (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
            repository.loadCurrent(prefs.read(), true);
            assertEquals(sameName ? "Europe/Moscow" : "", prefs.read().cityTimezone);
            assertEquals(LAT, prefs.read().cityLatitude, 0);
        }
    }
    @Test public void sameNamedRemoteCityCannotSupplyTimezoneForTheLegacySelection() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        String body = new JSONObject().put("results", new JSONArray().put(city("524901", LAT + .02, "Asia/Tokyo"))).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.contains("geocoding-api")) return body;
            throw new IOException("Primary offline");
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
        repository.loadCurrent(prefs.read(), true);
        assertEquals("", prefs.read().cityTimezone); assertEquals(0, store.applyCount);
    }
    @Test public void changingTheCityDuringLookupRejectsTheCallbackBeforeWeatherRequestsOrWrites() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        String body = new JSONObject().put("results", new JSONArray().put(city("524901", LAT, "Europe/Moscow"))).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            assertTrue("Weather request must stop after the selection changes", url.contains("geocoding-api"));
            prefs.setCity("Other city", LAT + 1, LON);
            return body;
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> { fail("Cancelled selection must not contact MET"); return null; }, prefs::rememberCityMetadata);
        assertEquals(WeatherRepository.Failure.CANCELLED, repository.loadCurrent(prefs.read(), true).failure);
        assertEquals("Other city", prefs.read().cityName); assertEquals("", prefs.read().cityTimezone);
        assertEquals(1, store.applyCount);
    }
    @Test public void ambiguousLookupKeepsTheLegacyCityAndBacksOffWithoutGuessing() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store); int[] lookups = {0};
        String body = new JSONObject().put("results", new JSONArray().put(city("1", LAT, "Asia/Tokyo"))
                .put(city("524901", LAT, "Europe/Moscow"))).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.contains("geocoding-api")) { lookups[0]++; return body; }
            throw new IOException("Primary offline");
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
        repository.loadCurrent(prefs.read(), false); repository.loadCurrent(prefs.read(), false);
        assertEquals(1, lookups[0]); assertTrue(prefs.read().hasCity());
        assertEquals("", prefs.read().cityTimezone); assertEquals(0, store.applyCount);
    }
    @Test public void cancelledLookupDoesNotWriteAndCanResumeWithoutARetrySuppression() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store); int[] lookups = {0};
        String body = new JSONObject().put("results", new JSONArray().put(city("524901", LAT, "Europe/Moscow"))).toString();
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.contains("geocoding-api")) {
                if (++lookups[0] == 1) Thread.currentThread().interrupt();
                return body;
            }
            throw new IOException("Primary offline");
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
        try {
            assertEquals(WeatherRepository.Failure.CANCELLED, repository.loadCurrent(prefs.read(), true).failure);
            assertEquals(0, store.applyCount);
        } finally { Thread.interrupted(); }
        assertNotNull(repository.loadCurrent(prefs.read(), false).weather);
        assertEquals(2, lookups[0]); assertEquals("Europe/Moscow", prefs.read().cityTimezone);
    }
    @Test public void explicitRefreshRetriesBothTransientFailureAndNoMatchBeforeTheirBackoffExpires() throws Exception {
        String body = new JSONObject().put("results",new JSONArray().put(city("524901",LAT,"Europe/Moscow"))).toString();
        for(boolean timeout:new boolean[]{true,false}){
            SettingsBackupTest.MemoryPreferences store=new SettingsBackupTest.MemoryPreferences();
            LauncherPrefs prefs=preferences(store);int[] lookups={0};
            WeatherRepository repository=new WeatherRepository(url->{
                if(url.contains("geocoding-api")){
                    if(++lookups[0]==1){
                        if(timeout)throw new SocketTimeoutException("First lookup timed out");
                        return "{}";
                    }
                    return body;
                }
                throw new IOException("Primary offline");
            },new Cache(),new WeatherRepository.JsonDecoder(),()->NOW,millis->{},
                    (settings,budget)->unknown(NOW),prefs::rememberCityMetadata);
            assertNotNull(repository.loadCurrent(prefs.read(),false).weather);
            repository.loadCurrent(prefs.read(),false);
            assertEquals("An automatic refresh respects the lookup backoff",1,lookups[0]);
            repository.loadCurrent(prefs.read(),true);
            assertEquals("An explicit refresh retries city metadata before the gate expires",2,lookups[0]);
            assertEquals("Europe/Moscow",prefs.read().cityTimezone);
            repository.loadCurrent(prefs.read(),true);
            assertEquals("A resolved metadata hint never needs another lookup",2,lookups[0]);
        }
    }
    @Test public void timeoutNetworkAndMalformedMetadataRetryAfterFifteenMinutesInsteadOfSixHours() throws Exception {
        String body=new JSONObject().put("results",new JSONArray().put(city("524901",LAT,"Europe/Moscow"))).toString();
        for(int mode=0;mode<3;mode++){
            int failureMode=mode;
            SettingsBackupTest.MemoryPreferences store=new SettingsBackupTest.MemoryPreferences();
            LauncherPrefs prefs=preferences(store);int[] lookups={0};long[] now={NOW};
            WeatherRepository repository=new WeatherRepository(url->{
                if(url.contains("geocoding-api")){
                    if(++lookups[0]==1){
                        if(failureMode==0)throw new SocketTimeoutException("Timeout");
                        if(failureMode==1)throw new IOException("Offline");
                        return "{not-json";
                    }
                    return body;
                }
                throw new IOException("Primary offline");
            },new Cache(),new WeatherRepository.JsonDecoder(),()->now[0],millis->{},
                    (settings,budget)->unknown(now[0]),prefs::rememberCityMetadata);
            repository.loadCurrent(prefs.read(),false);
            now[0]=NOW+15*60_000L-1;
            repository.loadCurrent(prefs.read(),false);
            assertEquals(1,lookups[0]);assertEquals("",prefs.read().cityTimezone);
            now[0]++;
            repository.loadCurrent(prefs.read(),false);
            assertEquals("The automatic retry runs at the transient deadline",2,lookups[0]);
            assertEquals("Europe/Moscow",prefs.read().cityTimezone);
        }
    }
    @Test public void identityNoMatchRetainsItsSixHourAutomaticBackoff() throws Exception {
        String body=new JSONObject().put("results",new JSONArray().put(city("524901",LAT,"Europe/Moscow"))).toString();
        SettingsBackupTest.MemoryPreferences store=new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs=preferences(store);int[] lookups={0};long[] now={NOW};
        WeatherRepository repository=new WeatherRepository(url->{
            if(url.contains("geocoding-api"))return ++lookups[0]==1?"{}":body;
            throw new IOException("Primary offline");
        },new Cache(),new WeatherRepository.JsonDecoder(),()->now[0],millis->{},
                (settings,budget)->unknown(now[0]),prefs::rememberCityMetadata);
        repository.loadCurrent(prefs.read(),false);
        now[0]=NOW+6*HOUR-1;
        repository.loadCurrent(prefs.read(),false);
        assertEquals(1,lookups[0]);assertEquals("",prefs.read().cityTimezone);
        now[0]++;
        repository.loadCurrent(prefs.read(),false);
        assertEquals(2,lookups[0]);assertEquals("Europe/Moscow",prefs.read().cityTimezone);
    }
    @Test public void interruptionReportedAsSocketTimeoutDoesNotSuppressTheNextAutomaticLookup() throws Exception {
        String body=new JSONObject().put("results",new JSONArray().put(city("524901",LAT,"Europe/Moscow"))).toString();
        SettingsBackupTest.MemoryPreferences store=new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs=preferences(store);int[] lookups={0};int[] fallbackCalls={0};
        WeatherRepository repository=new WeatherRepository(url->{
            if(url.contains("geocoding-api")){
                if(++lookups[0]==1){Thread.currentThread().interrupt();throw new SocketTimeoutException("Interrupted platform I/O");}
                return body;
            }
            throw new IOException("Primary offline");
        },new Cache(),new WeatherRepository.JsonDecoder(),()->NOW,millis->{},
                (settings,budget)->{fallbackCalls[0]++;return unknown(NOW);},prefs::rememberCityMetadata);
        try{
            assertEquals(WeatherRepository.Failure.CANCELLED,repository.loadCurrent(prefs.read(),false).failure);
            assertEquals(0,store.applyCount);assertEquals(0,fallbackCalls[0]);
        }finally{Thread.interrupted();}
        assertNotNull(repository.loadCurrent(prefs.read(),false).weather);
        assertEquals(2,lookups[0]);assertEquals(1,fallbackCalls[0]);
        assertEquals("Europe/Moscow",prefs.read().cityTimezone);
    }
    @Test public void lookupAndPrimaryKeepOneFifteenSecondBudgetForTheFallback() throws Exception {
        String body=new JSONObject().put("results",new JSONArray().put(city("524901",LAT,"Europe/Moscow"))).toString();
        SettingsBackupTest.MemoryPreferences store=new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs=preferences(store);int[] requests={0};int[] fallbackBudget={0};
        WeatherRepository.Transport transport=new WeatherRepository.Transport(){
            @Override public String get(String url)throws IOException{throw new AssertionError("Use the shared bounded transport");}
            @Override public String get(String url,int budget)throws IOException{
                requests[0]++;
                if(url.contains("geocoding-api")){
                    assertEquals(4_000,budget);
                    try{Thread.sleep(40);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                    return body;
                }
                assertEquals("The existing primary budget is unchanged",5_000,budget);
                throw new SocketTimeoutException("Primary timed out");
            }
        };
        WeatherRepository repository=new WeatherRepository(transport,new Cache(),new WeatherRepository.JsonDecoder(),()->NOW,
                millis->fail("The combined request cannot acquire another retry budget"),
                (settings,budget)->{fallbackBudget[0]=budget;return unknown(NOW);},prefs::rememberCityMetadata);
        assertNotNull(repository.loadCurrent(prefs.read(),true).weather);
        assertEquals(2,requests[0]);
        assertTrue("MET receives the remainder rather than a new fifteen seconds",fallbackBudget[0]>0&&fallbackBudget[0]<=14_970);
    }
    @Test public void geocoderTimeoutStillAllowsTheWeatherFallback() {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        WeatherRepository repository = new WeatherRepository(url -> {
            if (url.contains("geocoding-api")) throw new SocketTimeoutException("Metadata unavailable");
            throw new IOException("Primary offline");
        }, new Cache(), new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { },
                (settings, timeout) -> unknown(NOW), prefs::rememberCityMetadata);
        WeatherRepository.WeatherResult result = repository.loadCurrent(prefs.read(), true);
        assertEquals(WeatherRepository.Failure.NONE, result.failure); assertNotNull(result.weather);
        assertFalse(result.weather.cityTimezoneKnown); assertEquals("UTC", result.weather.timezone);
    }
    @Test public void expiredSameCityWeatherCanRestoreTimezoneMetadataWithoutBecomingDisplayable() throws Exception {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store); Cache cache = new Cache();
        long expired = NOW - WeatherPolicy.MAX_STALE_MILLIS - HOUR;
        cache.write("", new WeatherRepository.Weather(8, 3, true, 9, expired, expired, "celsius", null,
                0, 0, "Europe/Moscow", WeatherRepository.Provider.OPEN_METEO, 0, Collections.emptyList(), true));
        WeatherRepository repository = new WeatherRepository(url -> { throw new IOException("Offline"); }, cache,
                new WeatherRepository.JsonDecoder(), () -> NOW, millis -> { }, null, prefs::rememberCityMetadata);
        assertNull(repository.loadCurrent(prefs.read(), true).weather);
        assertEquals("Europe/Moscow", prefs.read().cityTimezone);
    }
    @Test public void freshUtcCacheReparsesRawWithCityZoneWithoutNetworkOrTimestampRewrite() throws Exception {
        long start = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
        long fetched = start + 24 * HOUR - 10 * 60_000, now = start + 24 * HOUR + 10 * 60_000;
        long expiry = fetched + HOUR;
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 96; i++) rows.put(new JSONObject().put("time", Instant.ofEpochMilli(start + i * HOUR).toString())
                .put("data", new JSONObject().put("instant", new JSONObject().put("details", new JSONObject()
                        .put("air_temperature", i % 12).put("wind_speed", 2)))
                        .put("next_1_hours", new JSONObject().put("summary", new JSONObject().put("symbol_code", "cloudy")))));
        String body = new JSONObject().put("properties", new JSONObject().put("meta", new JSONObject().put("units",
                new JSONObject().put("air_temperature", "celsius").put("wind_speed", "m/s"))).put("timeseries", rows)).toString();
        MetWeatherRepository.Snapshot raw = new MetWeatherRepository.Snapshot(body, "original-validator", fetched, expiry, expiry);
        int[] rawWrites = {0};
        MetWeatherRepository.Store rawStore = new MetWeatherRepository.Store() {
            @Override public MetWeatherRepository.Snapshot read(String key) { return raw; }
            @Override public void write(String key, MetWeatherRepository.Snapshot value) { rawWrites[0]++; }
        };
        MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) -> {
            fail("Reparse must not contact MET"); return null;
        }, rawStore, () -> now);
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = preferences(store);
        Cache cache = new Cache(); cache.write("", met.load(prefs.read(), 2_000));
        assertEquals(WeatherRepository.ForecastTimeBasis.UTC, cache.read("").forecastTimeBasis);
        cache.snapshot = new JSONObject(cache.snapshot).put("forecast_schema", 2).toString();
        prefs.setCity("Moscow", LAT, LON, "524901", "Europe/Moscow");
        WeatherRepository repository = new WeatherRepository(url -> {
            fail("Fresh local reparse needs no primary/geocoder request"); return "";
        }, cache, new WeatherRepository.JsonDecoder(), () -> now, millis -> { }, met, prefs::rememberCityMetadata);
        WeatherRepository.Weather result = repository.loadCurrent(prefs.read(), false).weather;
        assertNotNull(result); assertTrue(result.cityTimezoneKnown); assertFalse(result.needsForecastUpgrade);
        assertEquals("Europe/Moscow", result.forecastTimezone); assertEquals(fetched, result.fetchedAtMillis);
        assertEquals(expiry, result.sourceExpiresAtMillis); assertEquals(0, rawWrites[0]);
        assertEquals("2026-10-25", result.days.get(0).date);
        assertEquals(Instant.parse("2026-10-24T21:00:00Z").toEpochMilli(), result.days.get(0).startMillis);
        assertEquals(3, result.upcomingHours(now).size());
        assertNotNull(result.dayNight(now).day); assertNotNull(result.dayNight(now).night);
        assertEquals(fetched, raw.fetchedAtMillis); assertEquals(expiry, raw.expiresAtMillis);
        assertEquals("original-validator", raw.lastModified);
    }
}
