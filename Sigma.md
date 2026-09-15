# Sigma.js + graphology Engine

Diese Dokumentation beschreibt die dritte Rendering-Engine des `SwitchingViewer`
— **sigma.js@2.3.1** kombiniert mit **graphology@0.25.4** und den
Layout-Bibliotheken `graphology-layout-forceatlas2`, `graphology-layout-noverlap`
sowie dem `graphology-layout`-Paket (`circular`, `random`).

Die Engine ist auf zwei Designziele hin optimiert:

1. **Datenlieferung über REST statt BrowserFunction-Push**, damit große
   Graphen (>1 MB JSON) per GZIP + ETag/304 günstig übertragen werden.
2. **Canvas-2D-Fallback-Renderer** als zweiter Pfad, falls WebGL im Browser
   fehlt (Headless-Chromium, Sandboxes, low-end Geräte).

---

## 1. Architektur-Übersicht

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Java-Seite (Spring Boot + RAP)                                         │
│                                                                         │
│  CsvExampleEntryPoint                                                   │
│       │ viewer.setGraphData(data)                                       │
│       ▼                                                                 │
│  SwitchingViewer (Composite)                                            │
│       │                                                                 │
│       │ engine == SIGMA                                                 │
│       ▼                                                                 │
│  SigmaViewer (Browser)                                                  │
│   │  Constructor parameters: (parent, style, htmlOrResourcePath, data)  │
│   │  bridge = new SigmaJsBridge(this)                                   │
│   │  if (data != null) bridge.applyData(data)  ← Push-via-Bridge          │
│   │  html = loadClasspathResource("sigma-viewer.html")                  │
│   │  setText(html)                                                      │
│   │  ResizeListener → bridge.resize() (NoOp-Resize für Canvas-Pfad)     │
│       │                                                                 │
│       ▼                                                                 │
│  SigmaJsBridge                                                          │
│   │  applyData(data):                                                   │
│   │     1. payload = data.toGraphologyElements(currentNodeConfig)       │
│   │     2. b64 = base64(gzip(gson.toJson(payload)))                     │
│   │     3. execWhenReady(atomic call: vg_clear + vg_setDataGz(b64))    │
│   │  applyNodeConfig(config): execWhenReady(vg_applyNodeConfig)         │
│   │  setLayout(algorithm): execWhenReady(vg_setLayout)                  │
│   │  setLeidenColors(map): execWhenReady(vg_applyLeidenColors)          │
│   │  notifyViewerReady via BrowserFunction vg_viewerReady               │
│   │                                                                     │
│   │  Kein REST-Endpoint, kein Per-Session-Cache, kein URL-Inlining.     │
└─────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼ BrowserScriptQueue.exec (atomic script)
┌─────────────────────────────────────────────────────────────────────────┐
│  JavaScript-Seite (sigma-viewer.html + sigma-viewer.js)                 │
│                                                                         │
│  <script src="/sigma/pako.min.js"></script>                             │
│  <script src="/sigma/graphology.min.js"></script>                       │
│  <script src="/sigma/graphology-layout.min.js"></script>                │
│  <script src="/sigma/graphology-layout-forceatlas2.min.js"></script>    │
│  <script src="/sigma/graphology-layout-noverlap.min.js"></script>       │
│  <script src="/sigma/sigma.min.js"></script>                            │
│  <script src="/sigma/sigma-viewer.js"></script>                         │
│                                                                         │
│  (function () {                                                         │
│      'use strict';                                                      │
│      var graph = new graphology.Graph({multi:true, directed:true, ...});│
│      var renderer = createRenderer(container, graph);                   │
│      //  ↳ Sigma-WebGL wenn verfügbar, sonst CanvasRenderer-Fallback    │
│      attachRendererEvents(renderer);  // unified API                    │
│      // Initial-Load: window.vg_setDataGz(b64) wird vom Java-Bridge     │
│      // gefeuert sobald der iframe bereit ist (execWhenReady) und die   │
│      // Daten landen via pako.ungzip → JSON.parse → rebuildGraph.       │
│      notifyViewerReady();                                               │
│  })();                                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Datenlieferung — REST-Endpoint-Architektur

