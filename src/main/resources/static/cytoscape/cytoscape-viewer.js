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

    /**
     * Announce viewer-ready to Java. Mirrors the
     * {@code waitForViewerReadyWrapper} pattern in vis-graph-viewer.js:
     * RAP's rap-client.js installs the BrowserFunction wrappers on
     * the iframe's window in its {@code _onload} handler, which runs
     * AFTER this IIFE but BEFORE the iframe's {@code load} event.
     * Calling {@code javaCall('cgv_viewerReady')} synchronously from
     * boot() races that install — the wrapper may not be on window
     * yet, {@code javaCall} would warn "BrowserFunction not
     * registered" and return silently, the Java side would never
     * see viewerReady, and every queued setGraphData / setLayout
     * call would sit in {@code pendingOps} forever. Polling for
     * the wrapper closes that window.
     */
    var cgvReadySent = false;
    function notifyViewerReady() {
        if (cgvReadySent) return;
        if (typeof window.cgv_viewerReady === 'function') {
            cgvReadySent = true;
            javaCall('cgv_viewerReady');
        } else {
            setTimeout(notifyViewerReady, 50);
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
            notifyViewerReady();
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
            notifyViewerReady();
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
        wireCommunityViewEvents();
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
            notifyViewerReady();
        }
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
     *
     * <p><b>Why a real Function, not a string.</b> The width mapper is
     * non-linear (sqrt, not LERP), so Cytoscape's native
     * {@code mapData(field, minIn, maxIn, minOut, maxOut)} mapper is not
     * a drop-in replacement — it would visibly thin edges with
     * logWeight between 0.5 and 1 by ~30% (33.27% thinner at lw=0.5).
     * The function mapper also lets us default missing data to 0
     * (Cytoscape's docs do not state what mapData returns for undefined
     * fields, and the cluster edge bundle must work on unweighted
     * edges too).</p>
     *
     * <p>The value is a real {@code Function} object, not a stringified
     * source. Cytoscape's {@code fromJson} does NOT evaluate
     * function-shaped strings (the docs warn about this explicitly) —
     * the function only works when it is a JS-level reference at the
     * time {@code fromJson} walks the array. Since this style rule is
     * built in JS (never round-tripped through JSON.parse), that is
     * exactly what we do here.</p>
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
                'width': function (edge) {
                    var lw = edge.data('logWeight');
                    lw = (typeof lw === 'number' && lw > 0) ? lw : 0;
                    return 0.6 + 0.9 * Math.sqrt(Math.min(Math.max(lw, 0), 4));
                }
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
            notifyViewerReady();
            return;
        }
        notifyViewerReady();
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
        // When the user is currently inside a community view, a
        // NodeConfig update (e.g. tag-color change) must NOT strip the
        // community-specific rules. Re-attach them so the community
        // nodes / edges keep their distinctive styling until the user
        // exits the view via "Back to Communities" or via dialog.
        if (communityViewState && communityViewState !== 'normal') {
            style = style.concat(communityStyleRules());
        }
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
     *
     * <p>Aggregated <b>community nodes</b> get a "Cluster members"
     * tooltip listing every node that belongs to the community — the
     * user explicitly asked for this so they can identify the cluster
     * without double-clicking into it.</p>
     */
    function buildTooltipHtml(ele) {
        if (!ele || !ele.data) return null;
        var body = ele.data('tooltip');
        if (!body) body = '';
        // Aggregated community-nodes: render a header + a list of member
        // node names. data.memberIds comes from CommunityAggregator; the
        // JS side resolves each id against the original-graph element
        // set (or the intra-detail set) via cy.getElementById().name.
        if (ele.isNode && ele.isNode() && body === '' &&
                ele.data('isCommunity') === true) {
            return buildCommunityNodeTooltip(ele);
        }
        // Edges get a "<from> -> <to>" header using the connected
        // node labels. Prefer the explicit `name` property; fall back
        // to the node id (which is the same as the name in our CSV).
        if (ele.isEdge && ele.isEdge()) {
            var headerText = ele.data('tooltipHeader');
            if (!headerText) {
                var s = ele.source();
                var t = ele.target();
                var sLabel = (s.data('name') || s.data('label') || s.id());
                var tLabel = (t.data('name') || t.data('label') || t.id());
                headerText = sLabel + ' -> ' + tLabel;
            }
            var header = '<div class="cgv-tt-header">' + escapeHtml(headerText) + '</div>';
            return header + '<div class="cgv-tt-body">' + body + '</div>';
        }
        if (!body) return null;
        return body;
    }

    /**
     * Build the "Cluster members" tooltip HTML for an aggregated
     * community-node. Uses {@code data.memberIds} (the original
     * {@link GraphNode} ids) and joins with the current canvas's
     * {@code name} property. Falls back to the id when the member
     * node is not currently present in the canvas (detail view).
     */
    function buildCommunityNodeTooltip(communityNode) {
        var memberIds = communityNode.data('memberIds') || [];
        var headerText = communityNode.data('label') || communityNode.id();
        var header = '<div class="cgv-tt-header">' + escapeHtml(headerText) + '</div>';
        var lines = [];
        for (var i = 0; i < memberIds.length; i++) {
            var mid = memberIds[i];
            var m = cy.getElementById(mid);
            var name = (m && m.length > 0)
                    ? (m.data('name') || m.data('label') || mid) : mid;
            lines.push('<div class="cgv-tt-member">' + escapeHtml(String(name)) + '</div>');
        }
        var body;
        if (lines.length === 0) {
            body = '<div class="cgv-tt-body"><em>(keine Member-Knoten)</em></div>';
        } else {
            body = '<div class="cgv-tt-body">' +
                    '<div class="cgv-tt-section-title">Mitglieder (' +
                    lines.length + '):</div>' + lines.join('') + '</div>';
        }
        return header + body;
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
        var sectionTitle = tip.querySelector('.cgv-tt-section-title');
        if (sectionTitle) {
            sectionTitle.style.cssText = 'font-weight:600;margin:0 0 4px 0;color:#555;font-size:11px;text-transform:uppercase;letter-spacing:0.4px;';
        }
        var members = tip.querySelectorAll('.cgv-tt-member');
        for (var mi = 0; mi < members.length; mi++) {
            members[mi].style.cssText = 'padding:1px 0;font-size:12px;line-height:1.4;';
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
                .concat(clusterStyleRules());
            // Same reasoning as cgv_applyNodeConfig: when the user is
            // inside the community view, re-attach the community rules
            // so a Leiden re-run doesn't strip them out.
            if (communityViewState && communityViewState !== 'normal') {
                merged = merged.concat(communityStyleRules());
            }
            merged.push(imageNodeStyle());
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

    /* ============================================================== */
    /*  Community Aggregation View ("Show kumulated Communities")      */
    /* ============================================================== */

    /**
     * Module-level state for the optional community-aggregation view.
     * Drives whether the canvas shows the original nodes, the
     * aggregated root view (one node per community), or a drilled-down
     * detail view (one community's original nodes only).
     *
     * <ul>
     *   <li>{@code 'normal'} — default; shows the original graph.</li>
     *   <li>{@code 'root'} — aggregated view; one node per community.</li>
     *   <li>{@code 'detail:<hex>'} — drill-down into a single community.</li>
     * </ul>
     *
     * <p>The state is queried by {@link applyCommunityView} so a second
     * invocation can replace the previous payload without leaving
     * stale elements on the canvas.</p>
     */
    var communityViewState = 'normal';

    /**
     * Set to the hex colour of the community currently being shown in
     * detail view. {@code null} when the view is 'normal' or 'root'.
     */
    var communityViewDrillColor = null;

    /**
     * Whether the cytoscape-side {@code communityNodeStyle} should scale
     * community-node sizes logarithmically with {@code incomingWeightSum}
     * ({@code true}) or render every node at the same fixed size
     * ({@code false}, the user-requested default). Threaded through
     * {@code cgv_applyCommunityView} from the Java bridge.
     */
    var communityDynamicSize = false;

    /**
     * Cytoscape style rule for an aggregated community-node produced by
     * {@link CommunityAggregator.buildRootElements} on the Java side.
     *
     * <p>Visual design (per the user spec):</p>
     * <ul>
     *   <li>Shape: <b>ellipse</b> (a true circle) — was round-rectangle.</li>
     *   <li>Background: the Leiden palette colour at <b>full opacity</b>
     *       (was 0.18) so the node visually matches its legend entry.</li>
     *   <li>Border: same colour, solid 3 px (was dashed).</li>
     *   <li>Padding: 0 — the arrow lands directly on the circle's
     *       perimeter ("arrows arrive from inside the circle").</li>
     *   <li>Label: white text for contrast against the saturated fill.</li>
     *   <li>Size: depends on the {@code communityDynamicSize} flag
     *       threaded from the Java dialog:
     *       <ul>
     *         <li>{@code communityDynamicSize === true} — logarithmic in
     *             {@code incomingWeightSum} so heavy "load sink"
     *             communities visibly grow (cap 140 px so adjacent
     *             circle-neighbours on the layout never overlap).</li>
     *         <li>{@code communityDynamicSize === false} (user default)
     *             — every community-node renders at the same fixed
     *             110 px diameter so the cluster layout reads uniformly.</li>
     *       </ul></li>
     * </ul>
     */
    function communityNodeStyle() {
        var FIXED_SIZE = 110;
        return { selector: 'node[?isCommunity]',
            style: {
                'shape': 'ellipse',
                'background-color': 'data(communityColor)',
                'background-opacity': 1.0,
                'border-width': 3,
                'border-color': 'data(communityColor)',
                'border-style': 'solid',
                'padding': '0px',
                'label': 'data(label)',
                'font-size': 13,
                'color': '#ffffff',
                'text-valign': 'center',
                'text-halign': 'center',
                'text-wrap': 'wrap',
                'min-width': FIXED_SIZE,
                'min-height': FIXED_SIZE,
                // Dynamic vs fixed size controlled by the dialog's
                // 'Dynamic Clusternode Size' checkbox (default false).
                'width': function (n) {
                    if (!communityDynamicSize) return FIXED_SIZE;
                    var inSum = n.data('incomingWeightSum');
                    inSum = (typeof inSum === 'number' && inSum > 0) ? inSum : 0;
                    return Math.min(140, Math.max(70, 45 + Math.log(inSum + 1) * 12));
                },
                'height': function (n) {
                    if (!communityDynamicSize) return FIXED_SIZE;
                    var inSum = n.data('incomingWeightSum');
                    inSum = (typeof inSum === 'number' && inSum > 0) ? inSum : 0;
                    return Math.min(140, Math.max(70, 45 + Math.log(inSum + 1) * 12));
                }
            }};
    }

    /**
     * Cytoscape style rule for an aggregated inter-community edge.
     *
     * <p>The Java-side {@code CommunityAggregator.buildRootElements}
     * emits one edge PER DIRECTION (A->B and B->A are separate). The
     * cytoscape bridge therefore:</p>
     * <ul>
     *   <li>colors the line + target arrow in the SOURCE community's
     *       colour (data.sourceCommunityColor / data.targetCommunityColor) —
     *       so a quick glance shows where traffic flows FROM;</li>
     *   <li>scales the line WIDTH logarithmically with the raw weight,
     *       <b>halved</b> from the previous formula
     *       {@code 0.6 + 1.5 * log(w+1)} (cap 12 px) to
     *       {@code 0.3 + 0.75 * log(w+1)} (cap 6 px) — keeps the
     *       overall canvas light when many communities are stacked on
     *       a circle;</li>
     *   <li>renders a bezier curve with a generous
     *       {@code control-point-step-size} so the A->B and the B->A
     *       cable stay visibly apart and do not overlap;</li>
     *   <li>shows a Cytoscape-native tooltip
     *       "{@code Cluster N -> Cluster M: <sumWeight>}" on hover.</li>
     * </ul>
     */
    function communityEdgeStyle() {
        return { selector: 'edge[?isCommunityEdge]',
            style: {
                'curve-style': 'bezier',
                // 80px separation is enough that two parallel bezier
                // cables (A->B and B->A between the same pair of
                // communities) read as distinct arcs even at high zoom.
                'control-point-step-size': 80,
                'target-arrow-shape': 'triangle',
                'line-color': 'data(sourceCommunityColor)',
                'target-arrow-color': 'data(targetCommunityColor)',
                'opacity': 0.9,
                // Logarithmic width in raw weight. Halved from the
                // previous 0.6 + 1.5 * log(w+1) formula (cap 12) so the
                // community overview doesn't drown in cable weight.
                // Cap 6 px — even at w=10000 the cable stays readable
                // without dominating adjacent node circles.
                'width': function (edge) {
                    var w = edge.data('weight');
                    w = (typeof w === 'number' && w > 0) ? w : 0;
                    return Math.min(6, Math.max(0.3, 0.3 + 0.75 * Math.log(w + 1)));
                },
                'label': function (e) {
                    var cnt = e.data('edgeCount');
                    return (typeof cnt === 'number' && cnt > 1) ? (cnt + 'x') : '';
                },
                'font-size': 11,
                'color': '#333333',
                'text-rotation': 'autorotate',
                'text-background-color': '#ffffff',
                'text-background-opacity': 0.7,
                'text-background-padding': '2px',
                // Cytoscape-native tooltip — "Cluster N -> Cluster M: <sum>".
                // text-events:'yes' (the default for edges) lets the
                // browser render the tooltip on hover.
                'tooltip': function (e) { return e.data('tooltip') || ''; }
            }};
    }

    /**
     * Compose the community-view style rules into a single array so
     * callers can splice them into a {@code cy.style().fromJson(...)}
     * payload. Mirrors the {@link clusterStyleRules} pattern used by
     * the regular cluster-layout path.
     */
    function communityStyleRules() {
        return [communityNodeStyle(), communityEdgeStyle()];
    }

    /**
     * Rebuild the Cytoscape stylesheet so the community-view rules
     * (node[?isCommunity] / edge[?isCommunityEdge]) reach the renderer.
     *
     * <p>{@code mode} is currently unused but accepted so callers can
     * later branch on root vs detail without changing the call site.</p>
     */
    function applyStyleForCommunityView(mode) {
        if (!cyReady || !cy) return;
        try {
            var defaults = defaultStyle();
            var rules = defaults
                .concat(clusterStyleRules())
                .concat(communityStyleRules());
            rules.push(imageNodeStyle());
            cy.style().fromJson(rules).update();
        } catch (e) {
            log('applyStyleForCommunityView failed: ' + e.message);
        }
    }

    /**
     * Rebuild the Cytoscape stylesheet WITHOUT the community rules.
     * Used by {@code cgv_clearCommunityView} so the freshly-pushed
     * original nodes render with their per-type colors instead of
     * dangling community rules.
     */
    function applyStyleWithoutCommunity() {
        if (!cyReady || !cy) return;
        try {
            var defaults = defaultStyle();
            var rules = defaults.concat(clusterStyleRules());
            rules.push(imageNodeStyle());
            cy.style().fromJson(rules).update();
        } catch (e) {
            log('applyStyleWithoutCommunity failed: ' + e.message);
        }
    }

    /**
     * Pre-seed community-node positions on a CIRCLE for the root view.
     * The largest community (highest {@code memberCount}) sits at 12
     * o'clock; the remaining communities are placed clockwise in
     * descending size. Deterministic — id-based tiebreak for equal-sized
     * communities.
     *
     * <p>The radius is the MAX of three quantities: a hard floor, a
     * viewport-relative default, and a "no-overlap" minimum that grows
     * with the number of communities. The no-overlap minimum guarantees
     * that adjacent circles on the ring always have at least
     * {@code maxNodeSize} arc length between them — the
     * {@link communityNodeStyle} size cap (140 px) is the binding
     * constraint.</p>
     *
     * <p>After positioning, a no-op {@code cy.layout({name:'preset'})}
     * confirms the manual positions and prevents Cytoscape from later
     * recomputing them when the container resizes.</p>
     */
    function preseedCommunityCirclePositions() {
        if (!cy) return;
        var communityNodes = cy.nodes().filter(function (n) {
            return n.data('isCommunity') === true;
        });
        if (communityNodes.length === 0) return;
        var k = communityNodes.length;
        // Frame sized so that the circle plus labels fits comfortably
        // even at moderate zoom. 1600 × 900 keeps the aspect ratio close
        // to the standard Cytoscape canvas.
        var FRAME_W = 1600;
        var FRAME_H = 900;
        // Hard ceiling per community-node diameter. Mirrors the cap /
        // fixed-size value in communityNodeStyle() — if the two drift
        // apart, update both. When the user runs the community overview
        // with the default (Dynamic Clusternode Size = OFF) we use 70 px
        // (= 110 / 2 rounded down) instead of 140 px so the circle is
        // half as large per the user's "50 % kleiner" request — the
        // uniformly-sized nodes don't need the larger arc.
        var maxNodeSize = communityDynamicSize ? 140 : 70;
        // No-overlap minimum radius: arc length per community must be at
        // least maxNodeSize, so 2π·r/k >= maxNodeSize  =>  r >= k·maxNodeSize/(2π).
        // We add another maxNodeSize of slack so the circles sit comfortably
        // inside the ring rather than kissing each other.
        var noOverlapRadius = Math.ceil(k * maxNodeSize / (2 * Math.PI)) + maxNodeSize;
        var maxRadius = Math.min(FRAME_W, FRAME_H) * 0.46;
        var defaultRadius = Math.max(180, maxRadius * (1 + Math.min(k, 12) / 24));
        var radius = Math.max(noOverlapRadius, Math.min(maxRadius, defaultRadius));

        var nodes = [];
        communityNodes.forEach(function (n) { nodes.push(n); });
        nodes.sort(function (a, b) {
            var ca = a.data('memberCount') || 0;
            var cb = b.data('memberCount') || 0;
            if (cb !== ca) return cb - ca;
            return String(a.id()).localeCompare(String(b.id()));
        });

        // theta_0 = -PI/2  ->  12 o'clock
        // step   =  2*PI/k -> clockwise (Cytoscape's y-axis points DOWN
        //                     so sin(theta) already flips y accordingly)
        for (var i = 0; i < k; i++) {
            var angle = -Math.PI / 2 + 2 * Math.PI * i / k;
            var n = nodes[i];
            n.position({
                x: Math.cos(angle) * radius,
                y: Math.sin(angle) * radius
            });
        }
        try {
            cy.layout({ name: 'preset', animate: false, fit: false }).run();
        } catch (e) { /* ignore */ }
        log('preseedCommunityCirclePositions: ' + k
                + ' communities on circle radius=' + Math.round(radius)
                + ' (noOverlapMin=' + Math.round(noOverlapRadius) + ')');
    }

    /**
     * Show or hide the community navigation overlay (Back button + label).
     * Toggles the {@code #cgv-community-nav} element defined in
     * cytoscape-viewer.html. The label text comes from the
     * {@code data-community-label} attribute so the Java side can
     * supply a localised description.
     */
    function setCommunityNavVisibility(visible, label) {
        var nav = $('cgv-community-nav');
        if (!nav) return;
        nav.style.display = visible ? 'flex' : 'none';
        var labelEl = $('cgv-community-label');
        if (labelEl && label) labelEl.textContent = label;
    }

    /**
     * Apply the community-aggregation view. Called by
     * {@code cgv_applyCommunityView} from the Java bridge.
     *
     * <p>The function replaces the canvas elements with the supplied
     * payload, runs the appropriate preset / grid layout, and toggles
     * the back-navigation overlay. {@code mode === 'root'} shows one
     * node per community; {@code mode === 'detail'} shows the original
     * nodes of the community whose hex colour is encoded in each
     * member-node's parent (or looked up via the per-element
     * {@code _communityColor} field).</p>
     */
    function applyCommunityView(mode, elements) {
        if (!cyReady || !cy) {
            log('applyCommunityView: not ready, deferring (' + mode + ')');
            pendingCommunityElements = { mode: mode, elements: elements };
            return;
        }
        if (activeLegendColor) {
            activeLegendColor = null;
            renderLegendPanel();
        }
        try {
            cy.batch(function () {
                cy.elements().remove();
                cy.add(elements || []);
            });
        } catch (e) {
            showError('applyCommunityView failed: ' + e.message);
            return;
        }
        communityViewState = mode;
        // Push the community-aware stylesheet so node[?isCommunity] /
        // edge[?isCommunityEdge] rules win over the generic node/edge
        // defaults. Without this rebuild, community-nodes fall back to
        // blue circles and community-edges to grey lines.
        applyStyleForCommunityView(mode);
        // Swap the tap-handler set: community-view handlers route
        // selection to the #cgv-community-edges table + dim the rest
        // of the canvas, the normal-view handlers fire the Java
        // node/relationship listener callbacks.
        setCommunitySelectionEnabled(true);
        if (mode === 'root') {
            communityViewDrillColor = null;
            preseedCommunityCirclePositions();
            try { cy.fit(undefined, 40); } catch (e) { /* ignore */ }
            setCommunityNavVisibility(false, '');
        } else if (mode === 'detail') {
            // Try to extract the community colour from the first member's
            // data — the Java side stamps every member with a
            // _communityColor field so we can label the navigation bar.
            var firstNode = cy.nodes()[0];
            var drillColor = firstNode ? firstNode.data('_communityColor') : null;
            communityViewDrillColor = drillColor || null;
            // Run a quick fcose layout for the drilled view so the nodes
            // spread out nicely without depending on the main
            // clusterLayout options.
            try {
                cy.layout({
                    name: 'fcose',
                    quality: 'default',
                    randomize: true,
                    animate: false,
                    fit: true,
                    padding: 40,
                    nodeRepulsion: 4500,
                    idealEdgeLength: 80,
                    edgeElasticity: 0.45,
                    gravity: 0.25,
                    numIter: 1500
                }).run();
            } catch (e) {
                log('applyCommunityView detail: fcose layout failed: ' + e.message);
            }
            try { cy.fit(undefined, 40); } catch (e) { /* ignore */ }
            var memberCount = cy.nodes().length;
            var label = 'Detail view: ' + memberCount + ' member nodes';
            if (drillColor) label += ' (color ' + drillColor + ')';
            setCommunityNavVisibility(true, label);
        } else {
            communityViewDrillColor = null;
            setCommunityNavVisibility(false, '');
        }
        notifyViewerReady();
        log('applyCommunityView: mode=' + mode + ', '
                + cy.nodes().length + ' nodes, ' + cy.edges().length + ' edges');
    }

    /**
     * Pending community-view payload. When applyCommunityView arrives
     * before the Cytoscape boot is complete, we stash it here so the
     * {@code cgv_setData} re-entrancy guard can hand off to it once the
     * viewer is ready.
     */
    var pendingCommunityElements = null;

    /**
     * Java-callable entry point for the community-aggregation view.
     * {@code mode} is either {@code 'root'} or {@code 'detail'}; the
     * payload is the JSON elements array produced by
     * {@code CommunityAggregator.buildRootElements} /
     * {@code buildCommunityDetailElements} on the Java side.
     */
    window.cgv_applyCommunityView = function (mode, elements, dynamicSize) {
        log('cgv_applyCommunityView: mode=' + mode + ', elements='
                + (elements ? elements.length : 0)
                + ', dynamicSize=' + dynamicSize);
        // Store the dynamic-size flag BEFORE applyCommunityView runs so
        // the communityNodeStyle function mappers see the right value
        // when the stylesheet is rebuilt.
        communityDynamicSize = (dynamicSize === true);
        applyCommunityView(mode, elements || []);
    };

    /**
     * Java-callable entry point that returns the canvas to the original
     * (non-aggregated) node view. The Java bridge is responsible for
     * re-pushing the original {@code __cgv_elements} via the normal
     * {@code cgv_setData} path AFTER this call returns, so we just clear
     * the visible state here.
     */
    window.cgv_clearCommunityView = function () {
        log('cgv_clearCommunityView');
        communityViewState = 'normal';
        communityViewDrillColor = null;
        communityDynamicSize = false;
        setCommunityNavVisibility(false, '');
        hideCommunityEdgesTable();
        // Restore the normal-view tap handlers BEFORE rebuilding the
        // stylesheet so a subsequent background-tap (or any orphan tap)
        // is handled by the generic path, not by stale community-view
        // handlers that no longer match the elements on the canvas.
        setCommunitySelectionEnabled(false);
        // Rebuild the stylesheet WITHOUT community rules so the
        // freshly-pushed original nodes (which the Java bridge loads
        // via applyData right after this call) render with their
        // per-type colors. Without this, the community-nodes / edges
        // would have been replaced but the stylesheet would still
        // contain community-specific selectors that no longer match.
        applyStyleWithoutCommunity();
    };

    /**
     * Wire the BACK-BUTTON DOM click handler. Called once from
     * {@link boot} right after Cytoscape is up. The cytoscape-side
     * dblclick-on-community-node + tap handlers are now installed by
     * {@link wireCommunitySelectionEvents} (per community-view activation)
     * so they can be torn down cleanly via
     * {@link setCommunitySelectionEnabled}.
     */
    function wireCommunityViewEvents() {
        var backBtn = $('cgv-community-back');
        if (backBtn) {
            backBtn.addEventListener('click', function () {
                log('community back button clicked');
                javaCall('cgv_notifyCommunityDrillOut');
            });
        }
    }

    /* ============================================================== */
    /*  Community Selection (root view only)                          */
    /* ============================================================== */

    /**
     * Selection handlers for the community-aggregation root view.
     *
     * <p>Wired via {@link setCommunitySelectionEnabled} which is called
     * by {@link applyCommunityView} on entry and by
     * {@link window.cgv_clearCommunityView} on exit. The handlers
     * intentionally do NOT fire the regular
     * {@code cgv_notifyNodeSelected} callback because the cytoscape
     * community-node id (e.g. {@code community_0}) is a synthetic
     * identifier with no matching Java {@code GraphNode} — the Java
     * bridge would {@code findNode(...).ifPresent(...)} on a non-existent
     * id and the listener would silently no-op.</p>
     *
     * <p>Instead, the table-row click is the single source of
     * {@code cgv_notifyRelationshipSelected} events (see
     * {@link onCommunityEdgeRowClick}), and the background-tap clears
     * the selection without an explicit Java callback.</p>
     */
    function wireCommunitySelectionEvents() {
        if (!cy) return;
        // The dblclick-on-community-node handler fires the drill-down
        // event to Java. Registered ONCE per community-view activation
        // (and implicitly replaced via cy.removeAllListeners('dblclick')
        // by setCommunitySelectionEnabled), so it cannot get out of
        // sync with the active handler set.
        cy.on('dblclick', 'node[?isCommunity]', function (evt) {
            var node = evt.target;
            if (!node) return;
            var color = node.data('originalColor') || node.data('communityColor');
            if (!color) return;
            log('dblclick on community node: ' + node.id() + ', color=' + color);
            javaCall('cgv_notifyCommunityDrillDown', color);
        });
        // Tap on a community-node: toggle selection + table.
        cy.on('tap', 'node[?isCommunity]', function (evt) {
            var node = evt.target;
            if (!node) return;
            if (node.selected()) {
                node.unselect();
                clearNeighborhoodHighlight(cy);
                hideCommunityEdgesTable();
            } else {
                cy.elements().unselect();
                node.select();
                highlightCommunityNode(node);
                renderCommunityEdgesTable(node);
            }
        });
        // Tap on a community-edge: visual selection only — the aggregated
        // edge has no Java GraphRelationship so we don't fire any
        // java-side listener here (table rows do the routing).
        cy.on('tap', 'edge[?isCommunityEdge]', function (evt) {
            var edge = evt.target;
            if (!edge) return;
            if (edge.selected()) {
                edge.unselect();
                clearNeighborhoodHighlight(cy);
                hideCommunityEdgesTable();
            } else {
                cy.elements().unselect();
                edge.select();
                highlightCommunityEdge(edge);
                // Hide the table — it's only for node-selection. Re-show
                // if the user clicks back on a node.
                hideCommunityEdgesTable();
            }
        });
        // Background tap clears the selection + table.
        cy.on('tap', function (evt) {
            if (evt.target === cy) {
                var sel = cy.elements(':selected');
                if (sel.length > 0) sel.unselect();
                clearNeighborhoodHighlight(cy);
                hideCommunityEdgesTable();
            }
        });
        // Re-attach the tooltip system so mouseover/mouseout for the
        // floating #cgv-tooltip survive the removeAllListeners('tap')
        // call in setCommunitySelectionEnabled. attachTooltips itself is
        // idempotent (it just registers fresh listeners each time).
        attachTooltips(cy);
        // Re-register the dblclick back-button DOM click as well — it's
        // a static DOM event so it's unaffected, but keep the lifecycle
        // explicit.
        var backBtn = $('cgv-community-back');
        if (backBtn && !backBtn.__cgvBackWired) {
            backBtn.__cgvBackWired = true;
            backBtn.addEventListener('click', function () {
                log('community back button clicked');
                javaCall('cgv_notifyCommunityDrillOut');
            });
        }
    }

    /**
     * Apply / drop the community-specific tap handlers. We use the
     * generic {@link wireCytoscapeEvents} for the normal graph view
     * and {@link wireCommunitySelectionEvents} for the aggregated root
     * view because the same {@code tap} event has different semantics
     * in the two views (node id != java node id in the aggregated
     * view).
     *
     * <p>Implementation note: we {@code removeAllListeners('tap')} so
     * we never end up with both handler sets active at once (which
     * would lead to duplicate highlight / table updates).</p>
     */
    function setCommunitySelectionEnabled(enabled) {
        if (!cy) return;
        try {
            // Strip ALL tap + dblclick listeners so we never end up with
            // BOTH the community-view and the generic-view handler sets
            // active at once. attachTooltips registers its own listeners
            // per-call, so we re-attach them inside wireCommunitySelectionEvents.
            cy.removeAllListeners('tap');
            cy.removeAllListeners('dblclick');
        } catch (e) {
            log('setCommunitySelectionEnabled: removeAllListeners failed: ' + e.message);
        }
        if (enabled) {
            wireCommunitySelectionEvents();
        } else {
            // wireCytoscapeEvents expects a cy argument and registers the
            // generic tap handlers used by the normal (non-aggregated)
            // graph view. The function is defined at the IIFE root.
            wireCytoscapeEvents(cy);
        }
    }

    /**
     * Re-use the normal-view neighborhood highlight: dim everything
     * that isn't the selected community-node + its 1-hop neighbours.
     * 1-hop in the aggregated root view = the selected community + all
     * connected community-nodes (via inter-community edges) + those
     * edges. Exactly the "alle eingehenden und ausgehenden Edges und
     * die Node" the user asked for.
     */
    function highlightCommunityNode(node) {
        clearNeighborhoodHighlight(cy);
        if (!node.neighborhood) return;
        var hood = node.neighborhood().add(node);
        var others = cy.elements().difference(hood);
        if (others.length > 0) others.addClass('cgv-faded');
    }

    /** Highlight a single community-edge + its two endpoint nodes. */
    function highlightCommunityEdge(edge) {
        clearNeighborhoodHighlight(cy);
        var hood = edge.connectedNodes().union(edge);
        var others = cy.elements().difference(hood);
        if (others.length > 0) others.addClass('cgv-faded');
    }

    /**
     * Build the "edges-of-selected-community" table for the root view.
     * One row per ORIGINAL {@link GraphRelationship} id
     * ({@code data.memberEdgeIds}) so a row-click can route to the
     * real Java {@code RelationshipSelectionListener}.
     *
     * <p>Sort: incoming edges (target == selected community) first,
     * then outgoing, both groups sorted by weight desc.</p>
     */
    function renderCommunityEdgesTable(node) {
        var panel = document.getElementById('cgv-community-edges');
        if (!panel) return;
        var body = panel.querySelector('.cgv-community-edges-body');
        var empty = panel.querySelector('.cgv-community-edges-empty');
        if (!body || !empty) return;
        body.innerHTML = '';
        var rows = [];
        node.connectedEdges().forEach(function (e) {
            var s = e.source();
            var t = e.target();
            var memberIds = e.data('memberEdgeIds') || [];
            var incoming = t.id() === node.id();
            rows.push({
                edgeId: e.id(),
                memberEdgeIds: memberIds,
                sourceLabel: incoming ? s.data('label') : t.data('label'),
                targetLabel: incoming ? t.data('label') : s.data('label'),
                weight: e.data('weight'),
                incoming: incoming
            });
        });
        rows.sort(function (a, b) {
            if (a.incoming !== b.incoming) return a.incoming ? -1 : 1;
            return (b.weight || 0) - (a.weight || 0);
        });
        if (rows.length === 0) {
            empty.textContent = 'Keine Edges an dieser Community.';
            empty.style.display = 'block';
            panel.style.display = 'block';
            return;
        }
        empty.style.display = 'none';
        var table = document.createElement('table');
        table.className = 'cgv-community-edges-table';
        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th>From</th><th>Weight</th><th>To</th></tr>';
        table.appendChild(thead);
        var tbody = document.createElement('tbody');
        rows.forEach(function (r) {
            // Expand the aggregated edge into one row per ORIGINAL
            // GraphRelationship id so a row-click can route to the
            // real Java listener. When the aggregated edge folded
            // only one member we just emit that single row.
            var edgeIds = r.memberEdgeIds.length > 0
                    ? r.memberEdgeIds : [r.edgeId];
            edgeIds.forEach(function (eid) {
                var tr = document.createElement('tr');
                tr.dataset.edgeId = eid;
                tr.title = eid;
                tr.className = r.incoming ? 'cgv-edge-incoming' : 'cgv-edge-outgoing';
                var fromTd = document.createElement('td');
                fromTd.className = 'cgv-edge-from';
                fromTd.textContent = r.sourceLabel;
                var weightTd = document.createElement('td');
                weightTd.className = 'cgv-edge-weight';
                weightTd.textContent = formatWeight(r.weight);
                var toTd = document.createElement('td');
                toTd.className = 'cgv-edge-to';
                toTd.textContent = r.targetLabel;
                tr.appendChild(fromTd);
                tr.appendChild(weightTd);
                tr.appendChild(toTd);
                tr.addEventListener('click', function (evt) {
                    onCommunityEdgeRowClick(eid, evt);
                });
                tbody.appendChild(tr);
            });
        });
        table.appendChild(tbody);
        body.appendChild(table);
        panel.style.display = 'block';
    }

    /** Hide and clear the community-edges table. Idempotent. */
    function hideCommunityEdgesTable() {
        var panel = document.getElementById('cgv-community-edges');
        if (!panel) return;
        panel.style.display = 'none';
        var body = panel.querySelector('.cgv-community-edges-body');
        if (body) body.innerHTML = '';
        var empty = panel.querySelector('.cgv-community-edges-empty');
        if (empty) empty.style.display = 'none';
    }

    /**
     * Row-Click-Handler. Fires
     * {@code javaCall('cgv_notifyRelationshipSelected', edgeId)} so the
     * Java-side {@code RelationshipSelectionListener} chain picks up
     * the original relationship id; for aggregated inter-community
     * ids (e.g. {@code inter_#abc_to_#def}) Java's
     * {@link GraphData#findRelationship} returns {@code Optional.empty()}
     * which is the correct behaviour — those ids are not real
     * relationships.
     */
    function onCommunityEdgeRowClick(edgeId, evt) {
        if (evt) evt.stopPropagation();
        if (!cy) return;
        javaCall('cgv_notifyRelationshipSelected', edgeId);
        // Best-effort: also set the cytoscape selection if the edge id
        // exists in the current canvas (true for the original-id rows
        // in the detail view; a no-op for aggregated ids in the root
        // view).
        var edge = cy.getElementById(edgeId);
        if (edge && edge.length > 0) {
            cy.elements().unselect();
            edge.select();
        }
    }

    /* ---- boot ---- */

    log('script parsed, readyState=' + document.readyState);
    log('script loaded; cytoscape=' + (typeof cytoscape) + ', fcose=' + (typeof window.cytoscapeFcose) + ', layoutBase=' + (typeof window.layoutBase) + ', coseBase=' + (typeof window.coseBase));
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
