package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Civil-time boundaries and solar sanity checks; no network, Android clock or timezone mutation. */
public final class DayNightPolicyTest {
    @Test public void ordinaryScheduleIncludesDayStartAndNightStartInTheCorrectPeriod(){
        LauncherPrefs.Settings settings=settings("schedule",7*60,19*60,"UTC",0,0);
        assertTrue(at(settings,"2026-10-25T06:59:59Z"));
        assertFalse(at(settings,"2026-10-25T07:00:00Z"));
        assertFalse(at(settings,"2026-10-25T18:59:59Z"));
        assertTrue(at(settings,"2026-10-25T19:00:00Z"));
        assertTrue(at(settings,"2026-10-25T23:59:59Z"));
        assertTrue(at(settings,"2026-10-26T00:00:00Z"));
    }

    @Test public void dayIntervalCanCrossCivilMidnight(){
        LauncherPrefs.Settings settings=settings("schedule",19*60,7*60,"UTC",0,0);
        assertFalse(at(settings,"2026-10-25T00:00:00Z"));
        assertFalse(at(settings,"2026-10-25T06:59:59Z"));
        assertTrue(at(settings,"2026-10-25T07:00:00Z"));
        assertTrue(at(settings,"2026-10-25T18:59:59Z"));
        assertFalse(at(settings,"2026-10-25T19:00:00Z"));
        assertFalse(at(settings,"2026-10-25T23:59:59Z"));
    }

    @Test public void scheduleUsesTheTrustedCityZoneAcrossDstSkippingAndRepeatedHours(){
        LauncherPrefs.Settings spring=settings("schedule",3*60,22*60,"America/New_York",40.71,-74.01);
        assertTrue(at(spring,"2026-03-08T06:59:59Z")); // 01:59:59 local
        assertFalse(at(spring,"2026-03-08T07:00:00Z")); // 03:00 local, 02:00 never happened
        LauncherPrefs.Settings autumn=settings("schedule",7*60,19*60,"America/New_York",40.71,-74.01);
        assertTrue(at(autumn,"2026-11-01T05:30:00Z")); // first 01:30
        assertTrue(at(autumn,"2026-11-01T06:30:00Z")); // repeated 01:30
        assertFalse(at(autumn,"2026-11-01T12:00:00Z")); // 07:00 after the offset changed
    }

