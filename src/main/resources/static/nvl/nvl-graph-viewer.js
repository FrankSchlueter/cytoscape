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

    // Tooltip state
    var tooltipEl = null;

    // Interaction handler instances (set up after NVL construction)
    var dragHandler = null;
    var clickHandler = null;
    var hoverHandler = null;
    var panHandler = null;
    var zoomHandler = null;

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
                javaCall('vgv_notifyNodeSelected', node.id);
            });
            clickHandler.updateCallback('onRelationshipClick', function (rel, hits, evt) {
                javaCall('vgv_notifyRelationshipSelected', rel.id);
            });
            clickHandler.updateCallback('onCanvasClick', function (evt) {
                javaCall('vgv_notifySelectionCleared');
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

            // Pan/Zoom are deliberately NOT enabled by default. The
            // embedded RAP iframe has its own scroll container and any
            // wheel/pan gestures tend to either zoom into a useless
            // spot or move the initial viewport away from the graph.
            // Users can still navigate via the Fit button in the
            // control bar; if a Java caller wants pan/zoom they can
            // construct handlers themselves.
            panHandler = null;
            zoomHandler = null;
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
        if (newNodes.length > 0 || newRels.length > 0) {
            nvl.addAndUpdateElementsInGraph(newNodes, newRels);
        }
        currentNodeIds = newNodes.map(function (nd) { return nd.id; });
        currentRelIds = newRels.map(function (rd) { return rd.id; });
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
        }
    }

    /**
     * Apply a per-node Leiden cluster color map (id → hex) to the NVL
     * nodes. Pairs with {@code NvlJsBridge.setLeidenColors} which is
     * invoked by {@code GraphConfigurationDialog} when the "Apply
     * Leiden Clustering" button is clicked. NVL has no stylesheet so
     * every node receives a per-node {@code {id, color}} update via
     * {@code nvl.updateElementsInGraph} — symmetric to the
     * vis-network handler.
     */
    window.vgv_applyLeidenColors = function (colors) {
        updateNodeColors(colors);
    };

    /**
     * Apply the engine-agnostic "effective per-node color" map produced
     * by {@code NodeColorResolver} (Java side). Pairs with
     * {@code NvlJsBridge.applyNodeColors} so the dialog's Tag-Colors
     * and Leiden-Colors buttons apply identically to all engines.
     */
    window.vgv_applyNodeColors = function (effective) {
        updateNodeColors(effective);
    };

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
