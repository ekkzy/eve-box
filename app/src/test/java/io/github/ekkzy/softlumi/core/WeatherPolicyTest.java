package io.github.ekkzy.softlumi.core;

import static org.junit.Assert.*;
import org.junit.Test;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class WeatherPolicyTest {
    private static final long NOW = 1800000000000L;
    @Test public void cacheBoundariesAreExplicit() {
        assertEquals(WeatherPolicy.CacheAge.FRESH, WeatherPolicy.cacheAge(NOW - WeatherPolicy.FRESH_MILLIS + 1, NOW));
        assertEquals(WeatherPolicy.CacheAge.STALE, WeatherPolicy.cacheAge(NOW - WeatherPolicy.FRESH_MILLIS, NOW));
        assertEquals(WeatherPolicy.CacheAge.STALE, WeatherPolicy.cacheAge(NOW - WeatherPolicy.MAX_STALE_MILLIS, NOW));
        assertEquals(WeatherPolicy.CacheAge.EXPIRED, WeatherPolicy.cacheAge(NOW - WeatherPolicy.MAX_STALE_MILLIS - 1, NOW));
        assertEquals(WeatherPolicy.CacheAge.EXPIRED, WeatherPolicy.cacheAge(NOW + 1, NOW));
    }
    @Test public void rejectsNonFiniteAndOutOfRangeCoordinates() {
        assertTrue(WeatherPolicy.validCoordinates(0, 0));
        assertTrue(WeatherPolicy.validCoordinates(-90, 180));
        assertFalse(WeatherPolicy.validCoordinates(Double.NaN, 0));
        assertFalse(WeatherPolicy.validCoordinates(91, 0));
        assertFalse(WeatherPolicy.validCoordinates(0, Double.POSITIVE_INFINITY));
    }
    @Test public void retriesOnlyTransientStatusesAndCapsExponentialBackoff() {
        for (int status : new int[]{408, 500, 502, 503, 504}) assertTrue(WeatherPolicy.retryableHttpStatus(status));
        for (int status : new int[]{200, 301, 400, 401, 403, 404, 429, 501, 505})
            assertFalse(WeatherPolicy.retryableHttpStatus(status));
        assertEquals(60_000, WeatherPolicy.retryDelayMillis(1, 0));
        assertEquals(120_000, WeatherPolicy.retryDelayMillis(2, 0));
        assertEquals(240_000, WeatherPolicy.retryDelayMillis(3, 0));
        assertEquals(480_000, WeatherPolicy.retryDelayMillis(4, 0));
        assertEquals(900_000, WeatherPolicy.retryDelayMillis(Integer.MAX_VALUE, 0));
        assertEquals(3_600_000, WeatherPolicy.retryDelayMillis(1, 3_600_000));
    }
    @Test public void retryAfterAcceptsSecondsAndHttpDateWithoutUnboundedWait() {
        assertEquals(120_000, WeatherPolicy.retryAfterMillis("120", NOW));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(NOW + 300_000).atZone(ZoneOffset.UTC));
        assertEquals(300_000, WeatherPolicy.retryAfterMillis(date, NOW));
        assertEquals(0, WeatherPolicy.retryAfterMillis(date, NOW + 300_001));
        assertEquals(WeatherPolicy.MAX_RETRY_AFTER_MILLIS,
                WeatherPolicy.retryAfterMillis("999999999999999999999999", NOW));
        for (String invalid : new String[]{null, "", "-1", "1.5", "tomorrow", "NaN"})
            assertEquals(0, WeatherPolicy.retryAfterMillis(invalid, NOW));
    }
}
