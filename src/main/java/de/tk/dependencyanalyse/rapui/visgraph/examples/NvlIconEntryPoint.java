package de.tk.dependencyanalyse.rapui.visgraph.examples;

import de.tk.dependencyanalyse.rapui.visgraph.GraphViewerControlBar;
import de.tk.dependencyanalyse.rapui.visgraph.SwitchingViewer;
import de.tk.dependencyanalyse.rapui.visgraph.callback.NodeSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.callback.RelationshipSelectionListener;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphRelationship;
import de.tk.dependencyanalyse.rapui.visgraph.data.LayoutAlgorithm;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import org.eclipse.rap.rwt.application.AbstractEntryPoint;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAP entry point that demonstrates the NVL {@code overlayIcon}
 * pipeline. The viewer renders a synthetic 20-node / 40-edge graph in
 * two batches:
 *
 * <h2>Batch 1 — labeled nodes (n0…n9)</h2>
 * Each node carries:
 * <ul>
 *   <li>a distinct background color (rendered as the NVL-native circle
 *       via the node's {@code color} attribute);</li>
 *   <li>a transparent SVG icon overlay picked from
 *       {@code /static/icons/} (8 SVGs available — used cyclically);</li>
 *   <li>a human-readable label that drives the NVL caption text.</li>
 * </ul>
 * The first 5 nodes additionally carry an annotation character rendered
 * inside a colored circle anchored at the bottom-right of the icon. The
 * annotation-circle color is taken from an independent palette.
 *
 * <p>Because the label is set, the icon is positioned with
 * {@code position: [0, -0.5]} (shifted 0.5 radii up) so the
 * {@code captionAlign: "bottom"} text below the icon does not overlap it.</p>
 *
 * <h2>Batch 2 — unlabeled nodes (n10…n19)</h2>
 * Each node carries:
 * <ul>
 *   <li>a distinct background color (different palette from batch 1);</li>
 *   <li>a transparent SVG icon overlay (different SVG icon per node,
 *       where possible — we have 8 SVGs and use them in a shuffled
 *       order so all 8 icons appear in batch 2);</li>
 *   <li>no caption / label.</li>
 * </ul>
 * The first 5 of these also carry an annotation character.
 *
 * <p>Because no caption is set, the icon stays centered on the node
 * (no need to shift it up to make room for a caption).</p>
 *
 * <p>Accessed at <code>http://localhost:8085/nvl-icon</code>.</p>
 *
 * <p>The graph is built in memory — no REST round-trip — so the demo
 * renders deterministically without depending on the bundled
 * {@code export.csv}.</p>
 */
public class NvlIconEntryPoint extends AbstractEntryPoint {

    /** Eight SVG icons available in {@code /static/icons/}. Used cyclically
     *  for batch 1 and in a shuffled order for batch 2 so all 8 are visible
     *  in both halves of the demo. */
    static final String[] ICON_FILES = {
            "java-16-svgrepo-com.svg",
            "interface-16-svgrepo-com.svg",
            "struct-16-svgrepo-com.svg",
            "ionic-16-svgrepo-com.svg",
            "prisma-16-svgrepo-com.svg",
            "folder-svgrepo-com.svg",
            "database-svgrepo-com.svg",
            "source-code.svg"
    };

    /** Shuffled icon order for batch 2 — guarantees all 8 distinct icons
     *  appear in the unlabeled half without relying on the natural cyclic
     *  order of {@link #ICON_FILES}. */
    static final int[] BATCH2_ICON_ORDER = { 4, 1, 6, 2, 7, 0, 5, 3, 4, 0 };

    /** Batch 1 node background colors. */
    static final String[] NODE_BG_COLORS = {
            "#E74C3C", "#3498DB", "#2ECC71", "#F1C40F", "#9B59B6",
            "#E67E22", "#1ABC9C", "#34495E", "#E91E63", "#00BCD4"
    };

    /** Batch 2 node background colors — different palette so the two
     *  halves are visually distinguishable. */
    static final String[] BATCH2_NODE_BG_COLORS = {
            "#8E44AD", "#16A085", "#D35400", "#C0392B", "#7F8C8D",
            "#2C3E50", "#F39C12", "#27AE60", "#2980B9", "#BDC3C7"
    };

    /** Annotation-circle colors — independent from the node colors. */
    static final String[] ANNOTATION_BG_COLORS = {
            "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8"
    };

    /** Single-character labels rendered inside each annotation circle
     *  in batch 1. */
    static final char[] ANNOTATION_CHARS = { 'A', 'B', 'C', 'D', 'E' };

    /** Single-character labels rendered inside each annotation circle
     *  in batch 2. */
    static final char[] BATCH2_ANNOTATION_CHARS = { 'F', 'G', 'H', 'I', 'J' };

    /** Human-readable labels for the batch 1 nodes. */
    static final String[] NODE_LABELS = {
            "Service", "Controller", "Entity", "Repository", "Table",
            "Batch", "Queue", "Config", "Enum", "Record"
    };

    /** Number of nodes per batch (each batch: 5 annotated + 5 plain). */
    static final int BATCH_SIZE = 10;
    static final int ANNOTATED_PER_BATCH = 5;
    static final int TOTAL_NODE_COUNT = BATCH_SIZE * 2;
    /** Two outgoing edges per node: i → (i+1) and i → (i+2). */
    static final int EDGE_COUNT = TOTAL_NODE_COUNT * 2;

    private SwitchingViewer viewer;
    private Label statusLabel;

    @Override
    protected void createContents(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setText("Lade NVL Icon-Testgraph ...");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        viewer = new SwitchingViewer(parent, SWT.NONE);
        viewer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.addNodeSelectionListener(new NodeSelectionListener() {
            @Override
            public void nodeSelected(GraphNode node) {
                System.out.println("NvlIcon: node selected: " + node.getId());
            }
        });
        viewer.addRelationshipSelectionListener(new RelationshipSelectionListener() {
            @Override
            public void relationshipSelected(GraphRelationship relationship) {
                System.out.println("NvlIcon: relationship selected: " + relationship.getId());
            }
        });

        // The viewer control bar adds the engine-selector, layout-selector,
        // and a Fit-to-screen button. We don't actually want to switch
        // engines in this demo (the overlay only renders correctly under
        // NVL) but the bar is handy for the layout picker.
        @SuppressWarnings("unused")
        GraphViewerControlBar bar = new GraphViewerControlBar(parent, viewer,
                () -> { /* no dialog */ });

        // Default engine is NEO4J_NVL — no switchTo() needed. Verify so we
        // catch a future refactor that silently changes the default.
        if (viewer.getEngine() != GraphEngine.NEO4J_NVL) {
            statusLabel.setText("Fehler: Default-Engine ist nicht NVL (ist "
                    + viewer.getEngine() + ").");
            return;
        }

        GraphData data = buildDemoGraph();
        // Attach a 10-entry per-node Color Palette so the NVL engine
        // renders the Color Palette panel automatically when the graph
        // is loaded. The panel rows are derived on the bridge side via
        // LegendBuilder.fromLeidenClusters(...) — clusters are sorted
        // by member-count descending, so the largest palette group
        // becomes "Cluster1".
        Map<String, String> palette = new LinkedHashMap<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            palette.put("n" + i, NODE_BG_COLORS[i]);
        }
        data = data.setColorPalette(palette);
        viewer.setGraphData(data);
        viewer.setLayout(LayoutAlgorithm.FORCE_ATLAS_2D);
        statusLabel.setText("NVL Icon-Testgraph geladen: "
                + data.getNodes().size() + " Knoten ("
                + BATCH_SIZE + " mit Label, "
                + BATCH_SIZE + " ohne), "
                + data.getRelationships().size() + " Kanten, "
                + (ANNOTATED_PER_BATCH * 2) + " mit Annotation, "
                + data.getColorPalette().size() + " Palette-Einträge.");
    }

    /**
     * Build the synthetic 20-node / 40-edge demo graph with
     * transparent SVG icon overlays.
     */
    static GraphData buildDemoGraph() {
        List<GraphNode> nodes = new ArrayList<>(TOTAL_NODE_COUNT);

        // ---- Batch 1: labeled nodes (n0…n9) ----
        for (int i = 0; i < BATCH_SIZE; i++) {
            String id = "n" + i;
            String bg = NODE_BG_COLORS[i];
            String icon = ICON_FILES[i % ICON_FILES.length];
            String label = NODE_LABELS[i];
            GraphNode n = new GraphNode(id, label, List.of("DemoNode"),
                    Map.of("name", label, "nodeTag", "icon-demo"));
            n.setColor(bg);
            // setCaption forces GraphNode.getCaption() != null so
            // GraphNode.toNvlNode() emits captionAlign: "bottom" +
            // overlayIcon.position: [0, -0.5].
            n.setCaption(label);
            if (i < ANNOTATED_PER_BATCH) {
                n.setSvgOverlayIcon(icon,
                        ANNOTATION_CHARS[i],
                        ANNOTATION_BG_COLORS[i]);
            } else {
                n.setSvgOverlayIcon(icon);
            }
            nodes.add(n);
        }

        // ---- Batch 2: unlabeled nodes (n10…n19) ----
        for (int i = 0; i < BATCH_SIZE; i++) {
            String id = "n" + (BATCH_SIZE + i);
            String bg = BATCH2_NODE_BG_COLORS[i];
            String icon = ICON_FILES[BATCH2_ICON_ORDER[i % BATCH2_ICON_ORDER.length]];
            // No `name` property → getCaption() returns null → no caption
            // text rendered by NVL → GraphNode.toNvlNode() sees no caption
            // and emits overlayIcon.position: [0, 0] (centered, no shift).
            GraphNode n = new GraphNode(id, "DemoNode", List.of("DemoNode"),
                    Map.of("nodeTag", "icon-demo-unlabeled"));
            n.setColor(bg);
            if (i < ANNOTATED_PER_BATCH) {
                n.setSvgOverlayIcon(icon,
                        BATCH2_ANNOTATION_CHARS[i],
                        ANNOTATION_BG_COLORS[i]);
            } else {
                n.setSvgOverlayIcon(icon);
            }
            nodes.add(n);
        }

        // Deterministic edge pattern: each node i has two outgoing edges
        // to (i+1) mod TOTAL and (i+2) mod TOTAL, giving exactly 40 edges.
        // Weights differentiate the two edges so the layout shows a
        // visible pattern. Edges stay within each batch (the modulo
        // arithmetic wraps at TOTAL, so batch-1 and batch-2 nodes are
        // never linked to each other — keeps the two clusters visually
        // separate for the demo).
        List<GraphRelationship> rels = new ArrayList<>(EDGE_COUNT);
        int seq = 0;
        for (int i = 0; i < TOTAL_NODE_COUNT; i++) {
            int j1 = (i + 1) % TOTAL_NODE_COUNT;
            int j2 = (i + 2) % TOTAL_NODE_COUNT;
            rels.add(new GraphRelationship("e" + (seq++), "REL",
                    nodes.get(i), nodes.get(j1),
                    propsWithWeight(1.0)));
            rels.add(new GraphRelationship("e" + (seq++), "REL",
                    nodes.get(i), nodes.get(j2),
                    propsWithWeight(0.5)));
        }
        return new GraphData(nodes, rels);
    }

    private static Map<String, Object> propsWithWeight(double weight) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("weight", weight);
        return p;
    }
}