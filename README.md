# cytoscape-graph-viewer

Spring Boot + Eclipse RAP 4.4 Webanwendung zur Visualisierung von Graphen
mit **Cytoscape.js + cytoscape-fcose** als Default-Renderer und **vis-network**
als zweite, zur Laufzeit umschaltbare Engine.

Die Datenebene (Nodes, Relationships, Properties) ist engine-agnostisch und
wird vom vis-graph-Projekt adaptiert. Der Beispielgraph
(`export (4).csv`, 1010 gewichtete Kanten zwischen 151 Knoten) wird über die
REST-Schnittstelle `GET /api/sample-graph` ausgeliefert und im Browser mit
**Cytoscape fcose** visualisiert; die Edge-Längen werden logarithmisch aus
dem `weight`-Attribut berechnet (`idealEdgeLength = 50 + 30·log(weight)`).

## URLs

| URL                          | Inhalt                                                                 |
|------------------------------|------------------------------------------------------------------------|
| `http://localhost:8085/graph`| RAP-Entry-Point mit Cytoscape-Default-View (CSV-Beispielgraph, fcose)  |
| `GET /api/sample-graph`      | Liefert JSON: `elements`, `cytoscapeLayoutOptions`, `stats`           |
| `/cytoscape-viewer.html`     | Eingebettetes HTML des Cytoscape-Viewers (für Debugging)              |
| `/cytoscape/*`               | Statische Cytoscape-JS-Bundles + `cytoscape-viewer.js`                 |
| `/cytoscape/cytoscape-leiden-worker.js` | Web-Worker für Leiden-Clusteranalyse im Browser            |

## Engines

Das `SwitchingViewer`-Composite beherbergt zur Laufzeit entweder einen
`GraphViewer` (vis-network) oder einen `CytoscapeViewer` (Cytoscape.js).
Umschaltung erfolgt über die Toolbar-Combobox **Engine: Vis / Cytoscape**;
Daten, NodeConfig und Layout bleiben erhalten.

## Toolbar (`GraphViewerControlBar`)

Am unteren Bildschirmrand:

| Control       | Bedeutung                                                                  |
|---------------|----------------------------------------------------------------------------|
| Engine        | Vis oder Cytoscape — zerstört den aktuellen Viewer und instanziiert frisch |
| Layout        | Engine-spezifisch: vis ⇒ Force-Atlas-2D, Barnes-Hut, …; cytoscape ⇒ fcose, cose, dagre, breadthfirst, … |
| Physics       | nur sichtbar/aktiv im Vis-Modus                                            |
| Auto-Fit      | nur sichtbar/aktiv im Vis-Modus                                            |
| Fit           | `fitToScreen()` auf den aktiven Viewer                                     |
| Graph Configuration… | öffnet den `GraphConfigurationDialog` (engine-agnostisch)          |

## Graph Configuration Dialog

`GraphConfigurationDialog` ist engine-agnostisch und hat drei Sektionen:

1. **Node Type Visualization** — Combobox `Shape | Color`.
   Die ersten 5 distincten `nodeType`-Werte bekommen je einen Eintrag aus
   der 5er-Shape- bzw. 5er-Color-Palette. Bei mehr als 5 Typen wird ein
   Hinweis angezeigt und kein Mapping vorgenommen.

2. **Tag Visualization** — bei ≤ 20 distincten Tag-Werten wird jedem Wert
   eine Farbe aus einer 20er-Palette zugewiesen und der Node-Type hat dann
   nur noch die Shape-Option.

