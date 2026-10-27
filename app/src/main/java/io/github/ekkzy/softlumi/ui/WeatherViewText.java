package io.github.ekkzy.softlumi.ui;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import io.github.ekkzy.softlumi.data.WeatherRepository;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared presentation only: no device-zone fallback, provider calls, storage or timers. */
final class WeatherViewText {
    private WeatherViewText() {}

    static Locale locale(boolean russian) { return russian ? Locale.forLanguageTag("ru-RU") : Locale.US; }
    static String tr(boolean russian, String ru, String en) { return russian ? ru : en; }
    static ZoneId zone(WeatherRepository.Weather weather) {
        if (weather != null && weather.cityTimezoneKnown) {
            try { return ZoneId.of(weather.timezone); }
            catch (DateTimeException | NullPointerException invalid) { /* UTC remains explicit in accessibility. */ }
        }
        return ZoneOffset.UTC;
    }
    static String time(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }
    static String temperature(double value, String units, boolean explicitUnit) {
        if (!Double.isFinite(value)) return "—";
        long rounded = Math.round(value);
        return (rounded > 0 ? "+" : "") + rounded + (explicitUnit ? "fahrenheit".equals(units) ? "°F" : "°C" : "°");
    }
    static String decimal(double value, boolean russian) {
        return Math.abs(value - Math.rint(value)) < .05 ? Long.toString(Math.round(value))
                : String.format(locale(russian), "%.1f", value);
    }
    static String condition(int code, boolean russian) {
        String ru, en;
        switch (code) {
            case 0: ru = "Ясно"; en = "Clear"; break;
            case 1: ru = "Малооблачно"; en = "Mostly clear"; break;
            case 2: ru = "Переменная облачность"; en = "Partly cloudy"; break;
            case 3: ru = "Пасмурно"; en = "Overcast"; break;
            case 45: case 48: ru = "Туман"; en = "Fog"; break;
            case 51: case 53: case 55: ru = "Морось"; en = "Drizzle"; break;
            case 56: case 57: ru = "Ледяная морось"; en = "Freezing drizzle"; break;
            case 61: case 63: case 65: ru = "Дождь"; en = "Rain"; break;
            case 66: case 67: ru = "Ледяной дождь"; en = "Freezing rain"; break;
            case 68: case 83: ru = "Дождь со снегом"; en = "Sleet"; break;
            case 71: case 73: case 75: case 77: ru = "Снег"; en = "Snow"; break;
            case 80: case 81: case 82: ru = "Ливень"; en = "Rain showers"; break;
            case 85: case 86: ru = "Снегопад"; en = "Snow showers"; break;
            case 95: ru = "Гроза"; en = "Thunderstorm"; break;
            case 96: case 99: ru = "Гроза с градом"; en = "Thunderstorm with hail"; break;
            default: ru = "Состояние не передано"; en = "Condition unavailable";
        }
        return russian ? ru : en;
    }
    static String symbol(int code, boolean day) {
        switch (code) {
            case 0: case 1: return day ? "sun-outline" : "moon-outline";
            case 2: return "partly-cloudy-outline";
            case 3: return "cloud-outline";
            case 45: case 48: return "fog";
            case 68: case 71: case 73: case 75: case 77: case 83: case 85: case 86: return "snow-outline";
            case 95: case 96: case 99: return "storm-outline";
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return "rain-outline";
            default: return null;
        }
    }
    static String hourSymbol(WeatherRepository.Weather weather, WeatherRepository.Hour hour) {
        Boolean day = hour.isDay;
        if (day == null) {
            for (WeatherRepository.Day dated : weather.days) {
                if (hour.timeMillis >= dated.startMillis && hour.timeMillis < dated.endMillis
                        && dated.sunriseMillis > 0 && dated.sunsetMillis > dated.sunriseMillis) {
                    day = hour.timeMillis >= dated.sunriseMillis && hour.timeMillis < dated.sunsetMillis;
                    break;
                }
            }
        }
        // An empty circle denotes clear sky without inventing a solar phase.
        if (day == null && (hour.weatherCode == 0 || hour.weatherCode == 1)) return "clear-outline";
        return symbol(hour.weatherCode, !Boolean.FALSE.equals(day));
    }
    static String attribution(WeatherRepository.WeatherResult result, boolean russian, long now) {
        WeatherRepository.Weather weather = result == null ? null : result.weather;
        if (weather == null) return "";
        boolean stale = result.status == WeatherRepository.Status.STALE
                || WeatherRepository.cacheAge(weather, now) != WeatherPolicy.CacheAge.FRESH;
        String fetched = weather.fetchedAtMillis <= 0
                ? tr(russian, "Время обновления неизвестно", "Update time unknown")
                : Instant.ofEpochMilli(weather.fetchedAtMillis).atZone(zone(weather)).format(
                        DateTimeFormatter.ofPattern(russian ? "d MMMM, HH:mm" : "MMM d, HH:mm", locale(russian)));
        return (stale ? tr(russian, "Сохранено ", "Saved ") : tr(russian, "Обновлено ", "Updated "))
                + fetched + ". " + weather.provider.attribution + ". "
                + (weather.cityTimezoneKnown
                        ? tr(russian, "Местное время города: ", "City local time: ") + weather.timezone
                        : tr(russian, "Часовой пояс города неизвестен. Время и даты по UTC.",
                                "The city time zone is unknown. Times and dates use UTC."));
    }
}
