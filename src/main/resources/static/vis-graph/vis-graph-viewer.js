/**
 * vis-graph-viewer.js
 *
 * Bridge between vis-network (rendering) and the Java side via BrowserFunctions.
 * The HTML is embedded in the RAP page via Browser.setText, which (in RAP 4.x)
 * renders the content inside a same-origin iframe. The bridge therefore calls
 * BrowserFunctions via the parent frame (`window.parent[name]`).
 *
 * @version 2026-08-09-r3 (fix: type character vertical centering via dy=0.35em
 *          instead of dominant-baseline=central; strips <?xml?> declaration for
 *          embedded webview compatibility).
 *
 * Globals written by Java (read here):
 *   window.__vgv_nodes       -- vis.DataSet payload (array of node objects)
 *   window.__vgv_edges       -- vis.DataSet payload (array of edge objects)
 *   window.__vgv_options     -- vis-network options object (optional)
 *
 * Globals written by JS (Java reads via BrowserFunction):
 *   vgv_viewerReady                          ()  -- after vis-network is sized
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

    var network = null;
    var nodes = null;
    var edges = null;
    var contextMenuCurrent = null;
    var pendingData = null;
    var networkReady = false;


    /**
     * Call a BrowserFunction on the iframe's contentWindow. BrowserFunctions
     * are set up by rap-client.js on the Browser widget's contentWindow —
     * i.e. on the iframe's own window, not on the parent.
     *
     * <p>Earlier versions of this function looked in {@code window.parent},
     * but the rap-client.js wrapper is on the iframe's window itself, not on
     * the parent. Looking in the wrong frame meant the function appeared
     * to be undefined and the Java side was never notified.</p>
     */
    function javaCall(name) {
        var fn = null;
        var target = null;
        try {
            target = window;
            fn = target[name];
        } catch (e) {
            console.error('javaCall(' + name + ') - access error:', e);
        }
        console.log('javaCall(' + name + '): typeof=' + typeof fn + ', isFunction=' + (typeof fn === 'function'));
        if (typeof fn !== 'function') {
            return undefined;
        }
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

    function pointerCoords(event) {
        if (!event) return { x: 0, y: 0 };
        return { x: event.clientX || 0, y: event.clientY || 0 };
    }

    function init() {
        var container = $('network');
        if (!container) {
            setTimeout(init, 50);
            return;
        }
        // Wait until the Browser widget has given the container a real size.
        // Without this, vis-network would render to a 0x0 canvas.
        function attempt() {
            if (container.clientWidth > 0 && container.clientHeight > 0) {
                boot(container);
            } else if (typeof ResizeObserver !== 'undefined') {
                var ro = new ResizeObserver(function () {
                    if (container.clientWidth > 0 && container.clientHeight > 0) {
                        ro.disconnect();
                        boot(container);
                    }
                });
                ro.observe(container);
            } else {
                var interval = setInterval(function () {
                    if (container.clientWidth > 0 && container.clientHeight > 0) {
                        clearInterval(interval);
                        boot(container);
                    }
                }, 50);
            }
        }
        attempt();
    }

    function boot(container) {
        if (typeof vis === 'undefined') {
            showError(container, 'vis-network library not loaded — check /vis-network/vis-network.min.js');
            javaCall('vgv_viewerReady');
            return;
        }
        nodes = new vis.DataSet([]);
        edges = new vis.DataSet([]);
        var data = { nodes: nodes, edges: edges };
        var options = {
            physics: {
                enabled: true,
                solver: 'forceAtlas2Based',
                stabilization: { enabled: true, iterations: 1000, fit: true }
            },
            interaction: {
                hover: true,
                tooltipDelay: 100,
                dragNodes: true,
                hideEdgesOnDrag: false
            }
        };
        try {
            network = new vis.Network(container, data, options);
        } catch (e) {
            showError(container, 'vis.Network init failed: ' + e.message);
            console.error(e);
            javaCall('vgv_viewerReady');
            return;
        }

        // Expose the network for debugging / programmatic selection from outside
        try { window.network = network; } catch (e) {}

        network.on('selectNode', function (params) {
            if (params.nodes && params.nodes.length > 0) {
                javaCall('vgv_notifyNodeSelected', params.nodes[0]);
            }
        });

        network.on('selectEdge', function (params) {
            if (params.edges && params.edges.length > 0) {
                javaCall('vgv_notifyRelationshipSelected', params.edges[0]);
            }
        });

        network.on('deselectNode', function () {
            var sn = network.getSelectedNodes();
            var se = network.getSelectedEdges();
            if (sn.length === 0 && se.length === 0) {
                javaCall('vgv_notifySelectionCleared');
            }
        });

        network.on('deselectEdge', function () {
            var sn = network.getSelectedNodes();
            var se = network.getSelectedEdges();
            if (sn.length === 0 && se.length === 0) {
                javaCall('vgv_notifySelectionCleared');
            }
        });

        network.on('onContext', function (params) {
            if (params.event && params.event.preventDefault) {
                params.event.preventDefault();
            }
            if (params.nodes && params.nodes.length > 0) {
                var n = params.nodes[0];
                var coords = pointerCoords(params.event);
                javaCall('vgv_requestNodeContextMenu', n, coords.x, coords.y);
            } else if (params.edges && params.edges.length > 0) {
                var e = params.edges[0];
                var coords2 = pointerCoords(params.event);
                javaCall('vgv_requestRelationshipContextMenu', e, coords2.x, coords2.y);
            }
        });

        document.addEventListener('click', hideContextMenu);
        window.addEventListener('resize', function () {
            if (network) {
                network.setSize(container.clientWidth, container.clientHeight);
                network.redraw();
            }
        });

        networkReady = true;

        if (pendingData) {
            applyPendingData();
        }

        // The RAP client (rap-client.js) sets up the BrowserFunction wrappers on
        // the iframe's window in its `_onload` handler. This happens AFTER
        // our script runs but BEFORE the iframe's load event fires. Since
        // boot() may run before _onload (e.g. if the ResizeObserver fires
        // synchronously), we poll for the wrapper instead of calling
        // vgv_viewerReady immediately.
        waitForViewerReadyWrapper();
    }

    function waitForViewerReadyWrapper() {
        if (typeof window.vgv_viewerReady === 'function') {
            javaCall('vgv_viewerReady');
        } else {
            setTimeout(waitForViewerReadyWrapper, 50);
        }
    }

    function applyPendingData() {
        if (pendingData && networkReady && nodes && edges) {
            nodes.clear();
            edges.clear();
            if (pendingData.options) {
                try { network.setOptions(pendingData.options); } catch (e) {}
            }
            // vis-network 9.x: setText uses innerText (plain text) on string titles.
            // To get HTML rendering, we must convert title HTML to a DOM element.
            // See: div frame.innerText=t  in the vis-network minified source.
            if (pendingData.nodes) {
                nodes.add(pendingData.nodes.map(n => applySvgImage(wrapTitleAsElement(n))));
            }
            if (pendingData.edges) {
                edges.add(pendingData.edges.map(e => wrapTitleAsElement(e)));
            }
            pendingData = null;
        }
    }

    function wrapTitleAsElement(item) {
        if (item && typeof item.title === 'string' && item.title.length > 0) {
            var div = document.createElement('div');
            div.innerHTML = item.title;
            item = Object.assign({}, item);
            item.title = div;
        }
        return item;
    }

    /* ----- API: node configuration ----- */

    /**
     * Push a batched list of per-node visual updates to vis-network. Each
     * update is one of:
     *
     * <ul>
     *   <li><code>{id, image: "&lt;base64 data URI&gt;"}</code> — replaces
     *       the SVG-badge image with the freshly rendered one. vis-network
     *       will fetch and cache the new data URI on the next draw.</li>
     *   <li><code>{id, color: {background, border}}</code> — applies the
     *       new color to a plain (non-image) node.</li>
     * </ul>
     *
     * <p>vis-network's {@code DataSet.update} handles the rest — it
     * triggers the redraw automatically and re-uses its internal
     * {@code Mb} image cache for SVG badges.</p>
     */
    window.vgv_applyNodeImages = function (updates) {
        if (!networkReady || !updates || updates.length === 0) return;
        // vis-network's DataSet.update emits an 'update' event that vis-network
        // picks up via its internal listener — but the redraw happens on
        // the next animation frame, and only for properties whose values
        // actually changed. To force a synchronous re-render that always
        // takes effect (so the new image / color is visible immediately,
        // especially right after a graph-configuration change), we also
        // call network.redraw() after the update.
        nodes.update(updates);
        try { network.redraw(); } catch (e) { /* ignore */ }
    };

    /* ----- API: SVG node images ----- */

    /**
     * If a node carries an {@code svgImage} attribute, the Java side has
     * already produced a fully-rendered SVG (icon + annotation circle +
     * typeChar) and stored it as {@code image} on the node. We simply
     * drop the {@code svgImage} descriptor — the vis-network viewer
     * must NOT re-render the SVG itself, doing so would discard the
     * icon. Returns the (possibly mutated) node.
     */
    function applySvgImage(n) {
        if (!n) return n;
        if (!n.svgImage) return n;
        delete n.svgImage;
        if (typeof n.image === 'string' && n.image.indexOf('data:image/') === 0) {
            n.shape = 'image';
        }
        return n;
    }

    /* ----- API: data ----- */


    window.vgv_setData = function () {
        if (!networkReady) {
            pendingData = {
                nodes: window.__vgv_nodes,
                edges: window.__vgv_edges,
                options: window.__vgv_options
            };
            return;
        }
        if (window.__vgv_options) {
            try { network.setOptions(window.__vgv_options); } catch (e) {
                console.error('vgv_setData: invalid options', e);
            }
        }
        // vis-network 9.x: setText uses innerText (plain text) on string titles.
        // To get HTML rendering, we must convert title HTML to a DOM element.
        nodes.clear();
        edges.clear();
        if (window.__vgv_nodes) nodes.add(window.__vgv_nodes.map(n => applySvgImage(wrapTitleAsElement(n))));
        if (window.__vgv_edges) edges.add(window.__vgv_edges.map(e => wrapTitleAsElement(e)));
        window.__vgv_nodes = null;
        window.__vgv_edges = null;
        window.__vgv_options = null;
    };

    window.vgv_clear = function () {
        if (networkReady && nodes && edges) {
            nodes.clear();
            edges.clear();
        }
    };

    window.vgv_fitToScreen = function () {
        if (network) {
            network.fit({ animation: { duration: 300, easingFunction: 'easeInOutQuad' } });
        }
    };

    /* ----- API: layout & physics ----- */

    window.vgv_setLayout = function (algorithm) {
        if (!network) return;
        var opts = {
            layout: { hierarchical: { enabled: false } },
            physics: { enabled: true }
        };
        switch (algorithm) {
            case 'FORCE_ATLAS_2D':
                opts.physics = { enabled: true, solver: 'forceAtlas2Based' };
                break;
            case 'BARNES_HUT':
                opts.physics = { enabled: true, solver: 'barnesHut' };
                break;
            case 'REPULSION':
                opts.physics = { enabled: true, solver: 'repulsion' };
                break;
            case 'HIERARCHICAL_REPULSION':
                opts.physics = { enabled: true, solver: 'hierarchicalRepulsion' };
                break;
            case 'HIERARCHICAL':
                opts.layout = {
                    hierarchical: {
                        enabled: true,
                        direction: 'UD',
                        sortMethod: 'directed',
                        levelSeparation: 150,
                        nodeSpacing: 100
                    }
                };
                opts.physics = { enabled: false };
                break;
            case 'NONE':
                opts.physics = { enabled: false };
                break;
            // GRID and CIRCULAR are NVL-only and ignored by vis-network.
        }
        network.setOptions(opts);
    };

    window.vgv_setPhysics = function (enabled) {
        if (network) network.setOptions({ physics: { enabled: !!enabled } });
    };

    window.vgv_setPhysicsSolver = function (solver) {
        if (!network) return;
        var s;
        switch (solver) {
            case 'FORCE_ATLAS_2_BASED': s = 'forceAtlas2Based'; break;
            case 'BARNES_HUT':           s = 'barnesHut'; break;
            case 'REPULSION':            s = 'repulsion'; break;
            case 'HIERARCHICAL_REPULSION': s = 'hierarchicalRepulsion'; break;
            default:                     s = 'forceAtlas2Based';
        }
        network.setOptions({ physics: { enabled: true, solver: s } });
    };

    window.vgv_setHierarchicalDirection = function (dir) {
        if (!network) return;
        var d = 'UD';
        switch (dir) {
            case 'UP_DOWN':     d = 'UD'; break;
            case 'DOWN_UP':     d = 'DU'; break;
            case 'LEFT_RIGHT':  d = 'LR'; break;
            case 'RIGHT_LEFT':  d = 'RL'; break;
        }
        network.setOptions({
            layout: { hierarchical: { enabled: true, direction: d } }
        });
    };

    window.vgv_setHierarchicalSpacing = function (levelSeparation, nodeSpacing) {
        if (!network) return;
        network.setOptions({
            layout: {
                hierarchical: {
                    enabled: true,
                    levelSeparation: levelSeparation,
                    nodeSpacing: nodeSpacing
                }
            }
        });
    };

    window.vgv_setStabilizationIterations = function (iterations) {
        if (network) network.setOptions({
            physics: { stabilization: { enabled: true, iterations: iterations } }
        });
    };

    window.vgv_setAutoFitOnStabilization = function (enabled) {
        if (network) network.setOptions({
            physics: { stabilization: { fit: !!enabled } }
        });
    };

    window.vgv_setOption = function (key, valueJson) {
        if (!network || !key) return;
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
            network.setOptions(opts);
        } catch (e) {
            console.error('vgv_setOption: failed for ' + key, e);
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
        contextMenuCurrent = { entries: entries, targetX: x, targetY: y };
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
            if (entry.disabled) {
                item.className += ' vgv-menu-disabled';
            }
            if (entry.children && entry.children.length > 0) {
                item.className += ' vgv-menu-submenu-label';
            }
            item.textContent = entry.label || '';
            if (!entry.disabled) {
                item.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    if (entry.children && entry.children.length > 0) {
                        return;
                    }
                    if (depth === 0) {
                        javaCall('vgv_invokeContextMenuAction', entry.id);
                    } else {
                        javaCall('vgv_invokeContextMenuSubmenuAction',
                            contextMenuCurrent && contextMenuCurrent.entries
                                ? contextMenuCurrent.entries[0].id
                                : '',
                            entry.id
                        );
                    }
                    hideContextMenu();
                });
            }
            container.appendChild(item);
        });
    }

    window.vgv_hideContextMenu = function () {
        hideContextMenu();
    };

    function hideContextMenu() {
        var menu = $('vgv-context-menu');
        if (menu) {
            menu.style.display = 'none';
            menu.innerHTML = '';
        }
        contextMenuCurrent = null;
    }

    function showError(container, message) {
        if (!container) return;
        container.innerHTML = '';
        var box = document.createElement('div');
        box.style.cssText = 'position:absolute;top:8px;left:8px;right:8px;padding:12px;'
            + 'background:#fee;border:1px solid #c00;color:#600;'
            + 'font-family:monospace;font-size:12px;z-index:10000;';
        box.textContent = 'vis-graph-viewer: ' + message;
        container.appendChild(box);
    }

    /* ----- bootstrap ----- */

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
