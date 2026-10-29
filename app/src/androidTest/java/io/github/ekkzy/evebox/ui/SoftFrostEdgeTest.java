package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
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
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.evebox.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native-resolution compositor evidence for quiet matte glass, not LIQUID rim optics. */
@RunWith(AndroidJUnit4.class)
public class SoftFrostEdgeTest {
    private static final String TAG = "SoftFrostEdgeTest";
    private static final int WIDTH = 3840, HEIGHT = 2160, SCALE = 3;
    private static final int PANEL_LEFT = 128, PANEL_TOP = 96, PANEL_WIDTH = 360, PANEL_HEIGHT = 180;
    private static final int REFERENCE_LEFT = 2304, CROP_SIZE = 160, CROP_MARGIN = 8;
    private static final float RADIUS = 20f, MAX_AA_WIDTH = 2.2f;

    @Test public void scaledFractionalCornersMatchNativeRoundRectCoverage() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                // These are two native clipping paths, not a device/theme/font matrix.
                for (boolean outline : new boolean[] {true, false}) {
                    main(() -> fixture.setPhase(outline, outline ? .125f : .375f, outline ? .25f : .5f));
                    await("Live matte glass", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                    RectF bounds = panelBounds(fixture);
                    assertEquals("The source-scale exposure must really be 3x", PANEL_WIDTH * SCALE, bounds.width(), .02f);
                    assertEquals(PANEL_HEIGHT * SCALE, bounds.height(), .02f);
                    Rect corner = cornerCrop(bounds);
                    Rect referenceCorner = new Rect(corner);
                    referenceCorner.offset(REFERENCE_LEFT - PANEL_LEFT * SCALE, 0);
                    Bitmap actual = copy(fixture, corner), reference = copy(fixture, referenceCorner);
                    String mode = outline ? "outline" : "ownclip";
                    try {
                        // Persist both before assertions so a failing edge remains inspectable.
                        save(fixture, actual, "test-soft-frost-edge-scale3-" + mode);
                        save(fixture, reference, "test-soft-frost-native-reference-" + mode);
                        float left = bounds.left - corner.left, top = bounds.top - corner.top;
                        EdgeMetrics metrics = inspectEdge(actual, reference, left, top);
                        Log.i(TAG, mode + " " + metrics);
                        assertTrue("Native reference must resolve one physical AA edge: " + metrics,
                                metrics.referenceWidth > 0f && metrics.referenceWidth <= MAX_AA_WIDTH);
                        assertTrue("A scaled local mask must not broaden the physical edge beyond 2.2px: " + metrics,
                                metrics.actualWidth > 0f && metrics.actualWidth <= MAX_AA_WIDTH);
                        assertTrue("The rounded edge must follow native coverage, not coarse mask steps: " + metrics,
                                metrics.coverageRmse < .12f);
                        assertTrue("The outer corner must remain clean without a bright rim: " + metrics,
                                metrics.outsideCoverage <= .06f && metrics.peakCoverage <= 1.12f);
                    } finally { actual.recycle(); reference.recycle(); }
                }
            } finally { main(fixture::close); }
        }
    }

    @Test public void matteFrostStillBlursAndFollowsChangingNativeScene() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                main(() -> { fixture.reference.setVisibility(View.GONE); fixture.content.setMode(Pattern.CHECKER); });
                await("Live frost", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                RectF bounds = panelBounds(fixture);
                int centerX = Math.round(bounds.centerX()), centerY = Math.round(bounds.centerY());
                Rect inside = new Rect(centerX - 128, centerY - 16, centerX + 128, centerY + 16);
                Rect outside = new Rect(inside);
                outside.offset(0, Math.round(bounds.height()) + 120);
                Bitmap blurred = copy(fixture, inside), raw = copy(fixture, outside);
                try {
                    save(fixture, blurred, "test-soft-frost-live-checker-blurred");
                    save(fixture, raw, "test-soft-frost-live-checker-raw");
                    float originalDetail = adjacentDetail(raw), blurredDetail = adjacentDetail(blurred);
                    assertTrue("The real native source must contain checker detail: " + originalDetail, originalDetail > 20f);
                    assertTrue("Matte translucency alone cannot substitute for live low-pass blur: " + blurredDetail,
                            blurredDetail < 6f && blurredDetail < originalDetail * .20f);
                } finally { blurred.recycle(); raw.recycle(); }

                int red = sourceColor(fixture, Pattern.RED, inside, "red");
                int blue = sourceColor(fixture, Pattern.BLUE, inside, "blue");
                assertTrue("A stationary panel must transmit an actual native source color change",
                        Color.red(red) > Color.blue(red) + 25 && Color.blue(blue) > Color.red(blue) + 25);
                assertEquals("This fixture must not substitute a CPU pixel snapshot", 0L,
                        onMain(fixture.owner::getOwnedBitmapBytes).longValue());
            } finally { main(fixture::close); }
        }
    }

    private static EdgeMetrics inspectEdge(Bitmap actual, Bitmap reference, float left, float top) {
        float radius = RADIUS * SCALE, cx = left + radius, cy = top + radius;
        float interior = grey(actual.getPixel(Math.round(cx + 24), Math.round(cy + 24)));
        assertTrue("Light matte on black must provide measurable edge contrast: " + interior, interior >= 45f);
        float actualMin = Float.POSITIVE_INFINITY, actualMax = Float.NEGATIVE_INFINITY;
        float referenceMin = Float.POSITIVE_INFINITY, referenceMax = Float.NEGATIVE_INFINITY;
        float outside = 0f, peak = 0f, squaredError = 0f;
        int count = 0;
        for (int y = 0; y < CROP_SIZE; y++) for (int x = 0; x < CROP_SIZE; x++) {
            float dx = x + .5f - cx, dy = y + .5f - cy;
            if (dx >= 0 || dy >= 0) continue;
            // The diagonal arc exposes stair steps; avoid straight-edge joins.
            double angle = Math.toDegrees(Math.atan2(-dy, -dx));
            if (angle < 20 || angle > 70) continue;
            float distance = (float) Math.hypot(dx, dy) - radius;
            if (Math.abs(distance) > 3f) continue;
            float coverage = grey(actual.getPixel(x, y)) / interior;
            float nativeCoverage = grey(reference.getPixel(x, y)) / 255f;
            if (coverage >= .10f && coverage <= .90f) {
                actualMin = Math.min(actualMin, distance); actualMax = Math.max(actualMax, distance);
            }
            if (nativeCoverage >= .10f && nativeCoverage <= .90f) {
                referenceMin = Math.min(referenceMin, distance); referenceMax = Math.max(referenceMax, distance);
            }
            if (distance >= 2.25f) outside = Math.max(outside, coverage);
            peak = Math.max(peak, coverage);
            float error = coverage - nativeCoverage; squaredError += error * error; count++;
        }
        assertTrue("The crop must contain a substantial native curved edge", count > 150);
        return new EdgeMetrics(actualMax - actualMin, referenceMax - referenceMin,
                (float) Math.sqrt(squaredError / count), outside, peak);
    }

    private static int sourceColor(Fixture fixture, int mode, Rect inside, String name) throws Exception {
        assertTrue("This probe expects a red or blue native scene", mode == Pattern.RED || mode == Pattern.BLUE);
        main(() -> fixture.content.setMode(mode));
        // A retained child RenderNode can update without re-recording its parent.
        // Observe the actual composited color instead of a parent's draw counter.
        long until = SystemClock.uptimeMillis() + 4000;
        while (true) {
            Bitmap image = copy(fixture, inside);
            try {
                int color = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
                boolean expected = mode == Pattern.RED
                        ? Color.red(color) > Color.blue(color) + 25
                        : Color.blue(color) > Color.red(color) + 25;
                if (expected) {
                    save(fixture, image, "test-soft-frost-live-" + name); return color;
                }
                if (SystemClock.uptimeMillis() >= until) {
                    save(fixture, image, "test-soft-frost-live-" + name + "-timeout");
                    fail("Live matte did not transmit the native " + name + " scene with >25 color separation; last=0x"
                            + Integer.toHexString(color));
                }
            } finally { image.recycle(); }
        }
    }

    private static float adjacentDetail(Bitmap image) {
        float sum = 0f; int count = 0;
        for (int y = 4; y < image.getHeight() - 4; y++) for (int x = 0; x < image.getWidth() - 1; x++) {
            sum += Math.abs(grey(image.getPixel(x, y)) - grey(image.getPixel(x + 1, y))); count++;
        }
        return sum / count;
    }
    private static float grey(int color) { return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3f; }
    private static Rect cornerCrop(RectF bounds) {
        int left = (int) Math.floor(bounds.left) - CROP_MARGIN, top = (int) Math.floor(bounds.top) - CROP_MARGIN;
        return new Rect(left, top, left + CROP_SIZE, top + CROP_SIZE);
    }
    private static RectF panelBounds(Fixture fixture) {
        return onMain(() -> {
            Matrix panel = new Matrix(), root = new Matrix(), inverseRoot = new Matrix();
            fixture.panel.transformMatrixToGlobal(panel); fixture.root.transformMatrixToGlobal(root);
            assertTrue("Fixture transform must be invertible", root.invert(inverseRoot));
            RectF bounds = new RectF(0, 0, fixture.panel.getWidth(), fixture.panel.getHeight());
            panel.mapRect(bounds); inverseRoot.mapRect(bounds); return bounds;
        });
    }
    private static Bitmap copy(Fixture fixture, Rect region) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(100);
        assertTrue("PixelCopy crop must stay inside the native 4K root: " + region,
                region.width() > 0 && region.height() > 0 && region.left >= 0 && region.top >= 0
                        && region.right <= WIDTH && region.bottom <= HEIGHT);
        Bitmap image = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888);
        CountDownLatch done = new CountDownLatch(1); int[] status = {-1};
        main(() -> {
            int[] location = new int[2]; fixture.root.getLocationInWindow(location);
            Rect windowRegion = new Rect(region); windowRegion.offset(location[0], location[1]);
            PixelCopy.request(fixture.dialog.getWindow(), windowRegion, image,
                    result -> { status[0] = result; done.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        if (!done.await(4, TimeUnit.SECONDS) || status[0] != PixelCopy.SUCCESS) {
            image.recycle(); fail("Actual hardware window PixelCopy failed: " + status[0]);
        }
        return image;
    }
    private static void save(Fixture fixture, Bitmap image, String name) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("eveBoxScreenshotRun", "screenshots");
        assertTrue("Safe screenshot directory", run.matches("[A-Za-z0-9_-]+"));
        File base = fixture.root.getContext().getExternalFilesDir(null); assertNotNull(base);
        File directory = new File(base, run); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue("Write native-resolution diagnostic crop", image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
    private static Fixture fixture(ActivityScenario<MainActivity> activity) {
        AtomicReference<Fixture> result = new AtomicReference<>();
        activity.onActivity(host -> {
            assertEquals("This focused regression requires standard font scale; it does not change global settings",
                    1f, host.getResources().getConfiguration().fontScale, .001f);
            Rect display = host.getWindowManager().getCurrentWindowMetrics().getBounds();
            assertEquals("Run on a genuine 3840px app window, not a scaled 1080p capture", WIDTH, display.width());
            assertEquals(HEIGHT, display.height()); result.set(new Fixture(host));
        });
        Fixture fixture = result.get();
        try {
            await("A hardware 3840x2160 fixture is required", () -> onMain(() -> fixture.root.getWidth() == WIDTH
                    && fixture.root.getHeight() == HEIGHT && fixture.root.isHardwareAccelerated()), 4000);
            return fixture;
        } catch (RuntimeException | Error failure) {
            main(fixture::close); throw failure;
        }
    }
    private static void requireGpuApi() { Assume.assumeTrue("Live RenderEffect requires API31+", Build.VERSION.SDK_INT >= 31); }
    private static void await(String message, BooleanSupplier ready, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < until) { if (ready.getAsBoolean()) return; SystemClock.sleep(25); }
        fail(message);
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private interface Query<T> { T get(); }
    private static <T> T onMain(Query<T> query) {
        AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(query.get())); return result.get();
    }
    private static FrameLayout.LayoutParams position(int width, int height, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left; params.topMargin = top; return params;
    }

    private static final class EdgeMetrics {
        final float actualWidth, referenceWidth, coverageRmse, outsideCoverage, peakCoverage;
        EdgeMetrics(float actual, float reference, float rmse, float outside, float peak) {
            actualWidth = actual; referenceWidth = reference; coverageRmse = rmse; outsideCoverage = outside; peakCoverage = peak;
        }
        @Override public String toString() {
            return "AA10-90=" + actualWidth + "px native=" + referenceWidth + "px rmse=" + coverageRmse
                    + " outside=" + outsideCoverage + " peak=" + peakCoverage;
        }
    }
    private static final class Fixture {
        final Dialog dialog;
        final FrameLayout root, board;
        final GlassScene scene;
        final Pattern content;
        final NativeReference reference;
        final View panel;
        final GlassBackdrop owner = new GlassBackdrop();
        final GlassDrawable glass;
        Fixture(Context context) {
            dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(context); root.setBackgroundColor(Color.BLACK);
            scene = new GlassScene(context); content = new Pattern(context);
            scene.addView(content, position(WIDTH, HEIGHT, 0, 0)); root.addView(scene, position(WIDTH, HEIGHT, 0, 0));
            board = new FrameLayout(context); board.setPivotX(0); board.setPivotY(0); board.setScaleX(SCALE); board.setScaleY(SCALE);
            root.addView(board, position(WIDTH / SCALE, HEIGHT / SCALE, 0, 0));
            panel = new View(context); glass = owner.drawable(panel, scene, RADIUS, true, true); panel.setBackground(glass);
            board.addView(panel, position(PANEL_WIDTH, PANEL_HEIGHT, PANEL_LEFT, PANEL_TOP));
            reference = new NativeReference(context);
            root.addView(reference, position(PANEL_WIDTH * SCALE + 2, PANEL_HEIGHT * SCALE + 2,
                    REFERENCE_LEFT, PANEL_TOP * SCALE));
            setPhase(true, .125f, .25f); dialog.setContentView(root);
            Window window = dialog.getWindow(); assertNotNull(window);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.TOP | Gravity.LEFT);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            dialog.show(); window.setLayout(WIDTH, HEIGHT);
        }
        void setPhase(boolean outline, float x, float y) {
            panel.setClipToOutline(outline); panel.setTranslationX(x); panel.setTranslationY(y);
            reference.phaseX = x * SCALE; reference.phaseY = y * SCALE; reference.invalidate();
        }
        void close() { owner.close(); dialog.dismiss(); }
    }
    private static final class NativeReference extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float phaseX, phaseY;
        NativeReference(Context context) { super(context); paint.setColor(Color.WHITE); }
        @Override protected void onDraw(Canvas canvas) {
            // A native physical-pixel roundrect, not a bitmap or scaled design-space mask.
            canvas.drawRoundRect(phaseX, phaseY, phaseX + PANEL_WIDTH * SCALE, phaseY + PANEL_HEIGHT * SCALE,
                    RADIUS * SCALE, RADIUS * SCALE, paint);
        }
    }
    private static final class Pattern extends View {
        static final int BLACK = 0, CHECKER = 1, RED = 2, BLUE = 3;
        final Paint paint = new Paint(); int mode = BLACK;
        Pattern(Context context) { super(context); }
        void setMode(int value) { mode = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(mode == RED ? Color.RED : mode == BLUE ? Color.BLUE : Color.BLACK);
            if (mode != CHECKER) return;
            paint.setColor(Color.WHITE);
            for (int y = 0; y < getHeight(); y += 8) for (int x = 0; x < getWidth(); x += 8) {
                if (((x / 8 + y / 8) & 1) == 0) canvas.drawRect(x, y, x + 8, y + 8, paint);
            }
        }
    }
}
