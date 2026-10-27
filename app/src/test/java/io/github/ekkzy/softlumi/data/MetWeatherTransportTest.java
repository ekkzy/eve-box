package io.github.ekkzy.softlumi.data;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/** Exercises the production transport over controlled HTTP connections, without opening a socket. */
public final class MetWeatherTransportTest {
    private static final String ENDPOINT = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=0&lon=0";
    private static final String USER_AGENT = "softlumi/test (io.github.ekkzy.softlumi.debug; Android TV launcher)";
    private static final String VALIDATOR = "Sat, 24 Oct 2026 19:17:00 GMT";

    @Test public void sendsIdentityCompressionAndExactValidatorWithinTheCallerBudget() throws Exception {
        Connection response = new Connection(200, "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8));
        response.lastModified = VALIDATOR; response.expiry = 1_800_000_000_000L;
        Exchanges requests = new Exchanges(response);
        MetWeatherRepository.Response result = transport(requests).get(ENDPOINT, VALIDATOR, 1200);
        assertEquals(200, result.status); assertEquals("{\"fixture\":true}", result.body);
        assertEquals(VALIDATOR, result.lastModified); assertEquals(response.expiry, result.expiresAtMillis);
        assertEquals(USER_AGENT, response.sentHeaders.get("User-Agent"));
        assertEquals("application/json", response.sentHeaders.get("Accept"));
        assertEquals("gzip, deflate", response.sentHeaders.get("Accept-Encoding"));
        assertEquals(VALIDATOR, response.sentHeaders.get("If-Modified-Since"));
        assertFalse(response.getInstanceFollowRedirects()); assertFalse(response.getUseCaches());
        assertTrue(response.getConnectTimeout() > 0 && response.getConnectTimeout() <= 1200);
        assertTrue(response.getReadTimeout() > 0 && response.getReadTimeout() <= 1200);
        assertTrue(response.bodyClosed); assertTrue(response.disconnected);
    }

    @Test public void decodesIdentityGzipAndDeflateWithoutChangingUtf8Payload() throws Exception {
        String text = "{\"fixture\":\"Облачно\"}";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (String encoding : new String[]{null, "identity", "gzip", "deflate"}) {
            Connection response = new Connection(203, encoded(bytes, encoding)); response.encoding = encoding;
            MetWeatherRepository.Response result = transport(new Exchanges(response)).get(ENDPOINT, "", 5000);
            assertEquals(203, result.status); assertEquals(text, result.body);
            assertTrue(response.bodyClosed); assertTrue(response.disconnected);
        }
    }

    @Test public void followsOnlyTwoSameHostHttpsRedirectsAndPreservesValidator() throws Exception {
        Connection first = new Connection(301, new byte[0]); first.location = "/forecast-again";
        Connection second = new Connection(307, new byte[0]); second.location = "https://api.met.no:443/final";
        Connection last = new Connection(304, new byte[0]); last.lastModified = VALIDATOR;
        Exchanges requests = new Exchanges(first, second, last);
        MetWeatherRepository.Response result = transport(requests).get(ENDPOINT, VALIDATOR, 5000);
        assertEquals(304, result.status); assertEquals("", result.body);
        assertEquals(3, requests.endpoints.size());
        assertEquals("https://api.met.no/forecast-again", requests.endpoints.get(1));
        assertEquals("https://api.met.no:443/final", requests.endpoints.get(2));
        for (Connection connection : Arrays.asList(first, second, last)) {
            assertEquals(VALIDATOR, connection.sentHeaders.get("If-Modified-Since"));
            assertEquals(0, connection.bodyRequests); assertTrue(connection.disconnected);
        }
    }

    @Test public void refusesUnsafeInitialUrlsBeforeOpeningAnyConnection() throws Exception {
        for (String address : new String[]{"http://api.met.no/forecast", "https://other.invalid/forecast",
                "https://api.met.no.other.invalid/forecast", "https://name@api.met.no/forecast", "https://api.met.no:444/forecast"}) {
            Exchanges requests = new Exchanges();
            assertThrows(ProtocolException.class, () -> transport(requests).get(address, "", 5000));
            assertTrue(requests.endpoints.isEmpty());
        }
    }

    @Test public void rejectsAnUnsafeRedirectBeforeOpeningItsDestination() throws Exception {
        for (String location : new String[]{"http://api.met.no/forecast", "https://other.invalid/forecast",
                "https://name@api.met.no/forecast", "https://api.met.no:444/forecast"}) {
            Connection redirect = new Connection(302, new byte[0]); redirect.location = location;
            Exchanges requests = new Exchanges(redirect);
            assertThrows(ProtocolException.class, () -> transport(requests).get(ENDPOINT, "", 5000));
            assertEquals(1, requests.endpoints.size()); assertTrue(redirect.disconnected);
        }
    }

    @Test public void missingDestinationAndThirdRedirectFailWithoutARequestLoop() throws Exception {
        Connection missing = new Connection(308, new byte[0]);
        assertThrows(ProtocolException.class, () -> transport(new Exchanges(missing)).get(ENDPOINT, "", 5000));
        assertTrue(missing.disconnected);
        Connection first = new Connection(302, new byte[0]); first.location = "/one";
        Connection second = new Connection(303, new byte[0]); second.location = "/two";
        Connection third = new Connection(308, new byte[0]); third.location = "/three";
        Exchanges requests = new Exchanges(first, second, third);
        assertThrows(ProtocolException.class, () -> transport(requests).get(ENDPOINT, "", 5000));
        assertEquals(3, requests.endpoints.size());
        assertTrue(first.disconnected && second.disconnected && third.disconnected);
    }

    @Test public void rateLimitPreservesRetryAfterWithoutOpeningTheErrorBody() throws Exception {
        Connection response = new Connection(429, new byte[0]); response.retryAfter = "120";
        WeatherRepository.HttpFailure failure = assertThrows(WeatherRepository.HttpFailure.class,
                () -> transport(new Exchanges(response)).get(ENDPOINT, "", 5000));
        assertEquals(429, failure.statusCode); assertEquals(120_000, failure.retryAfterMillis);
        assertEquals(0, response.bodyRequests); assertTrue(response.disconnected);
    }

    @Test public void limitsBothAdvertisedAndExpandedBodySizes() throws Exception {
        Connection advertised = new Connection(200, new byte[0]); advertised.advertisedLength = MetWeatherRepository.MAX_BYTES + 1;
        assertThrows(IOException.class, () -> transport(new Exchanges(advertised)).get(ENDPOINT, "", 5000));
        assertEquals(0, advertised.bodyRequests); assertTrue(advertised.disconnected);
        byte[] large = new byte[MetWeatherRepository.MAX_BYTES + 1]; Arrays.fill(large, (byte) 'x');
        Connection compressed = new Connection(200, encoded(large, "gzip")); compressed.encoding = "gzip";
        assertThrows(IOException.class, () -> transport(new Exchanges(compressed)).get(ENDPOINT, "", 5000));
        assertTrue(compressed.bodyClosed); assertTrue(compressed.disconnected);
    }

    @Test public void failedCompressionSetupStillClosesTheOpenedRawStream() throws Exception {
        for (String encoding : new String[]{"br", "gzip", "deflate"}) {
            Connection response = new Connection(200, new byte[]{1, 2, 3}); response.encoding = encoding;
            assertThrows(IOException.class, () -> transport(new Exchanges(response)).get(ENDPOINT, "", 5000));
            assertTrue("Raw stream closes for " + encoding, response.bodyClosed);
            assertTrue(response.disconnected);
        }
    }

    @Test public void invalidHeadersCannotBeSentAndCancelledRequestsOpenNoConnection() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new MetWeatherRepository.HttpsTransport("softlumi\r\nInjected: x"));
        Connection response = new Connection(304, new byte[0]);
        transport(new Exchanges(response)).get(ENDPOINT, "old\r\nInjected: x", 5000);
        assertFalse(response.sentHeaders.containsKey("If-Modified-Since"));
        Exchanges expired = new Exchanges();
        assertThrows(SocketTimeoutException.class, () -> transport(expired).get(ENDPOINT, "", 0));
        assertTrue(expired.endpoints.isEmpty());
        Exchanges cancelled = new Exchanges();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> transport(cancelled).get(ENDPOINT, "", 5000));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(cancelled.endpoints.isEmpty());
        } finally { Thread.interrupted(); }
    }

    private static MetWeatherRepository.HttpsTransport transport(Exchanges requests) {
        return new MetWeatherRepository.HttpsTransport(USER_AGENT, requests);
    }
    private static byte[] encoded(byte[] bytes, String encoding) throws IOException {
        if (!"gzip".equals(encoding) && !"deflate".equals(encoding)) return bytes;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if ("gzip".equals(encoding)) {
            try (GZIPOutputStream output = new GZIPOutputStream(result)) { output.write(bytes); }
        } else {
            try (DeflaterOutputStream output = new DeflaterOutputStream(result)) { output.write(bytes); }
        }
        return result.toByteArray();
    }
    private static final class Exchanges implements MetWeatherRepository.HttpsTransport.ConnectionFactory {
        final ArrayDeque<Connection> responses;
        final List<String> endpoints = new ArrayList<>();
        Exchanges(Connection... responses) { this.responses = new ArrayDeque<>(Arrays.asList(responses)); }
        @Override public HttpURLConnection open(URL endpoint) {
            endpoints.add(endpoint.toString());
            assertFalse("No unplanned request may be opened", responses.isEmpty());
            return responses.removeFirst();
        }
    }
    private static final class Connection extends HttpURLConnection {
        final int status; final byte[] bytes;
        final Map<String, String> sentHeaders = new HashMap<>();
        String encoding, location, lastModified, retryAfter;
        long expiry; int advertisedLength = -1, bodyRequests;
        boolean disconnected, bodyClosed;
        Connection(int status, byte[] bytes) throws Exception {
            super(new URL(ENDPOINT)); this.status = status; this.bytes = bytes;
        }
        @Override public void setRequestProperty(String name, String value) { sentHeaders.put(name, value); }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public int getContentLength() { return advertisedLength; }
        @Override public String getContentEncoding() { return encoding; }
        @Override public String getHeaderField(String name) {
            switch (name) {
                case "Location": return location;
                case "Last-Modified": return lastModified;
                case "Retry-After": return retryAfter;
                default: return null;
            }
        }
        @Override public long getHeaderFieldDate(String name, long fallback) { return "Expires".equals(name) ? expiry : fallback; }
        @Override public InputStream getInputStream() {
            bodyRequests++;
            return new ByteArrayInputStream(bytes) {
                @Override public void close() throws IOException { bodyClosed = true; super.close(); }
            };
        }
    }
}
