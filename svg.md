# SVG-Rendering für Cytoscape- und vis-Network-Knoten

Lessons learned, Strategien und Fallstricke aus der Implementierung der SVG-Badge-Pipeline für Cytoscape.js und vis-Network. Beide Viewer teilen sich denselben Java-Serialisierungspfad, weisen aber subtil unterschiedliche Renderer-Pfade auf, die zu überraschenden Regressions führen, wenn man nicht aufpasst.

## Architekturüberblick

```
                         ┌───────────────────────────────┐
                         │ GraphNode.setSvgShape(...)    │
                         │   • svgImage = {label,        │
                         │                   type,       │
                         │                   color}      │
                         │   • image = base64 data: URI  │
                         └────────────┬──────────────────┘
                                      │
                                      ▼
                         ┌─────────────────────────────┐
                         │ GraphNode.toCytoscapeNode() │
                         │   • Cytoscape: base64       │
                         └────────────┬────────────────┘
                                      │
                                      ▼
              ┌───────────────────────┴─────────────────────────────────────────────┐
              │                                                                     │
   ┌──────────▼────────────────────────────────┐                          ┌─────────▼──────────────┐
   │ CytoscapeJsBridge                         │                          │ VisJsBridge            │
   │  • cgv_applyNodeConfig (Style-Selector)   │                          │  • vgv_applyNodeImages │
   │  • cgv_applyNodeImages (image update)     │                          │    (DataSet.update)    │
   └──────────┬────────────────────────────────┘                          └─────────┬──────────────┘
              │                                                                     │
              ▼                                                                     ▼
   ┌────────────────────────────┐                                     ┌───────────────────────────────┐
   │ cytoscape-viewer.js        │                                     │ vis-graph-viewer.js           │
   │  • imageNodeStyle-         │                                     │  • applySvgImage (Normalize)  │
   │    Selector (background-   │                                     │  • nodes.update               │
   │    image: data(image))     │                                     │  • network.redraw()           │
   │  • cgv_applyNodeImages     │                                     │  • vgv_applyNodeImages        │
   │    (n.data('image', uri))  │                                     │    (DataSet.update + redraw)  │
   └────────────────────────────┘                                     └───────────────────────────────┘
```

## Erkenntnisse

### 1. Cytoscape splittet URL-Listen an Kommas — Base64 ist Pflicht

Cytoscape.js parst `background-image`-Style-Werte als **URL-Liste** vom Typ `urls`. Intern splittet es den String an jedem Komma. Ein `data:image/svg+xml;charset=utf-8,<url-encoded-svg>`-URI enthält **viele Kommas**:

- direkt nach dem Präfix (`data:image/svg+xml;charset=utf-8,`)
- im SVG-Body, z. B. in Pfad-Koordinaten, Multi-Wert-Attributen wie `font-family="Segoe UI, Arial, sans-serif"` (URL-encoded als `%2C`)

Cytoscape interpretiert jeden Splitter als separate URL. Die meisten Splitter sind keine gültigen URLs, das Bild lädt nicht. Resultat: Badge ist unsichtbar.

**Lösung**: **Base64-codierung** für beide Viewer. Base64-Alphabet (`A-Za-z0-9+/=`) enthält keine Kommas, keine Leerzeichen, keine Sonderzeichen. Cytoscape splittet Base64-URIs korrekt als **eine** URL.

**Code-Stelle**: `GraphNode.toSvgDataUri(String svgBody)` — einzige Helper-Methode, die alle Data-URIs produziert.

### 2. URL-Encoding (`+` vs `%20`) bricht vis-Network

Java's `URLEncoder.encode(svg, UTF_8)` codiert Leerzeichen als `+` (form-encoding). Browser-Data-URI-Decoder dekodieren `+` aber **nicht** als Leerzeichen — sie interpretieren es als Literal. RFC 3986 verlangt für Data-URIs `%20`.

Symptom: vis-network rendert das Bild nicht, obwohl `new Image(); image.src = uri;` ohne Fehler läuft — der Browser dekodiert die URI falsch und das Bild bleibt leer.

