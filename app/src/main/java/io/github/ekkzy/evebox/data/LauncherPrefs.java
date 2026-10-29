package io.github.ekkzy.evebox.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import io.github.ekkzy.evebox.core.FavoriteOrder;
import io.github.ekkzy.evebox.core.WeatherPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneId;
import org.json.JSONException;
import org.json.JSONObject;

/** One persisted source of truth for Home. Card format and palette are independent. */
public final class LauncherPrefs {
    public static final String FILE_NAME = "evebox_launcher_v2";
    public static final int SCHEMA_VERSION = 8;
    public static final String CARD_FORMAT_LANDSCAPE = "landscape";
    public static final String CARD_FORMAT_POSTER = "poster";
    public static final int MAX_HIDDEN_PACKAGES = 128;
    public static final int MAX_CUSTOM_LABELS = 128;
    public static final int MAX_RECOMMENDATION_CHANNELS = 6; // Legacy stored channel bindings only.
    public static final int MAX_RECOMMENDATION_PACKAGES = 6;
    public static final int MAX_RECOMMENDATION_SOURCES = 16;
    private static final int MAX_RECOMMENDATION_CHANNEL_KEY_LENGTH = 384;
    private static final String[] RETIRED_KEYS = {"theme", "selected_theme", "layout", "layout_index",
            "screensaver_source", "screensaver_video_uri", "screensaver_scene", "screensaver_period",
            "screensaver_interval_seconds", "screensaver_motion", "screensaver_clock", "recommendation_channels"};
    private static final Set<String> TIMEZONE_IDS = Collections.unmodifiableSet(ZoneId.getAvailableZoneIds());
    private final SharedPreferences prefs;

    public static final class Settings {
        public final String cardFormat;
        public final int background;
        public final boolean weatherEnabled;
        public final String cityName;
        public final double cityLatitude;
        public final double cityLongitude;
        /** Local geocoder metadata. Deliberately excluded from the settings-only backup contract. */
        public final String cityId;
        public final String cityTimezone;
        public final String units;
        public final String language;
        public final boolean reducedMotion;
        public final boolean sounds;
        public final List<String> favoriteIds;
        public final String paletteMode;
        public final int wallpaperDim;
        public final String wallpaperPeriod;
        public final int dayStartMinutes, nightStartMinutes;
        public final Set<String> hiddenPackages;
        public final Map<String, String> customLabels;
        public final boolean watchNextVisible;
        public final boolean recommendationsEnabled;
        /** Ordered local app selections; new eligible shelves of an enabled package are included. */
        public final List<String> recommendationPackages;
        public final List<String> recommendationSourceOrder;

        private Settings(String cardFormat, int background, boolean weatherEnabled, String cityName,
                double cityLatitude, double cityLongitude, String units, String language,
                boolean reducedMotion, boolean sounds, List<String> favoriteIds, Map<String, ?> data) {
            this.cardFormat = cardFormat;
            this.background = background;
            this.weatherEnabled = weatherEnabled;
            this.cityName = cityName;
            this.cityLatitude = cityLatitude;
            this.cityLongitude = cityLongitude;
            boolean sameCity = cityMetadataKey(cityName, cityLatitude, cityLongitude).equals(data.get("city_metadata_key"));
            this.cityId = sameCity ? geonameId(data.get("city_id")) : "";
            this.cityTimezone = sameCity ? ianaTimezone(data.get("city_timezone")) : "";
            this.units = units;
            this.language = language;
            this.reducedMotion = reducedMotion;
            this.sounds = sounds;
            this.favoriteIds = FavoriteOrder.normalize(favoriteIds);
            this.paletteMode = oneOf(data.get("palette_mode"), "auto", "auto", "light", "dark");
            this.wallpaperDim = integer(data.get("wallpaper_dim"), 15, 0, 60);
            this.wallpaperPeriod = oneOf(data.get("wallpaper_period"), "auto", "auto", "schedule", "day", "night");
            this.dayStartMinutes = integer(data.get("day_start_minutes"), 420, 0, 1439);
            int nightStart = integer(data.get("night_start_minutes"), 1140, 0, 1439);
            this.nightStartMinutes = nightStart == dayStartMinutes ? (dayStartMinutes + 720) % 1440 : nightStart;
            this.hiddenPackages = normalizePackages(data.get("hidden_packages"));
            this.customLabels = decodeLabels(data.get("custom_labels"));
            this.watchNextVisible = bool(data.get("watch_next_visible"), true);
            // A corrupt string/number is not consent to show recommendations.
            this.recommendationsEnabled = Boolean.TRUE.equals(data.get("recommendations_enabled"));
            this.recommendationPackages = data.containsKey("recommendation_packages")
                    ? normalizeRecommendationPackages(data.get("recommendation_packages"))
                    : integer(data.get("schema_version"), 0, 0, Integer.MAX_VALUE) < 8
                    ? legacyRecommendationPackages(data.get("recommendation_channels")) : Collections.emptyList();
            this.recommendationSourceOrder = normalizeRecommendationList(data.get("recommendation_source_order"), false);
        }