3. **Clustering** — Button „Apply Leiden Clustering" startet eine
   Louvain-/Leiden-artige Modularity-Optimierung (Java-seitig im Dialog,
   Web-Worker `cytoscape-leiden-worker.js` als alternative Browser-Lösung)
   und färbt die Nodes nach Community-Zugehörigkeit ein.

   **Cluster-Layout-Strategie (nur Cytoscape)**: zusätzlich zum Recolor
   wird in der Cytoscape-Engine die 3-Säulen-Strategie aus
   `Cluster-Layout.md` aktiviert:
   - **Compound-Cluster-Parents** (`injectClusterParents` in
     `cytoscape-viewer.js`) — pro Community wird ein
     `node[?isCluster]`-Container mit gestrichelter Border und
     Hintergrund-Fill in der Community-Farbe eingefügt; Member-Nodes
     bekommen `data.parent = cluster_<idx>`.
   - **fcose mit Compound-Kräften** (`ClusterLayoutOptions`) —
     `nestingFactor`, `gravityRangeCompound`, `gravityCompound`,
     `nodeRepulsion`, `idealInterClusterEdgeLength` und
     `randomize=false` (überschrieben vom JS-Bridge, damit die
     preseeded Cluster-Zentren erhalten bleiben).
   - **Log-gewichtete Federn** — `idealEdgeLength` und
     `edgeElasticity` lesen `data('logWeight')` (= `ln(weight+1)`,
     vorgerechnet in `GraphRelationship.toCytoscapeEdge()`), so dass
     extreme Kantengewichte (1…10 000) gestaucht werden.

   **Pre-Layout Edge-Filter (Cluster-Layout.md §5)** — Combo
   `Min. ln(weight+1) fürs Layout` im Clustering-Abschnitt. Edges
   unterhalb des Schwellwerts (Default `2.0`, entspricht
   `weight ≥ e²−1 ≈ 6.4`) werden per `partitionEdgesForLayout` aus
   `cy.add()` herausgehalten, damit fcose die Cluster-Struktur ohne
   Hintergrundrauschen berechnet. Die schwachen Edges werden via
   `restoreHeldBackEdges()` nach dem `layoutstop`-Event wieder
   hinzugefügt — sie folgen den vom Layout gesetzten Knoten-Positionen
   und verzerren die Cluster-Separation nicht. Auswahl `aus`
   deaktiviert den Filter (Status quo, alle Edges im Layout).
   Das Status-Label zeigt die Wirkung mit „X/Y Edges im Layout".

   **Sqrt-basierte Edge-Dicke** — `clusterEdgeStyle()` skaliert die
   Kantenbreite sub-linear
   `0.6 + 0.9 · sqrt(min(max(logWeight, 0), 4))` → 0.6…2.4 px.
   Ersetzt die alte `mapData(logWeight, 1, 10, 1.5, 9)`-Skalierung, die
   wegen Cytoscapes Out-of-Range-Clamping 36 % der export.csv-Edges an
   der Untergrenze festgenagelt hat.

   **Cluster-Edges-Tabelle** — Beim Click auf einen Legend-Eintrag
   blendet sich zusätzlich zur Hervorhebung eine Tabelle mit allen
   Edges des Clusters ein (rechts oben, gestapelt unter dem Legend-
   Panel). Spalten: **From** (Source-Node-Name), **Weight**, **To**
   (Target-Node-Name). Intra-Cluster-Edges zuerst, dann nach Weight
   absteigend; Brücken-Edges (zwischen Cluster und Außenwelt) sind
   kursiv markiert (`cgv-edge-bridge` CSS-Klasse). Click auf eine
   Tabellenzeile ruft den Java-`relListeners`-Callback auf (über den
   normalen Cytoscape-`tap edge`-Pfad: `edge.select()` → `tap edge`-
   Handler → `cgv_notifyRelationshipSelected` → Java
   `RelationshipSelectionListener.relationshipSelected(...)`). Die
   Tabelle verschwindet, sobald das Highlight gelöscht wird (zweiter
   Click auf gleichen Legend-Eintrag, anderer Legend-Eintrag, oder
   Background-Tap).

   vis-network hat keine Compound-Node-Semantik; dort werden die
   Community-Farben gesetzt, aber kein Cluster-Layout ausgelöst. Der
   Status-Text im Dialog weist darauf hin.

4. **Legend (optional)** — Checkbox `Enable Legend` plus Combo `Source`
   (`Combined`, `Tag Values`, `Leiden Clusters`, `Node Types`). Das Panel
   erscheint oben rechts im Viewer (vis & cytoscape) und erklärt jede
   Farbe. Bei `Source = Leiden Clusters` werden Communities nach Größe
   absteigend nummeriert (`Cluster1` = größtes Cluster, `Cluster2` = …).
   Bei `Source = Combined` werden Tag-Farben vor Cluster-Farben vor
   Node-Type-Farben priorisiert; identische Hex-Werte werden
   zusammengeführt, sodass jede Farbe nur einmal erscheint.

   **Click-to-Highlight**: ein Klick auf einen Legendeneintrag dimmt alle
   Nodes, die diese Farbe *nicht* tragen (`opacity: 0.18`) und rahmt
   die Treffer mit einem farbigen Border ein. Edges zwischen zwei
   getroffenen Nodes bleiben in derselben Farbe sichtbar; alle anderen
   Edges werden gedimmt. Ein zweiter Klick auf dieselbe Farbe (oder ein
   Klick auf den leeren Hintergrund des Viewers) hebt das Highlight
   wieder auf. Implementiert in `LegendBuilder.java` /
   `data/LegendEntry.java` (Java) und `cgv_applyLegend` /
   `vgv_applyLegend` (JS-Bridges).

## Beispielgraph

Datei `export (4).csv` liegt sowohl im Root des Projekts als auch unter
`src/main/resources/sample/export.csv` (für den gebauten JAR). Sie wird vom
`SampleGraphController` aus dem Classpath gelesen und als JSON-Response
ausgeliefert:

