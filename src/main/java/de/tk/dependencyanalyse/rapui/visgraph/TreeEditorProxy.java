package de.tk.dependencyanalyse.rapui.visgraph;

import org.eclipse.swt.custom.TreeEditor;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import java.util.ArrayList;
import java.util.List;

/**
 * A small wrapper around {@link TreeEditor} that lets us track and dispose
 * every editor we attach to a {@link Tree}. This is necessary because the
 * TreeEditor API only supports one editor at a time — to attach a new
 * editor to a different {@link TreeItem}, the previous one must be disposed
 * first.
 */
final class TreeEditorProxy {

    private final List<TreeEditor> active = new ArrayList<>();

    /**
     * Attach {@code control} to the given {@code item} in column
     * {@code column}. Stretches horizontally to fill the cell.
     */
    void setEditor(Control control, TreeItem item, int column) {
        TreeEditor editor = new TreeEditor(item.getParent());
        editor.grabHorizontal = true;
        editor.grabVertical = true;
        editor.horizontalAlignment = SWTAlignment.LEFT;
        editor.verticalAlignment = SWTAlignment.CENTER;
        editor.setEditor(control, item, column);
        active.add(editor);
    }

    /** Dispose every editor previously attached via {@link #setEditor}. */
    void disposeAll() {
        for (TreeEditor e : active) {
            try { e.dispose(); } catch (Exception ignored) { }
        }
        active.clear();
    }

    /* ---- inline alignment constants (avoid pulling in SWT just for two ints) ---- */

    private static final class SWTAlignment {
        static final int LEFT = org.eclipse.swt.SWT.LEFT;
        static final int CENTER = org.eclipse.swt.SWT.CENTER;
    }
}