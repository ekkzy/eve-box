package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class SettingsBackupTest {
    // Fixed candidate.15 export contract, independent of the current export implementation.
    private static final String PREVIOUS_APP_EXPORT = """
            {
              "schemaVersion": 5,
              "settings": {
                "cardFormat": "poster", "background": 11, "paletteMode": "dark",
                "wallpaperDim": 37, "weatherEnabled": true, "cityName": "Москва",
                "cityLatitude": 55.75, "cityLongitude": 37.62, "units": "celsius",
                "language": "ru", "reducedMotion": true, "sounds": false,
                "favoriteIds": ["pkg.beta/.Main", "pkg.alpha/.Tv"],
                "hiddenPackages": ["pkg.hidden"], "customLabels": {"pkg.alpha": "Любимые фильмы"},
                "watchNextVisible": false, "recommendationsEnabled": true,
                "recommendationSourceOrder": ["pkg.beta", "pkg.alpha"],
                "wallpaperPeriod": "schedule", "dayStartMinutes": 420, "nightStartMinutes": 1140
              }
            }
            """;

    @Test public void previousAppExportPreviewsWithoutWritesAndImportsIntoNewStorage() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        LauncherPrefs preferences = new LauncherPrefs(store);
        SettingsBackup.Preview preview = SettingsBackup.preview(PREVIOUS_APP_EXPORT);
        assertTrue(preview.errors.toString(), preview.valid);
        assertEquals(0, preview.resetFieldCount);
        assertEquals("Preview must not write the new app's storage", 0, store.editCount);
        assertTrue(store.values.isEmpty());
        assertTrue(SettingsBackup.apply(preferences, preview));
        assertEquals(1, store.applyCount); assertEquals(1, store.editCount);
        LauncherPrefs.Settings result = preferences.read();
        assertEquals("poster", result.cardFormat); assertEquals(11, result.background);
        assertEquals("dark", result.paletteMode); assertEquals(37, result.wallpaperDim);
        assertEquals("Москва", result.cityName); assertTrue(result.weatherEnabled);
        assertEquals(55.75, result.cityLatitude, 0); assertEquals(37.62, result.cityLongitude, 0);
        assertEquals("celsius", result.units); assertEquals("ru", result.language);
        assertTrue(result.reducedMotion); assertFalse(result.sounds); assertFalse(result.watchNextVisible);
        assertEquals(Arrays.asList("pkg.beta/.Main", "pkg.alpha/.Tv"), result.favoriteIds);
        assertEquals(Collections.singleton("pkg.hidden"), result.hiddenPackages);
        assertEquals(Collections.singletonMap("pkg.alpha", "Любимые фильмы"), result.customLabels);
        assertTrue(result.recommendationsEnabled); assertEquals(Arrays.asList("pkg.beta", "pkg.alpha"), result.recommendationSourceOrder);
        assertTrue("Device-local recommendation selection is not transferred", result.recommendationPackages.isEmpty());
        assertEquals("schedule", result.wallpaperPeriod); assertEquals(420, result.dayStartMinutes); assertEquals(1140, result.nightStartMinutes);
        JSONObject original = new JSONObject(PREVIOUS_APP_EXPORT).getJSONObject("settings");
        JSONObject exported = new JSONObject(SettingsBackup.exportJson(result)).getJSONObject("settings");
        assertEquals("Export preserves the supported field set", original.length(), exported.length());
        for (java.util.Iterator<String> keys = original.keys(); keys.hasNext();) {
            String key = keys.next();
            assertEquals("Export preserves " + key, original.get(key).toString(), exported.get(key).toString());
        }
    }

    @Test public void previousAppExportCannotImportHomeOrAccessibilityGrants() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        for (String key : new String[]{"defaultHome", "enabledAccessibilityServices", "remoteButtons", "persistedUriPermission"}) {
            JSONObject modified = new JSONObject(PREVIOUS_APP_EXPORT);
            modified.getJSONObject("settings").put(key, "untrusted.value");
            SettingsBackup.Preview preview = SettingsBackup.preview(modified.toString());
            assertFalse(key, preview.valid);
            assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
        }
        assertEquals(0, store.editCount); assertEquals(0, store.applyCount); assertTrue(store.values.isEmpty());
    }

    private static LauncherPrefs.Settings example() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("theme", 2); values.put("background", 11); values.put("palette_mode", "dark"); values.put("wallpaper_dim", 37);
        values.put("favorite_ids_v2", Arrays.asList("pkg.beta/.Main", "pkg.alpha/.Tv"));
        values.put("hidden_packages", Arrays.asList("pkg.hidden"));
        values.put("custom_labels", Collections.singletonMap("pkg.alpha", "Любимые фильмы"));
        values.put("watch_next_visible", false); values.put("screensaver_interval_seconds", 75);
        values.put("screensaver_motion", false); values.put("screensaver_clock", false);
        values.put("screensaver_source", "video"); values.put("screensaver_video_uri", "content://local/video/private");
        return LauncherPrefs.decode(values);
    }
    private static void removeDayNightFields(JSONObject content) {
        for(String key:new String[]{"wallpaperPeriod","dayStartMinutes","nightStartMinutes","screensaverScene","screensaverPeriod"})content.remove(key);
    }
    private static JSONObject legacyContent(int schema) throws Exception {
        JSONObject content = exported().getJSONObject("settings");
        content.remove("cardFormat"); content.put("theme", 0);
        content.put("screensaverIntervalSeconds", 75).put("screensaverMotion", false).put("screensaverClock", false);
        if (schema <= 3) { removeDayNightFields(content); content.put("screensaverSource", "video"); }
        else content.put("screensaverScene", "forest").put("screensaverPeriod", "night");
        if (schema == 1) { content.remove("recommendationsEnabled"); content.remove("recommendationSourceOrder"); }
        return content;
    }
    private static JSONObject exported() throws Exception { return new JSONObject(SettingsBackup.exportJson(example())); }
    private static String document(JSONObject content) throws Exception {
        return document(1, content);
    }
    private static String document(int version, JSONObject content) throws Exception {
        return new JSONObject().put("schemaVersion", version).put("settings", content).toString();
    }
    @Test public void completeRoundTripPreservesAppearanceFavoritesAndCustomization() {
        SettingsBackup.Preview preview = SettingsBackup.preview(SettingsBackup.exportJson(example()));
        assertTrue(preview.errors.toString(), preview.valid);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat); assertEquals(11, preview.settings.background);
        assertEquals("dark", preview.settings.paletteMode); assertEquals(37, preview.settings.wallpaperDim);
        assertEquals(example().favoriteIds, preview.settings.favoriteIds);
        assertEquals(example().customLabels, preview.settings.customLabels); assertEquals(2, preview.favoriteCount);
        assertEquals(1, preview.hiddenCount); assertEquals(1, preview.labelCount); assertEquals(0, preview.resetFieldCount);
        assertFalse(preview.settings.watchNextVisible);
    }
    @Test public void backupsExcludeRetiredVideoSourceUriHistoryAndCache() throws Exception {
        String json = SettingsBackup.exportJson(example());
        assertFalse(json.contains("content://")); assertFalse(json.contains("videoUri"));
        assertFalse(json.contains("history")); assertFalse(json.contains("fetchedAt"));
        SettingsBackup.Preview preview = SettingsBackup.preview(json);
        assertFalse(json.contains("screensaverSource")); assertFalse(json.contains("screensaver_source"));
        assertEquals(21, new JSONObject(json).getJSONObject("settings").length());
        assertFalse(json.contains("screensaver")); assertFalse(new JSONObject(json).getJSONObject("settings").has("theme"));
        assertTrue(preview.valid); assertFalse(preview.warnings.isEmpty());
        assertFalse(SettingsBackup.preferenceValues(example()).containsKey("screensaver_source"));
        assertFalse(SettingsBackup.preferenceValues(example()).containsKey("screensaver_video_uri"));
    }
    @Test public void validImportUsesOneTransactionAndRetiresExistingLocalVideoKeys() {
        MemoryPreferences store = new MemoryPreferences(); store.values.put("screensaver_video_uri", "content://already-granted/my-video");
        store.values.put("screensaver_source", "video"); store.values.put("theme", 0);
        LauncherPrefs preferences = new LauncherPrefs(store);
        assertTrue(SettingsBackup.apply(preferences, SettingsBackup.preview(SettingsBackup.exportJson(example()))));
        assertEquals(1, store.applyCount); assertEquals(1, store.editCount);
        LauncherPrefs.Settings result = preferences.read();
        assertEquals(11, result.background); assertEquals(example().favoriteIds, result.favoriteIds);
        assertEquals(example().hiddenPackages, result.hiddenPackages); assertEquals(example().customLabels, result.customLabels);
        assertFalse(store.values.containsKey("screensaver_video_uri"));
        assertFalse(store.values.containsKey("screensaver_source"));
        assertEquals(LauncherPrefs.SCHEMA_VERSION, store.values.get("schema_version"));
    }
    @Test public void legacySourceIsAcceptedAsInertWithoutChangingResetCountsOrCurrentSettings() throws Exception {
        for (int schema : new int[]{1, 2, 3}) for (String source : new String[]{"scenes", "video"}) {
            JSONObject content = legacyContent(schema);
            content.put("screensaverSource", source);
            content.put("theme", schema == 3 ? 5 : 4);
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, content));
            assertTrue("schema=" + schema + ", source=" + source + ": " + preview.errors, preview.valid);
            assertEquals(schema == 1 ? 5 : 3, preview.resetFieldCount);
            assertEquals(schema == 3 ? LauncherPrefs.CARD_FORMAT_POSTER : LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat);
            assertEquals(11, preview.settings.background); assertEquals(example().favoriteIds, preview.settings.favoriteIds);
            assertTrue(preview.warnings.stream().anyMatch(value -> value.contains("Retired screensaver")));
            assertFalse(SettingsBackup.exportJson(preview.settings).contains("screensaver"));
            assertFalse(SettingsBackup.preferenceValues(preview.settings).containsKey("screensaver_source"));
            assertFalse(SettingsBackup.exportJson(preview.settings).contains("screensaverSource"));
        }
    }
    @Test public void newSchemaRejectsRetiredSourceAndNoSchemaCanImportVideoUriOrGrant() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("theme", 5); store.values.put("background", 38);
        store.values.put("screensaver_source", "video"); store.values.put("screensaver_video_uri", "content://local/old");
        Map<String, Object> before = new HashMap<>(store.values);
        for (int schema : new int[]{1, 2, 3, 4, 5}) {
            for (String key : new String[]{"screensaverVideoUri", "screensaver_video_uri", "persistedUriPermission"}) {
                SettingsBackup.Preview preview = SettingsBackup.preview(document(schema,
                        new JSONObject().put(key, "content://untrusted/new")));
                assertFalse("schema=" + schema + ", key=" + key, preview.valid);
                assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
            }
        }
        for (Object invalid : new Object[]{"video", "scenes", "remote", true, JSONObject.NULL}) {
            SettingsBackup.Preview current = SettingsBackup.preview(document(4,
                    new JSONObject().put("screensaverSource", invalid)));
            assertFalse(current.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), current));
        }
        for (int schema : new int[]{1, 2, 3}) for (Object invalid : new Object[]{"remote", true, JSONObject.NULL}) {
            SettingsBackup.Preview legacy = SettingsBackup.preview(document(schema,
                    new JSONObject().put("screensaverSource", invalid)));
            assertFalse(legacy.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), legacy));
        }
        assertEquals("Invalid preview must not trigger even retirement writes", before, store.values);
        assertEquals(0, store.applyCount); assertEquals(0, store.editCount);
    }
    @Test public void invalidImportNeverOpensAnEditorOrPartiallyChangesPreferences() throws Exception {
        MemoryPreferences store = new MemoryPreferences(); store.values.put("theme", 3); store.values.put("background", 4);
        Map<String, Object> before = new HashMap<>(store.values);
        JSONObject root = exported(); root.getJSONObject("settings").put("wallpaperDim", 999);
        SettingsBackup.Preview preview = SettingsBackup.preview(root.toString());
        assertFalse(preview.valid); assertNull(preview.settings);
        assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
        assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
    }
    @Test public void missingRecognizedSettingsDefaultAndPreviewCountsResets() throws Exception {
        SettingsBackup.Preview preview = SettingsBackup.preview(document(new JSONObject().put("theme", 1)));
        assertTrue(preview.valid); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat); assertEquals(20, preview.resetFieldCount);
        assertEquals(15, preview.settings.wallpaperDim); assertTrue(preview.settings.watchNextVisible);
        assertEquals(0, preview.favoriteCount); assertFalse(preview.warnings.isEmpty());
    }
    @Test public void partialScheduleRejectsAnExplicitBoundaryThatConflictsWithTheMissingDefault() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("favorite_ids_v2", "pkg.retained/.Tv"); store.values.put("background", 48);
        store.values.put("day_start_minutes", 600); store.values.put("night_start_minutes", 1200);
        Map<String, Object> before = new LinkedHashMap<>(store.values);
        for (JSONObject content : new JSONObject[]{
                new JSONObject().put("wallpaperPeriod", "schedule").put("nightStartMinutes", 420),
                new JSONObject().put("wallpaperPeriod", "schedule").put("dayStartMinutes", 1140)}) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(4, content));
            assertFalse("A supplied boundary cannot be silently changed: " + content, preview.valid);
            assertNull(preview.settings);
            assertTrue(preview.errors.contains("Day and night must start at different times"));
            assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
            assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
        }
    }
    @Test public void partialBackupsKeepScheduleDefaultsAndPreserveValidExplicitMinutes() throws Exception {
        LauncherPrefs.Settings defaults = LauncherPrefs.decode(Collections.emptyMap());
        for (int schema : new int[]{1, 2, 3, 4, 5}) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, new JSONObject()));
            assertTrue("schema=" + schema + ": " + preview.errors, preview.valid);
            assertEquals(defaults.dayStartMinutes, preview.settings.dayStartMinutes);
            assertEquals(defaults.nightStartMinutes, preview.settings.nightStartMinutes);
            assertEquals("auto", preview.settings.wallpaperPeriod);
        }
        SettingsBackup.Preview day = SettingsBackup.preview(document(4,
                new JSONObject().put("wallpaperPeriod", "schedule").put("dayStartMinutes", 421)));
        assertTrue(day.errors.toString(), day.valid); assertEquals(421, day.settings.dayStartMinutes);
        assertEquals(defaults.nightStartMinutes, day.settings.nightStartMinutes);
        SettingsBackup.Preview night = SettingsBackup.preview(document(4,
                new JSONObject().put("wallpaperPeriod", "schedule").put("nightStartMinutes", 421)));
        assertTrue(night.errors.toString(), night.valid); assertEquals(421, night.settings.nightStartMinutes);
        assertEquals(defaults.dayStartMinutes, night.settings.dayStartMinutes);
    }
    @Test public void rejectsUnknownRootAndSettingsFieldsIncludingUriGrants() throws Exception {
        JSONObject root = exported(); root.put("history", new JSONArray());
        assertFalse(SettingsBackup.preview(root.toString()).valid);
        root = exported(); root.getJSONObject("settings").put("screensaverVideoUri", "content://unseen/private");
        assertFalse(SettingsBackup.preview(root.toString()).valid);
        root = exported(); root.getJSONObject("settings").put("paletteMod", "light");
        assertFalse(SettingsBackup.preview(root.toString()).valid);
    }
    @Test public void rejectsUnsupportedAndMistypedVersions() throws Exception {
        for (Object version : new Object[]{0, 6, "1", 1.5, JSONObject.NULL}) {
            JSONObject root = exported(); root.put("schemaVersion", version);
            assertFalse("version=" + version, SettingsBackup.preview(root.toString()).valid);
        }
    }
    @Test public void rejectsMalformedDeepAndTrailingJson() {
        for (String text : new String[]{"", "{", "[]", "{\"schemaVersion\":1}",
                "{\"schemaVersion\":1,\"settings\":{}} garbage", "[[[[[[[[[[{}]]]]]]]]]]"}) {
            assertFalse(text, SettingsBackup.preview(text).valid);
        }
    }
    @Test public void enforcesUtf8ByteLimitBeforeParsing() throws Exception {
        StringBuilder text = new StringBuilder(); for (int index = 0; index < 33000; index++) text.append('Я');
        assertFalse(SettingsBackup.preview(text.toString()).valid);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("cityName", text.toString()))).valid);
    }
    @Test public void rejectsOverLimitOrDuplicateCollectionEntries() throws Exception {
        JSONArray favorites = new JSONArray(); for (int index = 0; index < 9; index++) favorites.put("pkg.app" + index + "/.Main");
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("favoriteIds", favorites))).valid);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("favoriteIds", new JSONArray(Arrays.asList("pkg.a/.Main", "pkg.a/.Main"))))).valid);
        JSONArray hidden = new JSONArray(); for (int index = 0; index < 129; index++) hidden.put("pkg.hidden" + index);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("hiddenPackages", hidden))).valid);
    }
    @Test public void rejectsInvalidPackageNamesLabelsAndTypedSettings() throws Exception {
        JSONObject[] bad = {new JSONObject().put("hiddenPackages", new JSONArray(Arrays.asList("pkg.name;rm"))),
                new JSONObject().put("customLabels", new JSONObject().put("pkg.valid", String.join("", Collections.nCopies(81, "x")))),
                new JSONObject().put("customLabels", new JSONObject().put("pkg.valid", "Line\nbreak")),
                new JSONObject().put("watchNextVisible", "true"), new JSONObject().put("background", 49),
                new JSONObject().put("theme", 5), new JSONObject().put("screensaverIntervalSeconds", 14)};
        for (JSONObject fields : bad) assertFalse(fields.toString(), SettingsBackup.preview(document(fields)).valid);
    }
    @Test public void validatesCityCoordinatesTogetherAndKeepsWeatherOptIn() throws Exception {
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("cityName", "Moscow"))).valid);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("cityLatitude", 55))).valid);
        JSONObject city = new JSONObject().put("cityName", "Moscow").put("cityLatitude", 55.75).put("cityLongitude", 37.62).put("weatherEnabled", true);
        SettingsBackup.Preview preview = SettingsBackup.preview(document(city));
        assertTrue(preview.errors.toString(), preview.valid); assertTrue(preview.settings.hasCity()); assertTrue(preview.settings.weatherEnabled);
        SettingsBackup.Preview defaults = SettingsBackup.preview(SettingsBackup.exportJson(LauncherPrefs.decode(Collections.emptyMap())));
        assertTrue(defaults.errors.toString(), defaults.valid); assertFalse(defaults.settings.weatherEnabled); assertFalse(defaults.settings.hasCity());
    }
    @Test public void previewCollectionsCannotBeModifiedAfterValidation() {
        SettingsBackup.Preview preview = SettingsBackup.preview(SettingsBackup.exportJson(example()));
        try { preview.warnings.clear(); fail("Warnings must be immutable"); } catch (UnsupportedOperationException expected) { }
        try { preview.settings.customLabels.clear(); fail("Settings must be immutable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void aNewValidPreviewDoesNotRetainPreviousErrors() {
        assertFalse(SettingsBackup.preview("broken").errors.isEmpty());
        SettingsBackup.Preview valid = SettingsBackup.preview(SettingsBackup.exportJson(example()));
        assertTrue(valid.valid); assertTrue(valid.errors.isEmpty());
    }
    @Test public void localSettersPersistBoundariesAndClearAUserRename() {
        LauncherPrefs preferences = new LauncherPrefs(new MemoryPreferences());
        preferences.setBackground(11); preferences.setPaletteMode("light"); preferences.setWallpaperDim(100);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER); preferences.setWatchNextVisible(false);
        preferences.setWallpaperPeriod("schedule"); preferences.setDayNightSchedule(421, 420);
        assertTrue(preferences.setPackageHidden("pkg.hidden", true));
        preferences.setCustomLabel("pkg.hidden", "Моё приложение");
        LauncherPrefs.Settings saved = preferences.read();
        assertEquals(11, saved.background); assertEquals("light", saved.paletteMode); assertEquals(60, saved.wallpaperDim);
        assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, saved.cardFormat); assertFalse(saved.watchNextVisible);
        assertEquals("schedule", saved.wallpaperPeriod); assertEquals(421, saved.dayStartMinutes); assertEquals(420, saved.nightStartMinutes);
        assertEquals("Моё приложение", saved.customLabels.get("pkg.hidden"));
        preferences.setCustomLabel("pkg.hidden", ""); preferences.setPackageHidden("pkg.hidden", false);
        assertTrue(preferences.read().customLabels.isEmpty()); assertTrue(preferences.read().hiddenPackages.isEmpty());
    }
    @Test public void hiddenPackageLimitRefusesAdditionWithoutEvictingAnExistingChoice() {
        LauncherPrefs preferences = new LauncherPrefs(new MemoryPreferences());
        java.util.List<String> hidden = new ArrayList<>(); for (int index = 0; index < 128; index++) hidden.add("pkg.hidden" + index);
        preferences.setHiddenPackages(hidden); Set<String> before = preferences.read().hiddenPackages;
        assertFalse(preferences.setPackageHidden("pkg.new", true)); assertEquals(before, preferences.read().hiddenPackages);
        assertTrue(preferences.setPackageHidden("pkg.hidden0", false)); assertTrue(preferences.setPackageHidden("pkg.new", true));
    }
    @Test public void newWallpaperIdsSurviveBackupAndAtomicImportWithClassic() throws Exception {
        assertEquals("Unified cards use schema 5", 5, SettingsBackup.SCHEMA_VERSION);
        for (int id = 12; id <= WallpaperCatalog.MAX_ID; id++) {
            LauncherPrefs local = new LauncherPrefs(new MemoryPreferences());
            local.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); local.setBackground(id);
            local.setFavorites(Arrays.asList("pkg.beta/.Main", "pkg.alpha/.Tv"));
            String json = SettingsBackup.exportJson(local.read());
            JSONObject exported = new JSONObject(json);
            assertEquals(5, exported.getInt("schemaVersion"));
            assertEquals(id, exported.getJSONObject("settings").getInt("background"));
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, exported.getJSONObject("settings").getString("cardFormat"));
            SettingsBackup.Preview preview = SettingsBackup.preview(json);
            assertTrue(preview.errors.toString(), preview.valid);
            assertEquals(id, preview.settings.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat);
            MemoryPreferences target = new MemoryPreferences();
            assertTrue(SettingsBackup.apply(new LauncherPrefs(target), preview));
            assertEquals(1, target.editCount); assertEquals(1, target.applyCount);
            LauncherPrefs.Settings restored = new LauncherPrefs(target).read();
            assertEquals(id, restored.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, restored.cardFormat);
            assertEquals(local.read().favoriteIds, restored.favoriteIds);
        }
    }
    @Test public void everySupportedLegacyThemeImportsAsClassicWithoutChangingTheOtherFields() throws Exception {
        for (int schema : new int[]{1, 2}) for (int legacyTheme = 0; legacyTheme <= 4; legacyTheme++) {
            JSONObject content = legacyContent(schema).put("theme", legacyTheme);
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, content));
            assertTrue("Legacy theme=" + legacyTheme + " " + preview.errors, preview.valid);
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat); assertEquals(schema == 1 ? 5 : 3, preview.resetFieldCount);
            MemoryPreferences store = new MemoryPreferences();
            LauncherPrefs prefs = new LauncherPrefs(store);
            assertTrue(SettingsBackup.apply(prefs, preview));
            LauncherPrefs.Settings restored = prefs.read();
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, restored.cardFormat); assertEquals(11, restored.background);
            assertEquals(37, restored.wallpaperDim); assertEquals("dark", restored.paletteMode);
            assertEquals(example().favoriteIds, restored.favoriteIds);
            assertEquals(example().hiddenPackages, restored.hiddenPackages);
            assertEquals(example().customLabels, restored.customLabels);
            assertFalse(restored.watchNextVisible);
            assertEquals(21, new JSONObject(SettingsBackup.exportJson(restored)).getJSONObject("settings").length());
            assertEquals(1, store.applyCount);
        }
    }
    @Test public void outOfRangeThemeSixCannotPartiallyImportOtherwiseValidFields() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("background", 27); store.values.put("favorite_ids_v2", "pkg.retained/.Main");
        Map<String, Object> before = new HashMap<>(store.values);
        JSONObject root = exported(); root.getJSONObject("settings").put("theme", 6);
        SettingsBackup.Preview preview = SettingsBackup.preview(root.toString());
        assertFalse(preview.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
        assertEquals(before, store.values); assertEquals(0, store.applyCount); assertEquals(0, store.editCount);
    }
    @Test public void unknownWallpaperImportFailsBeforeAnyMutationAndLocalSetterDefaults() throws Exception {
        MemoryPreferences store = new MemoryPreferences(); store.values.put("background", 17); store.values.put("theme", 4);
        Map<String, Object> before = new HashMap<>(store.values);
        for (Object invalid : new Object[]{-1, 49, 12.5, "12", JSONObject.NULL}) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(new JSONObject().put("background", invalid)));
            assertFalse("Wallpaper=" + invalid, preview.valid);
            assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
            assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
        }
        LauncherPrefs preferences = new LauncherPrefs(store);
        preferences.setBackground(49); assertEquals(WallpaperCatalog.DEFAULT_ID, preferences.read().background);
        preferences.setCardFormat("invalid"); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, preferences.read().cardFormat);
    }
    @Test public void oversizedExportFailsRatherThanDroppingUserSettings() {
        String longSegment = String.join("", Collections.nCopies(220, "x"));
        java.util.List<String> hidden = new ArrayList<>(); Map<String, String> labels = new LinkedHashMap<>();
        for (int index = 0; index < 128; index++) {
            String name = "pkg." + longSegment + index; hidden.add(name);
            labels.put(name, String.join("", Collections.nCopies(80, "Я")));
        }
        Map<String, Object> values = new LinkedHashMap<>(); values.put("hidden_packages", hidden); values.put("custom_labels", labels);
        try { SettingsBackup.exportJson(LauncherPrefs.decode(values)); fail("An over-limit backup must not be silently truncated"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("64 KiB")); }
    }

    @Test public void portableRecommendationsRoundTripWithoutExportingOrRebindingLocalChannels() throws Exception {
        MemoryPreferences source = new MemoryPreferences();
        LauncherPrefs preferences = new LauncherPrefs(source);
        preferences.setRecommendations(true, Arrays.asList("pkg.beta", "pkg.alpha"),
                Arrays.asList("pkg.beta", "pkg.alpha", "pkg.uninstalled"));
        String json = SettingsBackup.exportJson(preferences.read());
        JSONObject root = new JSONObject(json), settings = root.getJSONObject("settings");
        assertEquals(5, root.getInt("schemaVersion")); assertTrue(settings.getBoolean("recommendationsEnabled"));
        assertEquals(new JSONArray(Arrays.asList("pkg.beta", "pkg.alpha", "pkg.uninstalled")).toString(),
                settings.getJSONArray("recommendationSourceOrder").toString());
        assertFalse(json.contains("recommendationChannels")); assertFalse(json.contains("recommendation_channels"));
        assertFalse(json.contains("recommendationPackages")); assertFalse(json.contains("recommendation_packages"));
        assertFalse(json.contains("123456")); assertFalse(json.contains("987654")); assertFalse(json.contains("|"));
        assertFalse(SettingsBackup.preferenceValues(preferences.read()).containsKey("recommendation_channels"));
        assertFalse(SettingsBackup.preferenceValues(preferences.read()).containsKey("recommendation_packages"));
        SettingsBackup.Preview preview = SettingsBackup.preview(json);
        assertTrue(preview.errors.toString(), preview.valid);
        assertTrue(preview.settings.recommendationsEnabled); assertTrue(preview.settings.recommendationPackages.isEmpty());
        assertEquals(preferences.read().recommendationSourceOrder, preview.settings.recommendationSourceOrder);
        assertTrue(preview.warnings.stream().anyMatch(warning -> warning.contains("Select apps again")));

        MemoryPreferences target = new MemoryPreferences();
        target.values.put("recommendation_channels", "0|pkg.retained|333|111");
        target.values.put("recommendation_packages", "pkg.enabled");
        target.values.put("screensaver_video_uri", "content://already-granted/local");
        LauncherPrefs restored = new LauncherPrefs(target);
        assertTrue(SettingsBackup.apply(restored, preview));
        assertEquals(1, target.editCount); assertEquals(1, target.applyCount);
        assertFalse(target.values.containsKey("recommendation_channels"));
        assertEquals("", target.values.get("recommendation_packages"));
        assertTrue(restored.read().recommendationsEnabled); assertTrue(restored.read().recommendationPackages.isEmpty());
        assertEquals(preferences.read().recommendationSourceOrder, restored.read().recommendationSourceOrder);
        assertFalse(target.values.containsKey("screensaver_video_uri"));
    }

    @Test public void completeLegacyBackupDefaultsRecommendationsOffAndClearsExistingLocalSelection() throws Exception {
        JSONObject content = legacyContent(1);
        SettingsBackup.Preview preview = SettingsBackup.preview(document(content));
        assertTrue(preview.errors.toString(), preview.valid); assertEquals(5, preview.resetFieldCount);
        assertFalse(preview.settings.recommendationsEnabled); assertTrue(preview.settings.recommendationSourceOrder.isEmpty());
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("recommendations_enabled", true); store.values.put("recommendation_channels", "0|pkg.old|12|3");
        store.values.put("recommendation_source_order", "pkg.old");
        LauncherPrefs preferences = new LauncherPrefs(store);
        assertTrue(SettingsBackup.apply(preferences, preview));
        assertFalse(preferences.read().recommendationsEnabled); assertTrue(preferences.read().recommendationPackages.isEmpty());
        assertTrue(preferences.read().recommendationSourceOrder.isEmpty());
        assertEquals(example().favoriteIds, preferences.read().favoriteIds); assertEquals(11, preferences.read().background);
        assertEquals(1, store.editCount); assertEquals(1, store.applyCount);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("recommendationsEnabled", true))).valid);
        assertFalse(SettingsBackup.preview(document(new JSONObject().put("recommendationSourceOrder", new JSONArray()))).valid);
    }

    @Test public void invalidRecommendationBackupNeverPartiallyAppliesOrAcceptsLocalIdentity() throws Exception {
        JSONArray tooMany = new JSONArray(); for (int index = 0; index < 17; index++) tooMany.put("pkg.source" + index);
        JSONObject[] bad = {
                new JSONObject().put("recommendationsEnabled", "true"),
                new JSONObject().put("recommendationsEnabled", 1),
                new JSONObject().put("recommendationsEnabled", JSONObject.NULL),
                new JSONObject().put("recommendationSourceOrder", "pkg.a"),
                new JSONObject().put("recommendationSourceOrder", JSONObject.NULL),
                new JSONObject().put("recommendationSourceOrder", new JSONArray(Arrays.asList("pkg.a", "pkg.a"))),
                new JSONObject().put("recommendationSourceOrder", new JSONArray(Arrays.asList("content://private/source"))),
                new JSONObject().put("recommendationSourceOrder", new JSONArray(Arrays.asList("0|pkg.a|1|1"))),
                new JSONObject().put("recommendationSourceOrder", new JSONArray().put(1)),
                new JSONObject().put("recommendationSourceOrder", tooMany),
                new JSONObject().put("recommendationChannels", new JSONArray(Arrays.asList("0|pkg.a|1|1"))),
                new JSONObject().put("recommendation_channels", "0|pkg.a|1|1"),
                new JSONObject().put("recommendationPackages", new JSONArray(Arrays.asList("pkg.a"))),
                new JSONObject().put("recommendation_packages", "pkg.a"),
                new JSONObject().put("recommendationPosterUri", "content://private/image")};
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("recommendations_enabled", true); store.values.put("recommendation_channels", "0|pkg.local|12|3");
        store.values.put("favorite_ids_v2", "pkg.favorite/.Tv");
        Map<String, Object> before = new HashMap<>(store.values);
        for (JSONObject content : bad) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(2, content));
            assertFalse(content.toString(), preview.valid);
            assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
            assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
        }
    }

    @Test public void portablePackageOrderAcceptsFullLimitWithoutCreatingChannelBindings() throws Exception {
        JSONArray packages = new JSONArray(); for (int index = 0; index < 16; index++) packages.put("pkg.source" + index);
        SettingsBackup.Preview preview = SettingsBackup.preview(document(2, new JSONObject()
                .put("recommendationsEnabled", true).put("recommendationSourceOrder", packages)));
        assertTrue(preview.errors.toString(), preview.valid); assertEquals(16, preview.settings.recommendationSourceOrder.size());
        assertEquals("pkg.source0", preview.settings.recommendationSourceOrder.get(0));
        assertEquals("pkg.source15", preview.settings.recommendationSourceOrder.get(15));
        assertTrue(preview.settings.recommendationPackages.isEmpty()); assertTrue(preview.settings.recommendationsEnabled);
    }

    @Test public void bothCardFormatsRoundTripWithDayNightWeatherFavoritesAndPortableSources() throws Exception {
        for (String format : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.CARD_FORMAT_POSTER}) {
            Map<String, Object> values = SettingsBackup.preferenceValues(example());
            values.put("card_format", format); values.put("background", WallpaperCatalog.MAX_ID);
            values.put("weather_enabled", true); values.put("city_name", "Moscow");
            values.put("city_latitude", 55.75); values.put("city_longitude", 37.62);
            values.put("wallpaper_period", "schedule"); values.put("day_start_minutes", 421); values.put("night_start_minutes", 420);
            values.put("recommendations_enabled", true); values.put("recommendation_source_order", Arrays.asList("pkg.beta", "pkg.alpha"));
            values.put("recommendation_channels", Arrays.asList("0|pkg.beta|123|4"));
            String json = SettingsBackup.exportJson(LauncherPrefs.decode(values));
            JSONObject root = new JSONObject(json); assertEquals(5, root.getInt("schemaVersion"));
            assertEquals(format, root.getJSONObject("settings").getString("cardFormat"));
            SettingsBackup.Preview preview = SettingsBackup.preview(json);
            assertTrue(preview.errors.toString(), preview.valid); assertEquals(format, preview.settings.cardFormat);
            MemoryPreferences store = new MemoryPreferences();
            store.values.put("recommendation_channels", "0|pkg.local|42|9"); store.values.put("remote_existing_choice", "pkg.remote");
            LauncherPrefs restored = new LauncherPrefs(store);
            assertTrue(SettingsBackup.apply(restored, preview));
            LauncherPrefs.Settings actual = restored.read();
            assertEquals(format, actual.cardFormat); assertEquals(WallpaperCatalog.MAX_ID, actual.background);
            assertEquals("dark", actual.paletteMode); assertEquals("schedule", actual.wallpaperPeriod);
            assertEquals(421, actual.dayStartMinutes); assertEquals(420, actual.nightStartMinutes);
            assertEquals(example().favoriteIds, actual.favoriteIds); assertEquals(example().customLabels, actual.customLabels);
            assertTrue(actual.weatherEnabled); assertEquals("Moscow", actual.cityName);
            assertEquals(55.75, actual.cityLatitude, 0); assertEquals(37.62, actual.cityLongitude, 0);
            assertTrue(actual.recommendationsEnabled); assertEquals(Arrays.asList("pkg.beta", "pkg.alpha"), actual.recommendationSourceOrder);
            assertTrue("Portable source order cannot recreate local bindings", actual.recommendationPackages.isEmpty());
            assertEquals("pkg.remote", store.values.get("remote_existing_choice"));
            assertEquals(1, store.editCount); assertEquals(1, store.applyCount);
        }
    }

    @Test public void legacyThemeDomainIsVersionedAndInvalidValuesNeverPartiallyApply() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        store.values.put("card_format", LauncherPrefs.CARD_FORMAT_POSTER); store.values.put("background", 38);
        store.values.put("favorite_ids_v2", "pkg.retained/.Tv");
        Map<String, Object> before = new HashMap<>(store.values);
        for (int schema : new int[]{1, 2, 3, 4}) for (Object value : new Object[]{-1, 0, 1, 2, 3, 4, 5, 6, 0.5, "5", JSONObject.NULL}) {
            boolean expected = value instanceof Integer && (schema < 3
                    ? (Integer) value >= 0 && (Integer) value <= 4
                    : (Integer) value == 0 || (Integer) value == 5);
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, new JSONObject().put("theme", value)));
            assertEquals("schema=" + schema + ", theme=" + value, expected, preview.valid);
            if (expected) assertEquals((Integer) value == 5 ? LauncherPrefs.CARD_FORMAT_POSTER : LauncherPrefs.CARD_FORMAT_LANDSCAPE, preview.settings.cardFormat);
            else {
                assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
                assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
            }
        }
    }

    @Test public void legacyScreensaverFieldsAreValidatedDiscardedAndNeverExported() throws Exception {
        for (int schema : new int[]{1, 2, 3, 4}) {
            JSONObject content = legacyContent(schema);
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, content));
            assertTrue("schema=" + schema + ": " + preview.errors, preview.valid);
            assertEquals(schema == 1 ? 5 : schema <= 3 ? 3 : 0, preview.resetFieldCount);
            MemoryPreferences store = new MemoryPreferences();
            for (String key : new String[]{"screensaver_source", "screensaver_video_uri", "screensaver_scene", "screensaver_period",
                    "screensaver_interval_seconds", "screensaver_clock", "screensaver_motion"}) store.values.put(key, "obsolete");
            assertTrue(SettingsBackup.apply(new LauncherPrefs(store), preview));
            for (String key : store.values.keySet()) assertFalse(key, key.startsWith("screensaver"));
            assertFalse(SettingsBackup.exportJson(preview.settings).contains("screensaver"));
            content.put("screensaverMotion", "false");
            Map<String, Object> before = new HashMap<>(store.values); int edits = store.editCount;
            SettingsBackup.Preview invalid = SettingsBackup.preview(document(schema, content));
            assertFalse(invalid.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), invalid));
            assertEquals(before, store.values); assertEquals(edits, store.editCount);
        }
    }

    @Test public void currentSchemaRejectsRetiredFieldsAndMistypedFormatsAtomically() throws Exception {
        MemoryPreferences store = new MemoryPreferences(); store.values.put("favorite_ids_v2", "pkg.retained/.Tv");
        Map<String, Object> before = new HashMap<>(store.values);
        for (String key : new String[]{"theme", "screensaverSource", "screensaverScene", "screensaverPeriod", "screensaverIntervalSeconds", "screensaverMotion", "screensaverClock"}) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(5, new JSONObject().put(key, 0)));
            assertFalse(key, preview.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
        }
        for (Object value : new Object[]{"wide", "Poster", 0, 5, true, JSONObject.NULL}) {
            SettingsBackup.Preview preview = SettingsBackup.preview(document(5, new JSONObject().put("cardFormat", value)));
            assertFalse(String.valueOf(value), preview.valid); assertFalse(SettingsBackup.apply(new LauncherPrefs(store), preview));
        }
        assertEquals(before, store.values); assertEquals(0, store.editCount); assertEquals(0, store.applyCount);
    }

    @Test public void oldDepthWallpaperExportsBecomeDefaultWhileCurrentSchemaRejectsRetiredIds() throws Exception {
        for (int schema : new int[]{1, 2, 3, 4}) for (int id = 39; id <= 48; id++) {
            JSONObject content = legacyContent(schema).put("background", id);
            SettingsBackup.Preview preview = SettingsBackup.preview(document(schema, content));
            assertTrue("schema=" + schema + ", id=" + id + ": " + preview.errors, preview.valid);
            assertEquals(WallpaperCatalog.DEFAULT_ID, preview.settings.background);
            assertEquals(example().favoriteIds, preview.settings.favoriteIds);
            assertFalse(SettingsBackup.preview(document(5, new JSONObject().put("background", id))).valid);
        }
    }

    /** Commits a staged editor map once, so tests observe transaction boundaries rather than setter calls. */
    static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new LinkedHashMap<>(); int applyCount, editCount;
        @Override public Map<String, ?> getAll() { return new LinkedHashMap<>(values); }
        @Override public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> fallback) { Object value = values.get(key); return value instanceof Set ? new HashSet<>((Set<String>) value) : fallback; }
        @Override public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
        @Override public long getLong(String key, long fallback) { Object value = values.get(key); return value instanceof Long ? (Long) value : fallback; }
        @Override public float getFloat(String key, float fallback) { Object value = values.get(key); return value instanceof Float ? (Float) value : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() {
            editCount++;
            return new Editor() {
                final Map<String, Object> staged = new LinkedHashMap<>(); final Set<String> removed = new HashSet<>(); boolean clear;
                @Override public Editor putString(String key, String value) { staged.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { staged.put(key, new HashSet<>(value)); return this; }
                @Override public Editor putInt(String key, int value) { staged.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { staged.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { staged.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
                @Override public Editor remove(String key) { removed.add(key); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() { if (clear) values.clear(); for (String key : removed) values.remove(key); values.putAll(staged); applyCount++; }
            };
        }
    }
}
