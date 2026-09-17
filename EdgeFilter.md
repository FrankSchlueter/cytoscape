# Edge-Filter bei Node- und Community-Selection

## 1. Zweck

Beim Auswählen einer einzelnen Node oder beim Klick auf eine Zeile der
Color Palette (Leiden-Cluster bzw. gleichgetaggte Node-Gruppe) blendet der
Viewer alle Edges aus, die nicht zur Selektion gehören. Der User behält
den vollen Graphen als Kontext, kann sich aber auf die Kanten konzentrieren,
die für die Seletion relevant sind. Ein zweiter Klick auf dieselbe Selektion
oder ein Klick auf den Hintergrund stellt wieder alle Edges her
(Toggle-Verhalten).

Das Verhalten ist über alle drei Engines identisch: **Cytoscape**, **Sigma**
und **NVL**. Die Implementierung nutzt die jeweils native Visualisierungs-
API, sodass das Rendering performant bleibt (keine Per-Frame-Inline-Styles,
keine Hit-Test-Kollisionen mit versteckten Edges).

---

## 2. Sichtbarkeitsregeln

### 2.1 Node-Selection
- **Aktiv durch:** Klick auf eine Node im Canvas.
- **Sichtbar bleiben:** Edges, deren Source oder Target die gewählte Node
  ist. Eingehende, ausgehende und Self-Loop-Edges zählen gleichermaßen.
- **Ausgeblendet:** Alle übrigen Edges.
- **Toggle aus durch:**
  - Zweiter Klick auf dieselbe Node (Cytoscape und NVL deselektieren die
    Node, Sigma toggelt über `edgeFilter.type === 'node'` Vergleich).
  - Klick auf eine andere Node (Filter wird ersetzt).
  - Klick auf den Hintergrund.
  - Klick direkt auf einen sichtbaren Edge (Filter wird aufgehoben, Edge
    selbst bleibt selektiert).
  - Wechsel zu einer Palette-Zeile (Cluster-Filter ersetzt Node-Filter).

### 2.2 Community / Color-Palette-Selection
- **Aktiv durch:** Klick auf eine Zeile im Color-Palette-Panel.
- **Match-Kriterium:** Der Node gehört zur gleichen Farbe wie die gewählte
  Zeile. Vergleichsreihenfolge:
  1. `currentEffectiveColors[nodeId]` (vom Java-`NodeColorResolver`
     aufgelöste Farbe — Tag-Colors, Label-Colors, Leiden-Override).
  2. `currentLeidenColors[nodeId]` (reine Leiden-Cluster-Farbe).
- **Sichtbar bleiben:**
  - **Intra-Cluster-Edges** (beide Endpoints im Cluster) — voll sichtbar,
    in der Cluster-Farbe.
  - **Bridge-Edges** (genau ein Endpoint im Cluster) — sichtbar, mit der
    normalen grauen Linienfarbe, damit der User sofort sieht, dass die
    andere Seite außerhalb des Clusters liegt.
- **Ausgeblendet:** Alle übrigen Edges.
- **Toggle aus durch:**
  - Zweiter Klick auf dieselbe Palette-Zeile.
  - Klick auf eine andere Palette-Zeile (Cluster-Filter ersetzt sich).
  - Klick auf den Hintergrund.
  - Klick direkt auf einen sichtbaren Edge.
  - Wechsel zu einer Node (Node-Filter ersetzt Cluster-Filter).
  - Aufruf von `vgv_applyColorPalette(enabled=false)` oder
    `vgv_hideColorPalette()` — das Panel verschwindet und der Filter wird
    mit aufgehoben.

---

## 3. Architektur

Jeder Viewer hält eine State-Variable `edgeFilter` im IIFE-Scope:

```javascript
// Format (alle drei Engines)
var edgeFilter = null;   // null ⇒ alle Edges sichtbar
// { type: 'node',    nodeId: '…'  }
// { type: 'cluster', hex:    '#…' }
```

