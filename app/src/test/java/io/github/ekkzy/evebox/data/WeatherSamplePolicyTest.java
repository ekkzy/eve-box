package io.github.ekkzy.evebox.data;

import static org.junit.Assert.*;

import io.github.ekkzy.evebox.core.WeatherPolicy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class WeatherSamplePolicyTest {
    private static final long HOUR = 3_600_000L;
    private static long time(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private static WeatherRepository.Hour hour(String iso, double value) {
        return new WeatherRepository.Hour(time(iso), value, 3);
    }
    private static WeatherRepository.Weather weather(long now, String zone, List<WeatherRepository.Hour> hours, boolean known) {
        return new WeatherRepository.Weather(8, 3, true, 9, now, now, "celsius", hours, 0, 0,
                zone, WeatherRepository.Provider.OPEN_METEO, 0, Collections.emptyList(), known);
    }
    @Test public void upcomingUsesThreeActualStrictlyFutureSamplesAndKeepsSparseProviderTimes() {
        long now = time("2026-10-25T09:00:00Z");
        WeatherRepository.Weather weather = weather(now, "Europe/Moscow", Arrays.asList(
                hour("2026-10-25T08:00:00Z", 0), hour("2026-10-25T09:00:00Z", 1),
                hour("2026-10-25T10:00:00Z", 2), hour("2026-10-25T16:00:00Z", 3),
                hour("2026-10-25T22:00:00Z", 4), hour("2026-10-26T04:00:00Z", 5)), true);
        List<WeatherRepository.Hour> future = weather.upcomingHours(now);
        assertEquals(3, future.size()); assertEquals(time("2026-10-25T10:00:00Z"), future.get(0).timeMillis);
        assertEquals(time("2026-10-25T16:00:00Z"), future.get(1).timeMillis);
        assertEquals(time("2026-10-25T22:00:00Z"), future.get(2).timeMillis);
        assertEquals(3, future.get(1).temp, 0);
        assertEquals(0, weather.upcomingHours(now + 24 * HOUR).size());
        try { future.clear(); fail("Returned projection must be immutable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void fourTwoHourlySlotsKeepTheFirstActualTimestampAndOriginalSampleValues() {
        long now = time("2026-10-25T09:00:00Z");
        WeatherRepository.Hour first = hour("2026-10-25T10:15:00Z", 17);
        WeatherRepository.Hour second = hour("2026-10-25T12:15:00Z", -2);
        WeatherRepository.Hour third = hour("2026-10-25T14:15:00Z", 8);
        WeatherRepository.Hour fourth = new WeatherRepository.Hour(time("2026-10-25T16:15:00Z"), 3, 0, false);
        WeatherRepository.Weather weather = weather(now, "Asia/Kolkata", Arrays.asList(
                fourth, hour("2026-10-25T09:00:00Z", 99), third, first, second), true);
        List<WeatherRepository.ForecastSlot> slots = weather.upcomingHourSlots(now);
        assertEquals(4, slots.size());
        List<WeatherRepository.Hour> actual = Arrays.asList(first, second, third, fourth);
        for (int index = 0; index < slots.size(); index++) {
            assertEquals(first.timeMillis + index * 2 * HOUR, slots.get(index).timeMillis);
            assertSame("No rounding, interpolation or replacement sample", actual.get(index), slots.get(index).sample);
        }
        assertEquals(-2, slots.get(1).sample.temp, 0);
        assertEquals(Boolean.FALSE, slots.get(3).sample.isDay);
        assertEquals("A fractional city offset preserves the real minute", 45,
                Instant.ofEpochMilli(slots.get(0).timeMillis).atZone(ZoneId.of("Asia/Kolkata")).getMinute());
        try { slots.clear(); fail("Returned slots must be immutable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void absentExactTwoHourSamplesRemainNullInsteadOfUsingNearbyRows() {
        long now = time("2026-10-25T09:00:00Z"), anchor = time("2026-10-25T10:00:00Z");
        WeatherRepository.Hour first = new WeatherRepository.Hour(anchor, -4, 71, true);
        WeatherRepository.Hour third = new WeatherRepository.Hour(anchor + 4 * HOUR, 9, 3, true);
        WeatherRepository.Weather weather = weather(now, "Europe/Moscow", Arrays.asList(first,
                new WeatherRepository.Hour(anchor + 2 * HOUR + 1, 88, 0), third,
                new WeatherRepository.Hour(anchor + 7 * HOUR, 99, 0)), true);
        List<WeatherRepository.ForecastSlot> slots = weather.upcomingHourSlots(now);
        assertEquals(4, slots.size()); assertSame(first, slots.get(0).sample); assertSame(third, slots.get(2).sample);
        assertEquals(anchor + 2 * HOUR, slots.get(1).timeMillis); assertNull(slots.get(1).sample);
        assertEquals(anchor + 6 * HOUR, slots.get(3).timeMillis); assertNull(slots.get(3).sample);
        assertEquals("The compatibility projection still returns actual sparse rows", anchor + 2 * HOUR + 1,
                weather.upcomingHours(now).get(1).timeMillis);
    }
    @Test public void moscowEveningSlotsAdvancePastOldAfternoonRowsAndAcrossMidnight() {
        ZoneId city = ZoneId.of("Europe/Moscow");
        long start = time("2026-10-25T11:00:00Z"); // 14:00 city time remains in the real source.
        List<WeatherRepository.Hour> rows = new ArrayList<>();
        for (int index = 0; index < 14; index++) rows.add(new WeatherRepository.Hour(start + index * HOUR, index, 3));
        long[] now = {time("2026-10-25T15:20:00Z"), time("2026-10-25T16:01:00Z")};
        int[][] expected = {{19, 21, 23, 1}, {20, 22, 0, 2}};
        WeatherRepository.Weather weather = weather(start, city.getId(), rows, true);
        for (int moment = 0; moment < now.length; moment++) {
            List<WeatherRepository.ForecastSlot> slots = weather.upcomingHourSlots(now[moment]);
            assertEquals(4, slots.size());
            for (int index = 0; index < slots.size(); index++) {
                WeatherRepository.ForecastSlot slot = slots.get(index);
                java.time.ZonedDateTime local = Instant.ofEpochMilli(slot.timeMillis).atZone(city);
                assertEquals(expected[moment][index], local.getHour());
                assertEquals("The midnight slots belong to their actual following date",
                        expected[moment][index] < 14 ? "2026-10-26" : "2026-10-25", local.toLocalDate().toString());
                assertTrue("Passed 14:00 and other past samples cannot anchor the forecast", slot.timeMillis > now[moment]);
                int sourceIndex = (int) ((slot.timeMillis - start) / HOUR);
                assertSame(rows.get(sourceIndex), slot.sample);
                assertEquals(sourceIndex, slot.sample.temp, 0);
            }
        }
    }
    @Test public void slotProjectionKeepsAvailableStaleDataButRejectsUnknownZonesExpiredAndEmptySources() {
        long fetched = time("2026-10-25T09:00:00Z");
        List<WeatherRepository.Hour> rows = new ArrayList<>();
        for (int offset : new int[]{2, 4, 6, 8, 30, 32, 34, 36})
            rows.add(new WeatherRepository.Hour(fetched + offset * HOUR, offset, 3));
        WeatherRepository.Weather saved = weather(fetched, "Europe/Moscow", rows, true);
        long stale = fetched + HOUR;
        assertEquals(WeatherPolicy.CacheAge.STALE, WeatherRepository.cacheAge(saved, stale));
        assertEquals(4, saved.upcomingHourSlots(stale).size());
        assertSame(rows.get(0), saved.upcomingHourSlots(stale).get(0).sample);
        assertTrue(weather(fetched, "Asia/Tokyo", rows, false).upcomingHourSlots(stale).isEmpty());
        assertTrue("A numeric offset does not identify the city's IANA timezone",
                weather(fetched, "+03:00", rows, true).upcomingHourSlots(stale).isEmpty());
        assertTrue(weather(fetched, "Europe/Moscow", Collections.emptyList(), true).upcomingHourSlots(stale).isEmpty());
        long expired = fetched + WeatherPolicy.MAX_STALE_MILLIS + 1;
        assertTrue("The fixture still has future source rows after expiry", rows.get(4).timeMillis > expired);
        assertTrue(saved.upcomingHourSlots(expired).isEmpty());
        assertTrue(saved.upcomingHourSlots(fetched + 40 * HOUR).isEmpty());
    }
    @Test public void twoHourEpochTargetsPreserveRealDstGapAndRepeatedHourWithoutCivilTimeGuessing() {
        String[] anchors = {"2026-03-29T00:00:00Z", "2026-10-25T00:00:00Z"};
        int[][] localHours = {{1, 4, 6, 8}, {2, 3, 5, 7}};
        for (int season = 0; season < anchors.length; season++) {
            long first = time(anchors[season]), now = first - 30 * 60_000L;
            List<WeatherRepository.Hour> rows = new ArrayList<>();
            for (int index = 0; index < 7; index++) rows.add(new WeatherRepository.Hour(first + index * HOUR, 10 + index, 3));
            List<WeatherRepository.ForecastSlot> slots = weather(now, "Europe/Berlin", rows, true).upcomingHourSlots(now);
            assertEquals(4, slots.size());
            for (int index = 0; index < slots.size(); index++) {
                WeatherRepository.ForecastSlot slot = slots.get(index);
                assertEquals(first + index * 2 * HOUR, slot.timeMillis);
                assertSame(rows.get(index * 2), slot.sample);
                assertEquals(localHours[season][index], Instant.ofEpochMilli(slot.timeMillis).atZone(ZoneId.of("Europe/Berlin")).getHour());
                assertEquals(10 + index * 2, slot.sample.temp, 0);
            }
        }
    }
    @Test public void dayAndNextNightSelectOnlyRealSamplesFromTheirOwnLocalDates() {
        long now = time("2026-10-25T14:00:00Z");
        WeatherRepository.Weather weather = weather(now, "Europe/Moscow", Arrays.asList(
                hour("2026-10-25T11:00:00Z", 10), hour("2026-10-25T23:00:00Z", 3),
                hour("2026-10-26T11:00:00Z", 20)), true);
        WeatherRepository.DayNight pair = weather.dayNight(now);
        assertEquals("2026-10-25", pair.dayDate); assertEquals("2026-10-26", pair.nightDate);
        assertEquals(10, pair.day.temp, 0); assertEquals(3, pair.night.temp, 0);
        WeatherRepository.Weather missingToday = weather(now, "Europe/Moscow", Arrays.asList(
                hour("2026-10-25T23:00:00Z", 3), hour("2026-10-26T11:00:00Z", 20)), true);
        assertNull(missingToday.dayNight(now).day); assertNotNull(missingToday.dayNight(now).night);
    }
    @Test public void fractionalOffsetRetainsTheActualHalfHourLocalSampleAndDate() {
        long now = time("2026-10-25T01:00:00Z");
        WeatherRepository.DayNight pair = weather(now, "Asia/Kolkata", Arrays.asList(
                hour("2026-10-25T08:00:00Z", 21), hour("2026-10-25T09:00:00Z", 22),
                hour("2026-10-25T21:00:00Z", 17)), true).dayNight(now);
        assertEquals(time("2026-10-25T08:00:00Z"), pair.day.timeMillis);
        assertEquals(13, Instant.ofEpochMilli(pair.day.timeMillis).atZone(ZoneId.of(pair.timezone)).getHour());
        assertEquals(30, Instant.ofEpochMilli(pair.day.timeMillis).atZone(ZoneId.of(pair.timezone)).getMinute());
        assertEquals("2026-10-26", pair.nightDate); assertEquals(time("2026-10-25T21:00:00Z"), pair.night.timeMillis);
    }
    @Test public void dstGapAndRepeatedHourUseRealInstantsRatherThanFabricatingLocalTwoOClock() {
        long spring = time("2026-03-28T20:00:00Z");
        WeatherRepository.DayNight gap = weather(spring, "Europe/Berlin", Arrays.asList(
                hour("2026-03-29T00:00:00Z", 3), hour("2026-03-29T01:00:00Z", 4)), true).dayNight(spring);
        assertEquals("2026-03-29", gap.nightDate); assertEquals(time("2026-03-29T01:00:00Z"), gap.night.timeMillis);
        assertEquals(3, Instant.ofEpochMilli(gap.night.timeMillis).atZone(ZoneId.of(gap.timezone)).getHour());
        long autumn = time("2026-10-24T18:00:00Z");
        WeatherRepository.Weather repeated = weather(autumn, "Europe/Berlin", Arrays.asList(
                hour("2026-10-25T00:00:00Z", 3), hour("2026-10-25T01:00:00Z", 4)), true);
        assertEquals(2, repeated.hours.size());
        assertEquals(time("2026-10-25T00:00:00Z"), repeated.dayNight(autumn).night.timeMillis);
    }
    @Test public void unknownTimezoneAndExpiredWeatherDoNotExposeMadeUpDayNightSamples() {
        long now = time("2026-10-25T09:00:00Z");
        List<WeatherRepository.Hour> rows = Arrays.asList(hour("2026-10-25T11:00:00Z", 10));
        WeatherRepository.DayNight unknown = weather(now, "Asia/Tokyo", rows, false).dayNight(now);
        assertEquals("", unknown.timezone); assertEquals("", unknown.dayDate); assertNull(unknown.day); assertNull(unknown.night);
        WeatherRepository.Weather saved = weather(now, "Europe/Moscow", rows, true);
        long expired = now + WeatherPolicy.MAX_STALE_MILLIS + 1;
        assertTrue(saved.upcomingHours(expired).isEmpty()); assertNull(saved.dayNight(expired).day);
    }
    @Test public void persistedHoursAreBoundedAndRetainTheNextNightBeyondTheOldTwelvePointLimit() throws Exception {
        long now = time("2026-10-25T06:00:00Z");
        List<WeatherRepository.Hour> rows = new ArrayList<>();
        for (int i = 0; i < 150; i++) rows.add(new WeatherRepository.Hour(now + i * HOUR, i % 20, 3));
        WeatherRepository.Weather original = weather(now, "Europe/Moscow", rows, true);
        WeatherRepository.Weather restored = WeatherRepository.CacheCodec.decode(WeatherRepository.CacheCodec.encode(original));
        assertTrue(restored.hours.size() <= WeatherRepository.MAX_FORECAST_HOURS);
        assertNotNull(restored.dayNight(now).night);
        assertEquals(time("2026-10-25T23:00:00Z"), restored.dayNight(now).night.timeMillis);
        assertFalse(restored.needsForecastUpgrade);
        assertEquals(4, restored.upcomingHourSlots(now).size());
        for (int index = 0; index < 4; index++) {
            WeatherRepository.ForecastSlot slot = restored.upcomingHourSlots(now).get(index);
            assertEquals(now + (1 + index * 2) * HOUR, slot.timeMillis);
            assertNotNull(slot.sample); assertEquals(1 + index * 2, slot.sample.temp, 0);
        }
    }
}
