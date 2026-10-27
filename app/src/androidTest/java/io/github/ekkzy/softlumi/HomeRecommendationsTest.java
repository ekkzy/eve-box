package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
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
import io.github.ekkzy.softlumi.data.WatchNextRepository;
import io.github.ekkzy.softlumi.data.WeatherRepository;
import io.github.ekkzy.softlumi.ui.CompactWeatherView;
import io.github.ekkzy.softlumi.ui.ThreeDayForecastView;
import io.github.ekkzy.softlumi.ui.TvHorizontalScrollView;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
 * UI fixtures only: no TV Provider writes, installed fixture apps, or content launches.
 * Immutable snapshots isolate presentation from device provider/permission availability.
 */
@RunWith(AndroidJUnit4.class)
public final class HomeRecommendationsTest {
    private static final String ALPHA = "io.github.ekkzy.softlumi.test.alpha";
    private static final String BETA = "io.github.ekkzy.softlumi.test.beta";
    private static final String CONTINUE = "recommendations-continue";
    private final ChannelRepository.Channel alpha = new ChannelRepository.Channel(0, ALPHA, 100, 1, 11, "TEST shelf A", "TEST Alpha");
    private final ChannelRepository.Channel beta = new ChannelRepository.Channel(0, BETA, 200, 1, 22, "TEST shelf B", "TEST Beta");
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private LauncherPrefs preferences;
    private final List<AppEntry> apps = new ArrayList<>();
    private List<ChannelRepository.Program> alphaCards, betaCards;
    private final Map<String, List<ChannelRepository.Program>> additionalCards = new HashMap<>();
    private long revision;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        String fixturePalette = InstrumentationRegistry.getArguments().getString("softlumiFixturePalette", "dark");
        assertTrue("The fixture palette is dark or light", "dark".equals(fixturePalette) || "light".equals(fixturePalette));
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = preferenceSnapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER); preferences.setPaletteMode(fixturePalette); preferences.setBackground("light".equals(fixturePalette) ? 20 : 18);
        preferences.setLanguage("en"); preferences.setSounds(false); preferences.setReducedMotion(true);
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(true);
        preferences.setRecommendationsEnabled(false); preferences.setFavorites(Collections.emptyList());
        for (int i = 0; i < 8; i++) apps.add(new AppEntry(new ComponentName(context.getPackageName(),
                context.getPackageName() + ".test.RecommendationFavorite" + i), "TEST Favorite " + (i + 1)));
        alphaCards = programs(alpha, 8); betaCards = programs(beta, 8);
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
        assertTrue(editor.commit()); assertEquals(original, preferenceSnapshot(storage));
    }

    @Test public void tabFocusDoesNotSelectUntilActivationAndProgramsHaveNoPlaybackProgress() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            focus(scene, "source:");
            scene.onActivity(current -> {
                assertWindow(current);
                assertTrue(target(current, "source:").isSelected());
                assertNotNull(target(current, "watch-" + CONTINUE).findViewWithTag("watch-progress-track"));
                assertTabAppearance(current, "source:", "source:");
            });
            AtomicReference<View> previousContinueTab = new AtomicReference<>();
            scene.onActivity(current -> previousContinueTab.set(target(current, "source:")));
            ChannelRepository.Channel renamedAlpha = new ChannelRepository.Channel(alpha.userSerial, alpha.packageName,
                    alpha.installEpoch, alpha.versionCode, alpha.id, alpha.displayName, "TEST Alpha refreshed", alpha.systemBrowsable);
            publish(scene, Arrays.asList(renamedAlpha, beta));
            await(activity, "Rebinding source labels preserves the current native tab focus", current -> stable(current)
                    && targets(current).get("source:") != previousContinueTab.get() && focused(current, "source:"));
            scene.onActivity(current -> assertTabAppearance(current, "source:", "source:"));
            assertTrue(device.pressDPadRight());
            await(activity, "Right focuses Alpha without replacing Continue", current -> stable(current)
                    && focused(current, "source:" + ALPHA));
            scene.onActivity(current -> {
                assertEquals("", get(current, "homeSource"));
                assertTrue("Selection remains on Continue while another tab is focused", target(current, "source:").isSelected());
                assertFalse(target(current, "source:" + ALPHA).isSelected());
                assertNotNull(target(current, "watch-" + CONTINUE));
                assertTabAppearance(current, "source:", "source:" + ALPHA);
            });

            assertTrue(device.pressDPadDown());
            await(activity, "Down activates the focused source and enters its first card", current -> stable(current)
                    && focused(current, cardKey(alphaCards.get(0))));
            scene.onActivity(current -> {
                assertEquals(ALPHA, get(current, "homeSource"));
                assertTrue(target(current, "source:" + ALPHA).isSelected());
                assertFalse(target(current, "source:").isSelected());
                assertNull(targets(current).get("watch-" + CONTINUE));
                assertRecommendationCards(current, alphaCards);
                assertVisibleHero(current);
                assertGenre(current, alphaCards.get(0), true);
                assertGenre(current, alphaCards.get(1), false);
                assertTabAppearance(current, "source:" + ALPHA, null);
            });
            await(activity, "Visible synthetic recommendation posters are decoded before capture", current ->
                    stable(current) && visiblePostersReady(current));
            capture(activity, "home-recommendations-alpha-eight-favorites-fixture");
            assertTrue(device.pressDPadUp());
            await(activity, "Up returns to the selected Alpha tab", current -> stable(current) && focused(current, "source:" + ALPHA));
            scene.onActivity(current -> {
                assertEquals("Leaving a poster keeps its fixed width", 160, target(current, cardKey(alphaCards.get(0))).getWidth());
                assertGenre(current, alphaCards.get(0), false);
                assertTabAppearance(current, "source:" + ALPHA, "source:" + ALPHA);
            });
            assertTrue(device.pressDPadDown());
            await(activity, "Re-entering the known genre card restores its caption", current -> stable(current) && focused(current, cardKey(alphaCards.get(0))));
            assertTrue(device.pressDPadRight());
            await(activity, "The next focused card has no provider genre", current -> stable(current) && focused(current, cardKey(alphaCards.get(1))));
            scene.onActivity(current -> { assertGenre(current, alphaCards.get(0), false); assertGenre(current, alphaCards.get(1), false); });
            assertTrue(device.pressDPadRight());
            await(activity, "A different publisher genre follows actual focus", current -> stable(current) && focused(current, cardKey(alphaCards.get(2))));
            scene.onActivity(current -> assertGenre(current, alphaCards.get(2), true));
            assertTrue(device.pressDPadDown());
            await(activity, "Genre disappears when the dock receives focus", current -> stable(current) && favoriteFocused(current));
            scene.onActivity(current -> {
                assertEquals(160, target(current, cardKey(alphaCards.get(2))).getWidth());
                assertGenre(current, alphaCards.get(2), false);
            });
            assertTrue(device.pressDPadUp());
            await(activity, "Returning from the dock restores the selected card", current -> stable(current) && focused(current, cardKey(alphaCards.get(2))));
            scene.onActivity(current -> assertGenre(current, alphaCards.get(2), true));
            assertTrue(device.pressDPadUp());
            await(activity, "The source tab remains reachable after genre transitions", current -> stable(current) && focused(current, "source:" + ALPHA));
            assertTrue(device.pressDPadRight());
            await(activity, "Beta may be focused while Alpha remains selected", current -> stable(current) && focused(current, "source:" + BETA));
            scene.onActivity(current -> assertTrue(target(current, "source:" + ALPHA).isSelected()));
            assertTrue(device.pressDPadCenter());
            await(activity, "OK selects Beta and keeps focus in the tab row", current -> stable(current)
                    && BETA.equals(get(current, "homeSource")) && focused(current, "source:" + BETA));
            assertTrue(device.pressDPadDown());
            await(activity, "Down enters Beta", current -> stable(current) && focused(current, cardKey(betaCards.get(0))));
            assertTrue(device.pressDPadLeft());
            await(activity, "The first program has no left wrap", current -> stable(current) && focused(current, cardKey(betaCards.get(0))));
            scene.onActivity(current -> assertRecommendationCards(current, betaCards));
        }
    }

    @Test public void overflowingProgramRowReachesItsLastCardAndRestoresItThroughTheSourceTab() {
        alphaCards = programs(alpha, 50);
        preferences.setReducedMotion(false);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            enterSource(scene, alpha);
            scene.onActivity(current -> {
                assertFalse("This scenario exercises normal motion", ((LauncherPrefs.Settings) get(current, "prefs")).reducedMotion);
                assertTrue("Native horizontal motion is enabled", ((TvHorizontalScrollView) get(current, "watchRail")).isSmoothScrollingEnabled());
            });
            // Do not wait for focus/scroll animation to settle between inputs.
            for (int i = 1; i < alphaCards.size(); i++) assertTrue(device.pressDPadRight());
            AtomicReference<RowPosition> last = new AtomicReference<>();
            String key = cardKey(alphaCards.get(alphaCards.size() - 1));
            await(activity, "A burst of 49 Right presses reaches the last of 50 programs", current -> stable(current) && focused(current, key));
            scene.onActivity(current -> {
                assertEquals("The complete bounded source is navigable", 50, ((List<?>) get(current, "watchTiles")).size());
                last.set(new RowPosition(current)); assertVisibleHero(current);
                assertEquals("Horizontal content navigation stays on the upper Home", 0, ((TvScrollView) get(current, "homeScroll")).getScrollY());
            });
            for (int i = 0; i < 4; i++) assertTrue(device.pressDPadRight());
            long downTime = SystemClock.uptimeMillis();
            for (int repeat = 0; repeat < 8; repeat++) InstrumentationRegistry.getInstrumentation().sendKeySync(
                    new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat));
            InstrumentationRegistry.getInstrumentation().sendKeySync(
                    new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
            await(activity, "Further Right presses stop at the last program", current -> stable(current) && focused(current, key));
            scene.onActivity(current -> {
                last.get().assertRestored(current);
                assertEquals("Right at the row boundary cannot reveal the forecast page", 0, ((TvScrollView) get(current, "homeScroll")).getScrollY());
            });
            String previous = cardKey(alphaCards.get(alphaCards.size() - 2));
            assertTrue(device.pressDPadLeft());
            scene.onActivity(current -> assertSame("Left responds immediately after held Right at the boundary",
                    target(current, previous), current.getCurrentFocus()));
            await(activity, "The penultimate program settles visibly", current -> stable(current) && focused(current, previous));
            scene.onActivity(HomeRecommendationsTest::assertVisibleHero);
            assertTrue(device.pressDPadRight());
            await(activity, "Right returns to the final program", current -> stable(current) && focused(current, key));
            scene.onActivity(current -> last.set(new RowPosition(current)));
            assertTrue(device.pressDPadUp());
            await(activity, "Up from the distant last card reaches its source tab", current -> stable(current) && focused(current, "source:" + ALPHA));
            assertTrue(device.pressDPadDown());
            await(activity, "Down restores that last card, not the first visible card", current -> stable(current) && focused(current, key));
            scene.onActivity(current -> { last.get().assertRestored(current); assertVisibleHero(current); });
        }
    }

    @Test public void eachSourceRestoresItsSelectedProgramAndScrollAcrossRapidRoundTrips() {
        alphaCards = programs(alpha, 16); betaCards = programs(beta, 16);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            enterSource(scene, alpha);
            moveRight(activity, alphaCards, 10);
            AtomicReference<RowPosition> a = new AtomicReference<>(), b = new AtomicReference<>();
            scene.onActivity(current -> a.set(new RowPosition(current)));
            assertTrue(device.pressDPadUp());
            await(activity, "Alpha returns to its tab", current -> stable(current) && focused(current, "source:" + ALPHA));
            assertTrue(device.pressDPadRight()); assertTrue(device.pressDPadDown());
            await(activity, "Beta starts at its own first item", current -> stable(current) && focused(current, cardKey(betaCards.get(0))));
            moveRight(activity, betaCards, 10);
            moveLeft(activity, betaCards, 9, 8);
            scene.onActivity(current -> b.set(new RowPosition(current)));
            assertTrue(device.pressDPadUp());
            await(activity, "Beta returns to its tab", current -> stable(current) && focused(current, "source:" + BETA));
            assertTrue(device.pressDPadLeft()); assertTrue(device.pressDPadDown());
            await(activity, "Alpha restores its distant item instead of Beta's position", current -> stable(current)
                    && focused(current, cardKey(alphaCards.get(10))));
            scene.onActivity(current -> a.get().assertRestored(current));

            // Deliberately queue multiple post-layout callbacks in one native UI turn.
            // The final request stays on Alpha's tab; earlier requests enter cards.
            scene.onActivity(current -> {
                select(current, BETA, true);
                select(current, ALPHA, true);
                select(current, BETA, true);
                select(current, ALPHA, false);
                // An unrelated/no-change delivery must not invalidate the callback which
                // restores the just-built rail and clears its restoring state.
                invoke(current, "refreshHomeDataInPlace");
            });
            await(activity, "Queued earlier requests cannot override the final tab focus", current -> stable(current)
                    && ALPHA.equals(get(current, "homeSource")) && focused(current, "source:" + ALPHA));
            drainUi();
            scene.onActivity(current -> {
                assertSame("No delayed callback re-enters an old card", target(current, "source:" + ALPHA), current.getCurrentFocus());
                a.get().assertRestored(current);
                assertSourcePosition(current, BETA, b.get());
            });
            assertTrue(device.pressDPadRight()); assertTrue(device.pressDPadDown());
            await(activity, "Beta still restores its independent distant item", current -> stable(current)
                    && focused(current, cardKey(betaCards.get(8))));
            scene.onActivity(current -> { b.get().assertRestored(current); assertVisibleHero(current); });
        }
    }

    @Test public void downWaitsForColdSourceButLaterDpadInputCancelsDeferredEntry() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            publishLoading(scene, alpha);
            focus(scene, "source:" + ALPHA);
            assertTrue(device.pressDPadDown());
            await(activity, "Down stays on Alpha while its first query has no cached cards", current -> stable(current)
                    && ALPHA.equals(get(current, "homeSource")) && focused(current, "source:" + ALPHA)
                    && ((List<?>) get(current, "watchTiles")).isEmpty()
                    && hasText((View) get(current, "root"), "Loading recommendations"));
            scene.onActivity(current -> {
                assertTrue(target(current, "source:" + ALPHA).isSelected());
                assertFalse("Cold loading must not return focus or selection to Continue", target(current, "source:").isSelected());
            });
            publish(scene, Arrays.asList(alpha, beta));
            await(activity, "The completed Alpha query fulfills the original Down press", current -> stable(current)
                    && focused(current, cardKey(alphaCards.get(0))));
            scene.onActivity(current -> assertVisibleHero(current));

            publishLoading(scene, beta);
            focus(scene, "source:" + BETA);
            assertTrue(device.pressDPadDown());
            await(activity, "Beta also waits on its own tab while its cache is absent", current -> stable(current)
                    && BETA.equals(get(current, "homeSource")) && focused(current, "source:" + BETA)
                    && ((List<?>) get(current, "watchTiles")).isEmpty());
            assertTrue(device.pressDPadLeft());
            await(activity, "A newer Left press focuses Alpha without selecting it", current -> stable(current)
                    && focused(current, "source:" + ALPHA) && BETA.equals(get(current, "homeSource")));
            AtomicReference<View> chosenTab = new AtomicReference<>();
            scene.onActivity(current -> {
                chosenTab.set(current.getCurrentFocus());
                assertTrue(target(current, "source:" + BETA).isSelected());
                assertFalse(target(current, "source:" + ALPHA).isSelected());
            });
            publish(scene, Arrays.asList(alpha, beta));
            await(activity, "Beta may finish loading without overriding the user's newer tab focus", current -> stable(current)
                    && targets(current).containsKey(cardKey(betaCards.get(0)))
                    && BETA.equals(get(current, "homeSource")) && focused(current, "source:" + ALPHA));
            drainUi();
            scene.onActivity(current -> {
                assertSame("No deferred entry steals focus after loading settles", chosenTab.get(), current.getCurrentFocus());
                assertTrue(target(current, "source:" + BETA).isSelected());
                assertRecommendationCards(current, betaCards);
            });

            assertTrue(device.pressDPadDown());
            await(activity, "Return to Alpha before the posted-focus race", current -> stable(current)
                    && focused(current, cardKey(alphaCards.get(0))));
            assertTrue(device.pressDPadUp());
            await(activity, "Alpha's tab is the starting focus", current -> stable(current) && focused(current, "source:" + ALPHA));
            scene.onActivity(current -> {
                select(current, BETA, false); // Rebuild regions; its focus callbacks are still queued.
                View nativeRoot = (View) get(current, "root");
                int left = nativeRoot.getLeft(), top = nativeRoot.getTop();
                int right = nativeRoot.getRight(), bottom = nativeRoot.getBottom();
                assertTrue("The existing native viewport has measured bounds", right > left && bottom > top);
                // FocusFinder excludes zero-size new views. Complete their real native
                // layout synchronously, without running the queued focus callbacks.
                nativeRoot.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
                nativeRoot.layout(left, top, right, bottom);
                assertTrue("Both destination tabs have native geometry", target(current, "source:" + ALPHA).getWidth() > 0
                        && target(current, "source:" + BETA).getWidth() > 0);
                invoke(current, "wireFocus");
                // Only setup uses requestFocus; the newer user action is a real key event.
                assertTrue(target(current, "source:" + BETA).requestFocus());
                long now = SystemClock.uptimeMillis();
                boolean consumed=current.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0));
                // Direct Activity dispatch has no ViewRoot input stage to handle an
                // unconsumed navigation key. Complete that boundary through native
                // focusSearch, keeping the queued application callbacks suspended.
                if(!consumed){View next=target(current,"source:"+BETA).focusSearch(View.FOCUS_LEFT);
                    assertSame("Native focus search resolves Left",target(current,"source:"+ALPHA),next);
                    assertTrue(next.requestFocus());}
                current.dispatchKeyEvent(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0));
                assertSame("The new Left input routes before queued restoration", target(current, "source:" + ALPHA), current.getCurrentFocus());
                chosenTab.set(current.getCurrentFocus());
            });
            drainUi();
            await(activity, "Posted callbacks respect input received after the partial rebind", current -> stable(current)
                    && BETA.equals(get(current, "homeSource")) && focused(current, "source:" + ALPHA));
            scene.onActivity(current -> assertSame("Late focus restoration cannot undo Left", chosenTab.get(), current.getCurrentFocus()));
        }
    }

    @Test public void refreshPreservesDockWeatherAndFocusThenRemovesAnUnavailableShelfHonestly() {
        alphaCards = programs(alpha, 16);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            enterSource(scene, alpha); moveRight(activity, alphaCards, 10); moveLeft(activity, alphaCards, 9, 8);
            assertTrue(device.pressDPadDown());
            await(activity, "Down reaches the favorite dock", current -> stable(current) && favoriteFocused(current));
            AtomicReference<HomeSurface> surface = new AtomicReference<>();
            AtomicReference<RowPosition> position = new AtomicReference<>();
            AtomicReference<View> originalRail = new AtomicReference<>();
            scene.onActivity(current -> {
                surface.set(new HomeSurface(current)); position.set(new RowPosition(current));
                originalRail.set((View) get(current, "watchRail"));
            });

            betaCards = new ArrayList<>(betaCards);
            betaCards.set(0, program(beta, 1, "TEST Beta refreshed in background"));
            publish(scene, Arrays.asList(alpha, beta));
            await(activity, "An inactive source update settles", HomeRecommendationsTest::stable);
            scene.onActivity(current -> {
                surface.get().assertRetained(current);
                assertSame("An unrelated source update leaves the active rail intact", originalRail.get(), get(current, "watchRail"));
            });

            ChannelRepository.Program removed = alphaCards.get(8), fallback = alphaCards.get(9);
            alphaCards = new ArrayList<>(alphaCards); alphaCards.remove(8);
            alphaCards.set(0, program(alpha, 1, "TEST Alpha refreshed"));
            publish(scene, Arrays.asList(alpha, beta));
            await(activity, "Deleting the selected program replaces it without leaving the dock", current -> stable(current)
                    && targets(current).get(cardKey(removed)) == null
                    && ("program:" + fallback.key).equals(get(get(current, "activeWatchState"), "id")));
            scene.onActivity(current -> {
                surface.get().assertRetained(current);
                assertEquals("The next surviving item takes the old index", 8, get(get(current, "activeWatchState"), "index"));
                assertEquals("The fallback keeps its fixed poster width before the user returns", 160, target(current, cardKey(fallback)).getWidth());
                assertEquals(position.get().offset, ((TvHorizontalScrollView) get(current, "watchRail")).getScrollX(), 1);
                assertFalse("The deleted card is removed from the focus graph", hasFocusReference(current, removed));
            });

            // Keep the old Programs in the cache, but remove its publication from discovery.
            // A stale program list must not masquerade as an available or empty shelf.
            publish(scene, Collections.singletonList(beta));
            await(activity, "The missing channel invalidates its cached cards", current -> stable(current)
                    && ((List<?>) get(current, "watchTiles")).isEmpty());
            scene.onActivity(current -> {
                surface.get().assertRetained(current);
                assertTrue("The missing selected source remains reachable", target(current, "source:" + ALPHA).isSelected());
                assertNotNull(target(current, "recommendations-sources"));
                assertNotNull(target(current, "recommendations-refresh"));
                assertNull(get(current, "watchRail"));
                for (String key : targets(current).keySet()) assertFalse("No stale program action remains", key.startsWith("watch-program:"));
                View root = (View) get(current, "root");
                assertTrue("Unavailable publication/access is explained, rather than reported as ordinary EMPTY",
                        hasText(root, "Recommendations from this app are unavailable") || hasText(root, "Recommendations need TV channel access"));
                assertFalse(hasText(root, "The app has not published available cards yet"));
            });
            assertTrue(device.pressDPadUp());
            await(activity, "The empty source's recovery action is reachable from the dock", current -> stable(current)
                    && focused(current, "recommendations-refresh"));
            assertTrue(device.pressDPadRight());
            await(activity, "Right reaches source selection from recovery", current -> stable(current)
                    && focused(current, "recommendations-sources"));
            assertTrue(device.pressDPadLeft());
            await(activity, "Left returns to refresh", current -> stable(current)
                    && focused(current, "recommendations-refresh"));
            assertTrue(device.pressDPadUp());
            await(activity, "Recovery returns to the active source tab", current -> stable(current)
                    && focused(current, "source:" + ALPHA));
            assertTrue(device.pressDPadDown());
            await(activity, "Down enters the empty source's recovery action", current -> stable(current)
                    && focused(current, "recommendations-refresh"));
            assertTrue(device.pressDPadUp());
            await(activity, "Recovery actions return to their selected source without a trap", current -> stable(current)
                    && focused(current, "source:" + ALPHA));
        }
    }

    @Test public void oneAppTabIncludesNewShelvesAndKeepsEachProgramsOriginalLaunchBinding() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene); enterSource(scene, alpha);
            ChannelRepository.Channel extra = new ChannelRepository.Channel(0, ALPHA, 100, 1, 12, "TEST second shelf", "TEST Alpha");
            ChannelRepository.Program duplicate = program(extra, 1, "TEST duplicate intent"),
                    added = program(extra, 101, "TEST new shelf program"), other = program(extra, 102, "TEST another new program");
            additionalCards.put(extra.key, Arrays.asList(duplicate, added, other));
            List<String> consent = new ArrayList<>(preferences.read().recommendationPackages);
            publish(scene, Arrays.asList(alpha, extra, beta));
            await(activity, "An enabled app automatically includes newly published shelves without moving current focus", current -> stable(current)
                    && targets(current).containsKey(cardKey(added)) && focused(current, cardKey(alphaCards.get(0))));
            scene.onActivity(current -> {
                assertEquals("A new shelf does not change the app consent", consent, preferences.read().recommendationPackages);
                assertEquals("Continue plus two apps produce exactly three tabs", 3, sourceTabCount(current));
                assertNotNull(target(current, "source:" + ALPHA)); assertNotNull(target(current, "source:" + BETA));
                assertNull("Channel identities do not become extra app tabs", targets(current).get("source:" + extra.key));
                assertNull("The same exact content intent is not duplicated between an app's shelves", targets(current).get(cardKey(duplicate)));
                assertEquals(alphaCards.size() + 2, ((List<?>) get(current, "watchTiles")).size());
                List<ChannelRepository.Program> expected = new ArrayList<>(alphaCards); expected.add(added); expected.add(other);
                assertRecommendationCards(current, expected); assertLaunchBindings(current, ALPHA);
            });
            focus(scene, cardKey(added));
            assertTrue(device.pressDPadUp());
            await(activity, "A merged program returns to its one app tab", current -> stable(current) && focused(current, "source:" + ALPHA));
            assertTrue(device.pressDPadDown());
            await(activity, "The app tab remembers the selected program from its other shelf", current -> stable(current) && focused(current, cardKey(added)));
            publish(scene, Arrays.asList(extra, beta));
            await(activity, "Losing another shelf preserves the selected surviving program", current -> stable(current)
                    && focused(current, cardKey(added)) && !targets(current).containsKey(cardKey(alphaCards.get(0))));
            scene.onActivity(current -> {
                assertTrue(target(current, "source:" + ALPHA).isSelected()); assertEquals(3, sourceTabCount(current));
                assertEquals("Only the remaining app shelf is displayed", 3, ((List<?>) get(current, "watchTiles")).size());
                assertLaunchBindings(current, ALPHA); assertVisibleHero(current);
            });
        }
    }

    @Test public void oneAppToggleRemovesAllItsShelvesWhileGlobalOffKeepsPersonalContinue() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene);
            ChannelRepository.Channel extra = new ChannelRepository.Channel(0, ALPHA, 100, 1, 12, "TEST second shelf", "TEST Alpha");
            ChannelRepository.Program added = program(extra, 101, "TEST extra app recommendation");
            additionalCards.put(extra.key, Collections.singletonList(added)); publish(scene, Arrays.asList(alpha, extra, beta));
            enterSource(scene, alpha); openSources(scene);
            scene.onActivity(current -> {
                int appRows = 0;
                for (String key : targets(current).keySet()) {
                    if (key.startsWith("app:")) appRows++;
                    assertFalse("There is no individual-channel toggle", key.startsWith("channel:") || key.startsWith("missing-channel:"));
                }
                assertEquals("Two apps produce two toggles even when one has multiple shelves", 2, appRows);
                assertTrue(target(current, "app:" + ALPHA).isSelected());
                assertFalse("Provider shelf names do not clutter app settings", hasText((View) get(current, "root"), extra.displayName));
            });
            focus(scene, "app:" + ALPHA); assertTrue(device.pressDPadCenter());
            await(activity, "One toggle disables the complete app without losing its settings focus", current -> stable(current)
                    && !preferences.read().recommendationPackages.contains(ALPHA) && focused(current, "app:" + ALPHA));
            scene.onActivity(current -> {
                assertFalse(target(current, "app:" + ALPHA).isSelected());
                assertTrue("Other selected apps are unaffected", preferences.read().recommendationPackages.contains(BETA));
            });
            assertTrue(device.pressBack());
            await(activity, "Disabling the active app returns Home to personal Continue", current -> stable(current)
                    && "HOME".equals(current.currentScreenForTest()) && "".equals(get(current, "homeSource"))
                    && targets(current).containsKey("watch-" + CONTINUE));
            scene.onActivity(current -> {
                assertNull(targets(current).get("source:" + ALPHA)); assertNotNull(target(current, "source:" + BETA));
                for (ChannelRepository.Program program : alphaCards) assertNull(targets(current).get(cardKey(program)));
                assertNull(targets(current).get(cardKey(added)));
            });
            openSources(scene); focus(scene, "app:" + ALPHA); assertTrue(device.pressDPadCenter());
            await(activity, "The same app toggle enables all its available shelves", current -> stable(current)
                    && preferences.read().recommendationPackages.contains(ALPHA) && target(current, "app:" + ALPHA).isSelected());
            assertTrue(device.pressBack());
            await(activity, "Home receives the re-enabled app tab", current -> stable(current) && "HOME".equals(current.currentScreenForTest())
                    && targets(current).containsKey("source:" + ALPHA));
            scene.onActivity(current -> assertTrue("Toggling an app retains the chosen app order",
                    target(current, "source:" + ALPHA).getLeft() < target(current, "source:" + BETA).getLeft()));
            enterSource(scene, alpha);
            scene.onActivity(current -> { assertNotNull(target(current, cardKey(added))); assertLaunchBindings(current, ALPHA); });
            openSources(scene); focus(scene, "sources-enabled"); assertTrue(device.pressDPadCenter());
            await(activity, "The global switch still disables recommendations", current -> stable(current) && !preferences.read().recommendationsEnabled);
            assertTrue(device.pressBack());
            await(activity, "Global off preserves personal Continue on Home", current -> stable(current)
                    && "HOME".equals(current.currentScreenForTest()) && targets(current).containsKey("watch-" + CONTINUE));
            scene.onActivity(current -> {
                assertTrue(preferences.read().watchNextVisible); assertTrue(preferences.read().recommendationPackages.contains(ALPHA));
                assertEquals("Global off retains the app choices for the next enable", 2, preferences.read().recommendationPackages.size());
                assertNull(targets(current).get("source:" + ALPHA)); assertNull(targets(current).get("source:" + BETA));
                assertNotNull(target(current, "favorite:" + apps.get(0).id)); assertNotNull(tag(current, "weather-status"));
            });
        }
    }

    @Test public void recreateFromSourcesMigratesTheLegacyBackRouteToItsAppTab() {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = prepareFixture(scene); enterSource(scene, alpha); openSources(scene);
            scene.onActivity(current -> {
                @SuppressWarnings("unchecked") java.util.Deque<Object> routes = (java.util.Deque<Object>) get(current, "backStack");
                Object previous = routes.peekLast(); assertNotNull(previous);
                Object home = get(previous, "screen"); assertEquals("HOME", home.toString());
                String legacy = "source:" + alpha.key;
                Object route = construct(previous.getClass(), new Class<?>[]{home.getClass(), String.class}, home, legacy);
                routes.clear(); routes.addLast(route); set(current, "lastUpperFocus", legacy);
            });
            // Recreation must not start a real network request for the synthetic city.
            preferences.setWeatherEnabled(false);
            scene.recreate();
            MainActivity restored = owner(scene); assertNotSame(activity, restored);
            scene.onActivity(current -> ((RecommendationsSession) get(current, "recommendationsSession")).close());
            await(restored, "Recreated source settings settle before restoring provider fixtures", current -> stable(current)
                    && "SOURCES".equals(current.currentScreenForTest()));
            scene.onActivity(current -> assertEquals("The saved Home origin is normalized during recreation", "source:" + ALPHA, get(current, "lastUpperFocus")));
            installFixture(scene);
            await(restored, "The recreated source settings receive the immutable app fixture", HomeRecommendationsTest::stable);
            assertTrue(device.pressBack());
            await(restored, "Back restores the app tab recorded by the old channel route", current -> stable(current)
                    && "HOME".equals(current.currentScreenForTest()) && focused(current, "source:" + ALPHA));
            scene.onActivity(current -> {
                assertSame("A legacy source route must not fall back to favorites", target(current, "source:" + ALPHA), current.getCurrentFocus());
                assertEquals(ALPHA, get(current, "homeSource")); assertTrue(target(current, "source:" + ALPHA).isSelected());
            });
        }
    }

    private static int sourceTabCount(MainActivity activity) {
        int count = 0; for (String key : targets(activity).keySet()) if (key.startsWith("source:")) count++; return count;
    }
    private static void assertLaunchBindings(MainActivity activity, String packageName) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("visibleHomeCards"); method.setAccessible(true);
            for (Object card : (List<?>) method.invoke(activity)) {
                ChannelRepository.Program program = (ChannelRepository.Program) get(card, "program");
                ChannelRepository.Channel channel = (ChannelRepository.Channel) get(card, "channel");
                assertNotNull("An aggregated recommendation retains its original program", program);
                assertNotNull("An aggregated recommendation retains a launch owner", channel);
                assertEquals(packageName, program.packageName); assertEquals(packageName, channel.packageName);
                assertEquals("Launch validation receives this program's exact source channel", program.channelKey, channel.key);
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void openSources(ActivityScenario<MainActivity> scene) {
        focus(scene, "sources-open"); assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressDPadCenter());
        await(owner(scene), "The app source settings are reachable", current -> stable(current) && "SOURCES".equals(current.currentScreenForTest()));
    }

    private MainActivity prepareFixture(ActivityScenario<MainActivity> scene) {
        MainActivity activity = owner(scene);
        await(activity, "Initial repository work settles before fixture injection", HomeRecommendationsTest::stable);
        installFixture(scene);
        await(activity, "Synthetic tabs, cards, favorites and weather receive native layout", current -> stable(current)
                && targets(current).containsKey("source:" + BETA) && targets(current).containsKey("watch-" + CONTINUE));
        return activity;
    }

    private void installFixture(ActivityScenario<MainActivity> scene) {
        scene.onActivity(current -> {
            // The live session was disabled at launch; closing it also prevents later tab
            // configure calls from starting provider queries or overwriting these fixtures.
            ((RecommendationsSession) get(current, "recommendationsSession")).close();
            List<String> favorites = new ArrayList<>(); for (AppEntry app : apps) favorites.add(app.id);
            preferences.setFavorites(favorites);
            preferences.setRecommendations(true, Arrays.asList(ALPHA, BETA), Arrays.asList(ALPHA, BETA));
            preferences.setCity("TEST City", 55.75, 37.62, "fixture", "Europe/Moscow"); preferences.setWeatherEnabled(true);
            set(current, "prefs", preferences.read());
            set(current, "catalog", Collections.unmodifiableList(new ArrayList<>(apps)));
            @SuppressWarnings("unchecked") Map<String, AppEntry> byId = (Map<String, AppEntry>) get(current, "appsById");
            byId.clear(); for (AppEntry app : apps) byId.put(app.id, app);
            set(current, "continuation", Collections.singletonList(new WatchNextRepository.Entry(CONTINUE,
                    "TEST Continue", "Synthetic playback", context.getPackageName(),
                    new Intent(context, MainActivity.class).toUri(Intent.URI_INTENT_SCHEME), poster(0),
                    25, 100, System.currentTimeMillis(), true, true, WatchNextRepository.Kind.MOVIE)));
            set(current, "watchLoaded", true); set(current, "watchStatus", WatchNextRepository.Status.ITEMS);
            set(current, "weather", controlledWeather()); set(current, "nextWeatherRequestAt", Long.MAX_VALUE);
            String source = (String) get(current, "homeSource");
            set(current, "recommendations", fixtureSnapshot(Arrays.asList(alpha, beta), source.isEmpty() ? null : source));
            invoke(current, "renderPreservingFocus");
        });
    }

    private void publish(ActivityScenario<MainActivity> scene, List<ChannelRepository.Channel> channels) {
        scene.onActivity(current -> {
            String active = (String) get(current, "homeSource");
            set(current, "recommendations", fixtureSnapshot(channels, active.isEmpty() ? null : active));
            invoke(current, "scheduleDataRender");
        });
    }

    private void publishLoading(ActivityScenario<MainActivity> scene, ChannelRepository.Channel channel) {
        scene.onActivity(current -> {
            RecommendationsSession.Snapshot loading = fixtureSnapshot(Arrays.asList(alpha, beta), channel.packageName, true);
            assertTrue("The fixture represents a pending active query", loading.activeLoading);
            assertFalse("A cold source has no stale cached programs", loading.programsByPackage.containsKey(channel.packageName));
            set(current, "recommendations", loading);
            invoke(current, "scheduleDataRender");
        });
        await(owner(scene), "The pending source snapshot is presented before D-pad input", HomeRecommendationsTest::stable);
    }

    private RecommendationsSession.Snapshot fixtureSnapshot(List<ChannelRepository.Channel> channels, String active) {
        return fixtureSnapshot(channels, active, false);
    }
    private RecommendationsSession.Snapshot fixtureSnapshot(List<ChannelRepository.Channel> channels, String active, boolean loading) {
        // Construct the same immutable public value objects the renderer consumes, without
        // exposing a production fixture hook or weakening launch/owner validation.
        RecommendationsSession session = new RecommendationsSession(context, ignored -> { });
        try {
            session.configure(true, preferences.read().recommendationPackages, active);
            set(session, "discovery", construct(ChannelRepository.Discovery.class,
                    new Class<?>[]{ChannelRepository.Status.class, ChannelRepository.Completeness.class, List.class, List.class, List.class, ChannelRepository.Stats.class},
                    channels.isEmpty() ? ChannelRepository.Status.EMPTY : ChannelRepository.Status.READY,
                    ChannelRepository.Completeness.FULL, channels, channels, Collections.emptyList(), null));
            @SuppressWarnings("unchecked") Map<String, RecommendationsSession.AppPrograms> cache = (Map<String, RecommendationsSession.AppPrograms>) get(session, "programCache");
            cache.put(ALPHA, appResult(ALPHA, channels)); cache.put(BETA, appResult(BETA, channels));
            if (loading) cache.remove(active);
            set(session, "wantDiscovery", false); set(session, "wantActive", loading);
            set(session, "revision", ++revision);
            return session.snapshot();
        } finally { session.close(); }
    }

    private RecommendationsSession.AppPrograms appResult(String packageName, List<ChannelRepository.Channel> discovered) {
        List<ChannelRepository.Channel> channels = new ArrayList<>();
        for (ChannelRepository.Channel channel : discovered) if (packageName.equals(channel.packageName)) channels.add(channel);
        // Deliberately retain an old aggregate when discovery loses the whole app;
        // Main must reject it rather than presenting stale cards as available.
        if (channels.isEmpty()) channels.add(ALPHA.equals(packageName) ? alpha : beta);
        List<ChannelRepository.Programs> results = new ArrayList<>();
        for (ChannelRepository.Channel channel : channels) {
            List<ChannelRepository.Program> items = channel.key.equals(alpha.key) ? alphaCards
                    : channel.key.equals(beta.key) ? betaCards : additionalCards.getOrDefault(channel.key, Collections.emptyList());
            results.add(programResult(channel, items));
        }
        return RecommendationsSession.aggregatePrograms(packageName, channels, results, 0, false);
    }

    private ChannelRepository.Programs programResult(ChannelRepository.Channel channel, List<ChannelRepository.Program> items) {
        return construct(ChannelRepository.Programs.class,
                new Class<?>[]{ChannelRepository.Status.class, ChannelRepository.Completeness.class, ChannelRepository.Channel.class, List.class, ChannelRepository.Stats.class},
                items.isEmpty() ? ChannelRepository.Status.EMPTY : ChannelRepository.Status.READY,
                ChannelRepository.Completeness.FULL, channel, items, null);
    }
    private List<ChannelRepository.Program> programs(ChannelRepository.Channel channel, int count) {
        List<ChannelRepository.Program> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add(program(channel, i, "TEST " + channel.appLabel + " program " + i));
        return Collections.unmodifiableList(result);
    }
    private ChannelRepository.Program program(ChannelRepository.Channel channel, long id, String title) {
        return construct(ChannelRepository.Program.class,
                new Class<?>[]{ChannelRepository.Channel.class, long.class, String.class, String.class, String.class, String.class, Long.class, Integer.class, Integer.class, String.class},
                channel, id, title, "Synthetic recommendation", "https://example.invalid/program/" + id,
                poster((int) id), Long.valueOf(100 - id), Integer.valueOf(0), Integer.valueOf(0),
                id == 1 ? "Drama" : id == 3 ? "Documentary" : "");
    }
    private String poster(int index) { return "android.resource://" + context.getPackageName() + "/"
            + (index % 2 == 0 ? R.drawable.background_mist_forest : R.drawable.background_moon_coast); }
    private WeatherRepository.WeatherResult controlledWeather() {
        long now = System.currentTimeMillis(), first = (now / 3_600_000L + 1) * 3_600_000L;
        List<WeatherRepository.Hour> hours = new ArrayList<>();
        for (int i = 0; i < 7; i++) hours.add(new WeatherRepository.Hour(first + i * 3_600_000L, 8 + i, 3, true));
        WeatherRepository.Weather value = new WeatherRepository.Weather(8, 3, true, 10.8, now, now, "celsius",
                hours, 0, 0, "Europe/Moscow", WeatherRepository.Provider.OPEN_METEO, 0, Collections.emptyList());
        return new WeatherRepository(url -> "fixture", new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather weather) { }
        }, new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long fetched) { return value; }
        }, () -> now, millis -> fail("Synthetic weather never retries")).loadCurrent(preferences.read(), true);
    }

    private void enterSource(ActivityScenario<MainActivity> scene, ChannelRepository.Channel channel) {
        focus(scene, "source:" + channel.packageName); assertTrue(device.pressDPadDown());
        await(owner(scene), "Enter " + channel.appLabel, current -> stable(current)
                && focused(current, "watch-program:" + channel.key + "|1"));
    }
    private void moveRight(MainActivity activity, List<ChannelRepository.Program> cards, int index) {
        for (int i = 1; i <= index; i++) {
            final String key = cardKey(cards.get(i)); assertTrue(device.pressDPadRight());
            await(activity, "D-pad selects " + key, current -> stable(current) && focused(current, key));
        }
    }
    private void moveLeft(MainActivity activity, List<ChannelRepository.Program> cards, int first, int last) {
        for (int i = first; i >= last; i--) {
            final String key = cardKey(cards.get(i)); assertTrue(device.pressDPadLeft());
            await(activity, "D-pad returns to " + key, current -> stable(current) && focused(current, key));
        }
    }
    private static void select(MainActivity activity, String source, boolean enterCards) {
        invoke(activity, "selectHomeSource", new Class<?>[]{String.class, boolean.class}, source, enterCards);
    }
    private static String cardKey(ChannelRepository.Program program) { return "watch-program:" + program.key; }
    private static void assertRecommendationCards(MainActivity activity, List<ChannelRepository.Program> programs) {
        for (ChannelRepository.Program program : programs) {
            View card = target(activity, cardKey(program));
            assertNull("Recommendation has no playback position", card.findViewWithTag("watch-progress-track"));
            assertNull(card.findViewWithTag("watch-progress-fill"));
            assertNull("A card has no glass plate behind artwork and footer", card.getBackground());
            View footer = card.findViewWithTag("watch-footer"); assertNotNull(footer);
            assertNull("The title and service mark sit directly on the wallpaper", footer.getBackground());
            assertNull(footer.getForeground());
            assertNull("Program description is not part of the Home footer", card.findViewWithTag("watch-description"));
            assertTrue(card.getContentDescription().toString().contains(", recommendation"));
            assertFalse(card.getContentDescription().toString().contains("continue watching"));
        }
    }
    private static void assertGenre(MainActivity activity, ChannelRepository.Program program, boolean visible) {
        View card = target(activity, cardKey(program)), genre = card.findViewWithTag("watch-genre");
        if (visible) {
            assertSame("Genre belongs to the actual focused card", card, activity.getCurrentFocus());
            assertFalse("The fixture supplies a real genre value", program.genre.isEmpty());
            assertTrue(genre instanceof TextView);
            assertEquals(program.genre, ((TextView) genre).getText().toString());
            assertEquals(View.VISIBLE, genre.getVisibility());
            assertTrue("Genre remains below the artwork", bounds(genre).top >= bounds(transition(card, "watch-art")).bottom);
        } else assertTrue("Absent genre or focus leaves only title and logo", genre == null || genre.getVisibility() != View.VISIBLE);
    }
    private static void assertTabAppearance(MainActivity activity, String selected, String focused) {
        int selectedColor = ((TextView) tag(activity, "clock")).getCurrentTextColor();
        int idleColor = android.graphics.Color.red(selectedColor) < 128 ? 0xff66666e : 0xff96969e;
        for (Map.Entry<String, View> entry : targets(activity).entrySet()) {
            if (!entry.getKey().startsWith("source:")) continue;
            View tab = entry.getValue();
            assertNull("Tabs have no background plate", tab.getBackground());
            assertNull("Tabs have no decorative focus foreground", tab.getForeground());
            View underline = tab.findViewWithTag("source-focus-underline"); assertNotNull(underline);
            assertEquals("Underline represents focus, not the selected source", entry.getKey().equals(focused), underline.getVisibility() == View.VISIBLE);
            TextView caption = firstText(tab); assertNotNull(caption);
            assertEquals("Selected text follows the ambient color; other tabs retain fixed gray",
                    entry.getKey().equals(selected) ? selectedColor : idleColor, caption.getCurrentTextColor());
        }
    }
    private static TextView firstText(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = firstText(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static final class RowPosition {
        final String id; final int index, offset, anchor;
        RowPosition(MainActivity activity) {
            Object row = get(activity, "activeWatchState"); id = (String) get(row, "id");
            index = (Integer) get(row, "index"); anchor = (Integer) get(row, "anchor");
            offset = ((TvHorizontalScrollView) get(activity, "watchRail")).getScrollX();
            assertTrue("Exercise a genuinely scrolled source", offset > 60);
        }
        void assertRestored(MainActivity activity) {
            Object row = get(activity, "activeWatchState"); assertEquals(id, get(row, "id")); assertEquals(index, get(row, "index"));
            assertEquals(anchor, ((Integer) get(row, "anchor")).intValue(), 1);
            assertEquals(offset, ((TvHorizontalScrollView) get(activity, "watchRail")).getScrollX(), 1);
            assertEquals("The saved item keeps its fixed poster width when tabs have focus", 160, target(activity, "watch-" + id).getWidth());
        }
    }
    private static void assertSourcePosition(MainActivity activity, String source, RowPosition expected) {
        Object row = ((Map<?, ?>) get(activity, "sourceRows")).get(source); assertNotNull(row);
        assertEquals(expected.id, get(row, "id")); assertEquals(expected.index, get(row, "index"));
        assertEquals(expected.offset, ((Integer) get(row, "scrollX")).intValue(), 1);
        assertEquals(expected.anchor, ((Integer) get(row, "anchor")).intValue(), 1);
    }
    private static final class HomeSurface {
        final View root, dock, weather, forecast, focus; final List<View> favorites = new ArrayList<>();
        final int generation;
        HomeSurface(MainActivity activity) {
            root = (View) get(activity, "root"); dock = tag(activity, "home-dock");
            weather = tag(activity, "weather-status"); forecast = tag(activity, "three-day-forecast");
            assertTrue(weather instanceof CompactWeatherView); assertTrue(forecast instanceof ThreeDayForecastView);
            focus = activity.getCurrentFocus(); generation = (Integer) get(activity, "renderGeneration");
            for (int i = 0; i < 8; i++) favorites.add(tag(activity, "favorite-" + i));
        }
        void assertRetained(MainActivity activity) {
            assertSame(root, get(activity, "root")); assertEquals(generation, get(activity, "renderGeneration"));
            assertSame("Keep the existing glass dock", dock, tag(activity, "home-dock"));
            assertSame("Do not replace compact weather", weather, tag(activity, "weather-status"));
            assertSame("Do not replace the A4 forecast", forecast, tag(activity, "three-day-forecast"));
            assertSame("A source refresh cannot take focus from the dock", focus, activity.getCurrentFocus());
            for (int i = 0; i < favorites.size(); i++) assertSame(favorites.get(i), tag(activity, "favorite-" + i));
        }
    }
    private static boolean hasFocusReference(MainActivity activity, ChannelRepository.Program removed) {
        @SuppressWarnings("unchecked") Map<String, Integer> ids = (Map<String, Integer>) get(activity, "stableIds");
        Integer id = ids.get(cardKey(removed)); if (id == null) return false;
        for (View view : targets(activity).values()) if (view.getNextFocusLeftId() == id || view.getNextFocusRightId() == id
                || view.getNextFocusUpId() == id || view.getNextFocusDownId() == id) return true;
        return false;
    }
    private static void assertVisibleHero(MainActivity activity) {
        View focused = activity.getCurrentFocus(); assertNotNull(focused); assertEquals(160, focused.getWidth());
        RectF card = bounds(focused), rail = bounds((View) get(activity, "watchRail"));
        assertTrue("Selected program stays inside the native viewport", card.left >= rail.left - 2 && card.right <= rail.right + 2);
        assertTrue(card.top >= 0 && card.bottom <= 2160);
    }
    private static RectF bounds(View view) { Matrix matrix = new Matrix(); view.transformMatrixToGlobal(matrix);
        RectF frame = new RectF(0, 0, view.getWidth(), view.getHeight()); matrix.mapRect(frame); return frame; }
    private static boolean visiblePostersReady(MainActivity activity) {
        int visible = 0; Rect bounds = new Rect();
        for (Object item : (List<?>) get(activity, "watchTiles")) {
            View art = transition((View) item, "watch-art");
            if (art == null || !art.getGlobalVisibleRect(bounds) || bounds.width() < 4 || bounds.height() < 4) continue;
            visible++;
            if (!(art instanceof ImageView) || !(((ImageView) art).getDrawable() instanceof BitmapDrawable)) return false;
        }
        return visible >= 3;
    }
    private static View transition(View view, String name) {
        if (name.equals(view.getTransitionName())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = transition(((ViewGroup) view).getChildAt(i), name); if (found != null) return found;
        }
        return null;
    }
    private static boolean favoriteFocused(MainActivity activity) { View view = activity.getCurrentFocus();
        return view != null && String.valueOf(view.getTag()).startsWith("favorite-"); }
    private static boolean focused(MainActivity activity, String key) { View view = targets(activity).get(key);
        return view != null && view == activity.getCurrentFocus(); }
    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private static void focus(ActivityScenario<MainActivity> scene, String key) {
        scene.onActivity(current -> assertTrue(key, target(current, key).requestFocus()));
        await(owner(scene), "Focus reaches " + key, current -> stable(current) && focused(current, key));
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
        do { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return; SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> diagnostic = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View focus = activity.getCurrentFocus(); Object rail = get(activity, "watchRail");
            StringBuilder state = new StringBuilder("source=").append(get(activity, "homeSource"))
                    .append(" focus=").append(focus == null ? "null" : focus.getTag())
                    .append(" active=").append(rowDiagnostic(get(activity, "activeWatchState")))
                    .append(" railOffset=").append(rail instanceof TvHorizontalScrollView ? ((TvHorizontalScrollView) rail).getScrollX() : "none")
                    .append(" restoring=").append(get(activity, "restoringWatchRow"))
                    .append(" pendingEntry=").append(get(activity, "pendingSourceEntry"))
                    .append(" dirty=").append(get(activity, "renderDirty"))
                    .append(" generations=").append(get(activity, "renderGeneration")).append('/').append(get(activity, "homeContentGeneration"))
                    .append(" rows={");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) get(activity, "sourceRows")).entrySet())
                state.append(entry.getKey()).append('=').append(rowDiagnostic(entry.getValue())).append(';');
            diagnostic.set(state.append('}').toString());
        });
        fail(message + " | " + diagnostic.get());
    }
    private static String rowDiagnostic(Object row) { return row == null ? "null" : "[id=" + get(row, "id")
            + ",index=" + get(row, "index") + ",offset=" + get(row, "scrollX") + ",anchor=" + get(row, "anchor") + ']'; }
    private static void drainUi() { InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { }); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private static MainActivity owner(ActivityScenario<MainActivity> scene) { AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set); return owner.get(); }
    @SuppressWarnings("unchecked") private static Map<String, View> targets(MainActivity activity) { return (Map<String, View>) get(activity, "targets"); }
    private static View target(MainActivity activity, String key) { View result = targets(activity).get(key); assertNotNull(key, result); return result; }
    private static View tag(MainActivity activity, String key) { View result = ((View) get(activity, "root")).findViewWithTag(key); assertNotNull(key, result); return result; }
    private static void assertWindow(MainActivity activity) { assertEquals("Actual UI width, not HDMI mode", 3840, activity.getWindow().getDecorView().getWidth());
        assertEquals(2160, activity.getWindow().getDecorView().getHeight()); assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f); }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void set(Object owner, String name, Object value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity activity, String name) { invoke(activity, name, new Class<?>[0]); }
    private static void invoke(MainActivity activity, String name, Class<?>[] parameters, Object... values) {
        try { Method method = MainActivity.class.getDeclaredMethod(name, parameters); method.setAccessible(true); method.invoke(activity, values); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static <T> T construct(Class<T> type, Class<?>[] parameters, Object... values) {
        try { Constructor<T> constructor = type.getDeclaredConstructor(parameters); constructor.setAccessible(true); return constructor.newInstance(values); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(type.getSimpleName(), failure); }
    }
    private static Map<String, Object> preferenceSnapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) copy.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }
    private void capture(MainActivity activity, String name) {
        CountDownLatch committed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertWindow(activity);
            View root = (View) get(activity, "root");
            assertTrue("Native screenshots require hardware composition", root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            root.invalidate();
        });
        try { assertTrue("The synthetic artwork frame reaches the renderer before capture", committed.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue(device.takeScreenshot(new File(folder, name + ".png")));
    }
}
