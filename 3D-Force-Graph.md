# 3D Force-Directed Graph Engine

Diese Dokumentation beschreibt die **fünfte** Rendering-Engine des
`SwitchingViewer` — **3D Force-Directed Graph** (`vasturiano/3d-force-graph@1.73.0`)
kombiniert mit **Three.js@0.160.0** und dem Layout-Solver **d3-force-3d@3.0.5**.
Die Komponenten-Wrapper-Schicht **kapsule@1.14.5** wird vom 3d-force-graph
selbst als Peer-Dependency verwendet.

Die Engine ist auf drei Designziele hin optimiert:

1. **NVL-paritätische API** — gleiche Methodennamen und Callback-Slots wie
   der NVL-Viewer, sodass der `SwitchingViewer` per `if/else if`-Kette ohne
   Sonderbehandlung zu ihm fan-out kann.
2. **WebGL-First** — das Rendering nutzt Three.js' WebGL-Renderer (Hardware-
   beschleunigt). Fehlt WebGL im Browser, zeigt der Viewer eine Inline-
   Fehlermeldung im Iframe statt eines leeren Canvas.
3. **Auto-verwaltete Color Palette** — identisch zum NVL-Pattern. Das Panel
   wird vom Bridge-Code aus `applyNodeColors` / `setLeidenClusterColors` /
   `applyGraphPalette` automatisch aufgebaut und beim `clear()` zurückgesetzt.

---

