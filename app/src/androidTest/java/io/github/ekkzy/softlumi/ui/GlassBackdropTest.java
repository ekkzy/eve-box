package io.github.ekkzy.softlumi.ui;

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
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.ekkzy.softlumi.MainActivity;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** PixelCopy reads real hardware output; Canvas(Bitmap) intentionally is not used. */
@RunWith(AndroidJUnit4.class)
public class GlassBackdropTest {
    @Test public void strongDarkAndLightGlassTransmitTheLiveNeutralScene() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            for (boolean light : new boolean[] {false, true}) {
              int[] transmissions = new int[2];
              for (int profile = 0; profile < 2; profile++) {
                // Material.forSurface selects a dock at width >= 4 * height;
                // the existing 420x180 surface is explicitly a sheet.
                boolean dock = profile == 0;
                Fixture fixture = fixture(activity, light, true, dock ? 560 : 420, dock ? 120 : 180);
                try {
                    await("Strong live glass", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                    main(() -> fixture.content.setMode(4));
                    int darkSource = centerColor(fixture);
                    main(() -> fixture.content.setMode(5));
                    int lightSource = centerColor(fixture);
                    int transmitted = Color.red(lightSource) - Color.red(darkSource);
                    transmissions[profile] = transmitted;
                    // Matte translucency retains source contrast through the
                    // neutral veil and the half-strength center of the top wash.
                    float tint = dock ? (light ? .34f : .30f) : .46f;
                    float centerWash = (light ? 12f : 8f) / 510f;
                    int expected = Math.round((1f - tint) * (1f - centerWash) * 255f);
                    assertEquals((light ? "Day " : "Night ") + (dock ? "dock" : "sheet")
                            + " must retain its live matte transmission", expected, transmitted, 12);
                    for (int pixel : new int[] {darkSource, lightSource}) {
                        assertTrue("Neutral material must not introduce a blue cast", Math.abs(Color.blue(pixel) - Color.red(pixel)) <= 3);
                        assertTrue("Neutral material must not introduce a green cast", Math.abs(Color.green(pixel) - Color.red(pixel)) <= 3);
                    }
                } finally { main(fixture::close); }
              }
              assertTrue("The dock must transmit more of the live scene than the sheet",
                      transmissions[0] > transmissions[1] + 20);
            }
        }
    }

    @Test public void realGpuBlurRemovesCheckerDetailAndTracksLiveSourceColors() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                await("GPU panel display list", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                if(Build.VERSION.SDK_INT>=33)assertTrue("The live matte material is active",onMain(fixture.glass::isMatteMaterialReady));
                Bitmap image = copy(fixture);
                try {
                    float rawDetail = adjacentDetail(image, 100, 300, 180);
                    float glassDetail = adjacentDetail(image, 100, 160, 180);
                    assertTrue("Fixture must contain real high frequency detail: " + rawDetail, rawDetail > 40);
                    assertTrue("Blur must remove detail beyond mere translucent tint: " + glassDetail, glassDetail < 8);
                } finally { image.recycle(); }
                main(() -> fixture.content.setMode(1));
                int red = centerColor(fixture);
                main(() -> fixture.content.setMode(2));
                int blue = centerColor(fixture);
                assertTrue("Live source must change red to blue beneath stationary glass",
                        Color.red(red) > Color.blue(red) + 45 && Color.blue(blue) > Color.red(blue) + 45);
                assertEquals(0L, onMain(fixture.owner::getOwnedBitmapBytes).longValue());
                assertEquals(0, onMain(fixture.owner::getPendingWorkCount).intValue());
            } finally { main(fixture::close); }
        }
    }

    @Test public void movingHostAndMovingNativeUnderlayRemainAligned() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                main(() -> { fixture.content.setMode(3); fixture.panel.setLayoutParams(position(220, 180, 48, 80)); });
                await("GPU panel", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                int initial = centerColor(fixture);
                assertTrue("Initial panel is over red half", Color.red(initial) > Color.blue(initial) + 45);
                main(() -> {
                    fixture.board.setPivotX(0); fixture.board.setPivotY(0);
                    fixture.board.setScaleX(.9f); fixture.board.setScaleY(.9f);
                    fixture.board.setTranslationX(290);
                    fixture.panel.setScaleX(1.05f); fixture.panel.setTranslationX(12);
                });
                int moved = centerColor(fixture);
                assertTrue("Ancestor scale/translation must reveal blue, not a stuck red snapshot",
                        Color.blue(moved) > Color.red(moved) + 45);
                // Changes a native child RenderNode below stationary glass, including its text.
                main(() -> fixture.content.setTranslationX(200));
                int underlayMoved = centerColor(fixture);
                assertTrue("Moving underlying content must update a stationary panel",
                        Color.red(underlayMoved) > Color.blue(underlayMoved) + 45);
            } finally { main(fixture::close); }
        }
    }

    @Test public void liveSourceRerecordUpdatesBlurWithoutRerecordingStationaryPanel() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                await("Initial live glass", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                copy(fixture).recycle();
                int source = onMain(fixture.scene::getRecordingCount);
                int panel = onMain(fixture.glass::getRecordingCount);
                main(() -> { fixture.content.setMode(1); fixture.scene.invalidate(); });
                int red = centerColor(fixture);
                assertTrue("Source display list was actually replaced", onMain(fixture.scene::getRecordingCount) > source);
                assertEquals("Changing live pixels does not change the panel transform", panel,
                        onMain(fixture.glass::getRecordingCount).intValue());
                source = onMain(fixture.scene::getRecordingCount);
                main(() -> { fixture.content.setMode(2); fixture.scene.invalidate(); });
                int blue = centerColor(fixture);
                assertTrue("Second live source display list was replaced", onMain(fixture.scene::getRecordingCount) > source);
                assertEquals("A second content update still reuses the stationary panel", panel,
                        onMain(fixture.glass::getRecordingCount).intValue());
                assertTrue("The retained reference must transmit current red and blue pixels",
                        Color.red(red) > Color.blue(red) + 45 && Color.blue(blue) > Color.red(blue) + 45);
                main(() -> fixture.panel.setTranslationX(20));
                copy(fixture).recycle();
                assertTrue("A real geometry change must still update the panel display list",
                        onMain(fixture.glass::getRecordingCount) > panel);
            } finally { main(fixture::close); }
        }
    }

    @Test public void scrollingPanelReusesSharedBlurAndStillSamplesLiveUnderlay() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, false, false, 220, 180);
            try {
                main(() -> { fixture.content.setMode(3); fixture.scene.invalidate(); });
                await("Shared live source", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                int red = centerColor(fixture);
                int sourceRecordings = onMain(fixture.scene::getSharedBlurRecordingCount);
                assertEquals("One material owns a filtered node and its raster layer", 2,
                        onMain(fixture.scene::getSharedBlurNodeCount).intValue());
                for (int translation = 40; translation <= 280; translation += 40) {
                    final int position = translation;
                    // Page scrolling applies this same changing translation in
                    // the ancestor matrix while source scale/radius stay fixed.
                    main(() -> fixture.board.setTranslationX(position));
                    copy(fixture).recycle();
                    assertEquals("Movement must not create or re-record the source blur", sourceRecordings,
                            onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
                }
                int blue = centerColor(fixture);
                assertTrue("Moving glass samples each real region of the underlay",
                        Color.red(red) > Color.blue(red) + 45 && Color.blue(blue) > Color.red(blue) + 45);
                main(() -> { fixture.content.setMode(1); fixture.scene.invalidate(); });
                int refreshed = centerColor(fixture);
                assertTrue("A changed live source updates the reused blur", Color.red(refreshed) > Color.blue(refreshed) + 45);
                assertEquals("Live content does not allocate another blur source", sourceRecordings,
                        onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
            } finally { main(fixture::close); }
        }
    }

    @Test public void uniformFocusReusesSceneBlurAndKeepsLivePixelsAlignedAtDesignScales() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                main(() -> { fixture.panel.setClipToOutline(true); fixture.content.setMode(6); fixture.scene.invalidate(); });
                await("Live glass before focus", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                int designIndex = 0;
                for (float design : new float[] {1f, 1.5f, 3f}) {
                    int beforeDesign = onMain(fixture.glass::getRecordingCount);
                    main(() -> {
                        fixture.board.setScaleX(design); fixture.board.setScaleY(design);
                        fixture.panel.setScaleX(1); fixture.panel.setScaleY(1);
                        fixture.content.setMode(6); fixture.scene.invalidate();
                    });
                    if (design != 1f) awaitGlassGeometry(fixture, beforeDesign, "Parent geometry at design " + design);
                    SystemClock.sleep(50);
                    Bitmap reference = copy(fixture);
                    try {
                        int recordings = onMain(fixture.scene::getSharedBlurRecordingCount);
                        assertEquals("Each new parent design scale records exactly one blur source: "
                                + focusDiagnostics(fixture), ++designIndex, recordings);
                        int nodes = onMain(fixture.scene::getSharedBlurNodeCount);
                        // HWUI may discard display lists no longer referenced by
                        // the render tree when the panel switches parent scale.
                        // The cache prunes those entries; Java references do not
                        // promise that all historical scales retain GPU resources.
                        assertTrue("The active source remains within the three-pair cache bound: "
                                + focusDiagnostics(fixture), nodes >= 2 && nodes <= 6);
                        assertTrue("The fixture exposes a genuinely blurred source edge",
                                Color.red(reference.getPixel(420, 180)) - Color.red(reference.getPixel(220, 180)) > 70);
                        for (float focus : new float[] {1.0137f, 1.0273f, 1.0411f, 1.07f, 1.05f, 1f}) {
                            int beforeFocus = onMain(fixture.glass::getRecordingCount);
                            main(() -> { fixture.panel.setScaleX(focus); fixture.panel.setScaleY(focus); });
                            awaitGlassGeometry(fixture, beforeFocus, "Focus geometry at " + design + " x " + focus);
                            SystemClock.sleep(50);
                            Bitmap focused = copy(fixture);
                            try {
                                long difference = 0;
                                for (int x = 220; x <= 420; x += 2) {
                                    difference += Math.abs(Color.red(reference.getPixel(x, 180))
                                            - Color.red(focused.getPixel(x, 180)));
                                }
                                // Compare fixed destination coordinates, independently of
                                // the implementation's matrices. Focus cannot move or
                                // widen the scene-space blur profile under the glass.
                                assertTrue("Focus preserves scene alignment and optical radius at " + design + " x " + focus,
                                        difference / 101f <= 2f);
                                if (design == 1f && focus == 1.07f) {
                                    RectF bounds = onMain(() -> {
                                        Matrix panelGlobal = new Matrix(), rootGlobal = new Matrix(), inverse = new Matrix();
                                        fixture.panel.transformMatrixToGlobal(panelGlobal);
                                        fixture.root.transformMatrixToGlobal(rootGlobal);
                                        assertTrue(rootGlobal.invert(inverse));
                                        RectF result = new RectF(0, 0, fixture.panel.getWidth(), fixture.panel.getHeight());
                                        panelGlobal.mapRect(result); inverse.mapRect(result); return result;
                                    });
                                    int y = (int) Math.ceil(bounds.top) + 1;
                                    assertTrue("Native rounded corner exposes the black source without a rectangular halo",
                                            Color.red(focused.getPixel((int) Math.ceil(bounds.left) + 1, y)) <= 2);
                                    assertTrue("Native rounded corner preserves the white source at destination resolution",
                                            Color.red(focused.getPixel((int) Math.floor(bounds.right) - 2, y)) >= 253);
                                }
                            } finally { focused.recycle(); }
                            assertEquals("Fractional focus values must reuse the parent scale cache key: "
                                    + focusDiagnostics(fixture), recordings,
                                    onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
                        }
                        main(() -> {
                            fixture.panel.setScaleX(1.07f); fixture.panel.setScaleY(1.07f);
                            fixture.content.setMode(7); fixture.scene.invalidate();
                        });
                        SystemClock.sleep(80);
                        Bitmap refreshed = copy(fixture);
                        try {
                            assertTrue("Changing the real underlay reverses the edge through focused glass",
                                    Color.red(refreshed.getPixel(220, 180)) - Color.red(refreshed.getPixel(420, 180)) > 70);
                        } finally { refreshed.recycle(); }
                        assertEquals("Live updates do not allocate another full-scene cache entry", recordings,
                                onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
                        assertTrue(onMain(fixture.scene::getSharedBlurNodeCount) <= 6);
                    } finally { reference.recycle(); }
                }
            } finally { main(fixture::close); }
        }
    }

    @Test public void sharedBlurIsBoundedAndOwnerClearPauseDetachReleaseIt() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, false, false, 220, 180);
            AtomicReference<GlassDrawable[]> extra = new AtomicReference<>();
            try {
                await("Initial material", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(() -> {
                    View dock = new View(fixture.root.getContext());
                    GlassDrawable dockGlass = fixture.owner.drawable(dock, fixture.scene, 20, false, true);
                    dock.setBackground(dockGlass); fixture.board.addView(dock, position(400, 80, 40, 0));
                    View sheet = new View(fixture.root.getContext());
                    GlassDrawable sheetGlass = fixture.owner.drawable(sheet, fixture.scene, 20, false, true);
                    sheet.setBackground(sheetGlass); fixture.board.addView(sheet, position(150, 100, 480, 250));
                    FrameLayout scaled = new FrameLayout(fixture.root.getContext());
                    scaled.setScaleX(.9f); scaled.setScaleY(.9f);
                    fixture.board.addView(scaled, position(160, 100, 450, 110));
                    View fourth = new View(fixture.root.getContext());
                    GlassDrawable fourthGlass = fixture.owner.drawable(fourth, fixture.scene, 20, false, false);
                    fourth.setBackground(fourthGlass); scaled.addView(fourth, position(140, 80, 0, 0));
                    extra.set(new GlassDrawable[] {dockGlass, sheetGlass, fourthGlass});
                });
                await("Every material, including the fallback, remains live", () -> onMain(() -> {
                    for (GlassDrawable surface : extra.get()) if (!surface.isLiveBlurReady()) return false;
                    return fixture.glass.isLiveBlurReady();
                }), 4000);
                assertEquals("Three material radii own at most two nodes each", 6,
                        onMain(fixture.scene::getSharedBlurNodeCount).intValue());
                assertEquals("A fourth scale uses the per-panel fallback", 3,
                        onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
                main(() -> {
                    fixture.owner.clear();
                    assertEquals(0, fixture.owner.getGpuNodeCount());
                    assertEquals("Trim clears all shared display lists too", 0, fixture.owner.getSharedGpuNodeCount());
                });
                await("Active panels recover after trim", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(fixture.owner::pause);
                assertEquals("Paused pages retain no shared blur layer", 0, onMain(fixture.scene::getSharedBlurNodeCount).intValue());
                main(fixture.owner::resume);
                await("Resume rebuilds the live source", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(() -> fixture.root.removeView(fixture.scene));
                assertEquals("Scene detach releases its shared source cache", 0, onMain(fixture.scene::getSharedBlurNodeCount).intValue());
                copy(fixture).recycle();
                assertFalse(onMain(fixture.glass::isLiveBlurReady));
            } finally { main(fixture::close); }
        }
    }

    @Test public void growingAttachedSceneCannotReuseBlurClippedToItsPreviousRecordingSize() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity, false, false, 220, 180);
            try {
                main(() -> {
                    fixture.content.setMode(2);
                    fixture.scene.setLayoutParams(position(320, 360, 0, 0));
                });
                await("The attached source shrinks", () -> onMain(() -> fixture.scene.getWidth() == 320
                        && fixture.glass.isLiveBlurReady()), 4000);
                int small = centerColor(fixture);
                assertTrue("The original source supplies blue", Color.blue(small) > Color.red(small) + 45);
                int originalRecordings = onMain(fixture.scene::getSharedBlurRecordingCount);
                main(() -> {
                    fixture.scene.setLayoutParams(position(640, 360, 0, 0));
                    fixture.content.setMode(1);
                    fixture.panel.setTranslationX(280);
                });
                await("The source grows while remaining attached", () -> onMain(() -> fixture.scene.getWidth() == 640
                        && fixture.glass.isLiveBlurReady()), 4000);
                // This panel center is beyond the old x=320 source boundary.
                // A cache built in preDraw from the old source recording clips it.
                int expanded = centerColor(fixture);
                assertTrue("Newly exposed source pixels must be present in live blur",
                        Color.red(expanded) > Color.blue(expanded) + 45);
                assertEquals("The new bounds own one filtered source and its raster layer", 2,
                        onMain(fixture.scene::getSharedBlurNodeCount).intValue());
                assertEquals("Only the completed resized recording enters the cache", originalRecordings + 1,
                        onMain(fixture.scene::getSharedBlurRecordingCount).intValue());
            } finally { main(fixture::close); }
        }
    }

    @Test public void stationarySceneDoesNotScheduleFramesAndCloseCannotPublishStaleGlass() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                await("GPU panel", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                SystemClock.sleep(500);
                int source = onMain(fixture.scene::getRecordingCount);
                int panel = onMain(fixture.glass::getRecordingCount);
                SystemClock.sleep(500);
                assertEquals("Static source must not loop draw", source, onMain(fixture.scene::getRecordingCount).intValue());
                assertEquals("Static glass must not loop record", panel, onMain(fixture.glass::getRecordingCount).intValue());
                main(fixture.owner::pause);
                assertEquals(0, onMain(fixture.owner::getGpuNodeCount).intValue());
                assertEquals(0, onMain(fixture.owner::getSharedGpuNodeCount).intValue());
                main(fixture.owner::resume);
                await("Resume live glass", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(fixture.owner::close);
                int closedCount = onMain(fixture.glass::getRecordingCount);
                main(() -> { fixture.content.setMode(2); fixture.panel.setTranslationX(20); });
                copy(fixture).recycle();
                assertFalse(onMain(fixture.glass::isLiveBlurReady));
                assertEquals(0, onMain(fixture.owner::getGpuNodeCount).intValue());
                assertEquals(0, onMain(fixture.owner::getLiveSurfaceCount).intValue());
                assertEquals(closedCount, onMain(fixture.glass::getRecordingCount).intValue());
            } finally { main(fixture::close); }
        }
    }

    @Test public void unusedBackgroundAndDetachedSourceReleaseGpuThenRecoverOnReuse() throws Exception {
        requireGpuApi();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Fixture fixture = fixture(activity);
            try {
                await("Initial live glass", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(() -> fixture.panel.setBackgroundColor(Color.WHITE));
                copy(fixture).recycle();
                await("Unused material releases GPU", () -> onMain(fixture.owner::getGpuNodeCount) == 0, 4000);
                int unused = onMain(fixture.glass::getRecordingCount);
                main(() -> { fixture.content.setMode(2); fixture.panel.setTranslationX(10); });
                copy(fixture).recycle();
                assertEquals("Unused material does not record", unused, onMain(fixture.glass::getRecordingCount).intValue());
                main(() -> fixture.panel.setBackground(fixture.glass));
                await("Same material works when restored", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
                main(() -> fixture.root.removeView(fixture.scene));
                copy(fixture).recycle();
                assertFalse("Detached target cannot be reported as live", onMain(fixture.glass::isLiveBlurReady));
                assertEquals(0, onMain(fixture.owner::getGpuNodeCount).intValue());
                main(() -> fixture.root.addView(fixture.scene, 0, position(640, 360, 0, 0)));
                await("Reattached scene recovers", () -> onMain(fixture.glass::isLiveBlurReady), 4000);
            } finally { main(fixture::close); }
        }
    }
    private static void requireGpuApi() { Assume.assumeTrue("RenderEffect requires API 31", Build.VERSION.SDK_INT >= 31); }
    private static Fixture fixture(ActivityScenario<MainActivity> activity) {
        return fixture(activity, false, false);
    }
    private static Fixture fixture(ActivityScenario<MainActivity> activity, boolean light, boolean strong) {
        return fixture(activity, light, strong, 420, 180);
    }
    private static Fixture fixture(ActivityScenario<MainActivity> activity, boolean light, boolean strong,
            int width, int height) {
        AtomicReference<Fixture> result = new AtomicReference<>();
        activity.onActivity(host -> result.set(new Fixture(host, light, strong, width, height)));
        Fixture fixture = result.get();
        await("Hardware window", () -> onMain(() -> fixture.root.getWidth() >= 640
                && fixture.root.getHeight() >= 360 && fixture.root.isHardwareAccelerated()), 4000);
        return fixture;
    }
    private static int centerColor(Fixture fixture) throws Exception {
        // Wait for the actual requested UI change and a subsequent compositor frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(150);
        float[] sample = onMain(() -> {
            // Both transforms use the same native global basis. Inverting root then
            // maps the actual transformed panel center into the PixelCopy crop.
            Matrix panelGlobal = new Matrix(), rootGlobal = new Matrix(), globalToRoot = new Matrix();
            fixture.panel.transformMatrixToGlobal(panelGlobal);
            fixture.root.transformMatrixToGlobal(rootGlobal);
            assertTrue("Fixture root transform must be invertible", rootGlobal.invert(globalToRoot));
            float[] center = {fixture.panel.getWidth() / 2f, fixture.panel.getHeight() / 2f};
            panelGlobal.mapPoints(center); globalToRoot.mapPoints(center); return center;
        });
        Bitmap image = copy(fixture);
        try {
            assertTrue("Panel center x outside copied fixture: " + sample[0], sample[0] >= 0 && sample[0] < image.getWidth());
            assertTrue("Panel center y outside copied fixture: " + sample[1], sample[1] >= 0 && sample[1] < image.getHeight());
            return image.getPixel((int) sample[0], (int) sample[1]);
        } finally { image.recycle(); }
    }
    private static void awaitGlassGeometry(Fixture fixture, int previous, String message) {
        long until = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < until) {
            if (onMain(fixture.glass::getRecordingCount) > previous) return;
            SystemClock.sleep(30);
        }
        fail(message + ": " + focusDiagnostics(fixture));
    }
    private static String focusDiagnostics(Fixture fixture) {
        return onMain(() -> {
            Matrix sourceGlobal = new Matrix(), hostGlobal = new Matrix(), parentGlobal = new Matrix();
            Matrix inverse = new Matrix(), sourceToHost = new Matrix(), sourceToParent = new Matrix();
            fixture.scene.transformMatrixToGlobal(sourceGlobal);
            fixture.panel.transformMatrixToGlobal(hostGlobal);
            fixture.board.transformMatrixToGlobal(parentGlobal);
            boolean hostInvertible = hostGlobal.invert(inverse);
            sourceToHost.setConcat(inverse, sourceGlobal);
            boolean parentInvertible = parentGlobal.invert(inverse);
            sourceToParent.setConcat(inverse, sourceGlobal);
            float[] actual = new float[9], canonical = new float[9];
            sourceToHost.getValues(actual); sourceToParent.getValues(canonical);
            Rect panelVisible = new Rect(), sceneVisible = new Rect();
            boolean panelShown = fixture.panel.getGlobalVisibleRect(panelVisible);
            boolean sceneShown = fixture.scene.getGlobalVisibleRect(sceneVisible);
            return "sourceToHost=" + Arrays.toString(actual) + ", sourceToParent=" + Arrays.toString(canonical)
                    + ", invertible=" + hostInvertible + "/" + parentInvertible
                    + ", sourceGlobal=" + sourceGlobal + ", hostGlobal=" + hostGlobal + ", parentGlobal=" + parentGlobal
                    + ", ownMatrix=" + fixture.panel.getMatrix() + ", parentMatrix=" + fixture.board.getMatrix()
                    + ", panelVisible=" + panelShown + ":" + panelVisible + ", sceneVisible=" + sceneShown + ":" + sceneVisible
                    + ", scene=" + fixture.scene.getWidth() + "x" + fixture.scene.getHeight()
                    + ", panel=" + fixture.panel.getWidth() + "x" + fixture.panel.getHeight()
                    + ", panelRecords=" + fixture.glass.getRecordingCount() + ", sourceRecords=" + fixture.scene.getRecordingCount()
                    + ", sharedRecords=" + fixture.scene.getSharedBlurRecordingCount()
                    + ", sharedNodes=" + fixture.scene.getSharedBlurNodeCount();
        });
    }
    private static Bitmap copy(Fixture fixture) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        CountDownLatch completed = new CountDownLatch(1); int[] status = {-1};
        main(() -> {
            int[] origin = new int[2]; fixture.root.getLocationInWindow(origin);
            PixelCopy.request(fixture.dialog.getWindow(), new Rect(origin[0], origin[1], origin[0] + 640, origin[1] + 360),
                    image, value -> { status[0] = value; completed.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue("PixelCopy callback", completed.await(4, TimeUnit.SECONDS));
        assertEquals("Read actual GPU window", PixelCopy.SUCCESS, status[0]); return image;
    }
    private static float adjacentDetail(Bitmap image, int left, int top, int width) {
        long sum = 0;
        for (int x = left; x < left + width; x++) sum += Math.abs(Color.red(image.getPixel(x, top)) - Color.red(image.getPixel(x + 1, top)));
        return sum / (float) width;
    }
    private static void await(String message, BooleanSupplier ready, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < until) { if (ready.getAsBoolean()) return; SystemClock.sleep(30); }
        fail(message);
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private interface Query<T> { T get(); }
    private static <T> T onMain(Query<T> query) {
        AtomicReference<T> result = new AtomicReference<>(); main(() -> result.set(query.get())); return result.get();
    }
    private static FrameLayout.LayoutParams position(int width, int height, int x, int y) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height); params.leftMargin = x; params.topMargin = y; return params;
    }
    private static final class Fixture {
        final Dialog dialog;
        final FrameLayout root, board;
        final GlassScene scene;
        final Pattern content;
        final View panel;
        final GlassBackdrop owner = new GlassBackdrop();
        final GlassDrawable glass;
        Fixture(Context context, boolean light, boolean strong, int width, int height) {
            dialog = new Dialog(context); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(context); root.setBackgroundColor(Color.BLACK);
            scene = new GlassScene(context); content = new Pattern(context);
            scene.addView(content, position(640, 360, 0, 0)); root.addView(scene, position(640, 360, 0, 0));
            board = new FrameLayout(context); root.addView(board, position(640, 360, 0, 0));
            panel = new View(context); glass = owner.drawable(panel, scene, 20, light, strong); panel.setBackground(glass);
            board.addView(panel, position(width, height, Math.min(80, (640-width)/2), 80)); dialog.setContentView(root);
            Window window = dialog.getWindow(); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.show(); window.setLayout(640, 360);
        }
        void close() { owner.close(); dialog.dismiss(); }
    }
    private static final class Pattern extends View {
        final Paint paint = new Paint(); int mode;
        Pattern(Context context) { super(context); }
        void setMode(int value) { mode = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (mode == 4 || mode == 5) { canvas.drawColor(mode == 4 ? Color.BLACK : Color.WHITE); return; }
            if (mode == 6 || mode == 7) {
                canvas.drawColor(mode == 6 ? Color.BLACK : Color.WHITE);
                paint.setColor(mode == 6 ? Color.WHITE : Color.BLACK);
                canvas.drawRect(320, 0, getWidth(), getHeight(), paint); return;
            }
            if (mode == 0) {
                for (int y = 0; y < getHeight(); y += 4) for (int x = 0; x < getWidth(); x += 4) {
                    paint.setColor(((x / 4 + y / 4) & 1) == 0 ? Color.BLACK : Color.WHITE);
                    canvas.drawRect(x, y, x + 4, y + 4, paint);
                }
            } else {
                canvas.drawColor(mode == 2 ? Color.BLUE : Color.RED);
                if (mode == 3) { paint.setColor(Color.BLUE); canvas.drawRect(320, 0, getWidth(), getHeight(), paint); }
                paint.setColor(Color.WHITE); paint.setTextSize(22); canvas.drawText("LIVE UNDERLAY", 60, 310, paint);
            }
        }
    }
}
