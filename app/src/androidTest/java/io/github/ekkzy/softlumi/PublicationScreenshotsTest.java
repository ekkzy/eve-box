package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.ChannelRepository;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.RecommendationsSession;
import io.github.ekkzy.softlumi.data.WallpaperCatalog;
import io.github.ekkzy.softlumi.data.WatchNextRepository;
import io.github.ekkzy.softlumi.data.WeatherRepository;
import io.github.ekkzy.softlumi.ui.AppArtworkCatalog;
import io.github.ekkzy.softlumi.ui.SharedRowsSheet;
import io.github.ekkzy.softlumi.ui.TvHorizontalScrollView;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiImageLoader;
import io.github.ekkzy.softlumi.ui.UiMotion;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Publication-only demo on the owned 4K emulator: no account, provider history or live weather.
 * README captions must identify the cards, service selection and Moscow forecast as demo data.
 * Screenshots are native renderer output; no composite, resize or artwork replacement after capture.
 */
@RunWith(AndroidJUnit4.class)
public final class PublicationScreenshotsTest {
    private static final ZoneId CITY_ZONE = ZoneId.of("Europe/Moscow");
    private static final String KINO = "ru.kinopoisk.tv", VK = "com.vk.tv";
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private LauncherPrefs preferences;
    private final List<AppEntry> apps = new ArrayList<>();

