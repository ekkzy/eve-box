package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A content-tinted background authored for softlumi. The base is always available;
 * real provider artwork is a separate, translucent GPU-blurred layer.
 * Image ownership remains with UiImageLoader. No poster pixels are copied here.
 */
public final class ContentBackdrop extends FrameLayout {
    private final DiffuseArt image;

    public ContentBackdrop(Context context, boolean light, boolean subdued) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                light ? new int[]{0xffedf0f4, 0xffd4dbe3} : new int[]{0xff202733, 0xff10141c}));
        image = new DiffuseArt(context, (light ? .24f : .30f) * (subdued ? .55f : 1f));
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public ImageView posterView() { return image; }

    private static final class DiffuseArt extends ImageView {
        private final float opacity;
        private boolean ready;

        DiffuseArt(Context context, float opacity) {
            super(context);
            this.opacity = opacity;
            setScaleType(ScaleType.CENTER_CROP);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setAlpha(0f);
        }

        private void updateEffect() {
            ready = Build.VERSION.SDK_INT >= 31 && isAttachedToWindow()
                    && isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0;
            if (Build.VERSION.SDK_INT >= 31) {
                if (ready) Effects.blur(this, Math.max(24f, Math.min(160f, getWidth() * .045f)));
                else Effects.clear(this);
            }
            setAlpha(ready ? opacity : 0f);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateEffect(); }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH); updateEffect();
        }
        @Override protected void onDetachedFromWindow() {
            ready = false;
            if (Build.VERSION.SDK_INT >= 31) Effects.clear(this);
            setAlpha(0f); super.onDetachedFromWindow();
        }
        @Override public void draw(Canvas canvas) {
            // Software Canvas ignores RenderEffect. Keep its honest base fallback.
            if (ready && canvas.isHardwareAccelerated()) super.draw(canvas);
        }
    }

    @android.annotation.TargetApi(31)
    private static final class Effects {
        static void blur(View view, float radius) {
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        static void clear(View view) { view.setRenderEffect(null); }
    }
}
