package io.github.ekkzy.softlumi.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Live matte backdrop. Only the background is filtered; rounded coverage and
 * the neutral veil are composited in destination space, never inside a scaled
 * RenderEffect texture. No wallpaper snapshot or CPU bitmap is created.
 */
public final class GlassDrawable extends Drawable implements View.OnAttachStateChangeListener {
    private final GlassBackdrop owner;
    private final WeakReference<View> host;
    private final WeakReference<GlassScene> scene;
    private final float cornerRadius;
    private final boolean light, strongFrost;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private final Path clip = new Path();
    private final Outline hostOutline = new Outline();
    private final Rect hostClipBounds = new Rect();
    private final Rect visibleBounds = new Rect();
    private final Matrix hostToRoot = new Matrix(), sourceToRoot = new Matrix(), rootToHost = new Matrix();
    private final Matrix parentToRoot = new Matrix(), rootToParent = new Matrix(), sourceToParent = new Matrix();
    private final Matrix sourceToHost = new Matrix(), step = new Matrix();
    private final float[] matrixValues = new float[9], previousValues = new float[9];
    private final float[] parentValues = new float[9];
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> { synchronizeGeometry(); return true; };
    private ViewTreeObserver observer;
    private Gpu gpu;
    private Material material;
    private boolean geometryReady, disposed;
    private int alpha = 255, sourceWidth, sourceHeight, recordingCount;
    private float previousCanonicalScale = Float.NaN;

