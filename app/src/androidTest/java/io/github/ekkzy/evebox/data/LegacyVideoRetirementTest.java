package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real isolated SharedPreferences migration; no document provider, URI grant, or device setting is accessed. */
@RunWith(AndroidJUnit4.class)
public final class LegacyVideoRetirementTest {
    @Test public void schemaFiveContentUriBecomesInertWhileCurrentChoicesSurviveReopening() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences storage = app.getSharedPreferences("evebox_legacy_video_retirement_test", Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                assertEquals("Migration must stay in the isolated launcher namespace", LauncherPrefs.FILE_NAME, name);
                return storage;
            }
        };
        try {
            assertTrue(storage.edit().clear().putInt("schema_version", 5).putInt("theme", 5)
                    .putInt("background", 38).putString("favorite_ids_v2", "pkg.b/.Tv\npkg.a/.Main")
                    .putBoolean("weather_enabled", true).putString("city_name", "Moscow")
                    .putString("city_latitude", "55.75").putString("city_longitude", "37.62")
                    .putString("city_metadata_key", LauncherPrefs.cityMetadataKey("Moscow", 55.75, 37.62))
                    .putString("city_id", "524901").putString("city_timezone", "Europe/Moscow")
                    .putString("wallpaper_period", "schedule").putInt("day_start_minutes", 450).putInt("night_start_minutes", 1155)
                    .putString("screensaver_scene", "forest").putString("screensaver_period", "night")
                    .putString("screensaver_source", "video")
                    .putString("screensaver_video_uri", "content://synthetic.old.selection/document/fixture-only").commit());
            LauncherPrefs migrated = new LauncherPrefs(isolated);
            LauncherPrefs.Settings settings = migrated.read();
            assertEquals(LauncherPrefs.SCHEMA_VERSION, storage.getInt("schema_version", -1));
            assertFalse(storage.contains("screensaver_source")); assertFalse(storage.contains("screensaver_video_uri"));
            assertEquals(LauncherPrefs.CARD_FORMAT_POSTER, settings.cardFormat); assertEquals(38, settings.background);
            assertEquals(Arrays.asList("pkg.b/.Tv", "pkg.a/.Main"), settings.favoriteIds);
            assertTrue(settings.weatherEnabled); assertEquals("Moscow", settings.cityName);
            assertEquals("Europe/Moscow", settings.cityTimezone); assertEquals("524901", settings.cityId);
            assertEquals("schedule", settings.wallpaperPeriod);
            assertEquals(450, settings.dayStartMinutes); assertEquals(1155, settings.nightStartMinutes);
            for (String retired : new String[]{"theme", "screensaver_scene", "screensaver_period",
                    "screensaver_interval_seconds", "screensaver_motion", "screensaver_clock",
                    "screensaver_source", "screensaver_video_uri"})
                assertFalse("Retired key is removed: " + retired, storage.contains(retired));
            LauncherPrefs.Settings reopened = new LauncherPrefs(isolated).read();
            assertEquals(settings.cardFormat, reopened.cardFormat); assertEquals(settings.favoriteIds, reopened.favoriteIds);
            assertFalse(storage.contains("screensaver_video_uri"));
        } finally {
            assertTrue(storage.edit().clear().commit());
        }
    }
}
