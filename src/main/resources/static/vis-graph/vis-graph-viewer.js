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
    // Legend state
    var legendEntries = [];
    var legendEnabled = false;
    var activeLegendColor = null;
    var legendCollapsed = false;


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
                if (activeLegendColor) {
                    clearLegendHighlight();
                    renderLegendPanel();
                }
                javaCall('vgv_notifySelectionCleared');
            }
        });

        network.on('deselectEdge', function () {
            var sn = network.getSelectedNodes();
            var se = network.getSelectedEdges();
            if (sn.length === 0 && se.length === 0) {
                if (activeLegendColor) {
                    clearLegendHighlight();
                    renderLegendPanel();
                }
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

    /**
     * Apply a per-node Leiden-cluster color map (id → hex) to the vis-
     * network nodes. Pairs with {@code VisJsBridge.setLeidenClusterColors}
     * which is invoked by {@code GraphConfigurationDialog} when the
     * "Apply Leiden Clustering" button is clicked. Each known node
     * receives a {@code {color, highlight, hover}} update via
     * {@code nodes.update} — vis-network's ColorSpec keys, NOT
     * {@code background}. A trailing {@code network.redraw()} makes the
     * recolor visible even when none of the values actually changed
     * (vis-network otherwise skips re-rendering identical property
     * values).
     */
    window.vgv_applyLeidenColors = function (colors) {
        if (!networkReady || !colors) return;
        var updates = [];
        Object.keys(colors).forEach(function (nodeId) {
            var color = colors[nodeId];
            if (!color) return;
            // vis-network's color spec is shape-dependent. For ellipse /
            // box / circle / database / etc. the primary fill reads
            // `color.background` (NOT `color.color` as the docs suggest).
            // The highlight / hover slots are nested objects with their
            // own `background` and `border`. To make the recolor
            // actually take effect we therefore push the Leiden color
            // into ALL three: the primary background, plus matching
            // highlight / hover overrides so the node stays the same
            // color in selected / hover state.
            updates.push({
                id: nodeId,
                color: {
                    background: color,
                    border: color,
                    highlight: { background: color, border: color },
                    hover: { background: color, border: color }
                }
            });
        });
        if (updates.length === 0) return;
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
        // The graph is being replaced — release any in-flight Cluster-Layout
        // state so the new dataset starts clean (no pinned anchors, no
        // deferred edges held back from the old run).
        releasePendingLayoutState();
        // Drop any active legend highlight because the cached match-set
        // would point at stale node ids.
        if (activeLegendColor) {
            activeLegendColor = null;
            renderLegendPanel();
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
        releasePendingLayoutState();
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

    window.vgv_applyLegend = function (entries, enabled) {
        applyLegend(entries, enabled);
    };

    /* ----- API: layout & physics ----- */

    /**
     * vis-network Cluster-Layout-Strategie payload. Holds the option map
     * pushed via {@code vgv_setLayoutOptions} so that subsequent
     * {@code vgv_setLayout('FORCE_ATLAS_2D')} calls re-apply the same
     * FA2 tuning. Cleared by {@code vgv_dispose}.
     */
    var currentLayoutOptions = null;

    /**
     * Module-level state for the in-flight Cluster-Layout run. Held
     * outside any function-local closure so that a second
     * {@code vgv_setLayoutOptions} call (or {@code vgv_setLayout},
     * {@code vgv_setData}, {@code vgv_dispose}) can deterministically
     * release everything the previous run had deferred or pinned.
     *
     * <p>The bug this guards against: if {@code vgv_setLayoutOptions} is
     * called twice in quick succession (e.g. Java's
     * {@link GraphConfigurationDialog#applyLeidenClustering} invokes
     * {@code setLayoutOptions(opts)} followed by
     * {@code setLayout(FORCE_ATLAS_2D)} and {@code GraphViewer.setLayout}
     * also re-pushes the options), the first run's deferred edges can
     * stay removed indefinitely if the {@code stabilized} event of the
     * second run supersedes the first listener and the fallback timer
     * races with the network re-init. Keeping the state here lets every
     * entry point enforce idempotent cleanup before doing anything else.</p>
     */
    var pendingDeferredEdges = null;   // Array<{id, ...originalFields}>
    var pendingPinnedNodes = null;     // Array<nodeId>
    var pendingFallbackTimer = null;   // setTimeout handle for cleanup fallback
    var pendingStabilizedOff = null;   // function that deregisters the 'stabilized' listener
    var pendingPollTimer = null;       // setTimeout handle for position-polling fallback
    var pendingMassUpdates = null;     // Array<{id, mass}> for per-node mass-reset on cleanup

    /**
     * Release everything the previous Cluster-Layout run still holds:
     * re-add the weak edges that were removed by the pre-layout filter,
     * unpin the cluster anchors, cancel the fallback timer, and
     * detach the {@code stabilized} listener. Idempotent — safe to call
     * from {@code vgv_setLayoutOptions}, {@code vgv_setLayout},
     * {@code vgv_setData} and {@code vgv_dispose}.
     *
     * <p>The {@code skipDetach} flag is set when we're already inside the
     * {@code stabilized} handler — calling {@code network.off(...)} from
     * within its own listener is unsafe on some vis-network builds.</p>
     */
    function releasePendingLayoutState(skipDetach) {
        var cleanupStart = Date.now();
        if (pendingFallbackTimer != null) {
            try { clearTimeout(pendingFallbackTimer); } catch (e) { /* ignore */ }
            pendingFallbackTimer = null;
        }
        if (pendingPollTimer != null) {
            try { clearTimeout(pendingPollTimer); } catch (e) { /* ignore */ }
            pendingPollTimer = null;
        }
        if (!skipDetach && typeof pendingStabilizedOff === 'function') {
            try { pendingStabilizedOff(); } catch (e) { /* ignore */ }
            pendingStabilizedOff = null;
        }
        // Re-add deferred edges. Filter out any whose IDs are
        // already in the DataSet (race-condition guard) to avoid
        // vis-network warnings when a previous cleanup already
        // restored them.
        if (pendingDeferredEdges && pendingDeferredEdges.length && edges) {
            var toReAdd = [];
            pendingDeferredEdges.forEach(function (d) {
                if (d && d.id && !edges.get(d.id)) toReAdd.push(d);
            });
            if (toReAdd.length) {
                try {
                    edges.add(toReAdd);
                    console.log('[VGV] cleanup: re-added '
                            + toReAdd.length + '/' + pendingDeferredEdges.length
                            + ' deferred edges');
                } catch (err) {
                    console.error('[VGV] cleanup: edges.add FAILED for '
                            + toReAdd.length + ' edges', err);
                }
            }
        }
        pendingDeferredEdges = null;
        // Reset orphan mass BEFORE the anchor unpinning so the
        // anchor nodes don't get yanked around while their masses
        // are still reduced.
        if (pendingMassUpdates && pendingMassUpdates.length && nodes) {
            var massResets = pendingMassUpdates.map(function (u) {
                return { id: u.id, mass: 1.0 };
            });
            try { nodes.update(massResets); } catch (err) {
                console.error('[VGV] cleanup: mass reset FAILED', err);
            }
        }
        pendingMassUpdates = null;
        if (pendingPinnedNodes && pendingPinnedNodes.length && nodes) {
            var unpinned = pendingPinnedNodes.map(function (id) {
                return { id: id, fixed: { x: false, y: false } };
            });
            try { nodes.update(unpinned); } catch (err) {
                console.error('[VGV] cleanup: unpin FAILED', err);
            }
        }
        pendingPinnedNodes = null;
        console.log('[VGV] cleanup done in ' + (Date.now() - cleanupStart) + 'ms, '
                + 'edgesNow=' + (edges ? edges.length : 'n/a'));
    }

    /**
     * Position-polling fallback: when neither the 'stabilized'
     * event nor the 1.5 s fallback timer fires (e.g. physics was
     * just toggled and FA2 never settles into a true stable state),
     * this loop samples the first 10 node positions every 200 ms
     * and triggers {@link releasePendingLayoutState} once the
     * movement drops below {@code MOVEMENT_TOLERANCE_PX} for
     * {@code STABLE_THRESHOLD_FRAMES} consecutive frames.
     *
     * <p>Hard cap: 30 polls (~6 s) so a never-stabilising graph
     * doesn't pin anchors / defer edges forever.</p>
     */
    function startPositionPollingForCleanup() {
        if (pendingPollTimer != null) return;
        var lastPositions = {};
        var stableFrames = 0;
        var STABLE_THRESHOLD_FRAMES = 3;
        var MOVEMENT_TOLERANCE_PX = 1.0;
        var POLL_INTERVAL_MS = 200;
        var MAX_POLLS = 30;
        var pollCount = 0;

        function pollOnce() {
            pollCount++;
            if (!network || !nodes || pollCount > MAX_POLLS) {
                pendingPollTimer = null;
                return;
            }
            var sample = nodes.get().slice(0, 10);
            var movement = 0;
            sample.forEach(function (n) {
                var prev = lastPositions[n.id];
                var x = n.x || 0, y = n.y || 0;
                if (prev) movement += Math.abs(x - prev.x) + Math.abs(y - prev.y);
                lastPositions[n.id] = { x: x, y: y };
            });
            if (movement < MOVEMENT_TOLERANCE_PX) {
                stableFrames++;
                if (stableFrames >= STABLE_THRESHOLD_FRAMES) {
                    console.log('[VGV] poll: positions stable for '
                            + STABLE_THRESHOLD_FRAMES + ' frames — early cleanup');
                    releasePendingLayoutState();
                    return;
                }
            } else {
                stableFrames = 0;
            }
            pendingPollTimer = setTimeout(pollOnce, POLL_INTERVAL_MS);
        }
        pendingPollTimer = setTimeout(pollOnce, POLL_INTERVAL_MS);
    }

    window.vgv_setLayout = function (algorithm) {
        if (!network) return;
        // Switching away from FORCE_ATLAS_2D (or into any other solver)
        // must release any deferred edges / pinned anchors that the
        // previous Cluster-Layout run still holds — otherwise the
        // anchors stay pinned forever and the deferred edges never
        // come back.
        releasePendingLayoutState();
        var opts = {
            layout: { hierarchical: { enabled: false } },
            physics: { enabled: true }
        };
        switch (algorithm) {
            case 'FORCE_ATLAS_2D':
                if (currentLayoutOptions && currentLayoutOptions.physics) {
                    // Re-apply the full FA2 tuning the Java side pushed
                    // (gravitationalConstant, centralGravity, ...). When
                    // the user hasn't pushed any options yet, fall back
                    // to a bare solver switch.
                    opts.physics = JSON.parse(JSON.stringify(currentLayoutOptions.physics));
                } else {
                    opts.physics = { enabled: true, solver: 'forceAtlas2Based' };
                }
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

    /**
     * Apply a JSON layout-option map produced by
     * {@code ForceAtlasOptions.buildOptions} on the Java side. Implements
     * the vis-network half of the Cluster-Layout-Strategie
     * (Cluster-Layout.md):
     * <ol>
     *   <li><b>Pre-Layout Edge-Filter</b> — edges whose {@code logWeight}
     *       is below {@code prefilterMinLogWeight} are removed from the
     *       DataSet and re-added after the layout has stabilized. Without
     *       this the strong edges would have to share spring time with a
     *       long tail of low-weight background noise.</li>
     *   <li><b>Per-Edge Lengths</b> — each entry in {@code edgeLengths}
     *       overrides vis-network's global {@code springLength} for that
     *       single edge, so heavy-weight edges are pulled tight while
     *       weak ones stay loose.</li>
     *   <li><b>Cluster Anchors</b> — for every Leiden community the
     *       highest-weighted-degree node is pinned to the supplied grid
     *       coordinate via {@code fixed: {x: true, y: true}}. The pin is
     *       released on the {@code stabilized} event so the node can
     *       respond to subsequent physics events.</li>
     *   <li><b>FA2 Physics</b> — the full {@code physics} block is pushed
     *       via {@code network.setOptions}, then {@code stabilize()} runs
     *       the configured number of iterations.</li>
     * </ol>
     *
     * The option map is cached in {@code currentLayoutOptions} so a
     * later {@code vgv_setLayout('FORCE_ATLAS_2D')} call re-applies the
     * same tuning.
     */
    window.vgv_setLayoutOptions = function (options) {
        currentLayoutOptions = options || null;
        if (!network) return;
        if (!options) return;

        // Diagnostic snapshot for the start of a Cluster-Layout run.
        var thresholdEarly = (typeof options.prefilterMinLogWeight === 'number')
                ? options.prefilterMinLogWeight : 0;
        console.log('[VGV] setLayoutOptions start: threshold=' + thresholdEarly
                + ', totalEdges=' + (edges ? edges.length : 'n/a')
                + ', prefilter=' + (thresholdEarly > 0 ? 'active' : 'off')
                + ', physicsEnabled=' + !!(network.physics && network.physics.physicsEnabled));

        // 0) Defensive cleanup: a previous run may still hold deferred
        //    edges / pinned anchors if Java called us twice in quick
        //    succession. Release them BEFORE we start a new run so the
        //    DataSet is in a known state when we sample edges below.
        releasePendingLayoutState();

        // 1) Pre-Layout Edge-Filter
        var deferredData = [];
        var threshold = (typeof options.prefilterMinLogWeight === 'number')
                ? options.prefilterMinLogWeight : 0;
        if (threshold > 0 && edges) {
            edges.forEach(function (e) {
                if (!e) return;
                var lw = (typeof e.logWeight === 'number') ? e.logWeight : 0;
                if (lw > 0 && lw < threshold) {
                    // Capture the original payload so we can re-add the
                    // edge with its original weight / logWeight / length.
                    var copy = {};
                    for (var k in e) { if (Object.prototype.hasOwnProperty.call(e, k)) copy[k] = e[k]; }
                    deferredData.push(copy);
                }
            });
            if (deferredData.length) {
                var deferredIds = deferredData.map(function (d) { return d.id; });
                try { edges.remove(deferredIds); } catch (err) {
                    console.warn('vgv_setLayoutOptions: edge remove failed', err);
                    // If remove failed, abort the filter step — keeping
                    // the DataSet intact is more important than enforcing
                    // the threshold on this run.
                    deferredData = [];
                }
            }
        }
        pendingDeferredEdges = deferredData;

        // 2) Per-edge length interpolation
        if (options.edgeLengths && edges) {
            var updates = [];
            Object.keys(options.edgeLengths).forEach(function (id) {
                updates.push({ id: id, length: options.edgeLengths[id] });
            });
            if (updates.length) {
                try { edges.update(updates); } catch (err) {
                    console.warn('vgv_setLayoutOptions: edge length update failed', err);
                }
            }
        }

        // 3) Pin cluster anchors
        var pinned = [];
        if (options.clusterCentroids && nodes) {
            Object.keys(options.clusterCentroids).forEach(function (nodeId) {
                var c = options.clusterCentroids[nodeId];
                if (!c || typeof c.x !== 'number' || typeof c.y !== 'number') return;
                try {
                    nodes.update({
                        id: nodeId,
                        x: c.x,
                        y: c.y,
                        fixed: { x: true, y: true }
                    });
                    pinned.push(nodeId);
                } catch (err) {
                    console.warn('vgv_setLayoutOptions: anchor pin failed for ' + nodeId, err);
                }
            });
        }
        pendingPinnedNodes = pinned;

        // 3b) Per-node mass for isolated nodes (orphans). vis-network's
        //     FA2 solver treats lighter nodes as more movable, so
        //     mass=0.3 makes degree-0 nodes drift away from clusters.
        //     Reset to 1.0 happens in releasePendingLayoutState right
        //     before the anchor unpinning.
        if (options.isolatedNodeIds && options.isolatedNodeIds.length && nodes) {
            var mass = (typeof options.isolatedNodeMass === 'number')
                    ? options.isolatedNodeMass : 0.3;
            var massUpdates = options.isolatedNodeIds.map(function (id) {
                return { id: id, mass: mass };
            });
            try {
                nodes.update(massUpdates);
                pendingMassUpdates = massUpdates;
                console.log('[VGV] setLayoutOptions: mass=' + mass
                        + ' assigned to ' + massUpdates.length + ' orphans');
            } catch (err) {
                console.warn('[VGV] setLayoutOptions: mass update failed', err);
            }
        }

        // 4) Push physics + stabilise
        if (options.physics) {
            try { network.setOptions(options.physics); } catch (err) {
                console.warn('vgv_setLayoutOptions: physics setOptions failed', err);
            }
        }
        var iters = (options.physics && options.physics.stabilization
                     && options.physics.stabilization.iterations) || 1000;

        // 5) Schedule the post-stabilization cleanup. Two paths release
        //    pendingDeferredEdges / pendingPinnedNodes:
        //      a) network.once('stabilized', ...) — fires once the
        //         physics solver converges. The handler is captured so
        //         a new run (or dispose) can deregister it.
        //      b) a 3 s setTimeout fallback — covers the cases where
        //         'stabilized' never fires (physics disabled, empty
        //         graph, FA2 oscillating, RAF stopped). 3 s is
        //         empirically enough for a 1000-iter FA2 run on a
        //         ~1000-edge graph; longer than this and the user has
        //         likely switched context anyway.
        //
        //    Both paths store a handle so releasePendingLayoutState()
        //    can cancel them when a new run starts.
        var onStabilized = function () {
            // We're inside the 'stabilized' listener — calling
            // network.off() from here would be a no-op anyway, and on
            // some vis-network builds it throws. Skip the detach.
            pendingStabilizedOff = null;
            releasePendingLayoutState(true);
        };
        pendingStabilizedOff = function () {
            if (typeof network.off === 'function') {
                try { network.off('stabilized', onStabilized); } catch (e) { /* ignore */ }
            }
        };
        try {
            network.once('stabilized', onStabilized);
        } catch (err) {
            // network.once unavailable → rely on the fallback only.
            pendingStabilizedOff = null;
        }
        // stabilize()-guard: ForceAtlas2 silently refuses to stabilise
        // when physics was just toggled off and back on. Detect and
        // re-enable explicitly so the user actually sees the cluster
        // layout converge (without this, the graph freezes on its
        // pre-apply positions because the 'stabilized' event never fires).
        try {
            var physEnabled = !!(network.physics && network.physics.physicsEnabled);
            if (!physEnabled) {
                console.warn('[VGV] stabilize: physics was disabled — re-enabling');
                network.setOptions({ physics: { enabled: true } });
            }
        } catch (e) {
            console.warn('[VGV] stabilize: introspection failed', e);
        }
        try {
            network.stabilize(iters);
        } catch (err) {
            console.warn('vgv_setLayoutOptions: stabilize failed', err);
        }
        // Shorter fallback timer (1.5 s instead of 3 s) plus the
        // position-polling fallback so cleanup still fires when
        // 'stabilized' is never delivered.
        pendingFallbackTimer = setTimeout(releasePendingLayoutState, 1500);
        startPositionPollingForCleanup();
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

    /* ----- Legend panel ----- */

    /**
     * Public entry point called by the Java bridge (window.vgv_applyLegend).
     * The payload is an array of {@code {colorHex, label, count}} records;
     * {@code enabled} controls the panel's visibility.
     */
    function applyLegend(entriesJson, enabled) {
        var list = [];
        if (typeof entriesJson === 'string') {
            try { list = JSON.parse(entriesJson) || []; }
            catch (e) { console.warn('vgv_applyLegend: bad JSON', e); list = []; }
        } else if (Array.isArray(entriesJson)) {
            list = entriesJson;
        }
        legendEntries = list;
        legendEnabled = !!enabled;
        if (!legendEnabled) {
            clearLegendHighlight();
        }
        renderLegendPanel();
    }

    /**
     * Rebuild the legend DOM. The panel is positioned top-right (CSS) and
     * hidden when {@code legendEnabled} is false OR the list is empty.
     */
    function renderLegendPanel() {
        var panel = document.getElementById('vgv-legend');
        if (!panel) return;
        var body = panel.querySelector('.vgv-legend-body');
        if (!body) return;
        if (!legendEnabled || legendEntries.length === 0) {
            panel.style.display = 'none';
            body.innerHTML = '';
            return;
        }
        var activeNorm = activeLegendColor ? normalizeColor(activeLegendColor) : null;
        body.innerHTML = '';
        legendEntries.forEach(function (e) {
            if (!e || !e.colorHex) return;
            var row = document.createElement('div');
            row.className = 'vgv-legend-item' +
                (activeNorm && activeNorm === normalizeColor(String(e.colorHex))
                    ? ' vgv-legend-active' : '');
            var swatch = document.createElement('span');
            swatch.className = 'vgv-legend-swatch';
            swatch.style.background = String(e.colorHex);
            row.appendChild(swatch);
            var labelEl = document.createElement('span');
            labelEl.className = 'vgv-legend-label';
            labelEl.textContent = e.label != null ? String(e.label) : '';
            row.appendChild(labelEl);
            if (typeof e.count === 'number') {
                var cnt = document.createElement('span');
                cnt.className = 'vgv-legend-count';
                cnt.textContent = String(e.count);
                row.appendChild(cnt);
            }
            row.addEventListener('click', function (ev) {
                ev.stopPropagation();
                toggleLegendHighlight(String(e.colorHex));
            });
            body.appendChild(row);
        });
        panel.classList.toggle('vgv-legend-collapsed', legendCollapsed);
        var toggle = panel.querySelector('.vgv-legend-toggle');
        if (toggle) {
            toggle.innerHTML = legendCollapsed ? '&#x2B;' : '&#x2212;';
            toggle.title = legendCollapsed ? 'Show legend' : 'Hide legend';
            toggle.onclick = function (ev) {
                ev.stopPropagation();
                legendCollapsed = !legendCollapsed;
                renderLegendPanel();
            };
        }
        panel.style.display = 'block';
    }

    /**
     * Toggle the highlight for the given hex color. First click activates
     * the highlight; a second click on the same color clears it. Clicks
     * on a different color swap the highlight.
     */
    function toggleLegendHighlight(hex) {
        var norm = String(hex || '').toLowerCase();
        if (activeLegendColor && activeLegendColor.toLowerCase() === norm) {
            clearLegendHighlight();
        } else {
            applyLegendHighlight(norm);
        }
        renderLegendPanel();
    }

    /**
     * Dim every node whose background color does NOT match {@code hex}, and
     * every edge that does not connect two matching nodes. Matched nodes
     * get a colored border. Edges between matched nodes get the same color.
     *
     * <p>vis-network has no stylesheet engine: each node carries its own
     * {@code color.background} value (set by the Java bridge from the
     * Leiden map or the tag-config map). We read it back out of the
     * DataSet and build an {@code update} batch with the new opacity /
     * borderWidth / color values, then call {@code nodes.update(...)} +
     * {@code network.redraw()}.</p>
     *
     * <p><b>Color matching</b>: vis-network stores {@code color.background}
     * in the same string the Java bridge wrote (typically {@code #rrggbb}
     * or {@code rgb(r, g, b)}). We normalize both sides via
     * {@link normalizeColor} so a click on a legend swatch always finds
     * its target nodes regardless of the encoding.</p>
     */
    function applyLegendHighlight(hex) {
        if (!network || !nodes) return;
        clearLegendHighlight();
        activeLegendColor = hex;
        var target = normalizeColor(hex);
        var all = nodes.get();
        var matched = {};
        all.forEach(function (n) {
            var bg = (n.color && n.color.background) || '';
            if (bg && normalizeColor(bg) === target) matched[n.id] = true;
        });
        var nodeUpdates = all.map(function (n) {
            if (matched[n.id]) {
                return {
                    id: n.id,
                    opacity: 1.0,
                    borderWidth: 4,
                    color: { border: hex }
                };
            }
            return { id: n.id, opacity: 0.18 };
        });
        nodes.update(nodeUpdates);
        // Edges: between two matched nodes -> full opacity + colored, else dim.
        var edgeUpdates = [];
        edges.forEach(function (e) {
            var both = matched[e.from] && matched[e.to];
            var upd = { id: e.id, opacity: both ? 1.0 : 0.18 };
            if (both && e.color !== undefined) {
                upd.color = { color: hex };
            }
            edgeUpdates.push(upd);
        });
        if (edgeUpdates.length > 0) edges.update(edgeUpdates);
        try { network.redraw(); } catch (err) { /* ignore */ }
    }

    /**
     * Convert any CSS color string into a canonical
     * {@code rgb(r, g, b)} form (lowercase, no alpha). Handles
     * {@code #rgb}, {@code #rrggbb}, {@code rgb(...)} and {@code rgba(...)}.
     */
    function normalizeColor(input) {
        if (input == null) return '';
        var s = String(input).trim().toLowerCase();
        if (s.length === 0) return '';
        if (s.charAt(0) === '#' && s.length === 4) {
            var r = parseInt(s.charAt(1) + s.charAt(1), 16);
            var g = parseInt(s.charAt(2) + s.charAt(2), 16);
            var b = parseInt(s.charAt(3) + s.charAt(3), 16);
            return 'rgb(' + r + ', ' + g + ', ' + b + ')';
        }
        if (s.charAt(0) === '#' && s.length === 7) {
            var r1 = parseInt(s.substring(1, 3), 16);
            var g1 = parseInt(s.substring(3, 5), 16);
            var b1 = parseInt(s.substring(5, 7), 16);
            return 'rgb(' + r1 + ', ' + g1 + ', ' + b1 + ')';
        }
        var m = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(s);
        if (m) {
            return 'rgb(' + m[1] + ', ' + m[2] + ', ' + m[3] + ')';
        }
        return s;
    }

    /**
     * Remove every opacity / borderWidth override we added during
     * {@link applyLegendHighlight}. The original {@code color.background}
     * is left intact on each node because vis-network keys redraws off
     * of property changes; setting {@code opacity} back to {@code 1.0}
     * is enough to make the dim disappear.
     */
    function clearLegendHighlight() {
        activeLegendColor = null;
        if (!nodes) return;
        var all = nodes.get();
        var resets = all.map(function (n) {
            return { id: n.id, opacity: 1.0, borderWidth: undefined };
        });
        // Drop entries with no id to avoid vis-network warnings.
        resets = resets.filter(function (u) { return !!u.id; });
        nodes.update(resets);
        if (edges) {
            var edgeResets = [];
            edges.forEach(function (e) {
                edgeResets.push({ id: e.id, opacity: 1.0 });
            });
            if (edgeResets.length > 0) edges.update(edgeResets);
        }
        if (network) {
            try { network.redraw(); } catch (err) { /* ignore */ }
        }
    }

    /* ----- bootstrap ----- */

    window.vgv_dispose = function () {
        // Drop the cached Cluster-Layout options so the next
        // GraphViewer instance starts with a clean slate.
        currentLayoutOptions = null;
        try { clearLegendHighlight(); } catch (e) { /* ignore */ }
        var legend = document.getElementById('vgv-legend');
        if (legend && legend.parentNode) legend.parentNode.removeChild(legend);
        var ctx = document.getElementById('vgv-context-menu');
        if (ctx && ctx.parentNode) ctx.parentNode.removeChild(ctx);
    };

    // Diagnostic hook, only active with ?vgvDebug=1 in the URL.
    // Exposes pending state + manual cleanup for live debugging.
    if (typeof location !== 'undefined'
            && location.search && location.search.indexOf('vgvDebug=1') >= 0) {
        window.__vgvTest = {
            forceCleanup: function () {
                console.log('[VGV] manual cleanup triggered');
                releasePendingLayoutState();
            },
            getPendingState: function () {
                return {
                    deferred: pendingDeferredEdges
                            ? pendingDeferredEdges.length : 0,
                    pinned: pendingPinnedNodes
                            ? pendingPinnedNodes.length : 0,
                    isolatedMassUpdates: pendingMassUpdates
                            ? pendingMassUpdates.length : 0,
                    timerActive: pendingFallbackTimer != null,
                    pollActive: pendingPollTimer != null,
                    physicsEnabled: !!(network && network.physics
                            && network.physics.physicsEnabled),
                    edgesTotal: edges ? edges.length : 0,
                    nodesTotal: nodes ? nodes.length : 0
                };
            },
            getCurrentLayoutOptions: function () { return currentLayoutOptions; }
        };
        console.log('[VGV] debug hook enabled — try window.__vgvTest');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