```
GET /api/sample-graph

{
  "elements": [ { "data": { "id", "label", "nodeType", "nodeTag", "weight?", "source", "target", ... } } ],
  "cytoscapeLayoutOptions": { "name": "fcose", "idealEdgeLength": "function(e){...;return 50+30*Math.log(w);}", ... },
  "stats": { "nodes": 151, "edges": 1010, "minWeight": 0.0, "maxWeight": 3831.0 }
}
```

## Build & Run

```bash
cd cytoscape
mvn -DskipTests package
java -jar target/cytoscape-graph-viewer-0.1.0-SNAPSHOT.jar
# → http://localhost:8085/graph
```

### RAP / Jetty 12 Hinweis

Eclipse RAP 4.4 setzt für die `RWTServletContextListener` voraus, dass
`jakarta.servlet.ServletContext.getServletRegistration` implementiert ist
— eine API, die in **Jetty 12 (Jakarta EE 10)** entfernt wurde. Der
Initializer registriert den `RWTServletContextListener` deshalb explizit
**vor** dem `RWTServlet` (Reihenfolge matters) und nutzt die
Servlet-API-`addServlet("rap", RWTServlet.class)`-Variante, die Spring
Boot 3.3.5 + Jetty 12 unterstützt. Falls der Listener dennoch scheitert,
lässt sich die RAP-Schicht via `-Drap.enabled=false` deaktivieren; die
REST-Schnittstelle und die statischen Assets bleiben erreichbar.

## Architektur

```
src/main/java/de/tk/dependencyanalyse/rapui/visgraph/
├── Application.java                      # Spring-Boot-Main
├── RapApplicationConfiguration.java      # registriert Entry-Point /graph
├── RapServletInitializer.java            # Listener-vor-Servlet-Reihenfolge
├── GraphViewer.java                      # vis-network-Widget (kopiert)
├── CytoscapeViewer.java                  # Cytoscape.js-Widget
├── SwitchingViewer.java                  # Composite-Wrapper, hält eine Engine
├── GraphViewerControlBar.java            # Toolbar mit Engine-Combo
├── GraphConfigurationDialog.java         # Node-Type / Tag / Clustering UI
├── ColorPicker.java, TreeEditorProxy.java
├── data/                                 # engine-agnostische Datenklassen
│   ├── GraphData.java                   # + toCytoscapeElements()
│   ├── GraphNode.java                   # + toCytoscapeNode()
│   ├── GraphRelationship.java           # + toCytoscapeEdge()
│   ├── ColorSpec.java, Shape.java, ArrowShape.java, SmoothType.java,
│   ├── HierarchicalDirection.java, PhysicsSolver.java, TooltipBuilder.java
│   └── LayoutAlgorithm.java             # erweitert: FCOSE, COSE, …
├── internal/
│   ├── BrowserFunctions.java, BrowserScriptQueue.java, ContextMenuSnapshot.java
│   ├── VisJsBridge.java                 # vis-network-Bridge (kopiert)
│   └── CytoscapeJsBridge.java           # Cytoscape-Bridge (cgv_*)
├── callback/                             # Selection- / ContextMenu-Listener
├── engine/GraphEngine.java              # VIS_NETWORK | CYTOSCAPE
├── config/
│   ├── NodeConfig.java, NodeConfigAnalyzer.java, TagProperty.java
├── examples/CsvExampleEntryPoint.java   # Default-View /graph
└── api/SampleGraphController.java       # GET /api/sample-graph
```

```
src/main/resources/
├── application.yml
├── sample/export.csv                    # Beispielgraph
└── static/
    ├── cytoscape-viewer.html            # HTML-Wrapper für Cytoscape
    ├── cytoscape/                       # Cytoscape-JS-Bundles
    │   ├── cytoscape.min.js
    │   ├── cytoscape-fcose.js
    │   ├── layout-base.js
    │   ├── cytoscape-viewer.js          # Bridge (cgv_*)
    │   └── cytoscape-leiden-worker.js   # Web-Worker
    ├── vis-network/vis-network.min.{js,css}
    └── vis-graph/                       # vis-graph-Viewer-Bridge (vgv_*)
```

## Beobachtete Werte

- `/api/sample-graph`: 151 Nodes, 1010 Edges, Weights 0–3831.
- `fcose idealEdgeLength = 50 + 30·log(weight)` — für w=1 sind das 50 px,
  für w=100 188 px, für w=3831 ≈ 263 px.
- Leiden-/Louvain-Modularity läuft im Dialog als 3-Pass-Optimierung und
  liefert deterministische Communities (gleiche Eingabe → gleiche Farben).