### 2.1 Datenlieferung — Bridge-Push mit gzip + base64

Wie Vis (`vgv_*`) und Cytoscape (`cgv_*`) schiebt Sigma seine Daten via
`BrowserScriptQueue.exec()` in den iframe — allerdings **gzip-komprimiert und
base64-kodiert**, damit auch sehr große Graphen (50 000+ Edges) in einem
einzigen Skript-Aufruf transportiert werden können.

| Schritt | Verantwortlich | Was passiert |
|--------|-----------------|---------------|
| 1 | Java `applyData` | `payload = data.toGraphologyElements(currentNodeConfig)`, `json = gson.toJson(payload)`, `b64 = base64(gzip(json))` |
| 2 | Bridge | `execWhenReady("if (window.vg_clear) ...; if (window.vg_setDataGz) window.vg_setDataGz('<b64>')")` — atomic |
| 3 | Browser (iframe) | `atob(b64)` → `Uint8Array` → `pako.ungzip(bytes, {toText:true})` → `JSON.parse` → `rebuildGraph(...)` |
| 4 | Browser | `runLayout(currentLayout)` + `renderer.refresh()` |

**Vorteile gegenüber REST:**
- Kein zweiter Thread-Pool, kein Servlet-Container-Roundtrip — alles
  läuft auf dem RAP-UI-Thread wie der Rest der Bridge
- Kein Per-Session-Cache, kein Token-Management, kein URL-Inlining in
  das iframe-HTML
- `execWhenReady` löst das Initial-Load-Race-Problem ohne inline-URLs
- `application.yml:server.compression` ist irrelevant für Sigma (Kompression
  passiert vor `Browser.execute(...)` auf der Java-Seite)

**Trade-offs:**
- Größere Inline-Skripte als ein Cytoscape-/vis-Push: ~30 KB Roh-JSON werden
  zu ~5 KB gzip + ~7 KB base64 → ca. Faktor 4 kleiner als das Roh-JSON,
  aber immer noch im einstelligen Kilobyte-Bereich für realistische
  Dependency-Graphen
- Die base64-Kodierung fügt ~33 % Overhead hinzu (unvermeidbar — die
  `Browser.execute(...)`-API nimmt nur JavaScript-Strings)
- Bei extrem großen Graphen (> 1 MB gzip-Output) stößt man an die
  String-Länge-Limits einzelner Browser — kann später durch Chunking
  abgemildert werden (mehrere `vg_setDataGzPartial`-Aufrufe)

### 2.2 Warum gzip + base64?

Reine JSON-`exec()`-Aufrufe wie bei Cytoscape/vis werden bei großen
Graphen schnell ineffizient:

- 30 KB Roh-JSON (1 010 Edges) → **30 KB Skript-String** im Browser
- 1 MB Roh-JSON (≈ 50 000 Edges) → **1 MB Skript-String** → kann je nach
  Browser an String-Länge-Limits stoßen

Mit gzip:

- 30 KB Roh-JSON → 5 KB gzip → 7 KB base64 (Faktor ~4 kleiner)
- 1 MB Roh-JSON → 200 KB gzip → 270 KB base64 (Faktor ~4 kleiner)
- Kompressionsrate ist gut, weil Labels, Farben und Node-ID-Präfixe
  stark wiederholen

`pako` wird nur für die Decompression gebraucht (`pako_inflate.umd.min.js`),
ist ~32 KB und läuft komplett im iframe (kein Build-Pipeline-Eingriff —
lokales npm-pack und 1:1-Kopie nach `static/sigma/pako.min.js`).

### 2.3 Initial-Load ohne Race