        public boolean hasCity() {
            return !cityName.isEmpty() && WeatherPolicy.validCoordinates(cityLatitude, cityLongitude);
        }
    }

    public LauncherPrefs(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        int version = integer(prefs.getAll().get("schema_version"), 0, 0, Integer.MAX_VALUE);
        if (version < SCHEMA_VERSION) {
            // The new application ID has its own sandbox. Older app settings enter
            // only through the validated document import, never by probing files.
            Map<String, Object> previous = new HashMap<>(prefs.getAll());
            if (version < 4) {
                previous.put("recommendations_enabled", false);
                previous.put("recommendation_channels", "");
                previous.put("recommendation_packages", "");
                previous.put("recommendation_source_order", "");
            }
            // Schemas before 5 had no supported poster format.
            if (version < 5) previous.put("theme", 0);
            writeAll(decode(previous));
        } else if (version == SCHEMA_VERSION) {
            Map<String, ?> current = prefs.getAll();
            boolean retired = false;
            for (String key : RETIRED_KEYS) if (current.containsKey(key)) { retired = true; break; }
            Settings settings = decode(current);
            if (retired || !settings.cardFormat.equals(current.get("card_format"))) writeAll(settings);
        }
    }

    /** Package-private persistence seam used by atomic-import tests. */
    LauncherPrefs(SharedPreferences preferences) { this.prefs = preferences; }

    public synchronized Settings read() { return decode(prefs.getAll()); }

    /** Pure migration/validation seam: accepts typed values from any supported schema. */
    public static Settings decode(Map<String, ?> values) {
        Map<String, ?> data = values == null ? Collections.emptyMap() : values;
        int version = integer(data.get("schema_version"), 0, 0, Integer.MAX_VALUE);
        String cardFormat;
        if (data.containsKey("card_format")) cardFormat = normalizedCardFormat(data.get("card_format"));
        else if (data.containsKey("schema_version") && (version < 5 || version >= 7))
            cardFormat = CARD_FORMAT_LANDSCAPE;
        else cardFormat = legacyCardFormat(first(data, "theme", "selected_theme", "layout", "layout_index"));
        int background = integer(first(data, "background", "selected_background", "wallpaper"),
                WallpaperCatalog.DEFAULT_ID, WallpaperCatalog.DEFAULT_ID, WallpaperCatalog.MAX_ID);
        String city = text(first(data, "city_name", "weather_city", "city"), "", 128);
        double latitude = number(first(data, "city_latitude", "latitude", "lat"));
        double longitude = number(first(data, "city_longitude", "longitude", "lon"));
        if (!WeatherPolicy.validCoordinates(latitude, longitude) || city.isEmpty()) {
            city = "";
            latitude = Double.NaN;
            longitude = Double.NaN;
        }
        String units = text(first(data, "units", "temperature_unit"), "auto", 16);
        units = "fahrenheit".equalsIgnoreCase(units) || "f".equalsIgnoreCase(units)
                ? "fahrenheit" : "celsius".equalsIgnoreCase(units) || "c".equalsIgnoreCase(units)
                ? "celsius" : "auto";
        String language = text(first(data, "language", "locale"), "system", 16);
        if (!"en".equals(language) && !"ru".equals(language)) language = "system";
        List<String> favorites = decodeFavorites(first(data, "favorite_ids_v2", "favorite_ids",
                "favorites_order", "favorites", "favorite_packages"));
        return new Settings(cardFormat, background,
                bool(first(data, "weather_enabled", "show_weather"), false), city, latitude, longitude,
                units, language, bool(first(data, "reduced_motion", "reduce_motion"), false),
                bool(first(data, "sounds", "sound_enabled"), true), favorites, data);
    }

