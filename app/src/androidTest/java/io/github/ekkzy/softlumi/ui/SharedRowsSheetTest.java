package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import io.github.ekkzy.softlumi.MainActivity;
import io.github.ekkzy.softlumi.data.LauncherPrefs;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual GPU crops of the bounded hardware layer, with a disjoint live native source. */
@RunWith(AndroidJUnit4.class)
public final class SharedRowsSheetTest {
    private static final int WIDTH = 3840, HEIGHT = 2160, SCALE = 3;
    private static final SharedRowsSheet.Spec DARK = new SharedRowsSheet.Spec(80, 100, 400, 240, 28, false);
    private static final SharedRowsSheet.Spec LIGHT = new SharedRowsSheet.Spec(80, 100, 400, 240, 28, true);
    private static final int REFERENCE_LEFT = 2200, CROP = 160, MARGIN = 8;
    private SharedPreferences storage;
    private Map<String, Object> original;

    @Before public void prepareOwned4kWindow() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never mutate release preferences", "io.github.ekkzy.softlumi.debug", context.getPackageName());
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Owned emulator only", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
        assertEquals(34, Build.VERSION.SDK_INT);
        assertEquals(WIDTH, device.getDisplayWidth()); assertEquals(HEIGHT, device.getDisplayHeight());
        assertEquals(1f, context.getResources().getConfiguration().fontScale, .001f);
        main(() -> { assertTrue(ValueAnimator.areAnimatorsEnabled()); assertEquals(1f, ValueAnimator.getDurationScale(), .001f); });
        storage = context.getSharedPreferences(LauncherPrefs.FILE_NAME, Context.MODE_PRIVATE); original = snapshot(storage);
        assertTrue(storage.edit().clear().putInt("schema_version", LauncherPrefs.SCHEMA_VERSION).commit());
        LauncherPrefs prefs = new LauncherPrefs(context);
        prefs.setCardFormat(LauncherPrefs.CARD_FORMAT_LANDSCAPE); prefs.setBackground(0); prefs.setPaletteMode("dark"); prefs.setLanguage("en");
        prefs.setWeatherEnabled(false); prefs.setWatchNextVisible(false); prefs.setSounds(false); prefs.setReducedMotion(false);
    }

    @After public void restoreAllPreferences() {
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

    @Test public void boundedHardwareFaceRemainsLiveAndReleasesAcrossOwnerLifecycleAndSpecChanges() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, DARK);
            try {
                awaitFrames("The hardware face has a real live source", () -> fixture.glass().isLiveBlurReady());
                View first = onMain(fixture::face); GlassDrawable firstGlass = onMain(fixture::glass);
                main(fixture::assertOneBoundedLayer);
                RectF bounds = onMain(() -> bounds(fixture.face(), fixture.root));
                Rect inside = new Rect(Math.round(bounds.centerX()) - 128, Math.round(bounds.centerY()) - 16,
                        Math.round(bounds.centerX()) + 128, Math.round(bounds.centerY()) + 16);
                Rect rawRegion = new Rect(inside); rawRegion.offset(0, Math.round(bounds.height()) + 120);

                // Only the actual source View is invalidated. Invalidating the
                // glass face here would conceal a stale hardware-layer cache.
                main(() -> fixture.pattern.setMode(Pattern.CHECKER)); nextFrame(); nextFrame();
                Bitmap blurred = copy(fixture, inside), raw = copy(fixture, rawRegion);
                try {
                    save(fixture, blurred, "test-shared-rows-checker-blurred"); save(fixture, raw, "test-shared-rows-checker-raw");
                    float detail = adjacentDetail(raw), filtered = adjacentDetail(blurred);
                    assertTrue("The native source really contains high-frequency detail: " + detail, detail > 20f);
                    assertTrue("Live low-pass blur must remain inside the hardware layer: " + filtered,
                            filtered < 6f && filtered < detail * .20f);
                } finally { blurred.recycle(); raw.recycle(); }
                int red = sourceColor(fixture, Pattern.RED, inside, "red");
                int blue = sourceColor(fixture, Pattern.BLUE, inside, "blue");
                assertTrue(Color.red(red) > Color.blue(red) + 25 && Color.blue(blue) > Color.red(blue) + 25);

                // The transient Home cache has this extra ancestor boundary;
                // the live scene stays outside it and only its child invalidates.
                main(() -> fixture.sheet.setLayerType(View.LAYER_TYPE_HARDWARE, null)); nextFrame(); nextFrame();
                sourceColor(fixture, Pattern.RED, inside, "ancestor-layer-red");
                sourceColor(fixture, Pattern.BLUE, inside, "ancestor-layer-blue");
                main(() -> fixture.sheet.setLayerType(View.LAYER_TYPE_NONE, null));

                // Give the completed source update one traversal, then move an
                // unrelated sibling through twelve actual native frames.
                nextFrame();
                int sceneRecords = onMain(fixture.scene::getRecordingCount);
                int blurRecords = onMain(() -> fixture.glass().getRecordingCount());
                for (int frame = 1; frame <= 12; frame++) {
                    final int offset = frame;
                    main(() -> fixture.proxy.setTranslationX(offset)); nextFrame();
                    main(() -> {
                        assertEquals("Other UI motion does not re-record a quiet source", sceneRecords, fixture.scene.getRecordingCount());
                        assertEquals("A stationary cached face schedules no new blur recordings", blurRecords, fixture.glass().getRecordingCount());
                        assertSame(first, fixture.face()); fixture.assertOneBoundedLayer();
                    });
                }
                main(() -> fixture.sheet.present(new SharedRowsSheet.Spec(80, 100, 400, 240, 28, false), true));
                nextFrame();
                main(() -> { assertSame("An equal spec does not rebuild the face", first, fixture.face()); assertFalse(fixture.sheet.isAnimating()); });

                main(() -> { fixture.sheet.pause(); fixture.owner.pause(); });
                main(() -> {
                    assertEquals("Pause releases the bounded hardware texture", View.LAYER_TYPE_NONE, first.getLayerType());
                    assertEquals("The owner drops every blur display list", 0, fixture.owner.getGpuNodeCount());
                    assertFalse(firstGlass.isLiveBlurReady());
                    fixture.pattern.setMode(Pattern.RED);
                });
                nextFrame(); nextFrame();
                main(() -> { fixture.owner.resume(); fixture.sheet.resume(); });
                awaitFrames("Resume recreates the live blur", () -> firstGlass.isLiveBlurReady());
                assertSourceColor(fixture, Pattern.RED, inside, "resumed-red");
                main(() -> { assertSame(first, fixture.face()); fixture.assertOneBoundedLayer(); });

                main(() -> fixture.pattern.setMode(Pattern.BLACK));
                int dark = centerGreyAfterSource(fixture, inside, false);
                main(() -> fixture.sheet.present(LIGHT, false));
                awaitFrames("The replacement palette has a live bounded face", () -> fixture.glass().isLiveBlurReady());
                main(() -> {
                    assertNotSame(first, fixture.face()); assertNull("Old palette face is detached", first.getParent());
                    assertEquals(View.LAYER_TYPE_NONE, first.getLayerType()); assertFalse(firstGlass.isLiveBlurReady());
                    fixture.assertOneBoundedLayer(); assertEquals("Only the current palette owns a blur node", 1, fixture.owner.getGpuNodeCount());
                });
                int light = centerGreyAfterSource(fixture, inside, true);
                assertTrue("The actual compositor must display the new palette, not its cached old texture", light > dark + 50);
                View replacement = onMain(fixture::face);
                main(() -> fixture.sheet.present(null, true));
                awaitFrames("Observe exit before reversing it", () -> fixture.sheet.isAnimating() && fixture.material().getAlpha() < .95f);
                main(() -> fixture.sheet.present(LIGHT, true));
                awaitFrames("A rapid reversal settles to the visible material", () -> !fixture.sheet.isAnimating()
                        && fixture.material().getVisibility() == View.VISIBLE && fixture.material().getAlpha() > .999f);
                // More frames expose an abandoned hide listener firing after the reversal.
                for (int frame = 0; frame < 3; frame++) nextFrame();
                main(() -> {
                    assertSame(replacement, fixture.face()); assertEquals(View.VISIBLE, fixture.material().getVisibility());
                    assertEquals(1f, fixture.material().getAlpha(), .001f); fixture.assertOneBoundedLayer();
                    assertEquals("No CPU backdrop snapshot exists", 0L, fixture.owner.getOwnedBitmapBytes());
                    fixture.sheet.present(null, false);
                    assertEquals(View.INVISIBLE, fixture.material().getVisibility());
                    assertEquals(View.LAYER_TYPE_NONE, replacement.getLayerType());
                    fixture.sheet.close();
                    assertNull(replacement.getParent()); assertNull(fixture.sheet.findViewWithTag("shared-rows-glass-face"));
                    assertEquals(0, fixture.owner.getGpuNodeCount());
                });
            } finally { main(fixture::close); }
        }
    }

    @Test public void cachedScaledFaceKeepsFinalCornerCoverageAtNativeResolution() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, LIGHT);
            try {
                main(() -> {
                    fixture.sheet.setTranslationX(.375f); fixture.sheet.setTranslationY(.625f);
                    fixture.reference.setVisibility(View.VISIBLE);
                });
                awaitFrames("A layered light face must really be live", () -> fixture.glass().isLiveBlurReady());
                nextFrame();
                main(fixture::assertOneBoundedLayer);
                RectF bounds = onMain(() -> bounds(fixture.face(), fixture.root));
                assertEquals(DARK.width * SCALE, bounds.width(), .02f); assertEquals(DARK.height * SCALE, bounds.height(), .02f);
                Rect corner = cornerCrop(bounds.left, bounds.top);
                Rect referenceCorner = cornerCrop(REFERENCE_LEFT + .375f, DARK.y * SCALE + .625f);
                Bitmap actual = copy(fixture, corner), reference = copy(fixture, referenceCorner);
                try {
                    save(fixture, actual, "test-shared-rows-hardware-corner"); save(fixture, reference, "test-shared-rows-native-corner");
                    EdgeMetrics edge = inspectEdge(actual, reference, bounds.left - corner.left, bounds.top - corner.top);
                    Log.i("SharedRowsSheetTest", edge.toString());
                    assertTrue("Native reference has one physical AA edge: " + edge, edge.referenceWidth > 0 && edge.referenceWidth <= 2.2f);
                    assertTrue("Caching at design scale must not enlarge the final native AA edge: " + edge,
                            edge.actualWidth > 0 && edge.actualWidth <= 2.2f);
                    assertTrue("The hardware layer must preserve native roundrect coverage: " + edge, edge.rmse < .12f);
                    assertTrue("No bright exterior rim appears around the cached mask: " + edge, edge.outside <= .06f && edge.peak <= 1.12f);
                } finally { actual.recycle(); reference.recycle(); }
            } finally { main(fixture::close); }
        }
    }

    @Test public void nonAnimatedPresentationSynchronouslySettlesAnAlreadyRunningSameTargetFade() throws Exception {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, DARK);
            try {
                awaitFrames("The real material is ready before exercising its animation policy", () -> fixture.glass().isLiveBlurReady());
                View face = onMain(fixture::face);
                main(() -> {
                    fixture.sheet.present(null, false);
                    assertEquals(View.INVISIBLE, fixture.material().getVisibility());
                    assertEquals(View.LAYER_TYPE_NONE, face.getLayerType());
                    fixture.sheet.present(DARK, true);
                });
                awaitFrames("Observe an actual partially progressed entry fade", () -> {
                    float alpha = fixture.material().getAlpha();
                    if (!fixture.sheet.isAnimating() || alpha <= .05f || alpha >= .95f) return false;
                    assertEquals(View.VISIBLE, fixture.material().getVisibility());
                    assertEquals(View.LAYER_TYPE_HARDWARE, face.getLayerType());
                    // Same target, but the caller now requires no motion (as for
                    // Reduce Motion). Check in this callback, before another frame.
                    fixture.sheet.present(DARK, false);
                    assertFalse("A non-animated same-spec call cancels the running entry synchronously", fixture.sheet.isAnimating());
                    assertEquals("Entry reaches its requested opacity before present returns", 1f, fixture.material().getAlpha(), .001f);
                    assertEquals(View.VISIBLE, fixture.material().getVisibility());
                    assertSame(face, fixture.face());
                    assertEquals(View.LAYER_TYPE_HARDWARE, face.getLayerType());
                    return true;
                });
                main(() -> fixture.sheet.present(null, true));
                awaitFrames("Observe an actual partially progressed exit fade", () -> {
                    float alpha = fixture.material().getAlpha();
                    if (!fixture.sheet.isAnimating() || alpha <= .05f || alpha >= .95f) return false;
                    assertEquals(View.VISIBLE, fixture.material().getVisibility());
                    assertEquals(View.LAYER_TYPE_HARDWARE, face.getLayerType());
                    fixture.sheet.present(null, false);
                    assertFalse("A non-animated same-null call cancels the running exit synchronously", fixture.sheet.isAnimating());
                    assertEquals("Exit reaches zero before present returns", 0f, fixture.material().getAlpha(), .001f);
                    assertEquals(View.INVISIBLE, fixture.material().getVisibility());
                    assertSame(face, fixture.face());
                    assertEquals("A completed exit immediately releases the bounded layer", View.LAYER_TYPE_NONE, face.getLayerType());
                    return true;
                });
                nextFrame(); nextFrame();
                main(() -> {
                    assertFalse(fixture.sheet.isAnimating()); assertEquals(View.INVISIBLE, fixture.material().getVisibility());
                    assertEquals(0f, fixture.material().getAlpha(), .001f); assertEquals(View.LAYER_TYPE_NONE, face.getLayerType());
                });
            } finally { main(fixture::close); }
        }
    }

    private static Fixture fixture(ActivityScenario<MainActivity> activity, SharedRowsSheet.Spec spec) throws Exception {
        AtomicReference<MainActivity> host = new AtomicReference<>(); activity.onActivity(host::set);
        awaitFrames("The Activity really lays out its native 4K window", () -> host.get().getWindow().getDecorView().getWidth() == WIDTH
                && host.get().getWindow().getDecorView().getHeight() == HEIGHT);
        AtomicReference<Fixture> result = new AtomicReference<>();
        activity.onActivity(current -> {
            assertEquals(WIDTH, current.getWindow().getDecorView().getWidth()); assertEquals(HEIGHT, current.getWindow().getDecorView().getHeight());
            result.set(new Fixture(current, spec));
        });
        Fixture fixture = result.get();
        try {
            awaitFrames("Actual native 4K hardware fixture", () -> fixture.root.getWidth() == WIDTH && fixture.root.getHeight() == HEIGHT
                    && fixture.root.isHardwareAccelerated() && fixture.face().isLaidOut() && !fixture.face().isLayoutRequested());
            return fixture;
        } catch (Exception | Error failure) { main(fixture::close); throw failure; }
    }

    private static final class Fixture {
        final Dialog dialog;
        final FrameLayout root;
        final GlassScene scene;
        final GlassBackdrop owner = new GlassBackdrop();
        final SharedRowsSheet sheet;
        final Pattern pattern;
        final NativeReference reference;
        final View proxy;
        Fixture(Context context, SharedRowsSheet.Spec spec) {
            dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar_Fullscreen); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(context); root.setBackgroundColor(Color.BLACK);
            scene = new GlassScene(context); pattern = new Pattern(context);
            scene.addView(pattern, position(WIDTH, HEIGHT, 0, 0)); root.addView(scene, position(WIDTH, HEIGHT, 0, 0));
            sheet = new SharedRowsSheet(context, owner, scene); root.addView(sheet, position(WIDTH, HEIGHT, 0, 0)); sheet.present(spec, false);
            proxy = new View(context); proxy.setBackgroundColor(Color.WHITE); root.addView(proxy, position(80, 4, 24, 24));
            reference = new NativeReference(context); reference.setVisibility(View.GONE);
            root.addView(reference, position(DARK.width * SCALE + 2, DARK.height * SCALE + 2, REFERENCE_LEFT, DARK.y * SCALE));
            dialog.setContentView(root); Window window = dialog.getWindow(); assertNotNull(window);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setGravity(Gravity.TOP | Gravity.LEFT);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            dialog.show(); window.setLayout(WIDTH, HEIGHT);
        }
        View face() { View face = sheet.findViewWithTag("shared-rows-glass-face"); assertNotNull(face); return face; }
        View material() { return (View) face().getParent(); }
        GlassDrawable glass() { assertTrue(face().getBackground() instanceof GlassDrawable); return (GlassDrawable) face().getBackground(); }
        void assertOneBoundedLayer() {
            assertEquals(View.LAYER_TYPE_HARDWARE, face().getLayerType());
            assertEquals("The sheet, shadow and design root never cache the full window", 1, hardwareLayers(sheet));
            RectF faceBounds = bounds(face(), root);
            assertTrue(faceBounds.width() > 0 && faceBounds.height() > 0 && faceBounds.width() < WIDTH && faceBounds.height() < HEIGHT);
            assertEquals(View.LAYER_TYPE_NONE, scene.getLayerType());
            assertEquals(1, owner.getGpuNodeCount()); assertTrue(glass().isLiveBlurReady());
        }
        void close() { sheet.close(); owner.close(); dialog.dismiss(); }
    }

    private static int sourceColor(Fixture fixture, int mode, Rect region, String name) throws Exception {
        main(() -> fixture.pattern.setMode(mode)); return assertSourceColor(fixture, mode, region, name);
    }
    private static int assertSourceColor(Fixture fixture, int mode, Rect region, String name) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 4000;
        while (true) {
            nextFrame(); Bitmap image = copy(fixture, region);
            try {
                int color = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
                boolean correct = mode == Pattern.RED ? Color.red(color) > Color.blue(color) + 25 : Color.blue(color) > Color.red(color) + 25;
                if (correct) { save(fixture, image, "test-shared-rows-" + name); return color; }
                if (SystemClock.uptimeMillis() >= deadline) {
                    save(fixture, image, "test-shared-rows-" + name + "-timeout");
                    fail("Changing only the native source must update the cached face; " + name + " last=0x" + Integer.toHexString(color));
                }
            } finally { image.recycle(); }
        }
    }
    private static int centerGreyAfterSource(Fixture fixture, Rect region, boolean light) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 4000;
        while (true) {
            nextFrame(); Bitmap image = copy(fixture, region);
            try {
                int grey = Math.round(grey(image.getPixel(image.getWidth() / 2, image.getHeight() / 2)));
                if (light ? grey > 70 : grey < 40) return grey;
                if (SystemClock.uptimeMillis() >= deadline) fail("Expected actual " + (light ? "light" : "dark") + " palette over black, last=" + grey);
            } finally { image.recycle(); }
        }
    }
    private static Bitmap copy(Fixture fixture, Rect region) throws Exception {
        assertTrue("PixelCopy only a bounded native crop: " + region, region.left >= 0 && region.top >= 0
                && region.width() > 0 && region.height() > 0 && region.right <= WIDTH && region.bottom <= HEIGHT);
        Bitmap image = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888);
        CountDownLatch done = new CountDownLatch(1); int[] result = {-1};
        main(() -> {
            int[] origin = new int[2]; fixture.root.getLocationInWindow(origin); Rect windowRegion = new Rect(region); windowRegion.offset(origin[0], origin[1]);
            PixelCopy.request(fixture.dialog.getWindow(), windowRegion, image, status -> { result[0] = status; done.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        if (!done.await(4, TimeUnit.SECONDS) || result[0] != PixelCopy.SUCCESS) { image.recycle(); fail("Native hardware window PixelCopy failed: " + result[0]); }
        return image;
    }
    private static void save(Fixture fixture, Bitmap image, String name) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("softlumiScreenshotRun", "screenshots");
        assertTrue("Safe diagnostic crop directory", run.matches("[A-Za-z0-9_-]+"));
        File base = fixture.root.getContext().getExternalFilesDir(null); assertNotNull(base);
        File directory = new File(base, run); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
    private static RectF bounds(View face, View root) {
        Matrix matrix = new Matrix(), rootMatrix = new Matrix(), inverse = new Matrix();
        face.transformMatrixToGlobal(matrix); root.transformMatrixToGlobal(rootMatrix); assertTrue(rootMatrix.invert(inverse));
        RectF bounds = new RectF(0, 0, face.getWidth(), face.getHeight()); matrix.mapRect(bounds); inverse.mapRect(bounds); return bounds;
    }
    private static int hardwareLayers(View view) {
        int count = view.getLayerType() == View.LAYER_TYPE_HARDWARE ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += hardwareLayers(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static float adjacentDetail(Bitmap image) {
        float sum = 0; int count = 0;
        for (int y = 4; y < image.getHeight() - 4; y++) for (int x = 0; x < image.getWidth() - 1; x++) {
            sum += Math.abs(grey(image.getPixel(x, y)) - grey(image.getPixel(x + 1, y))); count++;
        }
        return sum / count;
    }
    private static float grey(int color) { return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3f; }
    private static Rect cornerCrop(float x, float y) { int left = (int) Math.floor(x) - MARGIN, top = (int) Math.floor(y) - MARGIN; return new Rect(left, top, left + CROP, top + CROP); }
    private static EdgeMetrics inspectEdge(Bitmap actual, Bitmap reference, float left, float top) {
        float radius = DARK.radius * SCALE, cx = left + radius, cy = top + radius;
        float interior = grey(actual.getPixel(Math.round(cx + 24), Math.round(cy + 24)));
        assertTrue("A light face over black must provide measurable corner contrast", interior >= 45f);
        float actualMin = Float.POSITIVE_INFINITY, actualMax = Float.NEGATIVE_INFINITY;
        float nativeMin = Float.POSITIVE_INFINITY, nativeMax = Float.NEGATIVE_INFINITY;
        float outside = 0, peak = 0, squared = 0; int count = 0;
        for (int y = 0; y < CROP; y++) for (int x = 0; x < CROP; x++) {
            float dx = x + .5f - cx, dy = y + .5f - cy; if (dx >= 0 || dy >= 0) continue;
            double angle = Math.toDegrees(Math.atan2(-dy, -dx)); if (angle < 20 || angle > 70) continue;
            float distance = (float) Math.hypot(dx, dy) - radius; if (Math.abs(distance) > 3f) continue;
            float coverage = grey(actual.getPixel(x, y)) / interior, nativeCoverage = grey(reference.getPixel(x, y)) / 255f;
            if (coverage >= .1f && coverage <= .9f) { actualMin = Math.min(actualMin, distance); actualMax = Math.max(actualMax, distance); }
            if (nativeCoverage >= .1f && nativeCoverage <= .9f) { nativeMin = Math.min(nativeMin, distance); nativeMax = Math.max(nativeMax, distance); }
            if (distance >= 2.25f) outside = Math.max(outside, coverage); peak = Math.max(peak, coverage);
            float error = coverage - nativeCoverage; squared += error * error; count++;
        }
        assertTrue("A substantial diagonal native corner must be sampled", count > 150);
        return new EdgeMetrics(actualMax - actualMin, nativeMax - nativeMin, (float) Math.sqrt(squared / count), outside, peak);
    }
    private static final class EdgeMetrics {
        final float actualWidth, referenceWidth, rmse, outside, peak;
        EdgeMetrics(float actual, float reference, float rmse, float outside, float peak) { actualWidth = actual; referenceWidth = reference; this.rmse = rmse; this.outside = outside; this.peak = peak; }
        @Override public String toString() { return "AA10-90=" + actualWidth + "px native=" + referenceWidth + "px rmse=" + rmse + " outside=" + outside + " peak=" + peak; }
    }
    private static final class NativeReference extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NativeReference(Context context) { super(context); paint.setColor(Color.WHITE); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRoundRect(.375f, .625f, .375f + DARK.width * SCALE, .625f + DARK.height * SCALE, DARK.radius * SCALE, DARK.radius * SCALE, paint);
        }
    }
    private static final class Pattern extends View {
        static final int BLACK = 0, CHECKER = 1, RED = 2, BLUE = 3;
        final Paint paint = new Paint(); int mode = BLACK;
        Pattern(Context context) { super(context); }
        void setMode(int next) { mode = next; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(mode == RED ? Color.RED : mode == BLUE ? Color.BLUE : Color.BLACK);
            if (mode != CHECKER) return; paint.setColor(Color.WHITE);
            for (int y = 0; y < getHeight(); y += 8) for (int x = 0; x < getWidth(); x += 8)
                if (((x / 8 + y / 8) & 1) == 0) canvas.drawRect(x, y, x + 8, y + 8, paint);
        }
    }
    private static FrameLayout.LayoutParams position(int width, int height, int x, int y) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height); params.leftMargin = x; params.topMargin = y; return params;
    }
    private static void awaitFrames(String message, BooleanSupplier ready) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AssertionError> failure = new AtomicReference<>();
        AtomicReference<Choreographer.FrameCallback> callback = new AtomicReference<>();
        main(() -> {
            callback.set(time -> {
                try { if (ready.getAsBoolean()) { done.countDown(); return; } }
                catch (AssertionError error) { failure.set(error); done.countDown(); return; }
                Choreographer.getInstance().postFrameCallback(callback.get());
            });
            Choreographer.getInstance().postFrameCallback(callback.get());
        });
        try { assertTrue(message, done.await(5, TimeUnit.SECONDS)); }
        finally { main(() -> Choreographer.getInstance().removeFrameCallback(callback.get())); }
        if (failure.get() != null) throw failure.get();
    }
    private static void nextFrame() throws Exception {
        CountDownLatch done = new CountDownLatch(1); main(() -> Choreographer.getInstance().postFrameCallback(time -> done.countDown()));
        assertTrue("A real native frame callback is required", done.await(4, TimeUnit.SECONDS));
    }
    private interface Query<T> { T get(); }
    private static <T> T onMain(Query<T> query) { AtomicReference<T> value = new AtomicReference<>(); main(() -> value.set(query.get())); return value.get(); }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) { Object value = entry.getValue(); copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value); }
        return copy;
    }
}
