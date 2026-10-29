package io.github.ekkzy.evebox.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

/** Immutable Inter text roles and the separate Outfit clock face; provenance lives in fonts/. */
public final class UiTypography {
    public final Typeface light, displayRegular, displayMedium, displaySemibold;
    public final Typeface regular, medium, semibold;
    public final Typeface clockRegular;
    private static UiTypography shared;
    private enum Role {
        TEXT(400, false), MEDIUM(500, false), SEMIBOLD(600, false),
        LIGHT(300, true), DISPLAY(400, true), DISPLAY_MEDIUM(500, true), DISPLAY_SEMIBOLD(600, true);
        final int weight;
        final boolean display;
        Role(int weight, boolean display) { this.weight = weight; this.display = display; }
    }

    public static synchronized UiTypography get(Context context) {
        if (shared == null) shared = new UiTypography(context.getApplicationContext());
        return shared;
    }

    private UiTypography(Context context) {
        regular = load(context, Role.TEXT);
        medium = load(context, Role.MEDIUM);
        semibold = load(context, Role.SEMIBOLD);
        light = load(context, Role.LIGHT);
        displayRegular = load(context, Role.DISPLAY);
        displayMedium = load(context, Role.DISPLAY_MEDIUM);
        displaySemibold = load(context, Role.DISPLAY_SEMIBOLD);
        clockRegular = loadClock(context, displayRegular);
    }

    private static Typeface loadClock(Context context, Typeface fallback) {
        try {
            // Outfit's default variable instance is Thin (100); the clock uses Regular (400).
            Typeface face = new Typeface.Builder(context.getAssets(), "fonts/OutfitVariable.ttf")
                    .setFontVariationSettings("'wght' 400").setWeight(400).setItalic(false).build();
            return face == null ? fallback : face;
        } catch (RuntimeException unavailableAsset) {
            return fallback;
        }
    }

    private static Typeface load(Context context, Role role) {
        Typeface face;
        try {
            // The author's fvar table declares opsz14..32 and wght100..900.
            String axes = "'wght' " + role.weight + ", 'opsz' " + (role.display ? 32 : 14);
            face = new Typeface.Builder(context.getAssets(), "fonts/InterVariable.ttf")
                    .setFontVariationSettings(axes).setWeight(role.weight).setItalic(false).build();
            if (face == null) throw new IllegalStateException("Font builder returned no face");
        } catch (RuntimeException unavailableAsset) {
            face = Build.VERSION.SDK_INT >= 28 ? Api28.weight(role.weight)
                    : Typeface.create("sans-serif", role.weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
        return face;
    }

    @android.annotation.TargetApi(28)
    private static final class Api28 {
        static Typeface weight(int value) { return Typeface.create(Typeface.DEFAULT, value, false); }
    }
}