    public synchronized void setCardFormat(String value) { prefs.edit().putString("card_format", normalizedCardFormat(value)).apply(); }
    public synchronized void setBackground(int value) { prefs.edit().putInt("background", WallpaperCatalog.normalize(value)).apply(); }
    public synchronized void setPaletteMode(String value) { prefs.edit().putString("palette_mode", oneOf(value, "auto", "auto", "light", "dark")).apply(); }
    public synchronized void setWallpaperPeriod(String value) { prefs.edit().putString("wallpaper_period", oneOf(value, "auto", "auto", "schedule", "day", "night")).apply(); }
    public synchronized void setDayNightSchedule(int dayMinutes, int nightMinutes) {
        int day=Math.max(0, Math.min(1439, dayMinutes)), night=Math.max(0, Math.min(1439, nightMinutes));
        if(day==night)night=(day+720)%1440;
        prefs.edit().putInt("day_start_minutes", day).putInt("night_start_minutes", night).apply();
    }
    public synchronized void setWallpaperDim(int value) { prefs.edit().putInt("wallpaper_dim", Math.max(0, Math.min(60, value))).apply(); }
    public synchronized void setWatchNextVisible(boolean value) { prefs.edit().putBoolean("watch_next_visible", value).apply(); }
    public synchronized void setRecommendationsEnabled(boolean value) {
        prefs.edit().putBoolean("recommendations_enabled", value).apply();
    }
    /** Compatibility adapter for callers holding old exact channel bindings. New UI selects packages. */
    public synchronized void setRecommendationChannels(List<String> values) {
        setRecommendationPackages(legacyRecommendationPackages(values));
    }
    public synchronized void setRecommendationPackages(List<String> values) {
        prefs.edit().putString("recommendation_packages", String.join("\n", normalizeRecommendationPackages(values)))
                .remove("recommendation_channels").apply();
    }
    public synchronized void setRecommendationSourceOrder(List<String> values) {
        prefs.edit().putString("recommendation_source_order", String.join("\n", normalizeRecommendationList(values, false))).apply();
    }
    /** One transaction for a deliberate app-selection action. Discovery never opts in another app. */
    public synchronized void setRecommendations(boolean enabled, List<String> packages, List<String> sourceOrder) {
        prefs.edit().putBoolean("recommendations_enabled", enabled)
                .putString("recommendation_packages", String.join("\n", normalizeRecommendationPackages(packages)))
                .remove("recommendation_channels")
                .putString("recommendation_source_order", String.join("\n", normalizeRecommendationList(sourceOrder, false))).apply();
    }
    public synchronized void setHiddenPackages(Collection<String> values) {
        prefs.edit().putStringSet("hidden_packages", new LinkedHashSet<>(normalizePackages(values))).apply();
    }
    public synchronized boolean setPackageHidden(String packageName, boolean hidden) {
        if (!validPackageName(packageName)) return false;
        Set<String> packages = new LinkedHashSet<>(read().hiddenPackages);
        if (hidden && !packages.contains(packageName) && packages.size() >= MAX_HIDDEN_PACKAGES) return false;
        boolean changed = hidden ? packages.add(packageName) : packages.remove(packageName);
        if (changed) setHiddenPackages(packages);
        return changed;
    }
    public synchronized void setCustomLabel(String packageName, String label) {
        if (!validPackageName(packageName)) return;
        Map<String, String> labels = new LinkedHashMap<>(read().customLabels);
        String cleaned = text(label, "", 80);
        if (cleaned.isEmpty()) labels.remove(packageName);
        else if (validLabel(cleaned) && (labels.containsKey(packageName) || labels.size() < MAX_CUSTOM_LABELS)) labels.put(packageName, cleaned);
        prefs.edit().putString("custom_labels", new JSONObject(labels).toString()).apply();
    }
    public synchronized void setWeatherEnabled(boolean value) { prefs.edit().putBoolean("weather_enabled", value).apply(); }
    public synchronized void setReducedMotion(boolean value) { prefs.edit().putBoolean("reduced_motion", value).apply(); }
    public synchronized void setSounds(boolean value) { prefs.edit().putBoolean("sounds", value).apply(); }
    public synchronized void setUnits(String value) {
        prefs.edit().putString("units", "fahrenheit".equals(value) || "celsius".equals(value) ? value : "auto").apply();
    }
    public synchronized void setLanguage(String value) {
        prefs.edit().putString("language", "en".equals(value) || "ru".equals(value) ? value : "system").apply();
    }
    public synchronized void setCity(String name, double latitude, double longitude) {
        Settings current = read();
        boolean unchanged = cityMetadataKey(name, latitude, longitude)
                .equals(cityMetadataKey(current.cityName, current.cityLatitude, current.cityLongitude));
        setCity(name, latitude, longitude, unchanged ? current.cityId : "", unchanged ? current.cityTimezone : "");
    }
    public synchronized void setCity(String name, double latitude, double longitude, String id, String timezone) {
        String cleaned = text(name, "", 128);
        if (cleaned.isEmpty() || !WeatherPolicy.validCoordinates(latitude, longitude)) {
            prefs.edit().remove("city_name").remove("city_latitude").remove("city_longitude")
                    .remove("city_id").remove("city_timezone").remove("city_metadata_key").apply();
            return;
        }
        prefs.edit().putString("city_name", cleaned)
                .putString("city_latitude", Double.toString(latitude))
                .putString("city_longitude", Double.toString(longitude))
                .putString("city_id", geonameId(id)).putString("city_timezone", ianaTimezone(timezone))
                .putString("city_metadata_key", cityMetadataKey(cleaned, latitude, longitude)).apply();
    }

