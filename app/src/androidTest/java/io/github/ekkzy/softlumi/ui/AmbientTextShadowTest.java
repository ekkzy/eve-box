package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.ekkzy.softlumi.data.LauncherPrefs;
import io.github.ekkzy.softlumi.data.WeatherRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Component regression: palette/weather rebinding keeps the contrast aid and stable layout. */
@RunWith(AndroidJUnit4.class)
public final class AmbientTextShadowTest {
    private static final long NOW = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli();

    @Test public void weatherHeaderShadowSurvivesRebindingAndClearsWithoutAffectingChips() {
        WeatherRepository.WeatherResult result = weather();
        assertEquals(WeatherRepository.Status.FRESH, result.status);
        onMain(() -> {
            CompactWeatherView view = new CompactWeatherView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            view.setLayoutParams(new ViewGroup.LayoutParams(CompactWeatherView.DESIGN_WIDTH,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int height = bind(view, result, 0xff202126, 0xff55565e, 1f);
            String accessible = view.getContentDescription().toString();
            TextView city = view.findViewWithTag("compact-weather-city");
            TextView condition = view.findViewWithTag("compact-weather-condition");
            TextView temperature = view.findViewWithTag("compact-weather-temperature");
            TextView[] header = {city, condition, temperature};
            ViewGroup.LayoutParams cityPlacement = city.getLayoutParams();
            assertHeaderShadow(header, false);

            view.setTextShadowEnabled(true);
            assertHeaderShadow(header, true);
            assertEquals("Enabling contrast must retain the existing measured header", height,
                    bind(view, result, 0xff202126, 0xff55565e, 1f));
            assertSame(city, view.findViewWithTag("compact-weather-city"));
            assertSame(condition, view.findViewWithTag("compact-weather-condition"));
            assertSame(temperature, view.findViewWithTag("compact-weather-temperature"));
            assertSame(cityPlacement, city.getLayoutParams());
            assertEquals(accessible, view.getContentDescription().toString());
            assertFalse(view.isFocusable());
            assertHeaderShadow(header, true);

            float normalRadius = temperature.getShadowRadius();
            bind(view, result, 0xfffafafa, 0xffdedee2, 1.2f);
            assertHeaderShadow(header, true);
            assertTrue("The contrast aid follows the component's text scale",
                    temperature.getShadowRadius() > normalRadius);
            assertUnshadowedChips(view);

            view.setTextShadowEnabled(false);
            assertHeaderShadow(header, false);
            bind(view, result, 0xff202126, 0xff55565e, 1f);
            assertHeaderShadow(header, false);
            view.setTextShadowEnabled(true);
            assertHeaderShadow(header, true);
            assertEquals(accessible, view.getContentDescription().toString());
            assertUnshadowedChips(view);
        });
    }

    @Test public void renderedClockShadowDarkensItsSurroundingsWithoutAWhiteHaloOrRebindWork() {
        onMain(() -> {
            CountingTextView text = new CountingTextView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            text.setText("12:34");
            text.setTypeface(UiTypography.get(text.getContext()).clockRegular);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 72);
            text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            text.setPadding(20, 8, 20, 8);
            text.setSingleLine(true);
            text.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
            text.layout(0, 0, 360, 120);
            int backdrop = Color.rgb(194, 194, 194);
            for (int ink : new int[]{0xff181818, 0xff424242, 0xfff5f5f5}) {
                text.setTextColor(ink);
                AmbientTextShadow.apply(text, false, 1f);
                Bitmap plain = render(text, backdrop);
                Bitmap shadowed = null, cleared = null;
                try {
                    AmbientTextShadow.apply(text, true, 1f);
                    int changes = text.shadowChanges;
                    shadowed = render(text, backdrop);
                    int darkened = 0, brightened = 0;
                    for (int y = 0; y < plain.getHeight(); y++) for (int x = 0; x < plain.getWidth(); x++) {
                        // Pixels outside the unshadowed glyph isolate the drop shadow.
                        if (plain.getPixel(x, y) != backdrop) continue;
                        int pixel = shadowed.getPixel(x, y);
                        if (Color.red(pixel) > Color.red(backdrop) + 1
                                || Color.green(pixel) > Color.green(backdrop) + 1
                                || Color.blue(pixel) > Color.blue(backdrop) + 1) brightened++;
                        if (Color.red(pixel) < Color.red(backdrop) - 1
                                && Color.green(pixel) < Color.green(backdrop) - 1
                                && Color.blue(pixel) < Color.blue(backdrop) - 1) darkened++;
                    }
                    assertEquals("A light-surface drop shadow must never add a white halo", 0, brightened);
                    assertTrue("A real soft dark shadow must remain outside the glyph, ink="
                            + Integer.toHexString(ink) + ", pixels=" + darkened, darkened > 20);
                    for (int rebind = 0; rebind < 8; rebind++) AmbientTextShadow.apply(text, true, 1f);
                    assertEquals("Unchanged time/weather binding must not reset text shadow", changes, text.shadowChanges);
                    assertEquals("The helper must not allocate a View layer", View.LAYER_TYPE_NONE, text.getLayerType());
                    AmbientTextShadow.apply(text, false, 1f);
                    cleared = render(text, backdrop);
                    assertTrue("Disabling restores the exact unshadowed text", plain.sameAs(cleared));
                } finally {
                    plain.recycle();
                    if (shadowed != null) shadowed.recycle();
                    if (cleared != null) cleared.recycle();
                }
            }
        });
    }

    private static Bitmap render(TextView text, int backdrop) {
        Bitmap image = Bitmap.createBitmap(text.getWidth(), text.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(backdrop);
        text.draw(canvas);
        return image;
    }

    private static final class CountingTextView extends TextView {
        int shadowChanges;
        CountingTextView(Context context) { super(context); }
        @Override public void setShadowLayer(float radius, float dx, float dy, int color) {
            shadowChanges++;
            super.setShadowLayer(radius, dx, dy, color);
        }
    }

    private static int bind(CompactWeatherView view, WeatherRepository.WeatherResult result,
            int ink, int muted, float scale) {
        return view.bind(result, true, "Москва", true, ink, muted, 0xfffafafa, 0xffdedee2, scale, NOW);
    }

    private static void assertHeaderShadow(TextView[] header, boolean enabled) {
        for (TextView text : header) {
            assertNotEquals("Static text shadow must not force a software layer",
                    View.LAYER_TYPE_SOFTWARE, text.getLayerType());
            if (enabled) {
                assertTrue(text.getShadowRadius() > 0f && text.getShadowRadius() <= 3f);
                assertTrue(Color.alpha(text.getShadowColor()) > 0 && Color.alpha(text.getShadowColor()) < 255);
                int color = text.getShadowColor();
                assertEquals("The shadow must be neutral", Color.red(color), Color.green(color));
                assertEquals("The shadow must be neutral", Color.green(color), Color.blue(color));
                assertTrue("Dark header ink needs a dark drop shadow, never white glow", Color.luminance(color) < .05f);
            } else {
                assertEquals(0f, text.getShadowRadius(), 0f);
                assertEquals(Color.TRANSPARENT, text.getShadowColor());
            }
        }
    }

    private static void assertUnshadowedChips(CompactWeatherView view) {
        for (View chip : view.glassSurfaces()) {
            assertEquals(View.VISIBLE, chip.getVisibility());
            for (String tag : new String[] {"compact-weather-chip-label", "compact-weather-chip-value"}) {
                TextView text = chip.findViewWithTag(tag);
                assertEquals("Glass-backed chip text needs no additional shadow", 0f, text.getShadowRadius(), 0f);
            }
        }
    }

    private static WeatherRepository.WeatherResult weather() {
        WeatherRepository.Weather weather = new WeatherRepository.Weather(8, 0, true, 10.8, NOW, NOW, "celsius");
        WeatherRepository.Decoder decoder = new WeatherRepository.Decoder() {
            @Override public List<WeatherRepository.City> cities(String body) { return Collections.emptyList(); }
            @Override public WeatherRepository.Weather current(String body, String units, long fetchedAt) { return weather; }
        };
        WeatherRepository.Cache cache = new WeatherRepository.Cache() {
            @Override public WeatherRepository.Weather read(String key) { return null; }
            @Override public void write(String key, WeatherRepository.Weather value) { }
        };
        Map<String, Object> settings = new HashMap<>();
        settings.put("weather_enabled", true); settings.put("city_name", "Москва");
        settings.put("city_latitude", 55.752040); settings.put("city_longitude", 37.617810);
        settings.put("units", "celsius");
        return new WeatherRepository(url -> "{}", cache, decoder, () -> NOW,
                delay -> fail("Controlled weather never retries")).loadCurrent(LauncherPrefs.decode(settings), true);
    }

    private static void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() != null) throw new AssertionError("Native ambient text contrast", failure.get());
    }
}
