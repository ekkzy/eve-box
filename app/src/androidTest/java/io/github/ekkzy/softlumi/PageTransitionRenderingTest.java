package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import io.github.ekkzy.softlumi.data.AppCatalog;
import io.github.ekkzy.softlumi.data.AppEntry;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.ui.GlassDrawable;
import io.github.ekkzy.softlumi.ui.SharedRowsSheet;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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

/** Samples native pre-draw state, never software View.draw or a timed screenshot. */
@RunWith(AndroidJUnit4.class)
public final class PageTransitionRenderingTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences storage;
    private Map<String, Object> original;

    @Before public void prepareOwned4kHome() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(ValueAnimator.areAnimatorsEnabled());
            assertEquals("Observe a real forward animation at the agreed policy", 1f, ValueAnimator.getDurationScale(), .001f);
        });
        List<AppEntry> apps = AppCatalog.load(context); assertFalse("Actual installed favorites required", apps.isEmpty());
        List<String> favorites = new ArrayList<>(); for (AppEntry app : apps) if (favorites.size() < 2) favorites.add(app.id);
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE); original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs preferences = new LauncherPrefs(context);
        preferences.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); preferences.setBackground(0); preferences.setPaletteMode("dark"); preferences.setLanguage("en");
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false); preferences.setSounds(false);
        preferences.setReducedMotion(false); preferences.setFavorites(favorites);
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
        assertTrue(editor.commit()); assertEquals("All debug preferences restored", original, snapshot(storage));
    }

    @Test public void firstVisibleSettingsPreDrawStartsTransparentAndNeverResetsFromOpaque() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            awaitFrames(activity, "Home settles before the measured action", current -> settled(current) && "HOME".equals(current.currentScreenForTest()));
            scene.onActivity(current -> {
                assertWindow(current);
                // Focus is setup only. Opening the page below uses real injected
                // Center input, after the native pre-draw observer is installed.
                View shortcut = ((View) get(current, "root")).findViewWithTag("shortcut-launcher");
                assertNotNull(shortcut); assertTrue(shortcut.requestFocus());
            });
            awaitFrames(activity, "The real launcher settings control is focused and its scroll settles", current ->
                    settled(current) && current.getCurrentFocus() != null
                            && "shortcut-launcher".equals(current.getCurrentFocus().getTag())
                            && !((TvScrollView) get(current, "homeScroll")).isScrollAnimating());
            AtomicReference<EntryProbe> probe = new AtomicReference<>();
            scene.onActivity(current -> { probe.set(new EntryProbe(current)); probe.get().start(); });
            try {
                assertTrue("Real D-pad Center opens Settings", device.pressDPadCenter());
                assertTrue("Observe the complete entry through native pre-draw callbacks", probe.get().finished.await(10, TimeUnit.SECONDS));
                scene.onActivity(current -> {
                    probe.get().assertEntry(); assertWindow(current);
                    assertEquals("SETTINGS", current.currentScreenForTest());
                });
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { probe.get().stop(); probe.get().emit(); });
            }
        }
    }

    @Test public void rowsPagesKeepOneOpaqueLiveSheetThroughForwardBackAndEntryReversal() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            awaitFrames(activity, "Home is ready", current -> settled(current) && "HOME".equals(current.currentScreenForTest()));
            scene.onActivity(current -> {
                assertWindow(current);
                View shortcut = ((View) get(current, "root")).findViewWithTag("shortcut-launcher");
                assertNotNull(shortcut); assertTrue(shortcut.requestFocus());
            });
            awaitFrames(activity, "Settings shortcut and scroll settle", current -> settled(current)
                    && current.getCurrentFocus() != null && "shortcut-launcher".equals(current.getCurrentFocus().getTag())
                    && !((TvScrollView) get(current, "homeScroll")).isScrollAnimating());
            assertTrue(device.pressDPadCenter());
            awaitPage(activity, "SETTINGS");
            AtomicReference<RowsProbe> probe = new AtomicReference<>();
            scene.onActivity(current -> {
                assertEquals("settings-appearance", current.getCurrentFocus().getTag());
                probe.set(new RowsProbe(current)); probe.get().start();
            });
            try {
                assertTrue("Real Center opens Appearance", device.pressDPadCenter());
                awaitPage(activity, "APPEARANCE");
                scene.onActivity(current -> { probe.get().assertHealthy(); assertSettledPage(current); });
                assertTrue("Real Back returns to Settings", device.pressBack());
                awaitPage(activity, "SETTINGS");
                scene.onActivity(current -> {
                    probe.get().assertHealthy(); assertSettledPage(current);
                    assertEquals("settings-appearance", current.getCurrentFocus().getTag());
                    probe.get().armEntryReversal();
                });
                assertTrue(device.pressDPadCenter());
                assertTrue("Reverse on the first visible Appearance pre-draw, without a timing offset",
                        probe.get().reversed.await(10, TimeUnit.SECONDS));
                scene.onActivity(current -> probe.get().assertHealthy());
                awaitPage(activity, "SETTINGS");
                scene.onActivity(current -> {
                    probe.get().assertHealthy(); assertSettledPage(current);
                    assertTrue("Both rows pages were actually drawn", probe.get().appearanceDraws > 0 && probe.get().settingsDraws > 0);
                    assertSame("Rapid Back restores the actual retained text root", probe.get().returningRoot, get(current, "root"));
                    assertEquals("settings-appearance", current.getCurrentFocus().getTag());
                });
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { probe.get().stop(); probe.get().emit(); });
            }
            assertTrue(device.pressBack());
            awaitPage(activity, "HOME");
            scene.onActivity(current -> {
                assertSame("Leaving rows pages keeps their single reusable material owner", probe.get().sheet, get(current, "rowsSheet"));
                assertEquals("A completed exit hides the material", View.INVISIBLE, probe.get().material.getVisibility());
                assertEquals(0f, probe.get().material.getAlpha(), .001f);
                assertEquals("The hidden face releases its hardware layer", View.LAYER_TYPE_NONE, probe.get().face.getLayerType());
                assertSettledPage(current);
            });
        }
    }

    @Test public void backAndCenterBeforeFirstSettingsInitializationStillPrepareUsableNavigation() throws Exception {
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            awaitFrames(activity, "Home and its actual favorite icons are ready", current -> settled(current)
                    && "HOME".equals(current.currentScreenForTest()) && homeIconsReady((View) get(current, "root")));
            scene.onActivity(current -> {
                assertWindow(current);
                View shortcut = ((View) get(current, "root")).findViewWithTag("shortcut-launcher");
                assertNotNull(shortcut); assertTrue(shortcut.requestFocus());
            });
            awaitFrames(activity, "The real launcher settings shortcut is ready for input", current -> settled(current)
                    && current.getCurrentFocus() != null && "shortcut-launcher".equals(current.getCurrentFocus().getTag())
                    && !((TvScrollView) get(current, "homeScroll")).isScrollAnimating());
            AtomicReference<EarlyReentryProbe> probe = new AtomicReference<>();
            scene.onActivity(current -> { probe.set(new EarlyReentryProbe(current)); probe.get().start(); });
            try {
                assertTrue("Real Center starts the first Settings entry", device.pressDPadCenter());
                assertTrue("The first incoming Settings pre-draw must be caught while INVISIBLE; this is a required race precondition",
                        probe.get().inputCompleted.await(10, TimeUnit.SECONDS));
                scene.onActivity(current -> probe.get().assertNoFailure());
                awaitPage(activity, "SETTINGS");
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); assertSettledPage(current);
                    View root = (View) get(current, "root");
                    View appearance = root.findViewWithTag("settings-appearance"), wallpapers = root.findViewWithTag("settings-wallpapers");
                    assertNotNull(appearance); assertNotNull(wallpapers);
                    assertSame("Re-entered Settings has its actual default control focused", appearance, current.getCurrentFocus());
                    assertVisibleFocus(appearance);
                    assertEquals("Re-entry initializes the explicit Down link before accepting navigation", wallpapers.getId(), appearance.getNextFocusDownId());
                    assertEquals("The reverse link is initialized as well", appearance.getId(), wallpapers.getNextFocusUpId());
                });
                assertTrue("Actual D-pad Down selects Wallpapers", device.pressDPadDown());
                awaitFrames(activity, "Wallpapers focus settles after real Down", current -> settled(current)
                        && "SETTINGS".equals(current.currentScreenForTest()) && current.getCurrentFocus() != null
                        && "settings-wallpapers".equals(current.getCurrentFocus().getTag()));
                scene.onActivity(current -> { assertSettledPage(current); assertVisibleFocus(current.getCurrentFocus()); });
                assertTrue("Actual Center opens the selected Gallery", device.pressDPadCenter());
                awaitFrames(activity, "Gallery content and its actual photo preview are ready", current -> settled(current)
                        && "GALLERY".equals(current.currentScreenForTest())
                        && ((ViewGroup) get(current, "renderHost")).getChildCount() == 1
                        && bitmapReady(((View) get(current, "root")).findViewWithTag("background-0")));
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); assertSettledPage(current);
                    View firstWallpaper = ((View) get(current, "root")).findViewWithTag("background-0");
                    assertNotNull(firstWallpaper); assertSame(firstWallpaper, current.getCurrentFocus());
                    assertVisibleFocus(firstWallpaper); assertTrue("The visible gallery photo is decoded", bitmapReady(firstWallpaper));
                });
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { probe.get().stop(); probe.get().emit(); });
            }
        }
    }

    @Test public void homeFadeOwnsOneTransientLayerAndReleasesItAfterCompletionReversalAndRealPause() throws Exception {
        String fixturePackage = "io.github.ekkzy.softlumi.fixture.alpha";
        ComponentName fixture = new ComponentName(fixturePackage, "io.github.ekkzy.softlumi.fixture.FixtureActivity");
        assertTrue("The existing separate app fixture must be installed", context.getPackageManager().getActivityInfo(fixture, 0).exported);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(homeIntent())) {
            AtomicReference<MainActivity> owner = new AtomicReference<>(); scene.onActivity(owner::set);
            MainActivity activity = owner.get();
            awaitFrames(activity, "Home has settled before measuring its transient cache", current -> settled(current)
                    && "HOME".equals(current.currentScreenForTest()) && homeIconsReady((View) get(current, "root")));
            scene.onActivity(current -> {
                assertWindow(current);
                View shortcut = ((View) get(current, "root")).findViewWithTag("shortcut-launcher");
                assertNotNull(shortcut); assertTrue(shortcut.requestFocus());
            });
            awaitFrames(activity, "The Settings shortcut and Home scroll settle", current -> settled(current)
                    && current.getCurrentFocus() != null && "shortcut-launcher".equals(current.getCurrentFocus().getTag())
                    && !((TvScrollView) get(current, "homeScroll")).isScrollAnimating());
            AtomicReference<HomeLayerProbe> probe = new AtomicReference<>();
            scene.onActivity(current -> probe.set(new HomeLayerProbe(current, fixture)));
            try {
                scene.onActivity(current -> { probe.get().start(); probe.get().assertReleased(); });
                assertTrue(device.pressDPadCenter()); awaitPage(activity, "SETTINGS");
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); probe.get().assertReleased(); assertSettledPage(current);
                    assertTrue("At least one actual partially transparent Home exit used the cache", probe.get().exitFrames > 0);
                });
                assertTrue(device.pressBack()); awaitPage(activity, "HOME");
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); probe.get().assertReleased(); assertSettledPage(current);
                    assertTrue("At least one actual partially transparent Home entry used the cache", probe.get().entryFrames > 0);
                    assertEquals("shortcut-launcher", current.getCurrentFocus().getTag());
                    probe.get().reverseArmed = true;
                });
                assertTrue(device.pressDPadCenter());
                assertTrue("Rapid Back interrupts a real partially progressed Home exit", probe.get().reversed.await(10, TimeUnit.SECONDS));
                scene.onActivity(current -> probe.get().assertNoFailure());
                awaitPage(activity, "HOME");
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); probe.get().assertReleased(); assertSettledPage(current);
                    assertEquals("shortcut-launcher", current.getCurrentFocus().getTag());
                    probe.get().pauseArmed = true;
                });
                assertTrue(device.pressDPadCenter());
                assertTrue("A separate app really pauses the owner after a cached fade was observed", probe.get().paused.await(10, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    probe.get().assertNoFailure(); assertFalse((Boolean) get(activity, "resumed"));
                    assertTrue("The external launch began with an active transient Home layer", probe.get().externalStarted);
                    probe.get().assertReleased();
                });
                assertTrue("The separate fixture actually owns a foreground window before returning Home",
                        device.wait(Until.hasObject(By.pkg(fixturePackage)), 5000));
                assertEquals(fixturePackage, device.getCurrentPackageName());
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    probe.get().returnMarker = "home-layer-return-" + SystemClock.uptimeMillis();
                    probe.get().returnRequested = true;
                    context.startActivity(homeIntent().putExtra("softlumiLayerReturn", probe.get().returnMarker));
                });
                awaitFrames(activity, "Framework resumes the same HOME task with fully settled content and material", current -> {
                    probe.get().assertNoFailure();
                    return probe.get().returnResumed && settled(current) && "HOME".equals(current.currentScreenForTest())
                            && ((ViewGroup) get(current, "renderHost")).getChildCount() == 1;
                });
                scene.onActivity(current -> {
                    probe.get().assertNoFailure(); probe.get().assertReleased(); assertSettledPage(current); assertWindow(current);
                });
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { probe.get().stop(); probe.get().emit(); });
                // Recover only our existing task before closing the scenario;
                // no HOME-role changes or synthetic lifecycle calls are used.
                if (fixturePackage.equals(device.getCurrentPackageName())) {
                    context.startActivity(homeIntent());
                    awaitFrames(activity, "Cleanup resumes the existing debug Activity", current -> (Boolean) get(current, "resumed") && current.hasWindowFocus());
                }
            }
        }
    }

    private Intent homeIntent() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static final class HomeLayerProbe implements ViewTreeObserver.OnPreDrawListener, ActivityLifecycleCallback {
        final MainActivity activity;
        final ComponentName fixture;
        final View decor;
        final Map<View, View> homes = new IdentityHashMap<>();
        final List<String> samples = new ArrayList<>();
        final List<String> lifecycle = new ArrayList<>();
        final int ownerTask;
        final CountDownLatch reversed = new CountDownLatch(1), paused = new CountDownLatch(1);
        ViewTreeObserver observer;
        AssertionError failure;
        boolean reverseArmed, pauseArmed, externalStarted, returnRequested, returnResumed;
        String returnMarker;
        int entryFrames, exitFrames;
        HomeLayerProbe(MainActivity activity, ComponentName fixture) {
            this.activity = activity; this.fixture = fixture; ownerTask = activity.getTaskId();
            decor = activity.getWindow().getDecorView(); rememberCurrentHome();
        }
        void start() {
            observer = decor.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(this);
        }
        void stop() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(this);
        }
        void rememberCurrentHome() {
            if ("HOME".equals(activity.currentScreenForTest())) homes.put((View) get(activity, "root"), (View) get(activity, "board"));
        }
        void checkOwnership() {
            rememberCurrentHome(); View cached = (View) get(activity, "layeredHome"); int layers = 0;
            for (Map.Entry<View, View> entry : homes.entrySet()) {
                View root = entry.getKey(), board = entry.getValue();
                assertEquals("Home board never translates during a page fade", 0f, board.getTranslationX(), .001f);
                assertEquals(0f, board.getTranslationY(), .001f);
                assertEquals("Home keeps its native design scale during the fade", 1f, board.getScaleX(), .001f);
                assertEquals(1f, board.getScaleY(), .001f);
                if (root.getLayerType() == View.LAYER_TYPE_HARDWARE) {
                    layers++; assertSame("The sole owner tracks every cached Home, including outgoing roots", cached, root);
                } else assertEquals(View.LAYER_TYPE_NONE, root.getLayerType());
            }
            assertTrue("A rapid reversal never leaves two cached Homes", layers <= 1);
            assertEquals("The owner and hardware resources agree", cached == null ? 0 : 1, layers);
            assertEquals("No text page or full render host acquires an extra layer", layers, hardwareLayerCount((View) get(activity, "renderHost")));
            if (cached != null) {
                assertTrue(homes.containsKey(cached)); assertTrue(cached.isAttachedToWindow());
                assertTrue(cached.getWidth() > 0 && cached.getHeight() > 0 && (long) cached.getWidth() * cached.getHeight() <= 3840L * 2160);
                for (View source = (View) get(activity, "glassScene"); source != null; source = source.getParent() instanceof View ? (View) source.getParent() : null)
                    assertNotSame("The live scene stays outside the cached Home tree", cached, source);
            }
            if (!(Boolean) get(activity, "resumed")) assertReleased();
        }
        void assertReleased() {
            assertNull("No Home texture remains owned after completion or pause", get(activity, "layeredHome"));
            for (View home : homes.keySet()) assertEquals("Even detached former Homes release their hardware layers", View.LAYER_TYPE_NONE, home.getLayerType());
        }
        void record(String phase) {
            View cached = (View) get(activity, "layeredHome");
            if (samples.size() < 128) samples.add(phase + ",page=" + activity.currentScreenForTest()
                    + ",owner=" + Integer.toHexString(System.identityHashCode(cached))
                    + ",alpha=" + (cached == null ? "none" : cached.getAlpha()) + ",knownHomes=" + homes.size());
        }
        @Override public boolean onPreDraw() {
            try {
                checkOwnership(); record("pre-draw");
                View cached = (View) get(activity, "layeredHome");
                if (cached == null || cached.getAlpha() <= .05f || cached.getAlpha() >= .95f) return true;
                if ("HOME".equals(activity.currentScreenForTest())) entryFrames++; else exitFrames++;
                if (!"SETTINGS".equals(activity.currentScreenForTest())) return true;
                if (reverseArmed) {
                    reverseArmed = false;
                    decor.post(() -> {
                        try {
                            assertEquals("SETTINGS", activity.currentScreenForTest());
                            assertTrue("The reversal happens while Home is still cached", get(activity, "layeredHome") != null);
                            assertTrue(((UiMotion) get(activity, "uiMotion")).isRunning());
                            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                            checkOwnership(); record("rapid-back");
                        } catch (AssertionError error) { if (failure == null) failure = error; }
                        finally { reversed.countDown(); }
                    });
                } else if (pauseArmed) {
                    pauseArmed = false;
                    decor.post(() -> {
                        try {
                            checkOwnership(); assertNotNull("The external launch interrupts a cached Home fade", get(activity, "layeredHome"));
                            record("external-launch"); externalStarted = true;
                            activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setComponent(fixture).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                        } catch (RuntimeException | AssertionError error) {
                            if (failure == null) failure = error instanceof AssertionError ? (AssertionError) error : new AssertionError(error);
                            paused.countDown();
                        }
                    });
                }
            } catch (AssertionError error) {
                if (failure == null) failure = error; reversed.countDown(); paused.countDown();
            }
            return true;
        }
        @Override public void onActivityLifecycleChanged(Activity changed, Stage stage) {
            if (changed instanceof MainActivity) {
                lifecycle.add(stage + ",activity=" + Integer.toHexString(System.identityHashCode(changed)) + ",sameOwner=" + (changed == activity)
                        + ",task=" + changed.getTaskId() + ",page=" + ((MainActivity) changed).currentScreenForTest());
                if (returnRequested && stage == Stage.RESUMED) {
                    try {
                        assertSame("A real HOME return must resume the original Activity", activity, changed);
                        assertEquals("The established HOME task is reused", ownerTask, changed.getTaskId());
                        assertEquals("The real return Intent was delivered", returnMarker, changed.getIntent().getStringExtra("softlumiLayerReturn"));
                        returnResumed = true;
                    } catch (AssertionError error) { if (failure == null) failure = error; }
                }
            }
            if (changed != activity || stage != Stage.PAUSED) return;
            try { checkOwnership(); assertReleased(); record("framework-paused"); }
            catch (AssertionError error) { if (failure == null) failure = error; }
            finally { paused.countDown(); }
        }
        void assertNoFailure() { if (failure != null) throw failure; }
        String currentState() {
            View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
            UiMotion motion = (UiMotion) get(activity, "uiMotion"); SharedRowsSheet sheet = (SharedRowsSheet) get(activity, "rowsSheet");
            return "activity=" + Integer.toHexString(System.identityHashCode(activity)) + ",task=" + activity.getTaskId() + ",expectedTask=" + ownerTask
                    + ",page=" + activity.currentScreenForTest() + ",resumed=" + get(activity, "resumed") + ",destroyed=" + get(activity, "destroyed")
                    + ",windowFocus=" + activity.hasWindowFocus() + ",returnRequested=" + returnRequested + ",returnResumed=" + returnResumed
                    + ",actualMarker=" + activity.getIntent().getStringExtra("softlumiLayerReturn") + ",expectedMarker=" + returnMarker
                    + ",root=" + (root == null ? "null" : new Sample(0, root) + ",laidOut=" + root.isLaidOut() + ",layoutRequested=" + root.isLayoutRequested())
                    + ",loadDone=" + (load != null && load.isDone()) + ",loadCancelled=" + (load != null && load.isCancelled())
                    + ",catalogLoaded=" + get(activity, "catalogLoaded") + ",renderDirty=" + get(activity, "renderDirty")
                    + ",motionRunning=" + motion.isRunning() + ",channels=" + motion.runningChannelCount() + ",sheetAnimating=" + sheet.isAnimating()
                    + ",roots=" + ((ViewGroup) get(activity, "renderHost")).getChildCount() + ",layeredHome=" + get(activity, "layeredHome");
        }
        void emit() {
            Bundle status = new Bundle(); status.putString("stream", "\nHOME_TRANSIENT_LAYER entryFrames=" + entryFrames
                    + ",exitFrames=" + exitFrames + "\n" + samples + "\nLIFECYCLE " + lifecycle + "\nFINAL_STATE " + currentState() + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        }
    }

    /** Input is dispatched inside the measured pre-draw, before its queued after-layout initialization. */
    private static final class EarlyReentryProbe implements ViewTreeObserver.OnPreDrawListener {
        final MainActivity activity;
        final View home, decor;
        final CountDownLatch inputCompleted = new CountDownLatch(1);
        final List<String> samples = new ArrayList<>();
        ViewTreeObserver observer;
        View unprepared, returned;
        AssertionError failure;
        boolean inputIssued;
        int frames;
        EarlyReentryProbe(MainActivity activity) {
            this.activity = activity; home = (View) get(activity, "root"); decor = activity.getWindow().getDecorView();
        }
        void start() { observer = decor.getViewTreeObserver(); observer.addOnPreDrawListener(this); }
        void stop() { if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this); }
        void record(String phase) {
            View root = (View) get(activity, "root"); View focus = activity.getCurrentFocus();
            if (samples.size() < 96) samples.add(phase + ",page=" + activity.currentScreenForTest() + "," + new Sample(++frames, root)
                    + ",focus=" + (focus == null ? "null" : focus.getTag()));
        }
        @Override public boolean onPreDraw() {
            try {
                record("pre-draw");
                View current = (View) get(activity, "root");
                if (inputIssued) {
                    if (returned == null && "SETTINGS".equals(activity.currentScreenForTest())) returned = current;
                    return true;
                }
                if (current == home) return true;
                inputIssued = true; unprepared = current;
                assertEquals("The first new native pre-draw really belongs to Settings", "SETTINGS", activity.currentScreenForTest());
                assertEquals("Do not silently replace the required invisible entry race with a later visible-frame test", View.INVISIBLE, current.getVisibility());
                assertTrue("This is the native layout pre-draw, not a synthetic unattached view", current.isAttachedToWindow() && current.isLaidOut());
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                record("after-back"); assertEquals("HOME", activity.currentScreenForTest());
                assertNotNull(activity.getCurrentFocus()); assertEquals("shortcut-launcher", activity.getCurrentFocus().getTag());
                assertTrue("Returning Home keeps its decoded app icons", homeIconsReady((View) get(activity, "root")));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                // Native View may queue performClick from Center's key-up. Its
                // eventual route is observed below, without calling performClick.
                record("after-center-input"); inputCompleted.countDown();
            } catch (AssertionError error) { if (failure == null) failure = error; inputCompleted.countDown(); }
            return true;
        }
        void assertNoFailure() { if (failure != null) throw failure; assertNotNull("An actual first incoming root was sampled", unprepared); }
        void emit() {
            Bundle status = new Bundle(); status.putString("stream", "\nEARLY_SETTINGS_REENTRY unprepared="
                    + Integer.toHexString(System.identityHashCode(unprepared)) + ",returned="
                    + Integer.toHexString(System.identityHashCode(returned)) + "\n" + samples + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        }
    }

    private static void assertVisibleFocus(View focus) {
        assertTrue(focus.isFocused()); assertTrue(focus.isShown());
        Rect visible = new Rect(); assertTrue("The real focused control has visible native bounds", focus.getGlobalVisibleRect(visible) && !visible.isEmpty());
    }
    private static boolean homeIconsReady(View root) {
        if (root == null || root.findViewWithTag("favorite-0") == null) return false;
        for (int index = 0; index < 2; index++) {
            View tile = root.findViewWithTag("favorite-" + index);
            if (tile != null && !bitmapReady(tile)) return false;
        }
        return true;
    }
    private static boolean bitmapReady(View owner) {
        ImageView image = firstImage(owner);
        return image != null && image.getDrawable() instanceof BitmapDrawable
                && !((BitmapDrawable) image.getDrawable()).getBitmap().isRecycled();
    }
    private static ImageView firstImage(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ImageView image = firstImage(((ViewGroup) view).getChildAt(i)); if (image != null) return image;
        }
        return null;
    }

    private static void awaitPage(MainActivity activity, String page) throws Exception {
        awaitFrames(activity, page + " content and material settle", current -> settled(current)
                && page.equals(current.currentScreenForTest())
                && ((ViewGroup) get(current, "renderHost")).getChildCount() == 1);
    }

    private static void assertSettledPage(MainActivity activity) {
        View root = (View) get(activity, "root");
        assertEquals(View.VISIBLE, root.getVisibility()); assertTrue(root.isShown());
        assertEquals("Text finishes fully visible", 1f, root.getAlpha(), .001f);
        assertEquals(1, ((ViewGroup) get(activity, "renderHost")).getChildCount());
        assertNotNull(activity.getCurrentFocus()); assertTrue(activity.getCurrentFocus().isShown());
        assertEquals("Exactly one content control is focused", 1, focusedCount(root));
    }

    /** The shared material must not fade, be duplicated, or become a child of either text page. */
    private static final class RowsProbe implements ViewTreeObserver.OnPreDrawListener {
        final MainActivity activity;
        final View decor, face, material;
        final SharedRowsSheet sheet;
        final GlassDrawable glass;
        final CountDownLatch reversed = new CountDownLatch(1);
        final List<String> samples = new ArrayList<>();
        ViewTreeObserver observer;
        View returningRoot;
        AssertionError failure;
        boolean reverseArmed;
        int appearanceDraws, settingsDraws;
        RowsProbe(MainActivity activity) {
            this.activity = activity; decor = activity.getWindow().getDecorView();
            sheet = (SharedRowsSheet) get(activity, "rowsSheet"); assertNotNull(sheet);
            face = sheet.findViewWithTag("shared-rows-glass-face"); assertNotNull(face);
            assertTrue(face.getBackground() instanceof GlassDrawable); glass = (GlassDrawable) face.getBackground();
            material = (View) face.getParent(); assertMaterial();
        }
        void start() { observer = decor.getViewTreeObserver(); observer.addOnPreDrawListener(this); }
        void stop() { if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this); }
        void armEntryReversal() { returningRoot = (View) get(activity, "root"); reverseArmed = true; }
        @Override public boolean onPreDraw() {
            try {
                assertMaterial();
                String page = activity.currentScreenForTest(); View root = (View) get(activity, "root");
                assertTrue("Only the exercised rows routes may own this observation", "SETTINGS".equals(page) || "APPEARANCE".equals(page));
                if ("SETTINGS".equals(page)) settingsDraws++; else appearanceDraws++;
                if (samples.size() < 96) samples.add(page + ",root=" + Integer.toHexString(System.identityHashCode(root))
                        + ",visible=" + root.getVisibility() + ",textAlpha=" + root.getAlpha() + ",materialAlpha=" + material.getAlpha());
                if (reverseArmed && "APPEARANCE".equals(page) && root.getVisibility() == View.VISIBLE && root.isShown()) {
                    reverseArmed = false;
                    assertTrue("Reversal is armed during visible entry", root.getAlpha() < .95f);
                    // Queue native input after this actual draw; do not change the
                    // hierarchy from inside OnPreDraw or assign the expected focus.
                    decor.post(() -> {
                        try {
                            assertEquals("APPEARANCE", activity.currentScreenForTest());
                            assertTrue("Back interrupts a running transition", ((UiMotion) get(activity, "uiMotion")).isRunning());
                            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                            assertEquals("SETTINGS", activity.currentScreenForTest());
                            assertSame(returningRoot, get(activity, "root"));
                            assertMaterial();
                        } catch (AssertionError error) { if (failure == null) failure = error; }
                        finally { reversed.countDown(); }
                    });
                }
            } catch (AssertionError error) { if (failure == null) failure = error; reversed.countDown(); }
            return true;
        }
        void assertMaterial() {
            assertSame("One window-owned sheet survives rows-to-rows navigation", sheet, get(activity, "rowsSheet"));
            assertSame("Compatible pages reuse the exact glass face", face, sheet.findViewWithTag("shared-rows-glass-face"));
            assertSame(glass, face.getBackground()); assertSame(material, face.getParent());
            assertEquals(View.VISIBLE, material.getVisibility()); assertTrue(material.isShown());
            assertEquals("Rows-to-rows transitions never crossfade their material", 1f, material.getAlpha(), .001f);
            assertEquals(View.LAYER_TYPE_HARDWARE, face.getLayerType());
            assertTrue("The shared surface remains live", glass.isLiveBlurReady());
            assertEquals("Only the bounded glass face owns a hardware layer", 1, hardwareLayerCount(sheet));
            assertEquals(1, tagCount(decor, "shared-rows-glass-face"));
            assertNull("Fading text roots never contain the shared material", ((View) get(activity, "renderHost")).findViewWithTag("shared-rows-glass-face"));
        }
        void assertHealthy() { if (failure != null) throw failure; assertMaterial(); }
        void emit() {
            Bundle status = new Bundle(); status.putString("stream", "\nSHARED_ROWS_PRE_DRAW " + samples + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        }
    }

    private static int hardwareLayerCount(View view) {
        int count = view.getLayerType() == View.LAYER_TYPE_HARDWARE ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += hardwareLayerCount(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static int tagCount(View view, String tag) {
        int count = tag.equals(view.getTag()) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += tagCount(((ViewGroup) view).getChildAt(i), tag);
        return count;
    }
    private static int focusedCount(View view) {
        int count = view.isFocused() ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += focusedCount(((ViewGroup) view).getChildAt(i));
        return count;
    }

    private static final class Sample {
        final int frame, identity, visibility;
        final boolean attached, shown;
        final float alpha;
        Sample(int frame, View root) {
            this.frame = frame; identity = System.identityHashCode(root); visibility = root.getVisibility();
            attached = root.isAttachedToWindow(); shown = root.isShown(); alpha = root.getAlpha();
        }
        @Override public String toString() {
            return "frame=" + frame + ",root=" + Integer.toHexString(identity) + ",visibility=" + visibility
                    + ",attached=" + attached + ",shown=" + shown + ",alpha=" + alpha;
        }
    }

    private static final class EntryProbe implements ViewTreeObserver.OnPreDrawListener {
        final MainActivity activity;
        final View outgoing, decor;
        final List<Sample> samples = new ArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        View incoming;
        ViewTreeObserver observer;
        AssertionError failure;
        int frames;
        EntryProbe(MainActivity activity) {
            this.activity = activity; outgoing = (View) get(activity, "root"); decor = activity.getWindow().getDecorView();
        }
        void start() { observer = decor.getViewTreeObserver(); observer.addOnPreDrawListener(this); }
        void stop() { if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this); }
        @Override public boolean onPreDraw() {
            try {
                View current = (View) get(activity, "root");
                if (current == outgoing) return true;
                if (incoming == null) incoming = current;
                assertSame("One incoming Settings page owns this transition", incoming, current);
                assertEquals("The measured new root belongs to Settings", "SETTINGS", activity.currentScreenForTest());
                if (samples.size() < 128) samples.add(new Sample(++frames, current));
                else throw new AssertionError("Entry did not settle within 128 native draws");
                if (current.getVisibility() == View.VISIBLE && current.isShown() && current.getAlpha() > .95f
                        && !((UiMotion) get(activity, "uiMotion")).isRunning()
                        && ((ViewGroup) get(activity, "renderHost")).getChildCount() == 1) finished.countDown();
            } catch (AssertionError error) {
                if (failure == null) failure = error; finished.countDown();
            }
            return true;
        }
        void assertEntry() {
            if (failure != null) throw failure;
            assertNotNull("A different incoming root must really have appeared", incoming);
            Sample first = null, last = null; float previous = -1; int visible = 0;
            for (Sample sample : samples) {
                assertTrue("Every sampled alpha is finite", Float.isFinite(sample.alpha));
                if (sample.visibility != View.VISIBLE || !sample.shown || !sample.attached) continue;
                if (first == null) first = sample;
                assertTrue("Incoming alpha cannot fall from an opaque frame back to its starting value: " + samples,
                        sample.alpha + .01f >= previous);
                previous = sample.alpha; last = sample; visible++;
            }
            assertNotNull("At least one actual visible pre-draw is required", first);
            assertTrue("The first visible incoming draw must begin transparent: " + samples, first.alpha < .5f);
            assertTrue("A real fade has multiple observed draws", visible >= 2);
            assertNotNull(last); assertTrue("The incoming page finishes visible", last.alpha > .95f);
            assertSame(incoming, get(activity, "root"));
        }
        void emit() {
            StringBuilder trace = new StringBuilder("\nPAGE_ENTRY_PRE_DRAW outgoing=")
                    .append(Integer.toHexString(System.identityHashCode(outgoing))).append('\n');
            for (Sample sample : samples) trace.append(sample).append('\n');
            Bundle status = new Bundle(); status.putString("stream", trace.toString());
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        }
    }

    /** Frame-driven readiness; no delay offset determines which transition draw is sampled. */
    private static void awaitFrames(MainActivity activity, String message, Predicate<MainActivity> condition) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<AssertionError> failure = new AtomicReference<>();
        AtomicReference<Choreographer.FrameCallback> callback = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            callback.set(time -> {
                try { if (condition.test(activity)) { ready.countDown(); return; } }
                catch (AssertionError error) { failure.set(error); ready.countDown(); return; }
                Choreographer.getInstance().postFrameCallback(callback.get());
            });
            Choreographer.getInstance().postFrameCallback(callback.get());
        });
        try { assertTrue(message, ready.await(10, TimeUnit.SECONDS)); }
        finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Choreographer.getInstance().removeFrameCallback(callback.get())); }
        if (failure.get() != null) throw failure.get();
    }
    private static boolean settled(MainActivity activity) {
        View root = (View) get(activity, "root"); Future<?> load = (Future<?>) get(activity, "loadFuture");
        SharedRowsSheet sheet = (SharedRowsSheet) get(activity, "rowsSheet");
        return (Boolean) get(activity, "resumed") && activity.hasWindowFocus() && root != null && root.isLaidOut()
                && root.getVisibility() == View.VISIBLE && root.isShown() && root.getAlpha() > .95f
                && !root.isLayoutRequested() && load != null && load.isDone() && (Boolean) get(activity, "catalogLoaded")
                && !(Boolean) get(activity, "renderDirty") && !((UiMotion) get(activity, "uiMotion")).isRunning()
                && sheet != null && !sheet.isAnimating();
    }
    private static void assertWindow(MainActivity activity) {
        assertEquals("Actual UI window width", 3840, activity.getWindow().getDecorView().getWidth());
        assertEquals("Actual UI window height", 2160, activity.getWindow().getDecorView().getHeight());
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, .001f);
    }
    private static Object get(MainActivity activity, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) { Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value); }
        return copy;
    }
}