    /** An off-thread lookup cannot attach its result to a newly selected or imported city. */
    public synchronized boolean rememberCityMetadata(Settings expected, String id, String timezone) {
        Settings current = read();
        String zone = ianaTimezone(timezone);
        if (expected == null || !current.weatherEnabled || !current.hasCity() || zone.isEmpty()
                || !cityMetadataKey(expected.cityName, expected.cityLatitude, expected.cityLongitude)
                .equals(cityMetadataKey(current.cityName, current.cityLatitude, current.cityLongitude))) return false;
        String resolvedId = geonameId(id);
        if (zone.equals(current.cityTimezone) && (resolvedId.isEmpty() || resolvedId.equals(current.cityId))) return true;
        prefs.edit().putString("city_id", resolvedId.isEmpty() ? current.cityId : resolvedId)
                .putString("city_timezone", zone)
                .putString("city_metadata_key", cityMetadataKey(current.cityName, current.cityLatitude, current.cityLongitude)).apply();
        return true;
    }

    static String cityMetadataKey(String name, double latitude, double longitude) {
        return text(name, "", 128) + "|" + Double.toString(latitude) + "|" + Double.toString(longitude);
    }
    static String geonameId(Object raw) {
        if (!(raw instanceof String) || !((String) raw).matches("[1-9][0-9]{0,9}")) return "";
        try { return Long.parseLong((String) raw) <= Integer.MAX_VALUE ? (String) raw : ""; }
        catch (NumberFormatException invalid) { return ""; }
    }
    static String ianaTimezone(Object raw) {
        if (!(raw instanceof String)) return "";
        String zone = ((String) raw).trim();
        return zone.length() <= 128 && TIMEZONE_IDS.contains(zone) ? zone : "";
    }

    public synchronized void setFavorites(List<String> ids) {
        prefs.edit().putString("favorite_ids_v2", String.join("\n", FavoriteOrder.normalize(ids))).apply();
    }
    public synchronized boolean addFavorite(String id) {
        List<String> current = read().favoriteIds;
        List<String> next = FavoriteOrder.add(current, id);
        if (next.equals(current)) return false;
        setFavorites(next);
        return true;
    }
    public synchronized boolean removeFavorite(String id) {
        List<String> current = read().favoriteIds;
        List<String> next = FavoriteOrder.remove(current, id);
        if (next.equals(current)) return false;
        setFavorites(next);
        return true;
    }
    public synchronized boolean moveFavorite(String id, int destination) {
        List<String> current = read().favoriteIds;
        List<String> next = FavoriteOrder.move(current, id, destination);
        if (next.equals(current)) return false;
        setFavorites(next);
        return true;
    }

    /** Resolve package-only and short-component legacy IDs after the actual catalog is available. */
    public synchronized void migrateFavoriteComponents(List<AppEntry> apps) {
        if (apps == null || apps.isEmpty()) return;
        List<String> original = read().favoriteIds;
        List<String> resolved = new ArrayList<>();
        for (String id : original) {
            String result = id;
            ComponentName old = ComponentName.unflattenFromString(id);
            for (AppEntry app : apps) {
                if (id.equals(app.packageName) || app.componentName.equals(old)) {
                    result = app.id;
                    break;
                }
            }
            resolved.add(result);
        }
        if (!original.equals(resolved)) setFavorites(resolved);
    }