`SigmaViewer.applyData(initialData)` wird im Konstruktor **vor** `setText(html)`
aufgerufen. Der Aufruf landet in `execWhenReady(...)`, queued also bis
`vg_viewerReady` feuert, und wird dann als allererster Push an den iframe
geschickt. Kein URL-Inlining, kein zusätzlicher Boot-Mechanismus nötig — der
existierende Ready-Handshake der Bridge deckt das vollständig ab.

### 2.4 Was im Vergleich zum vorherigen REST-Design weggefallen ist

- `SigmaGraphCache` (process-lokaler Cache + Daemon-Evictor): gelöscht
- `SigmaGraphController` (`@RestController` für `/api/sigma/nodes` + `/edges`): gelöscht
- `UISessionListener`-Hook in `SigmaJsBridge`: gelöscht
- Token-Generierung (`UUID.randomUUID()`): gelöscht
- URL-Inlining in `SigmaViewer`-Konstruktor: gelöscht
- `__VG_INITIAL_NODES_URL__` / `__VG_INITIAL_EDGES_URL__` HTML-Platzhalter: gelöscht
- `fetch(...)` + `If-None-Match` + `ETag` in `sigma-viewer.js`: gelöscht

Verbleibend: `BrowserScriptQueue.exec(...)` mit `gzipAndBase64(json)` als
Helper und `pako.ungzip(...)` im iframe.

---

## 3. Graphology-Payload-Schema

`GraphData.toGraphologyElements(NodeConfig)` produziert den graphology-Standard:

```json
{
  "version": 1,
  "nodes": [
    {
      "key": "Kassenwechselmeldungen",
      "attributes": {
        "label": "Kassenwechselmeldungen",
        "nodeType": "Node",
        "nodeTag": "entity",
        "color": "#4A90E2",
        "size": 8,
        "tooltip": "<table class=\"vgv-tooltip\">...</table>",
        "raw": { "name": "Kassenwechselmeldungen", "nodeTag": "entity" }
      }
    }
  ],
  "edges": [
    {
      "key": "e1",
      "source": "Kassenwechselmeldungen",
      "target": "Kassenwechsel-Leistungsdaten",
      "attributes": {
        "label": "149",
        "size": 2.4,
        "color": "#888",
        "weight": 149,
        "logWeight": 2.176,
        "tooltip": "...",
        "tooltipHeader": "Kassenwechselmeldungen -> Kassenwechsel-Leistungsdaten"
      }
    }
  ]
}
```

**Schlüssel-Hinweise:**
- `key` ist der graphology-Knoten-Identifier, identisch mit `data.id`
- `source`/`target` zeigen auf andere `key`-Werte (Multi-Edge-fähig)
- `attributes.weight` ist der rohe Wert, `attributes.logWeight = ln(weight+1)`
  (vorberechnet vom Java-Side) — die Layouts nutzen beides
- `attributes.size` für Edges folgt sqrt-Log-Skalierung: `0.6 + 0.9·sqrt(min(max(logW, 0), 4))`
- SVG-Badges werden **nicht** serialisiert (kein `image`-Feld) — der
  CanvasRenderer kann keine SVG-Bilder darstellen, und das spart bei
  1 010 Edges ~500 KB Base64-Payload
- `tooltip` ist HTML aus `TooltipBuilder.fromProperties()` (Java-seitig
  vorberechnet, identisch zu Vis- und Cytoscape-Pfad)

---

## 4. JavaScript-Bridge — `vg_*`-API

Die Bridge exponiert exakt die `vg_*`-Funktionen, die `SigmaJsBridge` aus
dem Java-Side aufruft:

