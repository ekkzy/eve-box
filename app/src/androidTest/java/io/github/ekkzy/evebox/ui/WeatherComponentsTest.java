package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.ekkzy.evebox.core.WeatherPolicy;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WeatherRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;

/** Isolated native Views: no Activity preferences, real providers, installed apps or device mutations. */
@RunWith(AndroidJUnit4.class)
public final class WeatherComponentsTest {
    private static final ZoneId CITY_ZONE = ZoneId.of("Europe/Moscow");
    private static final long NOW = Instant.parse("2026-10-25T10:24:00Z").toEpochMilli();
    private static final int TEXT = 0xfff5f5f7, MUTED = 0xffc9c6c2;

    @Test public void lowerPresentsFourTwoHourlyPointsOnSharedAxisBeforeDaysWithoutFooterOrConfigureAction() {
        WeatherRepository.WeatherResult result = result(weather(false));
        assertEquals(WeatherRepository.Status.FRESH, result.status);
        onMain(() -> {
            ThreeDayForecastView view = new ThreeDayForecastView(context());
            int height = view.bind(result, true, "Москва", true, TEXT, MUTED, 1, NOW);
            view.layout(0, 0, ThreeDayForecastView.DESIGN_WIDTH, height);
            assertEquals(4, count(view, "forecast-hour-cell-")); assertEquals(3, count(view, "forecast-day-"));
            View hours = required(view, "forecast-hours");
            View firstDay = required(view, "forecast-day-2026-10-25");
            assertTrue("The four time points precede the daily columns", top(hours) + hours.getHeight() <= top(firstDay));
            assertEquals("14:00", text(required(view, "forecast-hour-cell-" + at(0, 14)), "forecast-hour-time"));
            assertEquals("+17°C", text(required(view, "forecast-hour-cell-" + at(0, 14)), "forecast-hour-temperature"));
            assertEquals("+15°C", text(required(view, "forecast-hour-cell-" + at(0, 16)), "forecast-hour-temperature"));
            assertEquals("+13°C", text(required(view, "forecast-hour-cell-" + at(0, 18)), "forecast-hour-temperature"));
            assertEquals("+11°C", text(required(view, "forecast-hour-cell-" + at(0, 20)), "forecast-hour-temperature"));
            assertNull("A supplied intermediate hour is not a selected two-hour point",
                    view.findViewWithTag("forecast-hour-cell-" + at(0, 15)));
            assertSharedAxisGeometry(view);
            assertEquals("13:24", text(view, "forecast-updated"));
            assertNull(view.findViewWithTag("forecast-footer"));
            assertFalse("No Configure or other focusable child", focusableDescendant(view));
            assertFalse("No visible source/legal paragraph", visibleText(view).contains("CC BY"));
            assertTrue(view.getContentDescription().toString().contains("Europe/Moscow"));
            assertTrue(view.getContentDescription().toString().contains("CC BY 4.0"));
            assertFalse("Known city hours have no UTC suffix", visibleText(hours).contains("UTC"));
            assertTrue("The complete lower forecast fits its agreed local design height: " + height, height > 200 && height <= 345);
            reportHeight("RU A4 with three full days", height);
        });
    }

