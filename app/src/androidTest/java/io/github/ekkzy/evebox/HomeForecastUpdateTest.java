package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.MetWeatherRepository;
import io.github.ekkzy.evebox.data.WeatherRepository;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Controlled weather data only. No network provider, weather cache or personal playback source is used. */
@RunWith(AndroidJUnit4.class)
public final class HomeForecastUpdateTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final String CITY = "Controlled data · Moscow";
    private static final WeatherRepository.Cache NO_CACHE = new WeatherRepository.Cache() {
        @Override public WeatherRepository.Weather read(String key) { return null; }
        @Override public void write(String key, WeatherRepository.Weather weather) { }
    };
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private LauncherPrefs preferences;
    private Map<String, Object> original;
    private int favoriteCount;

    @Before public void seedOwned4kHomeAndSnapshotPreferences() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("Use the agreed emulator baseline", 34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals("Standard font only", 1f, context.getResources().getConfiguration().fontScale, .001f);
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : AppCatalog.load(context)) if (favorites.size() < 3) favorites.add(app.id);
        assertFalse("At least one installed launchable application is required", favorites.isEmpty());
        favoriteCount = favorites.size();
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); preferences.setBackground(0); preferences.setPaletteMode("dark");
        preferences.setLanguage("en"); preferences.setUnits("celsius");
        preferences.setCity(CITY, 55.752040, 37.617810); preferences.setWeatherEnabled(true);
        preferences.setWatchNextVisible(false); preferences.setSounds(false); preferences.setReducedMotion(false);
        preferences.setFavorites(favorites);
    }

    @After public void restoreEveryOriginalPreference() {
        if (original == null) return;
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(strings));
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit());
        assertEquals("Restore the complete debug preference snapshot", original, snapshot(storage));
    }

    @Test public void providerUpdatesKeepHomeArtworkAndShowHonestForecastStatesInBothPalettes() throws Exception {
        long now = System.currentTimeMillis();
        LocalDate first = Instant.ofEpochMilli(now).atZone(MOSCOW).toLocalDate();
        JSONObject initialBody = fixture(now, 0, false);
        WeatherRepository.WeatherResult fresh = fresh(initialBody, now);
        JSONObject windBody = fixture(now, 0, false);
        windBody.getJSONObject("current").put("wind_speed_10m", 18);
        WeatherRepository.WeatherResult windOnly = fresh(windBody, now);
        WeatherRepository.WeatherResult dailyOnly = fresh(fixture(now, 1, false), now);
        WeatherRepository.WeatherResult stale = stale(fixture(now, 2, true), now);
        WeatherRepository.WeatherResult utc = utcForecast(now);
        LocalDate utcFirst = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate();
        JSONObject currentOnlyBody = fixture(now, 0, false); currentOnlyBody.remove("daily");
        WeatherRepository.WeatherResult currentOnly = fresh(currentOnlyBody, now);
        WeatherRepository.WeatherResult invalid = fresh(new JSONObject(), now);
        assertEquals(WeatherRepository.Status.FRESH, fresh.status);
        assertEquals(WeatherRepository.Status.FRESH, dailyOnly.status);
        assertEquals(WeatherRepository.Status.STALE, stale.status);
        assertEquals(WeatherRepository.ForecastTimeBasis.CITY, fresh.weather.forecastTimeBasis);
        assertEquals(MOSCOW.getId(), fresh.weather.forecastTimezone);
        assertEquals(WeatherRepository.Status.FRESH, utc.status);
        assertFalse(utc.weather.cityTimezoneKnown); assertEquals(WeatherRepository.ForecastTimeBasis.UTC, utc.weather.forecastTimeBasis);
        assertEquals("The UTC fixture supplies real hours that must stay hidden until the city zone is known", 3,
                utc.weather.upcomingHours(now).size());
        assertEquals(WeatherRepository.Status.ERROR, invalid.status); assertNull(invalid.weather);
        assertEquals("The causal update changes only dated forecast data", fresh.weather.fetchedAtMillis, dailyOnly.weather.fetchedAtMillis);
        assertEquals(fresh.weather.observedAtMillis, dailyOnly.weather.observedAtMillis);
        assertEquals(fresh.weather.temperature, dailyOnly.weather.temperature, 0);
        assertEquals("A wind-only update retains fetch time", fresh.weather.fetchedAtMillis, windOnly.weather.fetchedAtMillis);
        assertEquals(fresh.weather.observedAtMillis, windOnly.weather.observedAtMillis);

        AtomicInteger controlledRequests = new AtomicInteger();
        AtomicBoolean injected = new AtomicBoolean();
        WeatherRepository controlled = new WeatherRepository(url -> {
            controlledRequests.incrementAndGet();
            assertTrue("Only the explicit controlled city is requested", url.contains("latitude=55.752040&longitude=37.617810"));
            assertTrue(url.contains("forecast_days=3"));
            return initialBody.toString();
        }, NO_CACHE, new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("A valid fixture never retries"));
        ActivityLifecycleCallback injection = (Activity owner, Stage stage) -> {
            if (owner instanceof MainActivity && stage == Stage.CREATED) {
                set(owner, "weatherRepository", controlled); injected.set(true);
            }
        };
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(injection);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK))) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            assertTrue("The fake repository is installed before the first onResume", injected.get());
            await(activity, "Initial catalog, local artwork and three controlled days settle", current -> ready(current)
                    && countTagPrefix(forecast(current), "forecast-day-") == 3);
            assertEquals("Only one controlled initial provider request is needed", 1, controlledRequests.get());

            for (String palette : new String[]{"dark", "light"}) {
                scene.onActivity(current -> {
                    // Warm the new palette's real icons at the top before taking a retention baseline.
                    // A lazy offscreen icon is not evidence of an image update regression.
                    assertTrue(tag(current, "favorite-0").requestFocus());
                    ((TvScrollView) get(current, "homeScroll")).scrollTo(0, 0);
                    preferences.setPaletteMode(palette);
                    set(current, "prefs", preferences.read()); set(current, "weather", fresh);
                    set(current, "nextWeatherRequestAt", Long.MAX_VALUE);
                    invoke(current, "renderPreservingFocus");
                });
                await(activity, "Explicit " + palette + " palette and artwork settle", current -> ready(current)
                        && (Boolean) get(get(current, "palette"), "light") == palette.equals("light")
                        && text(day(current, first), "forecast-temperature").contains("+1°C"));
                captureTop(activity, "home-top-controlled-" + palette);
                AtomicReference<Surface> baseline = new AtomicReference<>();
                scene.onActivity(current -> {
                    assertWindow(current); baseline.set(new Surface(current, favoriteCount));
                    assertTrue(tag(current, "shortcut-launcher").requestFocus());
                });
                await(activity, "SL settings remains focused on the second page", HomeForecastUpdateTest::lowerPageFocusedAndSettled);
                scene.onActivity(current -> {
                    baseline.get().assertRetained(current); assertForecastValues(current, first, 0);
                    int headerColor = ((TextView) required(forecast(current), "forecast-title")).getCurrentTextColor();
                    assertEquals(headerColor, ((TextView) required(day(current, first), "forecast-date")).getCurrentTextColor());
                    assertEquals((Integer) get(get(current, "palette"), "text"), Integer.valueOf(headerColor));
                    assertTrue("Text follows the selected glass palette", palette.equals("light")
                            ? Color.luminance(headerColor) < .2f : Color.luminance(headerColor) > .75f);
                    assertHourlyTimeline(current, fresh.weather);
                    assertNull("No long visible footer", forecast(current).findViewWithTag("forecast-footer"));
                    assertForecastCardInsideWindow(current);
                });
                capture(activity, "home-forecast-controlled-" + palette);
                if (palette.equals("dark")) {
                    // Controlled causal probe: refresh the cached native outline without changing geometry or data.
                    scene.onActivity(current -> {
                        View card = tag(current, "home-forecast-card"); card.invalidateOutline(); card.invalidate();
                    });
                    capture(activity, "home-forecast-controlled-dark-outline-refreshed");
                }

                publish(scene, windOnly);
                await(activity, "A current-wind-only update repaints the compact chip", current -> settled(current)
                        && "5 m/s".equals(text(tag(current, "compact-weather-wind"), "compact-weather-chip-value")));
                scene.onActivity(current -> baseline.get().assertRetained(current));

                publish(scene, dailyOnly);
                await(activity, "Daily-only updates repaint without replacing current weather or Home", current -> settled(current)
                        && text(day(current, first), "forecast-temperature").contains("+4°C"));
                scene.onActivity(current -> { baseline.get().assertRetained(current); assertForecastValues(current, first, 1); });

                publish(scene, stale);
                await(activity, "Saved forecast values and the compact stale timestamp appear", current -> settled(current)
                        && text(forecast(current), "forecast-updated").startsWith("Saved ")
                        && text(day(current, first), "forecast-temperature").contains("+7°C"));
                scene.onActivity(current -> {
                    baseline.get().assertRetained(current);
                    assertTrue(text((View) get(current, "root"), "compact-weather-condition").startsWith("Saved · "));
                    View missing = day(current, first.plusDays(1));
                    assertTrue(text(missing, "forecast-temperature").contains("low —"));
                    assertTrue(text(missing, "forecast-temperature").contains("high +19°C"));
                    assertFalse("Unknown low temperature is not a fabricated zero", text(missing, "forecast-temperature").contains("0°C"));
                    assertEquals("Condition unavailable", text(missing, "forecast-condition"));
                    for (String key : new String[]{"forecast-precipitation", "forecast-wind", "forecast-sun", "forecast-uv"})
                        assertNull("Absent source fields have no fabricated row: " + key, missing.findViewWithTag(key));
                    assertEquals(3, countTagPrefix(forecast(current), "forecast-day-"));
                    assertHourlyTimeline(current, stale.weather);
                });

                publish(scene, utc);
                await(activity, "Unknown city zone keeps UTC explicit in accessibility with real MET periods", current -> settled(current)
                        && forecast(current).getContentDescription().toString().contains("The city time zone is not known yet."));
                scene.onActivity(current -> {
                    baseline.get().assertRetained(current);
                    assertEquals("An unknown city zone cannot label UTC samples as local upcoming hours", 0,
                            countTagPrefix(forecast(current), "forecast-hour-cell-"));
                    assertNull(forecast(current).findViewWithTag("forecast-hours"));
                    assertNull(forecast(current).findViewWithTag("forecast-hours-axis"));
                    assertEquals("Hourly forecast is not available yet", text(forecast(current), "forecast-hours-message"));
                    String updated = text(forecast(current), "forecast-updated");
                    assertTrue("The unknown-zone header uses age instead of an unqualified clock: " + updated,
                            updated.matches("(?:Saved )?(?:just now|[0-9]+[mhd] ago)"));
                    assertEquals(3, countTagPrefix(forecast(current), "forecast-day-"));
                    for (int offset = 0; offset < 3; offset++) {
                        View column = day(current, utcFirst.plusDays(offset));
                        String date = text(column, "forecast-date");
                        assertFalse("The compact visible date omits the timezone suffix", date.contains("UTC"));
                        assertFalse("Device/city today is not substituted for UTC", date.contains("Today") || date.contains("Tomorrow"));
                        assertNotNull(required(column, "forecast-temperature"));
                        assertNotNull(required(column, "forecast-precipitation"));
                        assertNotNull(required(column, "forecast-wind"));
                    }
                    assertTrue(forecast(current).getContentDescription().toString().contains("The city time zone is not known yet."));
                    assertNull(forecast(current).findViewWithTag("forecast-footer"));
                    assertForecastCardInsideWindow(current);
                });
                capture(activity, "home-forecast-controlled-utc-" + palette);

                publish(scene, currentOnly);
                await(activity, "Current-only weather reports daily data unavailable", current -> settled(current)
                        && "Daily forecast is not available yet.".equals(optionalText(forecast(current), "forecast-message")));
                scene.onActivity(current -> {
                    baseline.get().assertRetained(current); assertNoDailyForecastNumbers(current);
                    assertEquals("Current-only daily response still retains its four two-hour points", 4,
                            countTagPrefix(forecast(current), "forecast-hour-cell-"));
                });

                publish(scene, invalid);
                await(activity, "A provider error replaces the forecast with its honest empty state", current -> settled(current)
                        && "The weather service could not provide a forecast.".equals(optionalText(forecast(current), "forecast-message")));
                scene.onActivity(current -> { baseline.get().assertRetained(current); assertNoDailyForecastNumbers(current); assertWindow(current); });
            }
            assertEquals("Controlled model updates never access another provider", 1, controlledRequests.get());
            Bundle report = new Bundle();
            report.putString("stream", "\nHomeForecastUpdateTest: controlled data; API34; actual UI 3840x2160; font1; dark/light; provider calls=1; complete prefs snapshot/restore enabled.\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
        } finally { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(injection); }
    }

    private static JSONObject fixture(long now, int variation, boolean missingMiddle) throws JSONException {
        LocalDate first = Instant.ofEpochMilli(now).atZone(MOSCOW).toLocalDate();
        JSONObject daily = new JSONObject();
        for (String key : new String[]{"time", "weather_code", "temperature_2m_min", "temperature_2m_max", "precipitation_sum",
                "precipitation_probability_max", "wind_speed_10m_max", "wind_gusts_10m_max", "uv_index_max", "sunrise", "sunset"})
            daily.put(key, new JSONArray());
        for (int index = 0; index < 3; index++) {
            long start = first.plusDays(index).atStartOfDay(MOSCOW).toEpochSecond();
            boolean missing = missingMiddle && index == 1;
            daily.getJSONArray("time").put(start);
            daily.getJSONArray("weather_code").put(missing ? JSONObject.NULL : index == 1 ? 61 : 3);
            daily.getJSONArray("temperature_2m_min").put(missing ? JSONObject.NULL : 1 + index * 2 + variation * 3);
            daily.getJSONArray("temperature_2m_max").put(11 + index * 2 + variation * 3);
            daily.getJSONArray("precipitation_sum").put(missing ? JSONObject.NULL : .5 + index + variation);
            daily.getJSONArray("precipitation_probability_max").put(missing ? JSONObject.NULL : 20 + index * 15 + variation * 5);
            daily.getJSONArray("wind_speed_10m_max").put(missing ? JSONObject.NULL : 12 + index * 3 + variation * 4);
            daily.getJSONArray("wind_gusts_10m_max").put(missing ? JSONObject.NULL : 22 + index * 3 + variation * 4);
            daily.getJSONArray("uv_index_max").put(missing ? JSONObject.NULL : 1.5 + index);
            daily.getJSONArray("sunrise").put(missing ? JSONObject.NULL : start + 7 * 3600);
            daily.getJSONArray("sunset").put(missing ? JSONObject.NULL : start + 17 * 3600);
        }
        JSONObject hourly = new JSONObject();
        for (String key : new String[]{"time", "temperature_2m", "weather_code", "is_day"}) hourly.put(key, new JSONArray());
        long firstHour = now / 3_600_000L * 3_600_000L;
        for (int index = 0; index < 30; index++) {
            if (missingMiddle && index == 5) continue;
            long timestamp = firstHour + index * 3_600_000L;
            int localHour = Instant.ofEpochMilli(timestamp).atZone(MOSCOW).getHour();
            hourly.getJSONArray("time").put(timestamp / 1000);
            hourly.getJSONArray("temperature_2m").put(8 + index % 4);
            hourly.getJSONArray("weather_code").put(3);
            hourly.getJSONArray("is_day").put(localHour >= 7 && localHour < 17 ? 1 : 0);
        }
        return new JSONObject().put("timezone", MOSCOW.getId()).put("utc_offset_seconds", 10800)
                .put("current", new JSONObject().put("time", now / 1000).put("temperature_2m", 8)
                        .put("weather_code", 3).put("is_day", 1).put("wind_speed_10m", 8.4))
                .put("hourly", hourly).put("daily", daily).put("daily_units", new JSONObject().put("temperature_2m_min", "°C")
                        .put("temperature_2m_max", "°C").put("precipitation_sum", "mm").put("precipitation_probability_max", "%")
                        .put("wind_speed_10m_max", "km/h").put("wind_gusts_10m_max", "km/h").put("uv_index_max", ""));
    }
    private WeatherRepository.WeatherResult fresh(JSONObject body, long now) {
        WeatherRepository repository = new WeatherRepository(url -> body.toString(), NO_CACHE,
                new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("No retry for a JSON fixture"));
        return repository.loadCurrent(preferences.read(), true);
    }
    private WeatherRepository.WeatherResult stale(JSONObject body, long now) throws JSONException {
        WeatherRepository.Weather saved = new WeatherRepository.JsonDecoder().current(body.toString(), "celsius", now);
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return saved; }
            @Override public void write(String key, WeatherRepository.Weather weather) { fail("An offline fixture cannot overwrite its cache"); }
        };
        return new WeatherRepository(url -> { throw new IOException("controlled offline fixture"); }, cache,
                new WeatherRepository.JsonDecoder(), () -> now, millis -> { }).loadCurrent(preferences.read(), true);
    }
    private WeatherRepository.WeatherResult utcForecast(long now) throws JSONException {
        long first = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        JSONArray rows = new JSONArray();
        for (int index = 0; index <= 72; index++) rows.put(new JSONObject()
                .put("time", Instant.ofEpochMilli(first + index * 3_600_000L).toString())
                .put("data", new JSONObject().put("instant", new JSONObject().put("details", new JSONObject()
                        .put("air_temperature", 5 + index % 8).put("wind_speed", 3)))
                        .put("next_1_hours", new JSONObject().put("summary", new JSONObject().put("symbol_code", "cloudy"))
                                .put("details", new JSONObject().put("precipitation_amount", .2)))));
        String body = new JSONObject().put("properties", new JSONObject().put("meta", new JSONObject().put("units",
                new JSONObject().put("air_temperature", "celsius").put("wind_speed", "m/s").put("precipitation_amount", "mm")))
                .put("timeseries", rows)).toString();
        MetWeatherRepository.Store noRawCache = new MetWeatherRepository.Store() {
            @Override public MetWeatherRepository.Snapshot read(String key) { return null; }
            @Override public void write(String key, MetWeatherRepository.Snapshot value) { }
        };
        MetWeatherRepository met = new MetWeatherRepository((url, validator, timeout) ->
                new MetWeatherRepository.Response(200, body, "", now + 3_600_000L), noRawCache, () -> now);
        return new WeatherRepository(url -> { throw new IOException("controlled primary outage"); }, NO_CACHE,
                new WeatherRepository.JsonDecoder(), () -> now, millis -> fail("One primary attempt before MET"), met)
                .loadCurrent(preferences.read(), true);
    }
    private static void publish(ActivityScenario<MainActivity> scene, WeatherRepository.WeatherResult value) {
        scene.onActivity(current -> {
            assertTrue("Provider updates are delivered to the resumed Home", (Boolean) get(current, "resumed"));
            assertTrue("No initial catalog callback may overwrite the controlled update", ((Future<?>) get(current, "loadFuture")).isDone());
            set(current, "weather", value); set(current, "nextWeatherRequestAt", Long.MAX_VALUE);
            invoke(current, "scheduleDataRender");
            assertTrue("Exercise the real asynchronous data-render path", (Boolean) get(current, "renderDirty"));
        });
    }
    private static void assertForecastValues(MainActivity activity, LocalDate first, int variation) {
        assertEquals(3, countTagPrefix(forecast(activity), "forecast-day-"));
        for (int index = 0; index < 3; index++) {
            View column = day(activity, first.plusDays(index));
            String range = text(column, "forecast-temperature");
            assertTrue(range, range.contains("low +" + (1 + index * 2 + variation * 3) + "°C"));
            assertTrue(range, range.contains("high +" + (11 + index * 2 + variation * 3) + "°C"));
            assertEquals("Rain / snow " + (.5 + index + variation) + " mm · up to " + (20 + index * 15 + variation * 5) + "%",
                    text(column, "forecast-precipitation"));
            String[][] winds = {{"3.3 · gusts 6.1", "4.2 · gusts 6.9", "5 · gusts 7.8"},
                    {"4.4 · gusts 7.2", "5.3 · gusts 8.1", "6.1 · gusts 8.9"}};
            assertEquals("Wind up to " + winds[variation][index] + " m/s",
                    text(column, "forecast-wind"));
        }
    }
    private static void assertHourlyTimeline(MainActivity activity, WeatherRepository.Weather weather) {
        View content = forecast(activity);
        ViewGroup hours = (ViewGroup) required(content, "forecast-hours");
        View axis = required(content, "forecast-hours-axis");
        List<WeatherRepository.ForecastSlot> slots = weather.upcomingHourSlots(System.currentTimeMillis());
        assertEquals(4, slots.size()); assertEquals(4, hours.getChildCount());
        assertEquals(4, countTagPrefix(content, "forecast-hour-cell-"));
        assertEquals("All four groups share one axis", hours.getWidth(), axis.getWidth());
        assertTrue("The forecast content fits the lower page in local design pixels", content.getHeight() <= 345);
        float spacing = hours.getWidth() / 4f;
        RectF hourBounds = new RectF(0, 0, hours.getWidth(), hours.getHeight());
        Matrix hourMatrix = new Matrix(); hours.transformMatrixToGlobal(hourMatrix); hourMatrix.mapRect(hourBounds);
        RectF axisBounds = new RectF(0, 0, axis.getWidth(), axis.getHeight());
        Matrix axisMatrix = new Matrix(); axis.transformMatrixToGlobal(axisMatrix); axisMatrix.mapRect(axisBounds);
        assertTrue("Axis follows the common values row", axisBounds.top >= hourBounds.bottom);
        for (int index = 0; index < 4; index++) {
            WeatherRepository.ForecastSlot slot = slots.get(index);
            View cell = required(content, "forecast-hour-cell-" + slot.timeMillis);
            assertSame("Temporal order is unchanged by rebind", cell, hours.getChildAt(index));
            assertEquals(0, cell.getTop());
            assertEquals("Four equally spaced timeline points", spacing * (index + .5f),
                    (cell.getLeft() + cell.getRight()) / 2f, 1f);
            assertEquals(slots.get(0).timeMillis + index * 2 * 3_600_000L, slot.timeMillis);
            String time = text(cell, "forecast-hour-time");
            assertEquals(Instant.ofEpochMilli(slot.timeMillis).atZone(MOSCOW).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)), time);
            assertFalse("City hours never display unqualified UTC", time.contains("UTC"));
            String value = text(cell, "forecast-hour-temperature");
            if (slot.sample == null) {
                assertEquals("—", value); assertNull(cell.findViewWithTag("forecast-hour-icon"));
                assertTrue(content.getContentDescription().toString().contains("No data"));
            } else {
                long rounded = Math.round(slot.sample.temp);
                assertEquals((rounded > 0 ? "+" : "") + rounded + "°C", value);
            }
        }
        View firstDay = required(content, "forecast-day-" + weather.forecastDays(System.currentTimeMillis()).get(0).date);
        RectF dayBounds = new RectF(0, 0, firstDay.getWidth(), firstDay.getHeight());
        Matrix dayMatrix = new Matrix(); firstDay.transformMatrixToGlobal(dayMatrix); dayMatrix.mapRect(dayBounds);
        assertTrue("The three days remain below the shared time axis", dayBounds.top >= axisBounds.bottom);
    }
    private static void assertNoDailyForecastNumbers(MainActivity activity) {
        View forecast = forecast(activity);
        assertEquals(0, countTagPrefix(forecast, "forecast-day-"));
        for (String key : new String[]{"forecast-temperature", "forecast-precipitation", "forecast-wind", "forecast-sun", "forecast-uv"})
            assertNull("An unavailable forecast has no numeric placeholder: " + key, forecast.findViewWithTag(key));
        assertNotNull(required(forecast, "forecast-message"));
        assertTrue("The existing SL settings action keeps focus through errors", tag(activity, "shortcut-launcher").isFocused());
    }
    private final class Surface {
        final View root, card, forecast;
        final int generation;
        final List<View> favorites = new ArrayList<>();
        final List<ImageView> icons = new ArrayList<>();
        final List<Drawable> drawables = new ArrayList<>();
        Surface(MainActivity activity, int count) {
            root = (View) get(activity, "root"); card = tag(activity, "home-forecast-card"); forecast = forecast(activity);
            generation = (Integer) get(activity, "renderGeneration");
            for (int index = 0; index < count; index++) {
                View favorite = tag(activity, "favorite-" + index); ImageView image = icon(favorite);
                favorites.add(favorite); icons.add(image); drawables.add(image.getDrawable());
            }
        }
        void assertRetained(MainActivity activity) {
            assertSame("Provider data must keep the existing Home root", root, get(activity, "root"));
            assertEquals("No full render for a provider-only update", generation, get(activity, "renderGeneration"));
            assertSame("The read-only forecast surface is reused", card, tag(activity, "home-forecast-card"));
            assertSame("Only forecast contents are rebound", forecast, forecast(activity));
            for (int index = 0; index < favorites.size(); index++) {
                View favorite = tag(activity, "favorite-" + index);
                assertSame("Favorite tile " + index, favorites.get(index), favorite);
                assertSame("Favorite ImageView " + index, icons.get(index), icon(favorite));
                assertSame("The ready favorite drawable is not decoded/rebound " + index, drawables.get(index), icon(favorite).getDrawable());
            }
        }
    }
    private boolean ready(MainActivity activity) {
        if (!settled(activity) || !(Boolean) get(activity, "catalogLoaded")) return false;
        Future<?> load = (Future<?>) get(activity, "loadFuture");
        if (load == null || !load.isDone() || get(activity, "weather") == null) return false;
        for (int index = 0; index < favoriteCount; index++) {
            View favorite = ((View) get(activity, "root")).findViewWithTag("favorite-" + index);
            if (favorite == null) return false;
            Drawable drawable = icon(favorite).getDrawable();
            if (!(drawable instanceof BitmapDrawable) || ((BitmapDrawable) drawable).getBitmap().isRecycled()) return false;
        }
        return ((Map<?, ?>) get(get(activity, "imageLoader"), "jobs")).isEmpty();
    }
    private static boolean settled(MainActivity activity) {
        View root = (View) get(activity, "root");
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus() && "HOME".equals(activity.currentScreenForTest())
                && root != null && root.isLaidOut() && !(Boolean) get(activity, "renderDirty")
                && !((UiMotion) get(activity, "uiMotion")).isRunning()
                && get(activity, "homeScroll") != null && !((TvScrollView) get(activity, "homeScroll")).isScrollAnimating();
    }
    private static boolean lowerPageFocusedAndSettled(MainActivity activity) {
        return settled(activity) && tag(activity, "shortcut-launcher").isFocused();
    }
    private static boolean upperPageFocusedAndSettled(MainActivity activity) {
        return settled(activity) && tag(activity, "favorite-0").isFocused()
                && ((TvScrollView) get(activity, "homeScroll")).getScrollY() == 0;
    }
    private static void assertWindow(MainActivity activity) {
        View decor = activity.getWindow().getDecorView();
        assertEquals("Actual app window width", 3840, decor.getWidth()); assertEquals("Actual app window height", 2160, decor.getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static void assertForecastCardInsideWindow(MainActivity activity) {
        View focus = tag(activity, "home-forecast-card"); Matrix matrix = new Matrix(); focus.transformMatrixToGlobal(matrix);
        RectF bounds = new RectF(0, 0, focus.getWidth(), focus.getHeight()); matrix.mapRect(bounds);
        View decor = activity.getWindow().getDecorView();
        assertTrue("The read-only forecast must fit in the actual screenshot: " + bounds,
                bounds.left >= -2 && bounds.top >= -2 && bounds.right <= decor.getWidth() + 2 && bounds.bottom <= decor.getHeight() + 2);
    }
    private void capture(MainActivity activity, String filename) throws Exception {
        awaitTwoPreDraws(activity);
        AtomicReference<String> geometry = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("Screenshot follows two actual settled pre-draws", lowerPageFocusedAndSettled(activity));
            assertWindow(activity); assertForecastCardInsideWindow(activity);
            try { geometry.set(forecastGeometry(activity, filename).toString()); }
            catch (JSONException failure) { throw new AssertionError("Forecast geometry serialization", failure); }
        });
        assertTrue("Bound diagnostic output independently of the view tree", geometry.get().length() <= 16_384);
        String run = InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun", "home-forecast-controlled");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue("Save the controlled forecast visual evidence", device.takeScreenshot(new File(folder, filename + ".png")));
        try (FileOutputStream output = new FileOutputStream(new File(folder, filename + ".geometry.json"))) {
            output.write(geometry.get().getBytes(StandardCharsets.UTF_8));
        }
        Bundle status = new Bundle();
        status.putString("stream", "\nHomeForecastGeometry " + geometry.get() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
    private void captureTop(MainActivity activity, String filename) throws Exception {
        awaitTwoPreDraws(activity, HomeForecastUpdateTest::upperPageFocusedAndSettled);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("Capture the settled upper page before entering SL settings", upperPageFocusedAndSettled(activity));
            assertWindow(activity);
        });
        String run = InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun", "home-forecast-controlled");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue("Save the actual 4K upper Home with controlled weather", device.takeScreenshot(new File(folder, filename + ".png")));
    }
    private static void awaitTwoPreDraws(MainActivity activity) throws InterruptedException {
        awaitTwoPreDraws(activity, HomeForecastUpdateTest::lowerPageFocusedAndSettled);
    }
    private static void awaitTwoPreDraws(MainActivity activity, Predicate<MainActivity> settledPage) throws InterruptedException {
        CountDownLatch complete = new CountDownLatch(1); AtomicInteger frames = new AtomicInteger();
        AtomicReference<ViewTreeObserver.OnPreDrawListener> listener = new AtomicReference<>();
        View decor = activity.getWindow().getDecorView();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("Begin the draw barrier after the existing settled wait", settledPage.test(activity));
            listener.set(() -> {
                if (frames.incrementAndGet() >= 2) {
                    decor.getViewTreeObserver().removeOnPreDrawListener(listener.get()); complete.countDown();
                } else decor.postInvalidateOnAnimation();
                return true;
            });
            decor.getViewTreeObserver().addOnPreDrawListener(listener.get()); decor.postInvalidateOnAnimation();
        });
        try { assertTrue("Two actual pre-draw callbacks must arrive", complete.await(4, TimeUnit.SECONDS)); }
        finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(listener.get());
            });
        }
    }
    private static JSONObject forecastGeometry(MainActivity activity, String filename) throws JSONException {
        View card = tag(activity, "home-forecast-card"), content = forecast(activity);
        JSONObject result = new JSONObject().put("controlled_data", true).put("screenshot", filename)
                .put("after_pre_draws", 2).put("uptime_ms", SystemClock.uptimeMillis())
                .put("palette_light", get(get(activity, "palette"), "light"))
                .put("forecastCard", geometry(card)).put("forecastContent", geometry(content))
                .put("forecastTitle", geometry(required(content, "forecast-title")))
                .put("scene", geometry((View) get(activity, "glassScene")))
                .put("homeScroll", geometry((View) get(activity, "homeScroll")))
                .put("board", geometry((View) get(activity, "board")))
                .put("root", geometry((View) get(activity, "root")));
        Drawable background = card.getBackground();
        if (background != null) result.put("background", new JSONObject().put("class", background.getClass().getSimpleName())
                .put("bounds", rect(new RectF(background.getBounds()))).put("mapped_global_bounds", mapped(card, new RectF(background.getBounds())))
                .put("alpha", background.getAlpha()));
        Outline outline = new Outline();
        if (card.getOutlineProvider() != null) card.getOutlineProvider().getOutline(card, outline);
        Rect outlineRect = new Rect(); boolean hasRect = outline.getRect(outlineRect);
        result.put("outline", new JSONObject().put("source", "provider query; cached RenderNode outline is not exposed")
                .put("rect", hasRect ? rect(new RectF(outlineRect)) : JSONObject.NULL)
                .put("mapped_global_rect", hasRect ? mapped(card, new RectF(outlineRect)) : JSONObject.NULL)
                .put("radius", outline.getRadius()).put("canClip", outline.canClip()).put("alpha", outline.getAlpha()));
        result.put("forecast_sizing", new JSONObject().put("card_height", card.getHeight()).put("content_height", content.getHeight())
                .put("card_minus_content_height", card.getHeight() - content.getHeight())
                .put("day_columns", countTagPrefix(content, "forecast-day-"))
                .put("hour_points", countTagPrefix(content, "forecast-hour-cell-")));
        View hours = content.findViewWithTag("forecast-hours"), axis = content.findViewWithTag("forecast-hours-axis");
        if (hours instanceof ViewGroup && axis != null) {
            JSONArray points = new JSONArray();
            for (int index = 0; index < ((ViewGroup) hours).getChildCount(); index++) {
                View cell = ((ViewGroup) hours).getChildAt(index);
                points.put(new JSONObject().put("tag", cell.getTag()).put("time", text(cell, "forecast-hour-time"))
                        .put("value", text(cell, "forecast-hour-temperature"))
                        .put("mapped_global_bounds", mapped(cell, new RectF(0, 0, cell.getWidth(), cell.getHeight()))));
            }
            result.put("hourly_timeline", new JSONObject().put("interval_hours", 2).put("points", points)
                    .put("axis_global_bounds", mapped(axis, new RectF(0, 0, axis.getWidth(), axis.getHeight()))));
        }
        JSONArray titles = new JSONArray(); weatherTitles((View) get(activity, "root"), titles);
        return result.put("weather_titles", titles);
    }
    private static JSONObject geometry(View view) throws JSONException {
        Rect visible = new Rect(); boolean hasVisibleRect = view.getGlobalVisibleRect(visible);
        JSONObject result = new JSONObject().put("class", view.getClass().getSimpleName())
                .put("local_bounds", rect(new RectF(0, 0, view.getWidth(), view.getHeight())))
                .put("parent_bounds", rect(new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom())))
                .put("mapped_global_bounds", mapped(view, new RectF(0, 0, view.getWidth(), view.getHeight())))
                .put("visible_global_rect", hasVisibleRect ? rect(new RectF(visible)) : JSONObject.NULL)
                .put("measured_width", view.getMeasuredWidth()).put("measured_height", view.getMeasuredHeight())
                .put("layout_width", view.getLayoutParams().width).put("layout_height", view.getLayoutParams().height)
                .put("scrollY", view.getScrollY()).put("scaleX", view.getScaleX()).put("scaleY", view.getScaleY())
                .put("pivotX", view.getPivotX()).put("pivotY", view.getPivotY())
                .put("translationX", view.getTranslationX()).put("translationY", view.getTranslationY())
                .put("alpha", view.getAlpha()).put("clipToOutline", view.getClipToOutline())
                .put("layout_requested", view.isLayoutRequested()).put("in_layout", view.isInLayout());
        if (view instanceof ViewGroup) result.put("clipChildren", ((ViewGroup) view).getClipChildren())
                .put("clipToPadding", ((ViewGroup) view).getClipToPadding());
        return result;
    }
    private static JSONArray mapped(View view, RectF bounds) throws JSONException {
        Matrix matrix = new Matrix(); view.transformMatrixToGlobal(matrix); matrix.mapRect(bounds); return rect(bounds);
    }
    private static JSONArray rect(RectF bounds) throws JSONException {
        return new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom);
    }
    private static void weatherTitles(View view, JSONArray result) throws JSONException {
        if (result.length() >= 8) return;
        if (view instanceof TextView) {
            String value = ((TextView) view).getText().toString(); String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("weather") || lower.contains("погод")) result.put(new JSONObject()
                    .put("text", value.substring(0, Math.min(96, value.length()))).put("geometry", geometry(view)));
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount() && result.length() < 8; index++)
            weatherTitles(((ViewGroup) view).getChildAt(index), result);
    }
    private static ImageView icon(View favorite) {
        assertTrue(favorite instanceof ViewGroup); ViewGroup group = (ViewGroup) favorite;
        for (int index = 0; index < group.getChildCount(); index++) if (group.getChildAt(index) instanceof ImageView) return (ImageView) group.getChildAt(index);
        throw new AssertionError("Favorite has no artwork ImageView");
    }
    private static View forecast(MainActivity activity) { return required((View) get(activity, "root"), "three-day-forecast"); }
    private static View day(MainActivity activity, LocalDate date) { return required(forecast(activity), "forecast-day-" + date); }
    private static View tag(MainActivity activity, String key) { return required((View) get(activity, "root"), key); }
    private static View required(View root, String key) { View value = root.findViewWithTag(key); assertNotNull("Missing UI tag " + key, value); return value; }
    private static String text(View root, String key) { return ((TextView) required(root, key)).getText().toString(); }
    private static String optionalText(View root, String key) { View value = root.findViewWithTag(key); return value instanceof TextView ? ((TextView) value).getText().toString() : ""; }
    private static int countTagPrefix(View root, String prefix) {
        int count = root.getTag() instanceof String && ((String) root.getTag()).startsWith(prefix) ? 1 : 0;
        if (root instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) root).getChildCount(); index++) count += countTagPrefix(((ViewGroup) root).getChildAt(index), prefix);
        return count;
    }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void set(Object owner, String name, Object value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity owner, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void await(MainActivity activity, String reason, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 12_000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(reason);
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }
}
