package io.github.ekkzy.softlumi.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Borderless optical foreground above artwork or controls. UiMotion supplies its existing focus
 * spring; this drawable owns no animator, callback, bitmap, or frame timer.
 */
public final class ArtworkSheenDrawable extends Drawable {
    private enum Profile { APP, POSTER, CONTROL }
    private final float cornerRadius;
    private final boolean light, poster, control;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private final Path cornerGlint = new Path();
    private float progress;
    private int alpha = 255;
    private boolean focused;
    private float artworkHeight;

    /** Limit a poster's optics to its artwork, leaving an external text footer untouched. */
    public void setArtworkHeight(float height) {
        if (!Float.isFinite(height) || height < 0) throw new IllegalArgumentException("Nonnegative height required");
        artworkHeight = height; onBoundsChange(getBounds()); invalidateSelf();
    }

    /**
     * All geometry is in the host's local pixels. The legacy borderless argument
     * is retained for caller compatibility; every profile is now borderless.
     */
    public ArtworkSheenDrawable(float radius, boolean light, boolean poster, boolean borderless) {
        this(radius, light, poster ? Profile.POSTER : Profile.APP);
    }

    /** A stronger graded face reflection for ordinary buttons and settings rows. */
    public static ArtworkSheenDrawable control(float radius, boolean light) {
        return new ArtworkSheenDrawable(radius, light, Profile.CONTROL);
    }

    private ArtworkSheenDrawable(float radius, boolean light, Profile profile) {
        if (!Float.isFinite(radius) || radius < 0) throw new IllegalArgumentException("Nonnegative radius required");
        cornerRadius = radius; this.light = light;
        poster = profile == Profile.POSTER; control = profile == Profile.CONTROL;
    }

    /** Target notification retained for UiMotion; presentation follows progress only. */
    public void setFocused(boolean value) {
        if (focused == value) return;
        focused = value;
    }

    /** Clamped presentation from UiMotion, including its current reversal velocity. */
    public void setFocusProgress(float value) {
        float next = Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
        if (progress == next) return;
        // View transforms live in its RenderNode; invalidating the foreground
        // also re-records the artwork beneath it. Keep full spring precision,
        // but only dirty that display list when the drawn 8-bit alpha changes.
        int previousAlpha = Math.round(alpha * progress);
        progress = next;
        if (previousAlpha != Math.round(alpha * progress)) invalidateSelf();
    }

    public float getFocusProgress() { return progress; }

    @Override protected void onBoundsChange(Rect bounds) {
        face.set(bounds);
        if (artworkHeight > 0) face.bottom = Math.min(face.bottom, face.top + artworkHeight);
        cornerGlint.reset();
        if (face.isEmpty()) { sheen.setShader(null); reflection.setShader(null); glint.setShader(null); return; }
        // The original artwork reflection remains a face gradient, never an
        // outline. Ordinary controls use a broader and stronger graded reflection.
        sheen.setShader(control
                ? new LinearGradient(face.left, face.top, face.left, face.bottom,
                        new int[] {light ? 0x48FFFFFF : 0x58FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[] {0, .38f, .72f}, Shader.TileMode.CLAMP)
                : poster
                ? new LinearGradient(face.left, face.top, face.centerX(), face.bottom,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                : new LinearGradient(face.left, face.top, face.left, face.centerY(),
                        0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // A soft lower reflection gives white artwork a visible focus change,
        // while the white upper reflection remains visible over black artwork.
        // Neither is a flat tint; the center stays largely untouched.
        reflection.setShader(new LinearGradient(face.left, face.top, face.left, face.bottom,
                new int[] {0x00000000, 0x00000000, control ? (light ? 0x30000000 : 0x28000000) : 0x24000000},
                new float[] {0, .48f, 1}, Shader.TileMode.CLAMP));
        // A short, tapered piece of top-left light: no full-perimeter path and
        // no stroked edge. It dies away before the middle of either side.
        float r = radius(), thickness = Math.min(1.25f, Math.min(face.width(), face.height()) / 8f);
        float endX = face.left + Math.max(r, face.width() * .48f);
        float endY = face.top + Math.min(face.height() * .42f, r + face.height() * .08f);
        cornerGlint.moveTo(face.left, endY);
        cornerGlint.lineTo(face.left, face.top + r);
        if (r > 0) cornerGlint.arcTo(face.left, face.top, face.left + r * 2, face.top + r * 2, 180, 90, false);
        else cornerGlint.lineTo(face.left, face.top);
        cornerGlint.lineTo(endX, face.top);
        cornerGlint.lineTo(Math.max(face.left + r, endX - 10), face.top + thickness);
        cornerGlint.lineTo(face.left + r, face.top + thickness);
        if (r > thickness) cornerGlint.arcTo(face.left + thickness, face.top + thickness,
                face.left + r * 2 - thickness, face.top + r * 2 - thickness, 270, -90, false);
        else cornerGlint.lineTo(face.left + thickness, face.top + thickness);
        cornerGlint.close();
        glint.setShader(new LinearGradient(face.left, face.top, endX, endY,
                control ? 0x38FFFFFF : 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (face.isEmpty() || alpha == 0) return;
        int opticalAlpha = Math.round(alpha * progress);
        if (opticalAlpha == 0) return;
        sheen.setAlpha(opticalAlpha);
        reflection.setAlpha(opticalAlpha); glint.setAlpha(opticalAlpha);
        canvas.drawRoundRect(face, radius(), radius(), sheen);
        canvas.drawRoundRect(face, radius(), radius(), reflection);
        canvas.drawPath(cornerGlint, glint);
    }

    private float radius() { return Math.min(cornerRadius, Math.min(face.width(), face.height()) / 2f); }
    @Override public void setAlpha(int value) {
        int next = Math.max(0, Math.min(255, value));
        if (alpha != next) { alpha = next; invalidateSelf(); }
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        sheen.setColorFilter(filter); reflection.setColorFilter(filter); glint.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
