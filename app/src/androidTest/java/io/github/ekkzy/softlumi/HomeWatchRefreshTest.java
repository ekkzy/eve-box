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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WatchNextRepository;
import io.github.ekkzy.softlumi.ui.TvHorizontalScrollView;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import java.io.File;
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

/** In-memory continuation fixtures only: never writes TV Provider or launches content. */
@RunWith(AndroidJUnit4.class)
public final class HomeWatchRefreshTest {
    private static final String FIXTURE_PACKAGE = "io.github.ekkzy.softlumi.fixture.alpha";
    private static final ComponentName FIXTURE = new ComponentName(FIXTURE_PACKAGE, "io.github.ekkzy.softlumi.fixture.FixtureActivity");
    private static final String A = "refresh-a", B = "refresh-b";
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;
    private int favoriteCount;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("The agreed emulator baseline is API 34", 34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals("Standard font only", 1f, context.getResources().getConfiguration().fontScale, .001f);
        assertTrue("The known alpha fixture must be installed and exported", context.getPackageManager().getActivityInfo(FIXTURE, 0).exported);
        List<AppEntry> apps = AppCatalog.load(context);
        assertFalse("Actual installed favorites are required", apps.isEmpty());
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : apps) if (favorites.size() < 8) favorites.add(app.id);
        favoriteCount = favorites.size();
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_POSTER); preferences.setBackground(0); preferences.setPaletteMode("dark");
        preferences.setLanguage("en"); preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(true);
        preferences.setSounds(false); preferences.setReducedMotion(false); preferences.setFavorites(favorites);
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
        assertTrue(editor.commit()); assertEquals("Restore all raw debug preferences", original, snapshot(storage));
    }

    @Test public void partialWatchUpdatesKeepReadyFavoritesAndRestoreStableSelectionThenFallback() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            await(activity, "Initial repository callbacks and Home settle before fixtures", this::stable);
            afterDraw(activity);
            AtomicReference<HomeSurface> surface = new AtomicReference<>();
            scene.onActivity(current -> {
                assertWindow(current); surface.set(new HomeSurface(current, favoriteCount));
                assertSame("Start on the real favorite row", tag(current, "favorite-0"), current.getCurrentFocus());
            });
            WatchNextRepository.Entry first = entry(A, "TEST A", 20, R.drawable.background_mist_forest);
            WatchNextRepository.Entry second = entry(B, "TEST B", 30, R.drawable.background_moon_coast);

            inject(scene, Arrays.asList(first, second));
            await(activity, "Two controlled cards receive layout and local poster callbacks", current ->
                    stable(current) && cardsReady(current, A, B) && providerMarksReady(current, A, B));
            afterDraw(activity);
            scene.onActivity(current -> {
                surface.get().assertRetained(current);
                assertVisible(tag(current, "watch-" + A)); assertVisible(tag(current, "watch-" + B));
                assertEquals(.30f, progressFraction(tag(current, "watch-" + B)), .02f);
                assertCurrentTargets(current, A, B);
                assertProviderFooter(current, A, "TEST A");
                assertProviderFooter(current, B, "TEST B");
            });
            captureControlledCards(activity);

            assertTrue(device.pressDPadUp());
            await(activity, "Real Up enters the first watch card", current -> stable(current) && focused(current, "watch-" + A));
            assertTrue(device.pressDPadRight());
            await(activity, "Real Right selects B and settles its row", current -> stable(current) && focused(current, "watch-" + B));
            AtomicReference<RowPosition> selected = new AtomicReference<>();
            AtomicReference<Set<View>> replaced = new AtomicReference<>();
            scene.onActivity(current -> { selected.set(new RowPosition(current)); replaced.set(watchDescendants(current)); });

            WatchNextRepository.Entry updated = entry(B, "TEST B refreshed", 75, R.drawable.background_moon_coast);
            inject(scene, Arrays.asList(first, updated));
            await(activity, "Same-ID update publishes new title/progress and restores B", current ->
                    stable(current) && cardsReady(current, A, B) && focused(current, "watch-" + B)
                            && hasText(tag(current, "watch-" + B), updated.title));
            afterDraw(activity);
            scene.onActivity(current -> {
                surface.get().assertRetained(current); selected.get().assertPreserved(current);
                View card = tag(current, "watch-" + B);
                assertTrue("Accessible name is refreshed too", card.getContentDescription().toString().startsWith(updated.title));
                assertEquals("The visible progress fill reflects the new position", .75f, progressFraction(card), .02f);
                assertNoStaleBindings(current, replaced.get()); assertCurrentTargets(current, A, B);
                replaced.set(watchDescendants(current));
            });
            AtomicReference<Integer> removedB = new AtomicReference<>();
            scene.onActivity(current -> removedB.set(tag(current, "watch-" + B).getId()));

            inject(scene, Collections.singletonList(first));
            await(activity, "Removing selected B falls back to A through production focus restoration", current ->
                    stable(current) && cardsReady(current, A) && focused(current, "watch-" + A)
                            && peek(current, "watch-" + B) == null);
            afterDraw(activity);
            AtomicReference<Integer> removedA = new AtomicReference<>();
            scene.onActivity(current -> {
                surface.get().assertRetained(current); assertCurrentTargets(current, A);
                assertEquals(A, field(get(current, "activeWatchState"), "id"));
                assertEquals(0, ((Integer) field(get(current, "activeWatchState"), "index")).intValue());
                assertNoStaleBindings(current, replaced.get()); assertNoFocusLinksTo(current, removedB.get());
                assertNull("Removed B has no focus target", targets(current).get("watch-" + B));
                replaced.set(watchDescendants(current)); removedA.set(tag(current, "watch-" + A).getId());
            });

            inject(scene, Collections.emptyList());
            await(activity, "Empty continuation restores the real favorite without a stale card", current ->
                    stable(current) && focused(current, "favorite-0") && watchTiles(current).isEmpty()
                            && peek(current, "watch-" + A) == null && peek(current, "watch-" + B) == null);
            afterDraw(activity);
            scene.onActivity(current -> {
                surface.get().assertRetained(current); assertCurrentTargets(current);
                assertNull("An empty source has no stale rail", get(current, "watchRail"));
                assertTrue("Honest EMPTY presentation replaces the old cards", hasText((View) get(current, "root"), "No available items to continue watching"));
                assertNoStaleBindings(current, replaced.get());
                assertNoFocusLinksTo(current, removedA.get()); assertNoFocusLinksTo(current, removedB.get());
                assertSame(surface.get().favorites.get(0), current.getCurrentFocus());
            });
        }
    }

    @Test public void removingSelectedCardWhileDockFocusedKeepsFallbackHeroAndRowAnchor() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            await(activity, "Initial Home settles before the controlled row", this::stable);
            String[] ids = {A, B, "refresh-c", "refresh-d", "refresh-e", "refresh-f", "refresh-g", "refresh-h"};
            List<WatchNextRepository.Entry> entries = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) entries.add(entry(ids[i], "TEST " + (i + 1), 20 + i, R.drawable.background_mist_forest));
            inject(scene, entries);
            await(activity, "The controlled row receives native layout and artwork", current -> stable(current) && cardsReady(current, ids));
            assertTrue(device.pressDPadUp());
            await(activity, "Up enters the first card", current -> stable(current) && focused(current, "watch-" + ids[0]));
            // Visit a later card first so the third card has a real, nonzero scroll anchor.
            for (int i = 1; i <= 5; i++) {
                final String id = ids[i]; assertTrue(device.pressDPadRight());
                await(activity, "Right settles " + id, current -> stable(current) && focused(current, "watch-" + id));
            }
            for (int i = 4; i >= 2; i--) {
                final String id = ids[i]; assertTrue(device.pressDPadLeft());
                await(activity, "Left restores " + id, current -> stable(current) && focused(current, "watch-" + id));
            }
            assertTrue(device.pressDPadDown());
            await(activity, "Down leaves the selected third card for the real dock", current ->
                    stable(current) && current.getCurrentFocus() != null
                            && String.valueOf(current.getCurrentFocus().getTag()).startsWith("favorite-"));
            afterDraw(activity);
            AtomicReference<HomeSurface> surface = new AtomicReference<>();
            AtomicReference<View> dockFocus = new AtomicReference<>();
            AtomicReference<RectF> previousBounds = new AtomicReference<>();
            AtomicReference<Set<View>> replaced = new AtomicReference<>();
            int[] previous = new int[2];
            scene.onActivity(current -> {
                surface.set(new HomeSurface(current, favoriteCount)); dockFocus.set(current.getCurrentFocus());
                assertEquals(ids[2], field(get(current, "activeWatchState"), "id"));
                assertEquals(2, ((Integer) field(get(current, "activeWatchState"), "index")).intValue());
                previous[0] = ((TvHorizontalScrollView) get(current, "watchRail")).getScrollX();
                previous[1] = (Integer) field(get(current, "activeWatchState"), "anchor");
                assertTrue("Exercise a genuinely scrolled row", previous[0] > 0);
                previousBounds.set(bounds(tag(current, "watch-" + ids[2]))); replaced.set(watchDescendants(current));
            });

            entries.remove(2);
            String[] remaining = {ids[0], ids[1], ids[3], ids[4], ids[5], ids[6], ids[7]};
            inject(scene, entries);
            await(activity, "The replacement row settles without moving dock focus", current ->
                    stable(current) && cardsReady(current, remaining) && current.getCurrentFocus() == dockFocus.get());
            afterDraw(activity);
            scene.onActivity(current -> {
                surface.get().assertRetained(current); assertCurrentTargets(current, remaining);
                assertNoStaleBindings(current, replaced.get()); assertNull(peek(current, "watch-" + ids[2]));
                assertSame("Refreshing the shelf preserves the exact dock focus", dockFocus.get(), current.getCurrentFocus());
                Object state = get(current, "activeWatchState");
                assertEquals("The next surviving card takes the removed card's index", ids[3], field(state, "id"));
                assertEquals(2, ((Integer) field(state, "index")).intValue());
                View fallback = tag(current, "watch-" + ids[3]);
                assertEquals("The saved fallback is already the hero before re-entering the row", 567, fallback.getWidth());
                assertEquals("The first card must not become a competing hero", 182, tag(current, "watch-" + ids[0]).getWidth());
                TvHorizontalScrollView rail = (TvHorizontalScrollView) get(current, "watchRail");
                assertEquals("A surviving anchor preserves the scroll offset", previous[0], rail.getScrollX(), 1);
                assertEquals(previous[1], ((Integer) field(state, "anchor")).intValue(), 1);
                RectF actual = bounds(fallback), viewport = bounds(rail);
                assertEquals("The replacement occupies the removed hero's visible position", previousBounds.get().left, actual.left, 2f);
                assertTrue("The fallback hero remains fully within its viewport", actual.left >= viewport.left - 2f && actual.right <= viewport.right + 2f);
            });
        }
    }

    private WatchNextRepository.Entry entry(String id, String title, int progress, int poster) {
        String intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(FIXTURE_PACKAGE).setComponent(FIXTURE).putExtra("softlumiWatchFixture", id).toUri(Intent.URI_INTENT_SCHEME);
        return new WatchNextRepository.Entry(id, title, "Controlled continuation test", FIXTURE_PACKAGE, intent,
                "android.resource://" + context.getPackageName() + "/" + poster,
                progress, 100, System.currentTimeMillis(), true, true, WatchNextRepository.Kind.MOVIE);
    }

    private static void inject(ActivityScenario<MainActivity> scene, List<WatchNextRepository.Entry> entries) {
        scene.onActivity(current -> {
            Future<?> load = (Future<?>) get(current, "loadFuture");
            assertTrue("No late provider callback may overwrite the controlled data", load != null && load.isDone());
            set(current, "continuation", Collections.unmodifiableList(new ArrayList<>(entries)));
            set(current, "watchLoaded", true);
            set(current, "watchStatus", entries.isEmpty() ? WatchNextRepository.Status.EMPTY : WatchNextRepository.Status.ITEMS);
            invoke(current, "scheduleDataRender");
        });
    }

    private final class HomeSurface {
        final View root;
        final int generation;
        final List<View> favorites = new ArrayList<>();
        final List<ImageView> images = new ArrayList<>();
        final List<Drawable> artwork = new ArrayList<>();
        HomeSurface(MainActivity activity, int count) {
            root = (View) get(activity, "root"); generation = (Integer) get(activity, "renderGeneration");
            for (int i = 0; i < count; i++) {
                View favorite = tag(activity, "favorite-" + i); ImageView image = icon(favorite);
                favorites.add(favorite); images.add(image); artwork.add(image.getDrawable());
            }
        }
        void assertRetained(MainActivity activity) {
            assertWindow(activity); assertSame("Watch refresh retains the whole Home surface", root, get(activity, "root"));
            assertEquals("No hidden full-page render", generation, ((Integer) get(activity, "renderGeneration")).intValue());
            for (int i = 0; i < favorites.size(); i++) {
                assertSame(favorites.get(i), tag(activity, "favorite-" + i));
                assertSame(images.get(i), icon(favorites.get(i)));
                assertSame("Ready app artwork is not rebound by watch updates", artwork.get(i), images.get(i).getDrawable());
                assertTrue(artwork.get(i) instanceof BitmapDrawable);
                assertFalse(((BitmapDrawable) artwork.get(i)).getBitmap().isRecycled());
            }
            assertEquals("Exactly one native View owns focus", 1, countFocused(activity.getWindow().getDecorView()));
            assertVisible(activity.getCurrentFocus());
        }
    }

    private static final class RowPosition {
        final Object state;
        final int index, offset, anchor;
        final RectF bounds;
        RowPosition(MainActivity activity) {
            state = get(activity, "activeWatchState"); assertNotNull(state);
            assertEquals(B, field(state, "id")); index = (Integer) field(state, "index");
            offset = ((TvHorizontalScrollView) get(activity, "watchRail")).getScrollX(); anchor = (Integer) field(state, "anchor");
            bounds = bounds(tag(activity, "watch-" + B));
        }
        void assertPreserved(MainActivity activity) {
            Object current = get(activity, "activeWatchState");
            assertSame("Same row state owns the refreshed selection", state, current);
            assertEquals(B, field(current, "id")); assertEquals(index, ((Integer) field(current, "index")).intValue());
            assertEquals("Same-ID card keeps its row offset", offset, ((TvHorizontalScrollView) get(activity, "watchRail")).getScrollX(), 1);
            assertEquals("Same-ID card keeps its anchor", anchor, ((Integer) field(current, "anchor")).intValue(), 1);
            RectF actual = bounds(tag(activity, "watch-" + B));
            assertEquals("Selected card does not jump horizontally", bounds.left, actual.left, 2f);
            assertEquals("Selected card does not jump vertically", bounds.top, actual.top, 2f);
        }
    }

    private boolean stable(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        if (!(Boolean) get(activity, "resumed") || !activity.hasWindowFocus() || root == null || !root.isLaidOut()
                || root.isLayoutRequested() || !(Boolean) get(activity, "catalogLoaded") || load == null || !load.isDone()
                || (Boolean) get(activity, "renderDirty") || (Boolean) get(activity, "restoringWatchRow")
                || ((UiMotion) get(activity, "uiMotion")).isRunning()) return false;
        Object rail = get(activity, "watchRail");
        if (rail instanceof TvHorizontalScrollView && ((TvHorizontalScrollView) rail).isScrollAnimating()) return false;
        Object scroll = get(activity, "homeScroll");
        if (scroll instanceof TvScrollView && ((TvScrollView) scroll).isScrollAnimating()) return false;
        for (int i = 0; i < favoriteCount; i++) {
            View favorite = peek(activity, "favorite-" + i); if (favorite == null) return false;
            Drawable image = icon(favorite).getDrawable(); if (!(image instanceof BitmapDrawable)) return false;
        }
        return true;
    }
    private static boolean cardsReady(MainActivity activity, String... ids) {
        if (watchTiles(activity).size() != ids.length) return false;
        for (String id : ids) {
            View card = peek(activity, "watch-" + id);
            if (!(card instanceof ViewGroup) || !card.isLaidOut() || card.isLayoutRequested()) return false;
            View art = ((ViewGroup) card).getChildAt(0);
            if (!(art instanceof ImageView) || !(((ImageView) art).getDrawable() instanceof BitmapDrawable)) return false;
        }
        return true;
    }
    private static boolean providerMarksReady(MainActivity activity, String... ids) {
        for (String id : ids) {
            View card = peek(activity, "watch-" + id);
            View badge = card == null ? null : card.findViewWithTag("provider:" + FIXTURE_PACKAGE);
            if (!(badge instanceof ViewGroup) || !(icon(badge).getDrawable() instanceof BitmapDrawable)) return false;
        }
        return true;
    }
    private static float progressFraction(View card) {
        // The footer now follows the poster: locate the actual progress controls by role.
        View track = card.findViewWithTag("watch-progress-track"), fill = card.findViewWithTag("watch-progress-fill");
        assertNotNull("Known playback has a visible progress track", track);
        assertNotNull("Positive playback has a visible progress fill", fill);
        assertTrue("A laid out progress track is required", track.getWidth() > 0);
        assertEquals(track.getTop(), fill.getTop());
        return fill.getWidth() / (float) track.getWidth();
    }
    private static void assertCurrentTargets(MainActivity activity, String... ids) {
        Set<String> expected = new HashSet<>(); for (String id : ids) expected.add("watch-" + id);
        Set<String> actual = new HashSet<>(); View root = (View) get(activity, "root");
        for (Map.Entry<String, View> entry : targets(activity).entrySet()) {
            assertTrue("Every current target remains attached: " + entry.getKey(), entry.getValue().isAttachedToWindow());
            assertTrue("Every target belongs to the current Home", inside(entry.getValue(), root));
            if (entry.getKey().startsWith("watch-")) actual.add(entry.getKey());
        }
        assertEquals("Removed cards cannot remain in the target graph", expected, actual);
        assertEquals(1, countFocused(activity.getWindow().getDecorView()));
    }
    private static void assertProviderFooter(MainActivity activity, String id, String expectedTitle) {
        ViewGroup card = (ViewGroup) tag(activity, "watch-" + id);
        View art = card.getChildAt(0);
        View badge = card.findViewWithTag("provider:" + FIXTURE_PACKAGE);
        TextView title = findText(card, expectedTitle);
        assertNotNull("The controlled title remains visible: " + expectedTitle, title);
        assertEquals(expectedTitle, title.getText().toString());
        assertVisible(art); assertVisible(badge); assertVisible(title);
        RectF artworkBounds = bounds(art), badgeBounds = bounds(badge), titleBounds = bounds(title);
        assertTrue("The title stays below the poster", titleBounds.top >= artworkBounds.bottom);
        assertTrue("The provider mark stays below the poster", badgeBounds.top >= artworkBounds.bottom);
        assertTrue("The title and provider mark do not overlap", titleBounds.right <= badgeBounds.left);
        ImageView mark = icon(badge);
        assertVisible(mark); assertTrue("The actual installed provider mark has loaded", mark.getDrawable() instanceof BitmapDrawable);
        assertFalse("The provider mark retains live pixels", ((BitmapDrawable) mark.getDrawable()).getBitmap().isRecycled());
        RectF markBounds = bounds(mark);
        assertTrue("The mark has a real display size", mark.getWidth() > 0 && mark.getHeight() > 0);
        assertTrue("The provider mark fits its footer slot", badgeBounds.contains(markBounds));
        AppEntry providerApp = null;
        for (Object entry : (List<?>) get(activity, "catalog")) {
            AppEntry app = (AppEntry) entry;
            if (FIXTURE_PACKAGE.equals(app.packageName)) { providerApp = app; break; }
        }
        assertNotNull("The installed fixture belongs to the actual app catalog", providerApp);
        String provider = providerApp.label;
        assertFalse("Installed provider has an accessible name", provider.isEmpty());
        assertNotNull("The card retains its accessible name", card.getContentDescription());
        assertTrue("Accessible name contains its actual title", card.getContentDescription().toString().startsWith(expectedTitle));
        assertTrue("Accessible name contains its provider", card.getContentDescription().toString().contains(provider));
    }
    private void captureControlledCards(MainActivity activity) throws Exception {
        await(activity, "Both controlled cards and provider marks settle before capture", current -> stable(current)
                && cardsReady(current, A, B) && providerMarksReady(current, A, B) && ((Set<?>) get(current, "pendingLogos")).isEmpty());
        afterDraw(activity);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("Capture only after both native cards and Home settle", stable(activity) && cardsReady(activity, A, B));
            assertWindow(activity);
            assertSame("Capture the upper Home before entering a watch card", tag(activity, "favorite-0"), activity.getCurrentFocus());
            assertEquals(0, ((TvScrollView) get(activity, "homeScroll")).getScrollY());
        });
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "home-watch-controlled");
        assertTrue(run.matches("[A-Za-z0-9_-]+"));
        File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue("Save the actual 4K Home with controlled TEST A/B cards",
                device.takeScreenshot(new File(folder, "home-watch-controlled-two-cards.png")));
    }
    private static Set<View> watchDescendants(MainActivity activity) {
        Set<View> views = new HashSet<>(); for (View card : watchTiles(activity)) collect(card, views); return views;
    }
    private static void collect(View view, Set<View> views) {
        views.add(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), views);
    }
    private static void assertNoStaleBindings(MainActivity activity, Set<View> removed) {
        for (View view : removed) assertFalse("Replaced card subtree is detached", view.isAttachedToWindow());
        for (View view : targets(activity).values()) assertFalse(removed.contains(view));
        for (String name : new String[]{"focusKeys", "idleTints", "posterBindings", "providerLogos"})
            for (Object view : ((Map<?, ?>) get(activity, name)).keySet()) assertFalse("No stale " + name + " target", removed.contains(view));
        for (String name : new String[]{"pendingPosters", "pendingLogos"})
            for (Object view : (Set<?>) get(activity, name)) assertFalse("No stale " + name + " delivery", removed.contains(view));
    }
    private static void assertNoFocusLinksTo(MainActivity activity, int removedId) {
        for (View target : targets(activity).values()) {
            assertNotEquals("No left link to a removed card", removedId, target.getNextFocusLeftId());
            assertNotEquals("No right link to a removed card", removedId, target.getNextFocusRightId());
            assertNotEquals("No up link to a removed card", removedId, target.getNextFocusUpId());
            assertNotEquals("No down link to a removed card", removedId, target.getNextFocusDownId());
        }
    }
    private static void afterDraw(MainActivity activity) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<ViewTreeObserver> observer = new AtomicReference<>();
        AtomicReference<ViewTreeObserver.OnPreDrawListener> callback = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView(); observer.set(decor.getViewTreeObserver());
            callback.set(() -> { observer.get().removeOnPreDrawListener(callback.get()); frame.countDown(); return true; });
            observer.get().addOnPreDrawListener(callback.get()); decor.postInvalidateOnAnimation();
        });
        try { assertTrue("A native layout/draw must follow the data callback", frame.await(4, TimeUnit.SECONDS)); }
        finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (observer.get().isAlive()) observer.get().removeOnPreDrawListener(callback.get());
            });
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static boolean hasText(View view, String value) {
        return findText(view, value) != null;
    }
    private static TextView findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static void assertVisible(View view) { assertNotNull(view); assertTrue(view.isShown()); assertTrue(view.getGlobalVisibleRect(new Rect())); }
    private static RectF bounds(View view) { Matrix matrix = new Matrix(); view.transformMatrixToGlobal(matrix); RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight()); matrix.mapRect(bounds); return bounds; }
    private static void assertWindow(MainActivity activity) {
        assertEquals(3840, activity.getWindow().getDecorView().getWidth()); assertEquals(2160, activity.getWindow().getDecorView().getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static ImageView icon(View favorite) {
        ViewGroup group = (ViewGroup) favorite;
        for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i) instanceof ImageView) return (ImageView) group.getChildAt(i);
        throw new AssertionError("Favorite has no ImageView: " + favorite.getTag());
    }
    private static boolean focused(MainActivity activity, String key) { View view = peek(activity, key); return view != null && view == activity.getCurrentFocus(); }
    private static View peek(MainActivity activity, String key) { return ((View) get(activity, "root")).findViewWithTag(key); }
    private static View tag(MainActivity activity, String key) { View view = peek(activity, key); assertNotNull(key, view); return view; }
    private static boolean inside(View view, View root) { for (View current = view; current != null;) { if (current == root) return true; ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null; } return false; }
    @SuppressWarnings("unchecked") private static Map<String, View> targets(MainActivity activity) { return (Map<String, View>) get(activity, "targets"); }
    @SuppressWarnings("unchecked") private static List<View> watchTiles(MainActivity activity) { return (List<View>) get(activity, "watchTiles"); }
    private static Object get(MainActivity activity, String name) { return field(activity, name); }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
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
    private static int countFocused(View view) { int count = view.isFocused() ? 1 : 0; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countFocused(((ViewGroup) view).getChildAt(i)); return count; }
    private static void await(MainActivity activity, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 10000; AtomicBoolean done = new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(condition.test(activity)));
            if (done.get()) return; SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> state = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> state.set("screen=" + activity.currentScreenForTest() + ",dirty=" + get(activity, "renderDirty")
                + ",focus=" + (activity.getCurrentFocus() == null ? "null" : activity.getCurrentFocus().getTag()) + ",watchTiles=" + watchTiles(activity).size()));
        fail(message + "; " + state.get());
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) { Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value); }
        return copy;
    }
}