    @Test public void unknownCityZoneHidesActualUtcHoursAndUsesRelativeUpdateAgeWithoutDeviceZoneFallback() throws Exception {
        LocalDate first = Instant.ofEpochMilli(NOW).atOffset(ZoneOffset.UTC).toLocalDate();
        List<WeatherRepository.Day> days = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long start = first.plusDays(index).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            long end = first.plusDays(index + 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            days.add(new WeatherRepository.Day(first.plusDays(index).toString(), start, end, 3, 5, 12,
                    .5, Double.NaN, 10.8, Double.NaN, Double.NaN, 0, 0, true, false, start, end));
        }
        WeatherRepository.Weather cached = new WeatherRepository.Weather(8, 3, true, 10.8, NOW, NOW - 8 * 60_000L,
                "celsius", weather(false).hours, 0, 0, "UTC", WeatherRepository.Provider.MET_NORWAY,
                NOW + 3_600_000L, days);
        JSONObject snapshot = new JSONObject(WeatherRepository.CacheCodec.encode(cached))
                .put("city_timezone_known", false).put("forecast_time_basis", "UTC");
        WeatherRepository.Weather unknown = WeatherRepository.CacheCodec.decode(snapshot.toString());
        assertFalse(unknown.cityTimezoneKnown);
        assertEquals("The provider really has future samples; the View must withhold their unqualified clock labels",
                3, unknown.upcomingHours(NOW).size());
        WeatherRepository.WeatherResult result = result(unknown);
        onMain(() -> {
            ThreeDayForecastView view = new ThreeDayForecastView(context());
            for (boolean russian : new boolean[]{false, true}) {
                view.bind(result, true, "Москва", russian, TEXT, MUTED, 1, NOW);
                assertEquals(0, count(view, "forecast-hour-cell-"));
                assertNull(view.findViewWithTag("forecast-hours"));
                assertNull(view.findViewWithTag("forecast-hours-title"));
                assertNull(view.findViewWithTag("forecast-hours-axis"));
                assertEquals(russian ? "Почасовой прогноз пока недоступен" : "Hourly forecast is not available yet",
                        text(view, "forecast-hours-message"));
                assertEquals(russian ? "8 мин назад" : "8m ago", text(view, "forecast-updated"));
                assertEquals(3, count(view, "forecast-day-"));
                String date = text(required(view, "forecast-day-2026-10-25"), "forecast-date");
                assertFalse(date.contains("Today") || date.contains("Tomorrow") || date.contains("Сегодня") || date.contains("Завтра"));
                assertTrue(view.getContentDescription().toString().contains("UTC"));
                assertTrue(view.getContentDescription().toString().contains(russian
                        ? "Часовой пояс города пока неизвестен." : "The city time zone is not known yet."));
                assertFalse(focusableDescendant(view));
            }
            view.bind(result(weather(false)), true, "Москва", false, TEXT, MUTED, 1, NOW);
            assertEquals("A later trusted city zone restores the four actual two-hour slots", 4, count(view, "forecast-hour-cell-"));
            assertEquals("13:24", text(view, "forecast-updated"));
        });
    }