Die Variable ist die einzige Quelle der Wahrheit. Die drei Engines
unterscheiden sich nur darin, **wie** sie den Filter in ihre jeweilige
Render-Pipeline übersetzen.

```
┌─────────────────────┐
│  Click-Handler      │   cytoscape: on('tap', …) handlers
│  (Node / Palette /  │   sigma:     renderer.on('clickNode', …)
│   Background)       │   nvl:       clickHandler.updateCallback(…)
└─────────┬───────────┘
          │  setzt edgeFilter
          ▼
┌─────────────────────┐
│  edgeFilter State   │   IIFE-Top, pro Viewer genau eine Variable
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  Engine-spezifische │   cytoscape: cy.style('.cgv-edge-hidden') +
│  Filter-Anwendung   │              addClass / removeClass
│                     │   sigma:     renderer.edgeFilter + _render
│                     │              skip-Funktion für forEachEdge
│                     │   nvl:       nvl.updateElementsInGraph([], …)
│                     │              mit hidden:true/false je Relationship
└─────────────────────┘
```

---

## 4. Engine-spezifische Details

### 4.1 Cytoscape (`cytoscape-viewer.js`)

**Versteck-Mechanismus:** Klasse `.cgv-edge-hidden` mit der Property
`display: none`. Cytoscape berücksichtigt `display: none` sowohl beim
Hit-Test als auch beim Layout — versteckte Edges nehmen also keinen
Hit-Pixel ein und räumen ihren Layout-Slot, was Folge-Renderings
beschleunigt.

**Registrierung:** Im `cy.style()`-Chain innerhalb von `boot()`
(`cytoscape-viewer.js:329`):

```javascript
cy.style()
    .selector('.cgv-faded')
    .style({ 'opacity': 0.18 })
    .selector('.cgv-highlighted')
    .style({ 'border-width': 4, 'border-style': 'solid', 'border-color': '#E74C3C', 'opacity': 1 })
    .selector('node.cgv-node-hover')
    .style({ 'border-color': '#4A90E2', 'border-width': 3 })
    .selector('.cgv-edge-hidden')
    .style({ 'display': 'none' })          // ← Edge-Filter
    .update();
```

**Anwendung:**
- `highlightNeighborhood(cy, node)` setzt `edgeFilter = { type: 'node', nodeId }`
  und taggt alle Edges, die nicht Teil des 1-Hop-Neighborhoods sind, mit
  `cgv-edge-hidden`.
- `applyLegendHighlight(hex)` setzt `edgeFilter = { type: 'cluster', hex }`
  und taggt Edges, deren Source **und** Target beide nicht im Cluster sind.
  Edges mit genau einem Endpoint im Cluster (Bridge-Edges) bleiben mit
  normaler grauer Linienfarbe sichtbar.

**Toggle:**
- `wireCytoscapeEvents` (`cytoscape-viewer.js:1329`) ruft beim Toggle-Pfad
  `clearNeighborhoodHighlight(cy)` auf, das den Filter mit auf `null`
  setzt.
- Der Hintergrund-Handler ruft ebenfalls `clearNeighborhoodHighlight`
  + ggf. `clearLegendHighlight`.
- `clearLegendHighlight` (`cytoscape-viewer.js:1695`) entfernt zusätzlich
  `cgv-edge-hidden` und setzt `edgeFilter = null`.

**Bridge-Edge Sonderfall:** Im Cluster-Filter werden Edges, die genau
einen Endpoint im Cluster haben (`matchedSet[sId] || matchedSet[tId]`)
bewusst **nicht** versteckt — sie bleiben als graue Linien sichtbar, damit
der User die Verbindungen zwischen Clustern sehen kann. Edges mit beiden
Endpoints im Cluster werden zusätzlich in der Cluster-Farbe eingefärbt
(`line-color`, `target-arrow-color`).

