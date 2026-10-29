package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

@RunWith(AndroidJUnit4.class)
public class ContentBackdropTest {
    @Test public void unsupportedSoftwarePathKeepsTheExactUntintedGradient() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (boolean light : new boolean[] {true, false}) {
            main(() -> {
                ContentBackdrop background = new ContentBackdrop(context, light, false);
                background.posterView().setImageBitmap(solid(Color.RED));
                background.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
                background.layout(0, 0, 640, 360);
                Bitmap output = software(background);
                try { assertGradient(output, light); }
                finally { output.recycle(); background.posterView().setImageDrawable(null); }
            });
        }
    }

    @Test public void latePosterBindingUpdatesRealGpuUnderlayAndSoftwareStillFallsBack() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 31);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            for (boolean light : new boolean[] {true, false}) {
                AtomicReference<Fixture> reference = new AtomicReference<>();
                activity.onActivity(host -> reference.set(new Fixture(host, light)));
                Fixture fixture = reference.get();
                try {
                    awaitReady(fixture);
                    Bitmap initial = copy(fixture);
                    try { assertGradient(initial, light); } finally { initial.recycle(); }
                    // ImageView starts empty in the already recorded GlassScene. Bind later,
                    // exactly as UiImageLoader's asynchronous setImageBitmap publication does.
                    main(() -> fixture.background.posterView().setImageBitmap(solid(Color.RED)));
                    int red = center(fixture);
                    main(() -> fixture.background.posterView().setImageBitmap(solid(Color.BLUE)));
                    int blue = center(fixture);
                    int difference = light ? 32 : 45;
                    assertTrue("Red poster reached the actual scene", Color.red(red) > Color.blue(red) + difference);
                    assertTrue("Late replacement reached the actual scene", Color.blue(blue) > Color.red(blue) + difference);
                    main(() -> {
                        Bitmap fallback = software(fixture.background);
                        try { assertGradient(fallback, light); } finally { fallback.recycle(); }
                    });
                } finally { main(fixture::close); }
                assertEquals("Detached photo layer is suppressed", 0f,
                        onMain(() -> fixture.background.posterView().getAlpha()), 0f);
            }
        }
    }

    private static final class Fixture {
        final Dialog dialog;
        final GlassScene scene;
        final ContentBackdrop background;
        Fixture(Context context, boolean light) {
            dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            scene = new GlassScene(context); background = new ContentBackdrop(context, light, false);
            scene.addView(background, new FrameLayout.LayoutParams(-1, -1)); dialog.setContentView(scene);
            Window window = dialog.getWindow(); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show(); window.setLayout(640, 360);
        }
        void close() { dialog.dismiss(); background.posterView().setImageDrawable(null); }
    }
    private static Bitmap solid(int color) {
        Bitmap source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); source.eraseColor(color); return source;
    }
    private static Bitmap software(ContentBackdrop background) {
        Bitmap output = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        background.draw(new Canvas(output)); return output;
    }
    private static void assertGradient(Bitmap output, boolean light) {
        assertColor(light ? 0xffedf0f4 : 0xff202733, output.getPixel(320, 0));
        assertColor(light ? 0xffd4dbe3 : 0xff10141c, output.getPixel(320, 359));
    }
    private static void assertColor(int expected, int actual) {
        assertEquals(Color.red(expected), Color.red(actual), 1);
        assertEquals(Color.green(expected), Color.green(actual), 1);
        assertEquals(Color.blue(expected), Color.blue(actual), 1);
    }
    private static void awaitReady(Fixture fixture) {
        long until = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < until) {
            if (onMain(() -> fixture.scene.getWidth() == 640 && fixture.scene.getHeight() == 360
                    && fixture.scene.isHardwareAccelerated() && fixture.background.posterView().getAlpha() > 0f)) return;
            SystemClock.sleep(30);
        }
        fail("Hardware backdrop must attach and lay out");
    }
    private static int center(Fixture fixture) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(150);
        Bitmap image = copy(fixture);
        try { return image.getPixel(320, 180); } finally { image.recycle(); }
    }
    private static Bitmap copy(Fixture fixture) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        CountDownLatch done = new CountDownLatch(1); int[] result = {-1};
        main(() -> {
            int[] location = new int[2]; fixture.scene.getLocationInWindow(location);
            PixelCopy.request(fixture.dialog.getWindow(), new Rect(location[0], location[1], location[0] + 640, location[1] + 360),
                    image, status -> { result[0] = status; done.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue("PixelCopy callback", done.await(4, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, result[0]); return image;
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private interface Query<T> { T get(); }
    private static <T> T onMain(Query<T> query) {
        AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(query.get())); return result.get();
    }
}
