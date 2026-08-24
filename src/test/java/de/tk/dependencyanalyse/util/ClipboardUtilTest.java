package de.tk.dependencyanalyse.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardUtilTest {

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class, () -> ClipboardUtil.copyToClipboard(null));
    }

    @Test
    void headlessFallbackPrintsPayloadToStdout() {
        // No Display is available inside a normal Maven-Surefire run; the
        // utility's no-Display path prints the payload to System.out so a
        // recovery is still possible.
        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            ClipboardUtil.copyToClipboard("hello-gml");
        } finally {
            System.setOut(original);
        }
        String out = capture.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("ClipboardUtil.copyToClipboard"),
                "headless fallback should announce itself in stdout");
        assertTrue(out.contains("hello-gml"),
                "payload should be present in stdout: " + out);
    }
}
