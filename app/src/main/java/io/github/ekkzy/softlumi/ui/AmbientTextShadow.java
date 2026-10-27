package io.github.ekkzy.softlumi.ui;

import android.graphics.Color;
import android.widget.TextView;

/** Small static text contrast aid in the Home's local design pixels. */
public final class AmbientTextShadow {
    private AmbientTextShadow() { }

    public static void apply(TextView text, boolean enabled) {
        apply(text, enabled, text.getResources().getConfiguration().fontScale);
    }

    /** Explicit scale matches components whose host already resolves the text scale. */
    public static void apply(TextView text, boolean enabled, float textScale) {
        float scale = Float.isFinite(textScale) && textScale > 0f ? textScale : 1f;
        scale = Math.max(.75f, Math.min(1.35f, scale));
        float radius = enabled ? 2.2f * scale : 0f;
        float dy = enabled ? scale : 0f;
        // A drop shadow stays neutral and dark with either ink palette. Inverting
        // it for dark text creates a white halo on the light wallpaper.
        int color = enabled ? 0x66000000 : Color.TRANSPARENT;
        // Rebinding unchanged weather or time must not invalidate another text display list.
        if (text.getShadowRadius() != radius || text.getShadowDx() != 0f
                || text.getShadowDy() != dy || text.getShadowColor() != color) {
            text.setShadowLayer(radius, 0f, dy, color);
        }
    }
}
