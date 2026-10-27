package io.github.ekkzy.softlumi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.ekkzy.softlumi.core.WeatherPolicy;
import io.github.ekkzy.softlumi.data.WeatherRepository;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transparent four-point, two-hour timeline and three-day forecast in local design pixels.
 * The screen owns refreshes and the surrounding focusable settings action. Rebind
 * when its weather snapshot, palette, font scale or city-local date changes.
 */
public final class ThreeDayForecastView extends LinearLayout {
    public static final int DESIGN_WIDTH = 1132;
    private static final int COLUMN_GAP = 28;
    private static final String[] DETAIL_TAGS = {"forecast-precipitation", "forecast-wind"};
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private final UiTypography typography;
    private final StringBuilder accessible = new StringBuilder();
    private boolean russian;
    private int textColor, mutedColor;
    private float textScale = 1f;

    public ThreeDayForecastView(Context context) {
        super(context);
        typography = UiTypography.get(context);
        setOrientation(VERTICAL);
        setPadding(0, 4, 0, 4);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setTag("three-day-forecast");
    }

    /**
     * A null result is loading. Only supplied values are presented; source,
     * timezone and solar details remain in accessibility. Returns the height at
     * DESIGN_WIDTH; ordinary WRAP_CONTENT measurement also remains supported.
     * textScale is the host's text-pixel/design-pixel ratio, including font scale.
     */
    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String city,
            boolean russian, int textColor, int mutedColor, float textScale) {
        return bind(result, enabled, city, russian, textColor, mutedColor, textScale, System.currentTimeMillis());
    }

    /** Explicit time keeps fixtures deterministic and preserves city-local midnight semantics. */
    public int bind(WeatherRepository.WeatherResult result, boolean enabled, String city,
            boolean russian, int textColor, int mutedColor, float textScale, long now) {
        this.russian = russian;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        this.textScale = Float.isFinite(textScale) && textScale > 0 ? textScale : 1f;
        removeAllViews();
        accessible.setLength(0);

        String cityName = city == null ? "" : city.trim();
        boolean utcDays = result != null && result.weather != null
                && result.weather.provider == WeatherRepository.Provider.MET_NORWAY
                && result.weather.forecastTimeBasis == WeatherRepository.ForecastTimeBasis.UTC;
        String title = cityName.isEmpty() ? tr("Погода", "Weather") : tr("Погода · ", "Weather · ") + cityName;
        LinearLayout heading = new LinearLayout(getContext()); heading.setOrientation(HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView header = text(title, 22, textColor, true);
        header.setTag("forecast-title");
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (enabled && !cityName.isEmpty() && result != null && result.weather != null) {
            WeatherRepository.Weather current = result.weather;
            boolean saved = result.status == WeatherRepository.Status.STALE
                    || WeatherRepository.cacheAge(current, now) != WeatherPolicy.CacheAge.FRESH;
            String fetched = current.fetchedAtMillis <= 0 ? "—"
                    : current.cityTimezoneKnown ? WeatherViewText.time(current.fetchedAtMillis, WeatherViewText.zone(current))
                    : relativeAge(current.fetchedAtMillis, now);
            TextView updated = text((saved ? tr("Сохранено ", "Saved ") : "") + fetched, 15, mutedColor, false);
            updated.setTag("forecast-updated"); updated.setSingleLine(true);
            LinearLayout.LayoutParams updatedPlacement = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            updatedPlacement.leftMargin = 20; heading.addView(updated, updatedPlacement);
        }
        addRow(this, heading, 0);

        if (!enabled) {
            message(tr("Погода выключена. Включи её в настройках погоды.",
                    "Weather is off. Enable it in weather settings."));
            return finish();
        }
        if (cityName.isEmpty()) {
            message(tr("Выбери город в настройках погоды.", "Choose a city in weather settings."));
            return finish();
        }
        if (result == null || result.status == WeatherRepository.Status.DISABLED
                || result.status == WeatherRepository.Status.NEEDS_CITY) {
            message(tr("Загружаем прогноз…", "Loading forecast…"));
            return finish();
        }

        WeatherRepository.Weather weather = result.weather;
        if (weather == null) {
            message(unavailable(result));
            return finish();
        }
        WeatherPolicy.CacheAge age = WeatherRepository.cacheAge(weather, now);
        ZoneId zone = weather.forecastTimeBasis == WeatherRepository.ForecastTimeBasis.CITY && weather.cityTimezoneKnown
                ? zone(weather.forecastTimezone) : utcDays && "UTC".equals(weather.forecastTimezone) ? ZoneOffset.UTC : null;
        List<WeatherRepository.Day> days = age == WeatherPolicy.CacheAge.EXPIRED
                ? Collections.emptyList() : weather.forecastDays(now);
        List<WeatherRepository.ForecastSlot> slots = weather.upcomingHourSlots(now);
        if (!slots.isEmpty()) hours(slots, weather, now);
        else if (age != WeatherPolicy.CacheAge.EXPIRED && (!days.isEmpty() || !weather.cityTimezoneKnown)) {
            TextView missingHours = text(tr("Почасовой прогноз пока недоступен", "Hourly forecast is not available yet"),
                    16, mutedColor, false);
            missingHours.setTag("forecast-hours-message"); addRow(this, missingHours, 12);
        }
        if (days.isEmpty() || zone == null) {
            message(age == WeatherPolicy.CacheAge.EXPIRED
                    ? tr("Сохранённый прогноз устарел. Нужны новые данные.",
                            "The saved forecast has expired. New data is needed.")
                    : tr("Прогноз по дням пока недоступен.", "Daily forecast is not available yet."));
            footer(result, weather, age, zone, false);
            return finish();
        }

        boolean hasPartialDay = false, hasAggregates = false;
        boolean[] details = new boolean[DETAIL_TAGS.length];
        for (WeatherRepository.Day day : days) {
            hasPartialDay |= day.partialDay;
            hasAggregates |= day.aggregated;
            details[0] |= Double.isFinite(day.precipitationMm) || Double.isFinite(day.precipitationProbabilityMax);
            details[1] |= Double.isFinite(day.windSpeedMax) || Double.isFinite(day.windGustsMax);
        }
        LinearLayout columns = new LinearLayout(getContext());
        columns.setOrientation(HORIZONTAL);
        columns.setBaselineAligned(false);
        columns.setFocusable(false);
        List<LinearLayout> dayColumns = new ArrayList<>(WeatherRepository.MAX_FORECAST_DAYS);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (int offset = 0; offset < WeatherRepository.MAX_FORECAST_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            WeatherRepository.Day found = null;
            for (WeatherRepository.Day day : days) if (day.date.equals(date.toString())) {
                found = day;
                break;
            }
            LinearLayout column = dayColumn(date, offset, found, weather.units, zone, hasPartialDay, details, utcDays);
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            placement.leftMargin = offset == 0 ? 0 : COLUMN_GAP;
            columns.addView(column, placement); dayColumns.add(column);
        }
        alignRows(dayColumns);
        if (!slots.isEmpty()) {
            View divider = new View(getContext()); divider.setTag("forecast-daily-divider");
            divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            divider.setBackgroundColor((mutedColor & 0x00ffffff) | 0x22000000);
            LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            separator.topMargin = 12; addView(divider, separator);
        }
        addRow(this, columns, slots.isEmpty() ? 14 : 16);
        footer(result, weather, age, zone, hasAggregates);
        return finish();
    }

    private void hours(List<WeatherRepository.ForecastSlot> slots, WeatherRepository.Weather weather, long now) {
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(HORIZONTAL);
        section.setGravity(Gravity.TOP); section.setTag("forecast-hourly-timeline");
        TextView title = text(tr("Ближайшие часы", "Next hours"), 15, mutedColor, false);
        title.setTag("forecast-hours-title"); title.setMaxLines(2);
        LinearLayout.LayoutParams titlePlacement = new LinearLayout.LayoutParams(146, ViewGroup.LayoutParams.WRAP_CONTENT);
        titlePlacement.rightMargin = 16; titlePlacement.topMargin = Math.round(6 * textScale);
        section.addView(title, titlePlacement);
        LinearLayout timeline = new LinearLayout(getContext()); timeline.setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setBaselineAligned(false); row.setTag("forecast-hours");
        ZoneId displayZone = WeatherViewText.zone(weather);
        LocalDate today = Instant.ofEpochMilli(now).atZone(displayZone).toLocalDate();
        boolean showDayNotes = false;
        for (WeatherRepository.ForecastSlot slot : slots)
            showDayNotes |= Instant.ofEpochMilli(slot.timeMillis).atZone(displayZone).toLocalDate().isAfter(today);
        for (WeatherRepository.ForecastSlot slot : slots) {
            WeatherRepository.Hour hour = slot.sample;
            LinearLayout cell = new LinearLayout(getContext()); cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER); cell.setTag("forecast-hour-cell-" + slot.timeMillis);
            LinearLayout values = new LinearLayout(getContext()); values.setOrientation(HORIZONTAL);
            values.setGravity(Gravity.CENTER); values.setTag("forecast-hour-values");
            TextView time = text(time(slot.timeMillis, displayZone), 18, mutedColor, false);
            time.setSingleLine(true); time.setTag("forecast-hour-time");
            values.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            String glyph = hour == null ? null : WeatherViewText.hourSymbol(weather, hour);
            View icon = glyph == null ? new View(getContext()) : new SymbolView(getContext(), glyph, textColor);
            icon.setTag(glyph == null ? "forecast-hour-icon-space" : "forecast-hour-icon");
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(25, 25);
            iconSize.leftMargin = 10; iconSize.rightMargin = 10; values.addView(icon, iconSize);
            TextView value = text(hour == null ? "—" : WeatherViewText.temperature(hour.temp, weather.units, true),
                    24, textColor, true);
            value.setTag("forecast-hour-temperature"); value.setSingleLine(true); value.setFontFeatureSettings("'tnum'");
            values.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.addView(values, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (showDayNotes) {
                LocalDate date = Instant.ofEpochMilli(slot.timeMillis).atZone(displayZone).toLocalDate();
                String note = date.equals(today.plusDays(1)) ? tr("завтра", "Tomorrow")
                        : date.isAfter(today) ? date.format(DateTimeFormatter.ofPattern(russian ? "d MMM" : "MMM d", locale())) : " ";
                TextView dayNote = text(note, 11, mutedColor, false); dayNote.setSingleLine(true);
                dayNote.setGravity(Gravity.CENTER); dayNote.setTag("forecast-hour-day-note");
                LinearLayout.LayoutParams notePlacement = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT); notePlacement.topMargin = 2;
                cell.addView(dayNote, notePlacement);
            }
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(cell, placement);
            accessible.append(". ").append(Instant.ofEpochMilli(slot.timeMillis).atZone(displayZone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)))
                    .append(", ").append(hour == null ? tr("Нет данных", "No data") : condition(hour.weatherCode));
        }
        timeline.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TimelineAxis axis = new TimelineAxis(getContext(), mutedColor, slots.size());
        axis.setTag("forecast-hours-axis");
        LinearLayout.LayoutParams axisPlacement = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12);
        axisPlacement.topMargin = 5; timeline.addView(axis, axisPlacement);
        section.addView(timeline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow(this, section, 12);
    }

    /** One anti-aliased line and four dots; no bitmaps, effects, listeners or animation state. */
    private static final class TimelineAxis extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int points;
        TimelineAxis(Context context, int color, int points) {
            super(context); this.points = points;
            line.setColor(color); line.setAlpha(64); line.setStrokeWidth(.75f);
            dot.setColor(color); dot.setAlpha(180);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (points == 0) return;
            float step = getWidth() / (float) points, y = getHeight() / 2f;
            canvas.drawLine(step / 2f, y, getWidth() - step / 2f, y, line);
            for (int i = 0; i < points; i++) canvas.drawCircle(step * (i + .5f), y, 1.8f, dot);
        }
    }

    private LinearLayout dayColumn(LocalDate date, int offset, WeatherRepository.Day day,
            String units, ZoneId zone, boolean reservePartialNote, boolean[] details, boolean utcDays) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setFocusable(false);
        column.setTag("forecast-day-" + date);
        String weekday = offset == 0 ? tr("Сегодня", "Today") : offset == 1 ? tr("Завтра", "Tomorrow")
                : capitalize(date.getDayOfWeek().getDisplayName(TextStyle.FULL, locale()));
        String dateLabel = (utcDays ? "" : weekday + ", ") + date.format(DateTimeFormatter.ofPattern(
                russian ? "d MMM" : "MMM d", locale()));
        TextView heading = text(dateLabel, 20, textColor, true);
        heading.setTag("forecast-date");
        heading.setSingleLine(true);
        addRow(column, heading, 0);
        if (reservePartialNote) {
            TextView note = text(day != null && day.partialDay ? tr("Неполный день", "Partial day") : " ",
                    13, mutedColor, false);
            note.setSingleLine(true);
            addRow(column, note, 3);
        }
        if (day == null) {
            addRow(column, text(tr("Нет данных на этот день", "No data for this day"), 18, mutedColor, false), 14);
            return column;
        }

        LinearLayout condition = new LinearLayout(getContext());
        condition.setOrientation(HORIZONTAL);
        condition.setGravity(Gravity.CENTER_VERTICAL);
        condition.setTag("forecast-condition-row");
        condition.setMinimumHeight(Math.round(36 * textScale));
        String glyph = symbol(day.weatherCode);
        if (glyph != null) {
            SymbolView icon = new SymbolView(getContext(), glyph, textColor);
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(28, 28);
            iconSize.rightMargin = 10;
            condition.addView(icon, iconSize);
        }
        TextView conditionText = text(condition(day.weatherCode), 17, textColor, false);
        conditionText.setTag("forecast-condition");
        conditionText.setMaxLines(2);
        conditionText.setGravity(Gravity.CENTER_VERTICAL);
        condition.addView(conditionText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow(column, condition, 8);

        TextView temperatures = text(temperatureRange(day, units), 40, textColor, true);
        temperatures.setTag("forecast-temperature");
        temperatures.setMaxLines(2);
        temperatures.setFontFeatureSettings("'tnum'");
        addRow(column, temperatures, 4);

        String rain = "";
        if (Double.isFinite(day.precipitationMm)) rain = tr("Осадки ", "Rain / snow ")
                + decimal(day.precipitationMm) + tr(" мм", " mm");
        if (Double.isFinite(day.precipitationProbabilityMax)) {
            String chance = tr("до ", "up to ") + Math.round(day.precipitationProbabilityMax) + "%";
            rain = rain.isEmpty() ? tr("Вероятность осадков ", "Precipitation chance ") + chance : rain + " · " + chance;
        }
        detail(column, rain, DETAIL_TAGS[0], details[0]);

        String wind = "";
        if (Double.isFinite(day.windSpeedMax)) wind = tr("Ветер до ", "Wind up to ") + decimal(day.windSpeedMax / 3.6);
        if (Double.isFinite(day.windGustsMax)) wind += (wind.isEmpty() ? tr("Порывы ", "Gusts ")
                : tr(" · порывы ", " · gusts ")) + decimal(day.windGustsMax / 3.6);
        if (!wind.isEmpty()) wind += tr(" м/с", " m/s");
        detail(column, wind, DETAIL_TAGS[1], details[1]);

        String sun = "";
        if (day.sunriseMillis > 0) sun = tr("Восход ", "Sunrise ") + time(day.sunriseMillis, zone);
        if (day.sunsetMillis > 0) sun += (sun.isEmpty() ? "" : " · ")
                + tr("Закат ", "Sunset ") + time(day.sunsetMillis, zone);
        String uv = Double.isFinite(day.uvIndexMax) ? tr("УФ-индекс до ", "UV index up to ") + decimal(day.uvIndexMax) : "";
        if (!sun.isEmpty()) accessible.append(". ").append(sun);
        if (!uv.isEmpty()) accessible.append(". ").append(uv);
        if (rain.isEmpty()) accessible.append(". ").append(tr("Осадки не переданы", "Precipitation unavailable"));
        if (wind.isEmpty()) accessible.append(". ").append(tr("Ветер не передан", "Wind unavailable"));
        return column;
    }

    private CharSequence temperatureRange(WeatherRepository.Day day, String units) {
        SpannableStringBuilder range = new SpannableStringBuilder();
        temperaturePart(range, tr("макс. ", "high "), day.maxTemperature, units, false);
        range.append("   ");
        temperaturePart(range, tr("мин. ", "low "), day.minTemperature, units, true);
        return range;
    }

    private void temperaturePart(SpannableStringBuilder line, String label, double value, String units, boolean low) {
        int start = line.length();
        line.append(label);
        line.setSpan(new RelativeSizeSpan(14f / 40f), start, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new ForegroundColorSpan(mutedColor), start, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int numberStart = line.length();
        if (!Double.isFinite(value)) {
            line.append("—");
        } else {
            long rounded = Math.round(value);
            line.append(rounded > 0 ? "+" : "").append(Long.toString(rounded))
                    .append("fahrenheit".equals(units) ? "°F" : "°C");
        }
        if (low) {
            line.setSpan(new RelativeSizeSpan(28f / 40f), numberStart, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new ForegroundColorSpan(mutedColor), numberStart, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void detail(LinearLayout parent, String value, String tag, boolean reserveRow) {
        if (value.isEmpty()) {
            if (reserveRow) {
                View space = new View(getContext()); space.setTag("forecast-space-" + tag);
                space.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addRow(parent, space, 5);
            }
            return;
        }
        TextView row = text(value, 17, mutedColor, false);
        row.setTag(tag);
        row.setMaxLines(2);
        row.setFontFeatureSettings("'tnum'");
        addRow(parent, row, 5);
    }

    /** Align actual text rows across the three open columns, including honest gaps in optional fields. */
    private void alignRows(List<LinearLayout> columns) {
        int width = (DESIGN_WIDTH - getPaddingLeft() - getPaddingRight()
                - COLUMN_GAP * (WeatherRepository.MAX_FORECAST_DAYS - 1)) / WeatherRepository.MAX_FORECAST_DAYS;
        List<String> tags = new ArrayList<>(); tags.add("forecast-condition-row"); tags.add("forecast-temperature");
        Collections.addAll(tags, DETAIL_TAGS);
        for (String tag : tags) {
            int height = 0;
            for (LinearLayout column : columns) {
                View row = column.findViewWithTag(tag);
                if (row == null) continue;
                row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, row.getMeasuredHeight());
            }
            for (LinearLayout column : columns) {
                View row = column.findViewWithTag(tag);
                if (row == null) row = column.findViewWithTag("forecast-space-" + tag);
                if (row != null) row.getLayoutParams().height = height;
            }
        }
    }

    private void message(String value) {
        TextView message = text(value, 20, mutedColor, false);
        message.setTag("forecast-message");
        addRow(this, message, 18);
    }

    private void footer(WeatherRepository.WeatherResult result, WeatherRepository.Weather weather,
            WeatherPolicy.CacheAge age, ZoneId zone, boolean aggregated) {
        boolean stale = age != WeatherPolicy.CacheAge.FRESH || result.status == WeatherRepository.Status.STALE;
        String fetched = weather.fetchedAtMillis <= 0 ? tr("время обновления неизвестно", "update time unknown")
                : Instant.ofEpochMilli(weather.fetchedAtMillis).atZone(zone == null ? ZoneOffset.UTC : zone).format(
                        DateTimeFormatter.ofPattern(russian ? "d MMM, HH:mm" : "MMM d, HH:mm", locale()))
                        + (zone == null || weather.forecastTimeBasis == WeatherRepository.ForecastTimeBasis.UTC ? " UTC" : "");
        String value = (stale ? tr("Сохранено ", "Saved ") : tr("Обновлено ", "Updated "))
                + fetched + " · " + weather.provider.attribution;
        if (aggregated) value += "\n" + tr("Расчёт по доступным часам и интервалам",
                "Based on available hours and forecast periods");
        if (!weather.cityTimezoneKnown) value += "\n"
                + tr("Даты и интервалы по UTC. Часовой пояс города пока неизвестен.",
                        "Dates and periods use UTC. The city time zone is not known yet.");
        accessible.append(". ").append(value);
        if (weather.cityTimezoneKnown) accessible.append(". ").append(tr("Местное время города: ", "City local time: "))
                .append(weather.timezone);
    }

    private String unavailable(WeatherRepository.WeatherResult result) {
        switch (result.failure) {
            case NETWORK: return tr("Прогноз недоступен. Проверь подключение к интернету.",
                    "Forecast unavailable. Check the internet connection.");
            case TIMEOUT: return tr("Сервис погоды не ответил. Прогноз пока недоступен.",
                    "The weather service timed out. Forecast unavailable.");
            case RATE_LIMITED: return tr("Сервис погоды временно ограничил запросы.",
                    "The weather service has temporarily limited requests.");
            case HTTP: case INVALID_RESPONSE: return tr("Источник не передал доступный прогноз.",
                    "The weather service could not provide a forecast.");
            default: return tr("Прогноз пока недоступен. Открой настройки погоды, чтобы обновить.",
                    "Forecast unavailable. Open weather settings to refresh.");
        }
    }

    private TextView text(CharSequence value, float size, int color, boolean medium) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * textScale);
        label.setTypeface(medium ? typography.medium : typography.regular);
        label.setIncludeFontPadding(false);
        label.setLineSpacing(2 * textScale, 1f);
        label.setFocusable(false);
        label.setFocusableInTouchMode(false);
        label.setTextIsSelectable(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!value.toString().trim().isEmpty()) {
            if (accessible.length() > 0) accessible.append(". ");
            accessible.append(value);
        }
        return label;
    }

    private static void addRow(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        placement.topMargin = topMargin;
        parent.addView(child, placement);
    }

    private int finish() {
        setContentDescription(accessible.toString());
        measure(MeasureSpec.makeMeasureSpec(DESIGN_WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        requestLayout();
        return getMeasuredHeight();
    }

    private String decimal(double value) {
        return Math.abs(value - Math.rint(value)) < .05 ? Long.toString(Math.round(value))
                : String.format(locale(), "%.1f", value);
    }

    private String tr(String ru, String en) { return russian ? ru : en; }
    private String relativeAge(long fetchedAtMillis, long now) {
        if (fetchedAtMillis <= 0 || fetchedAtMillis > now) return "—";
        long minutes = (now - fetchedAtMillis) / 60_000L;
        if (minutes == 0) return tr("Только что", "just now");
        if (minutes < 60) return minutes + tr(" мин назад", "m ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + tr(" ч назад", "h ago");
        return hours / 24 + tr(" дн назад", "d ago");
    }
    private Locale locale() { return russian ? RUSSIAN : Locale.US; }
    private String capitalize(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(locale()) + value.substring(1);
    }
    private static ZoneId zone(String name) {
        try { return ZoneId.of(name); }
        catch (DateTimeException | NullPointerException invalid) { return null; }
    }
    private static String time(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }

    private String condition(int code) {
        return WeatherViewText.condition(code, russian);
    }

    private static String symbol(int code) {
        return WeatherViewText.symbol(code, true);
    }
}