| Funktion | Java-Trigger | Wirkung |
|----------|--------------|---------|
| `vg_loadGraph(nodesUrl, edgesUrl)` | `SigmaJsBridge.applyData` | Initiales oder refresh-Fetch der Graph-Payload |
| `vg_setLayout(name)` | `SigmaJsBridge.setLayout` | `runLayout(name)` + `renderer.refresh()` + `fitToViewport()` |
| `vg_setLayoutOptions(opts)` | `SigmaJsBridge.setLayoutOptions` | Wie oben, mit benutzerdefinierten Optionen |
| `vg_applyNodeConfig(config)` | `SigmaJsBridge.applyNodeConfig` | Setzt `nodeReducer` an `renderer.setSetting` |
| `vg_applyLeidenColors(map)` | `SigmaJsBridge.setLeidenColors` | Wie oben, plus Leiden-Override-Map |
| `vg_applyLegend(entries, enabled)` | `SigmaJsBridge.applyLegend` | Rendert Legend-Panel im DOM |
| `vg_showContextMenu(snapshot, x, y)` | `SigmaJsBridge.showContextMenu` | Floating Context-Menu |
| `vg_hideContextMenu()` | `SigmaJsBridge.hideContextMenu` | Versteckt Context-Menu |
| `vg_clear()` | `SigmaJsBridge.clear` | `graph.clear()` + `renderer.refresh()` |
| `vg_fitToScreen()` | `SigmaJsBridge.fitToScreen` | `fitToViewport()` |
| `vg_resize()` | `SigmaViewer.Resize-Listener` | Canvas-Pfad: `renderer.resize()`; Sigma-Pfad: no-op |
| `vg_dispose()` | `SigmaViewer.dispose` | Räumt Tooltip, Side-Panel, Legend auf |

`BrowserFunction`-Handler in `SigmaJsBridge`:

| Handler | Vom iframe aufgerufen bei |
|---------|---------------------------|
| `vg_viewerReady` | `notifyViewerReady()` im iframe — entkoppelt die `runWhenReady`-Queue |
| `vg_notifyNodeSelected(id)` | Click auf einen Knoten (Sigma `clickNode` / Canvas `click`) |
| `vg_notifyRelationshipSelected(id)` | Click auf eine Kante |
| `vg_notifySelectionCleared()` | Click ins Leere (Stage) |
| `vg_requestNodeContextMenu(id, x, y)` | Right-Click auf Knoten |
| `vg_requestRelationshipContextMenu(id, x, y)` | Right-Click auf Kante |
| `vg_invokeContextMenuAction(entryId)` | User klickt Eintrag im Context-Menu |

---

## 5. Sigma-Layouts

Das `LayoutAlgorithm`-Enum hat fünf `*_SIGMA`-Werte:

```java
FORCE_ATLAS_SIGMA       (false, false, true)  // graphology-layout-forceatlas2 (default)
FORCE_DIRECTED_2_SIGMA  (false, false, true)  // FA2 + log-Weight + NoOverlap
NOVERLAP_SIGMA          (false, false, true)  // graphology-layout-noverlap
CIRCULAR_SIGMA          (false, false, true)  // graphology-layout.circular
RANDOM_SIGMA            (false, false, true)  // graphology-layout.random
```

### 5.1 `FORCE_ATLAS_SIGMA` — Standard-ForceAtlas2

```js
graphologyLayoutForceAtlas2.assign(graph, {
    iterations: 150,
    settings: {
        gravity: 1, scalingRatio: 10, slowDown: 5, strongGravityMode: false
    }
});
```

Entspricht dem vis-network-`forceAtlas2Based`-Solver. **Kein** NoOverlap-Post-Processing —
Knoten können sich überlappen.

### 5.2 `FORCE_DIRECTED_2_SIGMA` — Weight-Proximity (Default für Sigma)

