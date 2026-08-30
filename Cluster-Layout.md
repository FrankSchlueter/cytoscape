Hier ist Ihre vollständige und praxisnahe Dokumentation, um das Haarknäuel-Syndrom in Cytoscape.js effektiv zu lösen.
Die fertige Datei wurde für Sie generiert: cytoscape_cluster_guide.md.
## Dokumentation: Geclusterte Visualisierung komplexer Graphen in Cytoscape.js
Um bei einem hochkomplexen Graphen mit Zyklen, bidirektionalen Beziehungen und extremen Kantengewichten (1 bis 10.000) ein strukturiertes Bild statt eines dichten Haarknäuels zu erhalten, müssen Sie die Clusteranalyse direkt in die physikalische Kräfteberechnung des Layouts einspeisen.
------------------------------
## 1. Das Architektur-Konzept (Die 3er-Säule)
Das Problem bei Standard-Layouts ist, dass extreme Gewichte ($10^4$) die mathematischen Formeln sprengen und Zyklen die Knoten im Zentrum kollabieren lassen. Die Lösung basiert auf drei Schritten:

   1. Pre-Processing (Kanten-Logarithmierung): Extreme Gewichtsunterschiede werden gestaucht.
   2. Compound Nodes (Physische Barrieren): Die Clusteranalyse definiert virtuelle "Eltern-Knoten" (Containers), die Cytoscape zwingen, Gruppen räumlich zu trennen.
   3. fCoSE Layout (Multi-Level-Kräfte): Ein Layout-Algorithmus, der explizit mit Clustern und Gewichten umgehen kann.

------------------------------
## 2. Schritt-für-Schritt-Implementierung## Schritt 1: Cluster-Zugehörigkeit berechnen
Nutzen Sie vor dem Rendering ein Community-Detection-Verfahren (z. B. den Louvain-Algorithmus über Bibliotheken wie jlouvain oder serverseitig via cdlib). Jeder Knoten benötigt eine zugewiesene Cluster-ID.
## Schritt 2: Datenstruktur mit Compound-Nodes aufbauen
Erstellen Sie für jedes Cluster einen unsichtbaren Über-Knoten (parent). Weisen Sie Ihren echten Knoten dieses Parent-Element zu.

```javascript
const elements = [
  // 1. Definition der virtuellen Cluster-Gehäuse (Parents)
  { data: { id: 'cluster_A', isCluster: true } },
  { data: { id: 'cluster_B', isCluster: true } },

  // 2. Physische Knoten mit Parent-Verknüpfung
  { data: { id: 'node_1', parent: 'cluster_A' } },
  { data: { id: 'node_2', parent: 'cluster_A' } },
  { data: { id: 'node_3', parent: 'cluster_B' } },

  // 3. Kanten mit normalisierten Log-Gewichten
  // (Originalgewichte von 1-10000 werden mathematisch gestaucht)
  { data: { id: 'e1', source: 'node_1', target: 'node_2', weight: 8500, logWeight: 9.05 } },
  { data: { id: 'e2', source: 'node_2', target: 'node_1', weight: 300,  logWeight: 5.70 } }, // Bidirektional
  { data: { id: 'e3', source: 'node_2', target: 'node_3', weight: 5,    logWeight: 1.79 } }  // Cluster-Brücke
];
```


## Schritt 3: Mathematische Kanten-Stauchung
Nutzen Sie für die Layoutkräfte zwingend den natürlichen Logarithmus der Gewichte:
```java
logWeight = ln(OriginalGewicht + 1)
```

Dadurch rücken extrem starke Knoten nicht zu nah aneinander, und schwache Knoten fliegen nicht aus dem Sichtfeld.
------------------------------
## 3. Die optimale Layout-Konfiguration: cytoscape-fcose
Verwenden Sie das Erweiterungs-Plug-in fCoSE (Fast Compound Spring Embedder). Es ist die performanteste Wahl für verschachtelte Cluster und zyklische, bidirektionale Graphen.

```javascript
const layoutOptions = {
  name: 'fcose',
  
  // Qualität auf Maximum für komplexe Zyklen
  quality: 'proof', 
  randomize: true, // Verhindert das Feststecken in lokalen Minima
  
  // Cluster-Separation steuern (Wichtig gegen Haarknäuel!)
  nestingFactor: 0.1,           // Je kleiner, desto dichter bleiben Knoten im eigenen Cluster
  gravityRangeCompound: 2.5,    // Erhöht die Abstoßung zwischen unterschiedlichen Clustern
  gravityCompound: 3.0,         // Drückt Cluster-Boxen aktiv auseinander
  
  // Dynamische Kräfte basierend auf Log-Gewichten
  edgeElasticity: (edge) => {
    // Hohes Log-Gewicht = stärkere Federkraft (Knoten rücken zusammen)
    return 1 / (edge.data('logWeight') || 1);
  },
  idealEdgeLength: (edge) => {
    // Geringes Log-Gewicht = längere Kanten (schiebt Fremdknoten weg)
    return 120 * (1 / (edge.data('logWeight') || 1));
  },
  
  // Abstände der Cluster-Brücken definieren
  nodeRepulsion: 6500,               // Allgemeine Knotenabstoßung hochhalten
  idealInterClusterEdgeLength: 300   // Zwingt Brücken-Kanten zwischen Clustern lang zu werden
};
``` 

------------------------------
## 4. CSS-Styling für bidirektionale Entwirrung
Damit Hin- und Rückkanäle (Bidirektionalität) bei identischen Knotenpaaren nicht übereinanderliegen, müssen Sie gekrümmte Kurven erzwingen.

```javascript
const cyStyle = [
  // Standard-Knoten
  {
    selector: 'node',
    style: {
      'width': 45,
      'height': 45,
      'background-color': '#4A90E2',
      'label': 'data(id)'
    }
  },
  // Visuelle Cluster-Gehäuse stylen (hilft dem Auge bei der Orientierung)
  {
    selector: 'node[isCluster]',
    style: {
      'background-opacity': 0.04,
      'background-color': '#2ECC71',
      'border-width': 2,
      'border-color': '#2ECC71',
      'border-style': 'dashed',
      'padding': 30
    }
  },
  // Bidirektionale & gewichtete Kanten entwirren
  {
    selector: 'edge',
    style: {
      'curve-style': 'bezier',       // Zwingend erforderlich für parallele/bidirektionale Kanten!
      'control-point-step-size': 45,   // Abstand zwischen dem Hin- und Rückkanal-Bogen
      'target-arrow-shape': 'triangle',
      // Visuelle Kantenbreite skaliert dezent mit dem Log-Gewicht
      'width': 'mapData(logWeight, 1, 10, 1.5, 9)', 
      'line-color': '#A2B1C6',
      'target-arrow-color': '#A2B1C6',
      'opacity': 0.8
    }
  }
];
```

------------------------------
## 5. Profi-Tricks bei extremen Datenmengen

Layout-Filterung (Kanten-Schnitt): Berechnen Sie das fCoSE-Layout ausschließlich mit Kanten, die ein logarithmisches Gewicht von über z.B. 4.0 haben. Fügen Sie die sehr schwachen Kanten (1 bis 50 im Echtwert) erst visuell hinzu, nachdem das Layout fertig berechnet ist (layout.run()). Das verhindert, dass "Hintergrundrauschen" die Cluster-Struktur verzerrt.
Edge Bundling: Wenn zu viele Kanten zwischen zwei Clustern verlaufen, nutzen Sie das Zusatz-Plug-in cytoscape-edge-bundling. Es bündelt Kantenbahnen wie Kabelstränge und schafft sofortige visuelle Ordnung.

