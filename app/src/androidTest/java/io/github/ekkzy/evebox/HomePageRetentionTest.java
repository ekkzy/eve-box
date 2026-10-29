package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.data.WallpaperCatalog;
import io.github.ekkzy.evebox.data.WatchNextRepository;
import io.github.ekkzy.evebox.ui.SharedRowsSheet;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import io.github.ekkzy.evebox.ui.UiImageLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Completed detached Home returns only; entry reversal remains in PageTransitionRenderingTest. */
@RunWith(AndroidJUnit4.class)
public final class HomePageRetentionTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private LauncherPrefs preferences;
    private Map<String, Object> original;
    private int favoriteCount;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.evebox.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals("Agreed emulator API", 34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(ValueAnimator.areAnimatorsEnabled());
            assertEquals("The default cases observe real completed fades", 1f, ValueAnimator.getDurationScale(), .001f);
        });
        List<AppEntry> apps = AppCatalog.load(context);
        assertTrue("At least two installed launchable apps are required", apps.size() >= 2);
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : apps) if (favorites.size() < 2) favorites.add(app.id);
        favoriteCount = favorites.size();
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); preferences.setBackground(WallpaperCatalog.AMBER_GALLERY);
        preferences.setPaletteMode("auto"); preferences.setLanguage("en");
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false);
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
        assertTrue(editor.commit()); assertEquals("Restore every debug preference", original, snapshot(storage));
    }

    @Test public void completedGalleryAndSettingsReturnsReuseOnlyOneDetachedHomeWithReadyArtwork() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene);
            for (String destination : new String[]{"GALLERY", "SETTINGS"}) {
                HomeSnapshot home = openFromHome(scene, activity, destination);
                AtomicReference<View> outgoing = new AtomicReference<>();
                scene.onActivity(current -> {
                    assertDetachedHome(current, home); outgoing.set(root(current));
                    assertNotSame(home.root, outgoing.get());
                });
                assertTrue("Real Back returns from " + destination, device.pressBack());
                awaitPage(activity, "HOME");
                scene.onActivity(current -> {
                    home.assertRestored(current); assertSingleAttachedPage(current);
                    assertNull("Completed non-Home exits own no saved page", get(current, "retainedPage"));
                    assertNull(outgoing.get().getParent()); assertNoHardwareLayer(outgoing.get());
                });
            }
        }
    }

    @Test public void galleryWallpaperAndPersistedPaletteChangesRebuildHomeWithTheNewAppearance() throws Exception {
        preferences.setBackground(WallpaperCatalog.DEFAULT_ID);
        preferences.setWallpaperPeriod("night");
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene);
            HomeSnapshot amber = openFromHome(scene, activity, "GALLERY");
            scene.onActivity(current -> {
                assertDetachedHome(current, amber);
                assertTrue(tag(current, "background-" + WallpaperCatalog.AMBER_GALLERY).requestFocus());
            });
            awaitFrames(activity, "The canonical gallery pair is focused, visible and decoded", current -> settled(current)
                    && focused(current, "background-" + WallpaperCatalog.AMBER_GALLERY)
                    && bitmapReady(tag(current, "background-" + WallpaperCatalog.AMBER_GALLERY))
                    && !galleryScroll(current).isScrollAnimating());
            scene.onActivity(current -> {
                assertFalse("The retained initial night Home uses dark Auto ink", (Boolean) field(get(current, "palette"), "light"));
                // A pair no longer changes light policy by its old individual DAY ID.
                // Persist a real period change before applying the different pair.
                preferences.setWallpaperPeriod("day");
            });
            assertTrue("Real Center changes wallpaper inside Gallery", device.pressDPadCenter());
            awaitFrames(activity, "Gallery applies the new wallpaper and its Auto light palette", current -> pageSettled(current, "GALLERY")
                    && ((LauncherPrefs.Settings) get(current, "prefs")).background == WallpaperCatalog.AMBER_GALLERY
                    && (Boolean) field(get(current, "palette"), "light"));
            assertTrue(device.pressBack()); awaitPage(activity, "HOME");
            scene.onActivity(current -> {
                assertRebuiltHome(current, amber); assertAppearance(current, WallpaperCatalog.AMBER_GALLERY, "auto", true);
            });

            HomeSnapshot ivory = openFromHome(scene, activity, "GALLERY");
            scene.onActivity(current -> {
                assertDetachedHome(current, ivory);
                // Controlled persisted input while Gallery stays active; Back must
                // validate current settings instead of trusting the saved Home ink.
                preferences.setPaletteMode("dark");
                assertEquals("GALLERY", current.currentScreenForTest());
            });
            assertTrue(device.pressBack()); awaitPage(activity, "HOME");
            scene.onActivity(current -> {
                assertRebuiltHome(current, ivory); assertAppearance(current, WallpaperCatalog.AMBER_GALLERY, "dark", false);
            });
        }
    }

    /** Callback policy only: does not claim OS memory pressure or collection of the Activity. */
    @Test public void trimPauseAndDestroyReleaseTheDetachedHomeAndLeaveUsableNavigation() throws Exception {
        ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class);
        try {
            MainActivity activity = owner(scene);
            HomeSnapshot trimmed = openFromHome(scene, activity, "GALLERY");
            scene.onActivity(current -> {
                assertDetachedHome(current, trimmed);
                View gallery = root(current), selection = current.getCurrentFocus();
                current.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
                assertReleased(current, trimmed);
                assertSame("Trimming the hidden Home does not rebuild active Gallery", gallery, root(current));
                assertSame(selection, current.getCurrentFocus()); assertSingleAttachedPage(current);
                assertTrue("Bound gallery artwork remains available after cache eviction", bitmapReady(selection));
            });
            assertTrue(device.pressBack()); awaitPage(activity, "HOME");
            scene.onActivity(current -> assertRebuiltHome(current, trimmed));

            HomeSnapshot paused = openFromHome(scene, activity, "SETTINGS");
            scene.onActivity(current -> assertDetachedHome(current, paused));
            scene.moveToState(Lifecycle.State.STARTED);
            scene.onActivity(current -> {
                assertFalse("The framework delivered onPause", (Boolean) get(current, "resumed"));
                assertReleased(current, paused); assertEquals(1, host(current).getChildCount());
            });
            scene.moveToState(Lifecycle.State.RESUMED);
            awaitPage(activity, "SETTINGS");
            assertTrue(device.pressBack()); awaitPage(activity, "HOME");
            scene.onActivity(current -> assertRebuiltHome(current, paused));

            HomeSnapshot destroyed = openFromHome(scene, activity, "GALLERY");
            scene.onActivity(current -> assertDetachedHome(current, destroyed));
            scene.moveToState(Lifecycle.State.DESTROYED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue("The framework delivered onDestroy", (Boolean) get(activity, "destroyed"));
                assertReleased(activity, destroyed);
            });
        } finally { scene.close(); }
    }

    @Test public void reducedMotionReturnReattachesTheSameHomeAfterNativeLayoutWithoutAnExitLayer() throws Exception {
        preferences.setReducedMotion(true);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene);
            HomeSnapshot home = openFromHome(scene, activity, "GALLERY");
            scene.onActivity(current -> {
                assertTrue(((LauncherPrefs.Settings) get(current, "prefs")).reducedMotion);
                assertDetachedHome(current, home);
            });
            assertTrue(device.pressBack()); awaitPage(activity, "HOME");
            scene.onActivity(current -> {
                home.assertRestored(current); assertSingleAttachedPage(current);
                assertNull(get(current, "retainedPage")); assertNull(get(current, "layeredHome"));
                assertFalse("The returned root completed native layout", root(current).isLayoutRequested());
            });
        }
    }

    @Test public void restoredHomeCancelsQueuedPosterBeforeHiddenRailCanReceiveItsBitmap() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            MainActivity activity = owner(scene);
            awaitFrames(activity, "Warm Home artwork before pausing its decoder", current -> homeArtworkReady(current)
                    && ((UiImageLoader) get(current, "imageLoader")).diagnostics().jobs == 0
                    && ((UiImageLoader) get(current, "imageLoader")).diagnostics().running == 0);
            UiImageLoader loader = (UiImageLoader) get(activity, "imageLoader");
            String posterUri = "android.resource://" + context.getPackageName() + "/" + R.drawable.background_calm_graphite_mist;
            String id = "TEST-retained-poster";
            WatchNextRepository.Entry entry = new WatchNextRepository.Entry(id, "TEST queued local poster", "Controlled fixture",
                    context.getPackageName(), new Intent(context, MainActivity.class).toUri(Intent.URI_INTENT_SCHEME),
                    posterUri, 0, 100, System.currentTimeMillis(), true, true, WatchNextRepository.Kind.MOVIE);
            scene.onActivity(current -> {
                loader.pause();
                // The initial repository run is finished. This in-memory row makes
                // no TV Provider writes, network requests or playback launches.
                preferences.setWatchNextVisible(true);
                set(current, "continuation", Collections.singletonList(entry));
                set(current, "watchLoaded", true); set(current, "watchStatus", WatchNextRepository.Status.ITEMS);
                invoke(current, "render");
            });
            awaitFrames(activity, "The visible fixture queues its missing poster without decoding", current ->
                    homeArtworkReady(current) && root(current).findViewWithTag("watch-" + id) != null
                            && firstImage(tag(current, "watch-" + id)).isLaidOut()
                            && posterReceiverCount(loader, firstImage(tag(current, "watch-" + id))) == 1);

            AtomicReference<View> savedHome = new AtomicReference<>();
            AtomicReference<ImageView> poster = new AtomicReference<>();
            int[] request = new int[2];
            scene.onActivity(current -> {
                assertTrue(tag(current, "favorite-1").requestFocus()); scroll(current).scrollTo(0, 0);
                savedHome.set(root(current)); poster.set(firstImage(tag(current, "watch-" + id)));
                assertNull(poster.get().getDrawable());
                Object binding = ((Map<?, ?>) get(current, "posterBindings")).get(poster.get());
                float scale = ((View) get(current, "designRoot")).getScaleX();
                request[0] = Math.round((Integer) field(binding, "width") * scale);
                request[1] = Math.round((Integer) field(binding, "height") * scale);
                // Invoke the existing Settings action while preserving the upper
                // viewport. Back and the subsequent Down use real remote input.
                assertTrue(tag(current, "shortcut-launcher").performClick());
            });
            awaitFrames(activity, "Settings retains the exact upper Home", current -> pageSettled(current, "SETTINGS")
                    && get(current, "retainedPage") != null
                    && field(get(current, "retainedPage"), "root") == savedHome.get()
                    && savedHome.get().getParent() == null);
            assertTrue(device.pressBack());
            awaitFrames(activity, "Back reattaches Home and queues one poster receiver", current -> pageSettled(current, "HOME")
                    && root(current) == savedHome.get() && scroll(current).getScrollY() == 0
                    && focused(current, "favorite-1") && posterReceiverCount(loader, poster.get()) == 1);
            scene.onActivity(current -> {
                assertNull("The paused loader keeps the restored poster missing", poster.get().getDrawable());
                assertEquals(0, loader.diagnostics().queued); assertEquals(0, loader.diagnostics().running);
            });

            assertTrue(device.pressDPadDown());
            awaitFrames(activity, "Down hides the whole rail in the lower viewport", current -> pageSettled(current, "HOME")
                    && focused(current, "shortcut-launcher") && scroll(current).getScrollY() == scroll(current).getHeight()
                    && !((View) get(current, "watchRail")).getGlobalVisibleRect(new Rect()));
            AtomicReference<ImageView> control = new AtomicReference<>();
            scene.onActivity(current -> {
                assertEquals("A hidden retained poster must release its queued delivery receiver", 0,
                        posterReceiverCount(loader, poster.get()));
                assertNull(poster.get().getDrawable());
                ImageView receiver = new ImageView(current); control.set(receiver);
                // Decode the identical nonempty local image into another receiver.
                // The old hidden View must not share this successful delivery.
                loader.poster(receiver, posterUri, request[0], request[1], (Integer) get(current, "renderGeneration"));
                assertEquals(1, posterReceiverCount(loader, receiver));
                loader.resume();
            });
            awaitFrames(activity, "The local raster proves a nonnull decode while Home stays below", current ->
                    bitmapReady(control.get()) && loader.diagnostics().jobs == 0 && loader.diagnostics().running == 0);
            scene.onActivity(current -> {
                assertSame(savedHome.get(), root(current)); assertEquals(scroll(current).getHeight(), scroll(current).getScrollY());
                assertTrue(bitmapReady(control.get()));
                assertNull("No late bitmap may reach the now offscreen retained poster", poster.get().getDrawable());
                assertEquals(0, posterReceiverCount(loader, poster.get()));
                loader.forget(control.get());
            });
        }
    }

    private static int posterReceiverCount(UiImageLoader loader, ImageView view) {
        int count = 0;
        for (Object job : ((Map<?, ?>) field(loader, "jobs")).values()) {
            if (!((String) field(job, "key")).startsWith("poster:")) continue;
            for (Object receiver : (List<?>) field(job, "targets")) if (field(receiver, "view") == view) count++;
        }
        return count;
    }

    private HomeSnapshot openFromHome(ActivityScenario<MainActivity> scene, MainActivity activity, String destination) throws Exception {
        awaitPage(activity, "HOME");
        scene.onActivity(current -> {
            assertWindow(current);
            assertTrue(tag(current, "favorite-1").requestFocus());
            scroll(current).scrollTo(0, 0);
        });
        awaitFrames(activity, "Warm every actual favorite before caching Home", current -> homeArtworkReady(current)
                && focused(current, "favorite-1") && scroll(current).getScrollY() == 0);
        String shortcut = "GALLERY".equals(destination) ? "shortcut-wallpapers" : "shortcut-launcher";
        scene.onActivity(current -> assertTrue(tag(current, shortcut).requestFocus()));
        awaitFrames(activity, "The real " + shortcut + " and lower page settle", current -> homeArtworkReady(current)
                && focused(current, shortcut) && scroll(current).getScrollY() == scroll(current).getHeight());
        AtomicReference<HomeSnapshot> snapshot = new AtomicReference<>();
        scene.onActivity(current -> snapshot.set(new HomeSnapshot(current)));
        assertTrue("Real Center opens " + destination, device.pressDPadCenter());
        awaitFrames(activity, "The complete " + destination + " fade detaches Home", current -> pageSettled(current, destination)
                && get(current, "retainedPage") != null
                && field(get(current, "retainedPage"), "root") == snapshot.get().root
                && snapshot.get().root.getParent() == null
                && (!"GALLERY".equals(destination) || bitmapReady(current.getCurrentFocus())));
        return snapshot.get();
    }

    private final class HomeSnapshot {
        final View root, focus;
        final TvScrollView scroll;
        final int scrollY;
        final List<View> favorites = new ArrayList<>();
        final List<ImageView> images = new ArrayList<>();
        final List<Drawable> drawables = new ArrayList<>();
        HomeSnapshot(MainActivity activity) {
            root = root(activity); focus = activity.getCurrentFocus(); scroll = scroll(activity); scrollY = scroll.getScrollY();
            assertEquals(scroll.getHeight(), scrollY); assertVisibleFocus(focus);
            for (int index = 0; index < favoriteCount; index++) {
                View favorite = tag(activity, "favorite-" + index); ImageView image = firstImage(favorite);
                assertTrue(bitmapReady(favorite)); favorites.add(favorite); images.add(image); drawables.add(image.getDrawable());
            }
        }
        void assertRestored(MainActivity activity) {
            assertWindow(activity); assertEquals("HOME", activity.currentScreenForTest());
            assertSame("Completed Back reuses the exact Home tree", root, root(activity));
            assertSame("Native scroll state belongs to the retained View", scroll, scroll(activity));
            assertEquals("The lower viewport survives the trip", scrollY, scroll.getScrollY());
            assertSame("The exact settings shortcut regains native focus", focus, activity.getCurrentFocus());
            assertVisibleFocus(focus);
            for (int index = 0; index < favorites.size(); index++) {
                View favorite = tag(activity, "favorite-" + index);
                assertSame("Favorite View " + index, favorites.get(index), favorite);
                assertSame("Ready ImageView " + index, images.get(index), firstImage(favorite));
                assertSame("Ready Drawable is not rebound " + index, drawables.get(index), images.get(index).getDrawable());
                assertTrue(bitmapReady(favorite));
            }
            assertNoHardwareLayer(root);
        }
    }

    private static void assertDetachedHome(MainActivity activity, HomeSnapshot home) {
        Object retained = get(activity, "retainedPage"); assertNotNull("One detached Home is retained", retained);
        assertEquals("HOME", field(retained, "screen").toString()); assertSame(home.root, field(retained, "root"));
        assertSame(home.scroll, field(retained, "homeScroll"));
        assertNull("The saved Home is not an extra renderHost child", home.root.getParent());
        assertFalse(home.root.isAttachedToWindow()); assertEquals(0, focusedCount(home.root));
        assertNull("Completed fades release the transient Home GPU owner", get(activity, "layeredHome"));
        assertNoHardwareLayer(home.root); assertSingleAttachedPage(activity);
    }
    private static void assertReleased(MainActivity activity, HomeSnapshot home) {
        assertNull("Lifecycle/memory policy releases the only retained page", get(activity, "retainedPage"));
        assertNull(get(activity, "layeredHome")); assertNull(home.root.getParent());
        assertFalse(home.root.isAttachedToWindow()); assertNoHardwareLayer(home.root);
    }
    private static void assertRebuiltHome(MainActivity activity, HomeSnapshot old) {
        assertEquals("HOME", activity.currentScreenForTest());
        assertNotSame("Invalid or released snapshots must not be restored", old.root, root(activity));
        assertNull(old.root.getParent()); assertNoHardwareLayer(old.root); assertSingleAttachedPage(activity);
        assertNull("The completed Gallery/Settings exit is not cached as Home", get(activity, "retainedPage"));
        assertEquals("The known return shortcut remains usable after rebuilding", old.focus.getTag(), activity.getCurrentFocus().getTag());
    }
    private static void assertAppearance(MainActivity activity, int wallpaper, String mode, boolean light) {
        LauncherPrefs.Settings prefs = (LauncherPrefs.Settings) get(activity, "prefs");
        assertEquals(wallpaper, prefs.background); assertEquals(mode, prefs.paletteMode);
        assertEquals(light, field(get(activity, "palette"), "light"));
        assertTrue("The live backdrop uses the new wallpaper and palette",
                ((String) get(activity, "backdropSignature")).startsWith(wallpaper + ":"
                        + io.github.ekkzy.evebox.data.DayNightPolicy.isNight(prefs,System.currentTimeMillis()) + ":" + light + ":"));
    }
    private boolean homeArtworkReady(MainActivity activity) {
        if (!pageSettled(activity, "HOME") || scroll(activity).isScrollAnimating()) return false;
        for (int index = 0; index < favoriteCount; index++) if (!bitmapReady(tag(activity, "favorite-" + index))) return false;
        return true;
    }
    private static boolean pageSettled(MainActivity activity, String page) {
        return page.equals(activity.currentScreenForTest()) && settled(activity) && host(activity).getChildCount() == 1;
    }
    private static boolean settled(MainActivity activity) {
        View root = root(activity); Future<?> load = (Future<?>) get(activity, "loadFuture");
        SharedRowsSheet sheet = (SharedRowsSheet) get(activity, "rowsSheet");
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus() && root != null && root.isLaidOut()
                && root.getVisibility() == View.VISIBLE && root.isShown() && root.getAlpha() > .99f && !root.isLayoutRequested()
                && load != null && load.isDone() && (Boolean) get(activity, "catalogLoaded")
                && !(Boolean) get(activity, "renderDirty") && !((UiMotion) get(activity, "uiMotion")).isRunning()
                && sheet != null && !sheet.isAnimating();
    }
    private static void assertSingleAttachedPage(MainActivity activity) {
        assertWindow(activity); View root = root(activity);
        assertEquals(1, host(activity).getChildCount()); assertSame(root, host(activity).getChildAt(0));
        assertTrue(root.isAttachedToWindow()); assertEquals(View.VISIBLE, root.getVisibility()); assertEquals(1f, root.getAlpha(), .001f);
        assertEquals("Exactly one current control has native focus", 1, focusedCount(root));
        assertVisibleFocus(activity.getCurrentFocus());
    }
    private static void assertNoHardwareLayer(View view) {
        assertEquals("No retained GPU layer on " + view.getClass().getSimpleName(), View.LAYER_TYPE_NONE, view.getLayerType());
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertNoHardwareLayer(((ViewGroup) view).getChildAt(index));
    }
    private static void assertVisibleFocus(View view) {
        assertNotNull(view); assertTrue(view.isFocused()); assertTrue(view.isShown());
        Rect visible = new Rect(); assertTrue(view.getGlobalVisibleRect(visible) && !visible.isEmpty());
    }
    private static boolean bitmapReady(View view) {
        ImageView image = firstImage(view);
        return image != null && image.getDrawable() instanceof BitmapDrawable
                && !((BitmapDrawable) image.getDrawable()).getBitmap().isRecycled();
    }
    private static ImageView firstImage(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            ImageView found = firstImage(((ViewGroup) view).getChildAt(index)); if (found != null) return found;
        }
        return null;
    }
    private static int focusedCount(View view) {
        int result = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) result += focusedCount(((ViewGroup) view).getChildAt(index));
        return result;
    }
    private static MainActivity owner(ActivityScenario<MainActivity> scene) {
        AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set); return owner.get();
    }
    private static void awaitPage(MainActivity activity, String page) throws Exception {
        awaitFrames(activity, page + " native content and fade settle", current -> pageSettled(current, page));
    }
    private static void awaitFrames(MainActivity activity, String message, Predicate<MainActivity> condition) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Choreographer.FrameCallback> callback = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            callback.set(time -> {
                try { if (condition.test(activity)) { ready.countDown(); return; } }
                catch (Throwable thrown) { failure.set(thrown); ready.countDown(); return; }
                Choreographer.getInstance().postFrameCallback(callback.get());
            });
            Choreographer.getInstance().postFrameCallback(callback.get());
        });
        try { assertTrue(message, ready.await(10, TimeUnit.SECONDS)); }
        finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Choreographer.getInstance().removeFrameCallback(callback.get())); }
        if (failure.get() != null) throw new AssertionError(message, failure.get());
    }
    private static void assertWindow(MainActivity activity) {
        assertEquals("Actual app window width", 3840, activity.getWindow().getDecorView().getWidth());
        assertEquals("Actual app window height", 2160, activity.getWindow().getDecorView().getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static View root(MainActivity activity) { return (View) get(activity, "root"); }
    private static ViewGroup host(MainActivity activity) { return (ViewGroup) get(activity, "renderHost"); }
    private static TvScrollView scroll(MainActivity activity) { return (TvScrollView) get(activity, "homeScroll"); }
    private static TvScrollView galleryScroll(MainActivity activity) { return (TvScrollView) field(get(activity, "galleryBindings"), "scroll"); }
    private static View tag(MainActivity activity, String key) { View view = root(activity).findViewWithTag(key); assertNotNull(key, view); return view; }
    private static boolean focused(MainActivity activity, String key) { return activity.getCurrentFocus() != null && key.equals(activity.getCurrentFocus().getTag()); }
    private static Object get(MainActivity activity, String name) { return field(activity, name); }
    private static void set(MainActivity activity, String name, Object value) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static void invoke(MainActivity activity, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }
}
