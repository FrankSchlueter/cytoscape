package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.eclipse.rap.rwt.widgets.BrowserCallback;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Display;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes {@link Browser#evaluate} calls.
 *
 * <p>RAP 4.x's {@link Browser#evaluate(String, BrowserCallback)} only allows
 * one script in flight at a time. A second {@code evaluate} call before the
 * first completes raises {@code IllegalStateException}. The {@link VisJsBridge}
 * (and the {@code NvlJsBridge}) issue many scripts in quick succession
 * ({@code setData}, {@code setLayout}, {@code setPhysics}, ...) so we queue
 * them and dispatch one at a time on the UI thread.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * BrowserScriptQueue queue = new BrowserScriptQueue(browser);
 * queue.exec("window.my_bridge_setData(" + json + ");");
 * queue.dispose();
 * }</pre>
 *
 * <p>The queue is safe to dispose while scripts are pending — pending scripts
 * are silently dropped when the underlying browser is gone.</p>
 *
 * <p><b>Draining model.</b> A single {@link AtomicBoolean} {@code draining}
 * guards the "one script in flight" invariant:
 * <ul>
 *   <li>{@code exec()} always offers to the queue. It tries to flip
 *       {@code draining} from {@code false} to {@code true} via CAS — if it
 *       succeeds, the caller becomes the active drainer and schedules a
 *       {@code drainQueue()} on the UI thread. If it loses the race, a
 *       drainer is already active (or scheduled) and the new script will be
 *       picked up by the in-flight loop.</li>
 *   <li>{@code drainQueue()} polls one script. If the queue is empty it
 *       releases {@code draining} and re-checks for a race (an {@code exec}
 *       that offered between the poll and the release). Otherwise it calls
 *       {@code browser.evaluate} and returns — the {@code draining} flag
 *       stays {@code true} until the script's callback fires.</li>
 *   <li>The {@code evaluationSucceeded} / {@code evaluationFailed} callback
 *       schedules the next {@code drainQueue()} via {@code asyncExec} so the
 *       in-flight guarantee is preserved (the next script is only evaluated
 *       <i>after</i> the current one finishes).</li>
 * </ul>
 * </p>
 */
public final class BrowserScriptQueue {

    private static final Logger LOG = Logger.getLogger(BrowserScriptQueue.class.getName());

    private final Browser browser;
    private final BlockingQueue<String> execQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile boolean disposed = false;

    public BrowserScriptQueue(Browser browser) {
        this.browser = browser;
    }

    /**
     * Enqueues a script. Returns immediately; the script will be evaluated
     * on the UI thread once the previously-enqueued scripts have finished.
     */
    public void exec(String script) {
        if (disposed) return;
        if (browser == null || browser.isDisposed()) return;
        execQueue.offer(script);
        // Try to become the active drainer. If we lose the race, a
        // drainer is already running and will pick up our script.
        if (draining.compareAndSet(false, true)) {
            scheduleDrain();
        }
    }

    /**
     * Drops all pending scripts and rejects further enqueues. Call from the
     * widget's {@code dispose()} method to avoid late callbacks touching a
     * disposed browser.
     */
    public void dispose() {
        disposed = true;
        execQueue.clear();
    }

    private void scheduleDrain() {
        if (disposed) return;
        if (browser == null || browser.isDisposed()) {
            releaseDraining();
            return;
        }
        Display display = browser.getDisplay();
        if (display == null) {
            releaseDraining();
            return;
        }
        display.asyncExec(this::drainQueue);
    }

    private void drainQueue() {
        if (disposed) return;
        String script = execQueue.poll();
        if (script == null) {
            // Queue is empty. Release the draining flag and re-check for
            // a race where another thread offered between our poll and
            // the release.
            releaseDraining();
            if (!execQueue.isEmpty() && draining.compareAndSet(false, true)) {
                scheduleDrain();
            }
            return;
        }
        final String currentScript = script;
        try {
            browser.evaluate(currentScript, new BrowserCallback() {
                public void evaluationSucceeded(Object result) {
                    scheduleDrain();
                }
                public void evaluationFailed(Exception ex) {
                    LOG.log(Level.WARNING, "execute failed: " + previewScript(currentScript), ex);
                    scheduleDrain();
                }
            });
            // Script is now in flight. The draining flag stays true until
            // the callback fires and schedules the next drain.
        } catch (Exception e) {
            LOG.log(Level.WARNING, "execute failed (sync): " + previewScript(script), e);
            // browser.evaluate refused the script synchronously. Release
            // the draining flag and chain to the next script.
            releaseDraining();
            if (!execQueue.isEmpty() && draining.compareAndSet(false, true)) {
                scheduleDrain();
            }
        }
    }

    private void releaseDraining() {
        draining.set(false);
    }

    private static String previewScript(String script) {
        return script.length() > 80 ? script.substring(0, 80) + "..." : script;
    }
}