```js
function runForceDirected2(graph) {
    // 1. Normalisiere Edge-Gewichte via log10(weight+1)
    var maxLog = 0;
    graph.forEachEdge(function (e) {
        var w = graph.getEdgeAttribute(e, 'weight') || 1;
        var lw = Math.log10(Number(w) + 1);
        if (lw > maxLog) maxLog = lw;
    });
    graph.forEachEdge(function (e) {
        var w = graph.getEdgeAttribute(e, 'weight') || 1;
        var lw = Math.log10(Number(w) + 1);
        // 0.05 + 0.95 * (lw / maxLog): auch Null-Gewicht-Edges ziehen minimal
        graph.setEdgeAttribute(e, '_fd2_weight', 0.05 + 0.95 * (lw / maxLog));
    });

    // 2. Initial-Random-Positionen für FA2-Stabilität
    graph.forEachNode(function (n) {
        var x = graph.getNodeAttribute(n, 'x');
        var y = graph.getNodeAttribute(n, 'y');
        if (typeof x !== 'number' || typeof y !== 'number'
                || !isFinite(x) || !isFinite(y)) {
            graph.setNodeAttribute(n, 'x', Math.random() - 0.5);
            graph.setNodeAttribute(n, 'y', Math.random() - 0.5);
        }
    });

    // 3. FA2 mit weight-normalisierten Anziehungsstärken
    graphologyLayoutForceAtlas2.assign(graph, {
        iterations: 200,
        settings: {
            gravity: 1, scalingRatio: 10, slowDown: 5, strongGravityMode: false,
            edgeWeight: function (edge) {
                return graph.getEdgeAttribute(edge, '_fd2_weight') || 0.1;
            }
        }
    });

    // 4. Post-FA2 NaN-Rescue
    graphologyLayoutNoverlap.assign(graph, {
        maxIterations: 50, gridSize: 8, ratio: …, margin: 4
    });

    // 5. Cleanup _fd2_weight
    graph.forEachEdge(function (e) {
        try { graph.removeEdgeAttribute(e, '_fd2_weight'); } catch (err) {}
    });
}
```

**Mathematik:** Höheres `weight` → höherer `_fd2_weight` → stärkere FA2-Anziehung
→ kürzere Equilibriumsdistanz → engere Cluster. Mit `log10`-Stauchung
vermeiden wir numerische Instabilität bei den Gewichten 1…3831 (siehe
`export.csv`-Beispiel).

**NoOverlap:** Standard-`gridSize: 8` mit `ratio = max(20, containerW × 0.85 / spread)`
stellt sicher, dass der Graph ~85% der Canvas-Breite nutzt.

**NaN-Rescue (Defensiv):** Sollte FA2 oder NoOverlap NaN-Koordinaten
produzieren (passiert in seltenen Multi-Edge-Konstellationen), werden
diese auf den Schwerpunkt der gültigen Knoten gesetzt, mit kleinem
Random-Offset zur Vermeidung von Overlap-Singularitäten.

### 5.3 `NOVERLAP_SIGMA` — Nur NoOverlap-Post-Processing

Wendet nur `graphologyLayoutNoverlap.assign()` auf die aktuellen Positionen
an (z.B. nach Random- oder FA2-Layout).

### 5.4 `CIRCULAR_SIGMA` — Knoten auf Kreis

```js
graphologyLayout.circular.assign(graph);
```

Platziert die Knoten gleichmäßig auf einem Kreis. **Nicht** von NoOverlap
gefolgt — die kreisförmige Anordnung ist bereits überschneidungsfrei bei
Default-Knotengröße.

### 5.5 `RANDOM_SIGMA` — Zufällige Positionen

```js
graphologyLayout.random.assign(graph);
```

Nützlich als Preseed für NoOverlap oder als „weißes Rauschen" vor FA2.

---

## 6. Renderer-Factory: WebGL oder Canvas-2D

`createRenderer(container, graph)` wählt automatisch den besten Renderer:

```js
function createRenderer(container, graph) {
    if (typeof Sigma !== 'undefined' && hasWebGL()) {
        try {
            return new Sigma(graph, container, defaultSigmaSettings());
        } catch (e) {
            log('Sigma WebGL renderer failed, falling back to Canvas: ' + e.message);
        }
    }
    return new CanvasRenderer(container, graph);
}

function hasWebGL() {
    try {
        var c = document.createElement('canvas');
        return !!(window.WebGLRenderingContext
            && (c.getContext('webgl2') || c.getContext('webgl') || c.getContext('experimental-webgl')));
    } catch (e) { return false; }
}
```