    @Before public void prepareIsolatedDemo() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never alter release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        assertEquals("Publication describes candidate.12", 33, BuildConfig.VERSION_CODE);
        assertTrue("Owned emulator only", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertEquals(34, Build.VERSION.SDK_INT);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setLanguage("ru"); preferences.setSounds(false); preferences.setReducedMotion(true);
        // App language does not change Locale.US on the emulator: auto would request Fahrenheit.
        preferences.setUnits("celsius");
        // All provider/network consent stays off until the real workers have been closed.
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false);
        preferences.setRecommendationsEnabled(false); preferences.setFavorites(Collections.emptyList());
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        preferences.setPaletteMode("dark"); preferences.setWallpaperPeriod("night");
        preferences.setBackground(WallpaperCatalog.CALM_GRAPHITE_MIST); preferences.setWallpaperDim(15);
        String[] packages = {KINO, VK, "ru.rutube.app", "com.netflix.ninja", "com.plexapp.android",
                "com.spotify.tv.android", "org.xbmc.kodi", "org.videolan.vlc"};
        for (String pkg : packages) {
            AppArtworkCatalog.Entry mark = AppArtworkCatalog.find(pkg);
            assertNotNull(pkg, mark); assertTrue("Only already bundled logos: " + pkg, mark.hasCuratedArtwork());
            apps.add(new AppEntry(new ComponentName(pkg, pkg + ".DemoActivity"), mark.title, "publication-demo"));
        }
    }

    @After public void restoreExactPreferences() {
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
        assertTrue(editor.commit()); assertEquals(original, snapshot(storage));
    }

    @Test public void captureDemonstrationPages() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene);
            await(activity, "Initial disabled repositories settle", PublicationScreenshotsTest::stable);
            scene.onActivity(current -> {
                stopRealWorkers(current);
                List<String> ids = new ArrayList<>(); for (AppEntry app : apps) ids.add(app.id);
                preferences.setFavorites(ids);
                preferences.setCity("Москва", 55.752040, 37.617810, "524901", CITY_ZONE.getId());
                preferences.setWeatherEnabled(true); preferences.setWatchNextVisible(true);
                preferences.setRecommendations(true, Arrays.asList(KINO, VK), Arrays.asList(KINO, VK));
                set(current, "catalog", Collections.unmodifiableList(new ArrayList<>(apps)));
                @SuppressWarnings("unchecked") Map<String, AppEntry> byId = (Map<String, AppEntry>) get(current, "appsById");
                byId.clear(); for (AppEntry app : apps) byId.put(app.id, app);
                set(current, "prefs", preferences.read());
                set(current, "continuation", Collections.singletonList(new WatchNextRepository.Entry("demo-continue",
                        "Горные маршруты", "Демонстрационная карточка, не история просмотра", KINO,
                        new Intent(Intent.ACTION_VIEW).setPackage(KINO).toUri(Intent.URI_INTENT_SCHEME), image(R.drawable.background_alpine_day),
                        18, 60, System.currentTimeMillis(), true, true, WatchNextRepository.Kind.SHOW)));
                set(current, "watchLoaded", true); set(current, "watchStatus", WatchNextRepository.Status.ITEMS);
                set(current, "weather", demoWeather()); set(current, "nextWeatherRequestAt", Long.MAX_VALUE);
                set(current, "recommendations", demoRecommendations()); set(current, "homeSource", KINO);
                set(current, "requestedFocus", "source:" + KINO);
                invoke(current, "render");
            });
            await(activity, "Landscape demo artwork and focus settle", PublicationScreenshotsTest::imagesReady);
            capture(activity, "home-landscape");

            preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER);
            preferences.setBackground(WallpaperCatalog.CALM_PEARL_MIST);
            preferences.setWallpaperPeriod("day"); preferences.setPaletteMode("light");
            scene.onActivity(current -> { set(current, "requestedFocus", "source:" + KINO); invoke(current, "render"); });
            await(activity, "Poster demo artwork and focus settle", PublicationScreenshotsTest::imagesReady);
            capture(activity, "home-poster");

            scene.onActivity(current -> {
                assertTrue(target(current, "shortcut-launcher").requestFocus());
                ((TvScrollView) get(current, "homeScroll")).smoothScrollToPosition(720);
            });
            await(activity, "Real lower Home forecast page settles", current -> stable(current)
                    && ((TvScrollView) get(current, "homeScroll")).getScrollY() == 720
                    && target(current, "shortcut-launcher") == current.getCurrentFocus());
            capture(activity, "weather");

            preferences.setBackground(WallpaperCatalog.CALM_GRAPHITE_MIST);
            preferences.setWallpaperPeriod("night"); preferences.setPaletteMode("dark");
            scene.onActivity(current -> push(current, "APPEARANCE"));
            await(activity, "Appearance page and background settle", current -> imagesReady(current)
                    && "APPEARANCE".equals(current.currentScreenForTest()));
            capture(activity, "appearance");
        }
    }

    private static void stopRealWorkers(MainActivity activity) {
        Future<?> load = (Future<?>) get(activity, "loadFuture");
        assertNotNull(load); assertTrue("Never race real data with the demo", load.isDone());
        set(activity, "loadGeneration", (Integer) get(activity, "loadGeneration") + 1);
        set(activity, "cityGeneration", (Integer) get(activity, "cityGeneration") + 1);
        ((RecommendationsSession) get(activity, "recommendationsSession")).close();
        ((ExecutorService) get(activity, "repositoryIo")).shutdownNow();
        ((ExecutorService) get(activity, "cityIo")).shutdownNow();
        Handler handler = (Handler) get(activity, "main");
        for (String name : new String[] {"clockTick", "uiRender", "dataRender"}) handler.removeCallbacks((Runnable) get(activity, name));
    }

    private RecommendationsSession.Snapshot demoRecommendations() {
        ChannelRepository.Channel kino = new ChannelRepository.Channel(0, KINO, 1, 1, 1, "Демонстрационная подборка", "Кинопоиск");
        ChannelRepository.Channel vk = new ChannelRepository.Channel(0, VK, 1, 1, 2, "Демонстрационная подборка", "VK Видео");
        List<ChannelRepository.Channel> channels = Arrays.asList(kino, vk);
        RecommendationsSession session = new RecommendationsSession(context, ignored -> { });
        try {
            session.configure(true, Arrays.asList(KINO, VK), KINO);
            set(session, "discovery", construct(ChannelRepository.Discovery.class,
                    new Class<?>[] {ChannelRepository.Status.class, ChannelRepository.Completeness.class, List.class, List.class, List.class, ChannelRepository.Stats.class},
                    ChannelRepository.Status.READY, ChannelRepository.Completeness.FULL, channels, channels, Collections.emptyList(), null));
            @SuppressWarnings("unchecked") Map<String, RecommendationsSession.AppPrograms> cache =
                    (Map<String, RecommendationsSession.AppPrograms>) get(session, "programCache");
            for (ChannelRepository.Channel channel : channels) {
                String[] titles = {"Горы", "Берег", "Лес", "Дюны", "Фьорд", "Ночь"};
                int[] images = {R.drawable.background_alpine_day, R.drawable.background_coast_day,
                        R.drawable.background_mist_forest, R.drawable.background_dunes_day,
                        R.drawable.background_twilight_fjord, R.drawable.background_night};
                List<ChannelRepository.Program> programs = new ArrayList<>();
                for (int i = 0; i < titles.length; i++) programs.add(construct(ChannelRepository.Program.class,
                        new Class<?>[] {ChannelRepository.Channel.class, long.class, String.class, String.class, String.class, String.class, Long.class, Integer.class, Integer.class, String.class},
                        channel, (long) i + 1, titles[i], "Вымышленный контент для демонстрации интерфейса",
                        "https://example.invalid/demo/" + channel.id + "/" + i, image(images[i]), Long.valueOf(100 - i), Integer.valueOf(0), Integer.valueOf(0),
                        i % 2 == 0 ? "Путешествия" : "Документальный"));
                ChannelRepository.Programs result = construct(ChannelRepository.Programs.class,
                        new Class<?>[] {ChannelRepository.Status.class, ChannelRepository.Completeness.class, ChannelRepository.Channel.class, List.class, ChannelRepository.Stats.class},
                        ChannelRepository.Status.READY, ChannelRepository.Completeness.FULL, channel, programs, null);
                cache.put(channel.packageName, RecommendationsSession.aggregatePrograms(channel.packageName,
                        Collections.singletonList(channel), Collections.singletonList(result), 0, false));
            }
            set(session, "wantDiscovery", false); set(session, "wantActive", false); set(session, "revision", 1L);
            return session.snapshot();
        } finally { session.close(); }
    }

    private WeatherRepository.WeatherResult demoWeather() {
        long now = System.currentTimeMillis();
        LocalDate first = Instant.ofEpochMilli(now).atZone(CITY_ZONE).toLocalDate();
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        long start = first.atStartOfDay(CITY_ZONE).toInstant().toEpochMilli();
        for (int i = 0; i < 72; i++) hours.add(new WeatherRepository.Hour(start + i * 3_600_000L,
                7 + 4 * Math.sin((i % 24 - 8) * Math.PI / 12), i / 24 == 1 ? 3 : 1, i % 24 >= 7 && i % 24 < 18));
        List<WeatherRepository.Day> days = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDate date = first.plusDays(i);
            long from = date.atStartOfDay(CITY_ZONE).toInstant().toEpochMilli();
            long to = date.plusDays(1).atStartOfDay(CITY_ZONE).toInstant().toEpochMilli();
            days.add(new WeatherRepository.Day(date.toString(), from, to, i == 1 ? 3 : 1,
                    5 + i, 12 + i, 0, 10 + i * 5, 10.8 + i, 18 + i, 1.5,
                    from + 7 * 3_600_000L, from + 17 * 3_600_000L, false, false, from, to));
        }
        WeatherRepository.Weather value = new WeatherRepository.Weather(11, 1, true, 10.8, now, now, "celsius",
                hours, start + 7 * 3_600_000L, start + 17 * 3_600_000L, CITY_ZONE.getId(), WeatherRepository.Provider.OPEN_METEO, 0, days);
        WeatherRepository.Decoder decoder = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long fetchedAt) { return value; }
        };
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather weather) { }
        };
        WeatherRepository.WeatherResult result = new WeatherRepository(url -> "demo", cache, decoder, () -> now,
                delay -> fail("The offline demo never retries")).loadCurrent(preferences.read(), true);
        assertEquals("The fixture must be accepted in the selected units", WeatherRepository.Status.FRESH, result.status);
        assertEquals(WeatherRepository.Failure.NONE, result.failure);
        assertSame(value, result.weather);
        assertEquals("Three complete demo days must reach the renderer", 3, result.weather.forecastDays(now).size());
        assertFalse("Hourly demo values must reach the renderer", result.weather.upcomingHourSlots(now).isEmpty());
        return result;
    }

    private String image(int resource) { return "android.resource://" + context.getPackageName() + "/" + resource; }

    private static boolean stable(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        if (!(Boolean) get(activity, "resumed") || !activity.hasWindowFocus() || root == null || !root.isLaidOut()
                || root.isLayoutRequested() || root.getAlpha() < .999f || root.getVisibility() != View.VISIBLE
                || !(Boolean) get(activity, "catalogLoaded") || load == null || !load.isDone()
                || (Boolean) get(activity, "renderDirty") || (Boolean) get(activity, "restoringWatchRow")
                || ((UiMotion) get(activity, "uiMotion")).isRunning()
                || ((SharedRowsSheet) get(activity, "rowsSheet")).isAnimating()) return false;
        Object rail = get(activity, "watchRail"), scroll = get(activity, "homeScroll");
        return !(rail instanceof TvHorizontalScrollView && ((TvHorizontalScrollView) rail).isScrollAnimating())
                && !(scroll instanceof TvScrollView && ((TvScrollView) scroll).isScrollAnimating());
    }

    private static boolean imagesReady(MainActivity activity) {
        if (!stable(activity) || activity.getCurrentFocus() == null) return false;
        UiImageLoader.Diagnostics diagnostics = ((UiImageLoader) get(activity, "imageLoader")).diagnostics();
        if (diagnostics.jobs != 0 || diagnostics.running != 0 || diagnostics.queued != 0) return false;
        ImageView wallpaper = (ImageView) get(activity, "wallpaperView");
        if (wallpaper == null || !(wallpaper.getDrawable() instanceof BitmapDrawable)) return false;
        for (String binding : new String[] {"iconBindings", "posterBindings", "providerLogos"}) {
            for (Object key : ((Map<?, ?>) get(activity, binding)).keySet()) {
                ImageView image = (ImageView) key;
                Rect visible = new Rect();
                if (image.isShown() && image.getGlobalVisibleRect(visible) && !visible.isEmpty()
                        && !(image.getDrawable() instanceof BitmapDrawable)) return false;
            }
        }
        return true;
    }

    private void capture(MainActivity activity, String name) throws InterruptedException {
        CountDownLatch committed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(3840, activity.getWindow().getDecorView().getWidth());
            assertEquals(2160, activity.getWindow().getDecorView().getHeight());
            View root = (View) get(activity, "root"); assertTrue(root.isHardwareAccelerated());
            assertDemoWeather(activity, root, "weather".equals(name));
            root.setContentDescription("Демонстрационные данные: вымышленный контент и прогноз Москвы, не личная история");
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
        });
        assertTrue("The native frame must reach the renderer", committed.await(3, TimeUnit.SECONDS));
        File directory = new File(context.getExternalFilesDir(null), "publication-run");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        assertTrue(device.takeScreenshot(new File(directory, name + ".png")));
    }

    private static void assertDemoWeather(MainActivity activity, View root, boolean forecastVisible) {
        WeatherRepository.WeatherResult result = (WeatherRepository.WeatherResult) get(activity, "weather");
        assertNotNull(result); assertEquals(WeatherRepository.Status.FRESH, result.status);
        assertEquals(WeatherRepository.Failure.NONE, result.failure); assertNotNull(result.weather);
        assertEquals("celsius", result.weather.units);
        assertEquals(11d, result.weather.temperature, 0d);
        long now = System.currentTimeMillis();
        List<WeatherRepository.Day> days = result.weather.forecastDays(now);
        assertEquals(3, days.size());
        if (!"HOME".equals(activity.currentScreenForTest())) return;
        assertEquals("Москва", requiredText(root, "compact-weather-city").getText().toString());
        assertEquals("Малооблачно", requiredText(root, "compact-weather-condition").getText().toString());
        assertTrue("The photographed header must show a temperature, never a dash",
                requiredText(root, "compact-weather-temperature").getText().toString().startsWith("+11°"));
        View forecast = root.findViewWithTag("home-forecast-card"); assertNotNull(forecast);
        assertNull("A publication capture must not contain a forecast error", forecast.findViewWithTag("forecast-message"));
        assertNotNull("The hourly forecast must be rendered", forecast.findViewWithTag("forecast-hour-temperature"));
        for (WeatherRepository.Day day : days) {
            View column = forecast.findViewWithTag("forecast-day-" + day.date); assertNotNull(day.date, column);
            TextView temperature = requiredText(column, "forecast-temperature");
            assertTrue("Every day must show real demo values", temperature.getText().toString().contains("+"));
            assertFalse(requiredText(column, "forecast-condition").getText().toString().isEmpty());
            if (forecastVisible) {
                Rect visible = new Rect();
                assertTrue("All three daily values must be visible in the weather PNG", temperature.getGlobalVisibleRect(visible));
                assertFalse(visible.isEmpty());
            }
        }
    }

    private static TextView requiredText(View root, String tag) {
        View found = root.findViewWithTag(tag); assertTrue(tag, found instanceof TextView); return (TextView) found;
    }

    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 15_000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return; SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message + "; screenshot fixture did not settle");
    }
    private static MainActivity owner(ActivityScenario<MainActivity> scene) {
        AtomicReference<MainActivity> activity = new AtomicReference<>(); scene.onActivity(activity::set); return activity.get();
    }
    private static View target(MainActivity activity, String key) {
        @SuppressWarnings("unchecked") Map<String, View> targets = (Map<String, View>) get(activity, "targets");
        View view = targets.get(key); assertNotNull(key, view); return view;
    }
    private static void push(MainActivity activity, String screen) {
        try {
            Object value = get(activity, "screen");
            for (Object candidate : value.getClass().getEnumConstants()) if (screen.equals(candidate.toString())) {
                Method method = MainActivity.class.getDeclaredMethod("push", value.getClass()); method.setAccessible(true);
                method.invoke(activity, candidate); return;
            }
            throw new AssertionError(screen);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(screen, failure); }
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity activity, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static <T> T construct(Class<T> type, Class<?>[] signature, Object... arguments) {
        try { Constructor<T> constructor = type.getDeclaredConstructor(signature); constructor.setAccessible(true); return constructor.newInstance(arguments); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(type.getSimpleName(), failure); }
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) copy.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }
}
