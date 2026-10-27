package io.github.ekkzy.softlumi.data;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated settings-only backup. Preview never reads/writes preferences or URI grants. */
public final class SettingsBackup {
    public static final int SCHEMA_VERSION = 5;
    public static final int MAX_BYTES = 65536;
    private static final Map<String, String> KEYS;
    private static final Set<String> V1_KEYS, V3_KEYS, V4_KEYS;
    static {
        Map<String, String> names = new LinkedHashMap<>();
        for (String pair : new String[]{"cardFormat:card_format", "background:background", "paletteMode:palette_mode",
                "wallpaperDim:wallpaper_dim", "weatherEnabled:weather_enabled", "cityName:city_name",
                "cityLatitude:city_latitude", "cityLongitude:city_longitude", "units:units", "language:language",
                "reducedMotion:reduced_motion", "sounds:sounds", "favoriteIds:favorite_ids_v2",
                "hiddenPackages:hidden_packages", "customLabels:custom_labels", "watchNextVisible:watch_next_visible"}) {
            String[] parts = pair.split(":"); names.put(parts[0], parts[1]);
        }
        Set<String> legacyV1 = new LinkedHashSet<>(names.keySet());
        legacyV1.remove("cardFormat"); legacyV1.add("theme");
        legacyV1.addAll(Arrays.asList("screensaverIntervalSeconds", "screensaverMotion", "screensaverClock", "screensaverSource"));
        V1_KEYS = Collections.unmodifiableSet(legacyV1);
        names.put("recommendationsEnabled", "recommendations_enabled");
        names.put("recommendationSourceOrder", "recommendation_source_order");
        Set<String> legacyV3 = new LinkedHashSet<>(legacyV1);
        legacyV3.addAll(Arrays.asList("recommendationsEnabled", "recommendationSourceOrder"));
        V3_KEYS = Collections.unmodifiableSet(legacyV3);
        names.put("wallpaperPeriod", "wallpaper_period");
        names.put("dayStartMinutes", "day_start_minutes");
        names.put("nightStartMinutes", "night_start_minutes");
        Set<String> legacyV4 = new LinkedHashSet<>(legacyV3);
        legacyV4.remove("screensaverSource");
        legacyV4.addAll(Arrays.asList("wallpaperPeriod", "dayStartMinutes", "nightStartMinutes", "screensaverScene", "screensaverPeriod"));
        V4_KEYS = Collections.unmodifiableSet(legacyV4);
        KEYS = Collections.unmodifiableMap(names);
    }
    private SettingsBackup() {}

    public static final class Preview {
        public final boolean valid;
        public final LauncherPrefs.Settings settings;
        public final List<String> errors;
        public final List<String> warnings;
        public final int favoriteCount;
        public final int hiddenCount;
        public final int labelCount;
        public final int resetFieldCount;
        private Preview(LauncherPrefs.Settings settings, List<String> errors, List<String> warnings, int resets) {
            this.valid = settings != null && errors.isEmpty();
            this.settings = valid ? settings : null;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.favoriteCount = valid ? settings.favoriteIds.size() : 0;
            this.hiddenCount = valid ? settings.hiddenPackages.size() : 0;
            this.labelCount = valid ? settings.customLabels.size() : 0;
            this.resetFieldCount = valid ? resets : 0;
        }
    }