Beide Pfade implementieren die identische API-Surface, die der Rest von
`sigma-viewer.js` über `attachRendererEvents(renderer)` konsumiert:

| API | Sigma-Implementierung | Canvas-Implementierung |
|-----|------------------------|--------------------------|
| `refresh()` | Force-Render über GL-Pipeline | `clearRect` + `_render()` |
| `getCamera().fit({padding})` | Sigma `Camera.fit` | `CanvasRenderer.fitCamera()` |
| `setSetting(key, val)` | Sigma-Settings-Map | `this.settings[key] = val` |
| `on(event, fn)` / `emit(event, payload)` | Sigma EventEmitter | Mini-EventEmitter in der Klasse |
| `kill()` | Sigma `kill()` | `removeEventListener` + DOM-Cleanup |

### 6.1 Warum ein Canvas-2D-Fallback?

sigma.js v2.x hat ein **hartes WebGL-Erfordernis** — der Konstruktor ruft
`gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)` auf, was mit
`Cannot read properties of null (reading 'blendFunc')` auf null-Context wirft.
Das schließt folgende Umgebungen aus:

- **Headless-Chromium** (z.B. die chrome-devtools-MCP-Instanz in dieser
  Codebase), das standardmäßig ohne WebGL-Support gestartet wird
- **Sandboxes** in denen `--use-gl=swiftshader` nicht gesetzt ist
- **Low-end mobile Geräte** deren GPU-Treiber keinen WebGL-Context liefern

Der Canvas-2D-Fallback garantiert:
1. Visuelle Bestätigung in Test-Umgebungen
2. Graceful Degradation für Endnutzer
3. Identische Event-/Selection-Pipeline

### 6.2 CanvasRenderer-Implementierung

```js
function CanvasRenderer(container, graph) {
    this._isCanvas = true;                       // Renderer-Marker
    this.graph = graph;                           // gemeinsame graphology-Referenz
    this.settings = { /* spiegelt Sigma-Settings */ };
    this._listeners = {};                         // Mini-EventEmitter
    this.selectedNodeId = this.selectedEdgeId = null;
    this.hoveredNodeId = this.hoveredEdgeId = null;
    this._dirty = true; this._projection = null;  // Bbox-Fit
    this.canvas = document.createElement('canvas');
    this.canvas.style = 'position:absolute;top:0;left:0;width:100%;height:100%';
    container.appendChild(this.canvas);
    this.ctx = this.canvas.getContext('2d');
    this.dpr = window.devicePixelRatio || 1;
    this.width = this.height = 0;
    this._bindListeners();                        // click, contextmenu, mousemove
    this.resize();                                // Backing-Buffer anpassen
    this.camera = { fit: function (opts) { self.fitCamera(opts); } };
}
```

**`_computeProjection()`:** Nimmt die Bounding-Box der Knoten
(`min(x)/max(x)/min(y)/max(y)`), skaliert so, dass die BBox + 30px Padding
in den Canvas passt. Y-Achse wird geflippt (graphology: y=0 unten,
Canvas-2D: y=0 oben).

**`_render()`:** Iteriert erst Edges (Linien mit log-gewichteter Dicke),
dann Nodes (Kreise mit NodeConfig-Color), dann Labels. Defensiver
`typeof x !== 'number'`-Check überspringt unpositionierte Knoten.

**`_hitTest(mx, my)`:** Distanz-basierte Hit-Tests in Graph-Koordinaten
nach Inversion der Bbox-Projektion. Knoten-Threshold `size + 2`,
Kanten-Threshold `size + 4` (jeweils in Graph-Units, geteilt durch `scale`).

---

## 7. Test-Status

### 7.1 Java-Tests

- **`SigmaJsBridgeGzipTest`** (4 Tests): gzip+base64 round-trip für ASCII,
  graphology-Payload, UTF-8 (inkl. Emoji), leerer String. Compression-Ratio-Sanity.
