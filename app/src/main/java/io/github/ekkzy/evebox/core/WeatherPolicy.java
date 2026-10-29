package io.github.ekkzy.evebox.core;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class WeatherPolicy {
    public static final long FRESH_MILLIS = 30L * 60 * 1000;
    public static final long MAX_STALE_MILLIS = 24L * 60 * 60 * 1000;
    public static final long RETRY_BASE_MILLIS = 60_000;
    public static final long MAX_RETRY_MILLIS = 15L * 60 * 1000;
    public static final long MAX_RETRY_AFTER_MILLIS = 24L * 60 * 60 * 1000;
    public enum CacheAge { FRESH, STALE, EXPIRED }
    private WeatherPolicy() {}

    public static CacheAge cacheAge(long fetchedAt, long now) {
        if (fetchedAt <= 0 || now < fetchedAt) return CacheAge.EXPIRED;
        long elapsed = now - fetchedAt;
        if (elapsed < FRESH_MILLIS) return CacheAge.FRESH;
        return elapsed <= MAX_STALE_MILLIS ? CacheAge.STALE : CacheAge.EXPIRED;
    }

    public static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    /** Only transient HTTP failures may be retried within the current request budget. */
    public static boolean retryableHttpStatus(int status) {
        return status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    public static long retryDelayMillis(int consecutiveFailures, long retryAfterMillis) {
        int shifts = Math.max(1, Math.min(5, consecutiveFailures)) - 1;
        long backoff = Math.min(MAX_RETRY_MILLIS, RETRY_BASE_MILLIS << shifts);
        return Math.max(backoff, Math.max(0, Math.min(MAX_RETRY_AFTER_MILLIS, retryAfterMillis)));
    }

    /** RFC 9110 Retry-After accepts either nonnegative seconds or an HTTP date. */
    public static long retryAfterMillis(String header, long now) {
        if (header == null || header.length() > 128) return 0;
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                return seconds >= MAX_RETRY_AFTER_MILLIS / 1000
                        ? MAX_RETRY_AFTER_MILLIS : seconds * 1000;
            } catch (NumberFormatException overflow) { return MAX_RETRY_AFTER_MILLIS; }
        }
        try {
            long timestamp = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return timestamp <= now ? 0 : Math.min(MAX_RETRY_AFTER_MILLIS, timestamp - now);
        } catch (DateTimeParseException | ArithmeticException invalid) { return 0; }
    }
}
