package io.github.ekkzy.evebox.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Offline day/night policy. Solar position follows NOAA's general solar equations.
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 * No weather request, location permission, timer or mutable global state is needed. */
public final class DayNightPolicy {
    private DayNightPolicy() {}

    public static boolean isNight(LauncherPrefs.Settings settings, long nowMillis) {
        if ("day".equals(settings.wallpaperPeriod)) return false;
        if ("night".equals(settings.wallpaperPeriod)) return true;
        if ("auto".equals(settings.wallpaperPeriod) && settings.hasCity())
            return solarNight(settings.cityLatitude, settings.cityLongitude, nowMillis);
        ZoneId zone = settings.cityTimezone.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(settings.cityTimezone);
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        return scheduledNight(now.getHour() * 60 + now.getMinute(), settings.dayStartMinutes, settings.nightStartMinutes);
    }

    /** Next bounded re-evaluation; solar crossings are resolved to the UI's minute precision. */
    public static long nextChangeMillis(LauncherPrefs.Settings settings, long nowMillis) {
        if ("day".equals(settings.wallpaperPeriod) || "night".equals(settings.wallpaperPeriod)) return Long.MAX_VALUE;
        return nowMillis + 60_000 - Math.floorMod(nowMillis, 60_000);
    }

    static boolean scheduledNight(int minute, int dayStart, int nightStart) {
        if (dayStart == nightStart) return false;
        return dayStart < nightStart ? minute < dayStart || minute >= nightStart
                : minute >= nightStart && minute < dayStart;
    }

    static boolean solarNight(double latitude, double longitude, long nowMillis) {
        ZonedDateTime utc = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC);
        double hour = utc.getHour() + utc.getMinute() / 60d + utc.getSecond() / 3600d;
        double gamma = 2 * Math.PI / utc.toLocalDate().lengthOfYear() * (utc.getDayOfYear() - 1 + (hour - 12) / 24);
        double equation = 229.18 * (.000075 + .001868 * Math.cos(gamma) - .032077 * Math.sin(gamma)
                - .014615 * Math.cos(2 * gamma) - .040849 * Math.sin(2 * gamma));
        double declination = .006918 - .399912 * Math.cos(gamma) + .070257 * Math.sin(gamma)
                - .006758 * Math.cos(2 * gamma) + .000907 * Math.sin(2 * gamma)
                - .002697 * Math.cos(3 * gamma) + .00148 * Math.sin(3 * gamma);
        double angle = Math.toRadians((hour * 60 + equation + 4 * longitude) / 4 - 180);
        double lat = Math.toRadians(latitude);
        double cosineZenith = Math.sin(lat) * Math.sin(declination) + Math.cos(lat) * Math.cos(declination) * Math.cos(angle);
        // Standard apparent sunrise, including atmospheric refraction and the solar disk.
        return cosineZenith < Math.cos(Math.toRadians(90.833));
    }
}