**Lösung**: Base64-Encoding löst auch diesen Bug, weil es keine Leerzeichen enthält. Die alte URL-encoded-Variante (`toVisNetworkDataUri`) brauchte einen JS-seitigen `+`-zu-`%20`-Reparatur-Helper (`vgv_normalizeSvgDataUri`), der mit Base64-Encoding obsolet wurde und entfernt werden konnte.

### 3. Cytoscape berechnet Edges gegen falsche Anker-Positionen wenn Images noch laden

Wenn `applyElements` ein `cy.batch(remove, add)` macht und **danach synchron** `cy.layout({name: 'preset'}).run()` aufruft, läuft das Layout mit den **Default-Bounding-Boxen** der Nodes (40×40). Die Image-Loads sind asynchron und noch nicht abgeschlossen.

Symptom: Edges werden zwischen den Default-Anker-Punkten gezeichnet. Wenn die Images fertig laden, werden die Bounding-Boxen größer, **aber die Edges bleiben auf den falschen Anker-Punkten**. Resultat: "Edges verschwinden" nach `Load Data...`.

**Lösung**: Layout-Run **nach** den Image-Loads starten:

```js
function preloadSvgImagesAndRedraw() {
    // ...
    var pending = uris.length;
    uris.forEach(function (uri) {
        var im = new Image();
        im.onload = im.onerror = function () {
            pending--;
            if (pending <= 0 && !fired) {
                fired = true;
                runPostLoadLayout();   // Layout NACH allen Loads
            }
        };
        im.src = uri;
    });
}
```

`runPostLoadLayout()` enthält den `cy.layout(...).run()` und `cy.fit()`-Aufruf. So nutzt das Layout die finalen Image-Bounding-Boxen.

### 4. vis-Network's `DataSet.update()` rendert nicht, wenn sich nichts ändert

`nodes.update(updates)` triggert vis-network's `'update'`-Event. vis-network prüft für jeden Node, ob sich Werte geändert haben — wenn nicht, wird der Node **nicht neu gezeichnet**.

Symptom: Recoloring mit **gleichem Wert** zeigt keine Änderung. Die Nodes sind da, aber vis-network rendert sie nicht neu.

**Lösung**: expliziter `network.redraw()` nach `nodes.update()`:

```js
window.vgv_applyNodeImages = function (updates) {
    nodes.update(updates);
    try { network.redraw(); } catch (e) { /* ignore */ }
};
```

### 5. vis-Network's `nodes.update()` mit unbekannten IDs erzeugt Phantom-Nodes

`nodes.update([{id: 'unknown', image: ...}])` interpretiert die ID als **Add**-Operation, wenn der Node nicht existiert. Resultat: ein Phantom-Node mit dem ID wird erzeugt. Vis-Network hat keinen Schutz gegen unbekannte IDs.

**Lesson**: `vgv_applyNodeImages` filtert Updates für nicht-existente IDs heraus (durch `getElementById`-Check im JS-Code). Auf Java-Seite muss `applyRecolorsBoth` die Updates aus `currentData` ableiten, damit die IDs immer existieren.

### 6. Cytoscape braucht `preloadSvgImagesAndRedraw` auch ohne Image-URIs

Wenn der Graph **keine Image-URIs** enthält (z. B. Plain-Nodes mit `background-color`), springt `preloadSvgImagesAndRedraw` per `if (uris.length === 0) return` raus — ohne `cy.resize()`. Cytoscape's Canvas-Größe wird nicht aktualisiert, der Layout-Run läuft mit falscher Canvas-Größe.

**Lösung**: `cy.resize()` muss immer laufen, auch ohne URIs:

```js
if (uris.length === 0) {
    fireResize();
    runPostLoadLayout();
    return;
}
```

### 7. Cytoscape's `cy.batch` ist async — Layout nach `applyElements`

`cy.batch(remove, add)` löst den Render erst im nächsten RAF-Tick aus. Wenn danach **synchron** Layout-Code läuft, ist der Render-Pass noch nicht erfolgt. Das Layout arbeitet mit alten Canvas-Daten.

