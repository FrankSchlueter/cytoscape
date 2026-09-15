package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip regression guard for the gzip+base64 payload format used
 * by {@link SigmaJsBridge#applyData} to ship the graphology JSON to the
 * iframe.
 *
 * <p>The Java side encodes the JSON as {@code base64(gzip(json))}; the
 * iframe decodes it via {@code pako.ungzip(atob(b64), {toText:true})}.
 * We can't drive the iframe-side pako from JUnit, but we CAN verify the
 * Java side emits a valid gzip stream that decodes back to the original
 * payload — and we can sanity-check the compression ratio on a realistic
 * graphology payload.</p>
 */
class SigmaJsBridgeGzipTest {

    @Test
    void roundTripSimpleAscii() throws Exception {
        String original = "{\"nodes\":[],\"edges\":[]}";
        String b64 = SigmaJsBridge.gzipAndBase64(original);
        assertNotNull(b64);
        // Must be valid base64.
        byte[] decoded = Base64.getDecoder().decode(b64);
        // Must decode to a valid gzip stream whose text equals the original.
        String text = gunzip(decoded);
        assertEquals(original, text, "gzip+base64 round-trip must preserve the input verbatim");
    }

    @Test
    void roundTripGraphologyPayload() throws Exception {
        // A realistic graphology payload — labels, colors, repeated node-id prefixes.
        String original = "{\"nodes\":["
                + "{\"key\":\"a.b.c.Node1\",\"attributes\":{\"label\":\"Node 1\",\"color\":\"#4A90E2\"}},"
                + "{\"key\":\"a.b.c.Node2\",\"attributes\":{\"label\":\"Node 2\",\"color\":\"#7B68EE\"}},"
                + "{\"key\":\"a.b.c.Node3\",\"attributes\":{\"label\":\"Node 3\",\"color\":\"#E74C3C\"}},"
                + "{\"key\":\"a.b.c.Node4\",\"attributes\":{\"label\":\"Node 4\",\"color\":\"#27AE60\"}}"
                + "],\"edges\":["
                + "{\"key\":\"e1\",\"source\":\"a.b.c.Node1\",\"target\":\"a.b.c.Node2\",\"attributes\":{\"weight\":1.0}},"
                + "{\"key\":\"e2\",\"source\":\"a.b.c.Node2\",\"target\":\"a.b.c.Node3\",\"attributes\":{\"weight\":2.5}},"
                + "{\"key\":\"e3\",\"source\":\"a.b.c.Node3\",\"target\":\"a.b.c.Node4\",\"attributes\":{\"weight\":7.125}}"
                + "]}";
        String b64 = SigmaJsBridge.gzipAndBase64(original);
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertEquals(original, gunzip(decoded),
                "graphology payload must survive gzip+base64 round-trip");
        // Sanity check on compression: even a small payload should compress to
        // less than the raw JSON because of repeated node-id prefixes / colors.
        // 50% reduction is a very loose bound — a real graphology payload
        // typically sees 5-10x reduction.
        assertTrue(b64.length() < original.length() * 0.75,
                "compressed payload should be noticeably smaller than raw JSON "
                        + "(raw=" + original.length() + "B, gz+b64=" + b64.length() + "B)");
    }

    @Test
    void roundTripUtf8NonAscii() throws Exception {
        // Labels like "Lager Nord" / German umlauts / emoji. UTF-8 round-trip
        // matters because pako on the JS side uses TextDecoder for the toText
        // path — we MUST emit UTF-8 bytes, not platform-default.
        String original = "{\"label\":\"Lager Nord — Größe 42 °C 🔧\"}";
        String b64 = SigmaJsBridge.gzipAndBase64(original);
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertEquals(original, gunzip(decoded),
                "UTF-8 payload must survive gzip+base64 round-trip");
    }

    @Test
    void emptyStringRoundTrips() throws Exception {
        String original = "";
        String b64 = SigmaJsBridge.gzipAndBase64(original);
        assertNotNull(b64);
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertEquals("", gunzip(decoded));
    }

    private static String gunzip(byte[] gzipped) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            ByteArrayOutputStreamSink sink = new ByteArrayOutputStreamSink();
            byte[] buf = new byte[1024];
            int n;
            while ((n = gz.read(buf)) > 0) sink.write(buf, 0, n);
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class ByteArrayOutputStreamSink extends java.io.ByteArrayOutputStream {
        // Marker subclass so the test can pass a generic-looking helper around
        // without leaking the underlying ByteArrayOutputStream API in the test
        // method body. Cheap indirection — no behaviour override.
    }
}
