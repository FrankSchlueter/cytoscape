package de.tk.dependencyanalyse.rapui.visgraph;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.util.function.Consumer;

/**
 * A composite that lets the user pick a color graphically via the native
 * {@link ColorDialog}. The currently selected color is shown as a colored
 * swatch; clicking the "Wählen..." button opens the dialog and updates the
 * swatch when the user confirms.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ColorPicker picker = new ColorPicker(parent, "#FF8800", color -> {
 *     // apply color
 * });
 * }</pre>
 */
public class ColorPicker extends Composite {

    private final Label swatch;
    private final Button pickButton;
    private final Consumer<String> onChange;
    private final Color[] allocated = new Color[1];
    private String currentColor;

    public ColorPicker(Composite parent, String initialColor, Consumer<String> onChange) {
        super(parent, SWT.NONE);
        this.onChange = onChange;
        this.currentColor = normalizeHex(initialColor);
        if (this.currentColor == null) this.currentColor = "#000000";

        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 4;
        setLayout(layout);

        pickButton = new Button(this, SWT.PUSH);
        pickButton.setText("Wählen...");
        pickButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        pickButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                openColorDialog();
            }
        });

        swatch = new Label(this, SWT.BORDER);
        // Match the height of the pickButton so the swatch is clearly visible.
        swatch.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        swatch.setAlignment(SWT.CENTER);
        applySwatch(this.currentColor);
    }

    /** Returns the currently selected color (upper-case hex, {@code #RRGGBB}). */
    public String getColor() {
        return currentColor;
    }

    /** Sets the current color (also updates the swatch and fires onChange). */
    public void setColor(String color) {
        String normalized = normalizeHex(color);
        if (normalized == null) return;
        this.currentColor = normalized;
        applySwatch(normalized);
        if (onChange != null) onChange.accept(normalized);
    }

    private void openColorDialog() {
        Shell shell = getParent().getShell();
        ColorDialog dialog = new ColorDialog(shell);
        RGB initial = parseRgb(currentColor);
        if (initial != null) {
            dialog.setRGB(initial);
        }
        RGB chosen = dialog.open();
        if (chosen != null) {
            setColor(rgbToHex(chosen));
        }
    }

    private void applySwatch(String color) {
        RGB rgb = parseRgb(color);
        if (rgb == null) return;
        if (allocated[0] != null && !allocated[0].isDisposed()) {
            allocated[0].dispose();
        }
        allocated[0] = new Color(getDisplay(), rgb);
        swatch.setBackground(allocated[0]);
    }

    @Override
    public void dispose() {
        if (allocated[0] != null && !allocated[0].isDisposed()) {
            allocated[0].dispose();
        }
        super.dispose();
    }

    /* ---- helpers ---- */

    private static String normalizeHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) {
                return null;
            }
        }
        return "#" + s.toUpperCase();
    }

    private static RGB parseRgb(String color) {
        String n = normalizeHex(color);
        if (n == null) return null;
        return new RGB(
                Integer.parseInt(n.substring(1, 3), 16),
                Integer.parseInt(n.substring(3, 5), 16),
                Integer.parseInt(n.substring(5, 7), 16));
    }

    private static String rgbToHex(RGB rgb) {
        if (rgb == null) return null;
        return String.format("#%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }

    /** Convenience to expose the underlying Display (for Color allocation in tests). */
    public Display getDisplay() {
        Display d = super.getDisplay();
        return d == null ? Display.getCurrent() : d;
    }
}