    private void writeAll(Settings settings) {
        SharedPreferences.Editor editor = prefs.edit().putInt("schema_version", SCHEMA_VERSION)
                .putString("card_format", settings.cardFormat).putInt("background", settings.background)
                .putBoolean("weather_enabled", settings.weatherEnabled)
                .putString("city_name", settings.cityName)
                .putString("city_latitude", Double.toString(settings.cityLatitude))
                .putString("city_longitude", Double.toString(settings.cityLongitude))
                .putString("city_id", settings.cityId).putString("city_timezone", settings.cityTimezone)
                .putString("city_metadata_key", cityMetadataKey(settings.cityName, settings.cityLatitude, settings.cityLongitude))
                .putString("units", settings.units).putString("language", settings.language)
                .putBoolean("reduced_motion", settings.reducedMotion).putBoolean("sounds", settings.sounds)
                .putString("favorite_ids_v2", String.join("\n", settings.favoriteIds))
                .putString("palette_mode", settings.paletteMode).putInt("wallpaper_dim", settings.wallpaperDim)
                .putString("wallpaper_period", settings.wallpaperPeriod)
                .putInt("day_start_minutes", settings.dayStartMinutes).putInt("night_start_minutes", settings.nightStartMinutes)
                .putStringSet("hidden_packages", new LinkedHashSet<>(settings.hiddenPackages))
                .putString("custom_labels", new JSONObject(settings.customLabels).toString())
                .putBoolean("watch_next_visible", settings.watchNextVisible)
                .putBoolean("recommendations_enabled", settings.recommendationsEnabled)
                .putString("recommendation_packages", String.join("\n", settings.recommendationPackages))
                .putString("recommendation_source_order", String.join("\n", settings.recommendationSourceOrder));
        for (String key : RETIRED_KEYS) editor.remove(key);
        editor.apply();
    }

    synchronized void applyImportedSettings(Settings imported) {
        Map<String, Object> values = SettingsBackup.preferenceValues(imported);
        // Portable ordering is not app-selection consent on another device.
        values.put("recommendation_packages", "");
        Settings current = read();
        if (cityMetadataKey(imported.cityName, imported.cityLatitude, imported.cityLongitude)
                .equals(cityMetadataKey(current.cityName, current.cityLatitude, current.cityLongitude))) {
            values.put("city_metadata_key", cityMetadataKey(current.cityName, current.cityLatitude, current.cityLongitude));
            values.put("city_id", current.cityId); values.put("city_timezone", current.cityTimezone);
        }
        writeAll(decode(values));
    }