## 1. Architektur-Übersicht

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Java-Seite (Spring Boot + RAP)                                         │
│                                                                         │
│  SwitchingViewer.setGraphData(data)                                      │
│       │                                                                 │
│       │ currentEngine == THREE_FORCE_GRAPH                              │
│       ▼                                                                 │
│  ThreeForceGraphViewer (Browser)                                        │
│    │  Constructor parameters: (parent, style[, htmlResource])           │
│    │  bridge = new ThreeForceGraphJsBridge(this)                        │
│    │  html = loadClasspathResource("three-force-viewer.html")           │
│    │  setText(html)                                                     │
│    │  ResizeObserver / setInterval polling waits for non-zero size      │
│    │  → tfgv_viewerReady → drain pendingOps                             │
│       │                                                                 │
│       ▼                                                                 │
│  ThreeForceGraphJsBridge                                                │
│    │  applyData(data):                                                  │
│    │     payload = data.toThreeForceGraphData()  ← {nodes, links}       │
│    │     exec("window.__tfg_nodes = " + gson.toJson(nodes) + ";")        │
│    │     exec("window.__tfg_links = " + gson.toJson(links) + ";")        │
│    │     exec("window.tfgv_setData();")                                  │
│    │  applyNodeConfig(config): execWhenReady(tfgv_applyNodeConfig)      │
│    │  setLayout(algorithm):   execWhenReady(tfgv_setLayout)             │
│    │  setPhysics(bool):       execWhenReady(tfgv_setPhysics)            │
│    │  setLeidenColors(map):   execWhenReady(tfgv_applyLeidenColors)     │
│    │  applyNodeColors(map):   execWhenReady(tfgv_applyNodeColors)       │
│    │  applyGraphPalette(map): refreshPalette()                          │
│    │  notifyViewerReady via BrowserFunction tfgv_viewerReady            │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                                ▼ BrowserScriptQueue.exec
┌─────────────────────────────────────────────────────────────────────────┐
│  JavaScript-Seite (three-force-viewer.html + three-force-viewer.js)    │
│                                                                         │
│  <script src="three.min.js"></script>                                   │
│  <script src="kapsule.min.js"></script>                                 │
│  <script src="d3-force-3d.min.js"></script>                             │
│  <script src="three-force-graph.min.js"></script>                       │
│  <script src="three-force-viewer.js"></script>                          │
│                                                                         │
│  IIFE startet:                                                          │
│    1. WebGL-Probe via createElement('canvas').getContext('webgl')       │
│       → fail: zeigt #tfg-error Element, feuert tfgv_viewerReady         │
│       → success: instanziiert ForceGraph3D(...)                         │
│    2. ForceGraph3D konfiguriert (nodeColor, linkColor, click/hover)     │
│    3. Tooltip-Element (position:fixed), Color-Palette-Panel (#tfg-color-palette)  │
│    4. ResizeObserver auf #tfg-canvas-wrap → signalReady() → pushDataToGraph()  │
│       → tfgv_viewerReady()                                              │
│                                                                         │
│  Java→JS: window.tfgv_setData, tfgv_applyNodeColors, tfgv_applyLeidenColors, │
│           tfgv_applyColorPalette, tfgv_hideColorPalette, tfgv_clear,    │
│           tfgv_fitToScreen, tfgv_setLayout, tfgv_setPhysics,            │
│           tfgv_applyNodeConfig, tfgv_setOption, tfgv_applyNodeImages    │
│  JS→Java: javaCall('tfgv_notifyNodeSelected', id),                      │
│           javaCall('tfgv_notifyLinkSelected', id),                      │
│           javaCall('tfgv_notifySelectionCleared'),                      │
│           javaCall('tfgv_requestNodeContextMenu', id, x, y),            │
│           javaCall('tfgv_requestLinkContextMenu', id, x, y),            │
│           javaCall('tfgv_invokeContextMenuAction', entryId),            │
│           javaCall('tfgv_viewerReady')                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Vendor-Bundles (offline-fähig)

Alle vier Vendor-Skripte sind unter `src/main/resources/static/three/`
gebundled — kein Internet-Zugang zur Laufzeit nötig:

| Datei | Quelle | Version | Größe | Lizenz |
|---|---|---|---|---|
| `three.min.js`               | `unpkg.com/three@0.160.0/build/three.min.js`                      | 0.160.0 | ~655 KB | MIT |
| `kapsule.min.js`             | `unpkg.com/kapsule@1.14.5/dist/kapsule.min.js`                    | 1.14.5  | ~6 KB   | MIT |
| `d3-force-3d.min.js`         | `unpkg.com/d3-force-3d@3.0.5/dist/d3-force-3d.min.js`             | 3.0.5   | ~10 KB  | MIT |
| `three-force-graph.min.js`   | `unpkg.com/3d-force-graph@1.73.0/dist/3d-force-graph.min.js`      | 1.73.0  | ~653 KB | MIT |

Lizenz-Texte sind in `src/main/resources/static/three/THIRD_PARTY_LICENSES`
dokumentiert.

---

## 3. Feature-Matrix (vs. NVL)

| Feature | NVL | 3D |
|---|---|---|
| Java Callbacks für Node-Selection | `vgv_notifyNodeSelected` | `tfgv_notifyNodeSelected` |
| Java Callbacks für Edge/Link-Selection | `vgv_notifyRelationshipSelected` | `tfgv_notifyLinkSelected` |
| Java Callbacks für Selection-Cleared | `vgv_notifySelectionCleared` | `tfgv_notifySelectionCleared` |
| Tooltip für Nodes (HTML, properties-table) | ja (`nvl-graph-viewer.js:400`) | ja (`three-force-viewer.js:renderTooltipHTML`) |
| Tooltip für Edges (HTML, properties-table) | ja | ja |
| Color Palette Panel (top-right) | ja | ja |
| Color Palette Filter (click row → highlight) | ja | ja |
| Per-node color update (Tag-/Leiden-Cluster-Farben) | `vgv_applyLeidenColors` / `vgv_applyNodeColors` | `tfgv_applyLeidenColors` / `tfgv_applyNodeColors` |
| Layout-Auswahl | NVL `forceDirected` + `hierarchical` | `FORCE_3D` (default) + `NONE` (paused) |
| Physics-Toggle | vis-network (nicht NVL-spezifisch) | vis-ähnlicher `pauseAnimation` / `resumeAnimation` |
| Context-Menu (right-click Node/Link) | ja | ja |
| WebGL / Canvas | Canvas (NVL) | WebGL (Three.js) — Hard requirement |
| Hardware-Beschleunigung | nein (SVG-basiert) | ja (GPU-Renderer) |
| Offline-fähig | ja | ja (alle Vendor-Skripte lokal gebundled) |

---

## 4. Wire-Format (Java ↔ JavaScript)

### 4.1 `GraphData.toThreeForceGraphData()` (`GraphData.java`)

Liefert `{ nodes: [...], links: [...] }` (Schlüssel ist `links`, nicht
`relationships` — das ist die d3-force-3d-Konvention).

### 4.2 `GraphNode.toThreeForceGraphNode()` (`GraphNode.java`)

```json
{
  "id": "node-42",
  "labels": ["Person"],
  "properties": {"name": "Alice", "age": "30"},
  "caption": "Alice",
  "color": "#4A90E2"
}
```

Identisch zu `toNvlNode()`, aber **ohne** die NVL-spezifischen
`overlayIcon` / `captionAlign`-Slots — 3d-force-graph rendert WebGL-Spheres,
die `color` direkt übernehmen.

### 4.3 `GraphRelationship.toThreeForceGraphLink()` (`GraphRelationship.java`)

```json
{
  "id": "rel-7",
  "source": "node-42",
  "target": "node-43",
  "type": "KNOWS",
  "properties": {"since": "2020"},
  "color": "#999999"
}
```

Schlüssel ist `source` / `target` (nicht `from` / `to` wie bei NVL).

---

## 5. Tooltip-Verhalten (NVL-paritätisch)

Der Tooltip wird lazy als `<div id="tfg-tooltip">` erzeugt und auf
`mousemove`-Events an die aktuelle Maus-Position geheftet:

- **Node-Tooltip**: caption (bold), labels (comma-separated, grey),
  Property-Table (key/value).
- **Link-Tooltip**: type (bold), caption (grey), Property-Table.
- Viewport-Flip bei Overflow rechts/unten.

Implementiert in `three-force-viewer.js:renderTooltipHTML()` und
`updateTooltip()`. Identische CSS-Klassen wie NVL's Tooltip (border-radius,
max-width 360px, position:fixed).