### 4.2 Sigma (`sigma-viewer.js`)

Sigma hat zwei Renderer-Pfade: WebGL via `Sigma` und Canvas-2D via die
lokale `CanvasRenderer`-Klasse. Der Edge-Filter wirkt auf beide, weil der
`Sigma`-Pfad das `_render` der WebGL-Renderer-Schicht nicht direkt
patchbar macht; stattdessen wird der Filter auf der `edgeReducer`-Ebene
umgesetzt. Der primäre Render-Pfad im Projekt ist die `CanvasRenderer`
(siehe `sigma-viewer.js:314`), die im Detail dokumentiert ist.

**Versteck-Mechanismus:** Skip-Filter im `_render`-Loop. Der Filter wird
auf zwei Ebenen ausgewertet:

1. **`CanvasRenderer._render`** (`sigma-viewer.js:600`) — der Edge wird im
   `forEachEdge`-Loop übersprungen, **bevor** Stroken, Arrowhead und
   Label berechnet werden. Spart CPU auf großen Graphen.
2. **`CanvasRenderer._hitTest`** (`sigma-viewer.js:749`) — der Edge wird
   beim Hit-Test ignoriert, damit der User keine versteckten Edges
   versehentlich anklickt (z. B. wenn ein Edge genau unter der Maus liegt,
   aber durch den Filter ausgeblendet ist).

**Property-Spiegelung:** Da `_render` und `_hitTest` als
`CanvasRenderer`-Methoden keinen Zugriff auf das IIFE-Top haben, wird der
Filter über `renderer.edgeFilter` gespiegelt. Der Konstruktor setzt
`this.edgeFilter = null` als Default (`sigma-viewer.js:347`).

**Filter-Funktion:**

```javascript
if (filter.type === 'node') {
    var nid = filter.nodeId;
    filterFn = function (edge) {
        return graph.source(edge) === nid || graph.target(edge) === nid;
    };
} else if (filter.type === 'cluster') {
    var fh = (filter.hex || '').toLowerCase();
    var ec = currentEffectiveColors || {};
    var lc = currentLeidenColors || {};
    filterFn = function (edge) {
        var sId = graph.source(edge), tId = graph.target(edge);
        var sCol = ((ec[sId] || lc[sId] || '') + '').toLowerCase();
        var tCol = ((ec[tId] || lc[tId] || '') + '').toLowerCase();
        return sCol === fh || tCol === fh;
    };
}
```

**Anwendung:**
- `attachRendererEvents.clickNode` (`sigma-viewer.js:1093`) — vergleicht
  `edgeFilter.nodeId === id` für den Toggle, ersetzt sonst den Filter.
- `attachRendererEvents.clickEdge` (`sigma-viewer.js:1113`) — räumt einen
  aktiven Filter auf (Edge bleibt selektiert).
- `attachRendererEvents.clickStage` (`sigma-viewer.js:1122`) — räumt den
  Filter auf und feuert `vg_notifySelectionCleared`.
- Palette-Row-Click (`sigma-viewer.js:1722`) — setzt den Cluster-Filter
  mit Toggle auf derselben Hex-Farbe.

**Reset:** `vg_clear` (`sigma-viewer.js:1797`), `vg_dispose`
(`sigma-viewer.js:1828`) und `highlightLegend(null)` setzen `edgeFilter
= null` und spiegeln das auf `renderer.edgeFilter`.

### 4.3 NVL (`nvl-graph-viewer.js`)

**Versteck-Mechanismus:** NVL kennt **keine** `hidden`-Property auf
Relationships — der ursprüngliche Plan, das Ausblenden über
`{ id, hidden: true }`-Updates an `nvl.updateElementsInGraph` zu lösen,
funktioniert nicht (NVL akzeptiert das Property im Update, speichert es,
liest es aber in keinem der drei Renderer-Pfade). Die einzige
zuverlässige Methode ist, die auszublendenden Relationships aus dem
Graph zu **entfernen** und bei Bedarf wieder einzufügen.

