package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
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
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WatchNextRepository;
import io.github.ekkzy.softlumi.data.WeatherRepository;
import io.github.ekkzy.softlumi.ui.CompactWeatherView;
import io.github.ekkzy.softlumi.ui.ThreeDayForecastView;
import io.github.ekkzy.softlumi.ui.TvHorizontalScrollView;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import io.github.ekkzy.softlumi.ui.UiTypography;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unified Home on the owned native 4K emulator. Entries, titles and weather are fixtures. */
@RunWith(AndroidJUnit4.class)
public final class TalkiesHomeCompositionTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private LauncherPrefs preferences;
    private final List<AppEntry> apps = new ArrayList<>();

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER); preferences.setPaletteMode("dark"); preferences.setBackground(18);
        preferences.setLanguage("ru"); preferences.setSounds(false); preferences.setReducedMotion(true);
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(true);
        preferences.setFavorites(Collections.emptyList());
        // Unique components exercise list geometry without installing or launching fixture apps.
        for (int i = 0; i < 8; i++) apps.add(new AppEntry(new ComponentName(context.getPackageName(),
                context.getPackageName() + ".test.TalkiesFavorite" + i), "TEST · Избранное " + (i + 1)));
    }

    @After public void restoreRawPreferences() {
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

    @Test public void fixedDockKeepsEveryCountAndReorderInItsOccupiedSlots() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial Home settles", TalkiesHomeCompositionTest::stable);
            for (int count : new int[]{0, 1, 4, 6, 8}) {
                inject(scene, count, Collections.emptyList());
                await(activity, "The " + count + " favorite layout settles", TalkiesHomeCompositionTest::stable);
                AtomicReference<List<Rect>> slots = new AtomicReference<>();
                scene.onActivity(current -> { assertWindow(current); slots.set(assertDock(current, count)); });
                if (count == 0) {
                    focus(scene, "favorite-add");
                    device.pressDPadDown(); await(activity, "Empty Home still reaches settings", current -> stable(current)
                            && tag(current, "shortcut-launcher") == current.getCurrentFocus());
                    device.pressDPadUp(); await(activity, "Empty Home returns to its add action", current -> stable(current)
                            && tag(current, "favorite-add") == current.getCurrentFocus());
                    continue;
                }
                focus(scene, "favorite-0"); device.pressDPadLeft();
                await(activity, "The dock has no left wrap", current -> stable(current)
                        && tag(current, "favorite-0") == current.getCurrentFocus());
                if (count == 1) {
                    device.pressDPadRight();
                    await(activity, "A single favorite has no right wrap", current -> stable(current)
                            && tag(current, "favorite-0") == current.getCurrentFocus());
                    continue;
                }
                focus(scene, "favorite-1");
                scene.onActivity(current -> assertTrue("The real long-click action enters reorder", tag(current, "favorite-1").performLongClick()));
                await(activity, "Reorder mode settles", current -> stable(current) && apps.get(1).id.equals(get(current, "movingId")));
                device.pressDPadRight();
                await(activity, "D-pad moves the chosen app one slot", current -> stable(current)
                        && tag(current, "favorite-2") == current.getCurrentFocus());
                scene.onActivity(current -> assertEquals("Reorder uses the same visible slots as first rendering",
                        slots.get(), assertDock(current, count)));
                device.pressDPadCenter();
                await(activity, "OK keeps the reordered favorite selected", current -> stable(current)
                        && get(current, "movingId") == null && tag(current, "favorite-2") == current.getCurrentFocus());
                assertEquals("The moved identity is persisted, not just its old index", apps.get(1).id,
                        preferences.read().favoriteIds.get(2));
                scene.onActivity(current -> assertEquals("Saving and rebuilding retains dock geometry", slots.get(), assertDock(current, count)));
                focus(scene, "favorite-" + (count - 1)); device.pressDPadRight();
                await(activity, "The last favorite cannot wrap", current -> stable(current)
                        && tag(current, "favorite-" + (count - 1)) == current.getCurrentFocus());
            }
        }
    }

    @Test public void nativeCardFootersStayBelowArtAndOnlyKnownPlaybackGetsProgress() {
        preferences.setRecommendationsEnabled(true);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial Home settles", TalkiesHomeCompositionTest::stable);
            inject(scene, 8, cards()); focus(scene, "watch-talkies-0");
            await(activity, "Local landscape artwork is ready", current -> {
                if(!stable(current))return false;
                for(int i=0;i<4;i++)if(!(artwork(tag(current,"watch-talkies-"+i)).getDrawable() instanceof BitmapDrawable))return false;
                return true;
            });
            scene.onActivity(current -> {
                assertWindow(current); assertDock(current, 8);
                for (int i = 0; i < 4; i++) assertFooter(current, i);
                View first = tag(current, "watch-talkies-0");
                assertEquals("Focus retains the selected poster's stable width", 160, first.getWidth());
                assertEquals(280, first.getHeight());
                View track = required(first, "watch-progress-track"), fill = required(first, "watch-progress-fill");
                assertEquals("The provider's 25/100 position becomes 25%, not a decorative progress bar",
                        track.getWidth() * .25f, fill.getWidth(), 1f);
                assertNull("Missing duration does not fabricate progress", tag(current, "watch-talkies-1").findViewWithTag("watch-progress-track"));
                assertNull("Missing playback position does not fabricate progress", tag(current, "watch-talkies-2").findViewWithTag("watch-progress-track"));
                assertNotNull("A known zero position is different from missing position", tag(current, "watch-talkies-3").findViewWithTag("watch-progress-track"));
                assertVisibleFocus(current);
                View tabs = tag(current, "source-tabs");
                assertEquals("The tab row is raised above content", 158, ((View) tabs.getParent()).getTop());
                assertEquals("The card rail retains its agreed placement", 200, ((View) tag(current, "watch-rail").getParent()).getTop());
                assertTrue("Focus enlargement does not overlap source tabs", bounds(first).top >= bounds(tabs).bottom - 1);
            });
            AtomicReference<List<Rect>> slots = new AtomicReference<>();
            scene.onActivity(current -> slots.set(cardSlots(current)));
            capture(activity,"unified-home-eight-favorites-fixture");
            device.pressDPadLeft(); await(activity, "First card has no left wrap", current -> stable(current)
                    && tag(current, "watch-talkies-0") == current.getCurrentFocus());
            for (int i = 1; i < 4; i++) {
                final int selected = i; device.pressDPadRight();
                await(activity, "Card " + i + " becomes selected", current -> stable(current)
                        && tag(current, "watch-talkies-" + selected) == current.getCurrentFocus());
                scene.onActivity(current -> {
                    assertFooter(current, selected); assertVisibleFocus(current);
                    assertEquals("The newly selected poster retains its width", 160, tag(current, "watch-talkies-" + selected).getWidth());
                    assertEquals("The preceding poster retains its width", 160, tag(current, "watch-talkies-" + (selected - 1)).getWidth());
                    assertEquals("Moving focus must not relayout neighboring cards", slots.get(), cardSlots(current));
                });
            }
            device.pressDPadRight(); await(activity, "Last card has no right wrap", current -> stable(current)
                    && tag(current, "watch-talkies-3") == current.getCurrentFocus());
            device.pressDPadDown(); await(activity, "Down leaves content for favorites", current -> stable(current)
                    && current.getCurrentFocus() != null && String.valueOf(current.getCurrentFocus().getTag()).startsWith("favorite-"));
        }
    }

    @Test public void newClockFaceDoesNotReplaceWeatherTypographyOrTheA4Timeline() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial Home settles", TalkiesHomeCompositionTest::stable);
            preferences.setCity("TEST · Москва", 55.752040, 37.617810, "fixture", "Europe/Moscow");
            preferences.setWeatherEnabled(true); preferences.setUnits("celsius");
            WeatherRepository.WeatherResult weather = controlledWeather();
            scene.onActivity(current -> { set(current, "weather", weather); set(current, "nextWeatherRequestAt", Long.MAX_VALUE); });
            inject(scene, 8, cards());
            await(activity, "Existing weather components bind inside the new Home", current -> stable(current)
                    && countTagPrefix(tag(current, "three-day-forecast"), "forecast-hour-cell-") == 4);
            scene.onActivity(current -> {
                assertWindow(current);
                UiTypography type = UiTypography.get(current);
                TextView clock = (TextView) tag(current, "clock");
                assertEquals("The clock is raised without growing", 28, clock.getTop());
                assertSame("Outfit is a separate Home clock role", type.clockRegular, clock.getTypeface());
                assertTrue(tag(current, "weather-status") instanceof CompactWeatherView);
                assertTrue(tag(current, "three-day-forecast") instanceof ThreeDayForecastView);
                TextView temperature = (TextView) tag(current, "compact-weather-temperature");
                assertSame("Current temperature retains the existing Inter display face", type.displayRegular, temperature.getTypeface());
                assertNotSame("Clock changes must not globally replace Inter", clock.getTypeface(), temperature.getTypeface());
                assertEquals("Current weather remains in its agreed position", 824, tag(current, "weather-status").getLeft());
                assertEquals(40, tag(current, "weather-status").getTop());
                assertFalse(tag(current, "weather-status").isFocusable());
                assertFalse(tag(current, "three-day-forecast").isFocusable());
                assertNotNull("A4 retains its native line and points", tag(current, "forecast-hours-axis"));
                assertOtherTextKeepsItsFont((View) get(current, "root"), clock, type);
            });
        }
    }

    @Test public void cardFormatsKeepApprovedHeaderOffsetsAndTheFixedDock() {
        preferences.setRecommendationsEnabled(true);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial Home settles", TalkiesHomeCompositionTest::stable);
            preferences.setCity("TEST · Москва", 55.752040, 37.617810, "fixture", "Europe/Moscow");
            preferences.setWeatherEnabled(true);
            WeatherRepository.WeatherResult weather = controlledWeather();
            scene.onActivity(current -> { set(current, "weather", weather); set(current, "nextWeatherRequestAt", Long.MAX_VALUE); });
            AtomicReference<List<Rect>> firstGeometry = new AtomicReference<>();
            for (String format : new String[]{LauncherPrefs.CARD_FORMAT_LANDSCAPE, LauncherPrefs.CARD_FORMAT_POSTER}) {
                boolean poster = LauncherPrefs.CARD_FORMAT_POSTER.equals(format);
                int headerOffset = poster ? 0 : 24;
                preferences.setCardFormat(format); inject(scene, 8, cards()); focus(scene, "favorite-0");
                scene.onActivity(current -> {
                    assertWindow(current); assertNotNull(tag(current, "home-unified")); assertDock(current, 8);
                    assertNull(peek(current, "home-classic")); assertNull(peek(current, "home-talkies"));
                    TextView clock = (TextView) tag(current, "clock");
                    assertEquals(54, clock.getLeft()); assertEquals(28 + headerOffset, clock.getTop());
                    assertSame(UiTypography.get(current).clockRegular, clock.getTypeface());
                    View topWeather = tag(current, "weather-status");
                    assertEquals(View.VISIBLE, topWeather.getVisibility());
                    assertEquals(824, topWeather.getLeft()); assertEquals(40 + headerOffset, topWeather.getTop());
                    List<Rect> geometry = new ArrayList<>();
                    geometry.add(withoutHeaderOffset(clock, headerOffset)); geometry.add(withoutHeaderOffset(topWeather, headerOffset));
                    geometry.add(withoutHeaderOffset(headerTextAt(current, 322, 48 + headerOffset), headerOffset));
                    geometry.add(withoutHeaderOffset(headerTextAt(current, 322, 86 + headerOffset), headerOffset));
                    View divider = childAt((ViewGroup) tag(current, "home-unified"), 298, 55 + headerOffset, 1, 56);
                    geometry.add(withoutHeaderOffset(divider, headerOffset));
                    geometry.add(localBounds(tag(current, "home-dock")));
                    for (int i = 0; i < 8; i++) geometry.add(localBounds(tag(current, "favorite-" + i)));
                    if (firstGeometry.get() == null) firstGeometry.set(geometry);
                    else assertEquals("Only the approved header translation changes; header sizes and dock remain fixed", firstGeometry.get(), geometry);
                    assertEquals(poster ? 158 : 214, ((View) tag(current, "source-tabs").getParent()).getTop());
                    assertEquals(poster ? 200 : 274, ((View) tag(current, "watch-rail").getParent()).getTop());
                    assertEquals(poster ? 160 : 576, tag(current, "watch-talkies-0").getWidth());
                    assertEquals(poster ? 280 : 196, tag(current, "watch-talkies-0").getHeight());
                    if (!poster) {
                        for (int i = 0; i < 4; i++) assertApprovedLandscapeCard(current, i);
                        RectF position = boundsIn(tag(current, "watch-talkies-0"), tag(current, "home-unified"));
                        assertEquals("The rail's inner padding is included in the actual artwork row", 294f, position.top, .01f);
                        assertEquals(490f, position.bottom, .01f);
                    }
                });
                if (!poster) {
                    AtomicReference<List<Rect>> slots = new AtomicReference<>();
                    scene.onActivity(current -> slots.set(cardSlots(current)));
                    focus(scene, "watch-talkies-0");
                    await(activity, "The approved landscape cover and service logo are decoded", current -> stable(current)
                            && landscapeImagesReady(current, 0));
                    scene.onActivity(current -> assertLandscapeFocusClearance(current));
                    capture(activity, "approved-landscape-logo-only-fixture");
                    device.pressDPadRight();
                    await(activity, "D-pad reaches the next enlarged landscape card", current -> stable(current)
                            && tag(current, "watch-talkies-1") == current.getCurrentFocus() && landscapeImagesReady(current, 1));
                    scene.onActivity(current -> {
                        assertLandscapeFocusClearance(current);
                        assertEquals("Focus does not relayout the enlarged cards", slots.get(), cardSlots(current));
                    });
                    device.pressDPadDown();
                    await(activity, "Enlarged cards still return to the fixed dock", current -> stable(current)
                            && current.getCurrentFocus() != null && String.valueOf(current.getCurrentFocus().getTag()).startsWith("favorite-"));
                }
            }
            preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); preferences.setRecommendationsEnabled(false);
            inject(scene, 8, cards()); focus(scene, "favorite-0");
            scene.onActivity(current -> {
                assertNull("The plain continuation heading replaces source tabs", peek(current, "source-tabs"));
                assertEquals("Продолжить просмотр", headerTextAt(current, 60, 226).getText().toString());
                assertEquals(52, tag(current, "clock").getTop()); assertEquals(64, tag(current, "weather-status").getTop());
                assertApprovedLandscapeCard(current, 0); assertDock(current, 8);
            });
            device.pressDPadDown();
            await(activity, "Settings shortcut remains reachable", current -> stable(current) && tag(current, "shortcut-launcher") == current.getCurrentFocus());
            device.pressDPadCenter();
            await(activity, "Launcher settings opens", current -> stable(current) && "SETTINGS".equals(current.currentScreenForTest()));
            scene.onActivity(current -> assertNull("Removed screensaver has no settings entry", peek(current, "settings-screensaver")));
        }
    }

    @Test public void headerContrastTracksTheVisiblePaletteAndSurvivesWeatherRebinding() {
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE);
        preferences.setCity("TEST · Москва", 55.752040, 37.617810, "fixture", "Europe/Moscow");
        preferences.setWeatherEnabled(true);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene); await(activity, "Initial Home settles", TalkiesHomeCompositionTest::stable);
            WeatherRepository.WeatherResult weather = controlledWeather();
            scene.onActivity(current -> { set(current, "weather", weather); set(current, "nextWeatherRequestAt", Long.MAX_VALUE); });
            for (int variant = 0; variant < 3; variant++) {
                final boolean shadow = variant != 1;
                preferences.setBackground(variant == 0 ? 19 : 0);
                preferences.setWallpaperPeriod(variant == 0 ? "day" : "night");
                preferences.setWallpaperDim(0);
                preferences.setPaletteMode(variant == 2 ? "light" : "dark");
                inject(scene, 8, cards());
                await(activity, "The header applies its current contrast treatment", TalkiesHomeCompositionTest::stable);
                scene.onActivity(current -> {
                    TextView clock = (TextView) tag(current, "clock");
                    TextView day = headerTextAt(current, 322, 72);
                    TextView date = headerTextAt(current, 322, 110);
                    TextView city = (TextView) tag(current, "compact-weather-city");
                    TextView condition = (TextView) tag(current, "compact-weather-condition");
                    TextView temperature = (TextView) tag(current, "compact-weather-temperature");
                    View weatherView = tag(current, "weather-status");
                    for (TextView label : new TextView[]{clock, day, date, city, condition, temperature}) {
                        assertEquals("Header contrast follows visible light surfaces", shadow, label.getShadowRadius() > 0);
                        assertNotEquals("Text contrast must not force software rendering", View.LAYER_TYPE_SOFTWARE, label.getLayerType());
                    }
                    Rect clockInk = new Rect(), dateInk = new Rect();
                    clock.getPaint().getTextBounds(clock.getText().toString(), 0, clock.length(), clockInk);
                    day.getPaint().getTextBounds(day.getText().toString(), 0, day.length(), dateInk);
                    int clockTop = clock.getTop() + clock.getBaseline() + clockInk.top;
                    int dateTop = day.getTop() + day.getBaseline() + dateInk.top;
                    assertTrue("Visible clock numerals align with the weekday, not just the TextView boxes", Math.abs(clockTop - dateTop) <= 12);
                    invoke(current, "bindTopWeather");
                    assertSame("Weather refresh retains the header host", weatherView, tag(current, "weather-status"));
                    assertSame(temperature, tag(current, "compact-weather-temperature"));
                    assertEquals("Rebinding weather retains contrast", shadow, temperature.getShadowRadius() > 0);
                });
                capture(activity, "header-contrast-" + variant + "-fixture");
            }
        }
    }

    @Test public void packagedAppHasNoScreensaverPreviewDreamOrSceneAssets() throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        if (installed.activities != null) for (android.content.pm.ActivityInfo activity : installed.activities)
            assertFalse("No screensaver preview Activity remains: " + activity.name, activity.name.contains(".screensaver."));
        if (installed.services != null) for (android.content.pm.ServiceInfo service : installed.services) {
            assertFalse("No screensaver service remains: " + service.name, service.name.contains(".screensaver."));
            assertNotEquals("No DreamService binding remains", "android.permission.BIND_DREAM_SERVICE", service.permission);
        }
        assertEquals("The removed Dream metadata is not packaged", 0, context.getResources().getIdentifier("dream", "xml", context.getPackageName()));
        assertEquals("The removed service label is not packaged", 0, context.getResources().getIdentifier("screensaver_service_label", "string", context.getPackageName()));
        assertEquals("Special scene assets are not packaged", 0, context.getAssets().list("screensaver").length);
    }

    private static TextView headerTextAt(MainActivity activity, int left, int top) {
        ViewGroup home = (ViewGroup) tag(activity, "home-unified");
        for (int i = 0; i < home.getChildCount(); i++) {
            View child = home.getChildAt(i);
            if (child instanceof TextView && child.getLeft() == left && child.getTop() == top) {
                TextView text = (TextView) child; assertTrue(text.getText().length() > 0); return text;
            }
        }
        throw new AssertionError("Date header text is missing at " + left + "," + top);
    }

    private static Rect localBounds(View view) { return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()); }
    private static Rect withoutHeaderOffset(View view, int offset) {
        Rect result = localBounds(view); result.offset(0, -offset); return result;
    }
    private static View childAt(ViewGroup parent, int left, int top, int width, int height) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getLeft() == left && child.getTop() == top && child.getWidth() == width && child.getHeight() == height) return child;
        }
        throw new AssertionError("Missing native child at " + left + "," + top + " size " + width + "x" + height);
    }
    private void assertApprovedLandscapeCard(MainActivity activity, int index) {
        ViewGroup card = (ViewGroup) tag(activity, "watch-talkies-" + index);
        assertEquals(576, card.getWidth()); assertEquals(196, card.getHeight());
        View first = card.getChildAt(0); assertTrue("Landscape artwork remains a native ImageView", first instanceof ImageView);
        ImageView art = (ImageView) first;
        assertEquals("The approved larger cover keeps the existing inset", new Rect(8, 8, 272, 188), localBounds(art));
        assertEquals("Covers crop proportionally rather than stretching the source", ImageView.ScaleType.CENTER_CROP, art.getScaleType());
        TextView title = (TextView) childAt(card, 298, 66, 262, 58);
        assertEquals(2, title.getMaxLines()); assertEquals(19f, title.getTextSize(), .01f);
        assertNotNull(title.getLayout());
        assertTrue("Two title lines fit the approved text slot", title.getLayout().getHeight() <= title.getHeight());
        ViewGroup provider = (ViewGroup) required(card, "provider:" + context.getPackageName());
        assertEquals(new Rect(298, 32, 327, 60), localBounds(provider));
        assertEquals("A landscape service is represented by one logo without a repeated caption", 1, provider.getChildCount());
        assertTrue(provider.getChildAt(0) instanceof ImageView);
        ImageView logo = (ImageView) provider.getChildAt(0);
        assertEquals(new Rect(0, 2, 29, 26), localBounds(logo));
        assertEquals("The service mark retains its proportions", ImageView.ScaleType.FIT_CENTER, logo.getScaleType());
        assertEquals("The larger cover keeps a clear text gutter", 26, title.getLeft() - art.getRight());
        assertTrue("The service logo remains separate from the two-line title", provider.getBottom() <= title.getTop());
        assertTrue("The card keeps the service name for accessibility", card.getContentDescription() != null
                && card.getContentDescription().toString().contains(apps.get(0).label));
        assertFalse("The accessible service name is not repeated as a visible label", containsVisibleText(card, apps.get(0).label));
        if (index == 0) {
            View track = childAt(card, 298, 173, 256, 4);
            View fill = childAt(card, 298, 173, 64, 4);
            assertEquals("The new progress width still expresses the provider's real 25% position", track.getWidth() * .25f, fill.getWidth(), 1f);
        }
    }
    private static boolean containsVisibleText(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && value.equals(((TextView) view).getText().toString())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsVisibleText(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }
    private static void assertLandscapeFocusClearance(MainActivity activity) {
        assertVisibleFocus(activity);
        RectF focused = bounds(activity.getCurrentFocus());
        assertTrue("Focused landscape art cannot overlap the raised source row", focused.top >= bounds(tag(activity, "source-tabs")).bottom - 1);
        assertTrue("Focused landscape art stays clear of the fixed glass dock", focused.bottom < bounds(tag(activity, "home-dock")).top);
        ViewGroup card = (ViewGroup) activity.getCurrentFocus();
        Rect visible = new Rect();
        assertTrue("The selected artwork has actual visible native pixels", card.getChildAt(0).getGlobalVisibleRect(visible));
    }
    private boolean landscapeImagesReady(MainActivity activity, int index) {
        ViewGroup card = (ViewGroup) tag(activity, "watch-talkies-" + index);
        ViewGroup provider = (ViewGroup) required(card, "provider:" + context.getPackageName());
        return ((ImageView) card.getChildAt(0)).getDrawable() instanceof BitmapDrawable
                && ((ImageView) provider.getChildAt(0)).getDrawable() != null;
    }
    private static RectF boundsIn(View view, View ancestor) {
        Matrix global = new Matrix(), inverse = new Matrix(); ancestor.transformMatrixToGlobal(global);
        assertTrue("The Home transform is invertible", global.invert(inverse));
        RectF result = bounds(view); inverse.mapRect(result); return result;
    }
    private static List<Rect> cardSlots(MainActivity activity) {
        List<Rect> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) slots.add(localBounds(tag(activity, "watch-talkies-" + i)));
        return slots;
    }

    private List<Rect> assertDock(MainActivity activity, int count) {
        View dock = tag(activity, "home-dock");
        assertEquals("The unified Home retains the Lumi dock position", 532, dock.getTop());
        assertEquals(52, dock.getLeft()); assertEquals(1176, dock.getWidth());
        assertEquals("The existing glass dock height is preserved", 132, dock.getHeight());
        assertEquals("The dock is centered in the actual window", 1920f, bounds(dock).centerX(), 2f);
        assertTrue("Dock stays on screen", bounds(dock).left >= 0 && bounds(dock).right <= 3840);
        List<Rect> occupied = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            View tile = tag(activity, "favorite-" + i);
            assertEquals("Favorite width does not stretch with count", 126, tile.getWidth());
            assertEquals("Existing favorite height is retained", 96, tile.getHeight());
            assertEquals("Favorite slots stay fixed regardless of count", 68 + i * 146, tile.getLeft());
            assertEquals(550, tile.getTop());
            Rect slot = new Rect(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
            if (!occupied.isEmpty()) {
                Rect previous = occupied.get(occupied.size() - 1);
                assertEquals("Consistent spacing after list changes", 20, slot.left - previous.right);
                assertEquals(previous.top, slot.top);
            }
            assertTrue("Every favorite fits the dock", slot.left >= dock.getLeft() && slot.right <= dock.getRight()
                    && slot.top >= dock.getTop() && slot.bottom <= dock.getBottom());
            occupied.add(slot);
        }
        assertNull("No filler tile after the real list", peek(activity, "favorite-" + count));
        if (count == 0) {
            View add = tag(activity, "favorite-add");
            assertEquals("Empty state is a reachable centered action", 1920f, bounds(add).centerX(), 2f);
            assertTrue(add.isFocusable());
        }
        return occupied;
    }

    private void assertFooter(MainActivity activity, int index) {
        View card = tag(activity, "watch-talkies-" + index);
        View art = artwork(card), footer = required(card, "watch-footer");
        View title = required(card, "watch-title"), provider = required(card, "provider:" + context.getPackageName());
        assertNull("A Home card has no glass background plate", card.getBackground());
        assertNull("Footer text and logo have no separate plate", footer.getBackground());
        assertNull(footer.getForeground());
        assertNull("Home never substitutes a description for a genre", card.findViewWithTag("watch-description"));
        View genre = card.findViewWithTag("watch-genre");
        assertTrue("No genre is invented for continuation records without that field", genre == null || genre.getVisibility() != View.VISIBLE);
        assertTrue("The footer is outside the poster", bounds(footer).top >= bounds(art).bottom - 1);
        assertTrue("Title cannot cover artwork", bounds(title).top >= bounds(art).bottom);
        assertTrue("Service mark cannot cover artwork", bounds(provider).top >= bounds(art).bottom);
        assertTrue("Title and app mark must not overlap", !RectF.intersects(bounds(title), bounds(provider)));
        assertTrue("Footer remains inside its card", bounds(footer).bottom <= bounds(card).bottom + 1);
        assertTrue("Content captions stay clear of the dock", bounds(footer).bottom < bounds(tag(activity, "home-dock")).top);
        assertTrue("Cards keep an accessible name", card.getContentDescription() != null
                && card.getContentDescription().toString().contains("TEST"));
    }

    private void inject(ActivityScenario<MainActivity> scene, int count, List<WatchNextRepository.Entry> entries) {
        List<String> ids = new ArrayList<>(); for (int i = 0; i < count; i++) ids.add(apps.get(i).id);
        preferences.setFavorites(ids);
        scene.onActivity(current -> {
            Future<?> load = (Future<?>) get(current, "loadFuture");
            assertTrue("Initial provider work has completed", load != null && load.isDone());
            set(current, "catalog", Collections.unmodifiableList(new ArrayList<>(apps)));
            @SuppressWarnings("unchecked") Map<String, AppEntry> byId = (Map<String, AppEntry>) get(current, "appsById");
            byId.clear(); for (AppEntry app : apps) byId.put(app.id, app);
            set(current, "prefs", preferences.read()); set(current, "continuation", entries); set(current, "watchLoaded", true);
            set(current, "watchStatus", entries.isEmpty() ? WatchNextRepository.Status.EMPTY : WatchNextRepository.Status.ITEMS);
            invoke(current, "renderPreservingFocus");
        });
        await(owner(scene), "Controlled Home is laid out", TalkiesHomeCompositionTest::stable);
    }

    private List<WatchNextRepository.Entry> cards() {
        List<WatchNextRepository.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 4; i++) entries.add(new WatchNextRepository.Entry("talkies-" + i,
                "TEST · Заголовок программы " + (i + 1), "Синтетическая карточка · Серия " + (i + 1),
                context.getPackageName(), new Intent(context, MainActivity.class).toUri(Intent.URI_INTENT_SCHEME),
                "android.resource://" + context.getPackageName() + "/"
                        + (i % 2 == 0 ? R.drawable.background_mist_forest : R.drawable.background_moon_coast),
                i == 2 ? -1 : i == 0 ? 25 : 0, i == 1 ? 0 : 100,
                System.currentTimeMillis() - i, true, true, WatchNextRepository.Kind.SHOW));
        return entries;
    }

    private WeatherRepository.WeatherResult controlledWeather() {
        long now = System.currentTimeMillis(), first = (now / 3_600_000L + 1) * 3_600_000L;
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        for (int i = 0; i < 7; i++) hours.add(new WeatherRepository.Hour(first + i * 3_600_000L, 8 + i, 3, true));
        WeatherRepository.Weather value = new WeatherRepository.Weather(8, 3, true, 10.8, now, now,
                "celsius", hours, 0, 0, "Europe/Moscow", WeatherRepository.Provider.OPEN_METEO, 0, Collections.emptyList());
        WeatherRepository.Decoder decoder = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long fetched) { return value; }
        };
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather weather) { }
        };
        return new WeatherRepository(url -> "fixture", cache, decoder, () -> now,
                millis -> fail("Controlled weather never retries")).loadCurrent(preferences.read(), true);
    }

    private static void assertOtherTextKeepsItsFont(View view, TextView clock, UiTypography type) {
        if (view instanceof TextView && view != clock) assertNotSame("Only the clock uses Outfit: " + view.getTag(),
                type.clockRegular, ((TextView) view).getTypeface());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertOtherTextKeepsItsFont(((ViewGroup) view).getChildAt(i), clock, type);
    }
    private static int countTagPrefix(View view, String prefix) {
        int count = String.valueOf(view.getTag()).startsWith(prefix) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            count += countTagPrefix(((ViewGroup) view).getChildAt(i), prefix);
        return count;
    }
    private static void assertVisibleFocus(MainActivity activity) {
        View focus = activity.getCurrentFocus(); assertNotNull(focus);
        Rect visible = new Rect(); assertTrue("Focused card remains visible", focus.getGlobalVisibleRect(visible));
        RectF frame = bounds(focus), viewport = bounds(tag(activity, "watch-rail"));
        assertTrue("The selected card fits the horizontal viewport", frame.left >= viewport.left - 2 && frame.right <= viewport.right + 2);
        assertTrue("The focused card stays on screen", frame.top >= 0 && frame.bottom <= 2160);
    }
    private void focus(ActivityScenario<MainActivity> scene, String key) {
        scene.onActivity(current -> assertTrue(key, tag(current, key).requestFocus()));
        await(owner(scene), "Focus reaches " + key, current -> stable(current) && tag(current, key) == current.getCurrentFocus());
    }
    private static boolean stable(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        if (!(Boolean) get(activity, "resumed") || !activity.hasWindowFocus() || root == null || !root.isLaidOut()
                || root.isLayoutRequested() || !(Boolean) get(activity, "catalogLoaded") || load == null || !load.isDone()
                || (Boolean) get(activity, "renderDirty") || (Boolean) get(activity, "restoringWatchRow")
                || ((UiMotion) get(activity, "uiMotion")).isRunning()) return false;
        Object rail = get(activity, "watchRail"), scroll = get(activity, "homeScroll");
        return !(rail instanceof TvHorizontalScrollView && ((TvHorizontalScrollView) rail).isScrollAnimating())
                && !(scroll instanceof TvScrollView && ((TvScrollView) scroll).isScrollAnimating());
    }
    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 10000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return; SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }
    private static MainActivity owner(ActivityScenario<MainActivity> scene) {
        AtomicReference<MainActivity> activity = new AtomicReference<>(); scene.onActivity(activity::set); return activity.get();
    }
    private static View peek(MainActivity activity, String key) { return ((View) get(activity, "root")).findViewWithTag(key); }
    private static View tag(MainActivity activity, String key) { View view = peek(activity, key); assertNotNull(key, view); return view; }
    private static View required(View parent, String key) { View view = parent.findViewWithTag(key); assertNotNull(key, view); return view; }
    private static ImageView artwork(View parent) {
        View view = transition(parent, "watch-art");
        assertTrue("A card has a separate native artwork ImageView", view instanceof ImageView);
        return (ImageView) view;
    }
    private static View transition(View view, String name) {
        if (name.equals(view.getTransitionName())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = transition(((ViewGroup) view).getChildAt(i), name);
            if (found != null) return found;
        }
        return null;
    }
    private static RectF bounds(View view) { Matrix matrix = new Matrix(); view.transformMatrixToGlobal(matrix); RectF rect = new RectF(0, 0, view.getWidth(), view.getHeight()); matrix.mapRect(rect); return rect; }
    private static void assertWindow(MainActivity activity) {
        assertEquals("Actual UI width, not HDMI mode", 3840, activity.getWindow().getDecorView().getWidth());
        assertEquals(2160, activity.getWindow().getDecorView().getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static Object get(MainActivity activity, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void set(MainActivity activity, String name, Object value) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity activity, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) copy.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }
    private void capture(MainActivity activity,String name) {
        CountDownLatch committed=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            View root=(View)get(activity,"root");
            assertTrue("Native screenshots require hardware composition",root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            root.invalidate();
        });
        try{assertTrue("Artwork frame reaches the renderer before capture",committed.await(3,TimeUnit.SECONDS));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue(device.takeScreenshot(new File(folder, name + ".png")));
    }
}
