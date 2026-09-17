/**
 * nvl-graph-viewer.js
 *
 * Bridge between NVL (@neo4j-nvl/base, rendered via the nvl-bundle.js Vite
 * IIFE) and the Java side via BrowserFunctions. Mirrors vis-graph-viewer.js
 * but speaks NVL on the wire.
 *
 * The bridge wires the official @neo4j-nvl/interaction-handlers (drag,
 * click, hover, pan, zoom) to the Java BrowserFunction protocol. Using
 * the official handlers - rather than custom pointer logic - gives us
 * correct hit-testing, multi-node drag, and proper interplay with NVL's
 * layout simulation (DragNodeInteraction uses setNodePositions with
 * updateLayout=true so manual moves stick while force-directed is running).
 *
 * Globals written by Java (read here):
 *   window.__nvl_nodes         -- NVLNode[] payload
 *   window.__nvl_relationships -- NVLRelationship[] payload
 *
 * Globals written by JS (Java reads via BrowserFunction):
 *   vgv_viewerReady                          ()  -- after NVL is sized
 *   vgv_notifyNodeSelected                   (id)
 *   vgv_notifyRelationshipSelected           (id)
 *   vgv_notifySelectionCleared               ()
 *   vgv_requestNodeContextMenu               (id, x, y)
 *   vgv_requestRelationshipContextMenu       (id, x, y)
 *   vgv_invokeContextMenuAction              (entryId)
 *   vgv_invokeContextMenuSubmenuAction       (entryId, childEntryId)
 */
