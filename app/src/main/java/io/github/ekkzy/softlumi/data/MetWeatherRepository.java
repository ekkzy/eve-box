package io.github.ekkzy.softlumi.data;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.ekkzy.softlumi.core.WeatherPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** MET Norway's documented compact forecast, used only after an eligible primary failure.
 * Raw responses and validators are isolated from the normalized weather cache. No workers or solar estimates.
 * https://api.met.no/doc/TermsOfService ; data CC BY 4.0, symbols mapped to WMO equivalents.
 */
public final class MetWeatherRepository implements WeatherRepository.Fallback {
    public static final int MAX_BYTES = 262_144;
    private static final long HOUR = 3_600_000L;
    private static final int[] PERIOD_HOURS = {1, 6, 12};
    private static final String[] PERIOD_KEYS = {"next_1_hours", "next_6_hours", "next_12_hours"};
    private static final long MIN_POLL = 30 * 60_000L;
    private static final String BASE = "https://api.met.no/weatherapi/locationforecast/2.0/compact?";

    public static final class Snapshot {
        public final String body, lastModified;
        public final long fetchedAtMillis, expiresAtMillis, requestAfterMillis;
        public Snapshot(String body, String lastModified, long fetchedAtMillis, long expiresAtMillis, long requestAfterMillis) {
            this.body = body == null ? "" : body;
            this.lastModified = safeHeader(lastModified);
            this.fetchedAtMillis = fetchedAtMillis; this.expiresAtMillis = expiresAtMillis;
            this.requestAfterMillis = requestAfterMillis;
        }
    }
    public interface Store {
        Snapshot read(String key);
        void write(String key, Snapshot snapshot);
    }
    public static final class Response {
        public final int status;
        public final String body, lastModified;
        public final long expiresAtMillis;
        public Response(int status, String body, String lastModified, long expiresAtMillis) {
            this.status = status; this.body = body == null ? "" : body;
            this.lastModified = safeHeader(lastModified); this.expiresAtMillis = expiresAtMillis;
        }
    }
    @FunctionalInterface public interface Transport {
        Response get(String url, String ifModifiedSince, int timeoutMillis) throws IOException;
    }
    private final Transport transport;
    private final Store store;
    private final WeatherRepository.Clock clock;
    public MetWeatherRepository(Context context) {
        this(new HttpsTransport(userAgent(context)), new PreferenceStore(context), System::currentTimeMillis);
    }
    public MetWeatherRepository(Transport transport, Store store, WeatherRepository.Clock clock) {
        if (transport == null || store == null || clock == null) throw new IllegalArgumentException("MET boundaries required");
        this.transport = transport; this.store = store; this.clock = clock;
    }
    @Override public synchronized WeatherRepository.Weather load(LauncherPrefs.Settings settings, int timeoutMillis) throws Exception {
        return load(settings, timeoutMillis, "");
    }
    @Override public synchronized WeatherRepository.Weather load(LauncherPrefs.Settings settings, int timeoutMillis,
            String cityTimezone) throws Exception {
        if (settings == null || !settings.weatherEnabled || !settings.hasCity()) throw new IOException("MET requires an explicit city");
        checkInterrupted();
        String key = coordinates(settings.cityLatitude, settings.cityLongitude);
        long now = clock.nowMillis();
        Snapshot saved;
        try { saved = store.read(key); } catch (RuntimeException invalidCache) { saved = null; }
        if (!validSnapshot(saved, now)) saved = null;
        String units = WeatherRepository.resolveUnits(settings.units);
        String selectedTimezone = LauncherPrefs.ianaTimezone(cityTimezone);
        if (selectedTimezone.isEmpty()) selectedTimezone = settings.cityTimezone;
        boolean knownCityTimezone = !selectedTimezone.isEmpty();
        String timezone = knownCityTimezone ? selectedTimezone : "UTC";
        if (saved != null && now < saved.requestAfterMillis) {
            try { return parse(saved.body, units, saved.fetchedAtMillis, saved.expiresAtMillis, now, timezone, knownCityTimezone); }
            catch (JSONException corruptCache) { saved = null; }
        }
        Response response = transport.get(BASE + key, saved == null ? "" : saved.lastModified, timeoutMillis);
        checkInterrupted();
        now = clock.nowMillis();
        if (response.status != 200 && response.status != 203 && response.status != 304)
            throw new WeatherRepository.HttpFailure(response.status);
        if (response.status == 304 && saved == null) throw new JSONException("MET 304 without a cached response");
        String body = response.status == 304 ? saved.body : response.body;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("MET response too large");
        String modified = response.status == 304 && response.lastModified.isEmpty() && saved != null
                ? saved.lastModified : response.lastModified;
        long expiry = response.expiresAtMillis > 0 ? response.expiresAtMillis : now + MIN_POLL;
        // Revalidation is a real network check. A 304 never changes the forecast sample's timestamp or values.
        Snapshot next = new Snapshot(body, modified, now, expiry,
                Math.max(expiry, now + MIN_POLL) + ThreadLocalRandom.current().nextLong(120_001));
        WeatherRepository.Weather weather = parse(body, units, next.fetchedAtMillis, expiry, now, timezone, knownCityTimezone);
        checkInterrupted();
        try { store.write(key, next); } catch (RuntimeException cacheFailure) { /* Keep the valid network response. */ }
        return weather;
    }
    @Override public synchronized WeatherRepository.Weather reparseCached(LauncherPrefs.Settings settings, String cityTimezone)
            throws IOException, JSONException {
        checkInterrupted();
        if (settings == null || !settings.weatherEnabled || !settings.hasCity()) return null;
        long now = clock.nowMillis();
        Snapshot saved;
        try { saved = store.read(coordinates(settings.cityLatitude, settings.cityLongitude)); }
        catch (RuntimeException corruptCache) { return null; }
        if (!validSnapshot(saved, now)) return null;
        String zone = LauncherPrefs.ianaTimezone(cityTimezone);
        boolean known = !zone.isEmpty();
        WeatherRepository.Weather weather = parse(saved.body, WeatherRepository.resolveUnits(settings.units),
                saved.fetchedAtMillis, saved.expiresAtMillis, now, known ? zone : "UTC", known);
        checkInterrupted();
        return weather;
    }
    private static boolean validSnapshot(Snapshot value, long now) {
        return value != null && !value.body.isEmpty() && value.body.length() <= MAX_BYTES
                && value.fetchedAtMillis > 0 && value.fetchedAtMillis <= now
                && now - value.fetchedAtMillis <= WeatherPolicy.MAX_STALE_MILLIS;
    }
    public static String coordinates(double latitude, double longitude) {
        if (!WeatherPolicy.validCoordinates(latitude, longitude)) throw new IllegalArgumentException("Invalid MET coordinates");
        return "lat=" + coordinateParameter(latitude) + "&lon=" + coordinateParameter(longitude);
    }
    private static String coordinateParameter(double coordinate) {
        // MET permits at most four decimal places. Integer conversion truncates toward zero,
        // using decimal arithmetic so binary floating-point does not move a boundary value.
        BigDecimal decimal = BigDecimal.valueOf(coordinate).movePointRight(4);
        return new BigDecimal(decimal.toBigInteger(), 4).toPlainString();
    }
    public static String userAgent(Context context) {
        String version = "development";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception unavailable) { /* The application/package identity remains truthful. */ }
        return "softlumi/" + safeHeader(version) + " (" + safeHeader(context.getPackageName()) + "; Android TV launcher)";
    }
    private static String safeHeader(String value) {
        if (value == null || value.length() > 256 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return "";
        return value;
    }

    public static WeatherRepository.Weather parse(String body, String units, long fetchedAtMillis,
            long expiresAtMillis, long now, String timezone) throws JSONException {
        return parse(body, units, fetchedAtMillis, expiresAtMillis, now, timezone, true);
    }
    private static WeatherRepository.Weather parse(String body, String units, long fetchedAtMillis,
            long expiresAtMillis, long now, String timezone, boolean knownCityTimezone) throws JSONException {
        if (body == null || body.length() > MAX_BYTES) throw new JSONException("MET response too large");
        JSONObject properties = new JSONObject(body).getJSONObject("properties");
        JSONObject unit = properties.getJSONObject("meta").getJSONObject("units");
        if (!"celsius".equals(unit.optString("air_temperature")) || !"m/s".equals(unit.optString("wind_speed")))
            throw new JSONException("Unsupported MET units");
        JSONArray timeseries = properties.getJSONArray("timeseries");
        ArrayList<WeatherRepository.Hour> hours = new ArrayList<>();
        Map<LocalDate, DayAccumulator> days = new LinkedHashMap<>();
        ZoneId zone = knownCityTimezone ? WeatherRepository.forecastZone(timezone) : ZoneOffset.UTC;
        WeatherRepository.ForecastTimeBasis basis = knownCityTimezone ? WeatherRepository.ForecastTimeBasis.CITY
                : WeatherRepository.ForecastTimeBasis.UTC;
        if (zone != null && now > 0) {
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            for (int index = 0; index < WeatherRepository.MAX_FORECAST_DAYS; index++) {
                LocalDate date = today.plusDays(index);
                days.put(date, new DayAccumulator(date, zone, units, unit));
            }
        }
        Sample current = null, future = null;
        long previousTime = 0;
        for (int index = 0; index < Math.min(timeseries.length(), 240); index++) {
            JSONObject row = timeseries.getJSONObject(index);
            long time = timestamp(row.optString("time"));
            if (time <= 0 || time <= previousTime) throw new JSONException("Invalid MET sample times");
            previousTime = time;
            JSONObject data = row.optJSONObject("data");
            JSONObject instant = data == null ? null : data.optJSONObject("instant");
            JSONObject detail = instant == null ? null : instant.optJSONObject("details");
            if (zone != null && !days.isEmpty()) {
                DayAccumulator day = days.get(Instant.ofEpochMilli(time).atZone(zone).toLocalDate());
                if (day != null) day.add(time, detail, data);
            }
            String symbol = displaySymbol(data);
            int code = wmoCode(symbol);
            double temperature = detail == null ? Double.NaN : detail.optDouble("air_temperature", Double.NaN);
            double wind = detail == null ? Double.NaN : detail.optDouble("wind_speed", Double.NaN);
            if (code < 0 || !Double.isFinite(temperature) || !Double.isFinite(wind) || wind < 0) continue;
            double converted = "fahrenheit".equals(units) ? temperature * 9 / 5 + 32 : temperature;
            Sample sample = new Sample(time, converted, code, wind * 3.6, symbol.endsWith("_day"));
            if (time <= now && now - time <= 90 * 60_000L) current = sample;
            else if (future == null && time > now && time - now <= HOUR) future = sample;
            if (time >= now - 24 * HOUR && time <= now + 72 * HOUR
                    && hours.size() < WeatherRepository.MAX_FORECAST_HOURS)
                hours.add(new WeatherRepository.Hour(time, converted, code, symbol.endsWith("_day") ? Boolean.TRUE
                        : symbol.endsWith("_night") ? Boolean.FALSE : null));
        }
        if (current == null) current = future;
        if (current == null) throw new JSONException("No current MET forecast sample");
        List<WeatherRepository.Day> forecast = new ArrayList<>(WeatherRepository.MAX_FORECAST_DAYS);
        for (DayAccumulator value : days.values()) {
            WeatherRepository.Day day = value.build();
            if (day.isValid()) forecast.add(day);
        }
        WeatherRepository.Weather weather = new WeatherRepository.Weather(current.temperature, current.code,
                current.day, current.wind, current.time, fetchedAtMillis, units, hours, 0, 0, timezone,
                WeatherRepository.Provider.MET_NORWAY, expiresAtMillis, forecast, knownCityTimezone, basis);
        if (!weather.isValid()) throw new JSONException("Invalid MET current values");
        return weather;
    }
    private static String displaySymbol(JSONObject data) {
        if (data == null) return "";
        for (String key : PERIOD_KEYS) {
            String symbol = periodSymbol(data.optJSONObject(key));
            if (wmoCode(symbol) >= 0) return symbol;
        }
        return "";
    }
    private static String periodSymbol(JSONObject period) {
        JSONObject summary = period == null ? null : period.optJSONObject("summary");
        return summary == null ? "" : summary.optString("symbol_code", "");
    }
    private static double number(JSONObject object, String field) {
        Object value = object == null ? null : object.opt(field);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }
    private static double maximum(double first, double second) {
        return !Double.isFinite(second) ? first : !Double.isFinite(first) ? second : Math.max(first, second);
    }
    private static double minimum(double first, double second) {
        return !Double.isFinite(second) ? first : !Double.isFinite(first) ? second : Math.min(first, second);
    }
    /** Never prorates a 6/12-hour sum across local midnight or counts overlapping periods twice.
     * Instant extrema remain sampled extrema; aggregated=true makes that distinction persistent.
     */
    private static final class DayAccumulator {
        final LocalDate date;
        final long start, end;
        final String units;
        final JSONObject metadata;
        long coverageStart, coverageEnd, periodEnd, coveredMillis, precipitationCoveredMillis;
        int code = -1;
        double low = Double.NaN, high = Double.NaN, wind = Double.NaN, gust = Double.NaN;
        double precipitation, probability = Double.NaN;
        DayAccumulator(LocalDate date, ZoneId zone, String units, JSONObject metadata) {
            this.date = date; this.units = units; this.metadata = metadata;
            start = date.atStartOfDay(zone).toInstant().toEpochMilli();
            end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        void add(long time, JSONObject instant, JSONObject data) {
            double temperature = temperature(number(instant, "air_temperature"));
            double speed = WeatherRepository.optionalNumber(number(instant, "wind_speed") * 3.6, 0, 500);
            if (Double.isFinite(temperature) || Double.isFinite(speed)) include(time, time);
            low = minimum(low, temperature); high = maximum(high, temperature); wind = maximum(wind, speed);
            if ("m/s".equals(metadata.optString("wind_speed_of_gust")))
                gust = maximum(gust, WeatherRepository.optionalNumber(number(instant, "wind_speed_of_gust") * 3.6, 0, 500));
            if (data == null) return;
            // Min/max may be supplied for 6/12-hour periods even while one-hour rain is present.
            // Overlap is harmless for extrema, but a period crossing midnight still does not belong to one day.
            for (int index = 0; index < PERIOD_HOURS.length; index++) {
                JSONObject period = data.optJSONObject(PERIOD_KEYS[index]);
                if (period == null || time + PERIOD_HOURS[index] * HOUR > end) continue;
                JSONObject detail = period.optJSONObject("details");
                double periodLow = "celsius".equals(metadata.optString("air_temperature_min"))
                        ? temperature(number(detail, "air_temperature_min")) : Double.NaN;
                double periodHigh = "celsius".equals(metadata.optString("air_temperature_max"))
                        ? temperature(number(detail, "air_temperature_max")) : Double.NaN;
                if (Double.isFinite(periodLow) && Double.isFinite(periodHigh) && periodLow > periodHigh) continue;
                low = minimum(low, periodLow); high = maximum(high, periodHigh);
            }
            if (time < periodEnd) return;
            for (int index = 0; index < PERIOD_HOURS.length; index++) {
                int span = PERIOD_HOURS[index];
                JSONObject period = data.optJSONObject(PERIOD_KEYS[index]);
                long last = time + span * HOUR;
                if (period == null || last > end) continue;
                include(time, last);
                coveredMillis += last - time;
                periodEnd = last;
                code = Math.max(code, wmoCode(periodSymbol(period)));
                JSONObject detail = period.optJSONObject("details");
                if ("mm".equals(metadata.optString("precipitation_amount"))) {
                    double amount = WeatherRepository.optionalNumber(number(detail, "precipitation_amount"), 0, 3000);
                    if (Double.isFinite(amount)) { precipitation += amount; precipitationCoveredMillis += last - time; }
                }
                if ("%".equals(metadata.optString("probability_of_precipitation")))
                    probability = maximum(probability, WeatherRepository.optionalNumber(number(detail, "probability_of_precipitation"), 0, 100));
                return;
            }
        }
        private double temperature(double celsius) {
            double valid = WeatherRepository.optionalNumber(celsius, -130, 100);
            return "fahrenheit".equals(units) ? valid * 9 / 5 + 32 : valid;
        }
        private void include(long first, long last) {
            coverageStart = coverageStart == 0 ? first : Math.min(coverageStart, first);
            coverageEnd = Math.max(coverageEnd, last);
        }
        WeatherRepository.Day build() {
            return new WeatherRepository.Day(date.toString(), start, end, code, low, high,
                    precipitationCoveredMillis > 0 ? precipitation : Double.NaN, probability, wind, gust, Double.NaN,
                    0, 0, true, coveredMillis != end - start
                    || precipitationCoveredMillis > 0 && precipitationCoveredMillis != end - start, coverageStart, coverageEnd);
        }
    }
    private static final class Sample {
        final long time; final double temperature, wind; final int code; final boolean day;
        Sample(long time, double temperature, int code, double wind, boolean day) {
            this.time = time; this.temperature = temperature; this.code = code; this.wind = wind; this.day = day;
        }
    }
    private static long timestamp(String value) {
        try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
        catch (RuntimeException invalid) { return 0; }
    }
    private static final Map<String, Integer> WEATHER_CODES = weatherCodes();

    /** Independently maintained coarse display equivalents of documented MET symbol names. */
    static int wmoCode(String symbol) {
        if (symbol == null) return -1;
        String name = symbol.toLowerCase(Locale.ROOT);
        int suffixAt = name.lastIndexOf('_');
        if (suffixAt >= 0) {
            String variant = name.substring(suffixAt + 1);
            if ("day".equals(variant) || "night".equals(variant) || "polartwilight".equals(variant))
                name = name.substring(0, suffixAt);
        }
        Integer code = WEATHER_CODES.get(name);
        return code == null ? -1 : code;
    }
    private static Map<String, Integer> weatherCodes() {
        Map<String, Integer> codes = new HashMap<>();
        register(codes, 0, "clearsky"); register(codes, 1, "fair");
        register(codes, 2, "partlycloudy"); register(codes, 3, "cloudy"); register(codes, 45, "fog");
        register(codes, 61, "lightrain"); register(codes, 63, "rain"); register(codes, 65, "heavyrain");
        register(codes, 68, "lightsleet", "sleet", "heavysleet");
        register(codes, 71, "lightsnow"); register(codes, 73, "snow"); register(codes, 75, "heavysnow");
        register(codes, 80, "lightrainshowers"); register(codes, 81, "rainshowers"); register(codes, 82, "heavyrainshowers");
        register(codes, 83, "lightsleetshowers", "sleetshowers", "heavysleetshowers");
        register(codes, 85, "lightsnowshowers"); register(codes, 86, "snowshowers", "heavysnowshowers");
        register(codes, 95, "lightrainandthunder", "rainandthunder", "heavyrainandthunder",
                "lightsleetandthunder", "sleetandthunder", "heavysleetandthunder",
                "lightsnowandthunder", "snowandthunder", "heavysnowandthunder",
                "lightrainshowersandthunder", "rainshowersandthunder", "heavyrainshowersandthunder",
                // Locationforecast 2.0 documents these two historical spelling errors.
                "lightssleetshowersandthunder", "sleetshowersandthunder", "heavysleetshowersandthunder",
                "lightssnowshowersandthunder", "snowshowersandthunder", "heavysnowshowersandthunder");
        return Collections.unmodifiableMap(codes);
    }
    private static void register(Map<String, Integer> codes, int value, String... symbols) {
        for (String symbol : symbols) codes.put(symbol, value);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("MET request interrupted");
    }
    private static int remaining(long deadline) throws SocketTimeoutException {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis <= 0) throw new SocketTimeoutException("MET deadline exceeded");
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
    public static final class HttpsTransport implements Transport {
        private static final int MAX_REDIRECTS = 2;
        private static final int TOTAL_TIMEOUT_MILLIS = 10_000;
        private static final int IO_TIMEOUT_MILLIS = 4_000;
        @FunctionalInterface interface ConnectionFactory { HttpURLConnection open(URL endpoint) throws IOException; }
        private final String userAgent;
        private final ConnectionFactory connections;
        public HttpsTransport(String userAgent) {
            this(userAgent, endpoint -> (HttpURLConnection) endpoint.openConnection());
        }
        HttpsTransport(String userAgent, ConnectionFactory connections) {
            this.userAgent = safeHeader(userAgent);
            if (this.userAgent.isEmpty()) throw new IllegalArgumentException("MET requires application identification");
            if (connections == null) throw new IllegalArgumentException("A connection factory is required");
            this.connections = connections;
        }
        @Override public Response get(String address, String ifModifiedSince, int timeoutMillis) throws IOException {
            if (timeoutMillis <= 0) throw new SocketTimeoutException("MET deadline exceeded");
            long deadline = System.nanoTime() + Math.min(TOTAL_TIMEOUT_MILLIS, timeoutMillis) * 1_000_000L;
            URL endpoint = new URL(address);
            String validator = safeHeader(ifModifiedSince);
            for (int hop = 0; ; hop++) {
                checkInterrupted();
                requireServiceEndpoint(endpoint);
                HttpURLConnection connection = connections.open(endpoint);
                try {
                    configure(connection, validator, deadline);
                    connection.connect();
                    checkInterrupted(); connection.setReadTimeout(ioTimeout(deadline));
                    int status = connection.getResponseCode();
                    checkInterrupted(); remaining(deadline);
                    if (isRedirect(status)) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isEmpty() || hop >= MAX_REDIRECTS)
                            throw new ProtocolException("MET redirect limit or missing destination");
                        endpoint = new URL(endpoint, location);
                        continue;
                    }
                    if (status != 200 && status != 203 && status != 304)
                        throw new WeatherRepository.HttpFailure(status, WeatherPolicy.retryAfterMillis(
                                connection.getHeaderField("Retry-After"), System.currentTimeMillis()));
                    String payload = status == 304 ? "" : readPayload(connection, deadline);
                    return new Response(status, payload, connection.getHeaderField("Last-Modified"),
                            connection.getHeaderFieldDate("Expires", 0));
                } finally { connection.disconnect(); }
            }
        }
        private void configure(HttpURLConnection connection, String validator, long deadline) throws IOException {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(ioTimeout(deadline));
            connection.setReadTimeout(ioTimeout(deadline));
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
            if (!validator.isEmpty()) connection.setRequestProperty("If-Modified-Since", validator);
        }
        private static void requireServiceEndpoint(URL endpoint) throws ProtocolException {
            boolean standardPort = endpoint.getPort() == -1 || endpoint.getPort() == 443;
            if (!"https".equals(endpoint.getProtocol()) || !"api.met.no".equals(endpoint.getHost())
                    || endpoint.getUserInfo() != null || !standardPort)
                throw new ProtocolException("Unsupported MET service destination");
        }
        private static boolean isRedirect(int status) {
            switch (status) {
                case 301: case 302: case 303: case 307: case 308: return true;
                default: return false;
            }
        }
        private static int ioTimeout(long deadline) throws SocketTimeoutException {
            return Math.min(IO_TIMEOUT_MILLIS, remaining(deadline));
        }
        private static String readPayload(HttpURLConnection connection, long deadline) throws IOException {
            if (connection.getContentLength() > MAX_BYTES) throw new IOException("MET response too large");
            InputStream raw = connection.getInputStream();
            InputStream decoded;
            try {
                String encoding = connection.getContentEncoding();
                if (encoding == null || encoding.isEmpty() || "identity".equalsIgnoreCase(encoding)) decoded = raw;
                else if ("gzip".equalsIgnoreCase(encoding)) decoded = new GZIPInputStream(raw);
                else if ("deflate".equalsIgnoreCase(encoding)) decoded = new InflaterInputStream(raw);
                else throw new ProtocolException("Unsupported MET content encoding");
            } catch (IOException failure) {
                raw.close(); throw failure;
            }
            try (InputStream input = decoded; ByteArrayOutputStream payload = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                for (;;) {
                    checkInterrupted(); connection.setReadTimeout(ioTimeout(deadline));
                    int count = input.read(chunk);
                    checkInterrupted(); remaining(deadline);
                    if (count < 0) return new String(payload.toByteArray(), StandardCharsets.UTF_8);
                    if (count > MAX_BYTES - payload.size()) throw new IOException("MET response too large");
                    payload.write(chunk, 0, count);
                }
            }
        }
    }
    private static final class PreferenceStore implements Store {
        private final SharedPreferences preferences;
        PreferenceStore(Context context) { preferences = context.getSharedPreferences("softlumi_met_weather_cache_v1", Context.MODE_PRIVATE); }
        @Override public Snapshot read(String key) {
            if (!key.equals(preferences.getString("key", ""))) return null;
            return new Snapshot(preferences.getString("body", ""), preferences.getString("modified", ""),
                    preferences.getLong("fetched", 0), preferences.getLong("expires", 0), preferences.getLong("request_after", 0));
        }
        @Override public void write(String key, Snapshot value) {
            preferences.edit().putString("key", key).putString("body", value.body).putString("modified", value.lastModified)
                    .putLong("fetched", value.fetchedAtMillis).putLong("expires", value.expiresAtMillis)
                    .putLong("request_after", value.requestAfterMillis).apply();
        }
    }
}
