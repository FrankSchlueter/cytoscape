package de.tk.dependencyanalyse.rapui.visgraph;

import de.tk.dependencyanalyse.rapui.visgraph.config.NodeConfig;
import de.tk.dependencyanalyse.rapui.visgraph.config.TagProperty;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphData;
import de.tk.dependencyanalyse.rapui.visgraph.data.GraphNode;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendBuilder;
import de.tk.dependencyanalyse.rapui.visgraph.data.LegendEntry;
import de.tk.dependencyanalyse.rapui.visgraph.data.Shape;
import de.tk.dependencyanalyse.rapui.visgraph.engine.GraphEngine;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Engine-agnostic configuration dialog for graph visualization, driven by
 * discovery of the current {@link GraphData}.
 *
 * <p>The dialog discovers the set of values for the synthetic
 * {@code _nodeType_} property of every node ({@code GraphNode.getLabels().get(0)}
 * by default) — i.e. the primary label / type per node — and:</p>
 *
 * <ul>
 *   <li>If exactly one value is present, the entire Node-Type Visualization
 *       section is hidden (nothing to map).</li>
 *   <li>If two or more values are present, the Node-Type Visualization section
 *       is shown with a {@code Shape} / {@code Color} mode combo and a
 *       sub-table that lists each {@code _nodeType_} value.</li>
 * </ul>
 *
 * <p>The dialog also walks every node and every property to find "tag"
 * candidate properties (e.g. {@code product}, {@code department}, …). A
 * property qualifies as a tag candidate if:</p>
 * <ul>
 *   <li>it is not one of the reserved names ({@code id}, {@code label},
 *       {@code nodeType}, {@code nodeTag}, {@code name}, {@code _nodeType_});</li>
 *   <li>at least two distinct non-null values are present across all nodes;</li>
 *   <li>the value-count is between 2 and {@link #MAX_TAG_VALUES}.</li>
 * </ul>
 *
 * <p>For each such property a "Tags:" row with a combo of candidate property
 * names is added below the Node-Type section. Selecting a property populates
 * a value table; clicking a color cell opens {@link ColorPicker}; clearing
 * the selection resets the table.</p>
 *
 * <p>Changes are pushed live to the {@link SwitchingViewer} via
 * {@link SwitchingViewer#setNodeConfig(NodeConfig)}.</p>
 */
public class GraphConfigurationDialog extends Dialog {

    /** Cap on per-property distinct values for tag-color assignment. */
    private static final int MAX_TAG_VALUES = 20;

    /** Reserved property names that we never treat as tag candidates. */
    private static final Set<String> RESERVED_PROPS;
    static {
        Set<String> s = new LinkedHashSet<>();
        s.add("id");
        s.add("label");
        s.add("nodeType");
        s.add("nodeTag");
        s.add("name");
        s.add("_nodeType_");
        RESERVED_PROPS = Collections.unmodifiableSet(s);
    }

    /**
     * Whitelist of property names the Tag section offers to the user.
     * Each name must additionally have between 2 and {@link #MAX_TAG_VALUES}
     * distinct non-null values across the graph to be eligible.
     */
    private static final List<String> TAG_PROPERTY_WHITELIST;
    static {
        List<String> l = new ArrayList<>();
        l.add("product");
        l.add("bundle");
        l.add("ownerProduct");
        TAG_PROPERTY_WHITELIST = Collections.unmodifiableList(l);
    }

    /** Possible values of the Node-Type mode combo. */
    private enum NodeTypeMode { SHAPE, COLOR }

    /** Possible sources for the legend panel. */
    private enum LegendSource { COMBINED, TAG_VALUES, LEIDEN_CLUSTERS, NODE_TYPES }

    private final SwitchingViewer viewer;
    private final GraphData data;
    private final GraphEngine engine;

    // ---- controls ----
    private Shell shell;
    private Table nodeTypeTable;
    private Combo nodeTypeModeCombo;
    private Label nodeTypeHint;

    private Combo tagPropertyCombo;
    private Table tagValueTable;
    private Label tagHint;

    private Button leidenApplyButton;
    private Label leidenStatus;

    private Button closeButton;

    // ---- legend widgets ----
    private Button legendEnableCheck;
    private Button legendShowCheck;
    private Combo legendSourceCombo;
    private Table legendPreviewTable;
    private Button legendApplyButton;
    private Button legendClearButton;
    private Label legendHint;
    private LegendSource legendSource = LegendSource.COMBINED;
    private List<LegendEntry> legendPreview = List.of();

    // ---- state ----
    private List<String> nodeTypeValues;
    private NodeTypeMode nodeTypeMode;
    private Shape[] availableShapes;
    private final Map<String, Shape> nodeTypeShapeMap = new TreeMap<>();
    private final Map<String, String> nodeTypeColorMap = new TreeMap<>();

    private List<String> tagCandidates;
    /** Distinct-value counts for each entry in {@link #tagCandidates}, parallel list. */
    private List<Integer> tagCandidateCounts;
    private String currentTagProperty;
    private Map<String, String> tagColorMap = new LinkedHashMap<>();

    public GraphConfigurationDialog(Shell parent, SwitchingViewer viewer, GraphData data) {
        super(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        this.viewer = viewer;
        this.data = data;
        this.engine = (viewer != null) ? viewer.getEngine() : GraphEngine.CYTOSCAPE;
    }

    /**
     * Static, testable discovery helpers. We pull these out of the dialog
     * class so tests can drive them without spinning up an SWT Shell.
     */
    public static final class Discovery {

        private Discovery() {}

        /**
         * Whitelisted tag property names, in the order the dialog surfaces
         * them. Re-exported so the dialog and the test agree.
         */
        static List<String> tagWhitelist() {
            return TAG_PROPERTY_WHITELIST;
        }

        static int maxTagValues() { return MAX_TAG_VALUES; }

        static Set<String> reservedProps() { return RESERVED_PROPS; }

        /**
         * @return the property name the dialog uses as the Node-Type key.
         *         Defaults to {@code "_nodeType_"} — the convention used by
         *         graph exports like TVERS-Usage.gml where every node has an
         *         explicit {@code _nodeType_} property identifying its
         *         conceptual role ("Class", "BatchReader", ...).
         */
        static String nodeTypeProperty() { return "_nodeType_"; }

        /**
         * @return sorted distinct non-null values of the {@code _nodeType_}
         *         property across {@code data}'s nodes. When the property is
         *         absent on every node we fall back to the first label of each
         *         node so the dialog remains useful for graphs whose nodes
         *         only carry labels (e.g. {@code GraphNode} instances built
         *         in code without a {@code _nodeType_} property).
         */
        static List<String> nodeTypeValues(GraphData data) {
            if (data == null || data.getNodes().isEmpty()) return List.of();
            Set<String> set = new TreeSet<>();
            String prop = nodeTypeProperty();
            boolean hasExplicitType = false;
            for (GraphNode n : data.getNodes()) {
                Object v = n.getProperties().get(prop);
                if (v != null) {
                    String sv = String.valueOf(v);
                    if (!sv.isEmpty()) {
                        set.add(sv);
                        hasExplicitType = true;
                    }
                }
            }
            if (hasExplicitType) return new ArrayList<>(set);
            // Fallback: synthesize nodeType from the primary label so older
            // / hand-built graphs without a `_nodeType_` property still work.
            for (GraphNode n : data.getNodes()) {
                String lbl = n.getLabels().isEmpty() ? null : n.getLabels().get(0);
                if (lbl != null && !lbl.isEmpty()) set.add(lbl);
            }
            return new ArrayList<>(set);
        }

        /**
         * @return sorted distinct non-null values of {@code property} across
         *         the graph, or an empty list when the property has no values.
         */
        static List<String> distinctValues(GraphData data, String property) {
            if (data == null || property == null) return List.of();
            Set<String> seen = new TreeSet<>();
            for (GraphNode n : data.getNodes()) {
                Object v = n.getProperties().get(property);
                if (v == null) continue;
                seen.add(String.valueOf(v));
            }
            return new ArrayList<>(seen);
        }

        /** Distinct-value count for a property. */
        static int distinctCount(GraphData data, String property) {
            return distinctValues(data, property).size();
        }

        /**
         * @return the property names (from the whitelist) that the dialog
         *         should offer in the Tags combo, in whitelist order, when
         *         each has between 2 and {@link #MAX_TAG_VALUES} distinct values.
         */
        static List<String> tagCandidates(GraphData data) {
            if (data == null || data.getNodes().isEmpty()) return List.of();
            for (String prop : TAG_PROPERTY_WHITELIST) {
                if (RESERVED_PROPS.contains(prop)) continue;
                // Discovery helper is the entry point for both whitelist filtering
                // and value-count filtering — the original logic walks every
                // property on every node once to build counts, but the whitelist
                // restricts the candidate space, so we can shortcut to the
                // whitelist and let distinctValues do the count.
            }
            List<String> out = new ArrayList<>();
            for (String prop : TAG_PROPERTY_WHITELIST) {
                int size = distinctCount(data, prop);
                if (size >= 2 && size <= MAX_TAG_VALUES) {
                    out.add(prop);
                }
            }
            return out;
        }

        // Public accessors so tests in other packages can exercise the
        // discovery logic without subclassing or reflection.
        public static List<String> publicNodeTypeValues(GraphData data) {
            return nodeTypeValues(data);
        }

        public static List<String> publicDistinctValues(GraphData data, String property) {
            return distinctValues(data, property);
        }

        public static int publicDistinctCount(GraphData data, String property) {
            return distinctCount(data, property);
        }

        public static List<String> publicTagCandidates(GraphData data) {
            return tagCandidates(data);
        }
    }

    public int open() {
        Shell parent = getParent();
        shell = new Shell(parent, getStyle());
        shell.setText("Graph Configuration");
        shell.setLayout(new GridLayout(2, false));

        /* ---- Node Type Visualization section ---- */
        Label sectionNodeType = new Label(shell, SWT.NONE);
        sectionNodeType.setText("Node Type Visualization:");
        sectionNodeType.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        nodeTypeValues = discoverNodeTypeValues();
        if (nodeTypeValues.size() <= 1) {
            hideNodeTypeSection(sectionNodeType);
        } else {
            buildNodeTypeSection();
        }

        /* ---- Tag Visualization section ---- */
        Label sectionTag = new Label(shell, SWT.NONE);
        sectionTag.setText("Tag Visualization:");
        sectionTag.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        tagCandidates = discoverTagCandidates();
        if (tagCandidates.isEmpty()) {
            hideTagSection(sectionTag);
        } else {
            buildTagSection();
        }

        /* ---- Leiden Clustering section ---- */
        Label sectionLeiden = new Label(shell, SWT.NONE);
        sectionLeiden.setText("Clustering:");
        sectionLeiden.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        leidenApplyButton = new Button(shell, SWT.PUSH);
        leidenApplyButton.setText("Apply Leiden Clustering");
        leidenApplyButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        leidenApplyButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                applyLeidenClustering();
            }
        });

        leidenStatus = new Label(shell, SWT.NONE);
        leidenStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        /* ---- Legend (optional) section ---- */
        Label sectionLegend = new Label(shell, SWT.NONE);
        sectionLegend.setText("Legend (optional):");
        sectionLegend.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        buildLegendSection();

        /* ---- Close ---- */
        closeButton = new Button(shell, SWT.PUSH);
        closeButton.setText("Close");
        closeButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
        closeButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                shell.close();
            }
        });

        pushNodeConfig();
        rebuildNodeTypeTable();
        rebuildTagTable();

        shell.setSize(640, 760);
        shell.open();
        Display display = parent.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return SWT.OK;
    }

    /* ============================================================== */
    /*  Node Type Visualization                                        */
    /* ============================================================== */

    private void hideNodeTypeSection(Label sectionLabel) {
        String values = (nodeTypeValues == null || nodeTypeValues.isEmpty())
                ? ""
                : " Werte: " + String.join(", ", nodeTypeValues);
        sectionLabel.setText("Node Type Visualization: (≤ 1 _nodeType_ im Graphen — Mapping nicht nötig."
                + values + ")");
        sectionLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        nodeTypeHint = new Label(shell, SWT.NONE);
        nodeTypeHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        nodeTypeMode = null;
    }

    private void buildNodeTypeSection() {
        availableShapes = engine == GraphEngine.VIS_NETWORK
                ? Shape.valuesForVisNetwork()
                : Shape.valuesForCytoscape();

        nodeTypeMode = NodeTypeMode.SHAPE;
        nodeTypeShapeMap.clear();
        nodeTypeColorMap.clear();
        NodeConfig existing = (viewer != null) ? viewer.getNodeConfig() : null;
        for (int i = 0; i < nodeTypeValues.size(); i++) {
            String t = nodeTypeValues.get(i);
            Shape s = availableShapes[i % availableShapes.length];
            nodeTypeShapeMap.put(t, s);
            String c = (existing == null) ? defaultPaletteColor(i)
                    : existing.colorForLabel(t);
            if (c == null) c = defaultPaletteColor(i);
            nodeTypeColorMap.put(t, c);
        }

        Composite modeRow = new Composite(shell, SWT.NONE);
        modeRow.setLayout(new GridLayout(2, false));
        modeRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label modeLabel = new Label(modeRow, SWT.NONE);
        modeLabel.setText("Mode:");
        nodeTypeModeCombo = new Combo(modeRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        nodeTypeModeCombo.setItems(new String[] { "Shape", "Color" });
        nodeTypeModeCombo.select(0);
        nodeTypeModeCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                int idx = nodeTypeModeCombo.getSelectionIndex();
                nodeTypeMode = (idx == 1) ? NodeTypeMode.COLOR : NodeTypeMode.SHAPE;
                rebuildNodeTypeTable();
                pushNodeConfig();
            }
        });

        nodeTypeTable = new Table(shell,
                SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        nodeTypeTable.setHeaderVisible(true);
        nodeTypeTable.setLinesVisible(true);
        GridData ndL = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        ndL.minimumHeight = 160;
        nodeTypeTable.setLayoutData(ndL);
        TableColumn colType = new TableColumn(nodeTypeTable, SWT.LEFT);
        colType.setText("_nodeType_");
        colType.setWidth(160);
        TableColumn colMap = new TableColumn(nodeTypeTable, SWT.LEFT);
        colMap.setText("Visualization");
        colMap.setWidth(260);

        nodeTypeHint = new Label(shell, SWT.NONE);
        nodeTypeHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void rebuildNodeTypeTable() {
        if (nodeTypeTable == null) return;
        // Dispose any previously-attached editors so re-renders don't leak.
        TableItem[] oldItems = nodeTypeTable.getItems();
        for (TableItem old : oldItems) {
            disposeEditor(old);
        }
        nodeTypeTable.removeAll();
        List<String> types = nodeTypeValues;
        if (types == null || types.isEmpty()) {
            if (nodeTypeHint != null)
                nodeTypeHint.setText("Keine _nodeType_-Werte im Graphen.");
            return;
        }
        if (nodeTypeHint != null) {
            String values = String.join(", ", types);
            String engineNote = (engine == GraphEngine.VIS_NETWORK
                    ? " (vis-network: nur Shapes mit visNetworkName verfügbar)"
                    : " (cytoscape.js: alle in Cytoscape verfügbaren Shapes)");
            nodeTypeHint.setText("Modus: " + nodeTypeMode
                    + " | Werte (" + types.size() + "): " + values + engineNote);
        }

        for (int i = 0; i < types.size(); i++) {
            String typeName = types.get(i);
            TableItem item = new TableItem(nodeTypeTable, SWT.NONE);
            item.setText(0, typeName);
            if (nodeTypeMode == NodeTypeMode.SHAPE) {
                Shape s = nodeTypeShapeMap.get(typeName);
                if (s == null) s = availableShapes[i % availableShapes.length];
                nodeTypeShapeMap.put(typeName, s);
                item.setText(1, s.name().toLowerCase());
            } else {
                String c = nodeTypeColorMap.get(typeName);
                if (c == null) c = defaultPaletteColor(i);
                nodeTypeColorMap.put(typeName, c);
                item.setText(1, c);
            }
            mountNodeTypeCellEditor(item);
        }
    }

    private void mountNodeTypeCellEditor(TableItem item) {
        String typeName = item.getText(0);
        TableEditor ed = new TableEditor(nodeTypeTable);
        ed.grabHorizontal = true;
        ed.grabVertical = true;
        ed.horizontalAlignment = SWT.FILL;
        ed.verticalAlignment = SWT.FILL;
        if (nodeTypeMode == NodeTypeMode.SHAPE) {
            Combo c = new Combo(nodeTypeTable, SWT.READ_ONLY | SWT.DROP_DOWN);
            String[] shapeNames = shapeDisplayNames(availableShapes);
            c.setItems(shapeNames);
            Shape current = nodeTypeShapeMap.get(typeName);
            for (int k = 0; k < availableShapes.length; k++) {
                if (availableShapes[k] == current) { c.select(k); break; }
            }
            c.addSelectionListener(new SelectionAdapter() {
                @Override public void widgetSelected(SelectionEvent e) {
                    int idx = c.getSelectionIndex();
                    if (idx >= 0 && idx < availableShapes.length) {
                        nodeTypeShapeMap.put(typeName, availableShapes[idx]);
                        item.setText(1, availableShapes[idx].name().toLowerCase());
                        pushNodeConfig();
                    }
                }
            });
            ed.setEditor(c, item, 1);
        } else {
            ColorPicker picker = new ColorPicker(nodeTypeTable,
                    nodeTypeColorMap.get(typeName), picked -> {
                        nodeTypeColorMap.put(typeName, picked);
                        item.setText(1, picked);
                        pushNodeConfig();
                    });
            ed.setEditor(picker, item, 1);
        }
        item.setData("editor", ed);
    }

    private static void disposeEditor(TableItem item) {
        Object raw = item.getData("editor");
        if (raw instanceof TableEditor ed) {
            Control ctl = ed.getEditor();
            if (ctl != null && !ctl.isDisposed()) ctl.dispose();
            ed.dispose();
            item.setData("editor", null);
        }
    }

    /* ============================================================== */
    /*  Tag Visualization                                              */
    /* ============================================================== */

    private void hideTagSection(Label sectionLabel) {
        sectionLabel.setText("Tag Visualization: (keine passenden Tag-Properties im Graphen gefunden)");
        sectionLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        tagHint = new Label(shell, SWT.NONE);
        tagHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        currentTagProperty = null;
        if (applyTagColorsButton != null) {
            applyTagColorsButton.setEnabled(false);
        }
    }

    private Button applyTagColorsButton;

    private void buildTagSection() {
        // Build the count-parallel list once so the combo labels are stable
        // even if the user later switches node-type modes.
        tagCandidateCounts = new ArrayList<>();
        for (String prop : tagCandidates) {
            tagCandidateCounts.add(distinctValuesFor(prop));
        }

        Composite propRow = new Composite(shell, SWT.NONE);
        propRow.setLayout(new GridLayout(2, false));
        propRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label lblProp = new Label(propRow, SWT.NONE);
        lblProp.setText("Tags:");
        tagPropertyCombo = new Combo(propRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        tagPropertyCombo.setItems(formatTagLabels(tagCandidates, tagCandidateCounts));
        tagPropertyCombo.select(0);
        currentTagProperty = tagCandidates.get(0);
        tagColorMap = new TreeMap<>(defaultTagColorsFor(currentTagProperty));
        tagPropertyCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                int idx = tagPropertyCombo.getSelectionIndex();
                if (idx < 0) return;
                currentTagProperty = tagCandidates.get(idx);
                tagColorMap = new TreeMap<>(defaultTagColorsFor(currentTagProperty));
                rebuildTagTable();
                pushNodeConfig();
            }
        });

        tagValueTable = new Table(shell, SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        tagValueTable.setHeaderVisible(true);
        tagValueTable.setLinesVisible(true);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        td.minimumHeight = 140;
        tagValueTable.setLayoutData(td);
        TableColumn colVal = new TableColumn(tagValueTable, SWT.LEFT);
        colVal.setText("Value");
        colVal.setWidth(200);
        TableColumn colCol = new TableColumn(tagValueTable, SWT.LEFT);
        colCol.setText("Color");
        colCol.setWidth(120);

        tagHint = new Label(shell, SWT.NONE);
        tagHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // Apply button: enabled because we already proved tagCandidates is non-empty
        // by entering buildTagSection() at all.
        applyTagColorsButton = new Button(shell, SWT.PUSH);
        applyTagColorsButton.setText("Apply Tag Colors");
        applyTagColorsButton.setEnabled(true);
        applyTagColorsButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
        applyTagColorsButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                applyTagColors();
            }
        });
    }

    /** Format the combo entries as "name: count". */
    private static String[] formatTagLabels(List<String> names, List<Integer> counts) {
        String[] out = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            out[i] = names.get(i) + ": " + counts.get(i);
        }
        return out;
    }

    private void rebuildTagTable() {
        if (tagValueTable == null) return;
        TableItem[] oldItems = tagValueTable.getItems();
        for (TableItem old : oldItems) disposeEditor(old);
        tagValueTable.removeAll();
        if (currentTagProperty == null) {
            if (tagHint != null) tagHint.setText("");
            return;
        }
        if (tagHint != null) {
            tagHint.setText("Tag: " + currentTagProperty + " ("
                    + tagColorMap.size() + " Werte)");
        }
        for (Map.Entry<String, String> e : tagColorMap.entrySet()) {
            TableItem item = new TableItem(tagValueTable, SWT.NONE);
            item.setText(0, e.getKey());
            item.setText(1, e.getValue());

            TableEditor ed = new TableEditor(tagValueTable);
            ed.grabHorizontal = true;
            ed.grabVertical = true;
            ed.horizontalAlignment = SWT.FILL;
            ed.verticalAlignment = SWT.FILL;
            String valueKey = e.getKey();
            ColorPicker picker = new ColorPicker(tagValueTable, e.getValue(), picked -> {
                tagColorMap.put(valueKey, picked);
                item.setText(1, picked);
                pushNodeConfig();
            });
            ed.setEditor(picker, item, 1);
            item.setData("editor", ed);
        }
    }

    private Map<String, String> defaultTagColorsFor(String prop) {
        List<String> sortedValues = sortedValuesForProperty(prop);
        return ColorScale.interpolate(sortedValues.size()).asMap(sortedValues);
    }

    private List<String> sortedValuesForProperty(String prop) {
        if (prop == null) return List.of();
        Set<String> seen = new TreeSet<>();
        if (data != null) {
            for (GraphNode n : data.getNodes()) {
                Object v = n.getProperties().get(prop);
                if (v == null) continue;
                seen.add(String.valueOf(v));
            }
        }
        return new ArrayList<>(seen);
    }

    /* ============================================================== */
    /*  Tag mapping                                                    */
    /* ============================================================== */

    /**
     * Apply the currently-configured tag colors. The push happens
     * immediately via {@link SwitchingViewer#setNodeConfig(NodeConfig)}.
     * The button stays enabled for as long as the combo offers at least
     * one tag property candidate (i.e. we are in {@link #buildTagSection}).
     */
    private void applyTagColors() {
        if (viewer == null || currentTagProperty == null) return;
        if (tagColorMap == null || tagColorMap.isEmpty()) {
            if (tagHint != null) {
                tagHint.setText("Apply Tag Colors: keine Werte für " + currentTagProperty);
            }
            return;
        }
        // Push the config — same call path as every ColorPicker edit, but
        // isolated here so the user gets explicit feedback in the hint label.
        pushNodeConfig();
        if (tagHint != null) {
            tagHint.setText("Apply Tag Colors: " + currentTagProperty + " → "
                    + tagColorMap.size() + " Farben gesetzt.");
        }
    }

    /* ============================================================== */
    /*  Discovery                                                       */
    /* ============================================================== */

    private List<String> discoverNodeTypeValues() {
        return Discovery.nodeTypeValues(data);
    }

    private List<String> discoverTagCandidates() {
        return Discovery.tagCandidates(data);
    }

    /** Distinct non-null value count for a property across the current graph. */
    int distinctValuesFor(String property) {
        return Discovery.distinctCount(data, property);
    }

    /* ============================================================== */
    /*  Mapping pushes to the viewer                                    */
    /* ============================================================== */

    private void pushNodeConfig() {
        if (viewer == null) return;
        NodeConfig cfg = viewer.getNodeConfig();
        if (cfg == null) cfg = NodeConfig.defaults();
        NodeConfig.Builder b = cfg.toBuilder();

        // NodeType mapping.
        b.labelColors(new LinkedHashMap<>());
        b.labelShapes(new LinkedHashMap<>());
        if (nodeTypeValues != null && nodeTypeValues.size() > 1 && nodeTypeMode != null) {
            if (nodeTypeMode == NodeTypeMode.SHAPE) {
                Map<String, String> shapeMap = new LinkedHashMap<>();
                for (Map.Entry<String, Shape> e : nodeTypeShapeMap.entrySet()) {
                    Shape s = e.getValue();
                    if (s == null || s.cytoscapeName() == null) {
                        b.labelColor(e.getKey(), defaultPaletteColor(
                                nodeTypeValues.indexOf(e.getKey())));
                    } else {
                        shapeMap.put(e.getKey(), s.cytoscapeName());
                    }
                }
                b.labelShapes(shapeMap);
            } else {
                for (Map.Entry<String, String> e : nodeTypeColorMap.entrySet()) {
                    b.labelColor(e.getKey(), e.getValue());
                }
            }
        }

        // Global tag mapping: the currently selected tag property is pushed
        // as a global override so every node whose property matches — not
        // just nodes of the active primary label — receives the color.
        // The Cytoscape selector is `node[property = "value"]` and is emitted
        // by buildStyleFromConfig() in cytoscape-viewer.js.
        String prop = currentTagProperty;
        if (prop != null && !tagColorMap.isEmpty()) {
            Map<String, String> byValue = new LinkedHashMap<>(tagColorMap);
            b.globalTagColors(Map.of(prop, byValue));
        } else {
            b.globalTagColors(Map.of());
        }

        viewer.setNodeConfig(b.build());

        // Tag / NodeType colors changed — refresh and re-push the legend so
        // the panel reflects the latest color mapping without requiring a
        // separate "Apply to Viewer" click.
        if (legendEnableCheck != null && legendEnableCheck.getSelection()) {
            refreshLegendSectionEnabled();
            rebuildLegendPreview();
            pushLegend();
        }
    }

    /* ============================================================== */
    /*  Palette helpers                                                 */
    /* ============================================================== */

    private static String defaultPaletteColor(int index) {
        String[] p = { "#4A90E2", "#E74C3C", "#F1C40F", "#27AE60", "#9B59B6", "#E67E22" };
        return p[Math.floorMod(index, p.length)];
    }

    private static String[] shapeDisplayNames(Shape[] shapes) {
        String[] out = new String[shapes.length];
        for (int i = 0; i < shapes.length; i++) {
            out[i] = humanizeName(shapes[i].name());
        }
        return out;
    }

    private static String humanizeName(String enumName) {
        if (enumName == null || enumName.isEmpty()) return enumName;
        StringBuilder sb = new StringBuilder(enumName.length() + 4);
        sb.append(Character.toUpperCase(enumName.charAt(0)));
        for (int i = 1; i < enumName.length(); i++) {
            char ch = enumName.charAt(i);
            char prev = enumName.charAt(i - 1);
            if (ch == '_') {
                sb.append('-');
            } else if (Character.isUpperCase(ch) && Character.isLowerCase(prev)) {
                sb.append('-').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /* ============================================================== */
    /*  Leiden clustering                                              */
    /* ============================================================== */

    private void applyLeidenClustering() {
        if (viewer == null || data == null) return;
        leidenStatus.setText("Clustering wird berechnet ...");
        Map<String, String> colors = LeidenColors.compute(data);
        if (colors == null || colors.isEmpty()) {
            leidenStatus.setText("Clustering nicht möglich (keine gewichteten Edges).");
            return;
        }
        viewer.setLeidenClusterColors(colors);
        long distinct = colors.values().stream().distinct().count();
        leidenStatus.setText("Clustering angewendet: " + distinct + " Communities.");
        // Refresh the legend preview + push it so the panel updates
        // immediately with the new cluster counts and the new colors.
        if (legendEnableCheck != null && legendEnableCheck.getSelection()) {
            rebuildLegendPreview();
            pushLegend();
        }
    }

    /* ============================================================== */
    /*  Legend (optional)                                              */
    /* ============================================================== */

    /**
     * Build the legend section. Always shown — but the controls start
     * disabled when the graph has no nodeTypes, no tag mapping, and no
     * Leiden colors yet (nothing to legend up).
     */
    private void buildLegendSection() {
        legendEnableCheck = new Button(shell, SWT.CHECK);
        legendEnableCheck.setText("Enable Legend");
        legendEnableCheck.setSelection(false);
        GridData enGD = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        legendEnableCheck.setLayoutData(enGD);
        legendEnableCheck.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                refreshLegendSectionEnabled();
                rebuildLegendPreview();
                // Auto-apply: the user shouldn't need a separate "Apply to
                // Viewer" click — enabling the checkbox should immediately
                // surface the legend in the canvas.
                pushLegend();
            }
        });

        legendShowCheck = new Button(shell, SWT.CHECK);
        legendShowCheck.setText("Show in viewer");
        legendShowCheck.setSelection(true);
        legendShowCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        legendShowCheck.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                pushLegend();
            }
        });

        Composite srcRow = new Composite(shell, SWT.NONE);
        srcRow.setLayout(new GridLayout(2, false));
        srcRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label srcLabel = new Label(srcRow, SWT.NONE);
        srcLabel.setText("Source:");
        legendSourceCombo = new Combo(srcRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        legendSourceCombo.setItems(new String[] {
                "Combined (Tag → Cluster → NodeType)",
                "Tag Values only",
                "Leiden Clusters only",
                "Node Types only"
        });
        legendSourceCombo.select(0);
        legendSource = LegendSource.COMBINED;
        legendSourceCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                legendSource = LegendSource.values()[legendSourceCombo.getSelectionIndex()];
                rebuildLegendPreview();
                // Source change updates the legend contents — auto-push.
                pushLegend();
            }
        });

        legendPreviewTable = new Table(shell,
                SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        legendPreviewTable.setHeaderVisible(true);
        legendPreviewTable.setLinesVisible(true);
        GridData lgGD = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        lgGD.minimumHeight = 120;
        legendPreviewTable.setLayoutData(lgGD);
        TableColumn colCol = new TableColumn(legendPreviewTable, SWT.LEFT);
        colCol.setText("Color");
        colCol.setWidth(80);
        TableColumn colLab = new TableColumn(legendPreviewTable, SWT.LEFT);
        colLab.setText("Label");
        colLab.setWidth(260);
        TableColumn colCnt = new TableColumn(legendPreviewTable, SWT.RIGHT);
        colCnt.setText("Count");
        colCnt.setWidth(60);

        legendHint = new Label(shell, SWT.NONE);
        legendHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Composite btnRow = new Composite(shell, SWT.NONE);
        btnRow.setLayout(new GridLayout(2, true));
        btnRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        legendApplyButton = new Button(btnRow, SWT.PUSH);
        legendApplyButton.setText("Apply to Viewer");
        legendApplyButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        legendApplyButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                rebuildLegendPreview();
                pushLegend();
            }
        });
        legendClearButton = new Button(btnRow, SWT.PUSH);
        legendClearButton.setText("Clear");
        legendClearButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        legendClearButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                legendPreview = List.of();
                rebuildLegendPreviewTable();
                if (viewer != null) viewer.clearLegend();
                if (legendHint != null) legendHint.setText("Legend: geleert.");
            }
        });

        refreshLegendSectionEnabled();
    }

    /**
     * Enable / disable the legend source combo + buttons depending on
     * whether there's anything to show. The checkbox stays editable so
     * the user can pre-arm the section before applying a clustering.
     */
    private void refreshLegendSectionEnabled() {
        boolean enabled = legendEnableCheck != null && legendEnableCheck.getSelection();
        boolean hasAnySource =
                !nodeTypeValues.isEmpty() || hasGlobalTagColors() || hasLeidenColors();
        if (legendShowCheck != null) legendShowCheck.setEnabled(enabled);
        if (legendSourceCombo != null) legendSourceCombo.setEnabled(enabled);
        if (legendPreviewTable != null) legendPreviewTable.setEnabled(enabled);
        if (legendApplyButton != null) legendApplyButton.setEnabled(enabled && hasAnySource);
        if (legendClearButton != null) legendClearButton.setEnabled(enabled);
        if (legendHint != null && !enabled) {
            legendHint.setText("Legend: deaktiviert (Checkbox anhaken, dann Apply to Viewer).");
        } else if (legendHint != null && !hasAnySource) {
            legendHint.setText("Legend: keine Daten vorhanden — bitte erst Tag-/Cluster-/NodeType-Mapping setzen.");
        }
    }

    private boolean hasGlobalTagColors() {
        return currentTagProperty != null && !tagColorMap.isEmpty();
    }

    private boolean hasLeidenColors() {
        if (viewer == null) return false;
        Map<String, String> map = viewer.getLeidenClusterColors();
        return map != null && !map.isEmpty();
    }

    /** Compute the legend preview from the current dialog state + viewer. */
    private void rebuildLegendPreview() {
        if (legendEnableCheck == null || !legendEnableCheck.getSelection()) {
            legendPreview = List.of();
            rebuildLegendPreviewTable();
            return;
        }
        NodeConfig cfg = viewer != null ? viewer.getNodeConfig() : null;
        if (cfg == null) cfg = NodeConfig.defaults();
        Map<String, String> leiden = viewer == null ? Map.of() : viewer.getLeidenClusterColors();
        switch (legendSource) {
            case COMBINED:
                legendPreview = LegendBuilder.combined(data, cfg, leiden);
                break;
            case TAG_VALUES:
                legendPreview = LegendBuilder.fromTagValues(data, cfg);
                break;
            case LEIDEN_CLUSTERS:
                legendPreview = LegendBuilder.fromLeidenClusters(data, leiden);
                break;
            case NODE_TYPES:
                legendPreview = LegendBuilder.fromNodeTypes(data, cfg);
                break;
            default:
                legendPreview = List.of();
        }
        rebuildLegendPreviewTable();
    }

    /** Repaint the preview table from {@link #legendPreview}. */
    private void rebuildLegendPreviewTable() {
        if (legendPreviewTable == null) return;
        legendPreviewTable.removeAll();
        if (legendPreview.isEmpty()) {
            if (legendHint != null && legendEnableCheck.getSelection()) {
                legendHint.setText("Legend: keine Einträge für Quelle '" + legendSource
                        + "' — Apply überspringen.");
            }
            return;
        }
        if (legendHint != null) {
            legendHint.setText("Legend: " + legendPreview.size() + " Einträge — Apply to Viewer pusht sie an "
                    + (engine == GraphEngine.VIS_NETWORK ? "vis-network" : "Cytoscape") + ".");
        }
        for (LegendEntry e : legendPreview) {
            TableItem item = new TableItem(legendPreviewTable, SWT.NONE);
            item.setText(0, e.colorHex());
            item.setText(1, e.label());
            item.setText(2, Integer.toString(e.count()));
        }
    }

    /** Push the current legend state to the active engine. */
    private void pushLegend() {
        if (viewer == null) return;
        boolean enabled = legendEnableCheck != null && legendEnableCheck.getSelection()
                && legendShowCheck != null && legendShowCheck.getSelection();
        viewer.setLegend(legendPreview, enabled);
        if (legendHint != null) {
            if (!enabled) {
                legendHint.setText("Legend: im Viewer ausgeblendet (Show in viewer ist aus).");
            } else if (legendPreview.isEmpty()) {
                legendHint.setText("Legend: keine Einträge — Panel bleibt verborgen.");
            } else {
                legendHint.setText("Legend: " + legendPreview.size() + " Einträge an Viewer gepusht.");
            }
        }
    }
}
