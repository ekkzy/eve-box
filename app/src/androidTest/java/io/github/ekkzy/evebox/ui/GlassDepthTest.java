package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
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
import io.github.ekkzy.evebox.MainActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual compositor evidence for depth outside a surface and optics above opaque art. */
@RunWith(AndroidJUnit4.class)
public class GlassDepthTest {
    @Test public void separateGpuShadowEscapesSurfaceAndSheenTracksFocusProgress() throws Exception {
        Assume.assumeTrue("Native GPU shape blur requires API31+", Build.VERSION.SDK_INT >= 31);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Fixture> result = new AtomicReference<>();
            activity.onActivity(host -> result.set(new Fixture(host)));
            Fixture fixture = result.get();
            try {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(200);
                assertTrue("Fixture uses the actual hardware window", onMain(fixture.root::isHardwareAccelerated));
                Bitmap idle = copy(fixture);
                try {
                    assertEquals("Untouched backdrop outside the shadow halo", 255, Color.red(idle.getPixel(40, 300)));
                    // All samples are below the original card, not its opaque face.
                    for (int y = 242; y <= 248; y++) {
                        int red = Color.red(idle.getPixel(300, y));
                        assertTrue("Shadow must darken pixels outside card bottom240, y=" + y + ": " + red, red < 248);
                    }
                    int base = idle.getPixel(240, 110);
                    assertEquals("Art is opaque and unchanged at progress0", Color.rgb(32, 64, 96), base);
                    main(() -> fixture.sheen.setFocusProgress(.5f));
                    Bitmap half = copy(fixture);
                    try {
                        main(() -> fixture.sheen.setFocusProgress(1f));
                        Bitmap full = copy(fixture);
                        try {
                            int middle = half.getPixel(240, 110), focused = full.getPixel(240, 110);
                            assertTrue("Existing spring progress must brighten the opaque artwork halfway",
                                    Color.red(middle) >= Color.red(base) + 5);
                            assertTrue("Full optical progress must add more sheen than half progress",
                                    Color.red(focused) >= Color.red(middle) + 5);
                            assertEquals("Sheen does not tint the external backdrop", idle.getPixel(40, 300), full.getPixel(40, 300));
                            main(() -> fixture.sheen.setFocusProgress(0f));
                            Bitmap restored = copy(fixture);
                            try { assertEquals("Reversal removes optical overlay", base, restored.getPixel(240, 110)); }
                            finally { restored.recycle(); }
                        } finally { full.recycle(); }
                    } finally { half.recycle(); }
                } finally { idle.recycle(); }
            } finally { main(fixture.dialog::dismiss); }
        }
    }

    private static Bitmap copy(Fixture fixture) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(100);
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        CountDownLatch completed = new CountDownLatch(1); int[] status = {-1};
        main(() -> {
            int[] origin = new int[2]; fixture.root.getLocationInWindow(origin);
            PixelCopy.request(fixture.dialog.getWindow(), new Rect(origin[0], origin[1], origin[0]+640, origin[1]+360),
                    image, value -> { status[0]=value; completed.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue("PixelCopy callback", completed.await(4, TimeUnit.SECONDS));
        assertEquals("Read actual GPU output", PixelCopy.SUCCESS, status[0]); return image;
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private interface Query<T> { T get(); }
    private static <T> T onMain(Query<T> query) {
        AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(query.get())); return result.get();
    }
    private static FrameLayout.LayoutParams position(int width, int height, int x, int y) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height); params.leftMargin=x; params.topMargin=y; return params;
    }
    private static final class Fixture {
        final Dialog dialog;
        final FrameLayout root;
        final ArtworkSheenDrawable sheen = new ArtworkSheenDrawable(20, false, true, true);
        Fixture(Context context) {
            dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(context); root.setBackgroundColor(Color.WHITE);
            SurfaceShadowView shadow = new SurfaceShadowView(context, 20, 14, 180);
            int halo = shadow.halo();
            root.addView(shadow, position(240+halo*2, 160+halo*2, 180-halo, 80-halo));
            View art = new View(context); art.setBackgroundColor(Color.rgb(32, 64, 96)); art.setForeground(sheen);
            root.addView(art, position(240, 160, 180, 80));
            dialog.setContentView(root);
            Window window = dialog.getWindow(); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show(); window.setLayout(640, 360);
        }
    }
}