**Lesson**: Layout-Runs in Cytoscape müssen in `cy.batch(...)` gewrapped sein oder auf den nächsten Render-Tick warten. Wir lösen das, indem `runPostLoadLayout` als Reaktion auf den Image-Load-Event läuft — dann ist der Render-Pass aus `cy.batch` längst abgeschlossen.

## Strategien

### Einheitliches Wire-Format für beide Viewer

**Base64** ist die einzige Kodierung, die für Cytoscape UND vis-network funktioniert:

| Aspekt | Base64 | URL-encoded |
|---|---|---|
| Cytoscape-URL-Listen-Parsing | ✅ keine Kommas | ❌ Kommas zerstören die URI |
| Browser-`+`/`%20`-Decoding | ✅ `+` ist gültiges Base64-Zeichen | ❌ `+` wird als Literal interpretiert |
| CORS-Bypass | ✅ data: ist same-origin | ✅ data: ist same-origin |
| Größe | ~33% größer als URL-encoded | kompakter |

**Konsequenz**: `GraphNode.toSvgDataUri(String)` ist die einzige Stelle, an der Data-URIs produziert werden. Beide Viewer-Bridges (`CytoscapeJsBridge`, `VisJsBridge`) konsumieren dasselbe Format.

### Java rendert SVGs, Browser konsumiert sie

**Architektur-Prinzip**: Java ist die Single Source of Truth für SVG-Inhalte. Der Browser rendert keine SVGs selbst — er dekodiert nur die Data-URI und zeigt sie an. Änderungen am Badge-Inhalt (Farbe, Icon, Type-Char) erfordern eine **neue** Data-URI vom Java-Server, kein client-seitiges Re-Rendering.

**Vorteile**:
- Konsistenz: Cytoscape und vis-Network zeigen exakt dasselbe Badge
- Recolor-Flow ist trivial: Java rendert mit neuer Farbe, schickt URI, Browser zeigt
- Keine SVG-Generation-Logik im JS-Code

### CSS-Klassen vs. Per-Node-Updates

Cytoscape hat eine **Stylesheet-Engine** mit `selector { property: value }`-Regeln. Recoloring funktioniert über Style-Selector-Updates:
```js
{ selector: 'node[nodeType="BatchReader"]', style: { 'background-color': '#FF00FF' } }
```
**Kein Per-Node-Update nötig** für Plain-Nodes.

vis-Network hat **keine** Stylesheet-Engine — Recoloring muss **per Node** über `nodes.update()` passieren:
```js
nodes.update([{ id: 'r1', color: { background: '#FF00FF' } }])
```

**Konsequenz**: `SvgBadgeColorUpdater.applyRecolorsBoth` produziert für beide Viewer unterschiedliche Update-Listen:
- `applyRecolors` (Cytoscape): nur `{id, image}` für SVG-Badges (Plain-Nodes werden via Style-Selector geupdatet)
- `applyRecolorsBoth` (vis-Network): `{id, image}` für SVG-Badges **oder** `{id, color}` für Plain-Nodes

### Asynchroner Image-Load vor Layout

**Cytoscape's `preloadSvgImagesAndRedraw`-Pattern**:
1. Sammle alle `data:image/...`-URIs der Nodes.
2. Erstelle ein `Image()`-Objekt pro URI, setze `src`.
3. Warte auf alle `onload`/`onerror`-Callbacks.
4. Wenn alle fertig: `n.emit('background')` für jeden Image-Node, `cy.resize()`, dann Layout + Fit.

**vis-Network hat das Pattern nicht nötig** — sein `imagelist`-Cache lädt Images intern und synchronisiert mit `DataSet.update`.

## Fallstricke

### Race-Condition: Layout läuft synchron vor Image-Loads

**Symptom**: Edges verschwinden nach `Load Data...`

**Root Cause**: `cy.layout({name:'preset'}).run()` läuft sofort nach `cy.add(elements)` und benutzt die Default-Bounding-Boxen (40×40), nicht die finalen Image-Boxen.

