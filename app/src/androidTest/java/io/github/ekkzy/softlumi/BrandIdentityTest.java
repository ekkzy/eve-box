package io.github.ekkzy.softlumi;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.SettingsBackup;
import io.github.ekkzy.softlumi.remote.RemoteButtonService;
import io.github.ekkzy.softlumi.ui.SharedRowsSheet;
import io.github.ekkzy.softlumi.ui.TvScrollView;
import io.github.ekkzy.softlumi.ui.UiMotion;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native branding paths; no release preferences, enabled services or document providers are changed. */
@RunWith(AndroidJUnit4.class)
public final class BrandIdentityTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences store;
    private Map<String, Object> original;

    @Before public void requireOwnedDebug4k() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.ekkzy.softlumi.debug", context.getPackageName());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(3840, device.getDisplayWidth()); assertEquals(2160, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        store = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE);
        original = snapshot();
    }

    @After public void restoreExactPreferences() {
        if (original == null) return;
        SharedPreferences.Editor editor = store.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(values));
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit()); assertEquals(original, snapshot());
    }

    @Test public void installedAppAndDeclaredAccessibilityMetadataUseTheCurrentBrandInBothLanguages() throws Exception {
        PackageManager packages = context.getPackageManager();
        assertEquals("Eve Box", packages.getApplicationLabel(context.getApplicationInfo()).toString());
        ServiceInfo service = packages.getServiceInfo(new ComponentName(context, RemoteButtonService.class), PackageManager.GET_META_DATA);
        assertTrue(service.loadLabel(packages).toString().contains("Eve Box"));
        int description;
        try (XmlResourceParser metadata = service.loadXmlMetaData(packages, AccessibilityService.SERVICE_META_DATA)) {
            assertNotNull(metadata);
            int event;
            do { event = metadata.next(); } while (event != XmlResourceParser.START_TAG && event != XmlResourceParser.END_DOCUMENT);
            assertEquals("Accessibility metadata has a root element", XmlResourceParser.START_TAG, event);
            description = metadata.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "description", 0);
        }
        assertNotEquals("The Android settings description resolves from service metadata", 0, description);
        for (String language : new String[] {"ru", "en"}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(language));
            Resources localized = context.createConfigurationContext(configuration).getResources();
            assertEquals("ru".equals(language) ? "Кнопки пульта Eve Box" : "Eve Box remote buttons", localized.getString(service.labelRes));
            assertTrue(localized.getString(description).contains("Eve Box"));
            assertFalse(localized.getString(description).contains("softlumi"));
        }
    }

    @Test public void settingsAboutRemoteHelpAndExportKeepTheirBrandAndFocusInBothPalettesAndLanguages() throws Exception {
        for (String language : new String[] {"ru", "en"}) for (String palette : new String[] {"dark", "light"}) {
            assertTrue(store.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
            LauncherPrefs preferences = new LauncherPrefs(context);
            preferences.setLanguage(language); preferences.setPaletteMode(palette); preferences.setBackground(29);
            preferences.setReducedMotion(true); preferences.setSounds(false); preferences.setWeatherEnabled(false);
            preferences.setWatchNextVisible(false); preferences.setRecommendationsEnabled(false); preferences.setFavorites(Collections.emptyList());
            String settings = "ru".equals(language) ? "Настройки Eve Box" : "Eve Box settings";
            try (ActivityScenario<MainActivity> scene = ActivityScenario.launch(MainActivity.class)) {
                await(scene, "HOME");
                scene.onActivity(a -> assertEquals(settings, target(a, "shortcut-launcher").getContentDescription().toString()));
                activate(scene, "shortcut-launcher", "SETTINGS");
                scene.onActivity(a -> {
                    assertEquals("Light and dark are real rendered palettes", "light".equals(palette), get(get(a, "palette"), "light"));
                    assertTrue(containsText(root(a), settings)); assertTrue(assertBrandTextFits(root(a)) >= 2);
                });
                capture(scene, "brand-settings-" + language + "-" + palette);
                activate(scene, "settings-advanced", "ADVANCED");
                activate(scene, "advanced-about", "ABOUT");
                scene.onActivity(a -> {
                    assertTrue(containsText(root(a), "Eve Box  " + BuildConfig.VERSION_NAME));
                    assertTrue(assertBrandTextFits(root(a)) >= 2);
                });
                back(scene, "ADVANCED", "advanced-about");
                activate(scene, "advanced-remote", "REMOTE");
                scene.onActivity(a -> assertTrue(target(a, "remote-access").requestFocus()));
                await(scene, "REMOTE");
                scene.onActivity(a -> {
                    TextView help = (TextView) get(a, "detailDescription");
                    assertTrue(help.getText().toString().contains("Eve Box")); assertTextFits(help);
                    assertTrue(assertBrandTextFits(root(a)) >= 2);
                });
                back(scene, "ADVANCED", "advanced-remote");
                activate(scene, "advanced-backup", "BACKUP");
                assertExportAction(scene);
                back(scene, "ADVANCED", "advanced-backup");
                back(scene, "SETTINGS", "settings-advanced");
            }
        }
    }

    private void assertExportAction(ActivityScenario<MainActivity> scene) {
        Map<String, Object> before = snapshot();
        AtomicReference<Intent> exported = new AtomicReference<>();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
                exported.set(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            scene.onActivity(a -> assertTrue(target(a, "backup-export").requestFocus()));
            await(scene, "BACKUP"); assertTrue(device.pressDPadCenter());
            instrumentation.waitForIdleSync();
            Intent intent = exported.get(); assertNotNull("Export uses the Android document contract", intent);
            assertEquals("application/json", intent.getType()); assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("eve-box-settings.json", intent.getStringExtra(Intent.EXTRA_TITLE));
            scene.onActivity(a -> assertTrue("Existing backup schema remains valid", SettingsBackup.preview((String) get(a, "pendingExportJson")).valid));
            await(scene, "BACKUP");
            scene.onActivity(a -> assertEquals("backup-export", a.getCurrentFocus().getTag()));
            assertEquals("Cancelling export does not change settings", before, snapshot());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void activate(ActivityScenario<MainActivity> scene, String key, String destination) {
        scene.onActivity(a -> assertTrue(key, target(a, key).requestFocus()));
        AtomicReference<String> current = new AtomicReference<>(); scene.onActivity(a -> current.set(a.currentScreenForTest()));
        await(scene, current.get()); assertTrue(device.pressDPadCenter()); await(scene, destination);
    }
    private void back(ActivityScenario<MainActivity> scene, String destination, String key) {
        assertTrue(device.pressBack()); await(scene, destination);
        scene.onActivity(a -> assertEquals("Back restores the owning row", key, a.getCurrentFocus().getTag()));
    }
    private static void await(ActivityScenario<MainActivity> scene, String screen) {
        long deadline = SystemClock.uptimeMillis() + 10_000; AtomicBoolean ready = new AtomicBoolean();
        do {
            scene.onActivity(a -> {
                View root = root(a), rows = (View) get(a, "settingsRows");
                TvScrollView home = (TvScrollView) get(a, "homeScroll");
                ready.set(screen.equals(a.currentScreenForTest()) && a.hasWindowFocus() && (Boolean) get(a, "catalogLoaded") && root != null && root.isLaidOut()
                        && !root.isLayoutRequested() && root.getAlpha() >= .999f && !(Boolean) get(a, "renderDirty")
                        && !((UiMotion) get(a, "uiMotion")).isRunning() && !((SharedRowsSheet) get(a, "rowsSheet")).isAnimating()
                        && (home == null || !home.isScrollAnimating())
                        && (rows == null || !(rows.getParent() instanceof TvScrollView) || !((TvScrollView) rows.getParent()).isScrollAnimating())
                        && a.getCurrentFocus() != null && a.getCurrentFocus().isShown());
            });
            if (ready.get()) return; SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(screen + " did not settle");
    }
    private static int assertBrandTextFits(View view) {
        int count = 0;
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            assertFalse("No old brand in the rendered settings: " + text, text.contains("softlumi") || text.contains(" SL") || text.startsWith("SL "));
            if (text.contains("Eve Box")) { assertTextFits((TextView) view); count++; }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += assertBrandTextFits(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static void assertTextFits(TextView text) {
        assertTrue("Branded text is visible", text.isShown());
        Layout layout = text.getLayout(); assertNotNull(layout); assertTrue(layout.getLineCount() > 0);
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        int height = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("Branded text is not ellipsized: " + text.getText(), 0, layout.getEllipsisCount(line));
            assertTrue("Branded text fits horizontally: " + text.getText(), layout.getLineWidth(line) <= width + 1);
            assertTrue("Branded text fits vertically: " + text.getText(), layout.getLineBottom(line) <= height);
        }
        assertEquals(text.getText().length(), layout.getLineEnd(layout.getLineCount() - 1));
        Matrix matrix = new Matrix(); text.transformMatrixToGlobal(matrix);
        RectF actual = new RectF(0, 0, text.getWidth(), text.getHeight()); matrix.mapRect(actual);
        View window = text.getRootView();
        assertTrue("Branded text stays inside the real window", actual.left >= -1 && actual.top >= -1
                && actual.right <= window.getWidth() + 1 && actual.bottom <= window.getHeight() + 1);
    }
    private void capture(ActivityScenario<MainActivity> scene, String name) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        scene.onActivity(a -> {
            assertEquals(3840, a.getWindow().getDecorView().getWidth()); assertEquals(2160, a.getWindow().getDecorView().getHeight());
            assertTrue(root(a).isHardwareAccelerated()); root(a).getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root(a).invalidate();
        });
        assertTrue(committed.await(3, TimeUnit.SECONDS));
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue(run.matches("[A-Za-z0-9_-]+")); File directory = new File(context.getExternalFilesDir(null), run);
        assertTrue(directory.isDirectory() || directory.mkdirs()); assertTrue(device.takeScreenshot(new File(directory, name + ".png")));
    }
    private Map<String, Object> snapshot() {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : store.getAll().entrySet()) copy.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }
    private static View root(MainActivity activity) { return (View) get(activity, "root"); }
    private static View target(MainActivity activity, String key) { View view = root(activity).findViewWithTag(key); assertNotNull(key, view); return view; }
    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (containsText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