    public static String exportJson(LauncherPrefs.Settings settings) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        try {
            Map<String, Object> values = preferenceValues(settings);
            JSONObject content = new JSONObject();
            for (Map.Entry<String, String> key : KEYS.entrySet()) {
                Object value = values.get(key.getValue());
                if ("favoriteIds".equals(key.getKey())) value = new JSONArray(settings.favoriteIds);
                else if ("recommendationSourceOrder".equals(key.getKey())) value = new JSONArray(settings.recommendationSourceOrder);
                else if ("hiddenPackages".equals(key.getKey())) {
                    List<String> sorted = new ArrayList<>(settings.hiddenPackages); Collections.sort(sorted);
                    value = new JSONArray(sorted);
                } else if ("customLabels".equals(key.getKey())) value = new JSONObject(settings.customLabels);
                else if (("cityLatitude".equals(key.getKey()) || "cityLongitude".equals(key.getKey())) && !settings.hasCity()) value = JSONObject.NULL;
                content.put(key.getKey(), value);
            }
            String result = new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("settings", content).toString(2);
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IllegalArgumentException("Settings exceed the 64 KiB backup limit");
            return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException("Cannot encode settings backup", invalid); }
    }

    public static Preview preview(String json) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return invalid("The backup is empty");
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return invalid("The backup exceeds 64 KiB");
        if (!boundedDepth(json)) return invalid("The backup is malformed or nested too deeply");
        try {
            JSONTokener parser = new JSONTokener(json);
            Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) return invalid("Expected one JSON backup object");
            JSONObject root = (JSONObject) parsed;
            unknown(root, new LinkedHashSet<>(Arrays.asList("schemaVersion", "settings")), "backup", errors);
            Object version = root.opt("schemaVersion");
            if (!wholeNumber(version, 1, SCHEMA_VERSION)) add(errors, "Unsupported backup schemaVersion");
            int schema = version instanceof Number ? ((Number) version).intValue() : -1;
            JSONObject content = root.optJSONObject("settings");
            if (content == null) return invalid("The backup settings object is missing");
            Set<String> allowedKeys = schema == 1 ? V1_KEYS : schema <= 3 ? V3_KEYS : schema == 4 ? V4_KEYS : KEYS.keySet();
            unknown(content, allowedKeys, "settings", errors);
            Map<String, Object> values = new LinkedHashMap<>();
            java.util.Iterator<String> keys = content.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowedKeys.contains(key)) continue;
                Object value = content.get(key);
                if (!validateField(key, value, schema, errors)) continue;
                if ("theme".equals(key)) values.put("card_format", LauncherPrefs.legacyCardFormat(((Number) value).intValue()));
                else if (KEYS.containsKey(key)) values.put(KEYS.get(key), convert(key, value));
            }
            String city = values.get("city_name") instanceof String ? ((String) values.get("city_name")).trim() : "";
            Object latitude = values.get("city_latitude"), longitude = values.get("city_longitude");
            if (!city.isEmpty() && (!(latitude instanceof Number) || !(longitude instanceof Number)
                    || !WeatherPolicy.validCoordinates(((Number) latitude).doubleValue(), ((Number) longitude).doubleValue())))
                add(errors, "A city requires valid latitude and longitude");
            if (city.isEmpty() && (latitude instanceof Number || longitude instanceof Number)) add(errors, "Coordinates require a city name");
            if (values.containsKey("day_start_minutes") || values.containsKey("night_start_minutes")) {
                // The storage decoder repairs equal times. An import must instead
                // reject a conflict with a missing field's default, not rewrite
                // the boundary explicitly supplied by the backup.
                LauncherPrefs.Settings defaults = LauncherPrefs.decode(Collections.emptyMap());
                Number day = (Number) values.get("day_start_minutes"), night = (Number) values.get("night_start_minutes");
                int dayStart = day == null ? defaults.dayStartMinutes : day.intValue();
                int nightStart = night == null ? defaults.nightStartMinutes : night.intValue();
                if (dayStart == nightStart) add(errors, "Day and night must start at different times");
            }
            if (!errors.isEmpty()) return new Preview(null, errors, warnings, 0);
            for (String key : allowedKeys) if (key.startsWith("screensaver") && content.has(key)) {
                warnings.add("Retired screensaver settings are ignored; built-in screensavers are no longer supported."); break;
            }
            warnings.add("Recommendation app selections are device-local and will be cleared. Select apps again on this device before showing recommendations.");
            int presentSettings = 0;
            for (String key : KEYS.values()) if (values.containsKey(key)) presentSettings++;
            int resets = KEYS.size() - presentSettings;
            if (resets > 0) warnings.add(resets + " missing settings will reset to defaults");
            return new Preview(LauncherPrefs.decode(values), errors, warnings, resets);
        } catch (JSONException | RuntimeException malformed) { return invalid("The backup is malformed JSON"); }
    }

    /** One SharedPreferences.Editor.apply transaction, only after a complete successful preview. */
    public static boolean apply(LauncherPrefs preferences, Preview preview) {
        if (preferences == null || preview == null || !preview.valid) return false;
        preferences.applyImportedSettings(preview.settings);
        return true;
    }

    static Map<String, Object> preferenceValues(LauncherPrefs.Settings s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("card_format", s.cardFormat); values.put("background", s.background);
        values.put("palette_mode", s.paletteMode); values.put("wallpaper_dim", s.wallpaperDim);
        values.put("weather_enabled", s.weatherEnabled); values.put("city_name", s.cityName);
        values.put("city_latitude", s.cityLatitude); values.put("city_longitude", s.cityLongitude);
        values.put("units", s.units); values.put("language", s.language);
        values.put("reduced_motion", s.reducedMotion); values.put("sounds", s.sounds);
        values.put("favorite_ids_v2", s.favoriteIds); values.put("hidden_packages", s.hiddenPackages);
        values.put("custom_labels", s.customLabels); values.put("watch_next_visible", s.watchNextVisible);
        values.put("recommendations_enabled", s.recommendationsEnabled);
        values.put("recommendation_source_order", s.recommendationSourceOrder);
        values.put("wallpaper_period", s.wallpaperPeriod);
        values.put("day_start_minutes", s.dayStartMinutes); values.put("night_start_minutes", s.nightStartMinutes);
        return values;
    }

    private static boolean validateField(String key, Object value, int schema, List<String> errors) throws JSONException {
        boolean valid;
        switch (key) {
            // Older backups retain their original theme domain; schema 3 adds the new sparse IDs.
            case "theme": valid = schema <= 4 && (schema >= 3
                    ? wholeNumber(value, 0, 0) || wholeNumber(value, 5, 5)
                    : wholeNumber(value, 0, 4)); break;
            case "cardFormat": valid = schema == SCHEMA_VERSION && enumValue(value,
                    LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.CARD_FORMAT_POSTER); break;
            case "background": valid = wholeNumber(value, WallpaperCatalog.DEFAULT_ID, schema <= 4 ? 48 : WallpaperCatalog.MAX_ID); break;
            case "wallpaperPeriod": valid = enumValue(value, "auto", "schedule", "day", "night"); break;
            case "screensaverPeriod": valid = enumValue(value, "auto", "day", "night"); break;
            case "dayStartMinutes": case "nightStartMinutes": valid = wholeNumber(value, 0, 1439); break;
            case "screensaverScene": valid = enumValue(value, "shuffle", "cactus", "peonies", "orchid", "monstera", "canyon", "alpine", "coast", "forest", "dunes", "sakura"); break;
            case "wallpaperDim": valid = wholeNumber(value, 0, 60); break;
            case "screensaverIntervalSeconds": valid = wholeNumber(value, 15, 300); break;
            case "paletteMode": valid = enumValue(value, "auto", "light", "dark"); break;
            case "units": valid = enumValue(value, "auto", "celsius", "fahrenheit"); break;
            case "language": valid = enumValue(value, "system", "ru", "en"); break;
            case "screensaverSource": valid = schema <= 3 && enumValue(value, "scenes", "video"); break;
            case "weatherEnabled": case "sounds": case "reducedMotion": case "watchNextVisible":
            case "screensaverMotion": case "screensaverClock": case "recommendationsEnabled": valid = value instanceof Boolean; break;
            case "cityName": valid = safeText(value, 128, true); break;
            case "cityLatitude": valid = value == JSONObject.NULL || finiteNumber(value, -90, 90); break;
            case "cityLongitude": valid = value == JSONObject.NULL || finiteNumber(value, -180, 180); break;
            case "favoriteIds": valid = stringArray(value, 8, true); break;
            case "hiddenPackages": valid = stringArray(value, LauncherPrefs.MAX_HIDDEN_PACKAGES, false); break;
            case "recommendationSourceOrder": valid = stringArray(value, LauncherPrefs.MAX_RECOMMENDATION_SOURCES, false); break;
            case "customLabels":
                valid = value instanceof JSONObject && ((JSONObject) value).length() <= LauncherPrefs.MAX_CUSTOM_LABELS;
                if (valid) {
                    JSONObject labels = (JSONObject) value; java.util.Iterator<String> names = labels.keys();
                    while (names.hasNext()) {
                        String name = names.next(); Object label = labels.get(name);
                        if (!LauncherPrefs.validPackageName(name) || !(label instanceof String)
                                || !LauncherPrefs.validLabel(((String) label).trim())) { valid = false; break; }
                    }
                }
                break;
            default: valid = false;
        }
        if (!valid) add(errors, "Invalid or out-of-range setting: " + key);
        return valid;
    }
    private static Object convert(String key, Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        // Former Depth gallery identities are accepted in old exports but have no artwork now.
        if ("background".equals(key) && ((Number) value).intValue() >= 39 && ((Number) value).intValue() <= 48)
            return WallpaperCatalog.DEFAULT_ID;
        if (value instanceof JSONArray) {
            List<String> items = new ArrayList<>(); JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) items.add(array.getString(index));
            return items;
        }
        if (value instanceof JSONObject) {
            Map<String, String> labels = new LinkedHashMap<>(); JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String name = keys.next(); labels.put(name, object.getString(name).trim()); }
            return labels;
        }
        if (value instanceof Number && !"cityLatitude".equals(key) && !"cityLongitude".equals(key))
            return ((Number) value).intValue();
        return value;
    }
    private static boolean stringArray(Object value, int max, boolean favorites) throws JSONException {
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() > max) return false;
        Set<String> unique = new LinkedHashSet<>(); JSONArray array = (JSONArray) value;
        for (int index = 0; index < array.length(); index++) {
            Object item = array.get(index);
            if (!(item instanceof String) || !unique.add((String) item)) return false;
            if (favorites ? !validFavorite((String) item) : !LauncherPrefs.validPackageName((String) item)) return false;
        }
        return true;
    }
    private static boolean validFavorite(String id) {
        if (id == null || id.length() > 512) return false;
        int slash = id.indexOf('/');
        if (slash < 0) return LauncherPrefs.validPackageName(id);
        if (!LauncherPrefs.validPackageName(id.substring(0, slash)) || slash != id.lastIndexOf('/')) return false;
        String activity = id.substring(slash + 1);
        if (activity.startsWith(".")) activity = activity.substring(1);
        return activity.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }
    private static boolean safeText(Object value, int max, boolean empty) {
        if (!(value instanceof String) || ((String) value).length() > max || (!empty && ((String) value).trim().isEmpty())) return false;
        for (int index = 0; index < ((String) value).length(); index++) if (Character.isISOControl(((String) value).charAt(index))) return false;
        return true;
    }
    private static boolean enumValue(Object value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }
    private static boolean wholeNumber(Object value, int min, int max) {
        return finiteNumber(value, min, max) && Math.rint(((Number) value).doubleValue()) == ((Number) value).doubleValue();
    }
    private static boolean finiteNumber(Object value, double min, double max) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= min && number <= max;
    }
    private static void unknown(JSONObject object, Set<String> allowed, String location, List<String> errors) {
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) add(errors, "Unknown " + location + " field: "
                    + (key.length() <= 80 ? key : key.substring(0, 80) + "…"));
        }
    }
    private static void add(List<String> errors, String error) { if (errors.size() < 16) errors.add(error); }
    private static Preview invalid(String error) { return new Preview(null, Collections.singletonList(error), Collections.emptyList(), 0); }
    private static boolean boundedDepth(String json) {
        int depth = 0; boolean string = false, escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char c = json.charAt(index);
            if (string) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if (c == '{' || c == '[') { if (++depth > 8) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        return !string && depth == 0;
    }
}