**Fix**: Layout-Run in den `im.onload`-Handler verschieben — `runPostLoadLayout()` triggert erst nach allen Image-Loads.

### Cytoscape zeigt "alten Graph" zwischen den Loads

**Symptom**: Kurzes Aufflackern des alten Graphs nach `Load Data...`

**Root Cause**: `cy.batch(remove, add)` ist asynchron. Der alte Graph bleibt sichtbar, bis der Render-Pass im RAF-Tick läuft.

**Fix**: `cy.resize()` aus dem Image-Load-Handler triggert den Resize-Event, das den Render synchronisiert. Wir laden Images asynchron, dann läuft `cy.resize()` + Layout als Reaktion.

### vis-Network rendert Badge nicht

**Symptom**: Nur Tooltips sichtbar, kein Badge.

**Root Cause** (historisch): `applySvgIcon` baute ein einfaches Rounded-Rect-Badge statt des Java-SVGs mit Icon. `setSvgShape`-Descriptor wurde verworfen, Java-Image wurde überschrieben.

**Fix**: `applySvgImage` löscht nur den Descriptor, behält das Java-Image. `applySvgIcon` ist Dead Code und wurde entfernt.

### `cy.resize()` in `preloadSvgImagesAndRedraw` löst Resize-Event aus

**Effekt**: Cytoscape ruft Resize-Listener auf, die `cy.fit()` triggern. Das passt die View an alle Nodes an.

**Wichtig**: `preloadSvgImagesAndRedraw` muss `cy.resize()` **immer** aufrufen, auch ohne Image-URIs, sonst bleibt die Canvas-Größe veraltet und Edges werden nicht neu gezeichnet.

### vis-network-Style-Property `color.background` vs. `color.fill`

vis-Network akzeptiert `{background, highlight, hover, border, inherit, opacity}` für `color.background`-Updates. **Nicht** `{fill}`. Wir verwenden `{background, border}` — beides korrekt.

**Wichtig**: `applyRecolorsBoth` produziert `{background, border}` für Plain-Nodes, NICHT `{fill}`. Sonst ignoriert vis-Network das Update.

### Cytoscape-PNG-Image-Loads sind synchron

Im Gegensatz zu vis-Network wartet Cytoscape NICHT asynchron auf Image-Loads. `cy.drawNode` prüft `imageObj.complete` und zeichnet das Bild nur, wenn `true`. Wenn das Bild noch nicht geladen ist, überspringt Cytoscape das Image — die Layer-Cache-Version wird **ohne** Image gespeichert.

**Konsequenz**: Ohne `preloadSvgImagesAndRedraw` werden Badges unsichtbar, weil der Layer-Cache das leere Image zwischenspeichert. Mit Preload wird der Layer-Cache **nach** dem Image-Load gezeichnet.

### Cytoscape's `data:image/svg+xml` vs. `data:image/png`

Cytoscape's Image-Cache akzeptiert **beide** Formate, aber SVG-Images sind ressourceneffizienter (kein Pixel-Buffer, kleinere Data-URIs). PNG-Images wären 5-10× größer.

**Empfehlung**: SVG-Base64 für alle Image-Badges.

## Code-Landkarte