    static boolean validPackageName(String value) {
        return value != null && value.length() <= 255 && ("android".equals(value)
                || value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"));
    }
    static boolean validRecommendationChannelKey(String key) {
        if (key == null || key.length() > MAX_RECOMMENDATION_CHANNEL_KEY_LENGTH) return false;
        String[] parts = key.split("\\|", -1);
        if (parts.length != 4 || !validPackageName(parts[1])) return false;
        for (int index : new int[]{0, 2, 3}) {
            if (!parts[index].matches("0|[1-9][0-9]{0,18}")) return false;
            try {
                long value = Long.parseLong(parts[index]);
                if (index == 3 && value == 0) return false;
            } catch (NumberFormatException overflow) { return false; }
        }
        return true;
    }
    private static List<String> normalizeRecommendationList(Object raw, boolean channelKeys) {
        int max = channelKeys ? MAX_RECOMMENDATION_CHANNELS : MAX_RECOMMENDATION_SOURCES;
        int itemLimit = channelKeys ? MAX_RECOMMENDATION_CHANNEL_KEY_LENGTH : 255;
        List<?> input;
        if (raw instanceof List<?>) input = (List<?>) raw;
        else if (raw instanceof String && ((String) raw).length() <= max * (itemLimit + 1))
            input = Arrays.asList(((String) raw).split("\n", -1));
        else return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int visited = 0;
        for (Object candidate : input) {
            if (++visited > 128 || result.size() >= max) break;
            if (!(candidate instanceof String)) continue;
            String value = (String) candidate;
            if (channelKeys ? validRecommendationChannelKey(value) : validPackageName(value)) result.add(value);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
    private static List<String> legacyRecommendationPackages(Object raw) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        for (String key : normalizeRecommendationList(raw, true)) packages.add(key.split("\\|", -1)[1]);
        return Collections.unmodifiableList(new ArrayList<>(packages));
    }
    static List<String> normalizeRecommendationPackages(Object raw) {
        List<?> input;
        if (raw instanceof List<?>) input = (List<?>) raw;
        else if (raw instanceof String && ((String) raw).length() <= MAX_RECOMMENDATION_PACKAGES * 256)
            input = Arrays.asList(((String) raw).split("\n", -1));
        else return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int visited = 0;
        for (Object value : input) {
            if (++visited > 128 || result.size() == MAX_RECOMMENDATION_PACKAGES) break;
            if (value instanceof String && validPackageName((String) value)) result.add((String) value);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
    static boolean validLabel(String value) {
        if (value == null || value.isEmpty() || value.length() > 80) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
    }
    private static Set<String> normalizePackages(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw instanceof Collection<?>) {
            int visited = 0;
            for (Object value : (Collection<?>) raw) {
                if (++visited > 512 || result.size() >= MAX_HIDDEN_PACKAGES) break;
                if (value instanceof String && validPackageName((String) value)) result.add((String) value);
            }
        }
        return Collections.unmodifiableSet(result);
    }
    private static Map<String, String> decodeLabels(Object raw) {
        Map<?, ?> input = Collections.emptyMap();
        if (raw instanceof Map<?, ?>) input = (Map<?, ?>) raw;
        else if (raw instanceof String && ((String) raw).length() <= 65536) {
            try {
                JSONObject object = new JSONObject((String) raw);
                Map<String, Object> parsed = new LinkedHashMap<>();
                java.util.Iterator<String> names = object.keys();
                while (names.hasNext() && parsed.size() < MAX_CUSTOM_LABELS) { String key = names.next(); parsed.put(key, object.opt(key)); }
                input = parsed;
            } catch (JSONException ignored) { }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (result.size() >= MAX_CUSTOM_LABELS) break;
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) continue;
            String key = (String) entry.getKey(), label = text(entry.getValue(), "", 80);
            if (validPackageName(key) && validLabel(label)) result.put(key, label);
        }
        return Collections.unmodifiableMap(result);
    }
    private static String oneOf(Object raw, String fallback, String... choices) {
        for (String choice : choices) if (choice.equals(raw)) return choice;
        return fallback;
    }
    private static int integer(Object value, int fallback, int min, int max) {
        try { int parsed = Integer.parseInt(String.valueOf(value)); return parsed >= min && parsed <= max ? parsed : fallback; }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static Object first(Map<String, ?> data, String... names) {
        for (String name : names) if (data.containsKey(name)) return data.get(name);
        return null;
    }
    private static String normalizedCardFormat(Object value) {
        return CARD_FORMAT_POSTER.equals(value) ? CARD_FORMAT_POSTER : CARD_FORMAT_LANDSCAPE;
    }
    static String legacyCardFormat(Object value) {
        return integer(value, 0, 0, 5) == 5 ? CARD_FORMAT_POSTER : CARD_FORMAT_LANDSCAPE;
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if ("true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value)) || "0".equals(value)) return false;
        return fallback;
    }
    private static String text(Object value, String fallback, int max) {
        if (!(value instanceof String)) return fallback;
        String result = ((String) value).trim();
        return result.length() <= max ? result : result.substring(0, max);
    }
    private static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }
    private static List<String> decodeFavorites(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof Collection<?>) {
            for (Object value : (Collection<?>) raw) if (value instanceof String) values.add((String) value);
            // SharedPreferences StringSet had no order; make its one-time migration deterministic.
            if (raw instanceof Set<?>) Collections.sort(values);
        } else if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.length() > 262144) return Collections.emptyList();
            if (text.startsWith("[")) {
                // Legacy JSON string arrays contain package/component IDs, which need no escape syntax.
                if (!text.endsWith("]")) return Collections.emptyList();
                String body = text.substring(1, text.length() - 1).trim();
                if (!body.isEmpty()) {
                    for (String token : body.split(",", -1)) {
                        String item = token.trim();
                        if (item.length() < 2 || !item.startsWith("\"") || !item.endsWith("\""))
                            return Collections.emptyList();
                        item = item.substring(1, item.length() - 1);
                        if (item.indexOf('\\') >= 0 || item.indexOf('"') >= 0) return Collections.emptyList();
                        values.add(item);
                    }
                }
            } else values.addAll(Arrays.asList(text.split("[\n,;]")));
        }
        return FavoriteOrder.normalize(values);
    }
}
