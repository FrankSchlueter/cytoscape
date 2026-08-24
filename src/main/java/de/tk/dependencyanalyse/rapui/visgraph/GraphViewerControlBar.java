package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import de.tk.dependencyanalyse.rapui.visgraph.io.GraphFileParser;
import de.tk.dependencyanalyse.util.ClipboardUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Composite that hosts the typical control widgets for a
 * {@link SwitchingViewer}: engine selector (vis / cytoscape), engine-specific
 * layout combo, fit-to-screen button, and an entry-point for the
 * "Graph Configuration" dialog.
 *
 * <p>The layout combo is repopulated whenever the engine changes, so each
 * engine only shows the layouts it understands.</p>
 *
 * <p>Cytoscape has no notion of physics — the "Physics" / "Auto-Fit" widgets
 * are disabled in Cytoscape mode. vis-network uses them as before.</p>
 */
public class GraphViewerControlBar extends Composite {

    /**
     * Default directory the "Load Data…" {@link FileDialog} opens in.
     * Resolution order:
     *   1. System property {@code vis.graph.sample-dir} (set at JVM start),
     *   2. Environment variable {@code VIS_GRAPH_SAMPLE_DIR},
     *   3. {@code target/classes/sample} relative to {@code user.dir} (the
     *      Maven build output), or
     *   4. {@code src/main/resources/sample} (fallback for IDEs).
     */
    public static final String SAMPLE_DIR = resolveSampleDir();

    /** Common operations the bar needs from a viewer. */
    private interface ViewerOps {
        void setLayout(LayoutAlgorithm algo);
        void fitToScreen();
        default void loadGraphData(GraphData data) {}
        default String exportToGml() { return ""; }
    }

    private static final class SwitchingOps implements ViewerOps {
        private final SwitchingViewer v;
        SwitchingOps(SwitchingViewer v) { this.v = v; }
        public void setLayout(LayoutAlgorithm a) { v.setLayout(a); }
        public void fitToScreen() { v.fitToScreen(); }
        public void loadGraphData(GraphData data) { v.setGraphData(data); }
        @Override public String exportToGml() {
            GraphData data = v.getGraphData();
            return data == null ? "" : data.exportToGml();
        }
    }

    private final ViewerOps viewer;
    private final SwitchingViewer switching;
    private final Runnable configButtonAction;

    private Combo engineCombo;
    private Combo layoutCombo;
    private Button physicsButton;
    private Button autoFitButton;
    private Button fitButton;
    private Button configButton;
    private Button loadDataButton;
    private Button exportGmlButton;

    private LayoutAlgorithm[] supportedLayouts;

    public GraphViewerControlBar(Composite parent, SwitchingViewer viewer) {
        this(parent, SWT.NONE, viewer, null);
    }

    public GraphViewerControlBar(Composite parent, SwitchingViewer viewer, Runnable configButtonAction) {
        this(parent, SWT.NONE, viewer, configButtonAction);
    }

    public GraphViewerControlBar(Composite parent, int style, SwitchingViewer viewer, Runnable configButtonAction) {
        super(parent, style);
        if (viewer == null) {
            throw new IllegalArgumentException("viewer must not be null");
        }
        this.viewer = new SwitchingOps(viewer);
        this.switching = viewer;
        this.configButtonAction = configButtonAction;
        this.supportedLayouts = currentLayoutsFor(switching.getEngine());
        buildUi();
        switching.addEngineListener(this::onEngineChanged);
    }