**Anwendung:** Über `nvl.removeRelationshipsWithIds(...)` für die
auszublendenden Rels und `nvl.addAndUpdateElementsInGraph([], [...])` für
das Wiederherstellen. Dazwischen hält das IIFE ein Backup
(`hiddenRelBackups`) der ursprünglichen Relationship-Properties, damit
beim Re-Insert Farbe, Caption, Width und Overlay-Icon erhalten bleiben.

**Helper `applyEdgeFilter`** (Sigma-konform):

```javascript
function applyEdgeFilter() {
    if (!nvl || !nvlReady) return;
    var rels = nvl.getRelationships();
    var ec = currentEffectiveColors || {};
    var lc = currentLeidenColors || {};
    // 1) Welche Rels sollen aktuell sichtbar sein?
    var keepSet = {};
    rels.forEach(function (r) {
        if (computeRelKeep(r, edgeFilter, ec, lc)) keepSet[r.id] = true;
    });
    // 2) Backup-Pool nachziehen (Rels, die gerade ausgeblendet sind
    //    und laut Filter wieder sichtbar sein sollen).
    var restoreIds = [];
    Object.keys(hiddenRelBackups).forEach(function (id) {
        if (computeRelKeep(hiddenRelBackups[id], edgeFilter, ec, lc)) {
            restoreIds.push(id);
        }
    });
    // 3) Sichtbare Rels, die laut Filter weg sollen → Backup + remove.
    var removeIds = [];
    rels.forEach(function (r) {
        if (!keepSet[r.id] && !hiddenRelBackups[r.id]) {
            removeIds.push(r.id);
            hiddenRelBackups[r.id] = cloneRelProps(r);
        }
    });
    if (removeIds.length > 0) {
        try { nvl.removeRelationshipsWithIds(removeIds); } catch (e) { ... }
    }
    if (restoreIds.length > 0) {
        var toRestore = restoreIds.map(function (id) { return hiddenRelBackups[id]; });
        try { nvl.addAndUpdateElementsInGraph([], toRestore); } catch (e) { ... }
        restoreIds.forEach(function (id) { delete hiddenRelBackups[id]; });
    }
}
```

`computeRelKeep(rel, filter, ec, lc)` entspricht der Sigma-Logik
(`sigma-viewer.js:601-616`): `null` ⇒ immer sichtbar; `type:'node'` ⇒
Source oder Target ist die selektierte Node; `type:'cluster'` ⇒
Source- oder Target-Farbe matcht die Cluster-Hex (effective oder
Leiden). Das Ergebnis ist dieselbe Filter-Semantik wie bei Cytoscape
(`cgv-edge-hidden`-Klasse) und Sigma (`filterFn`-Skip in `_render`).

**Trade-off:** `addAndUpdateElementsInGraph` triggert im Gegensatz zu
`updateElementsInGraph` einen kurzen Render-Pulse, weil die Rels neu
in den internen Graph-State eingefügt werden. Bei großen Graphen
(> 10k Rels) kann ein mehrfacher Filter-Wechsel messbar Reflows
verursachen — das ist die einzige Möglichkeit, die der NVL-Bundle
(Stand `nvl-bundle.js`) hergibt, weil er keine `hidden`-Property
unterstützt.