    @Test public void autoWithoutACityUsesTheTvScheduleWithoutInventingCoordinates(){
        Map<String,Object> map=base("auto",7*60,19*60);
        LauncherPrefs.Settings settings=LauncherPrefs.decode(map);assertFalse(settings.hasCity());
        long early=LocalDate.of(2026,10,25).atTime(6,59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long day=LocalDate.of(2026,10,25).atTime(7,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertTrue(DayNightPolicy.isNight(settings,early));assertFalse(DayNightPolicy.isNight(settings,day));
    }

    @Test public void forcedPeriodsOverrideBothSolarPositionAndSchedule(){
        LauncherPrefs.Settings day=settings("day",7*60,19*60,"UTC",89,0);
        LauncherPrefs.Settings night=settings("night",7*60,19*60,"UTC",0,0);
        assertFalse(at(day,"2026-12-21T00:00:00Z"));
        assertTrue(at(night,"2026-03-20T12:00:00Z"));
        assertEquals(Long.MAX_VALUE,DayNightPolicy.nextChangeMillis(day,123456));
        assertEquals(Long.MAX_VALUE,DayNightPolicy.nextChangeMillis(night,123456));
    }

    @Test public void solarAutoUsesCoordinatesEvenWhenWeatherAndTimezoneMetadataAreUnavailable(){
        LauncherPrefs.Settings east=settings("auto",0,1,"",0,90);
        LauncherPrefs.Settings west=settings("auto",0,1,"",0,-90);
        assertFalse(east.weatherEnabled);assertEquals("",east.cityTimezone);assertTrue(east.hasCity());
        assertFalse("Near solar noon at 90E",at(east,"2026-03-20T06:00:00Z"));
        assertTrue("Near solar midnight at 90W",at(west,"2026-03-20T06:00:00Z"));
        assertFalse("Equatorial solar noon",at(settings("auto",0,1,"",0,0),"2026-03-20T12:00:00Z"));
        assertTrue("Equatorial solar midnight",at(settings("auto",0,1,"",0,0),"2026-03-20T00:00:00Z"));
    }

    @Test public void polarSummerAndWinterDoNotCreateArtificialDailyCrossings(){
        LauncherPrefs.Settings north=settings("auto",7*60,19*60,"",89,0);
        LauncherPrefs.Settings south=settings("auto",7*60,19*60,"",-89,0);
        for(String hour:new String[]{"00:00:00Z","12:00:00Z"}){
            assertFalse(at(north,"2026-06-21T"+hour));assertTrue(at(south,"2026-06-21T"+hour));
            assertTrue(at(north,"2026-12-21T"+hour));assertFalse(at(south,"2026-12-21T"+hour));
        }
    }

    @Test public void changingPeriodsReevaluatesOnTheNextMinuteWithoutBusyPolling(){
        LauncherPrefs.Settings settings=settings("schedule",7*60,19*60,"UTC",0,0);
        for(long now:new long[]{-1,0,1,59999,60000,123456789}){
            long next=DayNightPolicy.nextChangeMillis(settings,now);
            assertTrue(next>now);assertTrue(next-now<=60000);assertEquals(0,Math.floorMod(next,60000));
        }
    }

    @Test public void schemaFiveKeepsCardFormatFavoritesAndLegacyBackgroundWhileAddingDefaults(){
        for(int theme:new int[]{0,5}){
            Map<String,Object> old=base("auto",420,1140);old.remove("wallpaper_period");
            old.remove("day_start_minutes");old.remove("night_start_minutes");
            old.put("schema_version",5);old.put("theme",theme);old.put("background",28);
            old.put("favorite_ids_v2",Arrays.asList("pkg.first/.Tv","pkg.second/.Tv"));
            LauncherPrefs.Settings current=LauncherPrefs.decode(old);
            assertEquals(theme==5?LauncherPrefs.CARD_FORMAT_POSTER:LauncherPrefs.CARD_FORMAT_LANDSCAPE,current.cardFormat);assertEquals(28,current.background);
            assertEquals(Arrays.asList("pkg.first/.Tv","pkg.second/.Tv"),current.favoriteIds);
            assertEquals("auto",current.wallpaperPeriod);assertEquals(420,current.dayStartMinutes);assertEquals(1140,current.nightStartMinutes);
        }
    }

    @Test public void portableBackupRoundTripsScheduleAndFormatAndRejectsAmbiguousTimes()throws Exception{
        Map<String,Object> map=base("schedule",19*60,7*60);map.put("card_format",LauncherPrefs.CARD_FORMAT_POSTER);
        map.put("background",38);
        SettingsBackup.Preview preview=SettingsBackup.preview(SettingsBackup.exportJson(LauncherPrefs.decode(map)));
        assertTrue(preview.errors.toString(),preview.valid);assertEquals(LauncherPrefs.CARD_FORMAT_POSTER,preview.settings.cardFormat);assertEquals(38,preview.settings.background);
        assertEquals("schedule",preview.settings.wallpaperPeriod);assertEquals(1140,preview.settings.dayStartMinutes);assertEquals(420,preview.settings.nightStartMinutes);
        JSONObject invalid=new JSONObject(SettingsBackup.exportJson(preview.settings));
        invalid.getJSONObject("settings").put("dayStartMinutes",420).put("nightStartMinutes",420);
        assertFalse("Equal boundaries cannot represent a day/night choice",SettingsBackup.preview(invalid.toString()).valid);
    }

    private static Map<String,Object> base(String period,int day,int night){
        Map<String,Object> map=new HashMap<>();map.put("wallpaper_period",period);
        map.put("day_start_minutes",day);map.put("night_start_minutes",night);return map;
    }
    private static LauncherPrefs.Settings settings(String period,int day,int night,String zone,double latitude,double longitude){
        Map<String,Object> map=base(period,day,night);map.put("city_name","TEST City");
        map.put("city_latitude",latitude);map.put("city_longitude",longitude);
        map.put("city_timezone",zone);map.put("city_metadata_key",LauncherPrefs.cityMetadataKey("TEST City",latitude,longitude));
        return LauncherPrefs.decode(map);
    }
    private static boolean at(LauncherPrefs.Settings settings,String instant){return DayNightPolicy.isNight(settings,Instant.parse(instant).toEpochMilli());}
}