| Datei | Verantwortlichkeit |
|---|---|
| `GraphNode.renderSvgIcon4` | Produziert SVG-Body mit Icon + Annotation-Kreis + Type-Char |
| `SvgRenderer.renderSvgIconWithAnnotation` | Low-Level SVG-Builder für Annotation-Badges |
| `GraphNode.setSvgShape` | Setzt `svgImage`-Descriptor und pre-rendert `image`-Data-URI |
| `GraphNode.recolorSvgShape` | Mutiert `svgImage.color` und regeneriert Data-URI |
| `GraphNode.toCytoscapeNode` / `toVisNetworkData` | Serialisiert Node für jeweiligen Viewer |
| `GraphNode.toSvgDataUri` | **Einzige** Data-URI-Factory (Base64) |
| `CytoscapeJsBridge.applyNodeConfig` | Pusht Config + Recoloring-Updates an Cytoscape |
| `VisJsBridge.applyNodeConfig` | Pusht Recoloring-Updates an vis-Network |
| `SvgBadgeColorUpdater` | Pure-logic Helper, testbar ohne Bridge |
| `cytoscape-viewer.js: imageNodeStyle` | Cytoscape-Style-Selector `node[?image]` mit `background-image: data(image)` |
| `cytoscape-viewer.js: cgv_applyNodeImages` | Setzt `n.data('image', uri)` + emit('background') + preload + layout |
| `cytoscape-viewer.js: preloadSvgImagesAndRedraw` | Wartet auf Image-Loads, dann Layout-Run |
| `vis-graph-viewer.js: vgv_setData` | `nodes.add(...)` mit `applySvgImage` (löscht Descriptor) |
| `vis-graph-viewer.js: vgv_applyNodeImages` | `nodes.update(...)` + `network.redraw()` |

## Test-Strategien

### Static Source-Tests (kein Browser nötig)

- `CytoscapeViewerJsSourceTest`: prüft dass `imageNodeStyle` existiert, dass `applyElements` `preloadSvgImagesAndRedraw` ruft, dass `runPostLoadLayout` im `im.onload`-Handler aufgerufen wird.
- `VisGraphViewerJsSourceTest`: prüft dass `vgv_applyNodeImages` `nodes.update` UND `network.redraw` ruft, dass `applySvgIcon`/`vgv_createSvgIcon` nicht mehr existieren.

### Java-Unit-Tests

- `SvgBadgeColorUpdaterTest`: testet `resolveEffectiveColor`-Reihenfolge (globalTagColors > tagColors > labelColors), testet dass `applyRecolorsBoth` für Badge-Nodes `{id, image}` und für Plain-Nodes `{id, color}` produziert.

### Headless-Browser-Tests (mit Chromium + Puppeteer)

- Reproduzieren des vollständigen User-Flows: `applyData → applyNodeConfig → applyNodeImages → applyData` und prüfen, dass Edges/Nodes sichtbar bleiben.
- Decode von Base64-Data-URIs im Test und prüfen, dass die richtige Farbe im SVG-Body landet.

## Quick-Reference: Welcher Pfad für welchen Bug?

| Symptom | Ursache | Fix |
|---|---|---|
| Cytoscape-Badge unsichtbar | URL-encoded URI mit Komma | Base64-Codierung |
| vis-Badge unsichtbar | `+`-vs-`%20`-Encoding-Bug | Base64-Codierung |
| vis zeigt keine Nodes nach Recolor | `DataSet.update` rendert nicht ohne Property-Change | `network.redraw()` |
| Cytoscape-Edges verschwinden nach Recolor | Layout läuft vor Image-Loads | `runPostLoadLayout` im Image-Load-Handler |
| Cytoscape "alter Graph"-Flash | `cy.batch` ist async | `cy.resize()` synchron im Load-Handler |
| Plain-Nodes nicht Recolored (Cytoscape) | Style-Selector nicht aktualisiert | `applyNodeConfig` ruft Style-Push |
| Plain-Nodes nicht Recolored (vis) | vis hat keine Style-Engine | `applyRecolorsBoth` pusht `color`-Updates |

## Quellen

- `cytoscape-graph-viewer/src/main/resources/static/cytoscape/cytoscape-viewer.js`
- `cytoscape-graph-viewer/src/main/resources/static/vis-graph/vis-graph-viewer.js`
- `cytoscape-graph-viewer/src/main/java/de/tk/dependencyanalyse/rapui/visgraph/data/GraphNode.java`
- `cytoscape-graph-viewer/src/main/java/de/tk/dependencyanalyse/rapui/visgraph/data/SvgRenderer.java`
- `cytoscape-graph-viewer/src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/CytoscapeJsBridge.java`
- `cytoscape-graph-viewer/src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/VisJsBridge.java`
- `cytoscape-graph-viewer/src/main/java/de/tk/dependencyanalyse/rapui/visgraph/internal/SvgBadgeColorUpdater.java`
