package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Live native underlay shared by disjoint glass panels. Actual scene content goes
 * here; glass targeting this scene must be outside it to avoid recursive capture.
 * SurfaceView's separate surface cannot participate. No pixel snapshot is made.
 */
public final class GlassScene extends FrameLayout {
    // Only the cached blur is sampled at this resolution. The live scene and
    // every panel's final rounded mask remain at their original resolution.
    static final float SHARED_BLUR_RESOLUTION = .5f;
    private final Set<GlassDrawable> dependents = Collections.newSetFromMap(new WeakHashMap<>());
    private final Capture capture = Build.VERSION.SDK_INT >= 31 ? new Api31() : null;
    private int recordingCount, capturedWidth, capturedHeight;
    public GlassScene(Context context) { super(context); setWillNotDraw(false); }
    @Override public void draw(Canvas canvas) {
        if (capture == null || !canvas.isHardwareAccelerated() || getWidth() == 0 || getHeight() == 0) {
            super.draw(canvas); return;
        }
        int width = getWidth(), height = getHeight();
        boolean resized = capturedWidth != width || capturedHeight != height;
        Canvas recording = capture.begin(width, height);
        try { super.draw(recording); } finally { capture.end(); }
        capturedWidth = width; capturedHeight = height;
        recordingCount++; capture.draw(canvas);
        // Event-driven invalidation: content actually changed, not a frame timer.
        for (GlassDrawable surface : new ArrayList<>(dependents)) {
            if (resized) surface.sharedSourceReleased(); else surface.sourceReady();
        }
    }
    @Override protected void onDetachedFromWindow() {
        if (capture != null) capture.release();
        capturedWidth = capturedHeight = 0;
        for (GlassDrawable surface : new ArrayList<>(dependents)) surface.sourceReady();
        super.onDetachedFromWindow();
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        clearSharedBlurCache();
    }
    void addDependent(GlassDrawable surface) { dependents.add(surface); }
    void removeDependent(GlassDrawable surface) { dependents.remove(surface); }
    boolean hasLiveDisplayList() { return capture != null && capture.hasDisplayList(); }
    Object hardwareNode() { return capture == null ? null : capture.handle(); }
    Object sharedBlurNode(float scale, float blur) {
        // Layout/preDraw precede draw. A resized View can still have the previous
        // source recording here; never cache a clipped blur using those old bounds.
        return capture == null || capturedWidth != getWidth() || capturedHeight != getHeight()
                ? null : capture.blurred(scale, blur);
    }
    /** Owner pause/trim discards GPU layers; visible panels recreate them on demand. */
    public void clearSharedBlurCache() {
        GlassBackdrop.checkMain();
        if (capture != null) capture.clearBlurred();
        for (GlassDrawable surface : new ArrayList<>(dependents)) surface.sharedSourceReleased();
    }
    public int getRecordingCount() { GlassBackdrop.checkMain(); return recordingCount; }
    public int getSharedBlurNodeCount() { GlassBackdrop.checkMain(); return capture == null ? 0 : capture.blurCount(); }
    public int getSharedBlurRecordingCount() { GlassBackdrop.checkMain(); return capture == null ? 0 : capture.blurRecordings(); }
    public boolean isLiveSourceReady() { GlassBackdrop.checkMain(); return hasLiveDisplayList(); }
    private interface Capture {
        Canvas begin(int width, int height);
        void end();
        void draw(Canvas canvas);
        Object handle();
        boolean hasDisplayList();
        Object blurred(float scale, float blur);
        int blurCount();
        int blurRecordings();
        void clearBlurred();
        void release();
    }
    @android.annotation.TargetApi(31)
    private static final class Api31 implements Capture {
        private final RenderNode node = new RenderNode("Eve Box live underlay");
        private final ArrayList<BlurSource> blurred = new ArrayList<>();
        private int blurRecordingCount;
        @Override public Canvas begin(int width, int height) {
            node.setPosition(0, 0, width, height); return node.beginRecording(width, height);
        }
        @Override public void end() { node.endRecording(); }
        @Override public void draw(Canvas canvas) { canvas.drawRenderNode(node); }
        @Override public Object handle() { return node; }
        @Override public boolean hasDisplayList() { return node.hasDisplayList(); }
        @Override public Object blurred(float scale, float blur) {
            for (int index = blurred.size() - 1; index >= 0; index--) {
                BlurSource source = blurred.get(index);
                if (!source.ready()) { source.release(); blurred.remove(index); continue; }
                if (source.scale == scale && source.blur == blur) return source.node;
            }
            // Three material radii share a source at the page's design scale.
            // Never churn full-scene layers for a changing focus/ancestor scale.
            if (blurred.size() >= 3 || !node.hasDisplayList() || !Float.isFinite(scale) || scale <= 0) return null;
            float rasterScale = scale * SHARED_BLUR_RESOLUTION;
            int halo = (int) Math.ceil(blur * SHARED_BLUR_RESOLUTION * 2f);
            double width = Math.ceil(node.getWidth() * (double) rasterScale) + halo * 2;
            double height = Math.ceil(node.getHeight() * (double) rasterScale) + halo * 2;
            // Large or unusual coordinate spaces keep the exact per-panel path.
            if (width < 1 || height < 1 || width * height > 3840L * 2160) return null;
            BlurSource source = new BlurSource(node, scale, blur, halo, (int) width, (int) height);
            blurred.add(source); blurRecordingCount++;
            return source.node;
        }
        @Override public int blurCount() { return blurred.size() * 2; }
        @Override public int blurRecordings() { return blurRecordingCount; }
        @Override public void clearBlurred() {
            for (BlurSource source : blurred) source.release();
            blurred.clear();
        }
        @Override public void release() { clearBlurred(); node.discardDisplayList(); }
        private static final class BlurSource {
            final float scale, blur;
            final RenderNode node = new RenderNode("Eve Box shared live blur raster");
            final RenderNode filtered = new RenderNode("Eve Box shared live blur filter");
            BlurSource(RenderNode scene, float scale, float blur, int halo, int width, int height) {
                this.scale = scale; this.blur = blur;
                filtered.setPosition(0, 0, width, height); filtered.setClipToBounds(true);
                Canvas recording = filtered.beginRecording(width, height);
                try {
                    recording.translate(halo, halo);
                    recording.scale(scale * SHARED_BLUR_RESOLUTION, scale * SHARED_BLUR_RESOLUTION);
                    recording.drawRenderNode(scene);
                } finally { filtered.endRecording(); }
                float rasterBlur = blur * SHARED_BLUR_RESOLUTION;
                filtered.setRenderEffect(RenderEffect.createBlurEffect(rasterBlur, rasterBlur, Shader.TileMode.CLAMP));
                // A filtered node caches its snapshot for only one clipBounds in
                // HWUI. Different panels otherwise re-filter it on every draw.
                // Cache that output in an unfiltered hardware layer with one
                // fixed child/clip; live child damage still updates the layer.
                node.setPosition(0, 0, width, height); node.setClipToBounds(true);
                node.setUseCompositingLayer(true, null);
                recording = node.beginRecording(width, height);
                try {
                    recording.clipRect(0, 0, width, height); recording.drawRenderNode(filtered);
                } finally { node.endRecording(); }
            }
            boolean ready() { return node.hasDisplayList() && filtered.hasDisplayList(); }
            void release() {
                node.discardDisplayList(); node.setUseCompositingLayer(false, null);
                filtered.discardDisplayList(); filtered.setRenderEffect(null);
            }
        }
    }
}
