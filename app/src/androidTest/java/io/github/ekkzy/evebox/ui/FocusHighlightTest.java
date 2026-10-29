package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android Canvas foreground tests; these do not claim hardware blur or visual approval. */
@RunWith(AndroidJUnit4.class)
public final class FocusHighlightTest {
    private static final int WIDTH = 240, HEIGHT = 140, GRAY = 0xFF606060;

    @Test public void idleForegroundLeavesArtworkUntouchedForEveryProfile() {
        for (boolean light : new boolean[] {false, true}) for (int profile = 0; profile < 4; profile++) {
            ArtworkSheenDrawable drawable = profile(profile, light);
            drawable.setFocused(false); drawable.setFocusProgress(0);
            Bitmap frame = render(drawable, GRAY);
            try {
                int[] pixels = new int[WIDTH * HEIGHT]; frame.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                for (int pixel : pixels) assertEquals("Idle must not add a border or tint", GRAY, pixel);
            } finally { frame.recycle(); }
        }
    }

    @Test public void focusedFaceHasNoSharpUniformPerimeterBand() {
        for (boolean light : new boolean[] {false, true}) for (int profile = 0; profile < 4; profile++) {
            ArtworkSheenDrawable drawable = profile(profile, light);
            drawable.setFocused(true); drawable.setFocusProgress(1);
            Bitmap frame = render(drawable, GRAY);
            try {
                // Probe straight edges away from the explicitly local top-left
                // glint. A 1-3 px white/gray perimeter stroke fails these checks;
                // a face gradient changes smoothly across these six-pixel spans.
                gradual(frame, 1, HEIGHT / 2, 7, HEIGHT / 2, "left", profile);
                gradual(frame, WIDTH - 2, HEIGHT / 2, WIDTH - 8, HEIGHT / 2, "right", profile);
                gradual(frame, WIDTH / 2, 1, WIDTH / 2, 7, "top", profile);
                gradual(frame, WIDTH / 2, HEIGHT - 2, WIDTH / 2, HEIGHT - 8, "bottom", profile);
            } finally { frame.recycle(); }
        }
    }

    @Test public void focusedReflectionChangesBothBlackAndWhiteArtwork() {
        for (boolean light : new boolean[] {false, true}) for (int profile = 0; profile < 4; profile++) {
            ArtworkSheenDrawable drawable = profile(profile, light);
            drawable.setFocused(true); drawable.setFocusProgress(1);
            Bitmap black = render(drawable, Color.BLACK), white = render(drawable, Color.WHITE);
            try {
                int upper = Color.red(black.getPixel(WIDTH / 4, HEIGHT / 5));
                int lower = Color.red(white.getPixel(WIDTH / 2, HEIGHT * 85 / 100));
                assertTrue("White reflection must survive black imagery, profile " + profile, upper >= 8);
                assertTrue("Lower face must remain visible on white imagery, profile " + profile, lower <= 243);
                assertEquals("Rounded corner stays outside the optical face", Color.BLACK, black.getPixel(0, 0));
            } finally { black.recycle(); white.recycle(); }
        }
    }

    @Test public void equivalentFocusPixelsDoNotInvalidateTheArtworkDisplayList() {
        for (boolean light : new boolean[] {false, true}) for (int profile = 0; profile < 4; profile++) {
            ArtworkSheenDrawable drawable = profile(profile, light);
            AtomicInteger invalidations = new AtomicInteger();
            drawable.setCallback(new Drawable.Callback() {
                @Override public void invalidateDrawable(Drawable who) { invalidations.incrementAndGet(); }
                @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { fail("Sheen owns no timer"); }
                @Override public void unscheduleDrawable(Drawable who, Runnable what) { }
            });
            drawable.setFocusProgress(.5f);
            Bitmap first = render(drawable, GRAY);
            invalidations.set(0);
            drawable.setFocusProgress(.5001f);
            assertEquals("Spring presentation retains full precision", .5001f, drawable.getFocusProgress(), 0);
            assertEquals("Identical optical pixels must reuse the artwork display list", 0, invalidations.get());
            Bitmap equivalent = render(drawable, GRAY);
            try { assertTrue("Skipping invalidation must preserve every rendered pixel", first.sameAs(equivalent)); }
            finally { first.recycle(); equivalent.recycle(); }
            drawable.setFocusProgress(.51f);
            assertEquals("A visible optical change must redraw", 1, invalidations.get());
            drawable.setAlpha(0); invalidations.set(0);
            drawable.setFocusProgress(.7f);
            assertEquals("An invisible foreground must not redraw its artwork", 0, invalidations.get());
            drawable.setAlpha(255);
            assertEquals("Showing the foreground publishes its latest progress", 1, invalidations.get());
            ArtworkSheenDrawable expected = profile(profile, light); expected.setFocusProgress(.7f);
            Bitmap shown = render(drawable, GRAY), latest = render(expected, GRAY);
            try { assertTrue("Hidden spring updates must appear when shown", shown.sameAs(latest)); }
            finally { shown.recycle(); latest.recycle(); }
        }
    }

    @Test public void reducedMotionUsesStaticEndpointAndSchedulesNoAnimation() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            UiMotion motion = new UiMotion(context, 1280);
            try {
                View control = new View(context);
                ArtworkSheenDrawable foreground = ArtworkSheenDrawable.control(24, false);
                control.setForeground(foreground);
                motion.configure(true, true); motion.resume();
                motion.focus(control, true, UiMotion.Style.CONTROL, 1.04f);
                assertEquals(1, foreground.getFocusProgress(), 0);
                assertEquals("Reduced motion keeps unscaled geometry", 1, control.getScaleX(), 0);
                assertFalse(motion.isRunning()); assertEquals(0, motion.runningChannelCount());
                Bitmap selected = render(foreground, Color.BLACK);
                try { assertTrue(Color.red(selected.getPixel(WIDTH / 4, HEIGHT / 5)) >= 8); }
                finally { selected.recycle(); }
                motion.focus(control, false, UiMotion.Style.CONTROL, 1.04f);
                assertEquals(0, foreground.getFocusProgress(), 0);
                assertFalse(motion.isRunning()); assertEquals(0, motion.runningChannelCount());
                Bitmap idle = render(foreground, GRAY);
                try { assertEquals(GRAY, idle.getPixel(WIDTH / 2, HEIGHT / 2)); }
                finally { idle.recycle(); }
            } catch (Throwable error) { failure.set(error); }
            finally { motion.close(); }
        });
        if (failure.get() != null) throw new AssertionError("Reduced-motion foreground", failure.get());
    }

    private static ArtworkSheenDrawable profile(int profile, boolean light) {
        if (profile == 3) return ArtworkSheenDrawable.control(24, light);
        return new ArtworkSheenDrawable(24, light, profile == 2, profile == 1);
    }
    private static Bitmap render(ArtworkSheenDrawable drawable, int background) {
        Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame); canvas.drawColor(background);
        drawable.setBounds(0, 0, WIDTH, HEIGHT); drawable.draw(canvas); return frame;
    }
    private static void gradual(Bitmap frame, int x, int y, int innerX, int innerY, String edge, int profile) {
        int outside = Color.red(frame.getPixel(x, y)), inside = Color.red(frame.getPixel(innerX, innerY));
        assertTrue("No sharp " + edge + " perimeter band, profile " + profile + ": " + outside + "/" + inside,
                Math.abs(outside - inside) <= 8);
    }
}