---

## 6. Color Palette (NVL-paritätisch)

Identisches Verhalten zu `nvl-graph-viewer.js:1273-1378`:

- Top-right Panel mit Collapse-Toggle (`+` / `−`).
- Jede Row: `<div class="tfg-cp-swatch">` + Label + Count.
- Click auf Row → `activeFilterHex` → `applyActiveFilter()` dimmt alle
  Nodes/Links, die nicht die gefilterte Farbe haben.
- `vgv_applyColorPalette(entries, true)` baut das DOM neu auf.
- `vgv_hideColorPalette()` versteckt das Panel.

`LegendBuilder.combined` / `fromGraphPalette` / `fromLeidenClusters` werden
1:1 vom Java-Bridge verwendet (siehe `ThreeForceGraphJsBridge.java:138-153`).

---

## 7. WebGL Hard-Requirement

Falls der Iframe-Canvas keinen WebGL-Kontext liefern kann, zeigt der
Viewer eine Inline-Fehlermeldung im Iframe (`<div id="tfg-error">`)
und signalisiert `tfgv_viewerReady` trotzdem, sodass das Java-Pendant
den `runWhenReady`-Pfad nicht blockiert.

```javascript
function probeWebGL() {
  var c = document.createElement('canvas');
  return !!(c.getContext('webgl') || c.getContext('experimental-webgl'));
}

if (!probeWebGL()) {
  document.getElementById('tfg-root').style.display = 'none';
  document.getElementById('tfg-error').style.display = 'block';
  javaCall('tfgv_viewerReady');   // unblock Java side
  return;
}
```

---

## 8. Engine-Combo & Layout-Combo

Im `GraphViewerControlBar` ist die Engine-Reihenfolge:

```
Nvl | 3D | Cytoscape | Sigma | Vis
```

Der Layout-Combo zeigt für die 3D-Engine zwei Werte:

- **`3D Force`** (`FORCE_3D`) — Default, läuft Force-Layout mit
  `d3-force-3d`-Solver.