    @Test public void fourPointTimelineAdvancesWithCityTimeAndMarksTomorrowAcrossMidnight() {
        long evening = at(0, 18) + 20 * 60_000L;
        WeatherRepository.Weather base = weather(false);
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        hours.add(new WeatherRepository.Hour(at(0, 14), 17, 0, true));
        for (int hour = 19; hour <= 23; hour++) hours.add(new WeatherRepository.Hour(at(0, hour), 28 - hour, 0, false));
        for (int hour = 0; hour <= 2; hour++) hours.add(new WeatherRepository.Hour(at(1, hour), 4 - hour, 0, false));
        List<WeatherRepository.Day> days = new ArrayList<>(base.days);
        WeatherRepository.Day first = days.get(0);
        days.set(0, new WeatherRepository.Day(first.date, first.startMillis, first.endMillis, first.weatherCode,
                first.minTemperature, first.maxTemperature, first.precipitationMm, first.precipitationProbabilityMax,
                first.windSpeedMax, first.windGustsMax, first.uvIndexMax, first.sunriseMillis, first.sunsetMillis,
                first.aggregated, true, first.coverageStartMillis, first.coverageEndMillis));
        WeatherRepository.Weather source = new WeatherRepository.Weather(8, 0, false, 10.8, evening, evening,
                "celsius", hours, at(0, 7), at(0, 17), CITY_ZONE.getId(), WeatherRepository.Provider.OPEN_METEO, 0, days);
        WeatherRepository.WeatherResult result = result(source, evening);
        onMain(() -> {
            ThreeDayForecastView view = new ThreeDayForecastView(context());
            int height = view.bind(result, true, "Москва", true, TEXT, MUTED, 1, evening);
            view.layout(0, 0, ThreeDayForecastView.DESIGN_WIDTH, height);
            assertSlotTimes(view, "19:00", "21:00", "23:00", "01:00");
            assertNull("Past daytime values are absent at 18:20", view.findViewWithTag("forecast-hour-cell-" + at(0, 14)));
            assertEquals("+9°C", text(required(view, "forecast-hour-cell-" + at(0, 19)), "forecast-hour-temperature"));
            assertEquals("+3°C", text(required(view, "forecast-hour-cell-" + at(1, 1)), "forecast-hour-temperature"));
            assertEquals("завтра", text(required(view, "forecast-hour-cell-" + at(1, 1)), "forecast-hour-day-note"));
            assertTrue(view.getContentDescription().toString().contains("2026-10-26 01:00"));
            assertTrue("Tomorrow note preserves the lower page fit: " + height, height <= 345);
            reportHeight("RU18:20 A4 with tomorrow and partial-day note", height);
            assertSharedAxisGeometry(view);

            long later = at(0, 19) + 60_000L;
            height = view.bind(result, true, "Moscow", false, TEXT, MUTED, 1, later);
            view.layout(0, 0, ThreeDayForecastView.DESIGN_WIDTH, height);
            assertSlotTimes(view, "20:00", "22:00", "00:00", "02:00");
            assertNull("A rebind removes the now-past 19:00 sample", view.findViewWithTag("forecast-hour-cell-" + at(0, 19)));
            assertEquals("+8°C", text(required(view, "forecast-hour-cell-" + at(0, 20)), "forecast-hour-temperature"));
            assertEquals("+2°C", text(required(view, "forecast-hour-cell-" + at(1, 2)), "forecast-hour-temperature"));
            assertEquals("Tomorrow", text(required(view, "forecast-hour-cell-" + at(1, 0)), "forecast-hour-day-note"));
            assertEquals("Tomorrow", text(required(view, "forecast-hour-cell-" + at(1, 2)), "forecast-hour-day-note"));
            assertEquals("The earlier same-day slot does not gain a tomorrow label", " ",
                    text(required(view, "forecast-hour-cell-" + at(0, 20)), "forecast-hour-day-note"));
            assertTrue(height <= 345); assertSharedAxisGeometry(view);
            reportHeight("EN19:01 A4 with Tomorrow and partial-day note", height);
        });
    }

    @Test public void compactUsesHourlyDayNightSamplesAndRetainsGlassHostsThroughRebinds() {
        WeatherRepository.WeatherResult result = result(weather(false));
        onMain(() -> {
            CompactWeatherView view = new CompactWeatherView(context());
            List<View> hosts = new ArrayList<>(view.glassSurfaces());
            int height = view.bind(result, true, "Москва", true, 0xff181818, 0xff424242, TEXT, MUTED, 1, NOW);
            view.layout(0, 0, CompactWeatherView.DESIGN_WIDTH, height);
            assertEquals(0xff181818, ((TextView) required(view, "compact-weather-city")).getCurrentTextColor());
            assertEquals(TEXT, ((TextView) required(required(view, "compact-weather-day"), "compact-weather-chip-value")).getCurrentTextColor());
            assertEquals("+17°", text(required(view, "compact-weather-day"), "compact-weather-chip-value"));
            assertEquals("+7°", text(required(view, "compact-weather-night"), "compact-weather-chip-value"));
            assertEquals("3 м/с", text(required(view, "compact-weather-wind"), "compact-weather-chip-value"));
            assertFalse("Daily maximum must not become the day sample", visibleText(view).contains("+30°"));
            assertTrue(view.getContentDescription().toString().contains("2026-10-26 02:00"));
            assertTrue(view.getContentDescription().toString().contains("Open-Meteo"));
            view.bind(null, true, "Москва", true, TEXT, MUTED, 1, NOW);
            assertEquals("—", text(view, "compact-weather-temperature"));
            for (int index = 0; index < hosts.size(); index++) assertSame(hosts.get(index), view.glassSurfaces().get(index));
            assertFalse(focusableDescendant(view));
        });
    }