- **`SigmaJsBridgeSourceTest`** (3 Tests): atomarer `exec`-Aufruf,
  `gzipAndBase64`-Sichtbarkeit, keine REST-Referenzen (Cache, Controller,
  UISessionListener, `__VG_INITIAL_*`, `/api/sigma`).
- **`SigmaViewerJsSourceTest`** (15 Tests): Source-Guard für `hasWebGL`,
  `createRenderer`, `CanvasRenderer.prototype.*`, Selection-Events,
  `addEdgeWithKey`, `runForceDirected2`, Error-Handling, **Bridge-Push via
  `vg_setDataGz` + `pako.ungzip`**, **HTML lädt `pako.min.js` vor
  `sigma-viewer.js`** und enthält keine `__VG_INITIAL_*`-Platzhalter mehr,
  **pako-Bundle ist vorhanden**.
- **`GraphDataGraphologyElementsTest`** (3 Tests): Node-/Edge-Attribute,
  Missing-Weight-Handling.
- **`LayoutAlgorithmSigmaTest`** (3 Tests): `isSupportedBySigma()` für alle
  Werte, `valuesForSigma()` Reihenfolge.

Plus die existierenden Tests ohne Regression.

### 8.2 Chrome-Browser-Verifikation (manuell)

Mit dem CanvasRenderer-Fallback lässt sich der Sigma-Engine jetzt auch in
WebGL-disabled chromium-Instanzen **visuell** verifizieren:

1. `mvn spring-boot:run` (Port 8085)
2. `http://localhost:8085/graph` aufrufen
3. Engine-Combo auf **Sigma** umschalten
4. Layout-Combo auf **Sigma ForceDirected2** (Default)
5. Screenshot: 150 blaue Knoten + 1010 graue Kanten, gewicht-getrieben geclustert

Auch getestet:
- `CIRCULAR_SIGMA` — 150 Knoten gleichmäßig auf Kreis
- `NOVERLAP_SIGMA` — überlap-frei nach Preseed-Layout
- `RANDOM_SIGMA` — zufällige Streuung
- `FORCE_ATLAS_SIGMA` — Standard-FA2 ohne Weight-Normalisierung

### 8.3 Was bei echtem WebGL passiert

In Production-Browsern (Chrome, Firefox, Safari, Edge) wird der `Sigma`
WebGL-Renderer aktiv. Die identische `attachRendererEvents` registriert
die gleichen Click/Right-Click/Hover/Context-Menu-Listener. ForceAtlas2
rendert dann mit Hardware-Beschleunigung, was bei 10 000+ Edges einen
deutlichen Performance-Sprung gegenüber Canvas-2D bedeutet.

---

## 9. Verzeichnis-Layout

```
src/main/
├── java/de/tk/dependencyanalyse/rapui/visgraph/
│   ├── engine/GraphEngine.java                      # VIS_NETWORK | CYTOSCAPE | SIGMA
│   ├── data/
│   │   ├── LayoutAlgorithm.java                     # 5 *_SIGMA-Werte + 3. Flag
│   │   ├── GraphData.java                            # +toGraphologyElements(NodeConfig)
│   │   ├── GraphNode.java                            # +toGraphologyNode(NodeConfig)
│   │   └── GraphRelationship.java                    # +toGraphologyEdge()
│   ├── api/
│   │   └── NodeConfigRegistry.java                   # per-Session NodeConfig-Lookup
│   ├── SigmaViewer.java                             # Browser-Widget, Bridge-Push
│   ├── internal/SigmaJsBridge.java                   # vg_* BrowserFunction-Handler + gzip+base64-Push
│   ├── GraphViewerControlBar.java                    # +Engine-Item "Sigma", +Layouts
│   ├── GraphConfigurationDialog.java                 # +"Sigma engine: no community-agg"
│   └── SwitchingViewer.java                         # +Sigma-Routing in switchTo/set*
└── resources/
    ├── application.yml                              # server.compression.enabled (für GraphUploadController / SampleGraph)
    └── static/
        ├── sigma-viewer.html                        # pako.min.js + graphology + sigma-viewer.js
        └── sigma/
            ├── pako.min.js                           # pako@3.0.2 pako_inflate.umd.min.js (~32 KB)
            ├── graphology.min.js                     # graphology@0.25.4 UMD
            ├── graphology-layout.min.js              # circular + random
            ├── graphology-layout-forceatlas2.min.js  # esbuild-IIFE-Bundle
            ├── graphology-layout-noverlap.min.js      # esbuild-IIFE-Bundle
            ├── sigma.min.js                          # sigma@2.3.1 UMD
            └── sigma-viewer.js                       # Bridge + Layouts + CanvasRenderer + vg_setDataGz
```

