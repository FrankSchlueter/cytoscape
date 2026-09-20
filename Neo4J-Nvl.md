---
title: "wie kann ich neo4j nvl in eine java rap anwendung zur graph visualsisierung einbetten"
source: "https://www.google.com/search?q=wie+kann+ich+neo4j+nvl+in+eine+java+rap+anwendung+zur+graph+visualsisierung+einbetten&sca_esv=cf5c3a640caff83a&sxsrf=APpeQnvvKiLydhwu4wmcyqSMuCZpBEH4Kw%3A1787114186183&ei=ijKFasaoCM2Uxc8PmKDOmQg&iflsig=ABILxe8AAAAAaoVAmpWIXS8JlNXKbcRioD3q5t1hWvJ4&uact=5&sclient=gws-wiz&udm=50&fbs=ABfTbFUyxjQn9bne4DuflY2dNqqK7N177jWgWpM5fkywM1UejB7tg32RbLFEmja3tIVUafSzeSlOyNd5_GotMH_hNIgLxQAjnLJ9PYBDxLQ7GRJVybHbP4b-3FTzRFU0F6gj_Wj9jCGr3HQY_MEDNTsDlZuWvQsLtvHz3NgADqQI0VqFdLkO6OLhIoUooa9BG1gSYDAQK11M&aep=10&ntc=1&mstk=AUtExfCvZVkmVZe29bYrAYq7PNMz4lWX-WdVrcUZLlS91cZF_qHwnTS8luWO5TgFKcpiHAF80eePZK7xuq5is_hhnSHDP5TqoiLFdwXb3BOs4JMj-lGoGfCdRA6z6B7PvQ7EGDWiLgMCwizJmX-kfdDzDj7zbY9QPkMxBfs32bSMdQFZp9STH0swa9tmsgcx2MOvSjqJlWKZFKOrPBVKZeySYMWP9wUk6WMYUAhdo2h7gAoKjtLxE4XqZ9GhHb0QVVtFxYxCDDU9Q588IP9GimUbLD39dPcZeyePw5glkaLP2PZIJi_PKomtqEjG6WN5Y9ueFTIDPpGnCvrjkw&aioh=3&csuir=1&atvm=2&mtid=LTOFara9EqaPxc8PwfDJ6Q8"
author:
published:
created: 2026-08-19
description:
tags:
  - "clippings"
---
In einem **Spring Boot** Projekt mit **Maven** und **Eclipse RAP** binden Sie JavaScript-Bibliotheken am besten über das **Spring Boot statische Ressourcenverzeichnis** (`src/main/resources/static/`) ein. 

Da `@neo4j-nvl/base` als NPM-Paket konzipiert ist und kein offizielles, fertiges "Single-File"-CDN (wie ein einfaches `<script>`\-Tag für unpkg) anbietet, haben Sie zwei Hauptwege: den modernen, unkomplizierten Weg über **Vite/Webpack** (Bundler) oder das direkte Herunterladen der kompilierten JS-Datei. 

Hier sind die zwei besten Ansätze für Ihr Setup: 

---

---
title: "wie kann ich neo4j nvl in eine java rap anwendung zur graph visualsisierung einbetten"
source: "https://www.google.com/search?q=wie+kann+ich+neo4j+nvl+in+eine+java+rap+anwendung+zur+graph+visualsisierung+einbetten&sca_esv=cf5c3a640caff83a&sxsrf=APpeQnvvKiLydhwu4wmcyqSMuCZpBEH4Kw%3A1787114186183&ei=ijKFasaoCM2Uxc8PmKDOmQg&iflsig=ABILxe8AAAAAaoVAmpWIXS8JlNXKbcRioD3q5t1hWvJ4&uact=5&sclient=gws-wiz&udm=50&fbs=ABfTbFUyxjQn9bne4DuflY2dNqqK7N177jWgWpM5fkywM1UejB7tg32RbLFEmja3tIVUafSzeSlOyNd5_GotMH_hNIgLxQAjnLJ9PYBDxLQ7GRJVybHbP4b-3FTzRFU0F6gj_Wj9jCGr3HQY_MEDNTsDlZuWvQsLtvHz3NgADqQI0VqFdLkO6OLhIoUooa9BG1gSYDAQK11M&aep=10&ntc=1&mstk=AUtExfCvZVkmVZe29bYrAYq7PNMz4lWX-WdVrcUZLlS91cZF_qHwnTS8luWO5TgFKcpiHAF80eePZK7xuq5is_hhnSHDP5TqoiLFdwXb3BOs4JMj-lGoGfCdRA6z6B7PvQ7EGDWiLgMCwizJmX-kfdDzDj7zbY9QPkMxBfs32bSMdQFZp9STH0swa9tmsgcx2MOvSjqJlWKZFKOrPBVKZeySYMWP9wUk6WMYUAhdo2h7gAoKjtLxE4XqZ9GhHb0QVVtFxYxCDDU9Q588IP9GimUbLD39dPcZeyePw5glkaLP2PZIJi_PKomtqEjG6WN5Y9ueFTIDPpGnCvrjkw&aioh=3&csuir=1&atvm=2&mtid=LTOFara9EqaPxc8PwfDJ6Q8"
author:
published:
created: 2026-08-19
description:
tags:
  - "clippings"
---
Ansatz 1: Der Bundler-Weg (Empfohlen für Produktion)