    @Test public void unknownFieldsAndExpiredCacheNeverFabricateForecastNumbers() {
        WeatherRepository.WeatherResult result = result(weather(true));
        onMain(() -> {
            ThreeDayForecastView view = new ThreeDayForecastView(context());
            view.bind(result, true, "Москва", false, TEXT, MUTED, 1, NOW);
            View missing = required(view, "forecast-day-2026-10-26");
            assertTrue(text(missing, "forecast-temperature").contains("low —"));
            assertNull(missing.findViewWithTag("forecast-precipitation"));
            assertNull(missing.findViewWithTag("forecast-wind"));
            View missingHour = required(view, "forecast-hour-cell-" + at(0, 18));
            assertEquals("18:00", text(missingHour, "forecast-hour-time"));
            assertEquals("—", text(missingHour, "forecast-hour-temperature"));
            assertNull("An absent sample has no invented weather mark", missingHour.findViewWithTag("forecast-hour-icon"));
            assertEquals("+11°C", text(required(view, "forecast-hour-cell-" + at(0, 20)), "forecast-hour-temperature"));
            assertTrue(view.getContentDescription().toString().contains("2026-10-25 18:00, No data"));
            view.bind(result, true, "Москва", false, TEXT, MUTED, 1, NOW + WeatherPolicy.MAX_STALE_MILLIS + 1);
            assertEquals(0, count(view, "forecast-hour-cell-")); assertEquals(0, count(view, "forecast-day-"));
            assertNotNull(view.findViewWithTag("forecast-message"));
            assertTrue(text(view, "forecast-updated").startsWith("Saved "));
        });
    }

    @Test public void hourlyClearGlyphUsesProviderPhaseOrRealSolarEventsWithoutGuessing() {
        WeatherRepository.Weather source = weather(false);
        assertEquals("moon-outline", WeatherViewText.hourSymbol(source, new WeatherRepository.Hour(at(1, 2), 7, 0, false)));
        assertEquals("sun-outline", WeatherViewText.hourSymbol(source, new WeatherRepository.Hour(at(0, 14), 17, 0, true)));
        WeatherRepository.Weather noSolar = new WeatherRepository.Weather(8, 0, true, 10.8, NOW, NOW, "celsius");
        assertEquals("clear-outline", WeatherViewText.hourSymbol(noSolar, new WeatherRepository.Hour(NOW + 3600_000, 9, 0)));
    }

