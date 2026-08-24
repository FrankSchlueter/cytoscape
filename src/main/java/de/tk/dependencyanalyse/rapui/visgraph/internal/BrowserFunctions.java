package de.tk.dependencyanalyse.rapui.visgraph.internal;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper for registering and deregistering {@link BrowserFunction}s bound to
 * a single {@link Browser} instance.
 *
 * <p>Use {@link #create(String, FnBody)} to register a function whose Java
 * callback receives the raw arguments forwarded by RAP's
 * {@code BrowserFunction} callback. {@link #dispose()} deregisters every
 * registered function in registration order.</p>
 *
 * <p>Deregistration requires the underlying browser to still be alive —
 * call {@link #dispose()} <em>before</em> the widget's
 * {@link Browser#dispose()}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * BrowserFunctions fns = new BrowserFunctions(browser);
 * fns.create("vgv_viewerReady", args -> { ready = true; return null; });
 * fns.dispose();
 * }</pre>
 */
public final class BrowserFunctions {

    private static final Logger LOG = Logger.getLogger(BrowserFunctions.class.getName());

    private final Browser browser;
    private final List<BrowserFunction> registered = new ArrayList<>();

    public BrowserFunctions(Browser browser) {
        this.browser = browser;
    }

    /**
     * Registers a new {@link BrowserFunction} with the given name and body.
     * The body receives the {@code args} array from RAP's
     * {@code BrowserFunction.function(Object[])} callback.
     */
    public BrowserFunction create(String name, FnBody body) {
        BrowserFunction f = new BrowserFunction(browser, name) {
            @Override
            public Object function(Object[] args) {
                try {
                    return body.call(args);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "function " + name + " threw", e);
                    return null;
                }
            }
        };
        registered.add(f);
        return f;
    }

    /**
     * Deregisters every registered function. Safe to call multiple times.
     */
    public void dispose() {
        for (BrowserFunction fn : registered) {
            try {
                fn.dispose();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "dispose failed", e);
            }
        }
        registered.clear();
    }

    /**
     * Returns the {@link Browser} this instance was bound to. Used for
     * assertions and exceptional code paths.
     */
    public Browser getBrowser() {
        return browser;
    }

    /**
     * Extracts a string argument at position {@code i}. Returns {@code null}
     * for missing arguments or {@code null} argument values.
     */
    public static String stringAt(Object[] args, int i) {
        if (args == null || i >= args.length) return null;
        Object v = args[i];
        return v == null ? null : v.toString();
    }

    /**
     * Extracts an int argument at position {@code i}. Returns {@code 0} for
     * missing arguments, wrong types, or unparseable values.
     */
    public static int intAt(Object[] args, int i) {
        if (args == null || i >= args.length) return 0;
        Object v = args[i];
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    @FunctionalInterface
    public interface FnBody {
        Object call(Object[] args);
    }
}
