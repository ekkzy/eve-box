package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.softlumi.MainActivity;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Compare real hardware output with the former View RenderEffect implementation. */
@RunWith(AndroidJUnit4.class)
public class SurfaceShadowViewTest {
    @Test public void cachedShadowMatchesNativeReferenceWhenClippedAndRecoversItsGpuResources() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 31);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            for (int opacity : new int[] {0x14, 0x24}) {
                AtomicReference<Fixture> value = new AtomicReference<>();
                activity.onActivity(host -> value.set(new Fixture(host, opacity)));
                Fixture fixture = value.get();
                Bitmap[] cached = new Bitmap[4];
                try {
                    await("Hardware shadow", () -> onMain(() -> fixture.root.isHardwareAccelerated()
                            && fixture.shadow.gpuNodeCount() == 2));
                    int recordings = onMain(fixture.shadow::getRecordingCount);
                    int[] x = {0, -90, 160, 0}, y = {0, -100, 140, 0};
                    for (int index = 0; index < cached.length; index++) {
                        final int dx = x[index], dy = y[index];
                        main(() -> { fixture.shadow.setTranslationX(dx); fixture.shadow.setTranslationY(dy); });
                        cached[index] = copy(fixture);
                        assertEquals("Parent clip changes reuse the same complete shadow", recordings,
                                onMain(fixture.shadow::getRecordingCount).intValue());
                    }
                    main(() -> {
                        fixture.root.removeView(fixture.shadow);
                        assertEquals("Detach releases both cached nodes", 0, fixture.shadow.gpuNodeCount());
                        fixture.reference = new ReferenceShadow(fixture.root.getContext(), opacity);
                        fixture.root.addView(fixture.reference, position(560, 188));
                    });
                    for (int index = 0; index < cached.length; index++) {
                        final int dx = x[index], dy = y[index];
                        main(() -> { fixture.reference.setTranslationX(dx); fixture.reference.setTranslationY(dy); });
                        Bitmap reference = copy(fixture);
                        try { sameShadow("Opacity " + opacity + ", translation " + dx + "/" + dy, reference, cached[index]); }
                        finally { reference.recycle(); }
                    }
                    // A uniform background alone must not satisfy the comparison.
                    assertTrue("A real soft shadow remains visible", Color.red(cached[0].getPixel(320, 170)) < 216);
                    main(() -> {
                        fixture.root.removeView(fixture.reference);
                        fixture.shadow.setTranslationX(0); fixture.shadow.setTranslationY(0);
                        fixture.root.addView(fixture.shadow, position(560, 188));
                    });
                    await("Reattached shadow", () -> onMain(() -> fixture.shadow.gpuNodeCount() == 2));
                    int beforeResize = onMain(fixture.shadow::getRecordingCount);
                    main(() -> fixture.shadow.setLayoutParams(position(400, 240)));
                    await("Resize records the new shape", () -> onMain(() -> fixture.shadow.getWidth() == 400
                            && fixture.shadow.getRecordingCount() > beforeResize && fixture.shadow.gpuNodeCount() == 2));
                    Bitmap resized = copy(fixture);
                    try {
                        for (String node : new String[] {"raster", "filtered"}) {
                            int beforeLoss = onMain(fixture.shadow::getRecordingCount);
                            main(() -> { discardNode(fixture.shadow, node); fixture.root.invalidate(); });
                            await("Lost " + node + " display list recovers", () -> onMain(() ->
                                    fixture.shadow.getRecordingCount() > beforeLoss && fixture.shadow.gpuNodeCount() == 2));
                            Bitmap recovered = copy(fixture);
                            try { sameShadow("Resource recovery: " + node, resized, recovered); }
                            finally { recovered.recycle(); }
                        }
                        main(fixture.dialog::hide);
                        await("Invisible window releases shadow resources", () -> onMain(() -> fixture.shadow.gpuNodeCount() == 0));
                        main(fixture.dialog::show);
                        await("Visible window restores shadow resources", () -> onMain(() -> fixture.shadow.gpuNodeCount() == 2));
                        Bitmap shown = copy(fixture);
                        try { sameShadow("Window visibility recovery", resized, shown); }
                        finally { shown.recycle(); }
                        main(() -> fixture.root.setVisibility(View.INVISIBLE));
                        assertEquals(0, onMain(fixture.shadow::gpuNodeCount).intValue());
                        main(() -> fixture.root.setVisibility(View.VISIBLE));
                        await("Visible ancestor restores shadow resources", () -> onMain(() -> fixture.shadow.gpuNodeCount() == 2));
                    } finally { resized.recycle(); }
                } finally {
                    for (Bitmap image : cached) if (image != null) image.recycle();
                    main(fixture.dialog::dismiss);
                }
            }
        }
    }

    private static void sameShadow(String message, Bitmap reference, Bitmap actual) {
        long difference = 0;
        int maximum = 0;
        for (int y = 0; y < reference.getHeight(); y++) for (int x = 0; x < reference.getWidth(); x++) {
            int expected = reference.getPixel(x, y), observed = actual.getPixel(x, y);
            for (int shift = 0; shift <= 16; shift += 8) {
                int delta = Math.abs((expected >> shift & 255) - (observed >> shift & 255));
                maximum = Math.max(maximum, delta); difference += delta;
            }
        }
        // One extra RGBA raster stage may round a channel; this is not a
        // low-resolution or visually judged approximation of the former shadow.
        float mean = difference / (reference.getWidth() * reference.getHeight() * 3f);
        assertTrue(message + ": max channel delta=" + maximum + ", mean=" + mean, maximum <= 2 && mean <= .25f);
    }
    private static void discardNode(SurfaceShadowView shadow, String name) {
        try {
            Field gpu = SurfaceShadowView.class.getDeclaredField("gpu"); gpu.setAccessible(true);
            Object cache = gpu.get(shadow);
            Field node = cache.getClass().getDeclaredField(name); node.setAccessible(true);
            ((RenderNode) node.get(cache)).discardDisplayList();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Bitmap copy(Fixture fixture) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(80);
        Bitmap result = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        CountDownLatch completed = new CountDownLatch(1); int[] status = {-1};
        main(() -> {
            int[] origin = new int[2]; fixture.root.getLocationInWindow(origin);
            PixelCopy.request(fixture.dialog.getWindow(), new Rect(origin[0], origin[1], origin[0] + 640, origin[1] + 360),
                    result, code -> { status[0] = code; completed.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue("PixelCopy callback", completed.await(4, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, status[0]); return result;
    }
    private static void await(String message, BooleanSupplier ready) {
        long until = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < until) { if (ready.getAsBoolean()) return; SystemClock.sleep(30); }
        fail(message);
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static <T> T onMain(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(work.get())); return result.get();
    }
    private static FrameLayout.LayoutParams position(int width, int height) {
        FrameLayout.LayoutParams value = new FrameLayout.LayoutParams(width, height); value.leftMargin = 40; value.topMargin = 80; return value;
    }
    private static final class Fixture {
        final Dialog dialog;
        final FrameLayout root;
        final SurfaceShadowView shadow;
        ReferenceShadow reference;
        Fixture(Context context, int opacity) {
            dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(context); root.setBackgroundColor(0xffe2e5ea);
            root.setClipChildren(false); root.setClipToPadding(false);
            shadow = new SurfaceShadowView(context, 28, 14, opacity); root.addView(shadow, position(560, 188));
            dialog.setContentView(root);
            Window window = dialog.getWindow(); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show(); window.setLayout(640, 360);
        }
    }
    private static final class ReferenceShadow extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF shape = new RectF();
        ReferenceShadow(Context context, int opacity) { super(context); paint.setColor(opacity << 24); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); setRenderEffect(RenderEffect.createBlurEffect(14, 14, Shader.TileMode.CLAMP));
        }
        @Override protected void onDetachedFromWindow() { setRenderEffect(null); super.onDetachedFromWindow(); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { shape.set(28, 28, w - 28, h - 28); }
        @Override protected void onDraw(Canvas canvas) { canvas.drawRoundRect(shape, 28, 28, paint); }
    }
}