```
src/test/java/.../visgraph/
├── data/GraphDataGraphologyElementsTest.java         # 3 Tests
├── data/LayoutAlgorithmSigmaTest.java                # 3 Tests
└── internal/
    ├── SigmaJsBridgeGzipTest.java                    # 4 Tests (round-trip + Kompressionsratio)
    ├── SigmaJsBridgeSourceTest.java                  # 3 Tests (atomic exec, Rest-Refs, gzip-Sichtbarkeit)
    └── SigmaViewerJsSourceTest.java                  # 15 Tests (Bridge-Push, HTML-Reihenfolge, pako-Bundle, +Canvas/Layout/Selection)
```

---

## 10. Bekannte Limitierungen

| Limitierung | Grund | Workaround / Status |
|------------|-------|---------------------|
| SVG-Badges für Nodes | `data:image/...` in `attributes.image` wird vom CanvasRenderer nicht gerendert; Serialisierung spart ~500 KB bei 1 010 Edges | Bewusste Entscheidung (s. Plan §10) |
| Community-Aggregation | sigma v2 hat keine Compound-Node-Semantik; `graphology.addNodeWithParent` ist eine v3-API | Im aktuellen PR deaktiviert (Dialog-Hinweis), Follow-up-PR für v3-Migration |
| Drag-Pan / Wheel-Zoom | Canvas-2D-Renderer hat (noch) keine Pan/Zoom-Steuerung | Production-Pfad via Sigma-WebGL hat volle Kamera-Steuerung |
| WebGL-only Sigma v2.x | Hard requirement im Konstruktor | Canvas-2D-Fallback kompensiert |

---

## 11. Versionen

| Paket | Version | Quelle |
|-------|---------|--------|
| `sigma` | 2.3.1 | npm: `sigma@2.3.1` (sigma.js + graphology als reines UMD-Bundle) |
| `graphology` | 0.25.4 | npm: `graphology@0.25.4` UMD |
| `graphology-layout` | 0.6.1 | npm: `graphology-layout@0.6.1` (circular + random als UMD) |
| `graphology-layout-forceatlas2` | 0.10.1 | npm: `graphology-layout-forceatlas2@0.10.1` (esbuild-IIFE-Bundle) |
| `graphology-layout-noverlap` | 0.4.2 | npm: `graphology-layout-noverlap@0.4.2` (esbuild-IIFE-Bundle) |
| `graphology-utils` | 2.5.2 | npm: `graphology-utils@2.5.2` (Peer von `forceatlas2`) |
| `pako` | 3.0.2 | npm: `pako@3.0.2` — nur `dist/browser/pako_inflate.umd.min.js` (~32 KB) |

Die UMD-Bundles von `graphology` und `sigma` werden direkt aus den
NPM-Paketen kopiert. Die Layout-Pakete (forceatlas2, noverlap) sind
nicht als UMD verfügbar; sie werden via esbuild zu IIFE-Bundles kompiliert
und in `static/sigma/` abgelegt:

```bash
esbuild --bundle --minify --format=iife \
  --global-name=graphologyLayoutForceAtlas2 \
  node_modules/graphology-layout-forceatlas2/index.js \
  --outfile=src/main/resources/static/sigma/graphology-layout-forceatlas2.min.js
```