**Cluster-Edges und Bridge-Edges:** bleiben sichtbar. Die Farbe jedes
Edges wird beim Aktivieren des Filters auf die Farbe der
**Quell-Node** gesetzt (`applyEdgeColors` in
`nvl-graph-viewer.js:507-580`) — Sigma-konform zu
`sigma-viewer.js:1653-1672` (`buildEdgeReducer`) und Cytoscape-konform
zu `cytoscape-viewer.js:2666-2667` (`data(sourceCommunityColor)`).
Cluster-Edges tragen damit die Cluster-Farbe der Source-Node; Bridges
behalten die Farbe ihrer (externen) Source-Node. Lookup-Reihenfolge:
`currentEffectiveColors[rel.from]` → `currentLeidenColors[rel.from]`
→ tatsächliche Farbe der Source-Node aus `nvl.getNodes()`
(= das, was `GraphNode.setColor(...)` über `toNvlNode` reingeschrieben
hat) → Default-Farbe aus dem Edge-Property. Dank der dritten Stufe
folgen NVL-Edges automatisch der Source-Farbe, auch wenn diese nur
über `GraphNode.setColor` (z.B. via GML-`color`-Attribut) gesetzt
wurde und kein Tag-/Cluster-Color-Mode aktiv ist.

**Node-Sichtbarkeit:** NVL blendet zusätzlich zu den Edges auch die
nicht-zugehörigen Nodes aus (`applyNodeFilter` in
`nvl-graph-viewer.js:662-725`). Beim Node-Click bleiben nur die
geklickte Node + ihre 1-Hop-Nachbarn sichtbar; beim Cluster-Click
bleiben Cluster-Members + Brücken-Nodes sichtbar. Das gleiche
Remove/Re-Insert-Pattern wie beim Edge-Filter kommt zum Einsatz;
`hiddenNodeBackups` ist das Node-Pendant zu `hiddenRelBackups`. Nach
dem Re-Insert werden die ursprünglichen Layout-Positionen per
`nvl.setNodePositions(..., false)` wiederhergestellt, damit die
sichtbar werdenden Nodes nicht "springen". Reihenfolge im Restore-Pfad:
erst Nodes (`applyNodeFilter`), dann Edges — sonst wirft
`nvl.addAndUpdateElementsInGraph` einen Validierungsfehler wegen
fehlender Endpoint-Nodes.

**Node-Highlighting:** Beim Node-Click wird `selected: true` per
`nvl.updateElementsInGraph` auf die geklickte Node gesetzt (NVL zeichnet
dann nativ einen Border). Ein Cluster-Dimm auf nicht-gematchte Nodes
ist nicht mehr nötig, weil diese Nodes durch `applyNodeFilter` ohnehin
aus dem Graph entfernt werden.

**Anwendung:**
- `onNodeClick` (`nvl-graph-viewer.js:251`) — Toggle auf gleicher
  `nodeId`, sonst ersetzen.
- `onRelationshipClick` (`nvl-graph-viewer.js:271`) — Filter wird
  aufgehoben.
- `onCanvasClick` (`nvl-graph-viewer.js:293`) — Filter wird aufgehoben.
- Palette-Row-Click (`nvl-graph-viewer.js:1276`) — Cluster-Filter mit
  Toggle auf gleicher Hex-Farbe.

**Reset:** `vgv_clear` (`nvl-graph-viewer.js:540`), `vgv_hideColorPalette`
(`nvl-graph-viewer.js:790`) und `vgv_applyColorPalette` mit
`enabled=false` (`nvl-graph-viewer.js:773`) räumen den Filter auf.

---

## 5. Interaktion mit bestehenden Features

### 5.1 Cluster-Edges-Tabelle (Cytoscape)

`renderEdgesTable(hex)` (`cytoscape-viewer.js:1798`) wird weiterhin aus
`applyLegendHighlight` aufgerufen und listet **alle** relevanten Edges
(Intra + Bridge) in der Tabelle — unabhängig davon, ob sie visuell
ausgeblendet sind oder nicht. Die Tabelle ist damit eine vollständige
Referenz, das Canvas zeigt nur die visuelle Hervorhebung.

### 5.2 Node-Highlight (rote Border bei Selektion)

Cytoscape behält die bestehende `node:selected` / `edge:selected`-Regel
aus `defaultStyle()`. Die Selektion einer Node zeigt weiterhin den roten
Border über den `node:selected`-Selektor; der Edge-Filter wirkt
**zusätzlich** auf die Kanten. Die `clearNeighborhoodHighlight`-Funktion
löscht weiterhin **nicht** die `:selected`-Klasse (die über
`node.unselect()` in den Event-Handlern entfernt wird).