(function () {
    'use strict';

    var nvl = null;
    var containerEl = null;
    var currentNodeIds = [];
    var currentRelIds = [];
    var lastLayout = 'forceDirected';
    var lastLayoutOptions = {};
    var physicsEnabled = true;
    var contextMenuCurrent = null;
    var pendingData = null;
    var nvlReady = false;

    // Color palette state — driven by applyNodeColors / setLeidenColors
    // from the Java bridge. The panel is auto-shown when at least one
    // non-empty color map is pushed, and auto-hidden by clear().
    var currentLeidenColors = {};
    var currentEffectiveColors = {};
    var paletteEntries = [];
    var paletteEnabled = false;
    var paletteCollapsed = false;
    // Edge-Filter: blendet nicht-relevante Edges aus, wenn eine Node
    // oder eine Palette-Zeile selektiert ist. null = alle Edges sichtbar.
    //   { type: 'node',    nodeId: '…'  }
    //   { type: 'cluster', hex:    '#…' }
    // Wird in den Click-Handlern gesetzt; applyEdgeFilter() überträgt den
    // Zustand auf NVL, indem ausgeblendete Rels per removeRelationshipsWithIds
    // entfernt und in hiddenRelBackups zwischengespeichert werden. Beim
    // Aufheben des Filters werden sie per addAndUpdateElementsInGraph wieder
    // eingefügt. NVL kennt keine "hidden"-Property auf Relationships, daher
    // ist dieses Remove/Re-Insert-Pattern die einzige zuverlässige Methode.
    var edgeFilter = null;
    // Backup der gerade ausgeblendeten Rels (Map relId → geklonte
    // Relationship-Properties). Werden beim Aufheben des Filters wieder
    // in den Graph eingefügt, damit keine Referenz / Farbe / Label
    // verloren geht.
    var hiddenRelBackups = {};
    // Backup der gerade ausgeblendeten Nodes (Map nodeId → geklonte
    // Node-Properties). Beim Aufheben des Filters via
    // addAndUpdateElementsInGraph wieder eingefügt; die ursprüngliche
    // Layout-Position wird über setNodePositions wiederhergestellt,
    // damit die sichtbar werdenden Nodes nicht "springen".
    var hiddenNodeBackups = {};
    // Sigma-konformer Selektions-Spiegel: eine Quelle der Wahrheit für
    // welchen Node-/Edge-Border NVL nativ zeichnen soll. selectedNodeId
    // treibt den roten NVL-nativen Border auf der geklickten Node,
    // selectedRelId treibt den selected:true + width-Boost auf der
    // geklickten Relationship.
    var selectedNodeId = null;
    var selectedRelId = null;
    // Width-Boost für die selektierte Relationship (Sigma-äquivalent zu
    // Math.max(2, attrs.size + 1.5)). Wird zusätzlich zum NVL-nativen
    // selected:true gesetzt, weil NVL die exakte visuelle Darstellung
    // von selected nicht dokumentiert und der width-Boost eine
    // zuverlässige zusätzliche Hervorhebung garantiert.
    var SELECTED_REL_WIDTH = 3;

    // Tooltip state
    var tooltipEl = null;

    // Interaction handler instances (set up after NVL construction)
    var dragHandler = null;
    var clickHandler = null;
    var hoverHandler = null;
    var panHandler;
    var zoomHandler;

    function javaCall(name) {
        var fn = null;
        var target = null;
        try {
            target = window;
            fn = target[name];
        } catch (e) {
            console.error('javaCall(' + name + ') - access error:', e);
        }
        if (typeof fn !== 'function') return undefined;
        try {
            return fn.apply(target, Array.prototype.slice.call(arguments, 1));
        } catch (e) {
            console.error('javaCall(' + name + ') - call error:', e);
            return undefined;
        }
    }

    function $(id) {
        return document.getElementById(id);
    }

    function init() {
        var container = $('vgv-frame');
        containerEl = container;
        if (!container) {
            setTimeout(init, 50);
            return;
        }
        if (!window.Neo4jNVL) {
            showError(container, 'NVL library not loaded');
            javaCall('vgv_viewerReady');
            return;
        }
        if (!window.Neo4jNVLInteractions) {
            showError(container, 'NVL interaction handlers not loaded');
            javaCall('vgv_viewerReady');
            return;
        }
        showLoading(container, 'Loading NVL\u2026');
        boot(container);
    }

    function boot(container) {
        if (nvl) return;
        var options = {
            disableTelemetry: true,
            layout: 'forceDirected',
            // NVL crashes with "f.match is not a function" when
            // setupNodeRendering reaches a node whose color is undefined
            // and styling.defaultNodeColor is also undefined.
            styling: {
                defaultNodeColor: '#C0C0C0',
                defaultRelationshipColor: '#A0A0A0'
            }
        };
        var callbacks = {
            onInitialization: function () { console.log('NVL: onInitialization'); },
            onError: function (err) {
                console.error('NVL error:', err && err.message ? err.message : err);
            },
            onLayoutComputing: function (computing) {
                console.log('NVL: onLayoutComputing', computing);
            },
            onLayoutDone: function () {
                // Layout simulation has settled - fit the viewport to
                // the final positions. Debounced so we don't re-fit on
                // every onLayoutDone during continuous d3Force runs.
                if (!pendingFitTimer) scheduleFit(0);
            },
            onZoomTransitionDone: function () { /* nop */ }
        };
        try {
            nvl = new window.Neo4jNVL(container, [], [], options, callbacks);
        } catch (e) {
            showError(container, 'NVL init failed: ' + (e && e.message ? e.message : e));
            console.error(e);
            javaCall('vgv_viewerReady');
            return;
        }

        try { window.nvl = nvl; } catch (e) {}

        // Watch for container size changes and re-fit when the container
        // grows from 0x0 to its actual size. The RAP iframe is often
        // 0x0 when the Browser widget is first constructed (before the
        // parent Composite is laid out), and NVL's initial viewport
        // gets pinned to whatever dimensions the container had at
        // boot. If the container grows later, the viewport stays
        // zoomed to the original 0x0 frame so the user sees a tiny
        // (or empty) section. This watcher fixes that.
        if (typeof ResizeObserver !== 'undefined') {
            var ro = new ResizeObserver(function (entries) {
                for (var i = 0; i < entries.length; i++) {
                    var e = entries[i];
                    var w = e.contentRect.width;
                    var h = e.contentRect.height;
                    if (w > 50 && h > 50 && nvl) {
                        // Container has grown - reset the viewport to
                        // a sane zoom and fit the graph.
                        try { nvl.setZoomAndPan(1.0, 0, 0); } catch (e2) {}
                        scheduleFit(0);
                    }
                }
            });
            ro.observe(container);
        }

        // Wire the official interaction handlers. They attach their own
        // event listeners to the container so we no longer need our own
        // pointer/mouse plumbing for selection, drag, hover, pan, zoom.
        // Callbacks are registered via updateCallback() (the constructor
        // takes only options, not callbacks - see interaction-handlers
        // README).
        try {
            dragHandler = new window.Neo4jNVLInteractions.DragNode(nvl, {});
            dragHandler.updateCallback('onDragStart', function (nodes, evt) {
                showCursor('grabbing');
                hideTooltip();
            });
            dragHandler.updateCallback('onDrag', function (nodes, evt) {
                hideTooltip();
            });
            dragHandler.updateCallback('onDragEnd', function (nodes, evt) {
                showCursor('');
            });

            clickHandler = new window.Neo4jNVLInteractions.Click(nvl, {
                selectOnClick: false
            });
            clickHandler.updateCallback('onNodeClick', function (node, hits, evt) {
                var id = node.id;
                // Edge-Filter: zweiter Klick auf dieselbe Node schaltet
                // den Filter wieder aus; ein Klick auf eine andere Node
                // ersetzt den aktiven Filter. Der Java-Callback wird
                // weiterhin in beiden Fällen gefeuert — die Selektion
                // der Node bleibt davon unabhängig.
                if (edgeFilter && edgeFilter.type === 'node' && edgeFilter.nodeId === id) {
                    edgeFilter = null;
                    selectedNodeId = null;
                } else {
                    edgeFilter = { type: 'node', nodeId: id };
                    selectedNodeId = id;
                }
                selectedRelId = null;
                applyEdgeFilter();
                applyNodeHighlight();
                applyRelHighlight();
                javaCall('vgv_notifyNodeSelected', id);
            });
            clickHandler.updateCallback('onRelationshipClick', function (rel, hits, evt) {
                // Direkter Edge-Klick räumt einen aktiven Filter auf,
                // damit der User nicht in einer Inkonsistenz landet
                // (Filter aktiv + Edge als selektiert markiert). Der
                // Edge selbst bekommt den Selektions-Highlight
                // (selectedRelId), die Knoten-Selektion wird gelöst.
                var hadFilter = !!edgeFilter;
                if (edgeFilter) {
                    edgeFilter = null;
                    applyEdgeFilter();
                }
                selectedNodeId = null;
                selectedRelId = rel.id;
                applyNodeHighlight();
                applyRelHighlight();
                if (hadFilter) {
                    // Filter wurde gerade aufgehoben — Java darf nichts
                    // als "selektiert" wähnen, was der Filter verdeckt hat.
                    javaCall('vgv_notifySelectionCleared');
                }
                javaCall('vgv_notifyRelationshipSelected', rel.id);
            });
            clickHandler.updateCallback('onCanvasClick', function (evt) {
                // Background-Klick räumt Filter + Selektion vollständig
                // auf, damit die NVL-nativen Borders (Node-Border,
                // Edge-width-Boost) konsistent verschwinden.
                var hadState = !!edgeFilter || !!selectedNodeId || !!selectedRelId;
                edgeFilter = null;
                selectedNodeId = null;
                selectedRelId = null;
                applyEdgeFilter();
                applyNodeHighlight();
                applyRelHighlight();
                if (hadState) {
                    javaCall('vgv_notifySelectionCleared');
                }
            });
            clickHandler.updateCallback('onNodeRightClick', function (node, hits, evt) {
                evt.preventDefault();
                javaCall('vgv_requestNodeContextMenu', node.id, evt.clientX, evt.clientY);
            });
            clickHandler.updateCallback('onRelationshipRightClick', function (rel, hits, evt) {
                evt.preventDefault();
                javaCall('vgv_requestRelationshipContextMenu', rel.id, evt.clientX, evt.clientY);
            });

            hoverHandler = new window.Neo4jNVLInteractions.Hover(nvl, {
                drawShadowOnHover: true
            });
            hoverHandler.updateCallback('onHover', function (element, hits, evt) {
                updateTooltip(element, evt);
            });

            // Enable Pan (drag on empty canvas) and Zoom (mouse wheel).
            // The official @neo4j-nvl/interaction-handlers attach their
            // own event listeners to the container, perform their own
            // hit-testing (so panning never starts on a node hit) and
            // call nvl.setPan() / nvl.setZoomAndPan() themselves with
            // NVL's built-in zoom limits enforced. The Zoom handler also
            // calls preventDefault() on the wheel event so the gesture
            // does not bubble up to the RAP scroll container.
            panHandler = new window.Neo4jNVLInteractions.Pan(nvl, {});
            zoomHandler = new window.Neo4jNVLInteractions.Zoom(nvl, {});
        } catch (e) {
            console.error('Interaction handler setup failed', e);
        }

        // Hide tooltip when the cursor leaves the container.
        container.addEventListener('mouseleave', hideTooltip);

        window.addEventListener('resize', function () {
            scheduleFit(200);
        });
        document.addEventListener('click', hideContextMenu);

        nvlReady = true;
        clearLoading(container);

        if (pendingData) {
            applyPendingData();
        }

        waitForViewerReadyWrapper();
    }

    function showCursor(value) {
        if (containerEl && containerEl.style) containerEl.style.cursor = value;
    }

    function showLoading(container, message) {
        if (!container || container.querySelector('.vgv-loading')) return;
        var box = document.createElement('div');
        box.className = 'vgv-loading';
        box.style.cssText = 'position:absolute;top:8px;left:8px;padding:6px 10px;'
            + 'background:#eef;border:1px solid #88c;color:#225;'
            + 'font-family:sans-serif;font-size:12px;z-index:10000;';
        box.textContent = message;
        container.appendChild(box);
    }

    function clearLoading(container) {
        if (!container) return;
        var el = container.querySelector('.vgv-loading');
        if (el && el.parentNode) el.parentNode.removeChild(el);
    }

    function showError(container, message) {
        if (!container) return;
        container.innerHTML = '';
        var box = document.createElement('div');
        box.style.cssText = 'position:absolute;top:8px;left:8px;right:8px;padding:12px;'
            + 'background:#fee;border:1px solid #c00;color:#600;'
            + 'font-family:monospace;font-size:12px;z-index:10000;';
        box.textContent = 'nvl-graph-viewer: ' + message;
        container.appendChild(box);
    }

    function ensureTooltip() {
        if (tooltipEl) return tooltipEl;
        tooltipEl = document.createElement('div');
        tooltipEl.id = 'vgv-tooltip';
        tooltipEl.style.cssText = [
            'position: fixed',
            'display: none',
            'background: #fffff0',
            'border: 1px solid #c0c0c0',
            'box-shadow: 2px 2px 6px rgba(0,0,0,0.2)',
            'padding: 6px 10px',
            'border-radius: 2px',
            'font-family: sans-serif',
            'font-size: 12px',
            'color: #000',
            'z-index: 10002',
            'pointer-events: none',
            'max-width: 360px',
            'white-space: pre-wrap'
        ].join(';');
        document.body.appendChild(tooltipEl);
        return tooltipEl;
    }

    function hideTooltip() {
        if (tooltipEl) tooltipEl.style.display = 'none';
    }

    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function renderTooltipHTML(element) {
        if (!element) return '';
        var lines = [];
        // Node
        if (element.labels !== undefined || element.caption !== undefined) {
            if (element.caption) lines.push('<b>' + escapeHtml(element.caption) + '</b>');
            if (element.labels && element.labels.length) {
                lines.push('<span style="color:#888">labels:</span> ' +
                    element.labels.map(escapeHtml).join(', '));
            }
            if (element.properties && Object.keys(element.properties).length > 0) {
                lines.push('<table style="border-collapse:collapse;margin-top:4px">');
                Object.keys(element.properties).forEach(function (k) {
                    lines.push('<tr><td style="color:#888;padding-right:8px;vertical-align:top">' +
                        escapeHtml(k) + '</td>' +
                        '<td>' + escapeHtml(String(element.properties[k])) + '</td></tr>');
                });
                lines.push('</table>');
            }
        } else {
            // Relationship
            if (element.type) lines.push('<b>' + escapeHtml(element.type) + '</b>');
            if (element.caption) lines.push('<span style="color:#888">' + escapeHtml(element.caption) + '</span>');
            if (element.properties && Object.keys(element.properties).length > 0) {
                lines.push('<table style="border-collapse:collapse;margin-top:4px">');
                Object.keys(element.properties).forEach(function (k) {
                    lines.push('<tr><td style="color:#888;padding-right:8px;vertical-align:top">' +
                        escapeHtml(k) + '</td>' +
                        '<td>' + escapeHtml(String(element.properties[k])) + '</td></tr>');
                });
                lines.push('</table>');
            }
        }
        return lines.join('');
    }

    function updateTooltip(element, evt) {
        if (!element) {
            hideTooltip();
            return;
        }
        var tip = ensureTooltip();
        tip.innerHTML = renderTooltipHTML(element);
        tip.style.display = 'block';
        var x = evt.clientX + 12;
        var y = evt.clientY + 12;
        tip.style.left = x + 'px';
        tip.style.top = y + 'px';
        var rect = tip.getBoundingClientRect();
        if (rect.right > window.innerWidth) {
            tip.style.left = (evt.clientX - rect.width - 12) + 'px';
        }
        if (rect.bottom > window.innerHeight) {
            tip.style.top = (evt.clientY - rect.height - 12) + 'px';
        }
    }

    /**
     * Entscheidet, ob eine Relationship gemäß des aktuellen Edge-Filters
     * sichtbar sein soll. Sigma-konform (siehe sigma-viewer.js _render):
     *   - null-Filter      → alle Edges sichtbar
     *   - type:'node'      → Edges mit der selektierten Node als Endpoint
     *   - type:'cluster'   → Edges mit mindestens einem Endpoint im Cluster
     *                        (Intra + Bridge-Edges bleiben sichtbar,
     *                        nur "extern→extern"-Edges werden entfernt)
     */
    function computeRelKeep(rel, filter, ec, lc) {
        if (!filter) return true;
        if (filter.type === 'node') {
            return (rel.from === filter.nodeId || rel.to === filter.nodeId);
        }
        if (filter.type === 'cluster') {
            var fh = (filter.hex || '').toLowerCase();
            var sCol = ((ec[rel.from] || lc[rel.from] || '') + '').toLowerCase();
            var tCol = ((ec[rel.to] || lc[rel.to] || '') + '').toLowerCase();
            return (sCol === fh || tCol === fh);
        }
        return true;
    }

    /**
     * Klont die Properties einer Relationship, die NVL beim Re-Insert
     * braucht. Wir kopieren nicht die kompletten internen Felder
     * (fromNode/toNode werden vom Controller neu gefüllt), sondern nur
     * die Properties, die der Aufrufer gesetzt hat — der Rest wird von
     * NVL gegen den State aufgelöst.
     */
    function cloneRelProps(r) {
        if (!r) return null;
        var out = { id: r.id, from: r.from, to: r.to };
        if (r.color !== undefined) out.color = r.color;
        if (r.width !== undefined) out.width = r.width;
        if (r.caption !== undefined) out.caption = r.caption;
        if (r.captionSize !== undefined) out.captionSize = r.captionSize;
        if (r.showLabel !== undefined) out.showLabel = r.showLabel;
        if (r.overlayIcon !== undefined) out.overlayIcon = r.overlayIcon;
        if (r.selected !== undefined) out.selected = r.selected;
        return out;
    }

    /**
     * Setzt die Farbe jedes Relationships auf die Farbe der Quell-Node.
     * Lookup-Reihenfolge: {@code currentEffectiveColors[rel.from]} →
     * {@code currentLeidenColors[rel.from]} → unverändert (default).
     * Synchronisiert auch die Backup-Einträge in {@code hiddenRelBackups},
     * damit beim Re-Insert eines gerade wiederhergestellten Rels die
     * aktuelle Source-Farbe mitwandert (sonst würde der Restore-Pfad mit
     * einer veralteten Farbe zurückspringen).
     *
     * <p>Aufgerufen nach jedem Push von Node-Farben
     * ({@code vgv_applyNodeColors} / {@code vgv_applyLeidenColors}),
     * nach {@code setDataInternal} und nach dem Restore-Pfad in
     * {@code applyEdgeFilter}. Diff-basiert: nur Relationships, deren
     * Farbe sich gerade ändert, werden per
     * {@code nvl.updateElementsInGraph} gepusht.</p>
     */
    function applyEdgeColors() {
        if (!nvl || !nvlReady) return;
        var rels = nvl.getRelationships();
        if (!rels || rels.length === 0) return;
        var ec = currentEffectiveColors || {};
        var lc = currentLeidenColors || {};
        var updates = [];
        for (var i = 0; i < rels.length; i++) {
            var r = rels[i];
            var srcColor = ec[r.from] || lc[r.from];
            if (srcColor && r.color !== srcColor) {
                updates.push({ id: r.id, color: srcColor });
            }
        }
        // Backup-Pool nachziehen — sonst käme ein gerade wieder
        // hergestellter Rel mit der zum Backup-Zeitpunkt aktuellen
        // Source-Farbe zurück, auch wenn die Source-Node inzwischen
        // umgefärbt wurde.
        Object.keys(hiddenRelBackups).forEach(function (id) {
            var br = hiddenRelBackups[id];
            var sc = ec[br.from] || lc[br.from];
            if (sc && br.color !== sc) br.color = sc;
        });
        if (updates.length > 0) {
            try { nvl.updateElementsInGraph([], updates); }
            catch (e) { console.error('applyEdgeColors failed', e); }
        }
    }

    /**
     * Klont die visuell relevanten Properties einer Node, die NVL beim
     * Re-Insert braucht. Positionen werden NICHT mitkopiert — die
     * werden separat über {@code nvl.setNodePositions} nachgeschoben,
     * damit der Layout-Slot erhalten bleibt.
     */
    function cloneNodeProps(n) {
        if (!n) return null;
        var out = { id: n.id };
        if (n.color !== undefined) out.color = n.color;
        if (n.opacity !== undefined) out.opacity = n.opacity;
        if (n.size !== undefined) out.size = n.size;
        if (n.caption !== undefined) out.caption = n.caption;
        if (n.captionSize !== undefined) out.captionSize = n.captionSize;
        if (n.labels !== undefined) out.labels = n.labels;
        if (n.showLabel !== undefined) out.showLabel = n.showLabel;
        if (n.overlayIcon !== undefined) out.overlayIcon = n.overlayIcon;
        if (n.selected !== undefined) out.selected = n.selected;
        return out;
    }

    /**
     * Berechnet die Menge der Node-IDs, die beim aktuellen Filter
     * sichtbar bleiben sollen. Semantik (siehe EdgeFilter.md §2):
     * <ul>
     *   <li>{@code null}-Filter → {@code null} (= alle sichtbar)</li>
     *   <li>{@code type:'node'} → selektierte Node + 1-Hop-Nachbarn
     *       (jede Node, die via einer sichtbaren ODER versteckten Edge
     *       an die selektierte Node angeschlossen ist)</li>
     *   <li>{@code type:'cluster'} → Cluster-Members
     *       (Treffer in {@code currentEffectiveColors} ODER
     *       {@code currentLeidenColors}) + Brücken-Nodes (jede Node,
     *       die via einer Edge mit einem Cluster-Member verbunden ist)</li>
     * </ul>
     */
    function computeKeptNodeIds(filter, ec, lc) {
        if (!filter) return null;
        var kept = {};
        if (filter.type === 'node') {
            kept[filter.nodeId] = true;
            var rels = nvl.getRelationships();
            if (rels) {
                for (var i = 0; i < rels.length; i++) {
                    var r = rels[i];
                    if (r.from === filter.nodeId) kept[r.to] = true;
                    if (r.to === filter.nodeId) kept[r.from] = true;
                }
            }
            // Auch ausgeblendete Edges berücksichtigen — sonst würden
            // Nachbarn über genau diese Edges verschwinden, obwohl die
            // Kante nur wegen des Filters weg ist.
            Object.keys(hiddenRelBackups).forEach(function (id) {
                var br = hiddenRelBackups[id];
                if (br.from === filter.nodeId) kept[br.to] = true;
                if (br.to === filter.nodeId) kept[br.from] = true;
            });
        } else if (filter.type === 'cluster') {
            var fh = (filter.hex || '').toLowerCase();
            Object.keys(ec).forEach(function (id) {
                if ((ec[id] || '').toLowerCase() === fh) kept[id] = true;
            });
            Object.keys(lc).forEach(function (id) {
                if ((lc[id] || '').toLowerCase() === fh) kept[id] = true;
            });
            var rels2 = nvl.getRelationships();
            if (rels2) {
                for (var j = 0; j < rels2.length; j++) {
                    var r2 = rels2[j];
                    var sCol = (ec[r2.from] || lc[r2.from] || '').toLowerCase();
                    var tCol = (ec[r2.to] || lc[r2.to] || '').toLowerCase();
                    if (sCol === fh || tCol === fh) {
                        kept[r2.from] = true;
                        kept[r2.to] = true;
                    }
                }
            }
            Object.keys(hiddenRelBackups).forEach(function (id) {
                var br2 = hiddenRelBackups[id];
                var sCol2 = (ec[br2.from] || lc[br2.from] || '').toLowerCase();
                var tCol2 = (ec[br2.to] || lc[br2.to] || '').toLowerCase();
                if (sCol2 === fh || tCol2 === fh) {
                    kept[br2.from] = true;
                    kept[br2.to] = true;
                }
            });
        }
        return kept;
    }

    /**
     * Überträgt den Node-Sichtbarkeits-Zustand auf NVL. NVL kennt keine
     * "hidden"-Property auf Nodes — die einzige zuverlässige Methode ist
     * das gleiche Remove/Re-Insert-Pattern, das auch
     * {@code applyEdgeFilter} verwendet.
     *
     * <p>Beim Remove werden die aktuellen Layout-Positionen in
     * {@code hiddenNodeBackups} mit-backupt. Beim Re-Insert via
     * {@code nvl.addAndUpdateElementsInGraph} werden die Positionen
     * anschließend per {@code nvl.setNodePositions} (zweites Argument
     * {@code false}, damit das Force-Directed-Layout nicht sofort
     * überschreibt) wieder eingespielt, damit sichtbar werdende Nodes
     * nicht an einen Layout-Default-Slot springen.</p>
     *
     * <p>Wird immer NACH {@code applyEdgeFilter} aufgerufen, weil die
     * 1-Hop-Berechnung gegen den vollständigen Edge-Bestand
     * (sichtbar + hidden-Backup) laufen muss.</p>
     */
    function applyNodeFilter() {
        if (!nvl || !nvlReady) return;
        var visible = nvl.getNodes();
        var kept = computeKeptNodeIds(
            edgeFilter, currentEffectiveColors || {}, currentLeidenColors || {});
        var removeIds = [];
        // Snapshot der aktuellen Positionen VOR dem Remove — danach
        // sind die Nodes aus nvl.getNodePositions() verschwunden.
        var posMap = {};
        var positions = nvl.getNodePositions();
        if (positions) {
            for (var p = 0; p < positions.length; p++) {
                posMap[positions[p].id] = positions[p];
            }
        }
        // 1) Sichtbare Nodes, die laut Filter raus müssen → backup + remove.
        for (var i = 0; i < visible.length; i++) {
            var n = visible[i];
            if (kept && !kept[n.id] && !hiddenNodeBackups[n.id]) {
                removeIds.push(n.id);
                var snap = cloneNodeProps(n);
                if (posMap[n.id]) {
                    snap.x = posMap[n.id].x;
                    snap.y = posMap[n.id].y;
                }
                hiddenNodeBackups[n.id] = snap;
            }
        }
        // 2) Versteckte Nodes, die laut Filter wieder sichtbar sein
        //    sollen → restore.
        var restoreIds = [];
        var restoreNodes = [];
        Object.keys(hiddenNodeBackups).forEach(function (id) {
            if (!kept || kept[id]) {
                restoreIds.push(id);
                restoreNodes.push(hiddenNodeBackups[id]);
            }
        });
        if (removeIds.length === 0 && restoreNodes.length === 0) return;
        if (removeIds.length > 0) {
            try { nvl.removeNodesWithIds(removeIds); }
            catch (e) {
                console.error('applyNodeFilter: removeNodesWithIds failed', e);
                // Backup wieder zurückrollen, damit ein späterer Versuch
                // nicht gegen einen verwaisten Eintrag läuft.
                removeIds.forEach(function (id) { delete hiddenNodeBackups[id]; });
            }
        }
        if (restoreNodes.length > 0) {
            try {
                nvl.addAndUpdateElementsInGraph(restoreNodes, []);
            } catch (e) {
                console.error('applyNodeFilter: addAndUpdateElementsInGraph failed', e);
                return;
            }
            // Positionen wiederherstellen. setNodePositions(..., false)
            // terminiert das Force-Layout-Update für diese Iteration,
            // damit unsere Positionen nicht sofort überschrieben werden.
            var restorePositions = [];
            restoreIds.forEach(function (id) {
                var hb = hiddenNodeBackups[id];
                if (hb && typeof hb.x === 'number' && typeof hb.y === 'number') {
                    restorePositions.push({ id: id, x: hb.x, y: hb.y });
                }
            });
            if (restorePositions.length > 0) {
                try { nvl.setNodePositions(restorePositions, false); }
                catch (e) {
                    console.error('applyNodeFilter: setNodePositions failed', e);
                }
            }
            restoreIds.forEach(function (id) { delete hiddenNodeBackups[id]; });
        }
    }

    /**
     * Überträgt den aktuellen Edge-Filter auf NVL. NVL kennt keine
     * "hidden"-Property auf Relationships, daher ist die einzige
     * zuverlässige Methode, nicht-zugehörige Rels aus dem Graph zu
     * entfernen und in hiddenRelBackups zwischenzuspeichern. Beim
     * Aufheben des Filters werden sie wieder per
     * addAndUpdateElementsInGraph eingefügt. Das Verfahren ist
     * differenz-basiert: nur Rels, deren Sichtbarkeit sich gerade
     * ändert, werden angefasst.
     *
     * <p>Sigma-äquivalent zu sigma-viewer.js _render:624
     * {@code if (filterFn && !filterFn(edge)) return;} — der Filter
     * wirkt direkt auf der Render-Schicht, bei NVL muss er auf der
     * Graph-Schicht wirken, weil NVL keine per-Frame-Skip-API hat.</p>
     */
    function applyEdgeFilter() {
        if (!nvl || !nvlReady) return;
        var rels = nvl.getRelationships();
        var ec = currentEffectiveColors || {};
        var lc = currentLeidenColors || {};

        // 1) Sichtbare-vs-versteckte Rels klassifizieren (aktueller
        //    + Backup-Pool werden zusammen betrachtet, damit der
        //    Restore-Pfad ebenfalls korrekt berechnet wird).
        var restoreIds = [];
        if (hiddenRelBackups) {
            Object.keys(hiddenRelBackups).forEach(function (id) {
                if (computeRelKeep(hiddenRelBackups[id], edgeFilter, ec, lc)) {
                    restoreIds.push(id);
                }
            });
        }
        var removeIds = [];
        if (rels) {
            for (var i = 0; i < rels.length; i++) {
                var r = rels[i];
                if (!computeRelKeep(r, edgeFilter, ec, lc)
                        && !hiddenRelBackups[r.id]) {
                    removeIds.push(r.id);
                    hiddenRelBackups[r.id] = cloneRelProps(r);
                }
            }
        }

        if (removeIds.length > 0) {
            try { nvl.removeRelationshipsWithIds(removeIds); }
            catch (e) { console.error('applyEdgeFilter: removeRelationshipsWithIds failed', e); }
        }
        // Restore-Pfad: erst Nodes wiederherstellen, dann Edges.
        // nvl.addAndUpdateElementsInGraph validiert, dass beide Endpoints
        // existieren — wenn wir die Edges vorher restoren, kracht der
        // Aufruf, weil die Endpoint-Nodes noch im hiddenNodeBackups-Pool
        // liegen. applyNodeFilter restauriert die benötigten Nodes; danach
        // filtern wir die wiederherzustellenden Rels noch nach existierenden
        // Endpoints (Defense-in-Depth, falls ein Node inzwischen
        // dauerhaft gelöscht wurde).
        applyNodeFilter();
        if (restoreIds.length > 0) {
            var toRestore = [];
            restoreIds.forEach(function (id) {
                var r = hiddenRelBackups[id];
                if (!r) return;
                if (nvl.getNodeById(r.from) && nvl.getNodeById(r.to)) {
                    toRestore.push(r);
                }
            });
            if (toRestore.length > 0) {
                try { nvl.addAndUpdateElementsInGraph([], toRestore); }
                catch (e) {
                    console.error('applyEdgeFilter: addAndUpdateElementsInGraph failed', e);
                    return;
                }
                toRestore.forEach(function (r) { delete hiddenRelBackups[r.id]; });
            }
        }
        // Gerade wiederhergestellte Rels brauchen ihre Source-Node-Farbe
        // (kann sich seit dem Backup geändert haben).
        applyEdgeColors();
    }

    /**
     * Überträgt den Selektions-Border auf die Nodes. NVL-native
     * roter Border via {@code selected:true}. Da non-matching Nodes
     * bei aktivem Filter bereits durch {@code applyNodeFilter}
     * ausgeblendet werden (statt nur gedimmt), entfällt die
     * Sigma-konforme FADE-Logik hier komplett — sichtbare Nodes
     * behalten ihre Originalfarbe, der Cluster-Highlight kommt allein
     * über die Node-Sichtbarkeit.
     */
    function applyNodeHighlight() {
        if (!nvl || !nvlReady) return;
        var nodes = nvl.getNodes();
        if (nodes.length === 0) return;
        var updates = [];
        var targetId = (edgeFilter && edgeFilter.type === 'node') ? edgeFilter.nodeId : null;
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            var shouldBeSelected = (n.id === targetId);
            if (!!n.selected !== shouldBeSelected) {
                updates.push({ id: n.id, selected: shouldBeSelected });
            }
        }
        if (updates.length > 0) {
            try { nvl.updateElementsInGraph(updates, []); }
            catch (e) { console.error('applyNodeHighlight failed', e); }
        }
    }

    /**
     * Setzt den Selektions-Highlight auf Relationships. Sigma-konform:
     * selektierte Edge erhält NVL-natives selected:true + einen width-
     * Boost (Sigma: Math.max(2, attrs.size + 1.5); wir verwenden
     * SELECTED_REL_WIDTH). Beim Aufheben wird selected:false gesetzt
     * und der width auf undefined (NVL fällt auf den edgeReducer-Default
     * zurück).
     */
    function applyRelHighlight() {
        if (!nvl || !nvlReady) return;
        var rels = nvl.getRelationships();
        if (!rels || rels.length === 0) return;
        var updates = [];
        var targetId = selectedRelId;
        for (var i = 0; i < rels.length; i++) {
            var r = rels[i];
            var shouldBeSelected = (r.id === targetId);
            if (!!r.selected !== shouldBeSelected) {
                updates.push({ id: r.id, selected: shouldBeSelected });
            }
            if (shouldBeSelected) {
                if (r.width !== SELECTED_REL_WIDTH) {
                    updates.push({ id: r.id, width: SELECTED_REL_WIDTH });
                }
            } else {
                // Restore nur, wenn wir diesen Rel vorher hochgeboostet
                // haben (also width === SELECTED_REL_WIDTH).
                if (r.width === SELECTED_REL_WIDTH) {
                    updates.push({ id: r.id, width: undefined });
                }
            }
        }
        if (updates.length > 0) {
            try { nvl.updateElementsInGraph([], updates); }
            catch (e) { console.error('applyRelHighlight failed', e); }
        }
    }

    function waitForViewerReadyWrapper() {
        if (typeof window.vgv_viewerReady === 'function') {
            javaCall('vgv_viewerReady');
        } else {
            setTimeout(waitForViewerReadyWrapper, 50);
        }
    }

    function applyPendingData() {
        if (!pendingData || !nvlReady || !nvl) return;
        setDataInternal(pendingData.nodes || [], pendingData.rels || []);
        pendingData = null;
    }

function setDataInternal(newNodes, newRels) {
        var existingNodeIds = nvl.getNodes().map(function (nd) { return nd.id; });
        var existingRelIds = nvl.getRelationships().map(function (rd) { return rd.id; });
        if (existingNodeIds.length > 0) nvl.removeNodesWithIds(existingNodeIds);
        if (existingRelIds.length > 0) nvl.removeRelationshipsWithIds(existingRelIds);
        // Edge-Filter-Caches mit-räumen, damit der nächste Graph-Aufbau
        // nicht gegen einen Filter/Backup läuft, der auf alte Node-IDs
        // verweist. Die alten Nodes sind gerade entfernt worden, also
        // sind alle hiddenRelBackups / hiddenNodeBackups jetzt verwaist.
        edgeFilter = null;
        selectedNodeId = null;
        selectedRelId = null;
        hiddenRelBackups = {};
        hiddenNodeBackups = {};
        if (newNodes.length > 0 || newRels.length > 0) {
            nvl.addAndUpdateElementsInGraph(newNodes, newRels);
        }
        currentNodeIds = newNodes.map(function (nd) { return nd.id; });
        currentRelIds = newRels.map(function (rd) { return rd.id; });
        // Edge-Farben aus den Source-Node-Farben ableiten. Bei einem
        // Erst-Load sind die Source-Farben noch nicht gesetzt (die
        // werden über vgv_applyNodeColors nachgeliefert), aber der
        // Aufruf ist trotzdem billig (no-op wenn keine Rels vorhanden
        // sind oder keine Source-Color matcht). Spätestens beim
        // Apply-Node-Colors-Aufruf werden die Farben korrekt gesetzt.
        applyEdgeColors();
        // Trigger the layout simulation. NVL does NOT auto-start a
        // simulation when nodes are added via addAndUpdateElementsInGraph
        // - we have to call restart() explicitly. We pass
        // retainPositions=false so newly-added nodes get positions
        // assigned by the chosen layout; previously-existing pinned
        // nodes are unaffected (NVL honours their pinned=true flag).
        // We do not pass a layout here because restart() merges its
        // options with the current ones, so the user's last layout
        // choice is preserved.
        try {
            nvl.restart({}, false);
        } catch (e) {
            console.error('setDataInternal: restart failed', e);
        }
        // Fit once the layout worker has had time to assign positions.
        // For 'free' and 'grid' layouts positions are set synchronously
        // so 50ms is plenty. For 'forceDirected'/'d3Force' the worker
        // runs async - we schedule an additional fit at 1500ms that
        // catches the settled state.
        scheduleFit(50);
        scheduleFit(1500);
    }

    var pendingFitTimer = null;
    function scheduleFit(delayMs) {
        if (pendingFitTimer) clearTimeout(pendingFitTimer);
        pendingFitTimer = setTimeout(function () {
            pendingFitTimer = null;
            try {
                var nodes = nvl.getNodes();
                // Only fit nodes that have actual positions - nodes
                // that haven't been laid out yet are at undefined
                // coordinates and would otherwise pull the viewport
                // towards (0,0).
                var ids = nodes
                    .filter(function (n) { return typeof n.x === 'number' && typeof n.y === 'number'; })
                    .map(function (n) { return n.id; });
                if (ids.length > 0) {
                    nvl.fit(ids, { padding: 40 });
                }
            } catch (e) {
                console.error('scheduleFit: failed', e);
            }
        }, delayMs || 0);
    }

    /* ----- API: data ----- */

    window.vgv_setData = function () {
        var nodes = window.__nvl_nodes || [];
        var rels = window.__nvl_relationships || [];
        if (!nvlReady) {
            pendingData = { nodes: nodes, rels: rels };
            return;
        }
        setDataInternal(nodes, rels);
        window.__nvl_nodes = null;
        window.__nvl_relationships = null;
    };

window.vgv_clear = function () {
        if (!nvlReady) return;
        var nodeIds = nvl.getNodes().map(function (n) { return n.id; });
        var relIds = nvl.getRelationships().map(function (r) { return r.id; });
        if (nodeIds.length > 0) nvl.removeNodesWithIds(nodeIds);
        if (relIds.length > 0) nvl.removeRelationshipsWithIds(relIds);
        currentNodeIds = [];
        currentRelIds = [];
        // Edge-Filter + Selection + Caches mit-räumen, damit der nächste
        // Graph-Aufbau nicht gegen Filter / Backups läuft, die auf
        // bereits entfernte Node-/Edge-IDs verweisen.
        edgeFilter = null;
        selectedNodeId = null;
        selectedRelId = null;
        hiddenRelBackups = {};
        hiddenNodeBackups = {};
    };

    window.vgv_fitToScreen = function () {
        if (!nvl) return;
        var ids = nvl.getNodes().map(function (n) { return n.id; });
        if (ids.length === 0) {
            try { nvl.resetZoom(); } catch (e) {}
            return;
        }
        try { nvl.fit(ids); } catch (e) {
            console.error('vgv_fitToScreen: NVL.fit failed', e);
        }
    };

    /* ----- API: layout & physics (design D7) ----- */

    function layoutForAlgorithm(algorithm) {
        switch (algorithm) {
            case 'FORCE_ATLAS_2D':
                return { layout: 'forceDirected', layoutOptions: {} };
            case 'BARNES_HUT':
            case 'REPULSION':
            case 'HIERARCHICAL_REPULSION':
                return { layout: 'd3Force', layoutOptions: {} };
            case 'HIERARCHICAL':
                return { layout: 'hierarchical', layoutOptions: { direction: 'down', packing: 'bin' } };
            case 'GRID':
                return { layout: 'grid', layoutOptions: {} };
            case 'CIRCULAR':
                return { layout: 'circular', layoutOptions: {} };
            case 'NONE':
                return { layout: 'free', layoutOptions: {} };
            default:
                return { layout: 'forceDirected', layoutOptions: {} };
        }
    }

    function directionForEnum(dir) {
        switch (dir) {
            case 'UP_DOWN':    return 'down';
            case 'DOWN_UP':    return 'up';
            case 'LEFT_RIGHT': return 'right';
            case 'RIGHT_LEFT': return 'left';
            default:           return 'down';
        }
    }

    window.vgv_setLayout = function (algorithm) {
        if (!nvl) return;
        var mapped = layoutForAlgorithm(algorithm);
        lastLayout = mapped.layout;
        lastLayoutOptions = mapped.layoutOptions || {};
        try {
            nvl.setLayout(mapped.layout);
            if (lastLayoutOptions && Object.keys(lastLayoutOptions).length > 0) {
                nvl.setLayoutOptions(lastLayoutOptions);
            }
            // Set LayoutType changes need restart() to re-run the
            // simulation. We pass retainPositions=true so any nodes
            // the user dragged manually stay where they are; the
            // remaining (un-pinned) nodes get new positions from the
            // chosen layout.
            nvl.restart({
                layout: mapped.layout,
                layoutOptions: lastLayoutOptions
            }, true);
        } catch (e) {
            console.error('vgv_setLayout: NVL.setLayout failed for ' + algorithm, e);
        }
        scheduleFit(800);
    };

    window.vgv_setPhysics = function (enabled) {
        if (!nvl) return;
        physicsEnabled = !!enabled;
        try {
            var layout = physicsEnabled ? lastLayout : 'free';
            var opts = physicsEnabled ? lastLayoutOptions : {};
            nvl.setLayout(layout);
            if (opts && Object.keys(opts).length > 0) {
                nvl.setLayoutOptions(opts);
            }
            nvl.restart({ layout: layout, layoutOptions: opts }, true);
        } catch (e) {
            console.error('vgv_setPhysics: failed', e);
        }
    };

    window.vgv_setPhysicsSolver = function (solver) {
        if (!nvl) return;
        var mapped;
        switch (solver) {
            case 'FORCE_ATLAS_2_BASED': mapped = { layout: 'forceDirected', layoutOptions: {} }; break;
            case 'BARNES_HUT':
            case 'REPULSION':
            case 'HIERARCHICAL_REPULSION':
            default:                     mapped = { layout: 'd3Force', layoutOptions: {} }; break;
        }
        try {
            nvl.setLayout(mapped.layout);
            nvl.restart({ layout: mapped.layout, layoutOptions: mapped.layoutOptions }, true);
            lastLayout = mapped.layout;
            lastLayoutOptions = mapped.layoutOptions;
        } catch (e) {
            console.error('vgv_setPhysicsSolver: failed for ' + solver, e);
        }
    };

    window.vgv_setHierarchicalDirection = function (dir) {
        if (!nvl) return;
        var direction = directionForEnum(dir);
        lastLayout = 'hierarchical';
        lastLayoutOptions = { direction: direction, packing: 'bin' };
        try {
            nvl.setLayout('hierarchical');
            nvl.setLayoutOptions({ direction: direction, packing: 'bin' });
            nvl.restart({ layout: 'hierarchical', layoutOptions: { direction: direction, packing: 'bin' } }, true);
        } catch (e) {
            console.error('vgv_setHierarchicalDirection: failed for ' + dir, e);
        }
    };

    // NVL does not expose hierarchical spacing parameters; no-op.
    window.vgv_setHierarchicalSpacing = function () { /* no-op */ };
    // NVL has no stabilization-iterations knob; no-op.
    window.vgv_setStabilizationIterations = function () { /* no-op */ };
    // NVL has no "fit on stabilization" knob; no-op.
    window.vgv_setAutoFitOnStabilization = function () { /* no-op */ };

    window.vgv_setOption = function (key, valueJson) {
        if (!nvl || !key) return;
        try {
            var value = JSON.parse(valueJson);
            var path = key.split('.');
            var opts = {};
            var cur = opts;
            for (var i = 0; i < path.length - 1; i++) {
                cur[path[i]] = cur[path[i]] || {};
                cur = cur[path[i]];
            }
            cur[path[path.length - 1]] = value;
            nvl.restart(opts, true);
        } catch (e) {
            console.error('vgv_setOption: failed for ' + key, e);
        }
    };

    /* ----- API: node configuration (design D13) -----
     *
     * NVL has no stylesheet engine like Cytoscape, so the Java-side
     * NodeColorResolver computes the effective per-node color and ships
     * the map via vgv_applyNodeColors. vgv_applyNodeConfig is kept for
     * backwards-compat with the bridge but delegates to the same
     * per-node update path (labelShapes etc. are still ignored — NVL
     * does not expose a shape concept).
     */

    function updateNodeColors(colorMap) {
        if (!nvlReady || !nvl || !colorMap) return;
        var ids = Object.keys(colorMap);
        if (ids.length === 0) return;
        var idsSet = new Set(ids);
        var existing = nvl.getNodes();
        var updates = [];
        for (var i = 0; i < existing.length; i++) {
            var node = existing[i];
            if (idsSet.has(node.id)) {
                updates.push({ id: node.id, color: colorMap[node.id] });
            }
        }
        if (updates.length === 0) return;
        try {
            nvl.updateElementsInGraph(updates, []);
        } catch (e) {
            console.error('updateNodeColors: nvl.updateElementsInGraph failed', e);
            return;
        }
        // Edge-Farben aus den neuen Source-Node-Farben ableiten.
        // Erst NACH dem erfolgreichen Node-Color-Update laufen lassen,
        // damit eine fehlgeschlagene Node-Aktualisierung nicht zu
        // inkonsistenten Edge-Farben führt.
        applyEdgeColors();
    }

    /**
     * Apply a per-node Leiden cluster color map (id → hex) to the NVL
     * nodes. Pairs with {@code NvlJsBridge.setLeidenColors} which is
     * invoked by {@code GraphConfigurationDialog} when the "Apply
     * Leiden Clustering" button is clicked. NVL has no stylesheet so
     * every node receives a per-node {@code {id, color}} update via
     * {@code nvl.updateElementsInGraph} — symmetric to the
     * vis-network handler.
     *
     * <p>Side-effect: also drives the auto-managed Color Palette panel.
     * The Java side pre-computes the palette entries and pushes them via
     * {@code vgv_applyColorPalette} right after this call lands, so this
     * handler only needs to cache the map for palette visibility tracking.</p>
     */
    window.vgv_applyLeidenColors = function (colors) {
        currentLeidenColors = (colors && typeof colors === 'object') ? colors : {};
        updateNodeColors(colors);
    };

    /**
     * Apply the engine-agnostic "effective per-node color" map produced
     * by {@code NodeColorResolver} (Java side). Pairs with
     * {@code NvlJsBridge.applyNodeColors} so the dialog's Tag-Colors
     * and Leiden-Colors buttons apply identically to all engines.
     *
     * <p>Side-effect: also drives the auto-managed Color Palette panel.
     * The Java side pre-computes the palette entries and pushes them via
     * {@code vgv_applyColorPalette} right after this call lands.</p>
     */
    window.vgv_applyNodeColors = function (effective) {
        currentEffectiveColors = (effective && typeof effective === 'object') ? effective : {};
        updateNodeColors(effective);
    };

    /**
     * Render the Color Palette panel. Pairs with
     * {@code NvlJsBridge.refreshPalette} which pushes one of these per
     * non-empty color map push. {@code enabled} controls visibility —
     * when false the panel hides but the entries are kept so toggling
     * back on restores the prior state.
     */
    window.vgv_applyColorPalette = function (entries, enabled) {
        var list = [];
        if (Array.isArray(entries)) {
            list = entries;
        } else if (typeof entries === 'string') {
            try { list = JSON.parse(entries) || []; } catch (e) { list = []; }
        }
        paletteEntries = list;
        var wasEnabled = paletteEnabled;
        paletteEnabled = !!enabled && list.length > 0;
        // Wenn das Panel deaktiviert wird (enabled=false), räumen wir
        // einen eventuell aktiven Cluster-Filter auf — sonst würde der
        // ausgeblendete Filter überleben, obwohl das Panel weg ist.
        if (wasEnabled && !paletteEnabled && edgeFilter && edgeFilter.type === 'cluster') {
            edgeFilter = null;
            applyEdgeFilter();
        }
        renderColorPalette();
    };

    /**
     * Hide the Color Palette panel. Pairs with {@code clear()} on the
     * Java side — clears the cached color maps and the panel state.
     */
    window.vgv_hideColorPalette = function () {
        paletteEntries = [];
        paletteEnabled = false;
        // Wenn der ausgeblendete Cluster-Filter noch aktiv war (z.B.
        // weil ein anderer Color-Mode deaktiviert wurde), wieder alle
        // Edges sichtbar schalten.
        if (edgeFilter && edgeFilter.type === 'cluster') {
            edgeFilter = null;
            applyEdgeFilter();
        }
        renderColorPalette();
    };

    /**
     * Rebuild the palette DOM. The panel sits top-right (CSS) and hides
     * when {@code paletteEnabled} is false OR the entries list is empty.
     */
    function renderColorPalette() {
        var panel = $('vgv-color-palette');
        if (!panel) return;
        var body = panel.querySelector('.vgv-palette-body');
        if (!body) return;
        if (!paletteEnabled || paletteEntries.length === 0) {
            panel.style.display = 'none';
            body.innerHTML = '';
            return;
        }
        panel.style.display = 'block';
        body.innerHTML = '';
        paletteEntries.forEach(function (entry) {
            if (!entry || !entry.colorHex) return;
            var row = document.createElement('div');
            row.className = 'vgv-palette-item';
            var sw = document.createElement('span');
            sw.className = 'vgv-palette-swatch';
            sw.style.background = entry.colorHex;
            row.appendChild(sw);
            var lbl = document.createElement('span');
            lbl.className = 'vgv-palette-label';
            lbl.textContent = entry.label != null ? String(entry.label) : '';
            row.appendChild(lbl);
            if (typeof entry.count === 'number') {
                var cnt = document.createElement('span');
                cnt.className = 'vgv-palette-count';
                cnt.textContent = String(entry.count);
                row.appendChild(cnt);
            }
            // Klick auf eine Palette-Zeile setzt den Filter auf "Cluster":
            // nur Nodes mit dieser Farbe + deren Brücken-Nodes bleiben
            // sichtbar; alle übrigen Nodes werden via removeNodesWithIds
            // ausgeblendet (Sigma-konformer Edge-Filter wirkt zusätzlich
            // auf die Kanten). Zweiter Klick auf dieselbe Zeile schaltet
            // den Filter wieder aus (Toggle).
            row.addEventListener('click', function () {
                var hex = String(entry.colorHex || '');
                var currentHex = (edgeFilter && edgeFilter.hex) || '';
                if (edgeFilter && edgeFilter.type === 'cluster'
                        && currentHex.toLowerCase() === hex.toLowerCase()) {
                    edgeFilter = null;
                } else {
                    edgeFilter = { type: 'cluster', hex: hex };
                }
                selectedNodeId = null;
                selectedRelId = null;
                applyEdgeFilter();
                applyNodeHighlight();
                applyRelHighlight();
            });
            body.appendChild(row);
        });
        panel.classList.toggle('vgv-palette-collapsed', paletteCollapsed);
        var toggle = panel.querySelector('.vgv-palette-toggle');
        if (toggle) {
            toggle.innerHTML = paletteCollapsed ? '&#x2B;' : '&#x2212;';
            toggle.title = paletteCollapsed ? 'Show palette' : 'Hide palette';
            toggle.onclick = function (ev) {
                ev.stopPropagation();
                paletteCollapsed = !paletteCollapsed;
                renderColorPalette();
            };
        }
    }

    /**
     * Backwards-compat alias — the NodeConfig is no longer applied via
     * a stylesheet (NVL has none). The Java-side resolver already
     * combines labelColors / tagColors / globalTagColors / Leiden into
     * a single per-node map and ships it via {@code vgv_applyNodeColors}
     * from {@code SwitchingViewer.applyNodeColors}. We still expose a
     * vgv_applyNodeConfig handler so the bridge call doesn't throw;
     * we just route the call into the unified per-node path when a
     * config is passed that already carries the resolved colors as
     * ad-hoc fields (it does not — the bridge ships the resolved map
     * separately). Logged for visibility and kept as a no-op fallback.
     */
    window.vgv_applyNodeConfig = function (config) {
        if (!config) return;
        // NVL ignores labelShapes / showTitle (no equivalent). The
        // per-node effective color is pushed separately via
        // vgv_applyNodeColors from SwitchingViewer.applyNodeColors so
        // this method intentionally does nothing beyond logging so a
        // misconfigured bridge call surfaces during debugging.
        if (typeof console !== 'undefined' && console.debug) {
            console.debug('vgv_applyNodeConfig: no-op for NVL (use vgv_applyNodeColors)');
        }
    };

    /* ----- API: context menu ----- */

    window.vgv_showContextMenu = function (entriesJson, x, y) {
        var menu = $('vgv-context-menu');
        if (!menu) return;
        var entries;
        try {
            entries = JSON.parse(entriesJson);
        } catch (e) {
            console.error('vgv_showContextMenu: invalid entries JSON', e);
            return;
        }
        menu.innerHTML = '';
        contextMenuCurrent = { entries: entries };
        renderMenuEntries(menu, entries, 0);
        menu.style.left = x + 'px';
        menu.style.top = y + 'px';
        menu.style.display = 'block';
    };

    function renderMenuEntries(container, entries, depth) {
        entries.forEach(function (entry) {
            if (entry.separator) {
                var sep = document.createElement('div');
                sep.className = 'vgv-menu-separator';
                container.appendChild(sep);
                return;
            }
            var item = document.createElement('div');
            item.className = 'vgv-menu-entry';
            if (entry.disabled) item.className += ' vgv-menu-disabled';
            if (entry.children && entry.children.length > 0) {
                item.className += ' vgv-menu-submenu-label';
            }
            item.textContent = entry.label || '';
            if (!entry.disabled) {
                item.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    if (entry.children && entry.children.length > 0) return;
                    if (depth === 0) {
                        javaCall('vgv_invokeContextMenuAction', entry.id);
                    } else {
                        javaCall('vgv_invokeContextMenuSubmenuAction',
                            contextMenuCurrent && contextMenuCurrent.entries
                                ? contextMenuCurrent.entries[0].id : '',
                            entry.id);
                    }
                    hideContextMenu();
                });
            }
            container.appendChild(item);
        });
    }

    window.vgv_hideContextMenu = function () { hideContextMenu(); };

    function hideContextMenu() {
        var menu = $('vgv-context-menu');
        if (menu) {
            menu.style.display = 'none';
            menu.innerHTML = '';
        }
        contextMenuCurrent = null;
    }

    /* ----- bootstrap ----- */

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