Dieser Weg nutzt ein kleines Frontend-Tooling innerhalb Ihres Maven-Projekts, um `@neo4j-nvl/base` und seine Abhängigkeiten in eine einzige, optimierte JavaScript-Datei zu packen. 

1\. Frontend-Ordner im Maven-Projekt anlegen

Erstellen Sie in Ihrem Projekt die Ordnerstruktur `src/main/frontend/`. 

2\. NPM initialisieren und NVL installieren

Öffnen Sie ein Terminal in `src/main/frontend/` und führen Sie aus: 

bash

```
npm init -y
npm install @neo4j-nvl/base vite
```

Verwende Code mit Vorsicht.

3\. Build-Skript in `package.json` definieren

Passen Sie die `package.json` so an, dass Vite die Bibliothek als globales Modul in Ihr Spring Boot statisches Verzeichnis ausgibt: 

json

```
{
  "name": "rap-nvl-bundler",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "build": "vite build"
  },
  "dependencies": {
    "@neo4j-nvl/base": "^1.0.0"
  },
  "devDependencies": {
    "vite": "^5.0.0"
  }
}
```

Verwende Code mit Vorsicht.

4\. Einstiegspunkt erstellen (`src/main/frontend/index.js`)

Machen Sie die NVL-Bibliothek im globalen `window`\-Objekt des Browsers verfügbar: 

javascript

```
import { NVL } from '@neo4j-nvl/base';

// NVL global für das RAP Browser-Widget registrieren
window.Neo4jNVL = NVL;
```

Verwende Code mit Vorsicht.

5\. Vite Konfiguration (`src/main/frontend/vite.config.js`)

Konfigurieren Sie Vite so, dass das Ergebnis direkt im `static`\-Ordner von Spring Boot landet: 

javascript

```
import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'index.js'),
      name: 'Neo4jNVLBundle',
      fileName: 'nvl-bundle',
      formats: ['iife'] // Sofort ausgeführte Funktion für den Browser
    },
    outDir: resolve(__dirname, '../resources/static/js'),
    emptyOutDir: true
  }
});
```

Verwende Code mit Vorsicht.

6\. Maven-Build automatisieren (`pom.xml`)

Nutzen Sie das `frontend-maven-plugin`, damit bei jedem Maven-Build automatisch `npm install` und `npm run build` ausgeführt werden: 

xml

```
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.0</version>
    <executions>
        <execution>
            <id>install node and npm</id>
            <goals>
                <goal>install-node-and-npm</goal>
            </goals>
            <configuration>
                <nodeVersion>v20.11.0</nodeVersion>
            </configuration>
        </execution>
        <execution>
            <id>npm install</id>
            <goals>
                <goal>npm</goal>
            </goals>
            <configuration>
                <workingDirectory>src/main/frontend</workingDirectory>
                <arguments>install</arguments>
            </configuration>
        </execution>
        <execution>
            <id>npm run build</id>
            <goals>
                <goal>npm</goal>
            </goals>
            <configuration>
                <workingDirectory>src/main/frontend</workingDirectory>
                <arguments>run build</arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

In Ihrem Java RAP Core-Code rufen Sie die HTML-Datei schließlich über das `Browser`\-Widget auf: 

java

```
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;

public class GraphVisualizationComponent {

    public void createPartControl(Composite parent) {
        Browser browser = new Browser(parent, SWT.NONE);
        
        // Lädt die statische Datei aus dem Spring Boot /static/ Ordner
        // (Spring Boot mapped den statischen Ordner standardmäßig auf den Root-Pfad)
        browser.setUrl("/nvl-graph.html"); 
        
        // Beispiel: Daten nach dem Laden aus Java heraus an JavaScript übergeben
        browser.addProgressListener(new org.eclipse.swt.browser.ProgressAdapter() {
            @Override
            public void completed(org.eclipse.swt.browser.ProgressEvent event) {
                String nodesJson = "[{\"id\":\"1\",\"labels\":[\"Java\"],\"properties\":{\"name\":\"Spring Boot\"}}]";
                String edgesJson = "[]";
                
                // Ruft die im HTML definierte JS-Funktion auf
                browser.execute("window.updateGraphData('" + nodesJson + "', '" + edgesJson + "');");
            }
        });
    }
}
```

## NVL Overlay-Icon Demo

Ein zweiter Entry-Point demonstriert NVL-native `overlayIcon`-Rendering:

```
http://localhost:8085/nvl-icon
```

Liefert einen synthetischen 10-Node / 20-Edge-Graph mit:
- 10 unterschiedlichen Node-Hintergrundfarben (NVL-native `color`)
- 8 SVG-Icons aus `/static/icons/`, zyklisch allen 10 Nodes zugewiesen
- 5 Nodes mit Annotation-Char in Kreis (eigene Annotation-Farbpalette, unabhängig von Node-Farbe)
- 5 Nodes ohne Annotation

API auf `GraphNode`:

```java
// Nur Icon, ohne Annotation
node.setSvgOverlayIcon("java-16-svgrepo-com.svg");

// Icon + Annotation-Char in Kreis mit konfigurierter Hintergrundfarbe
node.setSvgOverlayIcon("java-16-svgrepo-com.svg", 'C', "#FF6B6B");
```

Cytoscape und vis-network mappen die neue Methode intern auf `setSvgIcon(...)` — das bestehende Composite-Badge-Rendering wird wiederverwendet.