- **`None (frozen)`** (`NONE`) — `graph.pauseAnimation()` friert die
  aktuellen Positionen ein.

Der **Physics-Toggle** in der Toolbar ist für vis-network **und** für
3D aktiv (Animation pause/resume). Auto-Fit bleibt vis-network-only
(3D hat keinen Auto-Fit-Mechanismus — `tfgv_fitToScreen` ist nur
explizit über den "Fit"-Button erreichbar).

---

## 9. SwitchingViewer-Integration

`SwitchingViewer` hat einen neuen Branch in `switchTo(THREE_FORCE_GRAPH)`,
der:

1. `threeForceGraphViewer = new ThreeForceGraphViewer(this, SWT.NONE)` erzeugt.
2. `wireViewer(threeForceGraphViewer)` aufruft (Selektion-Listener).
3. `currentData` an den neuen Viewer weiterreicht.
4. `currentNodeConfig` anwendet.
5. `currentLayout` anwendet (Fallback auf `FORCE_3D`, falls
   `!isSupportedByThreeForceGraph()`).
6. `currentLeidenColors` per `setLeidenClusterColors` re-applied.
7. `currentColorPalette` per `applyGraphPalette` re-applied.
8. `applyNodeColors(resolveEffective(...))` schiebt die vereinheitlichte
   per-Node-Farbkarte an den neuen Viewer.

Analog sind alle Method-Fan-Outs (`setGraphData`, `setNodeConfig`,
`applyNodeColors`, `setLayout`, `setLeidenClusterColors`,
`setLayoutOptions`, `fitToScreen`, `clear`,
`addNodeSelectionListener`, `addRelationshipSelectionListener`,
`addSelectionClearedListener`, `setContextMenuProvider`) erweitert
um einen `currentEngine == THREE_FORCE_GRAPH && threeForceGraphViewer != null`-Branch.

---

## 10. Tests

Drei neue Test-Klassen sichern die Engine ab:

| Test-Klasse | Was sie prüft |
|---|---|
| `ThreeForceGraphViewerJsSourceTest` | `three-force-viewer.js` enthält jeden `tfgv_*`-Handler (setData/applyLeidenColors/applyNodeColors/applyColorPalette/hide/clear/fit/setLayout/setPhysics), feuert jeden JS→Java-Callback (`tfgv_notifyNodeSelected`, `tfgv_notifyLinkSelected`, `tfgv_notifySelectionCleared`, `tfgv_requestNodeContextMenu`, `tfgv_requestLinkContextMenu`, `tfgv_viewerReady`), enthält WebGL-Probe, Vendor-Bundles existieren, CSS-Selektoren präsent |
| `ThreeForceGraphBridgeSourceTest` | `ThreeForceGraphJsBridge.java` registriert jeden `BrowserFunction` mit korrektem `tfgv_*`-Namen, ruft `tfgv_applyLeidenColors` / `tfgv_applyNodeColors` und `refreshPalette()` auf, schiebt `tfgv_clear` + `tfgv_hideColorPalette` aus `clear()`, `applyData` schiebt `__tfg_nodes` / `__tfg_links` + `tfgv_setData`, `ThreeForceGraphViewer` liefert `GraphEngine.THREE_FORCE_GRAPH`, hat kein `setLegend` / `clearLegend` |
| `SwitchingViewerThreeRoutingTest` | `SwitchingViewer` enthält alle Engine-Fan-Outs für `THREE_FORCE_GRAPH`, `wireViewer(ThreeForceGraphViewer)`-Overload, `disposeViewer` disposed, `setLegend` excluded, `LayoutAlgorithm.FORCE_3D` + `isSupportedByThreeForceGraph()` + `valuesForThreeForceGraph()` existieren, `GraphData.toThreeForceGraphData()` existiert, `GraphEngine.THREE_FORCE_GRAPH` definiert |

Diese Tests sind allesamt statische Source-Level-Checks (sie lesen
`.java` und `.js` direkt) — keine Browser-Widget-Mocks, keine
JavaScript-Engines, keine Sleep-Timer.