    private static WeatherRepository.Weather weather(boolean missing) {
        LocalDate first = Instant.ofEpochMilli(NOW).atZone(CITY_ZONE).toLocalDate();
        List<WeatherRepository.Day> days = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long start = first.plusDays(index).atStartOfDay(CITY_ZONE).toInstant().toEpochMilli();
            long end = first.plusDays(index + 1).atStartOfDay(CITY_ZONE).toInstant().toEpochMilli();
            boolean absent = missing && index == 1;
            days.add(new WeatherRepository.Day(first.plusDays(index).toString(), start, end, absent ? -1 : index,
                    absent ? Double.NaN : -5 + index, 30 + index, absent ? Double.NaN : .5,
                    absent ? Double.NaN : 20, absent ? Double.NaN : 10.8, Double.NaN, Double.NaN,
                    start + 7 * 3600_000L, start + 17 * 3600_000L, false, false, start, end));
        }
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        hours.add(new WeatherRepository.Hour(at(0, 12), 19, 0, true));
        hours.add(new WeatherRepository.Hour(at(0, 14), 17, 0, true));
        hours.add(new WeatherRepository.Hour(at(0, 15), 16, 2, true));
        hours.add(new WeatherRepository.Hour(at(0, 16), 15, 3, true));
        if (!missing) hours.add(new WeatherRepository.Hour(at(0, 18), 13, 0, false));
        hours.add(new WeatherRepository.Hour(at(0, 20), 11, 0, false));
        hours.add(new WeatherRepository.Hour(at(1, 2), 7, 0, false));
        return new WeatherRepository.Weather(8, 0, true, 10.8, NOW, NOW, "celsius", hours,
                at(0, 7), at(0, 17), CITY_ZONE.getId(), WeatherRepository.Provider.OPEN_METEO, 0, days);
    }

    private static WeatherRepository.WeatherResult result(WeatherRepository.Weather weather) {
        return result(weather, NOW);
    }
    private static WeatherRepository.WeatherResult result(WeatherRepository.Weather weather, long clockMillis) {
        WeatherRepository.Decoder decoder = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long fetchedAtMillis) { return weather; }
        };
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather value) { }
        };
        Map<String, Object> settings = new HashMap<>(); settings.put("weather_enabled", true);
        settings.put("city_name", "Москва"); settings.put("city_latitude", 55.752040);
        settings.put("city_longitude", 37.617810); settings.put("units", "celsius");
        return new WeatherRepository(url -> "{}", cache, decoder, () -> clockMillis,
                millis -> fail("Controlled successful data never retries")).loadCurrent(LauncherPrefs.decode(settings), true);
    }

    private static long at(int day, int hour) {
        return Instant.ofEpochMilli(NOW).atZone(CITY_ZONE).toLocalDate().plusDays(day).atTime(hour, 0)
                .atZone(CITY_ZONE).toInstant().toEpochMilli();
    }
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static void reportHeight(String state, int height) {
        Bundle status = new Bundle();
        status.putString("stream", "\nWeatherA4Height " + state + ": " + height + " local px at width1132/textScale1\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
    private static void assertSlotTimes(View root, String... expected) {
        ViewGroup row = (ViewGroup) required(root, "forecast-hours");
        assertEquals(expected.length, row.getChildCount());
        for (int index = 0; index < expected.length; index++) assertEquals(expected[index], text(row.getChildAt(index), "forecast-hour-time"));
    }
    private static void assertSharedAxisGeometry(View root) {
        ViewGroup hours = (ViewGroup) required(root, "forecast-hours");
        View axis = required(root, "forecast-hours-axis");
        assertEquals(4, hours.getChildCount());
        assertEquals("One shared axis spans all hour cells", hours.getWidth(), axis.getWidth());
        assertTrue("The axis lies below the values", top(axis) >= top(hours) + hours.getHeight());
        assertTrue("The daily divider follows the axis", top(required(root, "forecast-daily-divider")) >= top(axis) + axis.getHeight());
        float spacing = hours.getWidth() / 4f;
        for (int index = 0; index < 4; index++) {
            View cell = hours.getChildAt(index);
            assertEquals("Hour labels share a common row", 0, cell.getTop());
            assertEquals("Axis points align with equal-width hour groups", spacing * (index + .5f),
                    (cell.getLeft() + cell.getRight()) / 2f, 1f);
        }
    }
    private static void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } });
        if (failure.get() != null) throw new AssertionError("Native weather component", failure.get());
    }
    private static View required(View root, String tag) { View found = root.findViewWithTag(tag); assertNotNull(tag, found); return found; }
    private static String text(View root, String tag) { return ((TextView) required(root, tag)).getText().toString(); }
    private static int count(View root, String prefix) {
        int value = root.getTag() instanceof String && ((String) root.getTag()).startsWith(prefix) ? 1 : 0;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) value += count(((ViewGroup) root).getChildAt(i), prefix);
        return value;
    }
    private static int top(View view) { int top = 0; while (view != null) { top += view.getTop(); view = view.getParent() instanceof View ? (View) view.getParent() : null; } return top; }
    private static boolean focusableDescendant(View view) {
        if (view.isFocusable()) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (focusableDescendant(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
    private static String visibleText(View view) {
        StringBuilder text = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = ((ViewGroup) view).getChildAt(i); if (child.getVisibility() == View.VISIBLE) text.append(' ').append(visibleText(child));
        }
        return text.toString();
    }
}
