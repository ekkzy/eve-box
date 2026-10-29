package io.github.ekkzy.evebox.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.ekkzy.evebox.core.WeatherPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Blocking, opt-in Open-Meteo adapter. The UI owns scheduling; there are no workers or services. */
public final class WeatherRepository {
    public static final int MAX_FORECAST_DAYS = 3;
    public static final int MAX_FORECAST_HOURS = 96;
    private static final long HOUR_MILLIS = 3_600_000L;
    public static final String ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0); city data by GeoNames";
    public static final String ATTRIBUTION_URL = "https://open-meteo.com/";
    public enum Status { DISABLED, NEEDS_CITY, FRESH, STALE, UNAVAILABLE, ERROR }
    public enum CityStatus { ITEMS, EMPTY, UNAVAILABLE, ERROR }
    public enum Failure { NONE, NETWORK, TIMEOUT, RATE_LIMITED, HTTP, INVALID_RESPONSE, CANCELLED }
    public enum ForecastTimeBasis { CITY, UTC, UNKNOWN }
    public enum Provider {
        OPEN_METEO("Open-Meteo", "Open-Meteo weather data (CC BY 4.0)", "https://open-meteo.com/"),
        MET_NORWAY("MET Norway", "MET Norway forecast data (CC BY 4.0); symbols mapped to WMO equivalents",
                "https://api.met.no/doc/License");
        public final String displayName, attribution, attributionUrl;
        Provider(String displayName, String attribution, String attributionUrl) {
            this.displayName = displayName; this.attribution = attribution; this.attributionUrl = attributionUrl;
        }
    }

    public static final class City {
        public final String id;
        public final String name;
        public final String country;
        public final String region;
        public final double latitude;
        public final double longitude;
        public final String timezone;
        public City(String id, String name, String country, String region, double latitude, double longitude) {
            this(id, name, country, region, latitude, longitude, "");
        }
        public City(String id, String name, String country, String region, double latitude, double longitude, String timezone) {
            this.id = clean(id, 128);
            this.name = clean(name, 128);
            this.country = clean(country, 128);
            this.region = clean(region, 128);
            this.latitude = latitude;
            this.longitude = longitude;
            this.timezone = LauncherPrefs.ianaTimezone(timezone);
        }
        public String displayName() {
            String suffix = region.isEmpty() || region.equals(name) ? country : region + ", " + country;
            return suffix.isEmpty() ? name : name + ", " + suffix;
        }
    }

    public static final class Weather {
        public final double temperature;
        public final int weatherCode;
        public final boolean isDay;
        public final double windSpeed;
        public final long observedAtMillis;
        public final long fetchedAtMillis;
        public final String units;
        public final List<Hour> hours;
        /** At most three dated provider days. Use forecastDays(now) when presenting a saved response. */
        public final List<Day> days;
        public final long sunriseMillis;
        public final long sunsetMillis;
        public final String timezone;
        /** True only when this zone came from the city's provider response, or a validated same-city cache hint. */
        public final boolean cityTimezoneKnown;
        /** Calendar basis for dated forecasts; current/hour display timezone remains independent. */
        public final ForecastTimeBasis forecastTimeBasis;
        public final String forecastTimezone;
        /** A decoded older cache has not been fetched with the three-day forecast contract yet. */
        public final boolean needsForecastUpgrade;
        public final Provider provider;
        /** Actual provider cache expiry, when supplied; not a fabricated observation timestamp. */
        public final long sourceExpiresAtMillis;
        public Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis,
                    units, Collections.emptyList(), 0, 0, "GMT");
        }
        public Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, Provider.OPEN_METEO);
        }
        public Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, provider, 0);
        }
        public Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider, long sourceExpiresAtMillis) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, provider, sourceExpiresAtMillis, Collections.emptyList(), false);
        }
        public Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider, long sourceExpiresAtMillis,
                List<Day> days) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, provider, sourceExpiresAtMillis, days, true);
        }
        Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider, long sourceExpiresAtMillis,
                List<Day> days, boolean cityTimezoneKnown) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, provider, sourceExpiresAtMillis, days,
                    cityTimezoneKnown, ForecastTimeBasis.UNKNOWN, false);
        }
        Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider, long sourceExpiresAtMillis,
                List<Day> days, boolean cityTimezoneKnown, ForecastTimeBasis forecastTimeBasis) {
            this(temperature, weatherCode, isDay, windSpeed, observedAtMillis, fetchedAtMillis, units,
                    hours, sunriseMillis, sunsetMillis, timezone, provider, sourceExpiresAtMillis, days,
                    cityTimezoneKnown, forecastTimeBasis, false);
        }
        private Weather(double temperature, int weatherCode, boolean isDay, double windSpeed,
                long observedAtMillis, long fetchedAtMillis, String units, List<Hour> hours,
                long sunriseMillis, long sunsetMillis, String timezone, Provider provider, long sourceExpiresAtMillis,
                List<Day> days, boolean cityTimezoneKnown, ForecastTimeBasis forecastTimeBasis, boolean needsForecastUpgrade) {
            this.temperature = temperature;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
            this.windSpeed = windSpeed;
            this.observedAtMillis = observedAtMillis;
            this.fetchedAtMillis = fetchedAtMillis;
            this.units = units;
            ArrayList<Hour> validHours = new ArrayList<>();
            Set<Long> times = new HashSet<>();
            if (hours != null) {
                int visited = 0;
                for (Hour hour : hours) {
                    if (++visited > MAX_FORECAST_HOURS) break;
                    if (hour != null && hour.isValid() && hour.timeMillis >= fetchedAtMillis - 24 * HOUR_MILLIS
                            && hour.timeMillis <= fetchedAtMillis + 72 * HOUR_MILLIS
                            && times.add(hour.timeMillis)) validHours.add(hour);
                }
            }
            validHours.sort(Comparator.comparingLong(hour -> hour.timeMillis));
            this.hours = Collections.unmodifiableList(validHours);
            this.sunriseMillis = Math.max(0, sunriseMillis);
            this.sunsetMillis = Math.max(0, sunsetMillis);
            this.timezone = timezone == null || timezone.trim().isEmpty() ? "GMT" : clean(timezone, 128);
            this.cityTimezoneKnown = cityTimezoneKnown && forecastZone(this.timezone) != null;
            this.needsForecastUpgrade = needsForecastUpgrade;
            this.provider = provider == null ? Provider.OPEN_METEO : provider;
            this.forecastTimeBasis = this.cityTimezoneKnown ? ForecastTimeBasis.CITY
                    : this.provider == Provider.MET_NORWAY && forecastTimeBasis == ForecastTimeBasis.UTC
                    ? ForecastTimeBasis.UTC : ForecastTimeBasis.UNKNOWN;
            this.forecastTimezone = this.forecastTimeBasis == ForecastTimeBasis.CITY ? this.timezone
                    : this.forecastTimeBasis == ForecastTimeBasis.UTC ? "UTC" : "";
            this.sourceExpiresAtMillis = Math.max(0, sourceExpiresAtMillis);
            ArrayList<Day> validDays = new ArrayList<>();
            ZoneId zone = forecastZone(this.forecastTimezone);
            LocalDate firstDate = zone == null || fetchedAtMillis <= 0 ? null
                    : Instant.ofEpochMilli(fetchedAtMillis).atZone(zone).toLocalDate();
            LocalDate lastDate = firstDate == null ? null : firstDate.plusDays(MAX_FORECAST_DAYS);
            // A valid raw MET response can be re-parsed after midnight without a new fetch.
            // Its actual recent sample may extend the bounded date window; timestamps are never rewritten.
            if (firstDate != null && this.provider == Provider.MET_NORWAY && observedAtMillis > fetchedAtMillis
                    && observedAtMillis - fetchedAtMillis <= WeatherPolicy.MAX_STALE_MILLIS + 3_600_000L) {
                LocalDate sampleLimit = Instant.ofEpochMilli(observedAtMillis).atZone(zone).toLocalDate().plusDays(MAX_FORECAST_DAYS);
                if (sampleLimit.isAfter(lastDate)) lastDate = sampleLimit;
            }
            Set<String> dates = new HashSet<>();
            if (days != null && firstDate != null) {
                int visited = 0;
                for (Day day : days) {
                    if (++visited > 8) break;
                    if (day == null || !day.isValid()) continue;
                    LocalDate date = LocalDate.parse(day.date);
                    if (date.isBefore(firstDate) || !date.isBefore(lastDate)
                            || day.startMillis != date.atStartOfDay(zone).toInstant().toEpochMilli()
                            || day.endMillis != date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            || !dates.add(day.date)) continue;
                    validDays.add(day);
                }
            }
            validDays.sort(Comparator.comparingLong(day -> day.startMillis));
            if (validDays.size() > MAX_FORECAST_DAYS) validDays.subList(MAX_FORECAST_DAYS, validDays.size()).clear();
            this.days = Collections.unmodifiableList(validDays);
        }
        public boolean isValid() {
            return Double.isFinite(temperature) && temperature >= -200 && temperature <= 200
                    && weatherCode >= 0 && weatherCode <= 99 && Double.isFinite(windSpeed)
                    && windSpeed >= 0 && windSpeed <= 500 && observedAtMillis > 0 && fetchedAtMillis > 0
                    && ("celsius".equals(units) || "fahrenheit".equals(units));
        }
        /** Never relabel yesterday as today after an offline midnight or extend an expired cache. */
        public List<Day> forecastDays(long nowMillis) {
            ZoneId zone = forecastZone(forecastTimezone);
            if (zone == null || nowMillis <= 0 || cacheAge(this, nowMillis) == WeatherPolicy.CacheAge.EXPIRED)
                return Collections.emptyList();
            LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
            ArrayList<Day> visible = new ArrayList<>(MAX_FORECAST_DAYS);
            for (Day day : days) {
                LocalDate date = LocalDate.parse(day.date);
                if (!date.isBefore(today) && date.isBefore(today.plusDays(MAX_FORECAST_DAYS))) visible.add(day);
            }
            return Collections.unmodifiableList(visible);
        }
        /** Three actual future samples. Sparse provider rows retain their actual timestamps. */
        public List<Hour> upcomingHours(long nowMillis) {
            if (nowMillis <= 0 || cacheAge(this, nowMillis) == WeatherPolicy.CacheAge.EXPIRED)
                return Collections.emptyList();
            ArrayList<Hour> result = new ArrayList<>(3);
            for (Hour hour : hours) {
                if (hour.timeMillis > nowMillis) result.add(hour);
                if (result.size() == 3) break;
            }
            return Collections.unmodifiableList(result);
        }
        /** Four two-hour targets anchored to the first actual future row; missing exact rows stay absent. */
        public List<ForecastSlot> upcomingHourSlots(long nowMillis) {
            if (!cityTimezoneKnown || LauncherPrefs.ianaTimezone(timezone).isEmpty() || nowMillis <= 0
                    || cacheAge(this, nowMillis) == WeatherPolicy.CacheAge.EXPIRED) return Collections.emptyList();
            int first = 0;
            while (first < hours.size() && hours.get(first).timeMillis <= nowMillis) first++;
            if (first == hours.size()) return Collections.emptyList();
            long anchor = hours.get(first).timeMillis;
            if (anchor > Long.MAX_VALUE - 6 * HOUR_MILLIS) return Collections.emptyList();
            ArrayList<ForecastSlot> slots = new ArrayList<>(4);
            int source = first;
            for (int index = 0; index < 4; index++) {
                long target = anchor + index * 2 * HOUR_MILLIS;
                while (source < hours.size() && hours.get(source).timeMillis < target) source++;
                Hour sample = source < hours.size() && hours.get(source).timeMillis == target ? hours.get(source) : null;
                slots.add(new ForecastSlot(target, sample));
            }
            return Collections.unmodifiableList(slots);
        }
        /** Civil-time sample policy, not daily extrema or a claim about sunrise/sunset. */
        public DayNight dayNight(long nowMillis) {
            ZoneId zone = cityTimezoneKnown ? forecastZone(timezone) : null;
            if (zone == null || nowMillis <= 0 || cacheAge(this, nowMillis) == WeatherPolicy.CacheAge.EXPIRED)
                return new DayNight("", "", "", null, null);
            LocalDate day = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
            LocalDate night = day.plusDays(1);
            return new DayNight(timezone, day.toString(), night.toString(),
                    nearestSample(day, zone, 12, 18, 14), nearestSample(night, zone, 0, 6, 2));
        }
        private Hour nearestSample(LocalDate date, ZoneId zone, int firstHour, int lastHour, int targetHour) {
            long first = date.atTime(firstHour, 0).atZone(zone).toInstant().toEpochMilli();
            long last = date.atTime(lastHour, 0).atZone(zone).toInstant().toEpochMilli();
            long target = date.atTime(targetHour, 0).atZone(zone).toInstant().toEpochMilli();
            Hour result = null;
            long difference = Long.MAX_VALUE;
            for (Hour hour : hours) {
                if (hour.timeMillis < first || hour.timeMillis >= last) continue;
                long distance = Math.abs(hour.timeMillis - target);
                if (distance < difference) { result = hour; difference = distance; }
            }
            return result;
        }
    }

    public static final class DayNight {
        public final String timezone, dayDate, nightDate;
        /** Null means that the provider supplied no sample in that dated local window. */
        public final Hour day, night;
        private DayNight(String timezone, String dayDate, String nightDate, Hour day, Hour night) {
            this.timezone = timezone; this.dayDate = dayDate; this.nightDate = nightDate;
            this.day = day; this.night = night;
        }
    }

    public static final class ForecastSlot {
        public final long timeMillis;
        /** Null means the source has no sample at this exact target instant. */
        public final Hour sample;
        private ForecastSlot(long timeMillis, Hour sample) { this.timeMillis = timeMillis; this.sample = sample; }
    }

    /** Optional numeric fields are NaN; absent solar events are zero. Speeds are always km/h, rain is mm.
     * MET extrema/conditions describe available samples/whole periods, rather than a provider daily product.
     * coverageStart/End preserve the source window; partialDay also records gaps inside that window.
     */
    public static final class Day {
        public final String date;
        public final long startMillis, endMillis, coverageStartMillis, coverageEndMillis;
        public final int weatherCode;
        public final double minTemperature, maxTemperature, precipitationMm, precipitationProbabilityMax;
        public final double windSpeedMax, windGustsMax, uvIndexMax;
        public final long sunriseMillis, sunsetMillis;
        public final boolean aggregated, partialDay;
        public Day(String date, long startMillis, long endMillis, int weatherCode,
                double minTemperature, double maxTemperature, double precipitationMm, double precipitationProbabilityMax,
                double windSpeedMax, double windGustsMax, double uvIndexMax, long sunriseMillis, long sunsetMillis,
                boolean aggregated, boolean partialDay, long coverageStartMillis, long coverageEndMillis) {
            this.date = clean(date, 11);
            this.startMillis = startMillis; this.endMillis = endMillis;
            this.weatherCode = validWeatherCode(weatherCode) ? weatherCode : -1;
            double low = optionalNumber(minTemperature, -200, 200), high = optionalNumber(maxTemperature, -200, 200);
            this.minTemperature = low <= high || !Double.isFinite(high) ? low : Double.NaN;
            this.maxTemperature = low <= high || !Double.isFinite(low) ? high : Double.NaN;
            this.precipitationMm = optionalNumber(precipitationMm, 0, 3000);
            this.precipitationProbabilityMax = optionalNumber(precipitationProbabilityMax, 0, 100);
            this.windSpeedMax = optionalNumber(windSpeedMax, 0, 500);
            this.windGustsMax = optionalNumber(windGustsMax, 0, 500);
            this.uvIndexMax = optionalNumber(uvIndexMax, 0, 50);
            this.sunriseMillis = sunriseMillis >= startMillis && sunriseMillis < endMillis ? sunriseMillis : 0;
            this.sunsetMillis = sunsetMillis >= startMillis && sunsetMillis < endMillis ? sunsetMillis : 0;
            this.aggregated = aggregated;
            this.coverageStartMillis = coverageStartMillis; this.coverageEndMillis = coverageEndMillis;
            this.partialDay = partialDay || coverageStartMillis != startMillis || coverageEndMillis != endMillis;
        }
        public boolean isValid() {
            try {
                if (date.length() != 10 || !LocalDate.parse(date).toString().equals(date)) return false;
            } catch (RuntimeException invalidDate) { return false; }
            return startMillis > 0 && endMillis > startMillis && endMillis - startMillis <= 26 * 3_600_000L
                    && coverageStartMillis > 0 && coverageEndMillis >= coverageStartMillis
                    && coverageStartMillis < endMillis && coverageEndMillis >= startMillis
                    && coverageStartMillis >= startMillis - 3_600_000L
                    && coverageEndMillis <= endMillis + 3_600_000L
                    && (weatherCode >= 0 || Double.isFinite(minTemperature) || Double.isFinite(maxTemperature)
                    || Double.isFinite(precipitationMm) || Double.isFinite(precipitationProbabilityMax)
                    || Double.isFinite(windSpeedMax) || Double.isFinite(windGustsMax) || Double.isFinite(uvIndexMax));
        }
    }

    static ZoneId forecastZone(String timezone) {
        try { return timezone == null ? null : ZoneId.of(timezone); }
        catch (RuntimeException invalidTimezone) { return null; }
    }
    static double optionalNumber(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : Double.NaN;
    }
    private static boolean validWeatherCode(int code) {
        switch (code) {
            case 0: case 1: case 2: case 3: case 45: case 48: case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67: case 68: case 71: case 73: case 75: case 77:
            case 80: case 81: case 82: case 83: case 85: case 86: case 95: case 96: case 99: return true;
            default: return false;
        }
    }

    public static final class Hour {
        public final long timeMillis;
        public final double temp;
        public final int weatherCode;
        /** Provider phase, when supplied; null does not mean day or night. */
        public final Boolean isDay;
        public Hour(long timeMillis, double temp, int weatherCode) {
            this(timeMillis, temp, weatherCode, null);
        }
        public Hour(long timeMillis, double temp, int weatherCode, Boolean isDay) {
            this.timeMillis = timeMillis;
            this.temp = temp;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
        }
        public boolean isValid() {
            return timeMillis > 0 && Double.isFinite(temp) && temp >= -200 && temp <= 200
                    && weatherCode >= 0 && weatherCode <= 99;
        }
    }

    public static final class WeatherResult {
        public final Status status;
        public final Weather weather;
        public final String message;
        public final Failure failure;
        public final int httpStatus;
        /** Earliest automatic refresh, or zero when weather is disabled, unconfigured or cancelled. */
        public final long retryAtMillis;
        private WeatherResult(Status status, Weather weather, String message,
                Failure failure, int httpStatus, long retryAtMillis) {
            this.status = status;
            this.weather = weather;
            this.message = message;
            this.failure = failure;
            this.httpStatus = httpStatus;
            this.retryAtMillis = retryAtMillis;
        }
    }
    public static final class CityResult {
        public final CityStatus status;
        public final List<City> items;
        public final String message;
        private CityResult(CityStatus status, List<City> items, String message) {
            this.status = status;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.message = message;
        }
    }

    @FunctionalInterface public interface Transport {
        String get(String url) throws IOException;
        /** Production transports share the remaining request budget across retries. */
        default String get(String url, int timeoutMillis) throws IOException { return get(url); }
    }
    @FunctionalInterface public interface Clock { long nowMillis(); }
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface public interface Fallback {
        Weather load(LauncherPrefs.Settings settings, int timeoutMillis) throws Exception;
        /** A zone hint belongs to the same explicit city/cache key. It must never be guessed from coordinates. */
        default Weather load(LauncherPrefs.Settings settings, int timeoutMillis, String cityTimezone) throws Exception {
            return load(settings, timeoutMillis);
        }
        /** Local-only re-normalization. It must preserve the original raw fetch/expiry/validators. */
        default Weather reparseCached(LauncherPrefs.Settings settings, String cityTimezone) throws Exception { return null; }
    }
    @FunctionalInterface public interface CityMetadataStore {
        boolean save(LauncherPrefs.Settings expected, String id, String timezone);
    }
    public interface Cache {
        Weather read(String key);
        void write(String key, Weather weather);
    }
    public interface Decoder {
        List<City> cities(String body) throws Exception;
        default List<City> cityMetadata(String body) throws Exception { return cities(body); }
        Weather current(String body, String units, long fetchedAtMillis) throws Exception;
    }
    public static final class HttpFailure extends IOException {
        public final int statusCode;
        public final long retryAfterMillis;
        public HttpFailure(int statusCode) { this(statusCode, 0); }
        public HttpFailure(int statusCode, long retryAfterMillis) {
            super("Weather HTTP " + statusCode);
            this.statusCode = statusCode;
            this.retryAfterMillis = Math.max(0, Math.min(WeatherPolicy.MAX_RETRY_AFTER_MILLIS, retryAfterMillis));
        }
    }

    private final Transport transport;
    private final Cache cache;
    private final Decoder decoder;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Fallback fallback;
    private final CityMetadataStore cityMetadataStore;
    private final LinkedHashMap<String, Long> cityLookupAfter = new LinkedHashMap<>(8, .75f, true);
    private static final int CITY_LOOKUP_TIMEOUT_MILLIS = 4_000;
    private static final long CITY_LOOKUP_TRANSIENT_RETRY_MILLIS = 15 * 60_000L;
    private static final long CITY_LOOKUP_RETRY_MILLIS = 6 * HOUR_MILLIS;
    private static final int FALLBACK_TOTAL_TIMEOUT_MILLIS = 15_000;
    private static final int PRIMARY_WITH_FALLBACK_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_FAILURE_KEYS = 8;
    private static final long RETRY_PAUSE_MILLIS = 250;
    private final LinkedHashMap<String, FailureState> failures = new LinkedHashMap<>(8, .75f, true);

    public WeatherRepository(Context context) {
        this(new HttpsTransport(), new PreferenceCache(context.getApplicationContext()),
                new JsonDecoder(), System::currentTimeMillis, Thread::sleep,
                new MetWeatherRepository(context.getApplicationContext()),
                new LauncherPrefs(context.getApplicationContext())::rememberCityMetadata);
    }
    /** Inject all external boundaries to exercise timeouts, offline results and stale cache deterministically. */
    public WeatherRepository(Transport transport, Cache cache, Decoder decoder, Clock clock) {
        this(transport, cache, decoder, clock, Thread::sleep);
    }
    public WeatherRepository(Transport transport, Cache cache, Decoder decoder, Clock clock, Sleeper sleeper) {
        this(transport, cache, decoder, clock, sleeper, null);
    }
    public WeatherRepository(Transport transport, Cache cache, Decoder decoder, Clock clock, Sleeper sleeper, Fallback fallback) {
        this(transport, cache, decoder, clock, sleeper, fallback, null);
    }
    public WeatherRepository(Transport transport, Cache cache, Decoder decoder, Clock clock, Sleeper sleeper,
            Fallback fallback, CityMetadataStore cityMetadataStore) {
        if (transport == null || cache == null || decoder == null || clock == null || sleeper == null)
            throw new IllegalArgumentException("Weather boundaries are required");
        this.transport = transport;
        this.cache = cache;
        this.decoder = decoder;
        this.clock = clock;
        this.sleeper = sleeper;
        this.fallback = fallback;
        this.cityMetadataStore = cityMetadataStore;
    }

    public CityResult searchCities(String query, String language) {
        String cleaned = clean(query, 128);
        if (cleaned.length() < 2) return cityResult(CityStatus.EMPTY, "Enter at least two characters");
        String locale = "system".equals(language) ? Locale.getDefault().getLanguage() : language;
        if (locale == null || !locale.matches("[a-zA-Z]{2,3}")) locale = "en";
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(cleaned, "UTF-8") + "&count=8&format=json&language="
                    + locale.toLowerCase(Locale.ROOT);
            List<City> parsed = decoder.cities(transport.get(url));
            if (parsed == null) throw new IOException("No city response");
            ArrayList<City> cities = new ArrayList<>();
            for (City city : parsed) {
                if (city != null && !city.name.isEmpty()
                        && WeatherPolicy.validCoordinates(city.latitude, city.longitude)) cities.add(city);
                if (cities.size() == 8) break;
            }
            return new CityResult(cities.isEmpty() ? CityStatus.EMPTY : CityStatus.ITEMS, cities,
                    cities.isEmpty() ? "No matching cities" : "");
        } catch (IOException unavailable) {
            return cityResult(CityStatus.UNAVAILABLE, "City search unavailable. Check your connection.");
        } catch (Exception invalid) {
            return cityResult(CityStatus.ERROR, "City search returned an invalid response");
        }
    }

    public synchronized WeatherResult loadCurrent(LauncherPrefs.Settings settings, boolean forceRefresh) {
        if (settings == null || !settings.weatherEnabled)
            return weatherResult(Status.DISABLED, null, "Weather is off");
        if (!settings.hasCity()) return weatherResult(Status.NEEDS_CITY, null, "Choose a city for weather");
        if (Thread.currentThread().isInterrupted())
            return new WeatherResult(Status.UNAVAILABLE, null, "Weather request cancelled", Failure.CANCELLED, 0, 0);
        long now = clock.nowMillis();
        long deadline = System.nanoTime() + FALLBACK_TOTAL_TIMEOUT_MILLIS * 1_000_000L;
        String units = resolveUnits(settings.units);
        String key = cacheKey(settings.cityLatitude, settings.cityLongitude, units);
        Weather cached;
        try { cached = cache.read(key); }
        catch (RuntimeException invalidCache) { cached = null; }
        // City lookup/reparse can be cancelled before the normal refresh path. Every
        // early fallback must receive the same validated cache as a network failure.
        if (!validAt(cached, units, now)) cached = null;
        String cityTimezone = settings.cityTimezone;
        try {
            cityTimezone = resolveCityTimezone(settings, cached, deadline, forceRefresh);
            boolean changedZone = !cityTimezone.isEmpty() && cached != null
                    && (!cached.cityTimezoneKnown || !cityTimezone.equals(cached.timezone));
            if (fallback != null && cached != null && cached.provider == Provider.MET_NORWAY
                    && (changedZone || cached.needsForecastUpgrade)) {
                Weather reparsed = fallback.reparseCached(settings, cityTimezone);
                checkInterrupted();
                if (validAt(reparsed, units, now)) {
                    cached = reparsed;
                    try { cache.write(key, reparsed); } catch (RuntimeException ignored) { }
                }
            }
        } catch (InterruptedIOException cancelled) {
            return fallback(cached, now, Status.UNAVAILABLE, "Weather request cancelled", Failure.CANCELLED, 0, 0);
        } catch (Exception invalidLocalCache) { /* Retain the resolved zone and refresh normally. */ }
        if (!validAt(cached, units, now)) cached = null;
        WeatherPolicy.CacheAge age = age(cached, now);
        boolean matchingZone = cityTimezone.isEmpty() || cached == null
                || cached.cityTimezoneKnown && cityTimezone.equals(cached.timezone);
        if (!forceRefresh && age == WeatherPolicy.CacheAge.FRESH && !cached.needsForecastUpgrade && matchingZone)
            return successful(cached, now);
        FailureState previous = failures.get(key);
        if (previous != null && now < previous.failedAtMillis) {
            failures.remove(key); // A corrected wall clock must not leave an obsolete retry gate.
            previous = null;
        }
        if (previous != null && now < previous.retryAtMillis
                && (!forceRefresh || now < previous.serverRetryAtMillis))
            return fallback(cached, now, previous.status, previous.message,
                    previous.failure, previous.httpStatus, previous.retryAtMillis);
        try {
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f"
                            + "&current=temperature_2m,weather_code,is_day,wind_speed_10m"
                            + "&hourly=temperature_2m,weather_code,is_day"
                            + "&daily=weather_code,temperature_2m_min,temperature_2m_max,precipitation_sum,"
                            + "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset"
                            + "&forecast_days=3&precipitation_unit=mm"
                            + "&temperature_unit=%s&wind_speed_unit=kmh&timeformat=unixtime&timezone=%s",
                    settings.cityLatitude, settings.cityLongitude, units,
                    cityTimezone.isEmpty() ? "auto" : URLEncoder.encode(cityTimezone, "UTF-8"));
            Weather weather;
            if (fallback == null) weather = decoder.current(fetchWithRetry(url), units, clock.nowMillis());
            else {
                try {
                    String body = transport.get(url, Math.min(PRIMARY_WITH_FALLBACK_TIMEOUT_MILLIS, remainingMillis(deadline)));
                    checkInterrupted();
                    weather = decoder.current(body, units, clock.nowMillis());
                } catch (IOException primaryFailure) {
                    if (!fallbackEligible(primaryFailure)) throw primaryFailure;
                    logFailure(classify(primaryFailure), primaryFailure instanceof HttpFailure
                            ? ((HttpFailure) primaryFailure).statusCode : 0, primaryFailure);
                    weather = fallback.load(settings, remainingMillis(deadline),
                            cityTimezone);
                }
            }
            checkInterrupted();
            now = clock.nowMillis();
            if (!validAt(weather, units, now)
                    || age(weather, now) == WeatherPolicy.CacheAge.EXPIRED)
                throw new IllegalArgumentException("Invalid current weather");
            if (weather.cityTimezoneKnown) rememberCityTimezone(settings, settings.cityId, weather.timezone);
            try { cache.write(key, weather); }
            catch (RuntimeException ignored) { /* A cache failure must not hide a valid response. */ }
            failures.remove(key);
            return successful(weather, now);
        } catch (IOException unavailable) {
            Failure failure = classify(unavailable);
            now = clock.nowMillis();
            int httpStatus = unavailable instanceof HttpFailure ? ((HttpFailure) unavailable).statusCode : 0;
            String message = failureMessage(failure, httpStatus);
            logFailure(failure, httpStatus, unavailable);
            if (failure == Failure.CANCELLED)
                return fallback(cached, now, Status.UNAVAILABLE, message, failure, httpStatus, 0);
            Status status = failure == Failure.INVALID_RESPONSE ? Status.ERROR : Status.UNAVAILABLE;
            FailureState next = rememberFailure(key, now, previous, status, failure, httpStatus, message,
                    unavailable instanceof HttpFailure ? ((HttpFailure) unavailable).retryAfterMillis : 0);
            return fallback(cached, now, status, message, failure, httpStatus, next.retryAtMillis);
        } catch (Exception invalid) {
            now = clock.nowMillis();
            if (Thread.currentThread().isInterrupted() || invalid instanceof InterruptedException) {
                if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
                logFailure(Failure.CANCELLED, 0, invalid);
                return fallback(cached, now, Status.UNAVAILABLE, failureMessage(Failure.CANCELLED, 0),
                        Failure.CANCELLED, 0, 0);
            }
            logFailure(Failure.INVALID_RESPONSE, 0, invalid);
            String message = failureMessage(Failure.INVALID_RESPONSE, 0);
            FailureState next = rememberFailure(key, now, previous, Status.ERROR,
                    Failure.INVALID_RESPONSE, 0, message, 0);
            return fallback(cached, now, Status.ERROR, message, Failure.INVALID_RESPONSE, 0, next.retryAtMillis);
        }
    }

    /** Resolves provider records by ID, or a unique same-name record within one kilometre for legacy selections. */
    private String resolveCityTimezone(LauncherPrefs.Settings settings, Weather cached, long deadline,
            boolean forceRefresh) throws IOException {
        checkInterrupted();
        if (!settings.cityTimezone.isEmpty()) return settings.cityTimezone;
        if (cached != null && cached.isValid() && cached.fetchedAtMillis <= clock.nowMillis() && cached.cityTimezoneKnown) {
            String zone = LauncherPrefs.ianaTimezone(cached.timezone);
            if (!zone.isEmpty()) { rememberCityTimezone(settings, settings.cityId, zone); return zone; }
        }
        if (cityMetadataStore == null) return "";
        String identity = LauncherPrefs.cityMetadataKey(settings.cityName, settings.cityLatitude, settings.cityLongitude);
        long now = clock.nowMillis();
        Long after = cityLookupAfter.get(identity);
        if (!forceRefresh && after != null && now < after && after - now <= CITY_LOOKUP_RETRY_MILLIS) return "";
        cityLookupAfter.put(identity, after(now, CITY_LOOKUP_TRANSIENT_RETRY_MILLIS));
        while (cityLookupAfter.size() > MAX_FAILURE_KEYS) cityLookupAfter.remove(cityLookupAfter.keySet().iterator().next());
        long started = System.nanoTime();
        CityLookupOutcome outcome = CityLookupOutcome.TRANSIENT_FAILURE;
        Failure failure = Failure.NONE;
        int httpStatus = 0;
        boolean matchedCity = false;
        try {
            String id = settings.cityId;
            String language = settings.cityName.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC)
                    ? "ru" : "en";
            String url = "https://geocoding-api.open-meteo.com/v1/" + (id.isEmpty()
                    ? "search?name=" + URLEncoder.encode(settings.cityName, "UTF-8") + "&count=100&format=json&language=" + language
                    : "get?id=" + id);
            String body = transport.get(url, Math.min(CITY_LOOKUP_TIMEOUT_MILLIS, remainingMillis(deadline)));
            httpStatus = HttpURLConnection.HTTP_OK;
            List<City> candidates = decoder.cityMetadata(body);
            if (candidates == null) throw new InvalidResponseException("No city metadata response");
            checkInterrupted();
            City matched = null;
            int visited = 0;
            if (candidates != null) for (City candidate : candidates) {
                if (++visited > 100) break;
                if (candidate == null || candidate.timezone.isEmpty() || LauncherPrefs.geonameId(candidate.id).isEmpty()
                        || !WeatherPolicy.validCoordinates(candidate.latitude, candidate.longitude)
                        || (!id.isEmpty() ? !id.equals(candidate.id) : !sameLegacyCity(settings, candidate))) continue;
                if (matched != null) {
                    outcome = CityLookupOutcome.AMBIGUOUS;
                    cityLookupAfter.put(identity, after(clock.nowMillis(), CITY_LOOKUP_RETRY_MILLIS));
                    return "";
                }
                matched = candidate;
            }
            if (matched != null) {
                rememberCityTimezone(settings, matched.id, matched.timezone);
                cityLookupAfter.remove(identity);
                outcome = CityLookupOutcome.RESOLVED;
                matchedCity = true;
                return matched.timezone;
            }
            outcome = CityLookupOutcome.NO_MATCH;
            cityLookupAfter.put(identity, after(clock.nowMillis(), CITY_LOOKUP_RETRY_MILLIS));
        } catch (Exception unavailableMetadata) {
            if (Thread.currentThread().isInterrupted() || unavailableMetadata instanceof InterruptedException
                    || unavailableMetadata instanceof InterruptedIOException && !(unavailableMetadata instanceof SocketTimeoutException)) {
                cityLookupAfter.remove(identity);
                outcome = CityLookupOutcome.CANCELLED;
                failure = Failure.CANCELLED;
                if (unavailableMetadata instanceof InterruptedException) Thread.currentThread().interrupt();
                if (unavailableMetadata instanceof InterruptedIOException) throw (InterruptedIOException) unavailableMetadata;
                throw new InterruptedIOException("City lookup interrupted");
            }
            failure = unavailableMetadata instanceof IOException ? classify((IOException) unavailableMetadata) : Failure.INVALID_RESPONSE;
            if (unavailableMetadata instanceof HttpFailure) httpStatus = ((HttpFailure) unavailableMetadata).statusCode;
            cityLookupAfter.put(identity, after(clock.nowMillis(), CITY_LOOKUP_TRANSIENT_RETRY_MILLIS));
        } finally {
            logCityLookup(outcome, failure, httpStatus, started, matchedCity);
        }
        return "";
    }
    private enum CityLookupOutcome { RESOLVED, NO_MATCH, AMBIGUOUS, TRANSIENT_FAILURE, CANCELLED }
    private static void logCityLookup(CityLookupOutcome outcome, Failure failure, int httpStatus,
            long startedNanos, boolean matched) {
        // One bounded local line per attempted lookup; never include city, coordinates, URL, body or exception text.
        String message = "cityLookup outcome=" + outcome.name() + " matched=" + matched + " elapsedMs="
                + Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L) + " http=" + httpStatus + " failure=" + failure.name();
        try {
            if (outcome == CityLookupOutcome.TRANSIENT_FAILURE) Log.w("EveBoxWeather", message);
            else Log.d("EveBoxWeather", message);
        } catch (RuntimeException unavailableLogger) { /* JVM fixtures do not have Android's logger. */ }
    }
    private static boolean sameLegacyCity(LauncherPrefs.Settings settings, City candidate) {
        if (!normalizedCityName(settings.cityName).equals(normalizedCityName(candidate.name))) return false;
        double latitude = Math.toRadians(candidate.latitude - settings.cityLatitude);
        double longitude = Math.toRadians(candidate.longitude - settings.cityLongitude);
        double arc = Math.pow(Math.sin(latitude / 2), 2)
                + Math.cos(Math.toRadians(settings.cityLatitude)) * Math.cos(Math.toRadians(candidate.latitude))
                * Math.pow(Math.sin(longitude / 2), 2);
        double distanceMetres = 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, arc))));
        return distanceMetres <= 1_000;
    }
    private static String normalizedCityName(String name) {
        return Normalizer.normalize(clean(name, 128), Normalizer.Form.NFKC).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private void rememberCityTimezone(LauncherPrefs.Settings settings, String id, String zone) throws IOException {
        checkInterrupted();
        if (cityMetadataStore == null) return;
        boolean saved;
        try { saved = cityMetadataStore.save(settings, id, zone); }
        catch (RuntimeException unavailablePreferences) { return; }
        if (!saved) throw new InterruptedIOException("Selected city changed");
    }

    /** At most two attempts; only the blocking caller waits, and interruption never triggers a retry. */
    private String fetchWithRetry(String url) throws IOException {
        long deadline = System.nanoTime() + HttpsTransport.TOTAL_TIMEOUT_MILLIS * 1_000_000L;
        for (int attempt = 0; ; attempt++) {
            checkInterrupted();
            try {
                String body = transport.get(url, remainingMillis(deadline));
                checkInterrupted();
                return body;
            } catch (IOException failure) {
                if (attempt >= 1 || !retryable(failure)
                        || remainingMillis(deadline) <= RETRY_PAUSE_MILLIS + 500) throw failure;
                try { sleeper.sleep(RETRY_PAUSE_MILLIS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Weather request interrupted");
                }
            }
        }
    }

    private static boolean retryable(IOException exception) {
        if (classify(exception) == Failure.CANCELLED || exception instanceof SSLException
                || exception instanceof ProtocolException || exception instanceof InvalidResponseException) return false;
        if (exception instanceof HttpFailure) {
            HttpFailure http = (HttpFailure) exception;
            return http.retryAfterMillis == 0 && WeatherPolicy.retryableHttpStatus(http.statusCode);
        }
        return true;
    }

    private static boolean fallbackEligible(IOException exception) {
        if (classify(exception) == Failure.CANCELLED || exception instanceof SSLException
                || exception instanceof ProtocolException || exception instanceof InvalidResponseException) return false;
        if (exception instanceof HttpFailure) {
            HttpFailure http = (HttpFailure) exception;
            return http.statusCode >= 500 && http.statusCode <= 599 && http.retryAfterMillis == 0;
        }
        return true;
    }

    private static Failure classify(IOException exception) {
        if (Thread.currentThread().isInterrupted()
                || exception instanceof InterruptedIOException && !(exception instanceof SocketTimeoutException))
            return Failure.CANCELLED;
        if (exception instanceof SocketTimeoutException) return Failure.TIMEOUT;
        if (exception instanceof HttpFailure)
            return ((HttpFailure) exception).statusCode == 429 ? Failure.RATE_LIMITED : Failure.HTTP;
        return exception instanceof InvalidResponseException ? Failure.INVALID_RESPONSE : Failure.NETWORK;
    }

    private static String failureMessage(Failure failure, int httpStatus) {
        switch (failure) {
            case TIMEOUT: return "Weather request timed out";
            case RATE_LIMITED: return "Weather provider is busy; retry later";
            case HTTP: return "Weather provider returned HTTP " + httpStatus;
            case INVALID_RESPONSE: return "Weather returned an invalid response";
            case CANCELLED: return "Weather request cancelled";
            default: return "Weather unavailable offline";
        }
    }

    private FailureState rememberFailure(String key, long now, FailureState previous, Status status,
            Failure failure, int httpStatus, String message, long retryAfterMillis) {
        int count = previous == null ? 1 : Math.min(16, previous.count + 1);
        long delay = WeatherPolicy.retryDelayMillis(count, retryAfterMillis);
        if (failure == Failure.INVALID_RESPONSE || failure == Failure.HTTP && !WeatherPolicy.retryableHttpStatus(httpStatus))
            delay = Math.max(delay, WeatherPolicy.MAX_RETRY_MILLIS);
        FailureState next = new FailureState(count, now, after(now, delay),
                retryAfterMillis > 0 ? after(now, retryAfterMillis) : 0,
                status, failure, httpStatus, message);
        failures.put(key, next);
        if (failures.size() > MAX_FAILURE_KEYS) failures.remove(failures.keySet().iterator().next());
        return next;
    }

    private static final class FailureState {
        final int count, httpStatus;
        final long failedAtMillis, retryAtMillis, serverRetryAtMillis;
        final Status status;
        final Failure failure;
        final String message;
        FailureState(int count, long failedAtMillis, long retryAtMillis, long serverRetryAtMillis,
                Status status, Failure failure, int httpStatus, String message) {
            this.count = count; this.failedAtMillis = failedAtMillis; this.retryAtMillis = retryAtMillis;
            this.serverRetryAtMillis = serverRetryAtMillis; this.status = status; this.failure = failure;
            this.httpStatus = httpStatus; this.message = message;
        }
    }

    private static void logFailure(Failure failure, int httpStatus, Exception exception) {
        // Never log a URL, coordinates, city, response body, message or exception stack.
        try { Log.w("EveBoxWeather", "failure=" + failure.name() + " http=" + httpStatus
                + " exception=" + exception.getClass().getSimpleName()); }
        catch (RuntimeException unavailableLogger) { /* JVM fixtures do not have Android's logger. */ }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Weather request interrupted");
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Weather deadline exceeded");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (remaining + 999_999L) / 1_000_000L));
    }

    private static long after(long now, long delay) {
        return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
    }

    private static WeatherResult successful(Weather weather, long now) {
        boolean fresh = age(weather, now) == WeatherPolicy.CacheAge.FRESH;
        long retryAt = weather.provider == Provider.MET_NORWAY ? after(now, WeatherPolicy.FRESH_MILLIS)
                : fresh ? after(Math.min(weather.fetchedAtMillis, weather.observedAtMillis), WeatherPolicy.FRESH_MILLIS)
                : after(now, WeatherPolicy.FRESH_MILLIS);
        if (fresh && weather.provider == Provider.MET_NORWAY && weather.sourceExpiresAtMillis > now)
            retryAt = Math.min(retryAt, weather.sourceExpiresAtMillis);
        return new WeatherResult(fresh ? Status.FRESH : Status.STALE, weather,
                fresh ? "" : "Latest available weather is delayed", Failure.NONE, 0, retryAt);
    }

    public static String cacheKey(double latitude, double longitude, String units) {
        return String.format(Locale.ROOT, "%.6f|%.6f|%s", latitude, longitude, units);
    }
    public static String resolveUnits(String preference) { return resolveUnits(preference, Locale.getDefault()); }
    public static String resolveUnits(String preference, Locale locale) {
        if ("celsius".equals(preference) || "fahrenheit".equals(preference)) return preference;
        String country = locale == null ? "" : locale.getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country)
                ? "fahrenheit" : "celsius";
    }
    private static WeatherResult fallback(Weather cached, long now, Status unavailable, String message,
            Failure failure, int httpStatus, long retryAtMillis) {
        return age(cached, now) != WeatherPolicy.CacheAge.EXPIRED
                ? new WeatherResult(Status.STALE, cached, message + "; showing saved weather", failure, httpStatus, retryAtMillis)
                : new WeatherResult(unavailable, null, message, failure, httpStatus, retryAtMillis);
    }
    private static WeatherPolicy.CacheAge age(Weather weather, long now) {
        return cacheAge(weather, now);
    }
    public static WeatherPolicy.CacheAge cacheAge(Weather weather, long now) {
        if (weather == null || weather.fetchedAtMillis > now) return WeatherPolicy.CacheAge.EXPIRED;
        WeatherPolicy.CacheAge saved = WeatherPolicy.cacheAge(Math.min(weather.fetchedAtMillis, weather.observedAtMillis), now);
        if (weather.provider == Provider.MET_NORWAY && saved != WeatherPolicy.CacheAge.EXPIRED
                && weather.observedAtMillis <= now + 3_600_000L
                && weather.observedAtMillis > now - 3_600_000L
                && weather.sourceExpiresAtMillis > now) return WeatherPolicy.CacheAge.FRESH;
        return saved;
    }
    private static boolean validAt(Weather weather, String units, long now) {
        return weather != null && weather.isValid() && units.equals(weather.units)
                && weather.fetchedAtMillis <= now && weather.observedAtMillis <= now
                + (weather.provider == Provider.MET_NORWAY ? 3_600_000L : 5 * 60 * 1000L);
    }
    private static WeatherResult weatherResult(Status status, Weather weather, String message) {
        return new WeatherResult(status, weather, message, Failure.NONE, 0, 0);
    }
    private static CityResult cityResult(CityStatus status, String message) {
        return new CityResult(status, Collections.emptyList(), message);
    }
    private static String clean(String text, int limit) {
        if (text == null) return "";
        String value = text.trim();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static final class InvalidResponseException extends IOException {
        InvalidResponseException(String message) { super(message); }
    }

    /** HTTPS only, fixed hosts, no redirects, bounded response and connect/read/deadline budgets. */
    public static final class HttpsTransport implements Transport {
        public static final int CONNECT_TIMEOUT_MILLIS = 4000;
        public static final int READ_TIMEOUT_MILLIS = 4000;
        public static final int TOTAL_TIMEOUT_MILLIS = 10000;
        public static final int MAX_BYTES = 262144;
        @Override public String get(String address) throws IOException {
            return get(address, TOTAL_TIMEOUT_MILLIS);
        }
        @Override public String get(String address, int timeoutMillis) throws IOException {
            if (timeoutMillis <= 0) throw new SocketTimeoutException("Weather deadline exceeded");
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || !("api.open-meteo.com".equals(url.getHost())
                    || "geocoding-api.open-meteo.com".equals(url.getHost()))
                    || url.getUserInfo() != null || url.getPort() != -1 && url.getPort() != 443)
                throw new ProtocolException("Unsupported weather endpoint");
            int budget = Math.min(TOTAL_TIMEOUT_MILLIS, timeoutMillis);
            long deadline = System.nanoTime() + budget * 1_000_000L;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MILLIS, budget));
            connection.setReadTimeout(Math.min(READ_TIMEOUT_MILLIS, budget));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Eve-Box/0.1 (Android TV launcher)");
            try {
                checkInterrupted();
                connection.connect();
                checkInterrupted();
                connection.setReadTimeout(Math.min(READ_TIMEOUT_MILLIS, remainingMillis(deadline)));
                int status = connection.getResponseCode();
                checkInterrupted();
                remainingMillis(deadline);
                if (status != HttpURLConnection.HTTP_OK)
                    throw new HttpFailure(status, WeatherPolicy.retryAfterMillis(
                            connection.getHeaderField("Retry-After"), System.currentTimeMillis()));
                int length = connection.getContentLength();
                if (length > MAX_BYTES) throw new InvalidResponseException("Weather response too large");
                try (InputStream input = connection.getInputStream();
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        int remaining = remainingMillis(deadline);
                        checkInterrupted();
                        connection.setReadTimeout((int) Math.min(READ_TIMEOUT_MILLIS, remaining));
                        int count = input.read(buffer);
                        checkInterrupted();
                        if (count < 0) break;
                        if (bytes.size() + count > MAX_BYTES) throw new InvalidResponseException("Weather response too large");
                        bytes.write(buffer, 0, count);
                    }
                    remainingMillis(deadline);
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
    }

    public static final class JsonDecoder implements Decoder {
        @Override public List<City> cities(String body) throws JSONException {
            return readCities(body, 8, false);
        }
        @Override public List<City> cityMetadata(String body) throws JSONException {
            return readCities(body, 100, true);
        }
        private static List<City> readCities(String body, int limit, boolean allowSingle) throws JSONException {
            if (body == null || body.length() > HttpsTransport.MAX_BYTES) throw new JSONException("Geocoding response too large");
            JSONObject root = new JSONObject(body);
            if (root.optBoolean("error", false)) throw new JSONException("Geocoding error");
            JSONArray values = root.optJSONArray("results");
            if (values == null && allowSingle && root.has("id")) values = new JSONArray().put(root);
            if (values == null) return Collections.emptyList();
            ArrayList<City> cities = new ArrayList<>();
            for (int index = 0; index < Math.min(values.length(), limit); index++) {
                JSONObject value = values.getJSONObject(index);
                cities.add(new City(value.optString("id", ""), value.getString("name"),
                        value.optString("country", ""), value.optString("admin1", ""),
                        value.getDouble("latitude"), value.getDouble("longitude"), value.optString("timezone", "")));
            }
            return cities;
        }
        @Override public Weather current(String body, String units, long fetchedAtMillis) throws JSONException {
            if (body == null || body.length() > HttpsTransport.MAX_BYTES) throw new JSONException("Weather response too large");
            JSONObject root = new JSONObject(body);
            if (root.optBoolean("error", false)) throw new JSONException("Weather error");
            JSONObject current = root.getJSONObject("current");
            long seconds = current.getLong("time");
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) throw new JSONException("Invalid time");
            int isDay = current.getInt("is_day");
            if (isDay != 0 && isDay != 1) throw new JSONException("Invalid day flag");
            JSONObject daily = root.optJSONObject("daily");
            String timezone = root.optString("timezone", "GMT");
            return new Weather(current.getDouble("temperature_2m"), current.getInt("weather_code"),
                    isDay == 1, current.getDouble("wind_speed_10m"), seconds * 1000, fetchedAtMillis, units,
                    readHours(root.optJSONObject("hourly"), fetchedAtMillis),
                    firstTime(daily, "sunrise"), firstTime(daily, "sunset"), timezone, Provider.OPEN_METEO, 0,
                    readDays(root, daily, timezone, units, fetchedAtMillis),
                    root.opt("timezone") instanceof String && forecastZone(timezone) != null);
        }
        private static List<Day> readDays(JSONObject root, JSONObject daily, String timezone, String units, long fetched) {
            ZoneId zone = forecastZone(timezone);
            JSONArray times = daily == null ? null : daily.optJSONArray("time");
            if (!(root.opt("timezone") instanceof String) || zone == null || times == null
                    || times.length() == 0 || times.length() > 8 || fetched <= 0)
                return Collections.emptyList();
            JSONObject metadata = root.optJSONObject("daily_units");
            String temperatureUnit = "fahrenheit".equals(units) ? "°F" : "°C";
            boolean temperatureUnits = matchesUnit(metadata, "temperature_2m_min", temperatureUnit)
                    && matchesUnit(metadata, "temperature_2m_max", temperatureUnit);
            LocalDate today = Instant.ofEpochMilli(fetched).atZone(zone).toLocalDate();
            // Open-Meteo daily products use the request-time UTC offset across DST changes.
            // Recover their advertised date using that offset, retaining the actual source window separately.
            ZoneOffset sourceOffset;
            try { sourceOffset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds",
                    zone.getRules().getOffset(Instant.ofEpochMilli(fetched)).getTotalSeconds())); }
            catch (RuntimeException invalidOffset) { return Collections.emptyList(); }
            ArrayList<Day> days = new ArrayList<>(MAX_FORECAST_DAYS);
            long previous = 0;
            for (int index = 0; index < times.length(); index++) {
                long sourceStart = unixMillis(strictLong(times, index));
                if (sourceStart <= previous) return Collections.emptyList();
                previous = sourceStart;
                LocalDate date = Instant.ofEpochMilli(sourceStart).atOffset(sourceOffset).toLocalDate();
                if (date.isBefore(today) || !date.isBefore(today.plusDays(MAX_FORECAST_DAYS))) continue;
                long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
                long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                long sourceEnd = sourceStart + 24 * 3_600_000L;
                Day day = new Day(date.toString(), start, end, code(daily.optJSONArray("weather_code"), index, times.length()),
                        temperatureUnits ? number(daily, "temperature_2m_min", index, times.length()) : Double.NaN,
                        temperatureUnits ? number(daily, "temperature_2m_max", index, times.length()) : Double.NaN,
                        matchesUnit(metadata, "precipitation_sum", "mm") ? number(daily, "precipitation_sum", index, times.length()) : Double.NaN,
                        matchesUnit(metadata, "precipitation_probability_max", "%") ? number(daily, "precipitation_probability_max", index, times.length()) : Double.NaN,
                        matchesUnit(metadata, "wind_speed_10m_max", "km/h") ? number(daily, "wind_speed_10m_max", index, times.length()) : Double.NaN,
                        matchesUnit(metadata, "wind_gusts_10m_max", "km/h") ? number(daily, "wind_gusts_10m_max", index, times.length()) : Double.NaN,
                        matchesUnit(metadata, "uv_index_max", "") ? number(daily, "uv_index_max", index, times.length()) : Double.NaN,
                        time(daily, "sunrise", index, times.length()), time(daily, "sunset", index, times.length()),
                        false, false, sourceStart, sourceEnd);
                if (day.isValid()) days.add(day);
            }
            return days;
        }
        private static boolean matchesUnit(JSONObject metadata, String field, String expected) {
            return metadata == null || !metadata.has(field) || expected.equals(metadata.optString(field));
        }
        private static double number(JSONObject object, String field, int index, int length) {
            JSONArray values = object.optJSONArray(field);
            Object value = values == null || values.length() != length ? null : values.opt(index);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        }
        private static int code(JSONArray values, int index, int length) {
            Object value = values == null || values.length() != length ? null : values.opt(index);
            if (!(value instanceof Number)) return -1;
            double number = ((Number) value).doubleValue();
            int code = ((Number) value).intValue();
            return number == code && validWeatherCode(code) ? code : -1;
        }
        private static long strictLong(JSONArray values, int index) {
            Object value = values.opt(index);
            if (!(value instanceof Number)) return 0;
            long time = ((Number) value).longValue();
            return ((Number) value).doubleValue() == time ? time : 0;
        }
        private static long time(JSONObject daily, String key, int index, int length) {
            JSONArray values = daily.optJSONArray(key);
            return values == null || values.length() != length ? 0 : unixMillis(strictLong(values, index));
        }
        private static List<Hour> readHours(JSONObject hourly, long fetchedAtMillis) {
            if (hourly == null) return Collections.emptyList();
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temperatures = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            JSONArray phase = hourly.optJSONArray("is_day");
            if (times == null || temperatures == null || codes == null
                    || times.length() != temperatures.length() || times.length() != codes.length())
                return Collections.emptyList();
            ArrayList<Hour> hours = new ArrayList<>();
            for (int index = 0; index < Math.min(times.length(), MAX_FORECAST_HOURS); index++) {
                long time = unixMillis(times.optLong(index, 0));
                if (time < fetchedAtMillis - 24 * HOUR_MILLIS || time > fetchedAtMillis + 72 * HOUR_MILLIS) continue;
                Object day = phase != null && phase.length() == times.length() ? phase.opt(index) : null;
                Boolean isDay = day instanceof Number && ((Number) day).doubleValue() == 1 ? Boolean.TRUE
                        : day instanceof Number && ((Number) day).doubleValue() == 0 ? Boolean.FALSE : null;
                Hour hour = new Hour(time, temperatures.optDouble(index, Double.NaN), codes.optInt(index, -1), isDay);
                if (hour.isValid()) hours.add(hour);
            }
            return hours;
        }
        private static long firstTime(JSONObject daily, String key) {
            JSONArray values = daily == null ? null : daily.optJSONArray(key);
            return values == null ? 0 : unixMillis(values.optLong(0, 0));
        }
        private static long unixMillis(long seconds) {
            return seconds > 0 && seconds <= Long.MAX_VALUE / 1000 ? seconds * 1000 : 0;
        }
    }

    /** Pure serialization seam for persisted current and optional forecast data. */
    public static final class CacheCodec {
        private static final int MAX_SNAPSHOT_CHARS = 32_768;
        private static final int FORECAST_SCHEMA = 3;
        private CacheCodec() {}
        public static String encode(Weather weather) throws JSONException {
            JSONObject value = new JSONObject();
            value.put("temperature", weather.temperature).put("code", weather.weatherCode)
                    .put("day", weather.isDay).put("wind", weather.windSpeed)
                    .put("observed", weather.observedAtMillis).put("fetched", weather.fetchedAtMillis)
                    .put("units", weather.units).put("sunrise", weather.sunriseMillis)
                    .put("sunset", weather.sunsetMillis).put("timezone", weather.timezone)
                    .put("city_timezone_known", weather.cityTimezoneKnown)
                    .put("forecast_time_basis", weather.forecastTimeBasis.name()).put("forecast_timezone", weather.forecastTimezone)
                    .put("provider", weather.provider.name()).put("source_expires", weather.sourceExpiresAtMillis);
            JSONArray hours = new JSONArray();
            for (Hour hour : weather.hours) {
                JSONObject row = new JSONObject().put("time", hour.timeMillis).put("temp", hour.temp).put("code", hour.weatherCode);
                if (hour.isDay != null) row.put("is_day", hour.isDay);
                hours.put(row);
            }
            value.put("hours", hours);
            JSONArray days = new JSONArray();
            for (Day day : weather.days) {
                JSONObject row = new JSONObject().put("date", day.date).put("start", day.startMillis)
                        .put("end", day.endMillis).put("code", day.weatherCode)
                        .put("sunrise", day.sunriseMillis).put("sunset", day.sunsetMillis)
                        .put("aggregated", day.aggregated).put("partial", day.partialDay)
                        .put("coverage_start", day.coverageStartMillis).put("coverage_end", day.coverageEndMillis);
                putNumber(row, "min", day.minTemperature); putNumber(row, "max", day.maxTemperature);
                putNumber(row, "precipitation", day.precipitationMm);
                putNumber(row, "probability", day.precipitationProbabilityMax);
                putNumber(row, "wind", day.windSpeedMax); putNumber(row, "gusts", day.windGustsMax);
                putNumber(row, "uv", day.uvIndexMax);
                days.put(row);
            }
            value.put("forecast_schema", FORECAST_SCHEMA).put("days", days);
            return value.toString();
        }
        private static void putNumber(JSONObject row, String key, double value) throws JSONException {
            if (Double.isFinite(value)) row.put(key, value);
        }
        public static Weather decode(String snapshot) throws JSONException {
            if (snapshot == null || snapshot.length() > MAX_SNAPSHOT_CHARS) throw new JSONException("Weather cache too large");
            JSONObject value = new JSONObject(snapshot);
            ArrayList<Hour> hours = new ArrayList<>();
            JSONArray values = value.optJSONArray("hours");
            if (values != null) for (int index = 0; index < Math.min(values.length(), MAX_FORECAST_HOURS); index++) {
                JSONObject hour = values.optJSONObject(index);
                if (hour != null) hours.add(new Hour(hour.optLong("time", 0), hour.optDouble("temp", Double.NaN),
                        hour.optInt("code", -1), hour.opt("is_day") instanceof Boolean ? (Boolean) hour.opt("is_day") : null));
            }
            Provider provider;
            try { provider = Provider.valueOf(value.optString("provider", Provider.OPEN_METEO.name())); }
            catch (IllegalArgumentException invalid) { throw new JSONException("Unknown weather provider"); }
            boolean knownCityTimezone = value.opt("timezone") instanceof String && forecastZone(value.optString("timezone")) != null
                    && (value.has("city_timezone_known") ? Boolean.TRUE.equals(value.opt("city_timezone_known")) : provider == Provider.OPEN_METEO);
            boolean needsForecastUpgrade = !value.has("forecast_schema") || value.optInt("forecast_schema", 0) < FORECAST_SCHEMA;
            int forecastSchema = value.optInt("forecast_schema", 0);
            ForecastTimeBasis basis = ForecastTimeBasis.UNKNOWN;
            boolean validForecastBasis = false;
            if (forecastSchema == 1 && knownCityTimezone) {
                basis = ForecastTimeBasis.CITY; validForecastBasis = true;
            } else if (forecastSchema == 2 || forecastSchema == FORECAST_SCHEMA) {
                if (knownCityTimezone && ForecastTimeBasis.CITY.name().equals(value.optString("forecast_time_basis"))
                        && value.optString("timezone").equals(value.optString("forecast_timezone"))) {
                    basis = ForecastTimeBasis.CITY; validForecastBasis = true;
                } else if (!knownCityTimezone && provider == Provider.MET_NORWAY
                        && ForecastTimeBasis.UTC.name().equals(value.optString("forecast_time_basis"))
                        && "UTC".equals(value.optString("forecast_timezone"))) {
                    basis = ForecastTimeBasis.UTC; validForecastBasis = true;
                }
            }
            ArrayList<Day> days = new ArrayList<>(MAX_FORECAST_DAYS);
            JSONArray dated = value.optJSONArray("days");
            // Legacy/unknown schemas keep valid current weather, without guessing forecast fields.
            if (validForecastBasis && dated != null && dated.length() <= 8) {
                for (int index = 0; index < dated.length(); index++) {
                    JSONObject row = dated.optJSONObject(index);
                    if (row == null || !(row.opt("aggregated") instanceof Boolean)
                            || !(row.opt("partial") instanceof Boolean)
                            || row.optBoolean("aggregated") != (provider == Provider.MET_NORWAY)) continue;
                    Day day = new Day(row.optString("date", ""), row.optLong("start", 0), row.optLong("end", 0),
                            row.optInt("code", -1), cachedNumber(row, "min"), cachedNumber(row, "max"),
                            cachedNumber(row, "precipitation"), cachedNumber(row, "probability"),
                            cachedNumber(row, "wind"), cachedNumber(row, "gusts"), cachedNumber(row, "uv"),
                            row.optLong("sunrise", 0), row.optLong("sunset", 0), row.getBoolean("aggregated"),
                            row.getBoolean("partial"), row.optLong("coverage_start", 0), row.optLong("coverage_end", 0));
                    if (day.isValid()) days.add(day);
                }
            }
            return new Weather(value.getDouble("temperature"), value.getInt("code"), value.getBoolean("day"),
                    value.getDouble("wind"), value.getLong("observed"), value.getLong("fetched"), value.getString("units"),
                    hours, value.optLong("sunrise", 0), value.optLong("sunset", 0), value.optString("timezone", "GMT"),
                    provider, value.optLong("source_expires", 0), days, knownCityTimezone, basis, needsForecastUpgrade);
        }
        private static double cachedNumber(JSONObject row, String key) {
            Object value = row.opt(key);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        }
    }

    private static final class PreferenceCache implements Cache {
        private final SharedPreferences preferences;
        PreferenceCache(Context context) { preferences = context.getSharedPreferences("evebox_weather_cache_v1", Context.MODE_PRIVATE); }
        @Override public Weather read(String key) {
            try {
                if (!key.equals(preferences.getString("key", ""))) return null;
                String snapshot = preferences.getString("snapshot", null);
                if (snapshot != null) return CacheCodec.decode(snapshot);
                return new Weather(Double.parseDouble(preferences.getString("temperature", "NaN")),
                        preferences.getInt("code", -1), preferences.getBoolean("day", true),
                        Double.parseDouble(preferences.getString("wind", "NaN")),
                        preferences.getLong("observed", 0), preferences.getLong("fetched", 0),
                        preferences.getString("units", ""));
            } catch (JSONException | RuntimeException corrupt) { return null; }
        }
        @Override public void write(String key, Weather weather) {
            try {
                preferences.edit().putString("key", key).putString("snapshot", CacheCodec.encode(weather)).apply();
            } catch (JSONException invalid) { throw new IllegalArgumentException("Invalid weather cache", invalid); }
        }
    }
}