### 5.3 Community-View (Cytoscape)

`wireCommunitySelectionEvents` (`cytoscape-viewer.js:3128`) nutzt dieselben
`clearNeighborhoodHighlight` / `clearLegendHighlight`-Funktionen wie der
Normal-View. Damit greift die Toggle-Logik auch im Community-View
automatisch, ohne dass dort eigene Anpassungen nötig sind.

### 5.4 Tooltips

Die Tooltips (Cytoscape via `mouseover`, Sigma via `enterNode` /
`enterEdge`, NVL via `hoverHandler.onHover`) sind vom Edge-Filter nicht
betroffen. Hover über eine ausgeblendete Node zeigt weiterhin den Tooltip
— der Edge-Filter wirkt nur auf die Kanten, nicht auf die Nodes selbst.

### 5.5 Such- und Context-Menu-Funktionen

`vgv_requestNodeContextMenu` / `vgv_requestRelationshipContextMenu`
werden unabhängig vom Edge-Filter aufgerufen — das Kontextmenü
funktioniert auch für versteckte Edges, falls der User den Filter
explizit über die API zurücksetzt und dann einen Edge-Click
nachstellt.

---

## 6. Test-Szenarien

| # | Aktion | Erwartung |
|---|--------|-----------|
| 1 | Node A klicken | Edges von/zur Node A sichtbar, alle anderen Edges versteckt. Node A selbst zeigt den Selektions-Border. |
| 2 | Erneut Node A klicken | Alle Edges wieder sichtbar (Toggle aus). |
| 3 | Node A, dann Node B klicken | Filter wechselt zu B; Edges von/zu B sichtbar, Edges von/zu A versteckt. |
| 4 | Node A, dann Hintergrund klicken | Filter aufgehoben, alle Edges sichtbar. |
| 5 | Node A, dann direkt einen sichtbaren Edge anklicken | Filter aufgehoben, Edge als selektiert markiert. |
| 6 | Palette-Zeile X klicken | Edges von/zu Nodes in Cluster X sichtbar (Intra + Bridge). Andere Edges versteckt. |
| 7 | Palette-Zeile X erneut klicken | Alle Edges wieder sichtbar (Toggle aus). |
| 8 | Palette-Zeile X, dann andere Zeile Y | Filter wechselt zu Y. |
| 9 | Node A, dann Palette-Zeile X | Cluster-Filter ersetzt Node-Filter. |
| 10 | Palette-Zeile X, dann Node A | Node-Filter ersetzt Cluster-Filter. |
| 11 | Palette deaktivieren (`vgv_hideColorPalette`) | Cluster-Filter wird aufgehoben, alle Edges sichtbar. |
| 12 | Graph clear (`vg_clear`) | Filter wird auf `null` zurückgesetzt, keine verwaisten Referenzen. |
| 13 | Hit-Test auf verstecktem Edge | Klick geht durch, es wird die Node dahinter (oder der Hintergrund) getroffen. |

---

## 7. Erweiterungspunkte

Falls das Verhalten auf weitere Selektionen ausgedehnt werden soll
(z. B. Path-Highlighting, Multi-Node-Selection), ist die zentrale
Stelle die `edgeFilter`-Variable:

1. Neuen Filter-Typ in den Setz-Stellen (`clickNode`, `clickEdge`,
   `applyLegendHighlight`, …) registrieren.
2. Filter-Funktion in `_render` / `_hitTest` (Sigma) bzw. in
   `applyEdgeFilter` (NVL) bzw. in `highlightNeighborhood` /
   `applyLegendHighlight` (Cytoscape) erweitern.
3. Reset in den entsprechenden `clear*`-Funktionen sicherstellen.
