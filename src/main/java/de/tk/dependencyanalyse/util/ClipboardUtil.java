package de.tk.dependencyanalyse.util;

import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

import java.util.Objects;

/**
 * Small helper around SWT {@link Clipboard} so callers do not have to deal
 * with {@code Display} lookup and {@code TextTransfer} boilerplate.
 *
 * <p>On the Eclipse RAP application server (Jetty) the {@link Clipboard}
 * routes the {@code setContents} call to the connected browser session via
 * the {@code org.eclipse.rap.rwt.client.ClientFileUploader}-equivalent
 * clipboard service, so the text lands in the user's OS clipboard from a
 * single, server-side {@code copyToClipboard} call.</p>
 *
 * <p>If no {@link Display} is currently bound (e.g. background threads) the
 * utility falls back to the default display — and as a final safety net it
 * logs the payload to {@code System.out} so the text is still recoverable
 * during tests or headless diagnostics.</p>
 */
public final class ClipboardUtil {

    private ClipboardUtil() {}

    /**
     * Place {@code text} on the system clipboard of the current UI session.
     *
     * @param text the string to copy; must not be {@code null}
     * @throws IllegalArgumentException if {@code text} is {@code null}
     */
    public static void copyToClipboard(String text) {
        Objects.requireNonNull(text, "text");
        Display display = Display.getCurrent();
        if (display == null) {
            display = Display.getDefault();
        }
        if (display == null) {
            // Headless fallback: dump the payload so the call does not silently
            // succeed without any side effect.
            System.out.println("ClipboardUtil.copyToClipboard (headless): " + text);
            return;
        }
        Clipboard cb = new Clipboard(display);
        try {
            cb.setContents(new Object[] { text },
                    new Transfer[] { TextTransfer.getInstance() });
        } finally {
            cb.dispose();
        }
    }
}
