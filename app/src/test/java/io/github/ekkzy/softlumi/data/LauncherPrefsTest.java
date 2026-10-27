package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import org.junit.Test;

public class LauncherPrefsTest {
    @Test public void defaultsArePrivateAndEmpty() {
        LauncherPrefs.Settings settings = LauncherPrefs.decode(Collections.emptyMap());
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        assertEquals(0, settings.background);
        assertFalse(settings.weatherEnabled);
        assertFalse(settings.hasCity());
        assertEquals("auto", settings.units);
        assertEquals("system", settings.language);
        assertTrue(settings.favoriteIds.isEmpty());
        assertFalse(settings.recommendationsEnabled);
        assertTrue(settings.recommendationPackages.isEmpty());
        assertTrue(settings.recommendationSourceOrder.isEmpty());
    }
    @Test public void migratesLegacyTypedValuesAndOrderedFavorites() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("selected_theme", "3");
        legacy.put("wallpaper", 2);
        legacy.put("show_weather", "true");
        legacy.put("weather_city", "Kazan");
        legacy.put("latitude", 55.79d);
        legacy.put("longitude", "49.12");
        legacy.put("temperature_unit", "F");
        legacy.put("favorites", "[\"pkg.b/.Tv\",\"pkg.a/.Main\",\"pkg.b/.Tv\"]");
        LauncherPrefs.Settings settings = LauncherPrefs.decode(legacy);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        assertEquals(2, settings.background);
        assertTrue(settings.weatherEnabled);
        assertTrue(settings.hasCity());
        assertEquals("fahrenheit", settings.units);
        assertEquals(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"), settings.favoriteIds);
    }
    @Test public void legacyUnorderedSetMigratesDeterministically() {
        Map<String, Object> old = new HashMap<>();
        old.put("favorites", new HashSet<>(Arrays.asList("z", "a", "m")));
        assertEquals(Arrays.asList("a", "m", "z"), LauncherPrefs.decode(old).favoriteIds);
    }
    @Test public void newEmptyFavoritesWinOverLegacyValue() {
        Map<String, Object> values = new HashMap<>();
        values.put("favorite_ids_v2", "");
        values.put("favorites", "[\"old\"]");
        assertTrue(LauncherPrefs.decode(values).favoriteIds.isEmpty());
    }
    @Test public void rejectsCorruptValuesWithoutLosingIndependentPreferences() {
        Map<String, Object> values = new HashMap<>();
        values.put("theme", 99);
        values.put("background", 3);
        values.put("city_name", "Invalid");
        values.put("city_latitude", "NaN");
        values.put("city_longitude", "0");
        values.put("language", "../../etc");
        values.put("favorites", "[broken]");
        LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        assertEquals(3, settings.background);
        assertFalse(settings.hasCity());
        assertEquals("system", settings.language);
        assertTrue(settings.favoriteIds.isEmpty());
    }
    @Test public void persistedFavoritesRetainEightSlotsAndOrderOnDecode() {
        assertEquals(Arrays.asList("h", "g", "f", "e", "d", "c", "b", "a"),
                LauncherPrefs.decode(Collections.singletonMap("favorite_ids_v2", "h\ng\nf\ne\nd\nc\nb\na\nz")).favoriteIds);
    }
    @Test public void oldV2ValuesKeepTheirChoicesAndReceiveNewDefaults() {
        Map<String, Object> old = new HashMap<>();
        old.put("schema_version", 2); old.put("theme", 3); old.put("background", 2);
        old.put("favorite_ids_v2", "pkg.a/.Main"); old.put("sounds", false);
        LauncherPrefs.Settings settings = LauncherPrefs.decode(old);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat); assertEquals(2, settings.background);
        assertEquals("auto", settings.paletteMode); assertEquals(15, settings.wallpaperDim);
        assertTrue(settings.watchNextVisible); assertTrue(settings.hiddenPackages.isEmpty());
        assertTrue(settings.customLabels.isEmpty());
        assertEquals(Arrays.asList("pkg.a/.Main"), settings.favoriteIds); assertFalse(settings.sounds);
    }
    @Test public void galleryRangeDoesNotExpandLayoutRange() {
        Map<String, Object> values = new HashMap<>(); values.put("background", 11); values.put("theme", 11);
        LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
        assertEquals(11, settings.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        values.put("background", 17); values.put("theme", 4);
        settings = LauncherPrefs.decode(values);
        assertEquals(17, settings.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        values.put("background", 18); assertEquals(18, LauncherPrefs.decode(values).background);
        values.put("background", 28); assertEquals(28, LauncherPrefs.decode(values).background);
        values.put("background", 49); values.put("theme", 6);
        settings = LauncherPrefs.decode(values);
        assertEquals(0, settings.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
    }
    @Test public void existingWallpaperIdsPreserveTheirMeaningAcrossLegacyKeys() {
        for (String key : new String[]{"background", "selected_background", "wallpaper"}) {
            for (int id = 0; id <= 11; id++) {
                Map<String, Object> values = new HashMap<>();
                values.put("schema_version", 2); values.put(key, id);
                values.put("theme", id % 4); values.put("favorite_ids_v2", "pkg.b/.Tv\npkg.a/.Main");
                LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
                assertEquals(key + " id=" + id, id, settings.background);
                assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
                assertEquals(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"), settings.favoriteIds);
            }
        }
    }
    @Test public void allBackgroundsKeepTheirIdentityWhileRetiredThemesNormalizeToTheMainTheme() {
        assertEquals(8, LauncherPrefs.SCHEMA_VERSION);
        for (int id = 12; id <= WallpaperCatalog.MAX_ID; id++) {
            Map<String, Object> values = new HashMap<>();
            values.put("schema_version", 3); values.put("theme", "4"); values.put("background", Integer.toString(id));
            LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
            assertEquals(id, settings.background); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
        }
        for (Object invalid : new Object[]{-1, 49, Integer.MAX_VALUE, 12.5, "invalid", "NaN"}) {
            assertEquals("Corrupt wallpaper=" + invalid, WallpaperCatalog.DEFAULT_ID,
                    LauncherPrefs.decode(Collections.singletonMap("background", invalid)).background);
        }
    }
    @Test public void legacyThemeReadAndCardSetterPreserveWallpaperCityAndFavorites() {
        for (int legacyTheme = 0; legacyTheme <= 4; legacyTheme++) {
            SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
            store.values.put("schema_version", 3); store.values.put("theme", legacyTheme);
            store.values.put("background", 27); store.values.put("weather_enabled", true);
            store.values.put("city_name", "Moscow"); store.values.put("city_latitude", "55.75222");
            store.values.put("city_longitude", "37.61556"); store.values.put("favorite_ids_v2", "pkg.b/.Tv\npkg.a/.Main");
            store.values.put("sounds", false);
            LauncherPrefs prefs = new LauncherPrefs(context(store));
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, prefs.read().cardFormat);
            prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, store.values.get("card_format"));
            assertFalse(store.values.containsKey("theme")); assertEquals(27, prefs.read().background);
            assertEquals("Moscow", prefs.read().cityName); assertTrue(prefs.read().weatherEnabled);
            assertEquals(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"), prefs.read().favoriteIds);
            assertFalse(prefs.read().sounds);
        }
    }
    @Test public void customizationCollectionsAreBoundedValidatedAndImmutable() {
        java.util.List<String> hidden = new java.util.ArrayList<>();
        for (int index = 0; index < 140; index++) hidden.add("pkg.hidden" + index);
        Map<String, String> labels = new HashMap<>(); labels.put("pkg.valid", "Переименованное приложение");
        labels.put("bad;command", "No"); labels.put("pkg.control", "Bad\nLabel");
        Map<String, Object> values = new HashMap<>(); values.put("hidden_packages", hidden); values.put("custom_labels", labels);
        LauncherPrefs.Settings settings = LauncherPrefs.decode(values); hidden.clear(); labels.clear();
        assertEquals(128, settings.hiddenPackages.size()); assertEquals(1, settings.customLabels.size());
        assertEquals("Переименованное приложение", settings.customLabels.get("pkg.valid"));
        try { settings.hiddenPackages.clear(); fail("Hidden packages must be immutable"); } catch (UnsupportedOperationException expected) { }
        try { settings.customLabels.clear(); fail("Labels must be immutable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void corruptExpandedValuesUseSafeDefaultsAndRetiredVideoKeysAreInert() {
        Map<String, Object> values = new HashMap<>(); values.put("palette_mode", "unknown"); values.put("wallpaper_dim", 99);
        values.put("screensaver_interval_seconds", 1); values.put("screensaver_source", "remote");
        values.put("screensaver_video_uri", "file:///private/movie.mp4");
        LauncherPrefs.Settings settings = LauncherPrefs.decode(values);
        assertEquals("auto", settings.paletteMode); assertEquals(15, settings.wallpaperDim);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, settings.cardFormat);
    }

    @Test public void schemaUpgradePreservesExistingChoicesAndRequiresNewRecommendationOptIn() {
        for (int oldVersion = 0; oldVersion <= 3; oldVersion++) {
            SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
            store.values.put("schema_version", oldVersion); store.values.put("theme", 4);
            store.values.put("background", 27); store.values.put("weather_enabled", true);
            store.values.put("favorite_ids_v2", "pkg.h/.Tv\npkg.g/.Tv\npkg.f/.Tv\npkg.e/.Tv\npkg.d/.Tv\npkg.c/.Tv\npkg.b/.Tv\npkg.a/.Tv");
            store.values.put("sounds", false); store.values.put("screensaver_video_uri", "content://local/granted");
            store.values.put("screensaver_source", "video");
            // Keys from an older/unknown version are not a current local selection.
            store.values.put("recommendations_enabled", true);
            store.values.put("recommendation_channels", "0|pkg.old|123|7");
            store.values.put("recommendation_source_order", "pkg.old");
            LauncherPrefs before = new LauncherPrefs(store);
            before.setCity("Moscow", 55.75, 37.62, "524901", "Europe/Moscow");
            List<String> favorites = before.read().favoriteIds;
            int edits = store.editCount, applies = store.applyCount;

            LauncherPrefs prefs = new LauncherPrefs(context(store));
            LauncherPrefs.Settings migrated = prefs.read();
            assertEquals(LauncherPrefs.SCHEMA_VERSION, store.values.get("schema_version"));
            assertEquals(edits + 1, store.editCount); assertEquals(applies + 1, store.applyCount);
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, migrated.cardFormat); assertEquals(27, migrated.background);
            assertEquals(favorites, migrated.favoriteIds); assertEquals(8, migrated.favoriteIds.size());
            assertTrue(migrated.weatherEnabled); assertEquals("Moscow", migrated.cityName);
            assertEquals("524901", migrated.cityId); assertEquals("Europe/Moscow", migrated.cityTimezone);
            assertFalse(migrated.sounds);
            assertFalse(store.values.containsKey("screensaver_source"));
            assertFalse(store.values.containsKey("screensaver_video_uri"));
            assertFalse(migrated.recommendationsEnabled); assertTrue(migrated.recommendationPackages.isEmpty());
            assertTrue(migrated.recommendationSourceOrder.isEmpty());
        }
    }

    @Test public void recommendationSelectionIsAtomicOrderedAndSurvivesRecreation() {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        store.values.put("schema_version", LauncherPrefs.SCHEMA_VERSION); store.values.put("card_format", LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        store.values.put("background", 27); store.values.put("favorite_ids_v2", "pkg.favorite/.Tv");
        LauncherPrefs prefs = new LauncherPrefs(context(store));
        List<String> channels = new ArrayList<>(Arrays.asList("pkg.beta", "pkg.alpha"));
        List<String> sources = new ArrayList<>(Arrays.asList("pkg.beta", "pkg.alpha"));
        prefs.setRecommendations(true, channels, sources);
        assertEquals(1, store.editCount); assertEquals(1, store.applyCount);
        channels.clear(); sources.clear();
        LauncherPrefs.Settings recreated = new LauncherPrefs(context(store)).read();
        assertTrue(recreated.recommendationsEnabled);
        assertEquals(Arrays.asList("pkg.beta", "pkg.alpha"), recreated.recommendationPackages);
        assertEquals(Arrays.asList("pkg.beta", "pkg.alpha"), recreated.recommendationSourceOrder);
        assertEquals(27, recreated.background); assertEquals(Arrays.asList("pkg.favorite/.Tv"), recreated.favoriteIds);
        assertEquals(1, store.editCount);
        try { recreated.recommendationPackages.clear(); fail("Local selections must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { recreated.recommendationSourceOrder.clear(); fail("Source order must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        prefs.setRecommendationsEnabled(false);
        assertFalse(prefs.read().recommendationsEnabled);
        assertEquals(recreated.recommendationPackages, prefs.read().recommendationPackages);
        prefs.setRecommendationChannels(null);
        prefs.setRecommendationSourceOrder(Arrays.asList("pkg.uninstalled", "pkg.alpha"));
        prefs.setRecommendationsEnabled(true);
        assertTrue(prefs.read().recommendationPackages.isEmpty());
        assertEquals(Arrays.asList("pkg.uninstalled", "pkg.alpha"), prefs.read().recommendationSourceOrder);
    }

    @Test public void packageSelectionHasOrderedLimitsAndLegacySetterOnlyMigratesValidatedBindings() {
        LauncherPrefs prefs = new LauncherPrefs(new SettingsBackupTest.MemoryPreferences());
        prefs.setRecommendationChannels(Arrays.asList(null, "broken", "0|pkg.a|1|1", "1|pkg.a|1|1",
                "0|pkg.a|2|1", "0|pkg.b|1|1", "0|pkg.c|1|1", "0|pkg.d|1|1"));
        assertEquals(Arrays.asList("pkg.a", "pkg.b", "pkg.c", "pkg.d"), prefs.read().recommendationPackages);
        List<String> packages = new ArrayList<>(Arrays.asList(null, "bad/package", "pkg.source0", "pkg.source0"));
        for (int index = 1; index < 20; index++) packages.add("pkg.source" + index);
        prefs.setRecommendationPackages(packages);
        assertEquals(6, prefs.read().recommendationPackages.size());
        assertEquals("pkg.source0", prefs.read().recommendationPackages.get(0));
        assertEquals("pkg.source5", prefs.read().recommendationPackages.get(5));
        prefs.setRecommendationSourceOrder(packages);
        assertEquals(16, prefs.read().recommendationSourceOrder.size());
        assertEquals("pkg.source15", prefs.read().recommendationSourceOrder.get(15));
        assertFalse(prefs.read().recommendationsEnabled);
    }

    @Test public void invalidChannelBindingsAreRejectedWithoutNormalizingTheirIdentity() {
        for (String value : new String[]{"", "pkg.a", "content://android.media.tv/channel/1",
                "0|pkg.a|1|0", "-1|pkg.a|1|1", "0|pkg.a|-1|1", "0|pkg.a|1|-1", "00|pkg.a|1|1",
                "0|pkg.a|01|1", "0|pkg.a|1|01", "+0|pkg.a|1|1", " 0|pkg.a|1|1", "0|pkg.a|1|1 ",
                "0|pkg/a|1|1", "0|pkg.a|1|1|extra", "0|pkg.a|9223372036854775808|1"}) {
            assertFalse(value, LauncherPrefs.validRecommendationChannelKey(value));
            assertTrue(value, LauncherPrefs.decode(Collections.singletonMap("recommendation_channels", Arrays.asList(value))).recommendationPackages.isEmpty());
        }
        assertTrue(LauncherPrefs.validRecommendationChannelKey("0|pkg.a|0|1"));
        assertTrue(LauncherPrefs.validRecommendationChannelKey("9223372036854775807|pkg.a|9223372036854775807|9223372036854775807"));
    }

    @Test public void corruptRecommendationStorageCannotEnableOrInventSelections() {
        for (Object raw : new Object[]{"true", 1, 1L, "1", false})
            assertFalse(LauncherPrefs.decode(Collections.singletonMap("recommendations_enabled", raw)).recommendationsEnabled);
        Map<String, Object> data = new HashMap<>();
        data.put("recommendation_channels", new HashSet<>(Arrays.asList("0|pkg.a|1|1")));
        data.put("recommendation_source_order", "content://private/source");
        assertTrue(LauncherPrefs.decode(data).recommendationPackages.isEmpty());
        assertTrue(LauncherPrefs.decode(data).recommendationSourceOrder.isEmpty());
        data.put("recommendation_channels", String.join("", Collections.nCopies(2400, "x")) + "\n0|pkg.a|1|1");
        data.put("recommendation_source_order", String.join("", Collections.nCopies(4200, "x")) + "\npkg.a");
        assertTrue(LauncherPrefs.decode(data).recommendationPackages.isEmpty());
        assertTrue(LauncherPrefs.decode(data).recommendationSourceOrder.isEmpty());
    }

    @Test public void schemaFourUpgradeKeepsClassicAndAllExistingRecommendationAndWeatherChoices() {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs existing = new LauncherPrefs(context(store));
        existing.setBackground(WallpaperCatalog.MAX_ID); existing.setPaletteMode("dark"); existing.setWallpaperDim(35);
        existing.setWeatherEnabled(true); existing.setCity("Moscow", 55.75, 37.62, "524901", "Europe/Moscow");
        existing.setFavorites(Arrays.asList("pkg.h/.Tv", "pkg.g/.Tv", "pkg.f/.Tv", "pkg.e/.Tv", "pkg.d/.Tv", "pkg.c/.Tv", "pkg.b/.Tv", "pkg.a/.Tv"));
        existing.setRecommendations(true, Arrays.asList("pkg.video"), Arrays.asList("pkg.video"));
        existing.setWatchNextVisible(false);
        store.values.put("screensaver_source", "video"); store.values.put("screensaver_video_uri", "content://local/granted");
        store.values.put("schema_version", 4);
        Map<String, Object> expected = new HashMap<>(store.values); expected.put("schema_version", LauncherPrefs.SCHEMA_VERSION);
        expected.remove("screensaver_source"); expected.remove("screensaver_video_uri");
        int applies = store.applyCount;

        LauncherPrefs upgraded = new LauncherPrefs(context(store));
        assertEquals(expected, store.values); assertEquals(applies + 1, store.applyCount);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, upgraded.read().cardFormat);
        assertEquals(8, upgraded.read().favoriteIds.size()); assertTrue(upgraded.read().recommendationsEnabled);
        assertEquals(Arrays.asList("pkg.video"), upgraded.read().recommendationPackages);
        assertEquals("Europe/Moscow", upgraded.read().cityTimezone);
    }

    @Test public void migrationRetiresThemesAndScreensaversAtomicallyWithoutLosingIndependentData() {
        for (int schema : new int[]{5, 6}) for (int theme : new int[]{0, 5}) {
            SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
            LauncherPrefs current = new LauncherPrefs(context(store));
            current.setBackground(38); current.setWallpaperDim(35); current.setPaletteMode("light");
            current.setWallpaperPeriod("schedule"); current.setDayNightSchedule(450, 1155);
            current.setWeatherEnabled(true); current.setCity("Moscow", 55.75, 37.62, "524901", "Europe/Moscow");
            current.setFavorites(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"));
            current.setRecommendations(true, Arrays.asList("pkg.publisher"), Arrays.asList("pkg.publisher"));
            store.values.put("schema_version", schema); store.values.remove("card_format");
            store.values.put("theme", theme); store.values.put("selected_theme", 3);
            store.values.put("layout", 4); store.values.put("layout_index", 1);
            for (String key : retiredScreensaverKeys()) store.values.put(key, "obsolete");
            store.values.put("remote_existing_choice", "pkg.remote");
            String expectedFormat = theme == 5 ? LauncherPrefs.CARD_FORMAT_POSTER : LauncherPrefs.CARD_FORMAT_LANDSCAPE;
            Map<String, Object> expected = new HashMap<>(store.values);
            expected.put("schema_version", LauncherPrefs.SCHEMA_VERSION); expected.put("card_format", expectedFormat);
            for (String key : retiredScreensaverKeys()) expected.remove(key);
            for (String key : new String[]{"theme", "selected_theme", "layout", "layout_index"}) expected.remove(key);
            int edits = store.editCount, applies = store.applyCount;
            LauncherPrefs migrated = new LauncherPrefs(context(store));
            assertEquals("Only the retired fields, card format and schema may change", expected, store.values);
            assertEquals(edits + 1, store.editCount); assertEquals(applies + 1, store.applyCount);
            assertEquals(expectedFormat, migrated.read().cardFormat); assertEquals("light", migrated.read().paletteMode);
            assertEquals("schedule", migrated.read().wallpaperPeriod);
            assertEquals(450, migrated.read().dayStartMinutes); assertEquals(1155, migrated.read().nightStartMinutes);
            assertEquals("Europe/Moscow", migrated.read().cityTimezone);
            assertEquals(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"), migrated.read().favoriteIds);
            new LauncherPrefs(context(store));
            assertEquals("Migration runs once", applies + 1, store.applyCount);
        }
    }

    @Test public void cardFormatRoundTripsIndependentlyOfPaletteWallpaperAndSharedData() {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs prefs = new LauncherPrefs(context(store));
        prefs.setFavorites(Arrays.asList("pkg.b/.Tv", "pkg.a/.Tv"));
        prefs.setWeatherEnabled(true); prefs.setCity("Moscow", 55.75, 37.62);
        prefs.setRecommendations(true, Arrays.asList("pkg.video"), Arrays.asList("pkg.video"));
        prefs.setPaletteMode("light"); prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);
        Map<String, Object> selected = new HashMap<>(store.values); int applies = store.applyCount;
        LauncherPrefs reopened = new LauncherPrefs(context(store));
        assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, reopened.read().cardFormat); assertEquals(selected, store.values);
        assertEquals("Reopening valid preferences must not rewrite them", applies, store.applyCount);
        reopened.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        selected.put("card_format", LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        assertEquals(selected, store.values);
        reopened.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);
        for (int wallpaper = 0; wallpaper <= WallpaperCatalog.MAX_ID; wallpaper++) {
            reopened.setBackground(wallpaper);
            assertEquals(wallpaper, reopened.read().background); assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, reopened.read().cardFormat);
            assertEquals("light", reopened.read().paletteMode);
        }
    }

    @Test public void currentCardFormatWinsAndInvalidValuesNeverReviveRetiredThemes() {
        Map<String, Object> values = new HashMap<>(); values.put("theme", 5);
        assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, LauncherPrefs.decode(values).cardFormat);
        values.put("schema_version", 4); assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.decode(values).cardFormat);
        values.put("schema_version", 5); assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, LauncherPrefs.decode(values).cardFormat);
        values.put("schema_version", 6); assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, LauncherPrefs.decode(values).cardFormat);
        values.put("card_format", LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.decode(values).cardFormat);
        for (Object invalid : new Object[]{-1, 5, "5", "Poster", "invalid", true}) {
            values.put("card_format", invalid);
            assertEquals("Explicit invalid card format=" + invalid, LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.decode(values).cardFormat);
            SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
            store.values.putAll(values); store.values.put("schema_version", LauncherPrefs.SCHEMA_VERSION); store.values.put("background", 38);
            for (String key : retiredScreensaverKeys()) store.values.put(key, "obsolete");
            LauncherPrefs normalized = new LauncherPrefs(context(store));
            assertEquals(LauncherPrefs.CARD_FORMAT_LANDSCAPE, normalized.read().cardFormat);
            assertEquals(38, normalized.read().background); assertFalse(store.values.containsKey("theme"));
            for (String key : retiredScreensaverKeys()) assertFalse(store.values.containsKey(key));
        }
    }

    @Test public void retiredDepthWallpapersFallbackButRemainingIdsAndFutureStorageArePreserved() {
        for (int id = 39; id <= 48; id++)
            assertEquals(WallpaperCatalog.DEFAULT_ID, LauncherPrefs.decode(Collections.singletonMap("background", id)).background);
        SettingsBackupTest.MemoryPreferences future = new SettingsBackupTest.MemoryPreferences();
        future.values.put("schema_version", LauncherPrefs.SCHEMA_VERSION + 1); future.values.put("theme", 5);
        future.values.put("screensaver_scene", "unknown-future-data"); future.values.put("card_format", "future");
        Map<String, Object> before = new HashMap<>(future.values);
        new LauncherPrefs(context(future)); assertEquals(before, future.values); assertEquals(0, future.applyCount);
    }

    @Test public void schemaSevenMigratesAnyEnabledShelfIntoOneAppAtomically() {
        SettingsBackupTest.MemoryPreferences store = new SettingsBackupTest.MemoryPreferences();
        LauncherPrefs before = new LauncherPrefs(context(store));
        before.setFavorites(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"));
        before.setCity("Moscow", 55.75, 37.62, "524901", "Europe/Moscow");
        before.setWallpaperPeriod("schedule"); before.setDayNightSchedule(421, 420);
        before.setPaletteMode("dark"); before.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);
        before.setRecommendationSourceOrder(Arrays.asList("pkg.beta", "pkg.alpha"));
        before.setRecommendationsEnabled(true);
        store.values.put("schema_version", 7); store.values.remove("recommendation_packages");
        store.values.put("recommendation_channels", "0|pkg.alpha|123|1\n0|pkg.alpha|123|2\n1|pkg.alpha|456|9\n0|pkg.beta|789|3");
        store.values.put("remote_existing_choice", "pkg.remote");
        Map<String, Object> expected = new HashMap<>(store.values);
        expected.put("schema_version", 8); expected.remove("recommendation_channels");
        expected.put("recommendation_packages", "pkg.alpha\npkg.beta");
        int edits = store.editCount, applies = store.applyCount;
        LauncherPrefs migrated = new LauncherPrefs(context(store));
        assertEquals("Only schema and the local source representation change", expected, store.values);
        assertEquals(edits + 1, store.editCount); assertEquals(applies + 1, store.applyCount);
        assertEquals(Arrays.asList("pkg.alpha", "pkg.beta"), migrated.read().recommendationPackages);
        assertTrue(migrated.read().recommendationsEnabled);
        assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, migrated.read().cardFormat);
        assertEquals("Europe/Moscow", migrated.read().cityTimezone);
        assertEquals(421, migrated.read().dayStartMinutes); assertEquals(420, migrated.read().nightStartMinutes);
        new LauncherPrefs(context(store)); assertEquals("Reopening does not repeat migration", applies + 1, store.applyCount);
    }
    @Test public void explicitPackageOptOutOrCorruptionNeverRevivesRetiredChannelSelections() {
        for (Object raw : new Object[]{"", Collections.emptyList(), 5, true, new HashSet<>(Arrays.asList("pkg.new")), "bad/package"}) {
            Map<String, Object> values = new HashMap<>(); values.put("schema_version", 7);
            values.put("recommendation_channels", "0|pkg.old|123|1"); values.put("recommendation_packages", raw);
            assertTrue("Explicit packages=" + raw, LauncherPrefs.decode(values).recommendationPackages.isEmpty());
        }
        for (int schema : new int[]{8, 9}) {
            Map<String, Object> values = new HashMap<>(); values.put("schema_version", schema);
            values.put("recommendation_channels", "0|pkg.old|123|1");
            assertTrue("Retired keys do not opt in current/future storage", LauncherPrefs.decode(values).recommendationPackages.isEmpty());
        }
        Map<String, Object> card = new HashMap<>(); card.put("schema_version", 7); card.put("theme", 5);
        assertEquals("Moving the recommendation schema cannot revive a retired theme", LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.decode(card).cardFormat);
    }
    @Test public void packageStorageRejectsForeignTypesOversizeAndChannelKeysWithoutChangingConsent() {
        LauncherPrefs prefs = new LauncherPrefs(new SettingsBackupTest.MemoryPreferences());
        prefs.setRecommendationPackages(Arrays.asList("pkg.real", "0|pkg.not_selected|1|1", "pkg.real", "bad package"));
        assertEquals(Collections.singletonList("pkg.real"), prefs.read().recommendationPackages);
        assertFalse(prefs.read().recommendationsEnabled);
        String huge = String.join("", Collections.nCopies(1600, "x")) + "\npkg.real";
        assertTrue(LauncherPrefs.decode(Collections.singletonMap("recommendation_packages", huge)).recommendationPackages.isEmpty());
    }

    private static String[] retiredScreensaverKeys() {
        return new String[]{"screensaver_source", "screensaver_video_uri", "screensaver_interval_seconds",
                "screensaver_motion", "screensaver_clock", "screensaver_scene", "screensaver_period"};
    }

    private static Context context(SettingsBackupTest.MemoryPreferences store) {
        return new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return LauncherPrefs.FILE_NAME.equals(name) ? store : new SettingsBackupTest.MemoryPreferences();
            }
        };
    }
}
