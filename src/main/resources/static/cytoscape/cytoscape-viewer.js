/**
 * cytoscape-viewer.js
 *
 * Bridge between Cytoscape.js + cytoscape-fcose (rendering) and the Java
 * side via BrowserFunctions. The HTML is embedded in the RAP page via
 * Browser.setText, which (in RAP 4.x) renders the content inside a
 * same-origin iframe. The bridge therefore calls BrowserFunctions via the
 * iframe's contentWindow (window.parent[name] is intentionally NOT used —
 * rap-client.js wraps BrowserFunctions on the iframe's own window).
 *
 * Globals written by Java (read here):
 *   window.__cgv_elements        -- Cytoscape elements array (nodes + edges)
 *
 * Globals written by JS (Java reads via BrowserFunction):
 *   cgv_viewerReady                          ()  -- after Cytoscape is sized
 *   cgv_notifyNodeSelected                   (id)
 *   cgv_notifyRelationshipSelected           (id)
 *   cgv_notifySelectionCleared               ()
 *   cgv_requestNodeContextMenu               (id, x, y)
 *   cgv_requestRelationshipContextMenu       (id, x, y)
 *   cgv_invokeContextMenuAction              (entryId)
 */

(function () {
    'use strict';

    var cy = null;
    var currentLayout = 'preset';  // default — works with the Leiden community preseed
    var pendingLayoutOptions = null;
    var pendingElements = null;
    var cyReady = false;
    // True between attempt() finding a non-zero container and the
    // boot() body having set cyReady = true. Prevents two
    // ResizeObserver / setInterval callbacks from racing each other
    // into double-boot during the FillLayout flush.
    var booting = false;
    var leidenColors = null;
    var resizeObserved = false;
    // Legend state
    var legendEntries = [];
    var legendEnabled = false;
    var activeLegendColor = null;
    var legendCollapsed = false;

    /**
     * Call a BrowserFunction on the iframe's contentWindow. BrowserFunctions
     * are set up by rap-client.js on the Browser widget's contentWindow —
     * i.e. on the iframe's own window, not on the parent.
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
        if (typeof fn !== 'function') {
            console.warn('javaCall(' + name + '): BrowserFunction not registered');
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

    function showError(msg) {
        var el = $('error');
        if (!el) return;
        el.textContent = msg;
        el.style.display = 'block';
        console.error('cgv error:', msg);
    }

    function log(msg) {
        try { console.log('[cgv] ' + msg); } catch (e) {}
    }

    function init() {
        // Idempotency guard: a second init() (e.g. after window resize
        // or an engine-switch that fires setText twice) must not
        // re-init cytoscape on top of an existing instance.
        if (cyReady || booting) return;
        log('init() start');
        var container = $('cy');
        if (!container) {
            log('container #cy not found, retry in 50ms');
            setTimeout(init, 50);
            return;
        }
        function attempt() {
            var w = container.clientWidth, h = container.clientHeight;
            log('attempt: container size=' + w + 'x' + h);
            if (w > 0 && h > 0) {
                boot(container);
            } else if (typeof ResizeObserver !== 'undefined' && !resizeObserved) {
                resizeObserved = true;
                var ro = new ResizeObserver(function (entries) {
                    for (var i = 0; i < entries.length; i++) {
                        var cr = entries[i].contentRect;
                        if (cr.width > 0 && cr.height > 0) {
                            ro.disconnect();
                            boot(container);
                            break;
                        }
                    }
                });
                ro.observe(container);
                // Fallback: poll every 100ms.
                var pollCount = 0;
                var poll = setInterval(function () {
                    pollCount++;
                    if (container.clientWidth > 0 && container.clientHeight > 0) {
                        clearInterval(poll);
                        ro.disconnect();
                        boot(container);
                    } else if (pollCount > 50) {
                        clearInterval(poll);
                        log('resize poll timeout, forcing boot with current size');
                        boot(container);
                    }
                }, 100);
                // Hard timeout: after 5 s the container might still be 0×0
                // because the parent composite's layout never propagated.
                // Surface a clear error in the #cgv-debug banner so the
                // user isn't left staring at an empty canvas wondering
                // why nothing rendered.
                setTimeout(function () {
                    if (cyReady || booting) return;
                    if (container.clientWidth > 0 && container.clientHeight > 0) {
                        boot(container);
                        return;
                    }
                    var dbg = $('cgv-debug');
                    if (dbg) {
                        dbg.style.display = 'block';
                        dbg.textContent = 'Container size 0×0 — cytoscape boot timed out';
                    }
                }, 5000);
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
        // Guard against double-boot: ResizeObserver + setInterval can
        // both fire on the same size transition, and the attempt() /
        // setTimeout paths can race if the polling interval hasn't been
        // cleared yet.
        if (cyReady || booting) return;
        booting = true;
        log('boot() enter');
        try {
            doBoot(container);
        } catch (outerEx) {
            // doBoot() catches its own errors and clears booting, but
            // belt-and-suspenders: if anything escapes (e.g. a thrown
            // exception in a listener registered inside doBoot), clear
            // booting so a subsequent ResizeObserver / setTimeout can
            // retry instead of leaving the viewer permanently dead.
            log('boot() caught outer exception: ' + outerEx.message);
            booting = false;
            throw outerEx;
        }
    }

    /**
     * Actual boot body, wrapped in {@link boot} so the {@code booting}
     * flag is reset on every failure path. Without this reset, a
     * single boot failure (e.g. cytoscape throwing in its constructor,
     * or the cytoscape.use(fcose) call blowing up on an exotic bundle
     * layout) would permanently brick the viewer — every subsequent
     * ResizeObserver / setInterval / setTimeout call would bail at
     * the {@code if (cyReady || booting) return;} guard.
     */
    function doBoot(container) {
        if (typeof cytoscape === 'undefined') {
            showError('Cytoscape library not loaded — check /cytoscape/cytoscape.min.js');
            javaCall('cgv_viewerReady');
            booting = false;
            return;
        }
        // The cytoscape-fcose UMD bundle registers itself automatically on
        // load (it calls `cytoscape('layout', 'fcose', impl)` if window.cytoscape
        // exists). We additionally accept a no-op `cytoscape.use(fcoseExt)` so
        // the bundle is robust to either load order.
        var fcoseExt = (typeof window.cytoscapeFcose !== 'undefined')
                ? window.cytoscapeFcose
                : (window.cytoscapeFcose && window.cytoscapeFcose.default);
        if (typeof fcoseExt !== 'undefined') {
            try { cytoscape.use(fcoseExt); }
            catch (e) { log('cytoscape.use(fcose) warning: ' + e.message); }
        } else {
            log('cytoscapeFcose extension not found on window — fcose may not be registered');
        }

        // Optional: cola (cytoscape.js-cola). The cola UMD registers itself
        // when both window.cytoscape and window.cola exist, but if the user
        // forgot to include cola.js before cytoscape-cola.js the layout
        // silently falls back to 'preset'. Surface that state in the log so
        // it shows up in the dev console.
        if (typeof window.cytoscapeCola !== 'undefined') {
            try { cytoscape.use(window.cytoscapeCola); }
            catch (e) { log('cytoscape.use(cola) warning: ' + e.message); }
        } else if (typeof window.cola === 'undefined') {
            log('cola/WebCola extension not loaded — cola layout will fall back to preset');
        }

        try {
            cy = cytoscape({
                container: container,
                elements: [],
                style: defaultStyle().concat([imageNodeStyle()]),
                layout: { name: 'preset' },
                // wheelSensitivity intentionally NOT set — Cytoscape's
                // default (= 1) lets the browser's native wheel-zoom
                // semantics drive the pan/zoom, which feels natural on
                // every mouse + OS combo. Setting it to a small value
                // (e.g. 0.2) makes the canvas zoom 5× more aggressively
                // than the user expects, and Cytoscape logs a console
                // warning about exactly that ("You have set a custom
                // wheel sensitivity. This will make your app zoom
                // unnaturally when using mainstream mice."). Our earlier
                // 0.2 setting also caused the "zoom away and it
                // disappears" symptom — at 0.2 sensitivity one scroll
                // tick is enough to push the zoom past minZoom.
                // minZoom: 0.25 keeps the graph visible even at the
                // extreme-out end (smaller values lose the silhouette),
                // maxZoom: 5 leaves room for inspecting individual
                // node badges without breaking the layout.
                minZoom: 0.25,
                maxZoom: 5
            });
            // Expose for debugging / test harnesses. Production users
            // should not depend on this — it's a debugging hook.
            window.__cgv_cy = cy;
            log('cytoscape instance created');
        } catch (e) {
            showError('Cytoscape init failed: ' + e.message);
            console.error(e);
            javaCall('cgv_viewerReady');
            booting = false;
            return;
        }

        // Force a resize so cytoscape knows the real container dimensions
        // before the first layout. Without this, fcose computes coordinates
        // against a 0x0 viewport and the layout collapses to a point.
        try { cy.resize(); } catch (e) { /* ignore */ }

        // Register the runtime classes that replace inline-style highlights
        // throughout the viewer. Class-based styles are stored once on
        // the stylesheet and applied via addClass/removeClass — Cytoscape
        // then re-renders the affected elements in a single layer-cache
        // pass, eliminating the flicker we saw when highlightNeighborhood
        // toggled `opacity` per element via n.style({...}). The selectors
        // themselves live on the cy.style() chain so the rule is honoured
        // even when our caller overrides the default node rule.
        try {
            cy.style()
                .selector('.cgv-faded')
                .style({ 'opacity': 0.18 })
                .selector('.cgv-highlighted')
                .style({
                    'border-width': 4,
                    'border-style': 'solid',
                    'border-color': '#E74C3C',
                    'opacity': 1
                })
                .selector('node.cgv-node-hover')
                .style({
                    'border-color': '#4A90E2',
                    'border-width': 3
                })
                .update();
        } catch (e) { /* ignore — selector rule registration is best-effort */ }

        wireCytoscapeEvents(cy);
        attachTooltips(cy);
        cyReady = true;
        // Boot succeeded — clear the booting flag so a subsequent engine
        // switch can re-enter boot(). (cyReady alone is enough to block
        // re-boot, but we leave booting consistent with cyReady for any
        // future code path that checks it independently.)
        booting = false;

        // If elements arrived before the boot, apply them now.
        if (pendingElements && pendingElements.length > 0) {
            log('applying ' + pendingElements.length + ' pending elements');
            applyElements(pendingElements);
            pendingElements = null;
        } else {
            // Still tell Java we're ready so setLayout / setData can flow.
            javaCall('cgv_viewerReady');
        }

        // Auto-load fallback: if Java doesn't push data within ~800ms,
        // fetch the sample graph ourselves. This guarantees the demo
        // renders quickly — the Java→JS RAP bridge typically completes
        // within 200-400ms in practice, so this fallback rarely fires.
        setTimeout(autoLoadFallback, 800);
    }

    function defaultStyle() {
        return [
            { selector: 'node',
              style: {
                  'background-color': '#4A90E2',
                  'label': 'data(label)',
                  'color': '#222222',
                  'font-size': 11,
                  'text-valign': 'bottom',
                  'text-halign': 'center',
                  'text-margin-y': 4,
                  'text-wrap': 'wrap',
                  // Native browser tooltip on hover — visible when the user
                  // hovers over a node. The text comes from data.tooltip (set
                  // by the Java serializer).
                  'text-events': 'yes',
                  'border-width': 1,
                  'border-color': '#222222',
                  'width': 40,
                  'height': 40
              }},
            { selector: 'edge',
              style: {
                  'width': 2,
                  'line-color': '#888888',
                  'target-arrow-color': '#888888',
                  'target-arrow-shape': 'triangle',
                  'curve-style': 'bezier',
                  // Only render the label when the edge actually has one
                  // (avoids "no mapping for property label" warnings on every
                  // unlabelled relationship).
                  'label': function(ele){ return ele.data('label') || ''; },
                  'font-size': 9,
                  'color': '#444444',
                  'text-rotation': 'autorotate',
                  'text-events': 'yes'
              }},
            { selector: 'node:selected',
              style: {
                  // Only change the border on selection — the node's
                  // Leiden color (background) is preserved so the user
                  // can still see which community it belongs to.
                  'border-color': '#E74C3C',
                  'border-width': 4,
                  'border-style': 'solid',
              }},
            { selector: 'edge:selected',
              style: {
                  'line-color': '#E74C3C',
                  'target-arrow-color': '#E74C3C',
                  'width': 3
              }}
        ];
    }

    /**
     * Style rule for nodes whose {@code data.image} is set (SVG badge nodes
     * produced by {@code GraphNode.setSvgShape} / {@code setSvgIcon} /
     * {@code setIcon}).
     *
     * <p>Cytoscape renders the SVG data URI as a {@code background-image}
     * clipped to the node's shape, so the underlying rounded-rect background
     * (provided by the SVG itself — the Leiden community color or whatever
     * the Java caller baked into {@code renderSvgIcon3}) is what the user
     * actually sees. We pick a neutral Cytoscape fill so the canvas does not
     * double-paint behind the SVG, and suppress Cytoscape's native label
     * because the label is already drawn inside the SVG.</p>
     *
     * <p>Must be appended AFTER {@link #defaultStyle()} so it overrides the
     * generic {@code node} rule; the {@code node:selected} rule from
     * {@code defaultStyle()} continues to win for selection because it is
     * appended last in {@code defaultStyle()} (Cytoscape applies the LAST
     * matching rule).</p>
     */
    function imageNodeStyle() {
        return { selector: 'node[?image]',
            style: {
                'shape': 'round-rectangle',
                'background-image': 'data(image)',
                'background-clip': 'node',
                'background-fit': 'contain',
                'background-width': '100%',
                'background-height': '100%',
                'background-image-opacity': 1,
                'background-color': '#ffffff',
                'corner-radius': '6px',
                'width': 40,
                'height': 31,
                // Min size floor: keeps the embedded icon + type-label
                // legible even when the underlying SVG body is narrower
                // than the canvas expects. Cytoscape's renderer uses the
                // max(width, min-width) for layout collision detection,
                // so this also prevents adjacent badges from crowding
                // each other in dense regions of the graph.
                'min-width': 50,
                'min-height': 31,
                'border-width': 1,
                'border-color': '#222222',
                'label': '',
                'text-events': 'no'
            }};
    }

    /**
     * Pre-position nodes based on their Leiden community color. Each
     * community gets a 2-D grid slot of the canvas; nodes inside the
     * same community are placed in a small ring within that slot. fcose
     * is then run with randomize=false so it preserves the rough community
     * separation and only fine-tunes intra-community positions.
     */
    function preseedCommunityPositions(colors) {
        if (!cy || !colors) return false;
        var colorToNodes = {};
        cy.nodes().forEach(function (n) {
            var c = colors[n.id()];
            if (!c) return;
            if (!colorToNodes[c]) colorToNodes[c] = [];
            colorToNodes[c].push(n);
        });
        var colorList = Object.keys(colorToNodes);
        if (colorList.length === 0) return false;
        // Sort communities by size descending so large ones get the
        // "important" cells (top-left, top-middle) — easier to spot.
        colorList.sort(function (a, b) {
            return colorToNodes[b].length - colorToNodes[a].length;
        });
        // Layout the community grid in roughly a square: ceil(sqrt(k)) cols.
        var k = colorList.length;
        var cols = Math.ceil(Math.sqrt(k));
        var rows = Math.ceil(k / cols);
        // Reference frame sized to match a typical viewport (1600x900).
        // After `cy.fit()`, this gets scaled to whatever the actual
        // container size is. 1500×900 gives a comfortable aspect ratio.
        var FRAME_W = 1500;
        var FRAME_H = 900;
        var cellW = FRAME_W / cols;
        var cellH = FRAME_H / rows;
        colorList.forEach(function (color, idx) {
            var col = idx % cols;
            var row = Math.floor(idx / cols);
            var cx = (col + 0.5) * cellW - FRAME_W / 2;
            var cy2 = (row + 0.5) * cellH - FRAME_H / 2;
            var nodes = colorToNodes[color];
            // Place the nodes in a small circle inside this cell.
            var radius = Math.min(cellW, cellH) * 0.30;
            nodes.forEach(function (n, i) {
                var angle = (i / nodes.length) * Math.PI * 2;
                var r = nodes.length === 1 ? 0 : radius;
                n.position({
                    x: cx + Math.cos(angle) * r,
                    y: cy2 + Math.sin(angle) * r,
                });
            });
        });
        log('preseedCommunityPositions: ' + colorList.length + ' communities in ' + cols + 'x' + rows + ' grid (frame ' + FRAME_W + 'x' + FRAME_H + ')');
        return true;
    }

    /**
     * Inject one Cytoscape compound-parent node per Leiden community into the
     * flat {@code elements} array and set {@code data.parent} on every member
     * node. Realises "Compound Nodes (Physische Barrieren)" from
     * Cluster-Layout.md §1, step 2.
     *
     * <p>Cluster parents are emitted as plain Cytoscape nodes with a sentinel
     * {@code isCluster: true} flag (consumed by {@link clusterCompoundStyle})
     * and the community colour stored as {@code _color} so the dashed-border
     * style can pick it up via {@code data(_color)}. Member nodes get
     * {@code data.parent = 'cluster_<idx>'} which makes fcose treat them as
     * children of the compound — the layout then actively pushes the parent
     * boxes apart via {@code gravityCompound} / {@code gravityRangeCompound}.</p>
     *
     * <p>No-op when {@code colors} is empty / null. The returned array is a
     * NEW array — the input is left untouched so callers can cache the raw
     * payload between runs.</p>
     */
    function injectClusterParents(elements, colors) {
        if (!Array.isArray(elements) || elements.length === 0) return elements;
        if (!colors || typeof colors !== 'object') return elements;
        var colorList = Object.keys(colors);
        if (colorList.length === 0) return elements;
        // Stable, deterministic colour→cluster-id mapping. Index is the
        // position in Object.keys() iteration order; matches the Java
        // helper ClusterLayoutOptions.clusterParentId(idx).
        var clusterIds = {};
        colorList.forEach(function (color, idx) {
            clusterIds[color] = 'cluster_' + idx;
        });
        var injected = [];
        var seenClusters = {};
        for (var i = 0; i < elements.length; i++) {
            var e = elements[i];
            var data = (e && e.data) || {};
            var id = data.id;
            var color = id ? colors[id] : null;
            if (color && clusterIds[color]) {
                var cid = clusterIds[color];
                if (!seenClusters[cid]) {
                    seenClusters[cid] = true;
                    injected.push({
                        data: {
                            id: cid,
                            isCluster: true,
                            _color: color,
                            label: '',
                            // Cytoscape compound parents are children of the
                            // root, never nested under another parent.
                            parent: undefined
                        }
                    });
                }
                // Clone the element so we don't mutate the input list.
                var cloned = { data: Object.assign({}, data, { parent: cid }) };
                if (e.position) cloned.position = e.position;
                injected.push(cloned);
            } else {
                injected.push(e);
            }
        }
        log('injectClusterParents: ' + Object.keys(seenClusters).length
                + ' compound parents injected for ' + colorList.length + ' colors');
        return injected;
    }

    /**
     * Cytoscape style rule(s) for the compound-cluster parent nodes
     * produced by {@link injectClusterParents}. Implements
     * Cluster-Layout.md §4 — the cluster container gets a very faint
     * background fill, a dashed border in the community colour, generous
     * padding so the spring forces leave room between member nodes and
     * the box edge, and a suppressed label.
     *
     * <p>This entry must be appended AFTER the defaults in
     * {@code defaultStyle()} so the {@code node[?isCluster]} selector wins
     * over the generic {@code node} rule.</p>
     */
    function clusterCompoundStyle() {
        return { selector: 'node[?isCluster]',
            style: {
                'shape': 'round-rectangle',
                'background-color': 'data(_color)',
                'background-opacity': 0.06,
                'border-width': 2,
                'border-color': 'data(_color)',
                'border-style': 'dashed',
                'padding': '30px',
                'label': '',
                'text-events': 'no',
                // Compound parents are layout-only; disable picking.
                'events': 'no',
                'min-width': 80,
                'min-height': 60
            }};
    }

    /**
     * Cytoscape style overrides for edges when the Cluster-Layout-Strategie
     * is active. Bezier curves + control-point-step-size spread parallel /
     * bidirectional edges so they don't overlap; width scales sub-linearly
     * with the pre-computed {@code logWeight} Cytoscape attribute so the
     * difference between weight 1 (lw ≈ 0.69) and weight 10000 (lw ≈ 9.21)
     * is visible without making the heavy edges overwhelming.
     *
     * <p>The formula is {@code 0.6 + 0.9 * sqrt(min(max(lw, 0), 4))} →
     * range 0.6 px (lw=0) to 2.4 px (lw=4). Higher weights are clamped to
     * lw=4 so the bridge edges (the heaviest) don't dominate the canvas.</p>
     *
     * <p>Cytoscape's LAST-matching-rule semantics guarantee this wins over
     * the {@code edge} rule from {@code defaultStyle()} as long as the
     * caller appends it AFTER the defaults.</p>
     */
    function clusterEdgeStyle() {
        return { selector: 'edge',
            style: {
                'curve-style': 'bezier',
                'control-point-step-size': 45,
                'target-arrow-shape': 'triangle',
                'line-color': '#A2B1C6',
                'target-arrow-color': '#A2B1C6',
                'opacity': 0.8,
                'width': 'function(edge){var lw=edge.data("logWeight");lw=typeof lw==="number"&&lw>0?lw:0;return 0.6+0.9*Math.sqrt(Math.min(Math.max(lw,0),4));}'
            }};
    }

    /**
     * Returns {@code true} when the currently pending layout options carry
     * the Cluster-Layout-Strategie signature (the compound-cluster forces
     * from {@code ClusterLayoutOptions.buildFcoseOptions}). The cytoscape
     * bridge uses this to decide whether to preseed community centers and
     * merge {@link clusterCompoundStyle} into the stylesheet.
     */
    function isClusterLayoutActive() {
        var opts = pendingLayoutOptions || {};
        return typeof opts.gravityCompound === 'number'
                && typeof opts.gravityRangeCompound === 'number'
                && typeof opts.idealInterClusterEdgeLength === 'number';
    }

    /**
     * Returns the array of style rules that should be merged into the
     * stylesheet when the Cluster-Layout-Strategie is active. Returns an
     * empty array when no cluster-layout options are pending so callers
     * can append unconditionally.
     *
     * <p>Order matters: cluster edge rules go BEFORE
     * {@link clusterCompoundStyle} so the dashed-border / padding / fill
     * of the compound parents win (Cytoscape applies the LAST matching
     * rule). {@link imageNodeStyle} still comes last from the caller's
     * side so SVG badges always render.</p>
     */
    function clusterStyleRules() {
        if (!isClusterLayoutActive()) return [];
        return [clusterEdgeStyle(), clusterCompoundStyle()];
    }

    /**
     * Module-level queue of edges that were held back from the initial
     * {@code cy.add()} call because their {@code logWeight} fell below the
     * user-selected threshold. Restored to the canvas once the layout has
     * stopped (see {@link restoreHeldBackEdges}).
     *
     * <p>Cluster-Layout.md §5 ("Profi-Tricks"): "Berechnen Sie das
     * fCoSE-Layout ausschließlich mit Kanten, die ein logarithmisches
     * Gewicht von über z.B. 4.0 haben. Fügen Sie die sehr schwachen
     * Kanten (1 bis 50 im Echtwert) erst visuell hinzu, nachdem das
     * Layout fertig berechnet ist (layout.run())."</p>
     */
    var pendingHeldBackEdges = [];

    /**
     * Read the user-selected pre-layout threshold from the pending layout
     * options. Returns 0 (filter disabled) when the value is missing,
     * non-positive, or not a number. The default comes from the Java
     * helper {@code ClusterLayoutOptions.DEFAULT_MIN_LOG_WEIGHT = 2.0}.
     */
    function prefilterMinLogWeight() {
        var opts = pendingLayoutOptions || {};
        var v = opts.prefilterMinLogWeight;
        if (typeof v !== 'number' || v <= 0) return 0;
        return v;
    }

    /**
     * Partition an elements array into edges that should participate in
     * the layout (above the threshold) and edges that should be held back
     * until after the layout has settled (below the threshold).
     *
     * <p>Edges without a numeric {@code data.logWeight} (i.e. no weight
     * property at all) are routed into {@code layoutEdges} so unweighted
     * relationships don't disappear — the Java fcose function string falls
     * back to {@code 1} for those, matching the {@code (typeof lw === 'number' && lw > 0)}
     * guard in {@code ClusterLayoutOptions.edgeElasticity}.</p>
     *
     * <p>Edges below the threshold are NOT removed — they're stashed in
     * {@link pendingHeldBackEdges} so {@link restoreHeldBackEdges} can
     * add them back to the canvas once the layout has placed the nodes.</p>
     */
    function partitionEdgesForLayout(elements, minLogWeight) {
        pendingHeldBackEdges = [];
        if (!Array.isArray(elements)) return elements || [];
        if (typeof minLogWeight !== 'number' || minLogWeight <= 0) return elements;
        var layoutEdges = [];
        for (var i = 0; i < elements.length; i++) {
            var e = elements[i];
            var data = (e && e.data) || {};
            // Skip compound-parent nodes and member nodes — the filter
            // applies to edges (source/target pairs) only.
            if (!data.source || !data.target) {
                layoutEdges.push(e);
                continue;
            }
            var lw = data.logWeight;
            if (typeof lw === 'number' && lw > 0 && lw < minLogWeight) {
                pendingHeldBackEdges.push(e);
            } else {
                layoutEdges.push(e);
            }
        }
        log('partitionEdgesForLayout: kept ' + layoutEdges.length
                + ' edges for layout, held back ' + pendingHeldBackEdges.length
                + ' (threshold=' + minLogWeight + ')');
        return layoutEdges;
    }

    /**
     * Add the held-back edges back to the canvas. Safe to call multiple
     * times — the internal queue is cleared after every successful pass.
     * Called from the {@code layout.on('layoutstop', ...)} handler in
     * {@link runPostLoadLayout} and from {@link runLayout}'s fcose branch.
     */
    function restoreHeldBackEdges() {
        if (!cy || !pendingHeldBackEdges || pendingHeldBackEdges.length === 0) return;
        var held = pendingHeldBackEdges;
        pendingHeldBackEdges = [];
        try {
            cy.batch(function () { cy.add(held); });
            log('restoreHeldBackEdges: re-added ' + held.length + ' weak edges after layout');
        } catch (e) {
            log('restoreHeldBackEdges: failed to add ' + held.length + ' edges: ' + e.message);
        }
    }

    /**
     * Preload every node's {@code data.image} (a data:image/svg+xml URI) into
     * a browser Image object, then force Cytoscape to redraw once the load
     * events fire.
     *
     * <p>Background: Cytoscape's renderer only paints
     * {@code background-image} when the underlying Image object is
     * {@code complete}. If the first draw happens before the browser has
     * finished parsing the SVG data URI, the layer cache is populated
     * without the image and the badge stays invisible. The auto-refinement
     * path Cytoscape usually relies on (the {@code backgroundTimestamp}
     * listener registered inside {@code getCachedImage}) is brittle when
     * the layer cache has already committed a frame, so we take the direct
     * route: preload every URI, wait for all of them, then redraw.</p>
     *
     * <p>This is a no-op when no nodes carry {@code data.image}.</p>
     */
    function preloadSvgImagesAndRedraw() {
        if (!cy) return;
        var uris = [];
        cy.nodes().forEach(function (n) {
            var img = n.data('image');
            if (typeof img === 'string' && img.length > 0 &&
                img.toLowerCase().indexOf('data:image/') === 0) {
                uris.push(img);
            }
        });
        // Always fire cy.resize() so the canvas matches the container and
        // the layout engine computes its final positions against the real
        // viewport. Without this, plain-node graphs (no data URIs to
        // preload) skip the resize entirely and Edges never get a final
        // paint pass.
        function fireResize() {
            try { cy.resize(); } catch (e) { /* ignore */ }
        }
        // Run the layout + fit ONCE all images have loaded (or
        // immediately if there are no images). This is the single source
        // of truth for the post-load paint pass — applyElements no longer
        // runs the layout synchronously to avoid the race where Edges are
        // computed against the default 40×40 bounding boxes BEFORE the
        // SVG data URIs have finished parsing.
        function runPostLoadLayout() {
            try {
                // Cytoscape draws nothing into a 0×0 canvas. Even with the
                // log-coordinate clamp the user sees a blank graph because
                // the render-area itself is empty. We MUST wait for a
                // positive container size before placing nodes and
                // running the layout. ResizeObserver fires the moment
                // the iframe container's FillLayout settles; setInterval
                // is the fallback for environments without ResizeObserver
                // (very old webviews). Hard timeout 5 s is the last
                // resort — surface a clear error in #cgv-debug.
                var cyContainer = document.getElementById('cy');
                var ready = function () {
                    return cyContainer &&
                        cyContainer.clientWidth > 0 &&
                        cyContainer.clientHeight > 0;
                };
                var doLayout = function () {
                    runPostLoadLayoutBody();
                };
                if (ready()) {
                    doLayout();
                    return;
                }
                log('postLoad: container still 0×0, waiting for ResizeObserver');
                if (typeof ResizeObserver !== 'undefined') {
                    var ro = new ResizeObserver(function () {
                        if (ready()) {
                            ro.disconnect();
                            doLayout();
                        }
                    });
                    ro.observe(cyContainer);
                    setTimeout(function () {
                        ro.disconnect();
                        if (ready()) { doLayout(); return; }
                        var dbg = document.getElementById('cgv-debug');
                        if (dbg) {
                            dbg.style.display = 'block';
                            dbg.textContent = 'Cytoscape: container size 0×0 after 5 s — aborting layout';
                        }
                        log('postLoad: container 0×0 after 5 s, aborting layout');
                    }, 5000);
                } else {
                    var pollCount = 0;
                    var poll = setInterval(function () {
                        pollCount++;
                        if (ready()) {
                            clearInterval(poll);
                            doLayout();
                        } else if (pollCount > 50) {
                            clearInterval(poll);
                            log('postLoad: container 0×0 after 5 s, forcing layout');
                            doLayout();
                        }
                    }, 100);
                }
            } catch (e) {
                log('postLoad: setup failed: ' + e.message);
                try { runPostLoadLayoutBody(); } catch (e2) { /* ignore */ }
            }
        }

        /**
         * The actual layout body, extracted from {@link runPostLoadLayout}
         * so the latter can wait for a positive container size before
         * placing nodes and running fcose. See the doc-comment on
         * {@code runPostLoadLayout} for why this matters.
         */
        function runPostLoadLayoutBody() {
            try {
                // Force a fresh resize right before we touch positions —
                // the very first runPostLoadLayout() often fires while
                // the iframe has just been (re)attached to the composite
                // and the container has not yet propagated its size
                // through Cytoscape's internal viewport tracking.
                // Without this, cy.width()/cy.height() return 0 and the
                // circle-preset branch lands every node on (0,0) — the
                // graph renders blank because all elements occupy the
                // same pixel.
                try { cy.resize(); } catch (e) { /* ignore */ }
                // Pin the layout viewport to a sane minimum even if the
                // iframe is still mid-resize: 600×400 is large enough
                // for a 1010-edge graph to spread out, and small enough
                // that Math.cos(angle) * Math.min(w, h) * 0.35 stays in
                // the safe integer range. Without the clamp, a 0×0
                // container returns NaN coordinates that Cytoscape then
                // refuses to draw.
                var vw = Math.max(cy.width() || 0, 600);
                var vh = Math.max(cy.height() || 0, 400);
                var preseed = leidenColors && preseedCommunityPositions(leidenColors);
                if (preseed) {
                    log('postLoad: community preset applied, kicking off off fcose async');
                } else {
                    log('postLoad: no community map, falling back to circle preset');
                    var radius = Math.min(vw, vh) * 0.35;
                    var cx = vw / 2;
                    var cyc = vh / 2;
                    var total = cy.nodes().length;
                    cy.nodes().forEach(function (n, i) {
                        var angle = total > 0 ? (i / total) * Math.PI * 2 : 0;
                        n.position({
                            x: cx + Math.cos(angle) * radius,
                            y: cyc + Math.sin(angle) * radius
                        });
                    });
                }
                cy.layout({ name: 'preset', animate: false, fit: false }).run();
                var mappedLayout = mapLayoutName(currentLayout);
                if (mappedLayout !== 'preset') {
                    setTimeout(function () {
                        // For the Cluster-Layout-Strategie, fcose must run
                        // with randomize=false so the preseeded cluster
                        // centres AND the compound-parent barriers stick.
                        // The Java helper sets randomize=true by default
                        // (so fcose genuinely uses the compound parents as
                        // physical barriers), but we force it back to
                        // false here so the preseeded positions are not
                        // discarded.
                        var opts = pendingLayoutOptions || {};
                        if (isClusterLayoutActive()) {
                            opts = Object.assign({}, opts, { randomize: false });
                        }
                        runLayout(currentLayout, opts);
                    }, 50);
                    // The held-back edges will be restored by runLayout's
                    // own 'layoutstop' handler once fcose settles.
                } else {
                    // 'preset' was the final layout — restore the held-back
                    // edges NOW because there is no fcose layoutstop to
                    // trigger restoreHeldBackEdges() automatically.
                    restoreHeldBackEdges();
                    try {
                        var nodes = cy.nodes();
                        if (nodes.length > 0) {
                            var bb = { x1: Infinity, y1: Infinity, x2: -Infinity, y2: -Infinity };
                            nodes.forEach(function (n) {
                                var p = n.position();
                                if (p.x - 30 < bb.x1) bb.x1 = p.x - 30;
                                if (p.y - 30 < bb.y1) bb.y1 = p.y - 30;
                                if (p.x + 30 > bb.x2) bb.x2 = p.x + 30;
                                if (p.y + 30 > bb.y2) bb.y2 = p.y + 30;
                            });
                            cy.fit(undefined, 30);
                            log('postLoad: nodes-only fit complete; spread=' +
                                Math.round(bb.x2 - bb.x1) + 'x' + Math.round(bb.y2 - bb.y1));
                        } else {
                            cy.fit(undefined, 30);
                        }
                    } catch (e) { /* ignore */ }
                }
            } catch (e) {
                log('postLoad: preset failed, running fcose directly: ' + e.message);
                runLayout(currentLayout, pendingLayoutOptions);
            }
        }
        if (uris.length === 0) {
            fireResize();
            runPostLoadLayout();
            return;
        }
        var pending = uris.length;
        var fired = false;
        uris.forEach(function (uri) {
            var im = new Image();
            im.onload = im.onerror = function () {
                pending--;
                if (pending <= 0 && !fired) {
                    fired = true;
                    // Force every image-badge node to re-render now that
                    // the SVG has finished parsing. emit('background') is
                    // the same signal Cytoscape's own image-loader uses.
                    cy.nodes().forEach(function (n) {
                        if (n.data('image')) n.emit('background');
                    });
                    fireResize();
                    runPostLoadLayout();
                }
            };
            im.src = uri;
        });
    }

    function applyElements(elements) {
        if (!cyReady || !cy) {
            log('applyElements: not ready, queueing ' + (elements ? elements.length : 0) + ' elements');
            pendingElements = elements;
            return;
        }
        // Drop any active legend highlight — the underlying nodes and
        // their styles are about to be replaced by cy.add().
        if (activeLegendColor) {
            activeLegendColor = null;
            renderLegendPanel();
        }
        // Cluster-Layout-Strategie: when a Leiden colour map is present
        // AND the cluster-layout options are pending, splice one
        // compound-parent node per community into the elements array and
        // set data.parent on every member node. fcose then treats the
        // parents as physical barriers (Cluster-Layout.md §1, step 2).
        if (leidenColors && isClusterLayoutActive()) {
            elements = injectClusterParents(elements || [], leidenColors);
        }
        // Pre-Layout Edge-Filter (Cluster-Layout.md §5): hold weak
        // edges back from cy.add() so fcose sees a leaner graph. The
        // held-back edges are re-added by restoreHeldBackEdges() once
        // the layout has stopped. Skipped when the threshold is missing
        // or non-positive — that matches the Java helper's OFF sentinel.
        var prefilter = prefilterMinLogWeight();
        if (prefilter > 0) {
            elements = partitionEdgesForLayout(elements, prefilter);
        }
        try {
            cy.batch(function () {
                cy.elements().remove();
                cy.add(elements);
            });
            log('applyElements: added ' + elements.length + ' elements (' +
                cy.nodes().length + ' nodes, ' + cy.edges().length + ' edges)');
            debugStatus('applyElements: ' + cy.nodes().length + ' nodes, ' + cy.edges().length + ' edges');
        } catch (e) {
            showError('applyElements failed: ' + e.message);
            console.error(e);
            javaCall('cgv_viewerReady');
            return;
        }
        javaCall('cgv_viewerReady');
        // Cytoscape's drawNode only paints background-image when the
        // underlying Image object reports complete=true (cytoscape.min.js,
        // Uu.drawNode → J()). If we draw before the SVG data URI finishes
        // parsing in the browser, the texture-cache layer is populated
        // WITHOUT the image and Cytoscape's auto-refinement path does NOT
        // reliably re-run — the badge then stays invisible. Worse: edges
        // are positioned using node-bounding-boxes, which are still the
        // pre-image defaults (40×40) until the image has loaded. Running
        // the layout NOW would paint edges against wrong anchor points
        // and the user would see "edges disappear" after the first resize
        // reflow. We therefore preload every node's image, then run the
        // layout AND fit ONCE so that everything is computed against the
        // final image dimensions.
        preloadSvgImagesAndRedraw();
        // pre-position + layout run is initiated from
        // preloadSvgImagesAndRedraw's resize callback (where the images
        // are guaranteed loaded) so we do not double-render here.
    }

    function runLayout(name, options) {
        if (!cyReady || !cy) {
            debugStatus('runLayout: cy not ready');
            return;
        }
        var layoutName = mapLayoutName(name);
        var layoutOpts = decodeOptions(options || {});
        layoutOpts.name = layoutName;
        // fcose needs a fit so the graph appears in the viewport on first
        // run, even when no idealEdgeLength is provided yet.
        if (layoutName === 'fcose' || layoutName === 'cose' || layoutName === 'cose-bilkent') {
            if (typeof layoutOpts.fit !== false) layoutOpts.fit = true;
            if (typeof layoutOpts.padding !== 'number') layoutOpts.padding = 30;
            if (typeof layoutOpts.animate !== true) layoutOpts.animate = false;
            // randomize=false is REQUIRED for the Leiden-community
            // pre-seeding to work. Without it, fcose throws away the
            // community grid and collapses everything to a blob.
            // Force randomize=false; the server always supplies the
            // correct value, but if a caller forgets we still produce
            // a usable layout.
            if (typeof layoutOpts.randomize === 'undefined') layoutOpts.randomize = false;
            // Higher nodeRepulsion + lower gravity + larger idealEdgeLength
            // so fcose actually spreads the nodes out. The previous
            // nodeRepulsion=50 collapsed the graph into a dense blob
            // because the spring forces overwhelmed the repulsion.
            // nodeRepulsion=18000 keeps adjacent nodes safely separated
            // even when their bounding box grows via min-width on SVG
            // badges (the layout engine uses max(width, min-width) for
            // collision detection).
            if (typeof layoutOpts.nodeRepulsion !== 'number') layoutOpts.nodeRepulsion = 18000;
            if (typeof layoutOpts.gravity !== 'number') layoutOpts.gravity = 0.05;
            if (typeof layoutOpts.edgeElasticity !== 'number') layoutOpts.edgeElasticity = 0.45;
        } else if (layoutName === 'cola') {
            // cola (cytoscape.js-cola 1.6.0 + bundled WebCola from 2016).
            //
            // The bundled WebCola is a 2016-era build that has known
            // stack-overflow paths on large / densely-connected graphs in
            // avoidOverlap + handleDisconnected mode (the solver recurses
            // through separateOverlappingComponents / packing). To stay
            // within the JS stack budget we keep both flags OFF and cap
            // the simulation time. The result is still a constraint-based
            // Cola layout; it just doesn't try to push overlapping
            // components apart at the cost of a deeper recursion.
            //
            // Override order: only fill in values that the server did NOT
            // already specify, so callers can dial the simulation up via
            // setLayoutOptions() when they know the graph fits.
            if (typeof layoutOpts.fit !== false) layoutOpts.fit = true;
            if (typeof layoutOpts.padding !== 'number') layoutOpts.padding = 30;
            if (typeof layoutOpts.animate === 'undefined') layoutOpts.animate = false;
            if (typeof layoutOpts.randomize === 'undefined') layoutOpts.randomize = false;
            // Defaults that the 2016 WebCola build does NOT handle well
            // on large graphs:
            if (typeof layoutOpts.avoidOverlap === 'undefined') layoutOpts.avoidOverlap = false;
            if (typeof layoutOpts.handleDisconnected === 'undefined') layoutOpts.handleDisconnected = false;
            if (typeof layoutOpts.ungrabifyWhileSimulating === 'undefined') layoutOpts.ungrabifyWhileSimulating = false;
            if (typeof layoutOpts.nodeSpacing !== 'number') layoutOpts.nodeSpacing = 10;
            if (typeof layoutOpts.edgeLength !== 'number') layoutOpts.edgeLength = 80;
            if (typeof layoutOpts.maxSimulationTime !== 'number') layoutOpts.maxSimulationTime = 2000;
            if (typeof layoutOpts.refresh !== 'number') layoutOpts.refresh = 1;
        }
        log('runLayout: ' + layoutName + ' with ' + Object.keys(layoutOpts).length + ' options, randomize=' + layoutOpts.randomize);
        debugStatus('runLayout: ' + layoutName);
        try {
            var layout = cy.layout(layoutOpts);
            layout.on('layoutstop', function () {
                log('layoutstop: ' + layoutName);
                debugStatus('layoutstop: ' + layoutName + ', nodes=' + cy.nodes().length);
                // Cluster-Layout.md §5: re-add weak edges that were held
                // back from cy.add() so fcose could compute the cluster
                // skeleton without noise. The nodes already have their
                // final positions at this point, so the restored edges
                // render between the right endpoints without distorting
                // the layout.
                restoreHeldBackEdges();
                // Force-fit to viewport after layout completes.
                try { cy.fit(undefined, 30); } catch (e) { /* ignore */ }
            });
            // Safety net for the 2016-era bundled WebCola (cytoscape.js-cola
            // 1.6.0). Cola can throw RangeError ("Maximum call stack size
            // exceeded") when its avoidOverlap / handleDisconnected paths
            // recurse on large or densely-connected graphs. We catch that
            // and fall back to the 'preset' layout (which leaves the
            // current node positions in place — usually the Leiden-community
            // grid that applyElements() pre-seeded).
            try {
                layout.run();
            } catch (err) {
                console.warn(layoutName + ' layout threw: ' + err.message + ' — falling back to preset');
                debugStatus(layoutName + ' threw: ' + err.message + ' — falling back to preset', true);
                try {
                    var fb = cy.layout({ name: 'preset', fit: true, padding: 30, animate: false });
                    fb.on('layoutstop', function () {
                        // Fallback layout may have lost the original
                        // held-back edges; restore them before fit.
                        restoreHeldBackEdges();
                        try { cy.fit(undefined, 30); } catch (e) { /* ignore */ }
                    });
                    fb.run();
                } catch (err2) {
                    console.error('preset fallback also failed', err2);
                    showError('Layout ' + layoutName + ' failed: ' + err.message);
                }
            }
        } catch (e) {
            console.error('layout ' + layoutName + ' failed', e);
            showError('Layout ' + layoutName + ' failed: ' + e.message);
            debugStatus('layout FAILED: ' + e.message, true);
        }
    }

    /**
     * Cytoscape layout options can contain JS functions (e.g. fcose's
     * idealEdgeLength). The Java side sends them as stringified function
     * source. Decode them via {@code new Function(...)} so Cytoscape can
     * invoke them directly.
     */
    function decodeOptions(opts) {
        var out = {};
        Object.keys(opts).forEach(function (k) {
            var v = opts[k];
            if (typeof v === 'string' && /^\s*function\s*\(/.test(v)) {
                try { out[k] = new Function('return ' + v)(); }
                catch (e) { console.warn('failed to compile function for', k, e); }
            } else {
                out[k] = v;
            }
        });
        return out;
    }

    function mapLayoutName(name) {
        switch ((name || '').toUpperCase()) {
            case 'FCOSE':         return 'fcose';
            case 'COSE':          return 'cose';
            case 'COSE_BILKENT':  return 'cose-bilkent';
            case 'DAGRE':         return 'dagre';
            case 'BREADTHFIRST':  return 'breadthfirst';
            case 'CIRCULAR':      return 'circle';
            case 'GRID':          return 'grid';
            case 'CONCENTRIC':    return 'concentric';
            case 'COLA':          return 'cola';
            case 'HIERARCHICAL':  return 'breadthfirst';
            case 'NONE':          return 'preset';
            case 'NULL':          return 'preset';
            case 'LEIDEN_GRID':   return 'preset';  // implemented by preseedCommunityPositions() before preset layout
            // Default: preset preserves the Leiden-community grid
            // pre-seeded by applyElements(). fcose force-directed
            // layout is still selectable via the toolbar but it's no
            // longer the automatic choice because it collapses all
            // communities toward the centre.
            default:              return 'preset';
        }
    }

    function wireCytoscapeEvents(cy) {
        // Hover highlight for SVG image-badge nodes (data.image set). Cytoscape
        // has no :hover pseudo-class in its style selectors, so we apply an
        // inline style override on mouseover and clear it on mouseout. We do
        // NOT touch grabbed/selected nodes — the red selection border wins.
        cy.on('mouseover', 'node[?image]', function (evt) {
            var n = evt.target;
            if (!n || n.grabbed() || n.selected()) return;
            // Class-based hover so the style is computed once on the
            // stylesheet (see cgv-node-hover rule registered in boot()).
            // Removing the inline-border override on mouseout lets the
            // stylesheet rule reassert itself.
            n.addClass('cgv-node-hover');
        });
        cy.on('mouseout', 'node[?image]', function (evt) {
            var n = evt.target;
            if (!n) return;
            n.removeClass('cgv-node-hover');
        });
        // Tap on a node:
        //   - if the node is already selected, deselect it (toggle)
        //   - if a different node is selected, switch the selection
        //     (deselect all others, then select the new one)
        //   - if nothing is selected, select this node
        // We mirror vis-network's `selectNode` / `deselectNode` semantics.
        cy.on('tap', 'node', function (evt) {
            var node = evt.target;
            if (!node) return;
            if (node.selected()) {
                // Toggle: deselect the already-selected node.
                node.unselect();
                javaCall('cgv_notifySelectionCleared');
                clearNeighborhoodHighlight(cy);
            } else {
                // Switch: deselect all other nodes/edges, then select
                // this one. We use `cy.elements().unselect()` rather
                // than the default multi-select behaviour.
                cy.elements().unselect();
                node.select();
                javaCall('cgv_notifyNodeSelected', node.id());
                highlightNeighborhood(cy, node);
            }
        });
        // Tap on an edge: same toggle behaviour.
        cy.on('tap', 'edge', function (evt) {
            var edge = evt.target;
            if (!edge) return;
            if (edge.selected()) {
                edge.unselect();
                javaCall('cgv_notifySelectionCleared');
                clearNeighborhoodHighlight(cy);
            } else {
                cy.elements().unselect();
                edge.select();
                javaCall('cgv_notifyRelationshipSelected', edge.id());
                highlightEdgeNeighborhood(cy, edge);
            }
        });
        // Click on background clears the selection + dimming + legend highlight.
        cy.on('tap', function (evt) {
            if (evt.target === cy) {
                var sel = cy.elements(':selected');
                if (sel.length > 0) sel.unselect();
                javaCall('cgv_notifySelectionCleared');
                clearNeighborhoodHighlight(cy);
                if (activeLegendColor) {
                    clearLegendHighlight();
                    renderLegendPanel();
                }
            }
        });

        // Right-click for context menu.
        cy.on('cxttap', 'node', function (evt) {
            var node = evt.target;
            var pos = evt.originalEvent || evt.position;
            javaCall('cgv_requestNodeContextMenu', node.id(),
                pos && pos.clientX ? pos.clientX : 0,
                pos && pos.clientY ? pos.clientY : 0);
        });
        cy.on('cxttap', 'edge', function (evt) {
            var edge = evt.target;
            var pos = evt.originalEvent || evt.position;
            javaCall('cgv_requestRelationshipContextMenu', edge.id(),
                pos && pos.clientX ? pos.clientX : 0,
                pos && pos.clientY ? pos.clientY : 0);
        });
    }

    /**
     * Highlight the selected node + its 1-hop neighbours and the
     * connecting edges. All other elements are dimmed. The selected
     * node's Leiden color is preserved — only its border is highlighted
     * (the border-width / border-color in node:selected style does
     * that automatically via the `node:selected` selector).
     */
    function highlightNeighborhood(cy, node) {
        if (!cy || !node) return;
        clearNeighborhoodHighlight(cy);
        if (!node.neighborhood) return;
        var hood = node.neighborhood().add(node);
        var others = cy.elements().difference(hood);
        // Class-based dimming — Cytoscape computes the opacity once per
        // frame instead of patching each element's style. Fixes the
        // "blink on mouse-move" symptom that came from per-element
        // inline style() calls racing with cy.style().update().
        if (others.length > 0) others.addClass('cgv-faded');
    }

    function highlightEdgeNeighborhood(cy, edge) {
        if (!cy || !edge) return;
        clearNeighborhoodHighlight(cy);
        var hood = edge.connectedNodes().union(edge);
        var others = cy.elements().difference(hood);
        if (others.length > 0) others.addClass('cgv-faded');
    }

    function clearNeighborhoodHighlight(cy) {
        if (!cy) return;
        // Strip only the runtime class overrides we added during
        // highlighting. We deliberately do NOT call
        // cy.style().resetToDefault() because that would wipe the
        // node:selected / edge:selected selectors from the stylesheet,
        // which are the source of the red selection border highlight.
        cy.batch(function () {
            cy.elements().removeClass('cgv-faded');
        });
    }

    /* ---- Legend panel ---- */

    /**
     * Public entry point called by the Java bridge (window.cgv_applyLegend).
     * The payload is a JSON-encoded array of {@code {colorHex, label, count}}
     * records; {@code enabled} controls the panel's visibility.
     */
    function applyLegend(entriesJson, enabled) {
        var list = [];
        if (typeof entriesJson === 'string') {
            try { list = JSON.parse(entriesJson) || []; }
            catch (e) { console.warn('cgv_applyLegend: bad JSON', e); list = []; }
        } else if (Array.isArray(entriesJson)) {
            list = entriesJson;
        }
        legendEntries = list;
        legendEnabled = !!enabled;
        // If the legend was disabled, drop any active highlight.
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
        var panel = document.getElementById('cgv-legend');
        if (!panel) return;
        var body = panel.querySelector('.cgv-legend-body');
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
            row.className = 'cgv-legend-item' +
                (activeNorm && activeNorm === normalizeColor(String(e.colorHex))
                    ? ' cgv-legend-active' : '');
            var swatch = document.createElement('span');
            swatch.className = 'cgv-legend-swatch';
            swatch.style.background = String(e.colorHex);
            row.appendChild(swatch);
            var labelEl = document.createElement('span');
            labelEl.className = 'cgv-legend-label';
            labelEl.textContent = e.label != null ? String(e.label) : '';
            row.appendChild(labelEl);
            if (typeof e.count === 'number') {
                var cnt = document.createElement('span');
                cnt.className = 'cgv-legend-count';
                cnt.textContent = String(e.count);
                row.appendChild(cnt);
            }
            row.addEventListener('click', function (ev) {
                ev.stopPropagation();
                toggleLegendHighlight(String(e.colorHex));
            });
            body.appendChild(row);
        });
        panel.classList.toggle('cgv-legend-collapsed', legendCollapsed);
        var toggle = panel.querySelector('.cgv-legend-toggle');
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
     * get a colored border so the user can see which cluster they're
     * hovering. Edges between matched nodes get the same color so they
     * stay visible too.
     *
     * <p><b>Color matching</b>: Cytoscape's {@code n.style('background-color')}
     * returns its internal representation — either {@code #rrggbb},
     * {@code rgb(r, g, b)}, or {@code rgba(r, g, b, a)} — depending on how
     * the stylesheet was authored. The legend payload comes from Java in
     * {@code #RRGGBB} form. We normalize both sides to a canonical
     * {@code rgb(r, g, b)} string before comparing so a match always
     * works regardless of the source encoding.</p>
     */
    function applyLegendHighlight(hex) {
        if (!cy) return;
        clearLegendHighlight();
        activeLegendColor = hex;
        var targetRgb = normalizeColor(hex);
        var nodes = cy.nodes();
        var matched = nodes.filter(function (n) {
            var bg = n.style('background-color');
            return bg && normalizeColor(bg) === targetRgb;
        });
        var matchedSet = {};
        matched.forEach(function (n) { matchedSet[n.id()] = true; });
        cy.batch(function () {
            // Inline-style the matched-cluster border colour so the legend
            // hex propagates through, then add the shared 'cgv-faded'
            // class to non-matching elements. Class-based dimming fixes
            // the mouse-move blink: Cytoscape caches class styles once
            // and re-uses them across re-renders, whereas inline
            // n.style({opacity: …}) calls invalidate the layer cache on
            // every set.
            nodes.forEach(function (n) {
                if (matchedSet[n.id()]) {
                    n.removeClass('cgv-faded');
                    n.style({
                        'border-width': 4,
                        'border-color': hex,
                        'border-style': 'solid'
                    });
                } else {
                    n.addClass('cgv-faded');
                }
            });
            cy.edges().forEach(function (e) {
                var sId = e.source().id();
                var tId = e.target().id();
                if (matchedSet[sId] && matchedSet[tId]) {
                    e.removeClass('cgv-faded');
                    e.style({
                        'line-color': hex,
                        'target-arrow-color': hex,
                        'opacity': 1
                    });
                } else {
                    e.addClass('cgv-faded');
                }
            });
        });
        // Cluster-Edges-Tabelle mit den gematchten Edges befüllen —
        // muss NACH dem cy.batch() laufen, damit die gematchten
        // Node-Styles (und damit die normalizeColor-Treffer im Panel)
        // bereits committed sind.
        renderEdgesTable(hex);
    }

    /**
     * Convert any CSS color string Cytoscape hands us into a canonical
     * {@code rgb(r, g, b)} form (lowercase, no alpha). Handles
     * {@code #rgb}, {@code #rrggbb}, {@code rgb(...)} and {@code rgba(...)}.
     * Unknown inputs (named colors, transparent) round-trip to themselves
     * so equality with another unknown input still works.
     */
    function normalizeColor(input) {
        if (input == null) return '';
        var s = String(input).trim().toLowerCase();
        if (s.length === 0) return '';
        // #rgb short form
        if (s.charAt(0) === '#' && s.length === 4) {
            var r = parseInt(s.charAt(1) + s.charAt(1), 16);
            var g = parseInt(s.charAt(2) + s.charAt(2), 16);
            var b = parseInt(s.charAt(3) + s.charAt(3), 16);
            return 'rgb(' + r + ', ' + g + ', ' + b + ')';
        }
        // #rrggbb form
        if (s.charAt(0) === '#' && s.length === 7) {
            var r1 = parseInt(s.substring(1, 3), 16);
            var g1 = parseInt(s.substring(3, 5), 16);
            var b1 = parseInt(s.substring(5, 7), 16);
            return 'rgb(' + r1 + ', ' + g1 + ', ' + b1 + ')';
        }
        // rgb(r, g, b) or rgba(r, g, b, a)
        var m = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(s);
        if (m) {
            return 'rgb(' + m[1] + ', ' + m[2] + ', ' + m[3] + ')';
        }
        // Named / unknown — return as-is so two same-name colors match.
        return s;
    }

    /**
     * Remove every inline override we added during
     * {@link applyLegendHighlight}. Class-based fades go via
     * {@code removeClass} so the stylesheet reasserts itself; per-element
     * inline-styles (the border-colour set by the active legend entry)
     * are cleared with {@code removeStyle}. The :selected selectors in
     * the default stylesheet stay intact because we never call
     * {@code cy.style().resetToDefault()}.
     */
    function clearLegendHighlight() {
        activeLegendColor = null;
        // Cluster-Edges-Tabelle verschwindet, sobald das Highlight gelöscht
        // wird (zweiter Click, Background-Tap, Legend-Disable). Wird vor
        // dem Style-Reset aufgerufen, damit der Tabellen-Render nicht
        // mitten im Style-Refresh passiert.
        hideEdgesTable();
        if (!cy) return;
        cy.batch(function () {
            cy.elements()
                .removeClass('cgv-faded')
                .removeStyle('border-width border-color border-style line-color target-arrow-color');
        });
    }

    /* ---- Cluster-Edges-Tabelle ---- */

    /**
     * Resolve a Cytoscape node id to a human-readable display name for the
     * edges table. Priority: {@code data.label} (set by
     * {@code GraphNode.toCytoscapeNode()} from {@code visualAttrs.label} /
     * {@code getCaption()}) → {@code data.name} → node id.
     */
    function displayNameFor(nodeId) {
        var n = cy.getElementById(nodeId);
        if (n && n.length > 0) {
            var lbl = n.data('label');
            if (lbl != null && String(lbl).length > 0) return String(lbl);
            var name = n.data('name');
            if (name != null && String(name).length > 0) return String(name);
        }
        return nodeId;
    }

    /**
     * Format an edge weight for the {@code Weight} column. Large values
     * are rounded to integers to keep the column compact; small values
     * stay as-is. {@code null}/missing returns an empty string.
     */
    function formatWeight(w) {
        if (w == null) return '';
        var n = typeof w === 'number' ? w : parseFloat(w);
        if (isNaN(n)) return String(w);
        if (Math.abs(n) >= 100) return String(Math.round(n));
        if (Math.abs(n) >= 10) return n.toFixed(1);
        return n.toString();
    }

    /**
     * Build (or rebuild) the cluster-edges table for the currently
     * highlighted hex color. Populates the {@code #cgv-edges} panel with
     * one {@code <tr>} per edge touching at least one matched node:
     * intra-cluster edges first (sorted by weight desc), then bridge
     * edges (sorted by weight desc). Intra vs. bridge is conveyed via
     * the {@code cgv-edge-intra} / {@code cgv-edge-bridge} CSS classes.
     *
     * <p>Safe to call when no nodes are matched — the empty-state hint
     * is shown instead.</p>
     */
    function renderEdgesTable(hex) {
        var panel = document.getElementById('cgv-edges');
        if (!panel) return;
        var body = panel.querySelector('.cgv-edges-body');
        var empty = panel.querySelector('.cgv-edges-empty');
        if (!body || !empty || !cy) {
            panel.style.display = 'none';
            return;
        }
        var target = normalizeColor(hex);
        // 1) Sammle alle Cluster-Members.
        var matched = {};
        cy.nodes().forEach(function (n) {
            var bg = n.style('background-color');
            if (bg && normalizeColor(bg) === target) matched[n.id()] = true;
        });
        // 2) Sammle alle Edges, die mindestens einen Cluster-Member haben.
        var rows = [];
        cy.edges().forEach(function (e) {
            var sId = e.source().id();
            var tId = e.target().id();
            var sMatched = !!matched[sId];
            var tMatched = !!matched[tId];
            if (!sMatched && !tMatched) return;
            rows.push({
                edgeId: e.id(),
                fromId: sId,
                fromLabel: displayNameFor(sId),
                toId: tId,
                toLabel: displayNameFor(tId),
                weight: e.data('weight'),
                intraCluster: sMatched && tMatched
            });
        });
        // 3) Sortierung: Intra-Cluster zuerst, dann Weight desc.
        rows.sort(function (a, b) {
            if (a.intraCluster !== b.intraCluster) return a.intraCluster ? -1 : 1;
            return (b.weight || 0) - (a.weight || 0);
        });
        body.innerHTML = '';
        if (rows.length === 0) {
            empty.textContent = 'Keine Edges im Cluster.';
            empty.style.display = 'block';
            panel.style.display = 'block';
            return;
        }
        empty.style.display = 'none';
        // 4) Tabelle aufbauen.
        var table = document.createElement('table');
        table.className = 'cgv-edges-table';
        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th>From</th><th>Weight</th><th>To</th></tr>';
        table.appendChild(thead);
        var tbody = document.createElement('tbody');
        rows.forEach(function (r) {
            var tr = document.createElement('tr');
            tr.dataset.edgeId = r.edgeId;
            tr.title = r.edgeId;
            tr.className = r.intraCluster ? 'cgv-edge-intra' : 'cgv-edge-bridge';
            var fromTd = document.createElement('td');
            fromTd.className = 'cgv-edge-from';
            fromTd.textContent = r.fromLabel;
            var weightTd = document.createElement('td');
            weightTd.className = 'cgv-edge-weight';
            weightTd.textContent = formatWeight(r.weight);
            var toTd = document.createElement('td');
            toTd.className = 'cgv-edge-to';
            toTd.textContent = r.toLabel;
            tr.appendChild(fromTd);
            tr.appendChild(weightTd);
            tr.appendChild(toTd);
            tr.addEventListener('click', function (evt) {
                onEdgeRowClick(r.edgeId, evt);
            });
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        body.appendChild(table);
        panel.style.display = 'block';
    }

    /**
     * Hide the cluster-edges table and clear its body. Idempotent; safe
     * to call when the panel is already hidden.
     */
    function hideEdgesTable() {
        var panel = document.getElementById('cgv-edges');
        if (!panel) return;
        panel.style.display = 'none';
        var body = panel.querySelector('.cgv-edges-body');
        if (body) body.innerHTML = '';
        var empty = panel.querySelector('.cgv-edges-empty');
        if (empty) empty.style.display = 'none';
    }

    /**
     * Row-Click-Handler. Triggert den Java-`relListeners`-Callback
     * <em>direkt</em> via {@code javaCall('cgv_notifyRelationshipSelected', …)}.
     *
     * <p>Wichtig: Wir verlassen uns NICHT darauf, dass
     * {@code edge.select()} ein 'tap'-Event im Canvas feuert — der
     * existierende {@code cy.on('tap', 'edge', …)}-Listener reagiert nur
     * auf User-Maus-Events innerhalb des Canvas, nicht auf
     * programmatische Selektionen. Da der Tabellen-Row außerhalb des
     * Canvas liegt, würde der Callback sonst nie ausgelöst.</p>
     *
     * <p>Die {@code edge.select()}-Aufrufe bleiben für die visuelle
     * Cytoscape-Hervorhebung (roter Selection-Border) — die sind orthogonal
     * zum Java-Callback.</p>
     */
    function onEdgeRowClick(edgeId, evt) {
        if (evt) evt.stopPropagation();
        if (!cy) return;
        var edge = cy.getElementById(edgeId);
        if (!edge || edge.length === 0) return;
        // 1) Java-Callback direkt feuern — umgeht den tap-only-Listener.
        javaCall('cgv_notifyRelationshipSelected', edge.id());
        // 2) Cytoscape-Selection setzen, damit der rote Border sichtbar
        //    wird und ein anschließendes Background-Tap konsistent
        //    cgv_notifySelectionCleared feuert.
        cy.elements().unselect();
        edge.select();
    }

    /* ---- Java-callable API (window.cgv_*) ---- */

    // Re-entrancy guard: applyElements() runs cy.batch(remove, add) which
    // can take a moment on a 1010-edge graph. If a second cgv_setData
    // arrives while the first is mid-flight (e.g. multiple queued
    // exec() calls fire during the same ResizeObserver flush), the
    // second applyElements would clobber the in-flight state and leave
    // the graph half-rendered. Queue the second payload and let the
    // first call's pending-data path pick it up.
    var cgvSetDataBusy = false;
    var cgvSetDataPending = null;

    window.cgv_setData = function () {
        log('cgv_setData called, __cgv_elements=' + (window.__cgv_elements ? window.__cgv_elements.length : 'null'));
        if (!window.__cgv_elements) {
            log('cgv_setData: no __cgv_elements yet');
            return;
        }
        // The first call wins. Capture the payload so a re-entrant call
        // doesn't drop it — but only the latest payload is kept, so the
        // canvas only ever ends up reflecting the most recent state.
        cgvSetDataPending = window.__cgv_elements;
        if (cgvSetDataBusy) return;
        cgvSetDataBusy = true;
        try {
            while (cgvSetDataPending) {
                var payload = cgvSetDataPending;
                cgvSetDataPending = null;
                applyElements(payload);
            }
        } finally {
            cgvSetDataBusy = false;
        }
    };

    window.cgv_setLayout = function (name) {
        log('cgv_setLayout: ' + name);
        currentLayout = name;
        if (cyReady && cy) {
            runLayout(currentLayout, pendingLayoutOptions);
        }
    };

    window.cgv_setLayoutOptions = function (options) {
        var keys = options ? Object.keys(options) : [];
        log('cgv_setLayoutOptions: ' + keys.length + ' keys: ' + keys.join(','));
        pendingLayoutOptions = options || {};
        if (cyReady && cy) {
            runLayout(currentLayout, pendingLayoutOptions);
        }
    };

    /**
     * Fallback: if Java hasn't pushed data after a short delay, fetch
     * {@code /api/sample-graph} ourselves so the demo graph always renders
     * (this also makes the viewer self-contained for testing without RAP).
     */
    function autoLoadFallback() {
        if (window.__cgv_elements && window.__cgv_elements.length > 0) {
            log('autoLoadFallback: data already present, skipping');
            return;
        }
        log('autoLoadFallback: no data from Java yet, fetching /api/sample-graph');
        debugStatus('auto-loading sample graph');
        fetch('/api/sample-graph', { credentials: 'include' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (root) {
                if (window.__cgv_elements && window.__cgv_elements.length > 0) {
                    log('autoLoadFallback: data arrived from Java in the meantime, ignoring fallback');
                    return;
                }
                log('autoLoadFallback: got ' + (root.elements ? root.elements.length : 0) + ' elements');
                window.__cgv_elements = root.elements;
                var opts = root.cytoscapeLayoutOptions || {};
                if (typeof opts.idealEdgeLength === 'string') {
                    try { opts.idealEdgeLength = new Function('return ' + opts.idealEdgeLength)(); }
                    catch (e) { console.warn('failed to compile idealEdgeLength', e); }
                }
                pendingLayoutOptions = opts;
                currentLayout = opts.name || 'fcose';
                // Apply the Leiden community colors BEFORE pushing data so
                // applyElements() can use them to pre-seed node positions
                // in their community's grid cell.
                if (root.leidenColors && cyReady && cy) {
                    try {
                        leidenColors = root.leidenColors;
                        var styles = [];
                        Object.keys(root.leidenColors).forEach(function (id) {
                            styles.push({
                                selector: 'node[id = "' + id + '"]',
                                style: { 'background-color': root.leidenColors[id] }
                            });
                        });
                        if (styles.length > 0) {
                            // Re-include defaultStyle() so the
                            // node:selected / edge:selected selectors
                            // survive the stylesheet replacement.
                            // Append imageNodeStyle() AFTER the Leiden
                            // overrides so image-badges win back their
                            // SVG rendering (Cytoscape applies the LAST
                            // matching rule). clusterStyleRules() (when
                            // active) sits BETWEEN styles and imageNode
                            // so the compound-parent dashed border wins
                            // over the Leiden per-node background-color.
                            var defaults = defaultStyle();
                            var merged = defaults
                                .concat(styles)
                                .concat(clusterStyleRules())
                                .concat([imageNodeStyle()]);
                            cy.style().fromJson(merged).update();
                            // Rebuild texture cache so SVG badges paint
                            // with the (possibly new) Leiden background
                            // color after the stylesheet swap.
                            preloadSvgImagesAndRedraw();
                        }
                        log('autoLoadFallback: applied ' + styles.length + ' Leiden colors');
                    } catch (e) { console.warn('leiden color apply failed', e); }
                }
                window.cgv_setData();
            })
            .catch(function (e) {
                log('autoLoadFallback failed: ' + e.message);
                debugStatus('auto-load failed: ' + e.message);
            });
    }

    /**
     * Debug helper: append a status line to a small overlay so we can see
     * what's happening without the browser dev-tools console. Only shown
     * on errors — successful flows stay invisible.
     */
    function debugStatus(text, isError) {
        log('debug: ' + text);
        if (typeof document === 'undefined') return;
        if (!isError) return; // suppress success overlays
        var el = document.getElementById('cgv-debug');
        if (!el) {
            el = document.createElement('div');
            el.id = 'cgv-debug';
            el.style.cssText = 'position:absolute;top:32px;right:4px;background:#ffe;color:#000;padding:4px 8px;font:11px monospace;z-index:10001;border:1px solid #c00;border-radius:3px;max-width:400px;white-space:pre-wrap;text-align:left;';
            document.body && document.body.appendChild(el);
        }
        el.textContent = text;
    }
    window.cgv_debugStatus = debugStatus;

    window.cgv_applyNodeConfig = function (config) {
        if (!cyReady || !cy) return;
        var style = buildStyleFromConfig(config || {});
        // Append imageNodeStyle() AFTER user-config overrides so image-badge
        // nodes always render their SVG (Cytoscape applies the LAST matching
        // style selector). clusterStyleRules() (when cluster layout is
        // active) sits BETWEEN user-config and imageNode so the compound
        // parent rules win over any per-node background-color override.
        style = style.concat(clusterStyleRules());
        style.push(imageNodeStyle());
        cy.style().fromJson(style).update();
        // Re-preload SVG badges — replacing the stylesheet rebuilds the
        // texture cache so any image that hadn't finished parsing is
        // gone from the cached Image() map.
        preloadSvgImagesAndRedraw();
    };

    /**
     * Swap the {@code data.image} attribute on a batch of nodes and
     * trigger a redraw so Cytoscape picks up the new sprite without a
     * full data reload. Called by the Java bridge whenever a
     * {@link NodeConfig} color override re-renders one or more SVG
     * badges (the new color is BAKED INTO the SVG body, so Cytoscape's
     * stylesheet {@code background-color} cannot change it — the only
     * way to recolor a badge is to swap the image URI).
     *
     * <p>{@code updates} is an array of {@code {id, image}} objects. The
     * bridge pre-loads each URI and waits for all loads to fire before
     * emitting {@code 'background'} on the touched nodes, mirroring the
     * preload-then-redraw fix used by {@code applyElements}.</p>
     */
    window.cgv_applyNodeImages = function (updates) {
        if (!cyReady || !cy || !updates || updates.length === 0) return;
        var uris = [];
        var touched = [];
        updates.forEach(function (u) {
            if (!u || !u.id || typeof u.image !== 'string' || u.image.length === 0) return;
            var n = cy.getElementById(u.id);
            if (n.length === 0) return;
            n.data('image', u.image);
            uris.push(u.image);
            touched.push(n);
        });
        if (uris.length === 0) return;
        log('cgv_applyNodeImages: swapping ' + uris.length + ' image(s)');
        // Preload each new URI so the texture cache repaints with the
        // freshly-parsed sprite instead of the cached empty placeholder.
        var pending = uris.length;
        var fired = false;
        uris.forEach(function (uri) {
            var im = new Image();
            im.onload = im.onerror = function () {
                pending--;
                if (pending <= 0 && !fired) {
                    fired = true;
                    touched.forEach(function (n) { n.emit('background'); });
                    try { cy.resize(); } catch (e) { /* ignore */ }
                }
            };
            im.src = uri;
        });
    };

    /**
     * Map a NodeConfig object (from Java) into a Cytoscape style array.
     * The config has the shape { showTitle, labelColors, tagColors }.
     */
    function buildStyleFromConfig(config) {
        var styles = defaultStyle();
        var showTitle = config.showTitle !== false;
        var labelColors = config.labelColors || {};
        var labelShapes = config.labelShapes || {};
        var tagColors = config.tagColors || {};
        var globalTagColors = config.globalTagColors || {};

        // Hide labels if requested.
        styles = styles.map(function (s) {
            if (s.selector === 'node' || s.selector === 'edge') {
                var styleCopy = Object.assign({}, s.style);
                if (!showTitle) {
                    styleCopy.label = '';
                }
                return { selector: s.selector, style: styleCopy };
            }
            return s;
        });

        // Per-nodeType color override.
        Object.keys(labelColors).forEach(function (nodeType) {
            styles.push({
                selector: 'node[nodeType = "' + nodeType + '"]',
                style: { 'background-color': labelColors[nodeType] }
            });
        });

        // Per-nodeType shape override (Cytoscape.js 'shape' style property).
        // Tokens come from cytoscape-Shape enum values (e.g. "ellipse",
        // "round-tag", "vee" — see Shape.cytoscapeName() in the Java side).
        Object.keys(labelShapes).forEach(function (nodeType) {
            var shapeToken = labelShapes[nodeType];
            if (!shapeToken) return;
            styles.push({
                selector: 'node[nodeType = "' + nodeType + '"]',
                style: { 'shape': shapeToken }
            });
        });

        // Per-(label, property, value) tag color override. The selector keys
        // on BOTH the node's primary nodeType AND the property/value. Useful
        // when the user wants to map colors only within one label's nodes.
        Object.keys(tagColors).forEach(function (nodeType) {
            var byProp = tagColors[nodeType] || {};
            Object.keys(byProp).forEach(function (prop) {
                var valueColors = byProp[prop] || {};
                Object.keys(valueColors).forEach(function (value) {
                    styles.push({
                        selector: 'node[nodeType = "' + nodeType + '"]["' + prop + '" = "' + cytoscapeQuote(value) + '"]',
                        style: { 'background-color': valueColors[value] }
                    });
                });
            });
        });

        // Global tag-color overrides. The selector matches ONLY on the
        // (property, value) — NO nodeType filter — so a value paints every
        // node across the entire graph whose property matches. Emitted by
        // the GraphConfigurationDialog "Apply Tag Colors" button when the
        // user selects a property like 'product' and applies its color map.
        Object.keys(globalTagColors).forEach(function (prop) {
            var valueColors = globalTagColors[prop] || {};
            Object.keys(valueColors).forEach(function (value) {
                styles.push({
                    // No nodeType filter — property-only match.
                    selector: 'node[' + prop + ' = "' + cytoscapeQuote(value) + '"]',
                    style: { 'background-color': valueColors[value] }
                });
            });
        });

        return styles;
    }

    /**
     * Cytoscape selector string escaping. The `'\\"'` quoting is what
     * allows values that contain special characters (spaces, German
     * umlauts, double-quote in the value itself) to round-trip through
     * the JSON-encoded style payload.
     */
    function cytoscapeQuote(value) {
        if (value == null) return '';
        return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    }

    /**
     * Tooltips for nodes and edges, vis-network-compatible behavior:
     *
     * <ul>
     *   <li>The element's `data.tooltip` HTML string is rendered in a
     *       floating DIV positioned next to the cursor whenever the user
     *       hovers the element.</li>
     *   <li>The element's `data.tooltip` text is also assigned as the
     *       HTML {@code title} attribute on the element so the browser's
     *       native tooltip serves as a fallback.</li>
     *   <li>The native Cytoscape text-events tooltip is enabled via
     *       {@code text-events: yes} in the default style.</li>
     * </ul>
     *
     * <p>Multiple event types (mouseover, tap) trigger the tooltip so
     * users on touch devices still get feedback when tapping a node.</p>
     */
    function attachTooltips(cy) {
        // Capture mouse coordinates from the most recent tap so the
        // tap-triggered tooltip positions itself sensibly on touch.
        var lastPointer = { x: 20, y: 20 };
        cy.on('mousemove', function (evt) {
            if (evt.originalEvent) {
                lastPointer.x = evt.originalEvent.clientX || lastPointer.x;
                lastPointer.y = evt.originalEvent.clientY || lastPointer.y;
            }
        });

        cy.on('mouseover', 'node, edge', function (evt) {
            var ele = evt.target;
            showTooltipFor(ele, evt.renderedPosition || lastPointer);
        });
        cy.on('mouseout', 'node, edge', function () { hideFloatingTooltip(); });
        // Also bind tap so touch / keyboard users see the tooltip.
        cy.on('tap', 'node, edge', function (evt) {
            var ele = evt.target;
            // Toggle: tapping the same element twice hides the tooltip.
            if (ele.scratch('_cgv_tooltip_visible')) {
                hideFloatingTooltip();
                ele.scratch('_cgv_tooltip_visible', false);
                return;
            }
            showTooltipFor(ele, evt.renderedPosition || lastPointer);
            ele.scratch('_cgv_tooltip_visible', true);
        });
        cy.on('tap', function (evt) {
            if (evt.target === cy) hideFloatingTooltip();
        });
        // Update the tooltip position as the mouse moves over the element.
        cy.on('mousemove', 'node, edge', function (evt) {
            if (evt.target === cy) return;
            var ele = evt.target;
            if (ele.scratch('_cgv_tooltip_visible')) {
                showTooltipFor(ele, evt.renderedPosition || lastPointer);
            }
        });
    }

    function showTooltipFor(ele, pos) {
        var html = buildTooltipHtml(ele);
        if (!html) return;
        showFloatingTooltip(html, pos);
    }

    /**
     * Build the tooltip HTML for an element. For edges, prepend a bold
     * "<from> -> <to>" header using the source/target node names so the
     * direction is obvious at a glance. The Java serializer stores the
     * header separately in `data.tooltipHeader`; if it's missing (e.g.
     * older data), we fall back to deriving it from the source/target.
     */
    function buildTooltipHtml(ele) {
        if (!ele || !ele.data) return null;
        var body = ele.data('tooltip');
        if (!body) return null;
        // Edges get a "<from> -> <to>" header using the connected
        // node labels. Prefer the explicit `name` property; fall back
        // to the node id (which is the same as the name in our CSV).
        if (ele.isEdge && ele.isEdge()) {
            var headerText = ele.data('tooltipHeader');
            if (!headerText) {
                var s = ele.source();
                var t = ele.target();
                var sLabel = s.data('name') || s.id();
                var tLabel = t.data('name') || t.id();
                headerText = sLabel + ' -> ' + tLabel;
            }
            var header = '<div class="cgv-tt-header">' + escapeHtml(headerText) + '</div>';
            return header + '<div class="cgv-tt-body">' + body + '</div>';
        }
        return body;
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function showFloatingTooltip(html, pos) {
        var tip = document.getElementById('cgv-tooltip');
        if (!tip) {
            tip = document.createElement('div');
            tip.id = 'cgv-tooltip';
            // z-index above the Cytoscape canvas (which sits at default 0)
            // so the tooltip is never hidden behind nodes / edges. The
            // pointer-events: none lets mouse events pass through to the
            // canvas underneath, so subsequent clicks still reach cytoscape.
            tip.style.cssText = 'position:absolute;background:#ffffff;border:1px solid #888;box-shadow:1px 1px 4px rgba(0,0,0,0.25);padding:0;font:12px Segoe UI,Arial,sans-serif;line-height:1.3;border-radius:4px;z-index:99999;max-width:340px;pointer-events:none;color:#222;display:none;overflow:hidden;';
            document.body && document.body.appendChild(tip);
        }
        tip.innerHTML = html;
        // Style the header / body sub-elements (set on every render in
        // case innerHTML overwrote them).
        var header = tip.querySelector('.cgv-tt-header');
        if (header) {
            header.style.cssText = 'padding:6px 10px;font-weight:600;background:#f4f4f4;border-bottom:1px solid #ccc;';
        }
        var body = tip.querySelector('.cgv-tt-body');
        if (body) {
            body.style.cssText = 'padding:6px 10px;';
        }
        var container = document.getElementById('cy');
        var rect = container ? container.getBoundingClientRect() : { left: 0, top: 0 };
        // pos.x and pos.y are the *container-local* coordinates returned by
        // cytoscape's renderedPosition. Translate them to viewport coords
        // (which is what style.left/top expect).
        var x = (pos && typeof pos.x === 'number') ? pos.x : 20;
        var y = (pos && typeof pos.y === 'number') ? pos.y : 20;
        tip.style.left = Math.round(rect.left + x + 14) + 'px';
        tip.style.top = Math.round(rect.top + y - 10) + 'px';
        tip.style.display = 'block';
    }

    function hideFloatingTooltip() {
        var tip = document.getElementById('cgv-tooltip');
        if (tip) tip.style.display = 'none';
        if (cy) {
            cy.elements().forEach(function (e) { e.scratch('_cgv_tooltip_visible', false); });
        }
    }

    window.cgv_applyLeidenColors = function (colors) {
        leidenColors = colors || {};
        if (!cyReady || !cy) return;
        var styles = [];
        Object.keys(leidenColors).forEach(function (nodeId) {
            var color = leidenColors[nodeId];
            styles.push({
                selector: 'node[id = "' + nodeId + '"]',
                style: { 'background-color': color }
            });
        });
        if (styles.length > 0) {
            // fromJson replaces the stylesheet, so we re-include the
            // default styles (which contain node:selected / edge:selected)
            // to keep the selection highlight working.
            var existing = cy.style().json();
            var defaults = defaultStyle();
            // Only add defaults that aren't already in the new styles.
            // Cytoscape applies the LAST matching style, so the Leiden
            // colors need to come AFTER the defaults in the array.
            // clusterStyleRules() (when active) sits BETWEEN the per-node
            // Leiden background-colors and imageNodeStyle() so the
            // compound-parent dashed border wins over the Leiden fill.
            // imageNodeStyle() comes LAST so image-badge nodes still
            // render their SVG even when Leiden set background-color.
            var merged = defaults
                .concat(styles)
                .concat(clusterStyleRules())
                .concat([imageNodeStyle()]);
            cy.style().fromJson(merged).update();
            // Rebuild the texture cache for SVG badges — see
            // preloadSvgImagesAndRedraw in applyElements for the rationale.
            preloadSvgImagesAndRedraw();
        }
    };

    window.cgv_clear = function () {
        if (cyReady && cy) {
            cy.elements().remove();
        }
    };

    window.cgv_fitToScreen = function () {
        if (cyReady && cy) {
            cy.fit(undefined, 30);
        }
    };

    window.cgv_applyLegend = function (entries, enabled) {
        applyLegend(entries, enabled);
    };

    /**
     * Iframe-side cleanup hook invoked by {@code CytoscapeJsBridge}
     * just before the Browser widget is disposed (e.g. on an engine
     * switch to vis-network). Removes the floating tooltip element from
     * {@code document.body} and clears any pending tooltip state. Without
     * this the orphan {@code #cgv-tooltip} div survives the iframe
     * swap and floats on top of the vis-network canvas, looking exactly
     * like "the graph is gone, only the tooltip is left".
     */
    window.cgv_dispose = function () {
        try { hideFloatingTooltip(); } catch (e) { /* ignore */ }
        // Clear any active legend highlight so a fresh Cytoscape instance
        // does not inherit the previous cluster dimming state.
        try { clearLegendHighlight(); } catch (e) { /* ignore */ }
        var tip = document.getElementById('cgv-tooltip');
        if (tip && tip.parentNode) tip.parentNode.removeChild(tip);
        var sidePanel = document.getElementById('cgv-side-panel');
        if (sidePanel && sidePanel.parentNode) sidePanel.parentNode.removeChild(sidePanel);
    };

    /**
     * Resize hook invoked by the SWT Resize listener on
     * {@link GraphViewer}/{@link CytoscapeViewer} so the canvas follows
     * the composite's actual dimensions. The cytoscape engine needs an
     * explicit {@code cy.resize()} + {@code cy.fit()} because it does
     * not auto-detect the parent's Resize event from inside the iframe.
     */
    window.cgv_resize = function () {
        if (!cy) return;
        // Guard against 0×0 sizes — the parent composite's FillLayout
        // can briefly report size 0 during the dispose/create sequence
        // of SwitchingViewer.switchTo(). A cy.resize() + cy.fit() against
        // a zero viewport collapses every node onto (0,0) and makes
        // the graph appear blank until the next valid Resize event.
        // Skip the call in that case — the next cgv_resize with a real
        // size will sort everything out.
        var c = document.getElementById('cy');
        if (c) {
            var w = c.clientWidth;
            var h = c.clientHeight;
            if (w <= 0 || h <= 0) return;
        }
        try {
            cy.resize();
            // Fit only after the very first paint — running fit() while
            // preloadSvgImagesAndRedraw is still in flight wipes out the
            // image badges that haven't finished parsing yet.
            if (cy.nodes().length > 0) {
                cy.fit(undefined, 30);
            }
        } catch (e) { /* ignore */ }
    };

    /* ---- context menu rendering ---- */

    window.cgv_showContextMenu = function (snapshot, x, y) {
        var menu = $('cgv-context-menu');
        if (!menu) return;
        menu.innerHTML = '';
        (snapshot.entries || []).forEach(function (entry) {
            if (entry.separator) {
                var sep = document.createElement('div');
                sep.className = 'cgv-menu-separator';
                menu.appendChild(sep);
                return;
            }
            var item = document.createElement('div');
            item.className = 'cgv-menu-item';
            item.textContent = entry.label || entry.id || '';
            item.addEventListener('click', function () {
                javaCall('cgv_invokeContextMenuAction', entry.id);
                window.cgv_hideContextMenu();
            });
            menu.appendChild(item);
        });
        menu.style.left = (x || 0) + 'px';
        menu.style.top = (y || 0) + 'px';
        menu.style.display = 'block';
        setTimeout(function () {
            document.addEventListener('click', hideOnOutside, { once: true });
        }, 0);
    };

    function hideOnOutside(evt) {
        var menu = $('cgv-context-menu');
        if (menu && !menu.contains(evt.target)) {
            window.cgv_hideContextMenu();
        }
    }

    window.cgv_hideContextMenu = function () {
        var menu = $('cgv-context-menu');
        if (menu) menu.style.display = 'none';
    };

    /* ---- boot ---- */

    log('script parsed, readyState=' + document.readyState);
    log('script loaded; cytoscape=' + (typeof cytoscape) + ', fcose=' + (typeof window.cytoscapeFcose) + ', layoutBase=' + (typeof window.layoutBase) + ', coseBase=' + (typeof window.coseBase));
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