    private void buildUi() {
        GridLayout layout = new GridLayout(10, false);
        layout.marginHeight = 4;
        layout.marginWidth = 4;
        setLayout(layout);

        /* ---- Engine selector ---- */
        Label lblEngine = new Label(this, SWT.NONE);
        lblEngine.setText("Engine:");
        engineCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        engineCombo.setItems(new String[] { "Vis", "Cytoscape" });
        engineCombo.select(switching.getEngine() == GraphEngine.CYTOSCAPE ? 1 : 0);
        engineCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                int idx = engineCombo.getSelectionIndex();
                if (idx == 0) switching.switchTo(GraphEngine.VIS_NETWORK);
                else if (idx == 1) switching.switchTo(GraphEngine.CYTOSCAPE);
            }
        });

        /* ---- Layout algorithm ---- */
        Label lblLayout = new Label(this, SWT.NONE);
        lblLayout.setText("Layout:");
        layoutCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        layoutCombo.setItems(displayNamesFor(supportedLayouts));
        layoutCombo.select(defaultLayoutIndex(supportedLayouts));
        layoutCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                int idx = layoutCombo.getSelectionIndex();
                if (idx >= 0 && idx < supportedLayouts.length) {
                    LayoutAlgorithm a = supportedLayouts[idx];
                    viewer.setLayout(a);
                }
            }
        });

        /* ---- Physics (vis-only) ---- */
        physicsButton = new Button(this, SWT.CHECK);
        physicsButton.setText("Physics");
        physicsButton.setSelection(true);
        physicsButton.setEnabled(switching.getEngine() == GraphEngine.VIS_NETWORK);

        /* ---- Auto-Fit (vis-only) ---- */
        autoFitButton = new Button(this, SWT.CHECK);
        autoFitButton.setText("Auto-Fit");
        autoFitButton.setSelection(true);
        autoFitButton.setEnabled(switching.getEngine() == GraphEngine.VIS_NETWORK);

        /* ---- Fit-to-Screen button ---- */
        fitButton = new Button(this, SWT.PUSH);
        fitButton.setText("Fit");
        fitButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                viewer.fitToScreen();
            }
        });

        /* ---- Graph Configuration button ---- */
        configButton = new Button(this, SWT.PUSH);
        configButton.setText("Graph Configuration...");
        if (configButtonAction != null) {
            configButton.addSelectionListener(new SelectionAdapter() {
                @Override public void widgetSelected(SelectionEvent e) {
                    configButtonAction.run();
                }
            });
        } else {
            configButton.setEnabled(false);
        }

        /* ---- Load Data button ---- */
        loadDataButton = new Button(this, SWT.PUSH);
        loadDataButton.setText("Load Data...");
        loadDataButton.setToolTipText("Load a graph from a CSV (edge list) or GML file.");
        loadDataButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                openLoadDataDialog();
            }
        });

        /* ---- Export GML button ---- */
        exportGmlButton = new Button(this, SWT.PUSH);
        exportGmlButton.setText("Export Gml");
        exportGmlButton.setToolTipText("Copy the current graph to the clipboard as a GML document.");
        exportGmlButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                exportGmlToClipboard();
            }
        });

        Label filler = new Label(this, SWT.NONE);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void exportGmlToClipboard() {
        String gml = viewer.exportToGml();
        if (gml == null || gml.isEmpty()) {
            showError("Kein Graph zum Exportieren — bitte zuerst Daten laden.", null);
            return;
        }
        try {
            ClipboardUtil.copyToClipboard(gml);
        } catch (RuntimeException ex) {
            showError("Kopieren in die Zwischenablage fehlgeschlagen", ex);
        }
    }

    private void openLoadDataDialog() {
        Shell shell = getShell();
        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Load Graph — CSV or GML");
        // Configure filters. SWT extension filters are of the form ".csv"
        // (no glob); the RAP implementation accepts multiple dots per entry.
        fd.setFilterExtensions(new String[] { "*.csv;*.gml", "*.csv", "*.gml" });
        // Ensure the upload directory exists on disk and is writable.
        File sampleDir = ensureSampleDir();
        fd.setUploadDirectory(sampleDir);
        // Hard cap: 10 MB per file, 2 minutes.
        fd.setUploadSizeLimit(10L * 1024 * 1024);
        fd.setUploadTimeLimit(120L);

        String fileName = fd.open();
        if (fileName == null || fileName.isEmpty()) return;

        Path path = sampleDir.toPath().resolve(fileName);
        GraphFileParser.Format fmt = GraphFileParser.detectFormat(fileName);
        GraphData loaded;
        try (InputStream in = Files.newInputStream(path)) {
            loaded = GraphFileParser.parse(in, fmt);
        } catch (IOException ex) {
            showError("Datei konnte nicht gelesen werden: " + path, ex);
            return;
        }
        if (loaded == null || loaded.getNodes().isEmpty()) {
            showError("Parser lieferte keinen Graph oder leeren Graph", null);
            return;
        }
        // Do NOT delete the uploaded file — the user picked it intentionally
        // and it can stay in the upload (sample) directory alongside the
        // bundled sample files.
        viewer.loadGraphData(loaded);
        if (switching != null && switching.getEngine() == GraphEngine.CYTOSCAPE) {
            switching.setLayout(LayoutAlgorithm.LEIDEN_GRID);
        } else if (switching != null) {
            switching.setLayout(LayoutAlgorithm.FORCE_ATLAS_2D);
        }
        switching.fitToScreen();
    }

    private File ensureSampleDir() {
        File dir = new File(SAMPLE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            // Last-resort fallback: a per-session temp dir so the FileDialog
            // still has a writable target.
            try {
                File f = Files.createTempDirectory("vis-graph-load-").toFile();
                return f;
            } catch (IOException e) {
                return new File(System.getProperty("java.io.tmpdir"));
            }
        }
        return dir;
    }

    private void showError(String msg, Throwable t) {
        Shell shell = getShell();
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText("Load Graph");
        mb.setMessage(t == null ? msg : msg + "\n" + t.getMessage());
        mb.open();
        if (t != null) t.printStackTrace();
    }

    private void onEngineChanged(GraphEngine engine) {
        supportedLayouts = currentLayoutsFor(engine);
        layoutCombo.setItems(displayNamesFor(supportedLayouts));
        layoutCombo.select(defaultLayoutIndex(supportedLayouts));
        boolean isVis = engine == GraphEngine.VIS_NETWORK;
        physicsButton.setEnabled(isVis);
        autoFitButton.setEnabled(isVis);
        // Reflect the new engine in the engine combo.
        engineCombo.select(isVis ? 0 : 1);
        // Apply a default layout for the new engine if the previous one
        // isn't supported.
        if (supportedLayouts.length > 0) {
            LayoutAlgorithm newDefault = supportedLayouts[defaultLayoutIndex(supportedLayouts)];
            viewer.setLayout(newDefault);
        }
    }

    private static LayoutAlgorithm[] currentLayoutsFor(GraphEngine engine) {
        return engine == GraphEngine.CYTOSCAPE
                ? LayoutAlgorithm.valuesForCytoscape()
                : LayoutAlgorithm.valuesForVisNetwork();
    }

    private static int defaultLayoutIndex(LayoutAlgorithm[] layouts) {
        for (int i = 0; i < layouts.length; i++) {
            if (layouts[i] == LayoutAlgorithm.FORCE_ATLAS_2D) return i;
        }
        // For Cytoscape, prefer LEIDEN_GRID (places each community in
        // its own visible cell); falls back to NULL (= preset), then FCOSE.
        for (int i = 0; i < layouts.length; i++) {
            if (layouts[i] == LayoutAlgorithm.LEIDEN_GRID) return i;
        }
        for (int i = 0; i < layouts.length; i++) {
            if (layouts[i] == LayoutAlgorithm.NULL) return i;
        }
        for (int i = 0; i < layouts.length; i++) {
            if (layouts[i] == LayoutAlgorithm.FCOSE) return i;
        }
        return layouts.length > 0 ? 0 : -1;
    }

    private static String[] displayNamesFor(LayoutAlgorithm[] layouts) {
        String[] out = new String[layouts.length];
        for (int i = 0; i < layouts.length; i++) {
            out[i] = layoutDisplayName(layouts[i]);
        }
        return out;
    }

    private static String layoutDisplayName(LayoutAlgorithm a) {
        switch (a) {
            case FORCE_ATLAS_2D:        return "Force-Atlas-2D";
            case BARNES_HUT:            return "Barnes-Hut";
            case REPULSION:             return "Repulsion";
            case HIERARCHICAL_REPULSION:return "Hier. Repulsion";
            case HIERARCHICAL:          return "Hierarchical";
            case GRID:                  return "Grid";
            case CIRCULAR:              return "Circular";
            case CONCENTRIC:            return "Concentric";
            case COSE:                  return "COSE";
            case COSE_BILKENT:          return "COSE-Bilkent";
            case FCOSE:                 return "fcose";
            case DAGRE:                 return "Dagre";
            case BREADTHFIRST:          return "Breadth-First";
            //case COLA:                  return "Cola";
            case NULL:                  return "Null (preset)";
            case NONE:                  return "None (frozen)";
            case LEIDEN_GRID:           return "Leiden Grid";
            default:                    return a.name();
        }
    }

    private static String resolveSampleDir() {
        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path[] candidates = {
                Paths.get(System.getProperty("vis.graph.sample-dir", "")),
                Paths.get(System.getenv().getOrDefault("VIS_GRAPH_SAMPLE_DIR", "")),
                cwd.resolve("target/classes/sample"),
                cwd.resolve("src/main/resources/sample"),
                cwd.resolve("src/main/resources/static/sample"),
        };
        for (Path p : candidates) {
            if (p == null) continue;
            String s = p.toString();
            if (s == null || s.isEmpty()) continue;
            if (Files.isDirectory(p)) return p.toAbsolutePath().toString();
        }
        // Last resort: create target/classes/sample on demand so the FileDialog
        // at least has a writable initial path.
        try {
            Files.createDirectories(candidates[2]);
            return candidates[2].toAbsolutePath().toString();
        } catch (IOException e) {
            return cwd.toAbsolutePath().toString();
        }
    }
}
