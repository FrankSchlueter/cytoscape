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
        log('boot() enter');
        if (typeof cytoscape === 'undefined') {
            showError('Cytoscape library not loaded — check /cytoscape/cytoscape.min.js');
            javaCall('cgv_viewerReady');
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
                wheelSensitivity: 0.2,
                minZoom: 0.1,
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
            return;
        }

        // Force a resize so cytoscape knows the real container dimensions
        // before the first layout. Without this, fcose computes coordinates
        // against a 0x0 viewport and the layout collapses to a point.
        try { cy.resize(); } catch (e) { /* ignore */ }

        wireCytoscapeEvents(cy);
        attachTooltips(cy);
        cyReady = true;

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
                  'width': 1.5,
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
                var preseed = leidenColors && preseedCommunityPositions(leidenColors);
                if (preseed) {
                    log('postLoad: community preset applied, kicking off fcose async');
                } else {
                    log('postLoad: no community map, falling back to circle preset');
                    cy.nodes().forEach(function (n, i) {
                        var angle = (i / cy.nodes().length) * Math.PI * 2;
                        n.position({
                            x: cy.width() / 2 + Math.cos(angle) * Math.min(cy.width(), cy.height()) * 0.35,
                            y: cy.height() / 2 + Math.sin(angle) * Math.min(cy.width(), cy.height()) * 0.35,
                        });
                    });
                }
                cy.layout({ name: 'preset', animate: false, fit: false }).run();
                var mappedLayout = mapLayoutName(currentLayout);
                if (mappedLayout !== 'preset') {
                    setTimeout(function () {
                        runLayout(currentLayout, pendingLayoutOptions);
                    }, 50);
                } else {
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
            n.style({
                'border-color': '#4A90E2',
                'border-width': 3
            });
        });
        cy.on('mouseout', 'node[?image]', function (evt) {
            var n = evt.target;
            if (!n) return;
            // Removing the inline overrides lets the stylesheet rule
            // (imageNodeStyle / node:selected) reassert itself.
            n.removeStyle('border-color border-width');
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
        if (others.length > 0) others.style({ 'opacity': 0.18 });
    }

    function highlightEdgeNeighborhood(cy, edge) {
        if (!cy || !edge) return;
        clearNeighborhoodHighlight(cy);
        var hood = edge.connectedNodes().union(edge);
        var others = cy.elements().difference(hood);
        if (others.length > 0) others.style({ 'opacity': 0.18 });
    }

    function clearNeighborhoodHighlight(cy) {
        if (!cy) return;
        // Reset only the inline opacity / border / line-width overrides
        // we added during highlighting. We deliberately do NOT call
        // cy.style().resetToDefault() because that would wipe the
        // node:selected / edge:selected selectors from the stylesheet,
        // which are the source of the red border highlight.
        cy.batch(function () {
            cy.elements().removeStyle('opacity border-width border-color border-style line-color target-arrow-color width');
        });
        // Re-apply the default stylesheet so the :selected selectors
        // continue to work for the next selection.
        cy.style().update();
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
            nodes.forEach(function (n) {
                if (matchedSet[n.id()]) {
                    n.style({
                        'border-width': 4,
                        'border-color': hex,
                        'border-style': 'solid'
                    });
                } else {
                    n.style({ 'opacity': 0.18 });
                }
            });
            cy.edges().forEach(function (e) {
                var sId = e.source().id();
                var tId = e.target().id();
                if (matchedSet[sId] && matchedSet[tId]) {
                    e.style({
                        'line-color': hex,
                        'target-arrow-color': hex,
                        'opacity': 1
                    });
                } else {
                    e.style({ 'opacity': 0.18 });
                }
            });
        });
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
     * Remove every inline opacity / border / line-color override we added
     * during {@link applyLegendHighlight}. The stylesheet's node:selected /
     * edge:selected rules are preserved by re-applying the stylesheet.
     */
    function clearLegendHighlight() {
        activeLegendColor = null;
        if (!cy) return;
        cy.batch(function () {
            cy.elements().removeStyle(
                'opacity border-width border-color border-style line-color target-arrow-color'
            );
        });
        cy.style().update();
    }

    /* ---- Java-callable API (window.cgv_*) ---- */

    window.cgv_setData = function () {
        log('cgv_setData called, __cgv_elements=' + (window.__cgv_elements ? window.__cgv_elements.length : 'null'));
        if (!window.__cgv_elements) {
            log('cgv_setData: no __cgv_elements yet');
            return;
        }
        applyElements(window.__cgv_elements);
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
                            // matching rule).
                            var defaults = defaultStyle();
                            var merged = defaults.concat(styles).concat([imageNodeStyle()]);
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
        // style selector).
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
            // imageNodeStyle() comes LAST so image-badge nodes still
            // render their SVG even when Leiden set background-color.
            var merged = defaults.concat(styles).concat([imageNodeStyle()]);
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