    GlassDrawable(GlassBackdrop owner, View host, GlassScene target, float radius, boolean light, boolean strongFrost) {
        this.owner = owner; this.host = new WeakReference<>(host); scene = new WeakReference<>(target);
        cornerRadius = Math.max(0, radius); this.light = light; this.strongFrost = strongFrost;
        host.addOnAttachStateChangeListener(this); target.addDependent(this);
        if (host.isAttachedToWindow() && owner.isActive()) observe(host);
    }
    void sourceReady() {
        // The panel display list references the same live scene RenderNode.
        // Its content can change without re-recording the unchanged panel
        // transform. Invalidation still makes the filtered content redraw.
        invalidateSelf();
    }
    void sharedSourceReleased() { geometryReady = false; invalidateSelf(); }
    void setEnabled(boolean enabled) {
        if (disposed) return;
        View view = host.get();
        if (enabled && view != null && view.isAttachedToWindow()) observe(view);
        else { unobserve(); release(); }
        invalidateSelf();
    }
    void release() {
        if (gpu != null) { gpu.release(); gpu = null; }
        geometryReady = false; invalidateSelf();
    }
    void dispose() {
        if (disposed) return;
        disposed = true; unobserve(); release();
        View view = host.get(); if (view != null) view.removeOnAttachStateChangeListener(this);
        GlassScene target = scene.get(); if (target != null) target.removeDependent(this);
    }
    boolean hasGpuDisplayList() { return gpu != null && gpu.hasDisplayList(); }
    public int getRecordingCount() { GlassBackdrop.checkMain(); return recordingCount; }
    public boolean isLiveBlurReady() { GlassBackdrop.checkMain(); return owner.isActive() && usableSource() && hasGpuDisplayList(); }
    public boolean isMatteMaterialReady() { return isLiveBlurReady() && material != null; }
    @Override public void onViewAttachedToWindow(View view) { if (!disposed && owner.isActive()) observe(view); }
    @Override public void onViewDetachedFromWindow(View view) { unobserve(); release(); }
    private void observe(View view) {
        ViewTreeObserver current = view.getViewTreeObserver();
        if (observer == current) return;
        unobserve(); observer = current; observer.addOnPreDrawListener(preDraw);
    }
    private void unobserve() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
    }
    @Override protected void onBoundsChange(Rect bounds) {
        face.set(bounds); geometryReady = false;
        material = Material.forSurface(light, strongFrost, face.width(), face.height());
        clip.reset(); clip.addRoundRect(face, radius(), radius(), Path.Direction.CW);
        if (face.isEmpty()) return;
        // A quiet top-to-bottom lift without a rim, lens, or bevel.
        wash.setShader(new LinearGradient(face.left, face.top, face.left, face.bottom,
                light ? 0x0cFFFFFF : 0x08FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }
    @Override public void draw(Canvas canvas) {
        if (face.isEmpty() || alpha == 0) return;
        boolean blurred = false;
        View view = host.get();
        if (!disposed && owner.isActive() && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            if (view != null && view.getBackground() == this) observe(view);
            synchronizeGeometry();
            blurred = gpu != null && gpu.hasDisplayList();
        }
        int saved = alpha == 255 ? canvas.save() : canvas.saveLayerAlpha(face, alpha);
        // The caller's rounded outline already owns final native AA coverage.
        // Otherwise this one destination-space clip provides the same contract.
        // Neither mask is recorded into the smaller blur RenderNode.
        if (!hasMatchingNativeClip(canvas, view)) canvas.clipPath(clip);
        if (blurred) gpu.draw(canvas, 255);
        tint.setColor(material.tintColor);
        float opacity = blurred ? material.tintAlpha : (light ? .56f : .58f);
        tint.setAlpha(Math.round(opacity * 255));
        canvas.drawRect(face, tint);
        wash.setAlpha(255); canvas.drawRect(face, wash);
        canvas.restoreToCount(saved);
    }
    private boolean hasMatchingNativeClip(Canvas canvas, View view) {
        if (!canvas.isHardwareAccelerated() || view == null || !view.getClipToOutline()
                || view.getOutlineProvider() == null) return false;
        hostOutline.setEmpty(); view.getOutlineProvider().getOutline(view, hostOutline);
        return hostOutline.canClip() && hostOutline.getRect(hostClipBounds)
                && hostClipBounds.equals(getBounds()) && Math.abs(hostOutline.getRadius() - radius()) < .001f;
    }
    /** Geometry/content changes trigger an update; stationary glass schedules no frames. */
    private void synchronizeGeometry() {
        if (disposed || !owner.isActive() || Build.VERSION.SDK_INT < 31 || face.isEmpty()) return;
        View view = host.get(); GlassScene target = scene.get();
        if (view == null || view.getBackground() != this) {
            unobserve(); if (gpu != null) release(); return;
        }
        if (!usableSource()) { if (gpu != null) release(); return; }
        // Retained pages and offscreen controls are still attached. They must
        // not re-record blur on every animation frame outside the viewport.
        if (!view.isShown() || !view.getGlobalVisibleRect(visibleBounds) || visibleBounds.isEmpty()) return;
        toRoot(view, hostToRoot); toRoot(target, sourceToRoot);
        if (!hostToRoot.invert(rootToHost)) return;
        sourceToHost.setConcat(rootToHost, sourceToRoot); sourceToHost.getValues(matrixValues);
        float canonicalScale = Float.NaN;
        if (Float.isFinite(view.getScaleX()) && view.getScaleX() > 0 && view.getScaleX() == view.getScaleY()
                && view.getRotation() == 0 && view.getRotationX() == 0 && view.getRotationY() == 0
                && view.getParent() instanceof View) {
            // Derive this key directly from ancestors. Multiplying the inverse
            // focused matrix by own scale can vary its last float bit each frame.
            toRoot((View) view.getParent(), parentToRoot);
            if (parentToRoot.invert(rootToParent)) {
                sourceToParent.setConcat(rootToParent, sourceToRoot);
                sourceToParent.getValues(parentValues);
                if (translatedUniformScale(parentValues)) canonicalScale = parentValues[Matrix.MSCALE_X];
            }
        }
        boolean changed = !geometryReady || sourceWidth != target.getWidth() || sourceHeight != target.getHeight()
                || Float.compare(canonicalScale, previousCanonicalScale) != 0
                || !Arrays.equals(matrixValues, previousValues);
        if (!changed && gpu != null && gpu.hasDisplayList()) return;
        if (gpu == null) gpu = new Api31();
        gpu.record(target, sourceToHost, face, material.blur, canonicalScale);
        sourceWidth = target.getWidth(); sourceHeight = target.getHeight();
        previousCanonicalScale = canonicalScale;
        System.arraycopy(matrixValues, 0, previousValues, 0, 9); geometryReady = true; recordingCount++;
        // Property animations may only dirty a parent's RenderNode, not this background.
        // Invalidate this host only on a real matrix/source/bounds change.
        invalidateSelf();
    }
    private boolean usableSource() {
        View view = host.get(); GlassScene target = scene.get();
        return view != null && view.getBackground() == this && target != null && view.isAttachedToWindow() && target.isAttachedToWindow()
                && view.getRootView() == target.getRootView() && !isWithin(view, target) && target.hasLiveDisplayList();
    }
    private static boolean isWithin(View view, View parent) {
        for (View current = view; current != null; ) {
            if (current == parent) return true;
            ViewParent next = current.getParent(); current = next instanceof View ? (View) next : null;
        }
        return false;
    }
    /** Every ancestor pivot/scale/translation and scroll contributes in shared-root space. */
    private void toRoot(View view, Matrix result) {
        result.reset(); View child = view;
        while (child.getParent() instanceof View) {
            View parent = (View) child.getParent(); step.set(child.getMatrix());
            step.postTranslate(child.getLeft() - parent.getScrollX(), child.getTop() - parent.getScrollY());
            result.postConcat(step); child = parent;
        }
    }
    private float radius() { return Math.min(cornerRadius, Math.min(face.width(), face.height()) / 2f); }
    private static boolean translatedUniformScale(float[] values) {
        return Float.isFinite(values[Matrix.MSCALE_X]) && values[Matrix.MSCALE_X] > 0
                && values[Matrix.MSCALE_X] == values[Matrix.MSCALE_Y]
                && Float.isFinite(values[Matrix.MTRANS_X]) && Float.isFinite(values[Matrix.MTRANS_Y])
                && values[Matrix.MSKEW_X] == 0 && values[Matrix.MSKEW_Y] == 0
                && values[Matrix.MPERSP_0] == 0 && values[Matrix.MPERSP_1] == 0 && values[Matrix.MPERSP_2] == 1;
    }
    @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(), radius()); }
    @Override public void setAlpha(int alpha) { this.alpha = Math.max(0, Math.min(255, alpha)); invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        tint.setColorFilter(filter); wash.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    private interface Gpu {
        void record(GlassScene scene, Matrix matrix, RectF bounds, float blur, float canonicalScale);
        void draw(Canvas canvas, int alpha);
        boolean hasDisplayList();
        void release();
    }
    private static final class Material {
        final int tintColor;
        final float tintAlpha, blur;
        Material(boolean light, float tintAlpha, float blur) {
            tintColor = light ? 0xFFFFFFFF : 0xFF181818;
            this.tintAlpha = tintAlpha; this.blur = blur;
        }
        private static final Material DOCK_DAY = new Material(true, .34f, 28);
        private static final Material DOCK_NIGHT = new Material(false, .30f, 28);
        private static final Material SHEET_DAY = new Material(true, .46f, 30);
        private static final Material SHEET_NIGHT = new Material(false, .46f, 30);
        private static final Material CONTROL_DAY = new Material(true, .40f, 24);
        private static final Material CONTROL_NIGHT = new Material(false, .36f, 24);
        static Material forSurface(boolean light, boolean strong, float width, float height) {
            if (strong && width >= height * 4f) return light ? DOCK_DAY : DOCK_NIGHT;
            if (strong) return light ? SHEET_DAY : SHEET_NIGHT;
            return light ? CONTROL_DAY : CONTROL_NIGHT;
        }
    }
    /** API 26 fallback never constructs these graphics objects. */
    @android.annotation.TargetApi(31)
    private static final class Api31 implements Gpu {
        private final RenderNode node = new RenderNode("softlumi rectangular live blur");
        private final Matrix transform = new Matrix();
        private final float[] values = new float[9];
        private RenderNode sharedSource;
        private float left, top, effectRadius = -1f;
        private RenderEffect effect;
        @Override public void record(GlassScene scene, Matrix matrix, RectF bounds, float blur, float canonicalScale) {
            int halo = (int) Math.ceil(blur * 2f);
            int width = Math.max(1, (int) Math.ceil(bounds.width()) + halo * 2);
            int height = Math.max(1, (int) Math.ceil(bounds.height()) + halo * 2);
            left = bounds.left - halo; top = bounds.top - halo;
            node.setPosition(0, 0, width, height); node.setClipToBounds(true);
            transform.set(matrix); transform.postTranslate(-left, -top);
            matrix.getValues(values);
            RenderNode shared = Float.isFinite(canonicalScale) && translatedUniformScale(values)
                    ? (RenderNode) scene.sharedBlurNode(canonicalScale, blur) : null;
            sharedSource = shared;
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                if (shared != null) {
                    // Geometry still uses the complete actual matrix, including
                    // the focus pivot. Its reciprocal sampling scale cancels own
                    // focus growth so the optical blur radius stays in scene space.
                    float sampling = values[Matrix.MSCALE_X] / canonicalScale;
                    recording.translate(values[Matrix.MTRANS_X] - left - halo * sampling,
                            values[Matrix.MTRANS_Y] - top - halo * sampling);
                    recording.scale(sampling, sampling);
                    recording.drawRenderNode(shared);
                } else {
                    recording.concat(transform); recording.drawRenderNode((RenderNode) scene.hardwareNode());
                }
            }
            finally { node.endRecording(); }
            if (shared != null) node.setRenderEffect(null);
            else {
                if (effect == null || effectRadius != blur) {
                    effect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP); effectRadius = blur;
                }
                node.setRenderEffect(effect);
            }
        }
        @Override public void draw(Canvas canvas, int alpha) {
            node.setAlpha(alpha / 255f); int save = canvas.save(); canvas.translate(left, top);
            canvas.drawRenderNode(node); canvas.restoreToCount(save);
        }
        @Override public boolean hasDisplayList() { return node.hasDisplayList() && (sharedSource == null || sharedSource.hasDisplayList()); }
        @Override public void release() { node.discardDisplayList(); node.setRenderEffect(null); sharedSource = null; }
    }
}
