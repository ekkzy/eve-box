package io.github.ekkzy.evebox;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.evebox.data.AppCatalog;
import io.github.ekkzy.evebox.data.AppEntry;
import io.github.ekkzy.evebox.data.LauncherPrefs;
import io.github.ekkzy.evebox.remote.RemoteButtonPreferences;
import io.github.ekkzy.evebox.remote.RemoteButtonPreferences.Button;
import io.github.ekkzy.evebox.ui.TvScrollView;
import io.github.ekkzy.evebox.ui.UiMotion;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

/** Local settings UI only: this suite never opens or enables an Android accessibility service. */
@RunWith(AndroidJUnit4.class)
public final class RemoteButtonSettingsTest {
    private static final String ALTERNATE = "io.github.ekkzy.evebox.fixture.remotevideo";
    private Context context;
    private UiDevice device;
    private SharedPreferences launcherStore, remoteStore;
    private Map<String, Object> originalLauncher, originalRemote, expectedLauncher;
    private RemoteButtonPreferences remote;
    private List<AppEntry> installed;
    private String accessibilityEnabled, accessibilityServices;

    @Before public void prepareOwnedDebugSettings() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.evebox.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        launcherStore = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        remoteStore = context.getSharedPreferences(RemoteButtonPreferences.FILE_NAME, Context.MODE_PRIVATE);
        originalLauncher = snapshot(launcherStore); originalRemote = snapshot(remoteStore);
        accessibilityEnabled = secure(Settings.Secure.ACCESSIBILITY_ENABLED);
        accessibilityServices = secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        assertTrue(launcherStore.edit().clear().commit()); assertTrue(remoteStore.edit().clear().commit());
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setLanguage("en"); prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); prefs.setBackground(18);
        prefs.setPaletteMode("dark"); prefs.setWallpaperDim(25); prefs.setReducedMotion(true); prefs.setSounds(false);
        prefs.setCity("TEST Remote City", 55.75, 37.62, "524901", "Europe/Moscow");
        prefs.setWeatherEnabled(false); prefs.setWatchNextVisible(false); prefs.setRecommendationsEnabled(false);
        installed = AppCatalog.load(context);
        assertFalse("Real installed apps seed favorites without migration", installed.isEmpty());
        List<String> favorites = new ArrayList<>();
        for (AppEntry app : installed) if (favorites.size() < 2) favorites.add(app.id);
        prefs.setFavorites(favorites); expectedLauncher = snapshot(launcherStore);
        remote = new RemoteButtonPreferences(context);
        assertFalse("A fresh local setting never enables remapping", remote.read().enabled);
    }

    @After public void restoreBothPreferenceFiles() {
        if (originalRemote != null) restore(remoteStore, originalRemote);
        if (originalLauncher != null) restore(launcherStore, originalLauncher);
        if (context != null && originalRemote != null) assertSystemAccessibilityUnchanged();
    }

    @Test public void dpadAppliesAndCancelsButtonChoicesWithoutChangingHomeOrSystemAccess() {
        List<AppEntry> catalog = new ArrayList<>(installed);
        catalog.add(app("ru.kinopoisk.tv", "First", "TEST Kinopoisk"));
        catalog.add(app("com.vk.tv", "First", "TEST VK Video"));
        catalog.add(app(ALTERNATE, "First", "TEST Alternate video"));
        catalog.add(app(ALTERNATE, "Second", "TEST Same package second activity"));
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            injectCatalog(scene, catalog); openRemote(scene);
            assertFalse(remote.read().enabled); assertHomePreferencesUnchanged();
            assertHelpFits(scene, "");
            capture(scene, "remote-button-settings");

            assertTrue(device.pressDPadDown()); awaitFocus(scene, "REMOTE", "remote-access");
            assertHelpFits(scene, "Accessibility");
            capture(scene, "remote-button-access-help");
            assertSystemAccessibilityUnchanged();
            assertTrue(device.pressDPadUp()); awaitFocus(scene, "REMOTE", "remote-enabled");

            assertTrue(device.pressDPadCenter());
            awaitFocus(scene, "REMOTE", "remote-enabled");
            assertTrue("The application flag persists independently of Android access", remote.read().enabled);
            assertSystemAccessibilityUnchanged();
            downTo(scene, "remote-netflix", 3);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE_TARGETS", "remote-target:ru.kinopoisk.tv");
            scene.onActivity(a -> assertEquals("One picker row per package", 1, countTag(activeRoot(a), "remote-target:" + ALTERNATE)));

            movePicker(scene, "remote-target-original", catalog);
            assertEquals("Focus alone does not apply", "ru.kinopoisk.tv", remote.read().netflixPackage);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE", "remote-netflix");
            assertEquals("", remote.read().netflixPackage); assertEquals("com.vk.tv", remote.read().primePackage);
            scene.onActivity(a -> assertTrue("Returned row reflects the saved original action", containsText(
                    tag(a, "remote-netflix"), "Original action")));
            assertHomePreferencesUnchanged();

            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE_TARGETS", "remote-target-original");
            movePicker(scene, "remote-target:" + ALTERNATE, catalog);
            assertEquals("", remote.read().netflixPackage);
            assertTrue(device.pressBack()); awaitFocus(scene, "REMOTE", "remote-netflix");
            assertEquals("Back cancels the merely focused target", "", remote.read().netflixPackage);
            scene.onActivity(a -> assertTrue(containsText(tag(a, "remote-netflix"), "Original action")));

            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE_TARGETS", "remote-target-original");
            movePicker(scene, "remote-target:" + ALTERNATE, catalog);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE", "remote-netflix");
            assertEquals(ALTERNATE, remote.read().netflixPackage); assertEquals("com.vk.tv", remote.read().primePackage);
            scene.onActivity(a -> assertTrue("Returned row must not reuse an obsolete cached assignment", containsText(
                    tag(a, "remote-netflix"), "TEST Alternate video")));
            scene.recreate(); await(scene, "Remote page returns after recreation", a -> dataReady(a)
                    && "REMOTE".equals(a.currentScreenForTest()) && focused(a, "remote-netflix"));
            assertTrue(remote.read().enabled); assertEquals(ALTERNATE, new RemoteButtonPreferences(context).read().netflixPackage);
            assertEquals("com.vk.tv", remote.read().primePackage);
            assertHomePreferencesUnchanged(); assertSystemAccessibilityUnchanged();
        }
    }

    @Test public void primePickerRecreationRestoresEditingButtonAndUnappliedFocus() {
        List<String> packages = uniquePackages(installed);
        assertTrue("Two real packages make cold catalog restoration meaningful", packages.size() >= 2);
        String selected = packages.get(0), candidate = packages.get(1);
        assertTrue(remote.setPackage(Button.PRIME, selected));
        Map<String, Object> before = snapshot(remoteStore);
        try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
            await(scene, "The real catalog finishes before opening settings", RemoteButtonSettingsTest::dataReady);
            openRemote(scene); downTo(scene, "remote-prime", 4);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE_TARGETS", "remote-target:" + selected);
            movePicker(scene, "remote-target:" + candidate, installed);
            assertEquals(before, snapshot(remoteStore));
            scene.onActivity(a -> {
                Bundle saved = new Bundle(); invokeSave(a, saved);
                assertEquals("PRIME", saved.getString("remote-button"));
                assertEquals("remote-target:" + candidate, saved.getString("focus"));
            });

            scene.recreate();
            await(scene, "Prime and its uncommitted target focus survive a cold catalog", a -> dataReady(a)
                    && "REMOTE_TARGETS".equals(a.currentScreenForTest()) && focused(a, "remote-target:" + candidate));
            scene.onActivity(a -> assertEquals(Button.PRIME, get(a, "remoteEditingButton")));
            assertEquals("Recreation cannot apply a merely focused target", before, snapshot(remoteStore));
            assertTrue(device.pressBack()); awaitFocus(scene, "REMOTE", "remote-prime");
            assertEquals(selected, remote.read().primePackage);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE_TARGETS", "remote-target:" + selected);
            movePicker(scene, "remote-target:" + candidate, installed);
            assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE", "remote-prime");
            assertEquals(candidate, remote.read().primePackage);
            assertEquals("Netflix must not be edited after recreating Prime", "ru.kinopoisk.tv", remote.read().netflixPackage);
            assertFalse(remote.read().enabled); assertHomePreferencesUnchanged(); assertSystemAccessibilityUnchanged();
        }
    }

    private void openRemote(ActivityScenario<MainActivity> scene) {
        await(scene, "Home favorites own initial focus", a -> settled(a) && "HOME".equals(a.currentScreenForTest())
                && a.getCurrentFocus() != null && String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-"));
        assertTrue(device.pressDPadDown()); awaitFocus(scene, "HOME", "shortcut-launcher");
        assertTrue(device.pressDPadCenter()); awaitFocus(scene, "SETTINGS", "settings-appearance");
        downTo(scene, "settings-advanced", 14);
        assertTrue(device.pressDPadCenter()); awaitFocus(scene, "ADVANCED", "advanced-language");
        downTo(scene, "advanced-remote", 12);
        assertTrue(device.pressDPadCenter()); awaitFocus(scene, "REMOTE", "remote-enabled");
    }

    private void downTo(ActivityScenario<MainActivity> scene, String wanted, int limit) {
        for (int i = 0; i < limit; i++) {
            String old = focusedTag(scene); if (wanted.equals(old)) return;
            assertTrue(device.pressDPadDown());
            await(scene, "Down moves from " + old, a -> settled(a) && a.getCurrentFocus() != null && !focused(a, old));
        }
        assertEquals(wanted, focusedTag(scene));
    }

    private void movePicker(ActivityScenario<MainActivity> scene, String wanted, List<AppEntry> catalog) {
        List<String> order = new ArrayList<>(); order.add("remote-target-original");
        for (String pkg : uniquePackages(catalog)) order.add("remote-target:" + pkg);
        assertTrue(order.contains(wanted));
        for (int attempts = 0; attempts <= order.size(); attempts++) {
            String current = focusedTag(scene); if (wanted.equals(current)) return;
            int index = order.indexOf(current), destination = order.indexOf(wanted);
            assertTrue("A known picker row owns focus: " + current, index >= 0);
            int step = destination > index ? 1 : -1;
            assertTrue(step > 0 ? device.pressDPadDown() : device.pressDPadUp());
            awaitFocus(scene, "REMOTE_TARGETS", order.get(index + step));
        }
        fail("Could not reach picker row " + wanted);
    }

    private void injectCatalog(ActivityScenario<MainActivity> scene, List<AppEntry> apps) {
        await(scene, "Real catalog callbacks finish before synthetic picker data", RemoteButtonSettingsTest::dataReady);
        scene.onActivity(a -> {
            set(a, "catalog", Collections.unmodifiableList(new ArrayList<>(apps)));
            @SuppressWarnings("unchecked") Map<String, AppEntry> index = (Map<String, AppEntry>) get(a, "appsById");
            index.clear(); for (AppEntry app : apps) index.put(app.id, app);
            invoke(a, "renderPreservingFocus");
        });
        await(scene, "Synthetic presentation settles", a -> settled(a) && a.getCurrentFocus() != null
                && String.valueOf(a.getCurrentFocus().getTag()).startsWith("favorite-"));
    }

    private static AppEntry app(String pkg, String activity, String label) { return new AppEntry(new ComponentName(pkg, pkg + "." + activity), label); }
    private static List<String> uniquePackages(List<AppEntry> apps) {
        Set<String> packages = new LinkedHashSet<>(); for (AppEntry app : apps) packages.add(app.packageName);
        return new ArrayList<>(packages);
    }
    private void assertHomePreferencesUnchanged() { assertEquals("Button settings do not change Home, favorites, weather or sources", expectedLauncher, snapshot(launcherStore)); }
    private String secure(String key) { return Settings.Secure.getString(context.getContentResolver(), key); }
    private void assertSystemAccessibilityUnchanged() {
        assertEquals(accessibilityEnabled, secure(Settings.Secure.ACCESSIBILITY_ENABLED));
        assertEquals("The test never invokes remote-access", accessibilityServices, secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }
    private static void awaitFocus(ActivityScenario<MainActivity> scene, String screen, String key) {
        await(scene, "Focus " + screen + "/" + key, a -> settled(a) && screen.equals(a.currentScreenForTest()) && focused(a, key));
    }
    private static boolean dataReady(MainActivity a) {
        Future<?> load = (Future<?>) get(a, "loadFuture");
        return settled(a) && load != null && load.isDone() && (Boolean) get(a, "catalogLoaded");
    }
    private static boolean settled(MainActivity a) {
        View root = activeRoot(a);
        if (root == null || !root.isLaidOut() || root.isLayoutRequested() || root.getVisibility() != View.VISIBLE
                || root.getAlpha() < .999f || !a.hasWindowFocus() || (Boolean) get(a, "renderDirty")
                || ((UiMotion) get(a, "uiMotion")).isRunning()) return false;
        TvScrollView home = (TvScrollView) get(a, "homeScroll"); return home == null || !home.isScrollAnimating();
    }
    private static void await(ActivityScenario<MainActivity> scene, String message, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8000; AtomicBoolean ready = new AtomicBoolean();
        do {
            scene.onActivity(a -> ready.set(condition.test(a)));
            if (ready.get()) { scene.onActivity(RemoteButtonSettingsTest::assertFocusVisible); return; }
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message + "; actual focus=" + focusedTag(scene));
    }
    private static void assertFocusVisible(MainActivity a) {
        View focus = a.getCurrentFocus(); assertNotNull(focus); assertTrue(focus.isShown());
        assertEquals(1, focusCount(a.getWindow().getDecorView()));
        RectF bounds = globalBounds(focus), window = globalBounds(a.getWindow().getDecorView());
        assertInside(bounds, window);
        for (ViewParent parent = focus.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent instanceof ScrollView) assertInside(bounds, globalBounds((View) parent));
    }
    private static RectF globalBounds(View view) {
        Matrix matrix = new Matrix(); view.transformMatrixToGlobal(matrix);
        RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight()); matrix.mapRect(bounds); return bounds;
    }
    private static void assertInside(RectF focus, RectF viewport) {
        assertTrue("Focused surface " + focus + " inside " + viewport, focus.left >= viewport.left - 2
                && focus.top >= viewport.top - 2 && focus.right <= viewport.right + 2 && focus.bottom <= viewport.bottom + 2);
    }
    private static int focusCount(View view) { int count = view.isFocused() ? 1 : 0; if (view instanceof ViewGroup)
        for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += focusCount(((ViewGroup) view).getChildAt(i)); return count; }
    private static int countTag(View view, String key) { int count = key.equals(view.getTag()) ? 1 : 0; if (view instanceof ViewGroup)
        for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countTag(((ViewGroup) view).getChildAt(i), key); return count; }
    private static boolean containsText(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsText(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }
    private static View tag(MainActivity a, String key) { View view = activeRoot(a).findViewWithTag(key); assertNotNull(key, view); return view; }
    private static boolean focused(MainActivity a, String key) { return a.getCurrentFocus() != null && key.equals(a.getCurrentFocus().getTag()); }
    private static String focusedTag(ActivityScenario<MainActivity> scene) {
        AtomicReference<String> tag = new AtomicReference<>(); scene.onActivity(a -> tag.set(a.getCurrentFocus() == null ? "null" : String.valueOf(a.getCurrentFocus().getTag()))); return tag.get();
    }
    private static View activeRoot(MainActivity a) { return (View) get(a, "root"); }
    private static void assertHelpFits(ActivityScenario<MainActivity> scene, String expectedText) {
        scene.onActivity(a -> {
            TextView help = (TextView) get(a, "detailDescription");
            assertNotNull("Focused row has a help description", help);
            assertTrue("Help remains visible beside the rows", help.isShown() && help.getAlpha() > .99f);
            String text = help.getText().toString(); assertFalse("Help is nonempty", text.trim().isEmpty());
            if (!expectedText.isEmpty()) assertTrue("Help explains " + expectedText, text.contains(expectedText));
            assertInside(globalBounds(help), globalBounds(a.getWindow().getDecorView()));
            android.text.Layout layout = help.getLayout(); assertNotNull("Help has native text layout", layout);
            assertTrue(layout.getLineCount() > 0);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("Help line must not be ellipsized: " + text, 0, layout.getEllipsisCount(line));
                assertTrue("Every help line fits vertically", layout.getLineBottom(line)
                        <= help.getHeight() - help.getCompoundPaddingTop() - help.getCompoundPaddingBottom());
            }
            assertEquals("All help text is laid out, not silently hidden by maxLines", text.length(),
                    layout.getLineEnd(layout.getLineCount() - 1));
        });
    }
    private void capture(ActivityScenario<MainActivity> scene, String name) {
        CountDownLatch frame = new CountDownLatch(1);
        scene.onActivity(a -> {
            assertEquals(3840, a.getWindow().getDecorView().getWidth()); assertEquals(2160, a.getWindow().getDecorView().getHeight());
            View root = activeRoot(a); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        try { assertTrue(frame.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        String run = InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun", "screenshots");
        assertTrue(run.matches("[A-Za-z0-9_-]+")); File folder = new File(context.getExternalFilesDir(null), run);
        assertTrue(folder.isDirectory() || folder.mkdirs()); assertTrue(device.takeScreenshot(new File(folder, name + ".png")));
    }
    private static Object get(MainActivity a, String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(a); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static void set(MainActivity a, String name, Object value) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(a, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static void invoke(MainActivity a, String name) {
        try { Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(a); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
    private static void invokeSave(MainActivity a, Bundle out) {
        try { Method method = MainActivity.class.getDeclaredMethod("onSaveInstanceState", Bundle.class); method.setAccessible(true); method.invoke(a, out); }
        catch (ReflectiveOperationException error) { throw new AssertionError("onSaveInstanceState", error); }
    }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) values.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
    }
    private static void restore(SharedPreferences prefs, Map<String, Object> saved) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) { @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value; edit.putStringSet(key, new HashSet<>(strings)); }
            else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(edit.commit()); assertEquals(saved, snapshot(prefs));
    }
}
