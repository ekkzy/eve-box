package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import io.github.ekkzy.softlumi.data.WeatherRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Home A weather in local design pixels. The host owns refreshes, live glass and visibility. */
public final class CompactWeatherView extends LinearLayout {
    public static final int DESIGN_WIDTH = 396;
    private final UiTypography typography;
    private final TextView city, condition, temperature;
    private final SymbolView currentIcon;
    private final LinearLayout minis;
    private final LinearLayout[] chips = new LinearLayout[3];
    private final TextView[] chipLabels = new TextView[3];
    private final TextView[] chipValues = new TextView[3];
    private final SymbolView[] chipIcons = new SymbolView[3];
    private final List<View> glassSurfaces;
    private boolean textShadowEnabled;
    private float textShadowScale = 1f;

    public CompactWeatherView(Context context) {
        super(context);
        typography = UiTypography.get(context);
        setOrientation(VERTICAL);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setTag("weather-status");

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout place = new LinearLayout(context); place.setOrientation(VERTICAL);
        city = label("compact-weather-city"); condition = label("compact-weather-condition");
        place.addView(city, wrap());
        LinearLayout.LayoutParams conditionPlacement = wrap(); conditionPlacement.topMargin = 5;
        place.addView(condition, conditionPlacement);
        header.addView(place, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        currentIcon = new SymbolView(context, "sun-outline", 0xffffffff);
        LinearLayout.LayoutParams iconPlacement = new LinearLayout.LayoutParams(32, 32);
        iconPlacement.leftMargin = 12; iconPlacement.rightMargin = 12;
        header.addView(currentIcon, iconPlacement);
        temperature = label("compact-weather-temperature");
        header.addView(temperature, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(header, wrap());

        minis = new LinearLayout(context); minis.setOrientation(HORIZONTAL);
        minis.setGravity(Gravity.CENTER_VERTICAL);
        String[] symbols = {"sun-outline", "moon-outline", "wind"};
        for (int i = 0; i < chips.length; i++) {
            LinearLayout chip = new LinearLayout(context); chip.setOrientation(HORIZONTAL);
            chip.setGravity(Gravity.CENTER); chip.setPadding(8, 9, 8, 9);
            chip.setTag(i == 0 ? "compact-weather-day" : i == 1 ? "compact-weather-night" : "compact-weather-wind");
            chip.setFocusable(false); chip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            chipIcons[i] = new SymbolView(context, symbols[i], 0xffffffff);
            LinearLayout.LayoutParams chipIconPlacement = new LinearLayout.LayoutParams(18, 18);
            chipIconPlacement.rightMargin = 6; chip.addView(chipIcons[i], chipIconPlacement);
            chipLabels[i] = label("compact-weather-chip-label");
            chip.addView(chipLabels[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            chipValues[i] = label("compact-weather-chip-value");
            LinearLayout.LayoutParams valuePlacement = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valuePlacement.leftMargin = i < 2 ? 6 : 0; chip.addView(chipValues[i], valuePlacement);
            LinearLayout.LayoutParams chipPlacement = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, i < 2 ? 1.1f : .85f);
            chipPlacement.leftMargin = i == 0 ? 0 : 6; minis.addView(chip, chipPlacement); chips[i] = chip;
        }
        LinearLayout.LayoutParams miniPlacement = wrap(); miniPlacement.topMargin = 14;
        addView(minis, miniPlacement);
        glassSurfaces = Collections.unmodifiableList(Arrays.<View>asList(chips));
    }

    /** Stable hosts. Apply the Activity-owned live glass once; bind never replaces these Views. */
    public List<View> glassSurfaces() { return glassSurfaces; }

    /** Only the unbacked header needs contrast support; glass chips retain their own ink. */
    public void setTextShadowEnabled(boolean enabled) {
        if (textShadowEnabled == enabled) return;
        textShadowEnabled = enabled;
        applyHeaderTextShadow();
    }

    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String cityName,
            boolean russian, int textColor, int mutedColor, float textScale) {
        return bind(result, enabled, cityName, russian, textColor, mutedColor, textScale, System.currentTimeMillis());
    }

    /** nowMillis is explicit for deterministic fixtures and city-local midnight rebinding. */
    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String cityName,
            boolean russian, int textColor, int mutedColor, float textScale, long nowMillis) {
        return bind(result, enabled, cityName, russian, textColor, mutedColor, textColor, mutedColor, textScale, nowMillis);
    }

    /** Header ink follows the wallpaper; chip ink follows the host's chosen glass palette. */
    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String cityName,
            boolean russian, int textColor, int mutedColor, int chipTextColor, int chipMutedColor, float textScale) {
        return bind(result, enabled, cityName, russian, textColor, mutedColor, chipTextColor, chipMutedColor,
                textScale, System.currentTimeMillis());
    }

    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String cityName,
            boolean russian, int textColor, int mutedColor, int chipTextColor, int chipMutedColor,
            float textScale, long nowMillis) {
        float scale = Float.isFinite(textScale) && textScale > 0 ? textScale : 1f;
        String selectedCity = cityName == null ? "" : cityName.trim();
        WeatherRepository.Weather weather = result == null ? null : result.weather;
        boolean ready = enabled && !selectedCity.isEmpty() && weather != null
                && WeatherRepository.cacheAge(weather, nowMillis) != WeatherPolicy.CacheAge.EXPIRED;
        style(city, 19, textColor, false, scale); style(condition, 14, mutedColor, false, scale);
        style(temperature, 43, textColor, false, scale); temperature.setTypeface(typography.displayRegular);
        textShadowScale = scale;
        applyHeaderTextShadow();
        city.setText(selectedCity.isEmpty() ? tr(russian, "Выбери город", "Choose a city") : selectedCity);
        String state = !enabled ? tr(russian, "Погода выключена", "Weather is off")
                : selectedCity.isEmpty() ? tr(russian, "В настройках погоды", "In weather settings")
                : result == null || result.status == WeatherRepository.Status.DISABLED || result.status == WeatherRepository.Status.NEEDS_CITY
                        ? tr(russian, "Загружаем погоду…", "Loading weather…")
                : weather != null && !ready ? tr(russian, "Данные устарели", "Saved weather expired")
                : tr(russian, "Данные недоступны", "Weather unavailable");
        boolean stale = ready && (result.status == WeatherRepository.Status.STALE
                || WeatherRepository.cacheAge(weather, nowMillis) == WeatherPolicy.CacheAge.STALE);
        condition.setText(ready ? (stale ? tr(russian, "Сохранено · ", "Saved · ") : "")
                + WeatherViewText.condition(weather.weatherCode, russian) : state);
        temperature.setText(ready ? WeatherViewText.temperature(weather.temperature, weather.units,
                "fahrenheit".equals(weather.units)) : "—");
        String glyph = ready ? WeatherViewText.symbol(weather.weatherCode, weather.isDay) : null;
        // Rebinding a glyph retains the View and the host's live-glass bindings.
        if (glyph != null && !glyph.equals(currentIcon.getTag())) {
            currentIcon.setTag(glyph); currentIcon.setSymbol(glyph);
        }
        currentIcon.setColor(textColor); currentIcon.setVisibility(glyph == null ? GONE : VISIBLE);
        minis.setVisibility(ready ? VISIBLE : GONE);
        StringBuilder accessible = new StringBuilder(city.getText()).append(". ").append(condition.getText());
        if (ready) {
            accessible.append(". ").append(WeatherViewText.temperature(weather.temperature, weather.units, true));
            WeatherRepository.DayNight sample = weather.dayNight(nowMillis);
            chipLabels[0].setText(tr(russian, "Днём", "Day"));
            chipLabels[1].setText(tr(russian, "Ночью", "Night")); chipLabels[2].setText("");
            WeatherRepository.Hour[] samples = {sample.day, sample.night};
            for (int i = 0; i < 2; i++) {
                chipValues[i].setText(samples[i] == null ? "—"
                        : WeatherViewText.temperature(samples[i].temp, weather.units, false));
                accessible.append(". ").append(chipLabels[i].getText()).append(": ");
                if (samples[i] == null) accessible.append(tr(russian, "нет данных", "unavailable"));
                else accessible.append(WeatherViewText.temperature(samples[i].temp, weather.units, true))
                        .append(", ").append(i == 0 ? sample.dayDate : sample.nightDate).append(" ")
                        .append(WeatherViewText.time(samples[i].timeMillis, WeatherViewText.zone(weather)));
            }
            chipValues[2].setText(Double.isFinite(weather.windSpeed)
                    ? WeatherViewText.decimal(weather.windSpeed / 3.6, russian) + tr(russian, " м/с", " m/s") : "—");
            accessible.append(". ").append(tr(russian, "Ветер ", "Wind ")).append(chipValues[2].getText());
            for (int i = 0; i < chips.length; i++) {
                style(chipLabels[i], 14, chipMutedColor, false, scale);
                style(chipValues[i], i < 2 ? 14 : 13, chipTextColor, true, scale);
                chipIcons[i].setColor(chipMutedColor);
            }
        }
        if (enabled && !selectedCity.isEmpty() && weather != null)
            accessible.append(". ").append(WeatherViewText.attribution(result, russian, nowMillis));
        setContentDescription(accessible);
        measure(MeasureSpec.makeMeasureSpec(getLayoutParams() != null && getLayoutParams().width > 0
                ? getLayoutParams().width : DESIGN_WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        requestLayout(); return getMeasuredHeight();
    }

    private TextView label(String tag) {
        TextView label = new TextView(getContext()); label.setTag(tag); label.setIncludeFontPadding(false);
        label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
        label.setFontFeatureSettings("'tnum'"); label.setFocusable(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); return label;
    }
    private void style(TextView label, float size, int color, boolean medium, float scale) {
        label.setTextColor(color); label.setTypeface(medium ? typography.medium : typography.regular);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale);
    }
    private void applyHeaderTextShadow() {
        AmbientTextShadow.apply(city, textShadowEnabled, textShadowScale);
        AmbientTextShadow.apply(condition, textShadowEnabled, textShadowScale);
        AmbientTextShadow.apply(temperature, textShadowEnabled, textShadowScale);
    }
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static String tr(boolean russian, String ru, String en) { return russian ? ru : en; }
}
