package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Emulator-only regression of real Main View ownership, not just the spring arithmetic. */
@RunWith(AndroidJUnit4.class)
@android.annotation.TargetApi(33)
public final class MotionContinuityTest {
    private UiDevice device;
    private String previousAnimator;
    private float previousEffective;
    private boolean animatorCaptured;

    @Before public void prepareComparableHome() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Never run this preference-mutating fixture on a physical device", "1",
                device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug", context.getPackageName());
        Assume.assumeTrue("Effective animator policy restoration uses API 33", Build.VERSION.SDK_INT >= 33);
        previousAnimator = device.executeShellCommand("settings get global animator_duration_scale").trim();
        if (!"null".equals(previousAnimator)) {
            assertTrue("Animator setting must be a numeric shell-safe value", previousAnimator.matches("[0-9.+eE-]+"));
            float rawScale = Float.parseFloat(previousAnimator);
            assertTrue(Float.isFinite(rawScale) && rawScale >= 0);
        }
        AtomicReference<Float> effective = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> effective.set(ValueAnimator.getDurationScale()));
        previousEffective = effective.get(); animatorCaptured = true;
        device.executeShellCommand("settings put global animator_duration_scale 1");
        await("Effective animator scale 1", () -> animatorPolicy(1), 4000);
        context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        LauncherPrefs preferences = new LauncherPrefs(context);
        preferences.setWeatherEnabled(false); preferences.setWatchNextVisible(false);
        preferences.setSounds(false); preferences.setReducedMotion(false);
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : AppCatalog.load(context)) if (favorites.size() < 8) favorites.add(app.id);
        assertTrue("At least two real installed apps required", favorites.size() >= 2);
        preferences.setFavorites(favorites);
    }

    @After public void restoreAnimatorPolicy() throws Exception {
        if (!animatorCaptured) return;
        if ("null".equals(previousAnimator)) {
            // Removing an unset key alone can retain the last effective WMS value.
            device.executeShellCommand("settings put global animator_duration_scale " + previousEffective);
            await("Restore effective animator scale", () -> animatorPolicy(previousEffective), 4000);
            device.executeShellCommand("settings delete global animator_duration_scale");
        } else {
            device.executeShellCommand("settings put global animator_duration_scale " + previousAnimator);
            await("Restore effective animator scale", () -> animatorPolicy(previousEffective), 4000);
        }
    }

    @Test public void rapidSettingsBackReusesHomeAndRetargetsItsCurrentAlpha() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            AtomicReference<FrameLayout> originalRoot = new AtomicReference<>(), originalBoard = new AtomicReference<>();
            scenario.onActivity(activity -> {
                assertTrue(tile(activity, 0).requestFocus());
                originalRoot.set((FrameLayout) get(activity, "root"));
                originalBoard.set((FrameLayout) get(activity, "board"));
            });
            settled(scenario);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch reversed = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                View settings=activity.getWindow().getDecorView().findViewWithTag("shortcut-launcher");
                assertNotNull("The actual Home shortcuts own the launcher settings action",settings);
                assertTrue(settings.requestFocus());assertTrue(settings.performClick());
                assertEquals("SETTINGS", get(activity, "screen").toString());
                // Arm before returning from dispatch: a later onActivity/idle wait
                // can otherwise delay the probe until the transition has finished.
                long deadline = SystemClock.uptimeMillis() + 3000;
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                    @Override public void doFrame(long time) {
                        try {
                            FrameLayout home = originalRoot.get();
                            assertTrue("Original Home is still attached during handover", home.isAttachedToWindow());
                            float alpha = home.getAlpha();
                            if (!(motion(activity).isRunning() && alpha > 0 && alpha < .9999f)) {
                                assertTrue("First fading frame must arrive within the bounded probe", SystemClock.uptimeMillis() < deadline);
                                Choreographer.getInstance().postFrameCallback(this); return;
                            }
                            activity.onBackPressed();
                            assertEquals("HOME", get(activity, "screen").toString());
                            assertSame("Rapid Back must reactivate the retained Home root", home, get(activity, "root"));
                            assertSame("Retain the existing board and its native presentation", originalBoard.get(), get(activity, "board"));
                            assertEquals("Retarget from current alpha without resetting to 0 or 1", alpha, home.getAlpha(), .0001f);
                        } catch (Throwable problem) { failure.set(problem); }
                        reversed.countDown();
                    }
                });
            });
            assertTrue("Bounded rapid reversal callback", reversed.await(4, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Rapid scene continuity", failure.get());
            settled(scenario);
            scenario.onActivity(activity -> {
                assertSame("An obsolete fade callback must not remove restored Home", originalRoot.get(), get(activity, "root"));
                assertTrue(originalRoot.get().isAttachedToWindow());
                assertEquals(1, originalRoot.get().getAlpha(), .0001f);
                assertEquals("Completed visual handover must release obsolete roots", 1,
                        ((FrameLayout) get(activity, "renderHost")).getChildCount());
                assertEquals(0, motion(activity).runningChannelCount());
            });
        }
    }

    @Test public void dueDataRefreshPreservesMovingFocusThenRestoresWithoutScaleRestart() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            scenario.onActivity(activity -> assertTrue(tile(activity, 0).requestFocus()));
            settled(scenario);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch checked = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                assertTrue(tile(activity, 1).requestFocus());
                // UiMotion's frame callback was posted by requestFocus first. Sample
                // after that first actual animation step instead of a wall-clock guess.
                Choreographer.getInstance().postFrameCallback(frameTime -> {
                    try {
                View focused = tile(activity, 1);
                assertSame(focused, activity.getCurrentFocus());
                assertTrue("Focus movement must be active before the refresh", motion(activity).runningChannelCount() > 0);
                float before = focused.getScaleX();
                assertTrue(before > 1.0001f && before < 1.20f);
                FrameLayout root = (FrameLayout) get(activity, "root");
                invoke(activity, "scheduleDataRender");
                // Drive the production scheduled callback as if already due; remove
                // its pending timer first so this does not create a second caller.
                Runnable refresh = (Runnable) get(activity, "dataRender");
                ((Handler) get(activity, "main")).removeCallbacks(refresh);
                refresh.run();
                assertSame("Data must not replace a root during focus movement", root, get(activity, "root"));
                assertSame("Keep the actual focused native View while it moves", focused, tile(activity, 1));
                assertEquals("Refresh must not reset current scale", before, focused.getScaleX(), .0001f);
                    } catch (Throwable problem) { failure.set(problem); }
                    finally { checked.countDown(); }
                });
            });
            assertTrue("Bounded first-frame data refresh check", checked.await(4, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Data refresh focus continuity", failure.get());
            AtomicBoolean ready = new AtomicBoolean();
            await("Deferred data refresh and restored settled focus", () -> {
                scenario.onActivity(activity -> {
                    View focused = tile(activity, 1);
                    ready.set(!(Boolean) get(activity, "renderDirty") && activity.getCurrentFocus() == focused
                            && !motion(activity).isRunning() && motion(activity).runningChannelCount() == 0
                            && Math.abs(focused.getScaleX() - 1.20f) < .0001f);
                });
                return ready.get();
            }, 8000);
            scenario.onActivity(activity -> assertEquals("No obsolete visual layers after data refresh", 1,
                    ((FrameLayout) get(activity, "renderHost")).getChildCount()));
        }
    }

    @Test public void threeRapidRoutesAndBackChainReleaseEveryObsoleteRoot() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            AtomicReference<FrameLayout> obsoleteAppearance = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch reversed = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                push(activity, "SETTINGS");
                onEntryFrame(activity, "SETTINGS", () -> {
                    push(activity, "APPEARANCE");
                    onEntryFrame(activity, "APPEARANCE", () -> {
                        obsoleteAppearance.set((FrameLayout) get(activity, "root"));
                        // No idle synchronization between these two Back operations:
                        // it would hide B's obsolete cleanup callback being replaced.
                        activity.onBackPressed();
                        assertEquals("SETTINGS", get(activity, "screen").toString());
                        activity.onBackPressed();
                        assertEquals("HOME", get(activity, "screen").toString());
                        reversed.countDown();
                    }, failure, reversed);
                }, failure, reversed);
            });
            assertTrue("Bounded three-route callback chain", reversed.await(6, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Three-route visual ownership", failure.get());
            settled(scenario);
            scenario.onActivity(activity -> {
                FrameLayout current = (FrameLayout) get(activity, "root");
                assertTrue(current.isAttachedToWindow()); assertEquals(1, current.getAlpha(), .0001f);
                assertEquals("Every obsolete visual layer must be removed after the chain", 1,
                        ((FrameLayout) get(activity, "renderHost")).getChildCount());
                assertNull("C must not remain opaque behind the current Home", obsoleteAppearance.get().getParent());
                assertEquals(0, motion(activity).runningChannelCount());
            });
        }
    }

    @Test public void dataPublishedBeforePushDoesNotBecomeFalseRenderedSignature() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ready(scenario);
            AtomicReference<String> fixtureName = new AtomicReference<>();
            scenario.onActivity(activity -> {
                String rendered = (String) get(activity, "renderedDataSignature");
                assertEquals(rendered, invokeValue(activity, "pageDataSignature"));
                View originalTile = tile(activity, 0);
                String originalDescription = originalTile.getContentDescription().toString();
                LauncherPrefs.Settings settings = (LauncherPrefs.Settings) get(activity, "prefs");
                String id = settings.favoriteIds.get(0);
                @SuppressWarnings("unchecked") Map<String, AppEntry> byId = (Map<String, AppEntry>) get(activity, "appsById");
                AppEntry original = byId.get(id); assertNotNull(original);
                // Explicit test-only catalog publication, retaining a real installed
                // component/id. No preferences, history or weather are invented.
                String label = "CONTINUITY CATALOG FIXTURE " + original.label;
                AppEntry updated = new AppEntry(original.componentName, label);
                @SuppressWarnings("unchecked") List<AppEntry> existing = (List<AppEntry>) get(activity, "catalog");
                List<AppEntry> replacement = new ArrayList<>(existing);
                int position = -1;
                for (int i = 0; i < replacement.size(); i++) if (replacement.get(i).id.equals(id)) position = i;
                assertTrue(position >= 0); replacement.set(position, updated);
                set(activity, "catalog", replacement); byId.put(id, updated); fixtureName.set(label);
                String published = (String) invokeValue(activity, "pageDataSignature");
                assertNotEquals("Fixture publication must change repository data", rendered, published);
                invoke(activity, "scheduleDataRender");
                assertEquals("Old pixels have not been rebound yet", originalDescription,
                        originalTile.getContentDescription().toString());
                push(activity, "SETTINGS");
                Object retained = get(activity, "retainedPage"); assertNotNull(retained);
                assertEquals("Retained signature must describe the old rendered pixels", rendered, get(retained, "dataSignature"));
                assertNotEquals("Published data cannot be labelled as already rendered", published, get(retained, "dataSignature"));
                activity.onBackPressed();
                assertEquals("HOME", get(activity, "screen").toString());
                assertTrue("Pending publication must survive route restoration", (Boolean) get(activity, "renderDirty"));
            });
            AtomicBoolean refreshed = new AtomicBoolean();
            await("Retained Home eventually binds the published catalog fixture", () -> {
                scenario.onActivity(activity -> {
                    View current = tile(activity, 0);
                    refreshed.set(!(Boolean) get(activity, "renderDirty") && !motion(activity).isRunning()
                            && current.getContentDescription().toString().contains(fixtureName.get())
                            && get(activity, "renderedDataSignature").equals(invokeValue(activity, "pageDataSignature")));
                });
                return refreshed.get();
            }, 8000);
            scenario.onActivity(activity -> assertEquals(1, ((FrameLayout) get(activity, "renderHost")).getChildCount()));
        }
    }

    private static void onEntryFrame(MainActivity activity, String screen, Runnable action,
                                     AtomicReference<Throwable> failure, CountDownLatch completion) {
        long deadline = SystemClock.uptimeMillis() + 3000;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long time) {
                try {
                    assertEquals(screen, get(activity, "screen").toString());
                    float alpha=((FrameLayout) get(activity, "root")).getAlpha();
                    if (motion(activity).isRunning() && alpha>0 && alpha < .9999f) {
                        action.run(); return;
                    }
                    assertTrue("Actual " + screen + " entry frame within bounded probe", SystemClock.uptimeMillis() < deadline);
                    Choreographer.getInstance().postFrameCallback(this);
                } catch (Throwable problem) { failure.set(problem); completion.countDown(); }
            }
        });
    }

    private static void ready(ActivityScenario<MainActivity> scenario) {
        AtomicBoolean ready = new AtomicBoolean();
        await("Initial real catalog and settled Home", () -> {
            scenario.onActivity(activity -> {
                Future<?> future = (Future<?>) get(activity, "loadFuture");
                ready.set((Boolean) get(activity, "catalogLoaded") && future != null && future.isDone()
                        && !(Boolean) get(activity, "renderDirty") && !motion(activity).isRunning()
                        && "HOME".equals(get(activity, "screen").toString()));
            });
            return ready.get();
        }, 10000);
    }

    private static void settled(ActivityScenario<MainActivity> scenario) {
        AtomicBoolean ready = new AtomicBoolean();
        await("All native motion channels settle", () -> {
            scenario.onActivity(activity -> ready.set(!motion(activity).isRunning() && motion(activity).runningChannelCount() == 0));
            return ready.get();
        }, 6000);
    }

    private static boolean animatorPolicy(float expected) {
        AtomicBoolean match = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> match.set(
                Math.abs(ValueAnimator.getDurationScale() - expected) < .0001f
                        && (expected == 0 || ValueAnimator.areAnimatorsEnabled())));
        return match.get();
    }
    private static View tile(MainActivity activity, int index) {
        View result = ((FrameLayout) get(activity, "board")).findViewWithTag("favorite-" + index);
        assertNotNull("Current board favorite " + index, result); return result;
    }
    private static UiMotion motion(MainActivity activity) { return (UiMotion) get(activity, "uiMotion"); }
    private static Object get(Object instance, String name) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Diagnostic field " + name, failure); }
    }
    private static void invoke(Object instance, String name) {
        invokeValue(instance, name);
    }
    private static Object invokeValue(Object instance, String name) {
        try { Method method = instance.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Production callback " + name, failure); }
    }
    private static void set(Object instance, String name, Object value) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Fixture field " + name, failure); }
    }
    private static void push(MainActivity activity, String name) {
        try {
            Class<?> screenType = Class.forName(MainActivity.class.getName() + "$Screen");
            Object requested = null;
            for (Object value : screenType.getEnumConstants()) if (name.equals(value.toString())) requested = value;
            assertNotNull(name, requested);
            Method method = MainActivity.class.getDeclaredMethod("push", screenType); method.setAccessible(true); method.invoke(activity, requested);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Production route " + name, failure); }
    }
    private static void await(String label, BooleanSupplier predicate, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        do { if (predicate.getAsBoolean()) return; SystemClock.sleep(25); }
        while (SystemClock.uptimeMillis() < until);
        fail(label + " timed out");
    }
}
