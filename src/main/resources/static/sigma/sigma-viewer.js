/**
 * sigma-viewer.js
 *
 * Bridge between sigma.js + graphology (rendering) and the Java side via
 * BrowserFunctions (selection / context menu callbacks). The HTML is
 * embedded in the RAP page via Browser.setText, which renders the content
 * inside a same-origin iframe. The bridge therefore calls BrowserFunctions
 * via the iframe's own window object (see {@link javaCall}).
 *
 * <h2>Data delivery</h2>
 * <p>Unlike the cytoscape or vis viewers that receive their elements via
 * Java-pushed globals, the sigma viewer fetches its graph from
 * {@code /api/sigma/nodes} and {@code /api/sigma/edges}. The initial URL
 * is inlined into the iframe HTML before {@code Browser.setText(...)} so
 * the iframe can start fetching as soon as it boots — eliminating the race
 * with Java-side {@code applyData(...)}.</p>
 *
 * <p>Globals read by Java / written by JS:</p>
 * <ul>
 *   <li>(read)  window.__vg_initialNodesUrl, __vg_initialEdgesUrl — initial fetch URLs inlined by Java</li>
 * </ul>
 *
 * <p>Java-callable API (window.vg_*):</p>
 * <ul>
 *   <li>vg_viewerReady                          ()  -- after sigma is sized</li>
 *   <li>vg_notifyNodeSelected                   (id)</li>
 *   <li>vg_notifyRelationshipSelected           (id)</li>
 *   <li>vg_notifySelectionCleared               ()</li>
 *   <li>vg_requestNodeContextMenu               (id, x, y)</li>
 *   <li>vg_requestRelationshipContextMenu       (id, x, y)</li>
 *   <li>vg_invokeContextMenuAction              (entryId)</li>
 *   <li>vg_setLayout                            (algorithm)</li>
 *   <li>vg_setLayoutOptions                     (options)</li>
 *   <li>vg_applyNodeConfig                      (config)</li>
 *   <li>vg_applyLeidenColors                    (colorMap)</li>
 *   <li>vg_applyColorPalette                    (entries, enabled) -- auto-pushed by bridge</li>
 *   <li>vg_hideColorPalette                     () -- auto-pushed by clear()</li>
 *   <li>vg_clear                                ()</li>
 *   <li>vg_fitToScreen                          ()</li>
 *   <li>vg_resize                               ()</li>
 *   <li>vg_dispose                              ()</li>
 *   <li>vg_showContextMenu                      (snapshot, x, y)</li>
 *   <li>vg_hideContextMenu                      ()</li>
 *   <li>vg_loadGraph                            (nodesUrl, edgesUrl)</li>
 * </ul>
 */

(function () {
    'use strict';

    var graph = null;
    var renderer = null;
    var cyReady = false;
    // Re-entrancy guard for the apply-data path — without it a second
    // vg_loadGraph arriving mid-flight could clobber the in-flight rebuild.
    var vgSetBusy = false;
    var vgSetPending = null;
    var currentLayout = 'FORCE_DIRECTED_2_SIGMA';
    var pendingLayoutOptions = null;
    var currentNodeConfig = { showTitle: true, labelColors: {}, labelShapes: {}, tagColors: {}, globalTagColors: {} };
    var currentLeidenColors = {};
    /**
     * Engine-agnostic effective per-node color map pushed by the Java
     * {@code NodeColorResolver} via {@code vg_applyNodeColors}. Takes
     * precedence over both {@code currentNodeConfig} (label/tag colors)
     * and {@code currentLeidenColors} in {@code buildNodeReducer} so the
     * "Apply Leiden / Apply Tag Colors" buttons apply identically to
     * the dialog vs. the resolver path. Empty / null = no override.
     */
    var currentEffectiveColors = {};
    var cachedNodesEtag = null;
    var cachedEdgesEtag = null;
    var cachedNodesBody = null;
    var cachedEdgesBody = null;
    var selectedNodeId = null;
    var selectedEdgeId = null;
    var resizeObserved = false;
    // Legend state
    var legendEntries = [];
    var legendEnabled = false;
    var activeLegendColor = null;
    var legendCollapsed = false;
    var MIN_NODE_RADIUS = 7;

    /**
     * Call a BrowserFunction on the iframe's own contentWindow.
     */
    function javaCall(name) {
        try {
            var fn = window[name];
            if (typeof fn !== 'function') {
                console.warn('[VG] javaCall(' + name + '): BrowserFunction not registered');
                return undefined;
            }
            return fn.apply(window, Array.prototype.slice.call(arguments, 1));
        } catch (e) {
            console.error('[VG] javaCall(' + name + ') - error:', e);
            return undefined;
        }
    }

    /**
     * Notify Java that the viewer is ready. Mirrors the
     * {@code waitForViewerReadyWrapper} pattern in the other viewers —
     * BrowserFunctions are installed asynchronously by rap-client.js, so
     * we poll {@code window.vg_viewerReady} until the wrapper appears.
     */
    var vgReadySent = false;
    function notifyViewerReady() {
        if (vgReadySent) return;
        if (typeof window.vg_viewerReady === 'function') {
            vgReadySent = true;
            javaCall('vg_viewerReady');
        } else {
            setTimeout(notifyViewerReady, 50);
        }
    }

    function $(id) { return document.getElementById(id); }

    function log(msg) {
        try { console.log('[VG] ' + msg); } catch (e) {}
    }

    function showError(msg) {
        var el = $('vg-error');
        if (!el) return;
        el.textContent = msg;
        el.style.display = 'block';
        console.error('[VG] error:', msg);
    }

    function showDebug(msg) {
        var el = $('vg-debug');
        if (!el) return;
        el.textContent = msg;
        el.style.display = 'block';
    }

    function init() {
        if (cyReady) return;
        log('init() start');
        var container = $('sigma-container');
        if (!container) {
            log('container #sigma-container not found, retry in 50ms');
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
                setTimeout(function () {
                    if (cyReady) return;
                    if (container.clientWidth > 0 && container.clientHeight > 0) {
                        boot(container);
                        return;
                    }
                    showDebug('Container size 0×0 — sigma boot timed out');
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
        if (cyReady) return;
        cyReady = true;
        log('boot() enter');
        try {
            doBoot(container);
        } catch (ex) {
            log('boot() caught outer exception: ' + ex.message);
            showError('Sigma boot failed: ' + ex.message);
        }
    }

    function doBoot(container) {
        if (typeof graphology === 'undefined') {
            showError('graphology library not loaded — check /sigma/graphology.min.js');
            return;
        }
        graph = new graphology.Graph({ multi: true, type: 'directed', allowSelfLoops: true });
        // Fetch + apply the graph payload FIRST so layout / weight checks
        // can run even if the WebGL-backed renderer fails to construct.
        // The fetch path runs regardless of WebGL availability — only the
        // visible paint step requires WebGL.
        applyElementsAndLayout();
        try {
            renderer = createRenderer(container, graph);
        } catch (ex) {
            // WebGL path may throw on the bare 'new Sigma(...)' call when
            // the GL context cannot be allocated. createRenderer() already
            // catches its own internal failure and falls back to the
            // CanvasRenderer, but if some future factor surfaces a higher-
            // level error we still want click / hover / tooltip events to
            // work — so fall back to a CanvasRenderer rather than
            // abandoning the renderer entirely.
            console.error('[VG] renderer init failed, switching to Canvas: ' + ex.message);
            showError('Renderer init failed, using Canvas fallback: ' + ex.message);
            renderer = new CanvasRenderer(container, graph);
        }
        // attachRendererEvents must run AFTER we have a renderer and
        // BEFORE the user can interact — otherwise click / hover / tooltip
        // events fire into a no-op event emitter and the Java bridge stays
        // silent even though the viewer reports "ready".
        attachRendererEvents(renderer);
        // Once the renderer is up, re-apply the latest layout so the
        // already-computed positions are reflected on screen.
        try { renderer.refresh(); } catch (e) { /* ignore */ }
        fitToViewport();
        notifyViewerReady();
        log('renderer ready (mode=' + (renderer._isCanvas ? 'canvas' : 'webgl') + ')');
    }

    /**
     * Probe for WebGL availability. Returns true when the runtime can
     * allocate a WebGL1 / WebGL2 context on a freshly-created canvas —
     * the only signal sigma v2.x cares about.
     */
    function hasWebGL() {
        try {
            var c = document.createElement('canvas');
            return !!(window.WebGLRenderingContext
                && (c.getContext('webgl2') || c.getContext('webgl') || c.getContext('experimental-webgl')));
        } catch (e) {
            return false;
        }
    }

    /**
     * Factory: pick the best renderer for the runtime.
     *
     * <p>Order of preference:
     * <ol>
     *   <li>If the Sigma bundle loaded AND WebGL is available — use
     *       {@code new Sigma(graph, container, settings)} (production path).</li>
     *   <li>Otherwise — instantiate the {@link CanvasRenderer} (a small
     *       Canvas-2D implementation that honours the same
     *       {@code refresh / getCamera().fit / setSetting / on} API surface).
     *       This path runs in WebGL-disabled chromium, headless test
     *       harnesses, and low-end devices where sigma would otherwise
     *       throw on its first paint.</li>
     * </ol>
     */
    function createRenderer(container, graph) {
        if (typeof Sigma !== 'undefined' && hasWebGL()) {
            try {
                var sigmaInstance = new Sigma(graph, container, defaultSigmaSettings());
                log('createRenderer: using Sigma WebGL');
                return sigmaInstance;
            } catch (e) {
                log('Sigma WebGL renderer failed to initialise, falling back to Canvas: ' + e.message);
            }
        } else {
            if (typeof Sigma === 'undefined') log('createRenderer: Sigma not loaded, using Canvas fallback');
            else if (!hasWebGL()) log('createRenderer: WebGL not available, using Canvas fallback');
        }
        return new CanvasRenderer(container, graph);
    }

    /**
     * Minimal Canvas-2D renderer that mirrors the sigma.js v2.x API
     * surface used by sigma-viewer.js — {@code refresh}, {@code getCamera().fit},
     * {@code setSetting}, {@code on(event, fn)} / {@code emit(event, payload)}.
     *
     * <p>Layout, weight-driven attraction and the no-overlap post-processing
     * are renderer-agnostic — they operate on the graphology graph's
     * {@code x}/{@code y} attributes — so the Canvas path produces a
     * faithful visual representation of the same layout the WebGL path
     * would draw.</p>
     *
     * <p>The renderer reads positions from each node's {@code x}/{@code y}
     * attribute (set by graphology-layout-* or random seeding) and maps
     * them onto the canvas via a per-frame {@link CanvasRenderer#_computeProjection}
     * step: bbox of all node positions, scale so the bbox fits within
     * {@code (canvasW - 2*padding) × (canvasH - 2*padding)}, origin at
     * the top-left of the bbox with padding on both axes.</p>
     */
    function CanvasRenderer(container, graph) {
        this.container = container;
        this.graph = graph;
        this._isCanvas = true;
        this.settings = {
            labelColor: '#222',
            labelSize: 12,
            labelFont: 'Segoe UI, Arial, sans-serif',
            edgeLabelColor: '#444',
            edgeLabelSize: 10,
            defaultDrawNodes: 'circle',
            defaultDrawEdges: 'arrow'
        };
        this._listeners = {};
        this._dirty = true;
        this._projection = null;
        this.selectedNodeId = null;
        this.selectedEdgeId = null;
        this.hoveredNodeId = null;
        this.hoveredEdgeId = null;
        // Camera state — Pan-Offset und Zoom-Ratio. ratio<1 ⇒ gezoomt rein,
        // ratio>1 ⇒ gezoomt raus. Wird durch Wheel/Pan mutiert und durch
        // fitCamera() zurückgesetzt.
        this._camera = { x: 0, y: 0, ratio: 1 };
        // Pan-Tracking: _isPanning wird beim mousedown gesetzt und beim
        // mouseup/mouseleave gelöscht. _didDrag unterscheidet Click von
        // Pan: erst nach > 3 px Mausbewegung wird _didDrag=true und ein
        // folgender Click-Event vom DOM unterdrückt.
        this._isPanning = false;
        this._didDrag = false;
        this._panAnchorX = 0;
        this._panAnchorY = 0;
        this._panStartX = 0;
        this._panStartY = 0;
        this._PAN_DRAG_THRESHOLD = 3;
        // Build canvas + 2D context.
        var dpr = window.devicePixelRatio || 1;
        // Defensive: a previous Sigma constructor may have appended a canvas
        // (e.g. half-initialised before throwing) which would intercept our
        // mouse events. Remove any pre-existing canvas children so the new
        // CanvasRenderer canvas is the only one that receives events.
        while (container.firstChild && container.firstChild.tagName === 'CANVAS') {
            container.removeChild(container.firstChild);
        }
        this.canvas = document.createElement('canvas');
        this.canvas.style.position = 'absolute';
        this.canvas.style.top = '0';
        this.canvas.style.left = '0';
        this.canvas.style.width = '100%';
        this.canvas.style.height = '100%';
        this.canvas.style.display = 'block';
        // Explicit pointer-events so no parent rule can ever swallow mouse
        // events on the canvas — without this, an inherited 'none' from the
        // container would freeze pan/zoom/click/hover even though the listener
        // attachment succeeded.
        this.canvas.style.pointerEvents = 'auto';
        // High z-index so the canvas sits above any orphaned sibling the
        // sigma WebGL init may have left behind during a failed construction.
        this.canvas.style.zIndex = '1';
        container.appendChild(this.canvas);
        this.ctx = this.canvas.getContext('2d');
        this.dpr = dpr;
        this.width = 0;
        this.height = 0;
        this._onClick = this._onClick.bind(this);
        this._onContextMenu = this._onContextMenu.bind(this);
        this._onMouseMove = this._onMouseMove.bind(this);
        this._onMouseDown = this._onMouseDown.bind(this);
        this._onMouseUp = this._onMouseUp.bind(this);
        this._onWheel = this._onWheel.bind(this);
        this._onMouseLeave = this._onMouseLeave.bind(this);
        this.canvas.addEventListener('click', this._onClick);
        this.canvas.addEventListener('contextmenu', this._onContextMenu);
        this.canvas.addEventListener('mousemove', this._onMouseMove);
        this.canvas.addEventListener('mousedown', this._onMouseDown);
        this.canvas.addEventListener('mouseup', this._onMouseUp);
        this.canvas.addEventListener('wheel', this._onWheel, { passive: false });
        this.canvas.addEventListener('mouseleave', this._onMouseLeave);
        this.canvas.style.cursor = 'grab';
        this.resize();
        // Camera stub — exposes the same `fit({padding})` API the rest of
        // the viewer calls via `renderer.getCamera().fit({padding: 30})`.
        var self = this;
        this.camera = {
            fit: function (opts) { self.fitCamera(opts); }
        };
    }

    CanvasRenderer.prototype.on = function (event, fn) {
        (this._listeners[event] = this._listeners[event] || []).push(fn);
    };
    CanvasRenderer.prototype.emit = function (event, payload) {
        var arr = this._listeners[event];
        if (!arr) return;
        for (var i = 0; i < arr.length; i++) {
            try { arr[i](payload); } catch (e) { console.warn('[VG] listener error', event, e); }
        }
    };
    CanvasRenderer.prototype.getCamera = function () {
        return this.camera;
    };
    CanvasRenderer.prototype.setSetting = function (key, val) {
        this.settings[key] = val;
        this._dirty = true;
    };
    CanvasRenderer.prototype.resize = function () {
        var rect = this.container.getBoundingClientRect();
        var w = Math.max(1, Math.floor(rect.width));
        var h = Math.max(1, Math.floor(rect.height));
        // Defensive: if the container is 0×0 (e.g. parent layout not yet
        // settled, or hidden tab) fall back to window dimensions so the
        // canvas still has a usable backing store. Otherwise the canvas
        // stays 0×0 forever and mouse events on the empty backing store
        // never reach the renderer.
        if (w === 1 && h === 1 && this.container.clientWidth > 0 && this.container.clientHeight > 0) {
            w = Math.max(1, Math.floor(this.container.clientWidth));
            h = Math.max(1, Math.floor(this.container.clientHeight));
        }
        if (w === 1 && h === 1) {
            w = Math.max(w, Math.floor(window.innerWidth || 800));
            h = Math.max(h, Math.floor(window.innerHeight || 600));
        }
        this.width = w;
        this.height = h;
        this.canvas.width = w * this.dpr;
        this.canvas.height = h * this.dpr;
        this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        this._dirty = true;
    };
    CanvasRenderer.prototype.refresh = function () {
        if (this.width === 0 || this.height === 0) this.resize();
        // Always recompute the projection on refresh — the bbox changes
        // whenever the graph topology changes (e.g. setGraphData clears
        // and repopulates, or a layout run updates every node's x/y),
        // and the dirty flag from the constructor fires once before the
        // graph is populated. Treating refresh() as "always recompute"
        // keeps the projection in sync with the current graph state.
        this._dirty = true;
        this._computeProjection();
        this._render();
    };
    CanvasRenderer.prototype.fitCamera = function (opts) {
        // Equivalent to sigma.js's camera.fit({padding: N}) — recompute
        // the projection so the graph bbox fills the canvas with the
        // requested padding on every side. Also resets the user-driven
        // Pan/Zoom so fit() returns to the canonical "see everything"
        // view (sigma's camera.fit() does the same).
        this._padding = (opts && typeof opts.padding === 'number') ? opts.padding : 30;
        this._camera.x = 0;
        this._camera.y = 0;
        this._camera.ratio = 1;
        this._dirty = true;
        this._computeProjection();
        this._render();
    };

    /**
     * Compute the bbox of every node position once per layout change and
     * store a projection function that maps graph coordinates to canvas
     * pixels. The Y axis is flipped because graphology positions are
     * bottom-up (y=0 at bottom) while canvas-2d is top-down.
     */
    CanvasRenderer.prototype._computeProjection = function () {
        var g = this.graph;
        if (!g || g.order === 0) {
            // Empty graph — use a default 1×1 bbox so the canvas clears
            // without throwing. The next refresh after data lands will
            // recompute against the populated graph.
            this._projection = null;
            this._dirty = false;
            return;
        }
        var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        var nodeCount = 0;
        var positionedCount = 0;
        g.forEachNode(function (n) {
            nodeCount++;
            var x = g.getNodeAttribute(n, 'x');
            var y = g.getNodeAttribute(n, 'y');
            if (typeof x === 'number' && typeof y === 'number') {
                positionedCount++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        });
        if (positionedCount === 0) {
            // No positioned nodes — bail out without a projection so
            // _render clears the canvas instead of drawing at NaN.
            this._projection = null;
            this._dirty = false;
            return;
        }
        if (minX === Infinity) { minX = 0; maxX = 1; minY = 0; maxY = 1; }
        var pad = (typeof this._padding === 'number') ? this._padding : 30;
        var w = Math.max(1, this.width - 2 * pad);
        var h = Math.max(1, this.height - 2 * pad);
        var spreadX = Math.max(0.001, maxX - minX);
        var spreadY = Math.max(0.001, maxY - minY);
        var baseScale = Math.min(w / spreadX, h / spreadY);
        // ratio < 1 ⇒ zoomed in (bigger canvas pixels per graph unit),
        // ratio > 1 ⇒ zoomed out. Clamped defensively in case _camera
        // got a stale value via setSetting or future mutation.
        var ratio = (typeof this._camera.ratio === 'number' && this._camera.ratio > 0)
                ? this._camera.ratio : 1;
        var scale = baseScale / ratio;
        // Pan-Offset is added to the bbox origin so the entire viewport
        // (and therefore every rendered node) shifts by (camera.x, camera.y)
        // canvas pixels — positive x/y moves content right/down.
        var camX = (typeof this._camera.x === 'number') ? this._camera.x : 0;
        var camY = (typeof this._camera.y === 'number') ? this._camera.y : 0;
        var offsetX = pad + (w - spreadX * scale) / 2 + camX;
        var offsetY = pad + (h - spreadY * scale) / 2 + camY;
        var self = this;
        this._projection = {
            toX: function (x) { return offsetX + (x - minX) * scale; },
            toY: function (y) { return offsetY + (spreadY - (y - minY)) * scale; },
            inverseX: function (px) { return minX + (px - offsetX) / scale; },
            // Inverse of toY(y) = offsetY + (spreadY - (y - minY)) * scale
            // is y = minY + spreadY - (py - offsetY) / scale. The previous
            // version was minY + (spreadY - (py - offsetY)) / scale which
            // — because of operator precedence — evaluated as
            // minY + (spreadY - py + offsetY) / scale, producing graph-y
            // values wildly outside [minY, maxY] for any click below the
            // top half of the canvas. The hit test then returned null for
            // every node click, so clickNode / clickEdge / clickStage never
            // fired and the Java bridge stayed silent.
            inverseY: function (py) { return minY + spreadY - (py - offsetY) / scale; },
            scale: scale
        };
        this._dirty = false;
    };

    CanvasRenderer.prototype._nodeAttrs = function (node, data) {
        var reducer = this.settings.nodeReducer;
        var attrs = data ? Object.assign({}, data) : {};
        if (typeof reducer === 'function') {
            try { attrs = reducer(node, attrs) || attrs; } catch (e) {}
        }
        attrs.size = (typeof attrs.size === 'number') ? attrs.size : 8;
        attrs.color = attrs.color || '#4A90E2';
        attrs.label = (this.settings.showTitle === false) ? '' : (attrs.label || '');
        attrs.opacity = (typeof attrs.opacity === 'number') ? attrs.opacity : 1;
        return attrs;
    };

    CanvasRenderer.prototype._edgeAttrs = function (edge, data) {
        var reducer = this.settings.edgeReducer;
        var attrs = data ? Object.assign({}, data) : {};
        if (typeof reducer === 'function') {
            try { attrs = reducer(edge, attrs) || attrs; } catch (e) {}
        }
        attrs.size = (typeof attrs.size === 'number') ? attrs.size : 1.2;
        attrs.color = attrs.color || '#888';
        attrs.label = attrs.label || '';
        return attrs;
    };

    CanvasRenderer.prototype._render = function () {
        var ctx = this.ctx;
        var p = this._projection;
        if (!p) {
            // No projection yet (empty graph) — leave the canvas cleared.
            ctx.clearRect(0, 0, this.width, this.height);
            return;
        }
        ctx.clearRect(0, 0, this.width, this.height);
        var self = this;
        // Edges first so node circles draw on top.
        this.graph.forEachEdge(function (edge) {
            var attrs = self._edgeAttrs(edge, self.graph.getEdgeAttributes(edge));
            var src = self.graph.getNodeAttributes(self.graph.source(edge));
            var tgt = self.graph.getNodeAttributes(self.graph.target(edge));
            // Edges carry no x/y of their own — only the source/target
            // nodes do. Skip only when an endpoint is unpositioned. A
            // previous version consulted the edge's own attribute x,
            // which is always undefined, and silently dropped every edge.
            if (typeof src.x !== 'number' || typeof tgt.x !== 'number') return;
            var x1 = p.toX(src.x), y1 = p.toY(src.y);
            var x2 = p.toX(tgt.x), y2 = p.toY(tgt.y);
            var isHighlighted = edge === self.selectedEdgeId || edge === self.hoveredEdgeId;
            // Edge-source-half-triangle 'arrow': the headsize scales with
            // the edge weight so heavy edges have chunkier pointers —
            // matches the visual emphasis of Cytoscape's 'triangle'
            // target-arrow on heavy edges.
            var dx = x2 - x1, dy = y2 - y1;
            var len = Math.sqrt(dx * dx + dy * dy);
            var tipX = x2, tipY = y2;
            var ux = 1, uy = 0;
            if (len > 0) {
                ux = dx / len;
                uy = dy / len;
            }
            var baseX = len > 0 ? tipX - ux * Math.max(10, attrs.size * 5) : x2;
            var baseY = len > 0 ? tipY - uy * Math.max(10, attrs.size * 5) : y2;
            var perpX = -uy, perpY = ux;
            // Pick the rendered edge colour — highlight overrides the
            // cluster colour so the user can still see which edge they
            // hovered over the palette's colour wash.
            var lineColor = isHighlighted ? '#E74C3C' : attrs.color;
            ctx.strokeStyle = lineColor;
            ctx.globalAlpha = isHighlighted ? 1 : 0.7;
            ctx.lineWidth = isHighlighted ? Math.max(2, attrs.size + 1.5) : Math.max(0.5, attrs.size);
            ctx.beginPath();
            ctx.moveTo(x1, y1);
            ctx.lineTo(tipX, tipY);
            ctx.stroke();
            // Arrowhead — fill as a closed triangle in the same colour.
            // Minimum size bumped from 6→10 so the arrowhead is clearly
            // visible on small edges (the old 6px triangle disappeared into
            // the line width on weight-1 relationships).
            if (len > 0) {
                ctx.globalAlpha = isHighlighted ? 1 : 0.85;
                ctx.fillStyle = lineColor;
                var headLen = Math.max(10, attrs.size * 5);
                var headHalf = headLen * 0.5;
                ctx.beginPath();
                ctx.moveTo(tipX, tipY);
                ctx.lineTo(baseX + perpX * headHalf,
                           baseY + perpY * headHalf);
                ctx.lineTo(baseX - perpX * headHalf,
                           baseY - perpY * headHalf);
                ctx.closePath();
                ctx.fill();
            }
            // Edge-label rendering — disabled when renderEdgeLabels is
            // explicitly false (mirrors sigma's WebGL setting). The
            // weight value lives on attrs.label, set by
            // GraphRelationship.toGraphologyEdge.
            if (attrs.label && self.settings.renderEdgeLabels !== false) {
                var midX = (x1 + x2) / 2, midY = (y1 + y2) / 2;
                var labelSize = self.settings.edgeLabelSize || 10;
                var labelFont = self.settings.labelFont || 'sans-serif';
                var labelText = String(attrs.label);
                ctx.font = labelSize + 'px ' + labelFont;
                var metrics = ctx.measureText(labelText);
                var pad = 3;
                var w = metrics.width + pad * 2;
                var h = labelSize + 2;
                ctx.fillStyle = '#ffffff';
                ctx.globalAlpha = 0.85;
                ctx.fillRect(midX - w / 2, midY - h / 2, w, h);
                ctx.strokeStyle = '#cccccc';
                ctx.lineWidth = 0.5;
                ctx.strokeRect(midX - w / 2, midY - h / 2, w, h);
                ctx.globalAlpha = 1;
                ctx.fillStyle = self.settings.edgeLabelColor || '#444';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(labelText, midX, midY);
            }
            ctx.globalAlpha = 1;
        });
        // Nodes
        this.graph.forEachNode(function (node) {
            var attrs = self._nodeAttrs(node, self.graph.getNodeAttributes(node));
            if (typeof attrs.x !== 'number' || typeof attrs.y !== 'number') return;
            var x = p.toX(attrs.x), y = p.toY(attrs.y);
            var r = Math.max(MIN_NODE_RADIUS, attrs.size);
            ctx.globalAlpha = attrs.opacity;
            ctx.fillStyle = attrs.color;
            ctx.beginPath();
            ctx.arc(x, y, r, 0, Math.PI * 2);
            ctx.fill();
            if (node === self.selectedNodeId || node === self.hoveredNodeId) {
                ctx.lineWidth = 3;
                ctx.strokeStyle = node === self.selectedNodeId ? '#E74C3C' : '#4A90E2';
                ctx.stroke();
            }
            ctx.globalAlpha = 1;
            if (attrs.label && self.settings.labelSize) {
                ctx.fillStyle = self.settings.labelColor || '#222';
                ctx.font = (self.settings.labelSize || 12) + 'px ' + (self.settings.labelFont || 'sans-serif');
                ctx.textAlign = 'center';
                ctx.textBaseline = 'top';
                ctx.fillText(attrs.label, x, y + r + 2);
            }
        });
    };

    CanvasRenderer.prototype._hitTest = function (mx, my) {
        if (!this._projection) return null;
        var p = this._projection;
        var gx = p.inverseX(mx), gy = p.inverseY(my);
        var self = this;
        var g = this.graph;
        var bestNode = null, bestNodeDist = Infinity;
        var bestEdge = null, bestEdgeDist = Infinity;
        g.forEachNode(function (n) {
            var attrs = self._nodeAttrs(n, g.getNodeAttributes(n));
            var dx = attrs.x - gx, dy = attrs.y - gy;
            var d = Math.sqrt(dx * dx + dy * dy);
            // threshold: node.radius in graph units ~ node.size / projection.scale.
            // Bumped the +2 padding to +6 so the hit window survives typical
            // sub-pixel click offsets — without this the 16-pixel nodes in a
            // graph spanning ~120 graph units only register hits within an
            // 18-pixel screen radius, which is tight on retina / zoomed views.
            var hitSize = Math.max(MIN_NODE_RADIUS, attrs.size);
            var thresh = (hitSize + 6) / p.scale;
            if (d < thresh && d < bestNodeDist) {
                bestNode = n;
                bestNodeDist = d;
            }
        });
        // Distance from point to segment in graph coords
        g.forEachEdge(function (e) {
            var src = g.getNodeAttributes(g.source(e));
            var tgt = g.getNodeAttributes(g.target(e));
            var d = pointSegmentDistance(gx, gy, src.x, src.y, tgt.x, tgt.y);
            var thresh = (self._edgeAttrs(e, g.getEdgeAttributes(e)).size + 4) / p.scale;
            if (d < thresh && d < bestEdgeDist) {
                bestEdge = e;
                bestEdgeDist = d;
            }
        });
        return bestNode ? { node: bestNode } : (bestEdge ? { edge: bestEdge } : null);
    };

    function pointSegmentDistance(px, py, x1, y1, x2, y2) {
        var dx = x2 - x1, dy = y2 - y1;
        var len2 = dx * dx + dy * dy;
        if (len2 === 0) {
            var ex = px - x1, ey = py - y1;
            return Math.sqrt(ex * ex + ey * ey);
        }
        var t = ((px - x1) * dx + (py - y1) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        var cx = x1 + t * dx, cy = y1 + t * dy;
        var ex = px - cx, ey = py - cy;
        return Math.sqrt(ex * ex + ey * ey);
    }

    CanvasRenderer.prototype._onClick = function (evt) {
        var rect = this.canvas.getBoundingClientRect();
        var mx = evt.clientX - rect.left;
        var my = evt.clientY - rect.top;
        var hit = this._hitTest(mx, my);
        if (hit && hit.node) {
            this.selectedNodeId = hit.node;
            this.selectedEdgeId = null;
            this._render();
            this.emit('clickNode', { node: hit.node, event: evt });
        } else if (hit && hit.edge) {
            this.selectedEdgeId = hit.edge;
            this.selectedNodeId = null;
            this._render();
            this.emit('clickEdge', { edge: hit.edge, event: evt });
        } else {
            if (this.selectedNodeId == null && this.selectedEdgeId == null) return;
            this.selectedNodeId = null;
            this.selectedEdgeId = null;
            this._render();
            this.emit('clickStage', { event: evt });
        }
    };

    CanvasRenderer.prototype._onContextMenu = function (evt) {
        evt.preventDefault();
        var rect = this.canvas.getBoundingClientRect();
        var mx = evt.clientX - rect.left;
        var my = evt.clientY - rect.top;
        var hit = this._hitTest(mx, my);
        if (hit && hit.node) {
            this.emit('rightClickNode', { node: hit.node, event: evt });
        } else if (hit && hit.edge) {
            this.emit('rightClickEdge', { edge: hit.edge, event: evt });
        }
    };

    CanvasRenderer.prototype._onMouseMove = function (evt) {
        var rect = this.canvas.getBoundingClientRect();
        var mx = evt.clientX - rect.left;
        var my = evt.clientY - rect.top;

        // Pan-Branch: wenn mousedown aktiv ist, Mausbewegung als Pan
        // interpretieren. Erst ab > _PAN_DRAG_THRESHOLD Pixel Bewegung
        // wird _didDrag gesetzt — ein normaler Click (kein Drag) erreicht
        // so weiterhin den DOM click-Handler, ein Drag stoppt ihn.
        if (this._isPanning) {
            var dx = mx - this._panAnchorX;
            var dy = my - this._panAnchorY;
            if (!this._didDrag) {
                if (Math.abs(dx) + Math.abs(dy) > this._PAN_DRAG_THRESHOLD) {
                    this._didDrag = true;
                }
            }
            if (this._didDrag) {
                this._camera.x = this._panStartX + dx;
                this._camera.y = this._panStartY + dy;
                this._dirty = true;
                this._render();
                this.canvas.style.cursor = 'grabbing';
                return;
            }
            // noch unter Threshold — noch potentiell ein Click
            this.canvas.style.cursor = 'grabbing';
            return;
        }

        var hit = this._hitTest(mx, my);
        var prevNode = this.hoveredNodeId;
        var prevEdge = this.hoveredEdgeId;
        this.hoveredNodeId = hit ? (hit.node || null) : null;
        this.hoveredEdgeId = hit ? (hit.edge || null) : null;
        if (this.hoveredNodeId !== prevNode) {
            if (prevNode) this.emit('leaveNode', { node: prevNode, event: evt });
            if (this.hoveredNodeId) {
                this._render();
                this.emit('enterNode', { node: this.hoveredNodeId, event: evt });
            }
        }
        if (this.hoveredEdgeId !== prevEdge) {
            if (prevEdge) this.emit('leaveEdge', { edge: prevEdge, event: evt });
            if (this.hoveredEdgeId) {
                this._render();
                this.emit('enterEdge', { edge: this.hoveredEdgeId, event: evt });
            }
        }
        if (!hit) {
            // leerer Bereich: signalisiere Pan-Möglichkeit mit 'grab'
            this.canvas.style.cursor = 'grab';
        } else {
            this.canvas.style.cursor = hit.node ? 'pointer' : 'crosshair';
        }
    };

    CanvasRenderer.prototype._onMouseDown = function (evt) {
        if (evt.button !== 0) return; // nur linke Maustaste
        var rect = this.canvas.getBoundingClientRect();
        this._isPanning = true;
        this._didDrag = false;
        this._panAnchorX = evt.clientX - rect.left;
        this._panAnchorY = evt.clientY - rect.top;
        this._panStartX = this._camera.x;
        this._panStartY = this._camera.y;
        this.canvas.style.cursor = 'grabbing';
        // preventDefault verhindert Text-Selektion beim Drag
        try { evt.preventDefault(); } catch (e) {}
    };

    CanvasRenderer.prototype._onMouseUp = function (evt) {
        if (!this._isPanning) return;
        this._isPanning = false;
        var wasDrag = this._didDrag;
        this._didDrag = false;
        this.canvas.style.cursor = 'grab';
        if (wasDrag) {
            // Drag war ein Pan — DOM 'click'-Event wird vom Browser
            // unterdrückt, also nichts weiter zu tun.
            try { evt.preventDefault(); } catch (e) {}
        }
    };

    CanvasRenderer.prototype._onWheel = function (evt) {
        // preventDefault, damit die Seite nicht selbst scrollt
        evt.preventDefault();
        if (!this._projection) return;
        var zoomingRatio = (this.settings && typeof this.settings.zoomingRatio === 'number')
                ? this.settings.zoomingRatio : 1.2;
        var minRatio = (this.settings && typeof this.settings.minCameraRatio === 'number')
                ? this.settings.minCameraRatio : 0.1;
        var maxRatio = (this.settings && typeof this.settings.maxCameraRatio === 'number')
                ? this.settings.maxCameraRatio : 5;
        var rect = this.canvas.getBoundingClientRect();
        var mx = evt.clientX - rect.left;
        var my = evt.clientY - rect.top;
        // Graph-Koordinate unter dem Cursor VOR dem Zoom berechnen —
        // nach dem Zoom muss diese Koordinate weiterhin unter der Maus
        // liegen, damit das Zoomen am Cursor "verankert" wirkt.
        var p = this._projection;
        var gxBefore = p.inverseX(mx);
        var gyBefore = p.inverseY(my);
        // deltaY > 0 (Scrollrad runter / Touchpad raus) ⇒ zoom raus
        var factor = Math.pow(zoomingRatio, -evt.deltaY / 100);
        var oldRatio = this._camera.ratio;
        var newRatio = oldRatio / factor;
        if (newRatio < minRatio) newRatio = minRatio;
        if (newRatio > maxRatio) newRatio = maxRatio;
        if (newRatio === oldRatio) return;
        this._camera.ratio = newRatio;
        // Projektion neu berechnen, dann Kamera-Offset so verschieben,
        // dass (gxBefore, gyBefore) weiterhin auf (mx, my) abgebildet wird.
        this._dirty = true;
        this._computeProjection();
        var p2 = this._projection;
        var xAfter = p2.toX(gxBefore);
        var yAfter = p2.toY(gyBefore);
        this._camera.x += (mx - xAfter);
        this._camera.y += (my - yAfter);
        this._dirty = true;
        this._computeProjection();
        this._render();
    };

    CanvasRenderer.prototype._onMouseLeave = function (evt) {
        var wasPanning = this._isPanning;
        this._isPanning = false;
        this._didDrag = false;
        // Hover-State aufräumen, sonst bleibt der Tooltip / Highlight
        // hängen, wenn der User mit der Maus aus dem Canvas fährt.
        var prevNode = this.hoveredNodeId;
        var prevEdge = this.hoveredEdgeId;
        this.hoveredNodeId = null;
        this.hoveredEdgeId = null;
        if (prevNode) this.emit('leaveNode', { node: prevNode, event: evt });
        if (prevEdge) this.emit('leaveEdge', { edge: prevEdge, event: evt });
        this.canvas.style.cursor = 'grab';
        if (wasPanning) {
            this._render();
        } else if (prevNode || prevEdge) {
            this._render();
        }
    };

    CanvasRenderer.prototype.kill = function () {
        this.canvas.removeEventListener('click', this._onClick);
        this.canvas.removeEventListener('contextmenu', this._onContextMenu);
        this.canvas.removeEventListener('mousemove', this._onMouseMove);
        this.canvas.removeEventListener('mousedown', this._onMouseDown);
        this.canvas.removeEventListener('mouseup', this._onMouseUp);
        this.canvas.removeEventListener('wheel', this._onWheel);
        this.canvas.removeEventListener('mouseleave', this._onMouseLeave);
        if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
        this._listeners = {};
    };

function defaultSigmaSettings() {
        return {
            labelColor: '#222',
            labelSize: 12,
            labelDensity: 0.07,
            labelGridCellSize: 60,
            labelRenderedSizeThreshold: 6,
            labelFont: 'Segoe UI, Arial, sans-serif',
            edgeLabelColor: '#444',
            edgeLabelSize: 10,
            zoomingRatio: 1.2,
            minCameraRatio: 0.1,
            maxCameraRatio: 5,
            defaultDrawNodes: 'circle',
            defaultDrawEdges: 'arrow',
            renderEdgeLabels: true,
            // Edge interaction toggles — sigma.js v2.x defaults BOTH to
            // false, which means the renderer never fires clickEdge /
            // enterEdge / rightClickEdge (it short-circuits to
            // "…Stage"). That would silently swallow every relationship
            // selection and every edge tooltip. Force them on so the
            // Java-bridge wiring (RelationshipSelectionListener +
            // enterEdge tooltip) actually receives events.
            enableEdgeClickEvents: true,
            enableEdgeHoverEvents: true,
            enableEdgeWheelEvents: true,
            // Node interaction toggles — sigma.js v2.x defaults
            // enableNodeHoverEvents to false, which means the renderer
            // never fires enterNode / leaveNode and the Node-Tooltip
            // (vg_tooltipBody) never reaches the DOM. Force hover on so
            // the tooltips actually appear. enableNodeClickEvents is
            // true by default, but we set it explicitly so a future
            // sigma default change cannot silently break the
            // NodeSelectionListener wiring.
            enableNodeClickEvents: true,
            enableNodeHoverEvents: true,
            enableNodeWheelEvents: true,
            // Edge / node reducers are added via applyNodeConfig(...) when
            // we know the user-config; before that we render plain circles.
            nodeReducer: function (node, data) {
                return Object.assign({}, data, {
                    size: data.size || 8,
                    color: data.color || '#4A90E2',
                    label: currentNodeConfig.showTitle === false ? '' : data.label
                });
            },
            edgeReducer: function (edge, data) {
                // Drop the graphology-side "type" attribute when it is NOT
                // a sigma.js v2.x-recognized edge-program key. Sigma only
                // ships render programs for "arrow" and "line"; any other
                // value (e.g. the relationship type "REL" emitted by
                // GraphRelationship.toGraphologyEdge) makes the next
                // refresh throw "Cannot read properties of undefined
                // (reading 'process')" from edgePrograms[edge.type].
                // Removing unknown types lets sigma fall back to its
                // settings.defaultDrawEdges ('arrow') automatically.
                var out = Object.assign({}, data);
                if (out.type && out.type !== 'arrow' && out.type !== 'line') {
                    delete out.type;
                }
                // Always pin the type to a sigma-recognised program key.
                // Without this explicit fallback, sigma falls back to
                // settings.defaultDrawEdges — which works today but is one
                // default-change away from silently rendering every edge as
                // a straight line. Setting it explicitly here makes the
                // WebGL arrow rendering defensive against future sigma
                // changes.
                if (!out.type) out.type = 'arrow';
                out.size = out.size || 1.2;
                out.color = out.color || '#888';
                out.label = out.label || '';
                return out;
            }
        };
    }

    function attachRendererEvents(r) {
        r.on('clickNode', function (payload) {
            var n = payload && payload.node;
            if (!n) return;
            var id = n.id || n;
            selectedNodeId = id;
            selectedEdgeId = null;
            try { r.refresh(); } catch (e) {}
            javaCall('vg_notifyNodeSelected', id);
        });
        r.on('clickEdge', function (payload) {
            var e = payload && payload.edge;
            if (!e) return;
            var id = e.id || e;
            selectedEdgeId = id;
            selectedNodeId = null;
            try { r.refresh(); } catch (e) {}
            javaCall('vg_notifyRelationshipSelected', id);
        });
        r.on('clickStage', function () {
            if (selectedNodeId == null && selectedEdgeId == null) return;
            selectedNodeId = null;
            selectedEdgeId = null;
            try { r.refresh(); } catch (e) {}
            javaCall('vg_notifySelectionCleared');
        });
        r.on('rightClickNode', function (payload) {
            var n = payload && payload.node;
            if (!n) return;
            var id = n.id || n;
            var ev = payload.event && payload.event.originalEvent
                    ? payload.event.originalEvent : payload.event;
            javaCall('vg_requestNodeContextMenu', id, ev && ev.clientX ? ev.clientX : 0, ev && ev.clientY ? ev.clientY : 0);
        });
        r.on('rightClickEdge', function (payload) {
            var e = payload && payload.edge;
            if (!e) return;
            var id = e.id || e;
            var ev = payload.event && payload.event.originalEvent
                    ? payload.event.originalEvent : payload.event;
            javaCall('vg_requestRelationshipContextMenu', id, ev && ev.clientX ? ev.clientX : 0, ev && ev.clientY ? ev.clientY : 0);
        });
        // Tooltips
        r.on('enterNode', function (payload) {
            var n = payload && payload.node;
            if (!n) return;
            var id = n.id || n;
            var node = graph.getNodeAttributes(id);
            showTooltipFor('node', id, node, payload.event);
        });
        r.on('leaveNode', function () { hideTooltip(); });
        r.on('enterEdge', function (payload) {
            var e = payload && payload.edge;
            if (!e) return;
            var id = e.id || e;
            var edge = graph.getEdgeAttributes(id);
            showTooltipFor('edge', id, edge, payload.event);
        });
        r.on('leaveEdge', function () { hideTooltip(); });
    }

    function showTooltipFor(kind, id, attrs, evt) {
        if (!attrs) return;
        var html = buildTooltipHtml(kind, id, attrs);
        if (!html) return;
        var pos = { x: 12, y: 12 };
        if (evt) {
            if (typeof evt.clientX === 'number') { pos.x = evt.clientX; pos.y = evt.clientY; }
            else if (evt.originalEvent && typeof evt.originalEvent.clientX === 'number') {
                pos.x = evt.originalEvent.clientX;
                pos.y = evt.originalEvent.clientY;
            }
        }
        showFloatingTooltip(html, pos);
    }

    function buildTooltipHtml(kind, id, attrs) {
        if (!attrs) return null;
        var body = attrs.tooltip || '';
        if (!body && (!attrs.tooltipHeader || kind === 'node')) return null;
        if (kind === 'edge') {
            var headerText = attrs.tooltipHeader || id;
            var header = '<div class="vg-tt-header">' + escapeHtml(headerText) + '</div>';
            return header + '<div class="vg-tt-body">' + body + '</div>';
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
        var tip = $('vg-tooltip');
        if (!tip) return;
        tip.innerHTML = html;
        var header = tip.querySelector('.vg-tt-header');
        if (header) {
            header.style.cssText = 'padding:6px 10px;font-weight:600;background:#f4f4f4;border-bottom:1px solid #ccc;';
        }
        var body = tip.querySelector('.vg-tt-body');
        if (body) {
            body.style.cssText = 'padding:6px 10px;';
        }
        var container = $('sigma-container');
        var rect = container ? container.getBoundingClientRect() : { left: 0, top: 0 };
        // #vg-tooltip sitzt als position:absolute direkt im Body, daher
        // ist style.left/top Viewport-relativ — pos.x/y sind bereits
        // evt.clientX/Y (Viewport). Ein zusätzliches rect.left würde den
        // Container-Offset doppelt zählen und den Tooltip weit rechts
        // vom Cursor rendern.
        tip.style.left = Math.round(pos.x + 14) + 'px';
        tip.style.top = Math.round(pos.y - 10) + 'px';
        tip.style.display = 'block';
    }

    function hideTooltip() {
        var tip = $('vg-tooltip');
        if (tip) tip.style.display = 'none';
    }

    /* ---- Fetch + apply ---- */

    function vg_loadGraph(nodesUrl, edgesUrl) {
        log('vg_loadGraph: ' + nodesUrl + ', ' + edgesUrl);
        if (nodesUrl && typeof nodesUrl === 'string') window.__vg_initialNodesUrl = nodesUrl;
        if (edgesUrl && typeof edgesUrl === 'string') window.__vg_initialEdgesUrl = edgesUrl;
        if (!cyReady || !renderer) return; // boot() picks them up on first fire
        applyElementsAndLayout();
    }
    window.vg_loadGraph = vg_loadGraph;

    async function applyElementsAndLayout() {
        if (!graph) return;
        // Re-entrancy guard: coalesce concurrent load requests
        if (vgSetBusy) {
            vgSetPending = true;
            return;
        }
        vgSetBusy = true;
        try {
            var nodesUrl = window.__vg_initialNodesUrl;
            var edgesUrl = window.__vg_initialEdgesUrl;
            var nodesResp = nodesUrl ? await fetchWithEtag(nodesUrl, 'nodes') : null;
            var edgesResp = edgesUrl ? await fetchWithEtag(edgesUrl, 'edges') : null;
            if (nodesResp && nodesResp.status === 304 && cachedNodesBody) {
                // unchanged
            } else if (nodesResp && nodesResp.ok) {
                cachedNodesBody = await nodesResp.json();
            }
            if (edgesResp && edgesResp.status === 304 && cachedEdgesBody) {
                // unchanged
            } else if (edgesResp && edgesResp.ok) {
                cachedEdgesBody = await edgesResp.json();
            }
            if (cachedNodesBody || cachedEdgesBody) {
                rebuildGraph(
                    cachedNodesBody ? (cachedNodesBody.nodes || []) : [],
                    cachedEdgesBody ? (cachedEdgesBody.edges || []) : []);
            }
            runLayout(currentLayout);
            try { renderer.refresh(); } catch (e) { /* ignore */ }
            fitToViewport();
        } catch (err) {
            showError('Graph load failed: ' + err.message);
        } finally {
            vgSetBusy = false;
            if (vgSetPending) {
                vgSetPending = false;
                applyElementsAndLayout();
            }
        }
    }

    async function fetchWithEtag(url, kind) {
        var headers = {};
        if (kind === 'nodes' && cachedNodesEtag) headers['If-None-Match'] = cachedNodesEtag;
        if (kind === 'edges' && cachedEdgesEtag) headers['If-None-Match'] = cachedEdgesEtag;
        var resp = await fetch(url, { credentials: 'same-origin', headers: headers });
        if (resp.ok) {
            var etag = resp.headers.get('ETag');
            if (kind === 'nodes') cachedNodesEtag = etag;
            if (kind === 'edges') cachedEdgesEtag = etag;
        }
        return resp;
    }

    function rebuildGraph(nodes, edges) {
        if (!graph) return;
        graph.clear();
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            if (!n || !n.key) continue;
            try {
                graph.addNode(n.key, n.attributes || {});
            } catch (err) {
                log('addNode failed for ' + n.key + ': ' + err.message);
            }
        }
        for (var j = 0; j < edges.length; j++) {
            var e = edges[j];
            if (!e || !e.key || !e.source || !e.target) continue;
            try {
                // graphology MultiDirectedGraph provides addDirectedEdgeWithKey(key, source, target, attributes).
                // The plain addEdge(key, source, target, attributes) signature seen in
                // simpleGraphs ALSO exists on MultiDirectedGraph but with the
                // auto-generate-key semantics — the 4th arg is silently dropped, so
                // passing 4 args lands in the wrong slot. addEdgeWithKey is the
                // explicit-key variant and accepts (edge, source, target, attrs).
                graph.addEdgeWithKey(e.key, e.source, e.target, e.attributes || {});
            } catch (err) {
                log('addEdge failed for ' + e.key + ': ' + err.message);
            }
        }
        log('rebuildGraph: ' + graph.order + ' nodes, ' + graph.size + ' edges');
    }

    function fitToViewport() {
        if (!renderer) return;
        var container = $('sigma-container');
        if (!container || container.clientWidth <= 0 || container.clientHeight <= 0) return;
        try { renderer.getCamera().fit({ padding: 30 }); } catch (e) {}
    }

    /* ---- Layouts ---- */

    function runLayout(name) {
        currentLayout = name;
        if (!graph || graph.order === 0) return;
        try {
            switch (name) {
                case 'FORCE_ATLAS_SIGMA':
                    graphologyLayoutForceAtlas2.assign(graph, {
                        iterations: 150,
                        settings: {
                            gravity: 1,
                            scalingRatio: 10,
                            slowDown: 5,
                            strongGravityMode: false
                        }
                    });
                    break;
                case 'FORCE_DIRECTED_2_SIGMA':
                    runForceDirected2(graph);
                    break;
                case 'NOVERLAP_SIGMA':
                    graphologyLayoutNoverlap.assign(graph, {
                        maxIterations: 50,
                        gridSize: 8,
                        ratio: 60,
                        margin: 4
                    });
                    break;
                case 'CIRCULAR_SIGMA':
                    graphologyLayout.circular.assign(graph);
                    break;
                case 'RANDOM_SIGMA':
                    graphologyLayout.random.assign(graph);
                    break;
                default:
                    log('unknown layout: ' + name + ', falling back to FORCE_ATLAS_SIGMA');
                    graphologyLayoutForceAtlas2.assign(graph, {
                        iterations: 150,
                        settings: {
                            gravity: 1,
                            scalingRatio: 10,
                            slowDown: 5
                        }
                    });
                    break;
            }
        } catch (err) {
            log('runLayout: ' + err.message);
        }
    }
    window.vg_setLayout = function (name) {
        log('vg_setLayout: ' + name);
        runLayout(name);
        if (renderer) {
            try { renderer.refresh(); } catch (e) {}
            fitToViewport();
        }
    };
    window.vg_setLayoutOptions = function (options) {
        pendingLayoutOptions = options || {};
        runLayout(currentLayout);
        if (renderer) {
            try { renderer.refresh(); } catch (e) {}
            fitToViewport();
        }
    };

    /**
     * ForceDirected2 — ForceAtlas2 with edge-weight-driven attraction.
     * Higher weight ⇒ shorter equilibrium distance ⇒ nodes pulled closer.
     *
     * Implementation:
     * 1. Compute max log-weight for normalization.
     * 2. Cache the FA2-weight attribute per edge in a temporary field so
     *    graphology-layout-forceatlas2's edgeWeight callback can read it.
     * 3. Run FA2 with the edgeWeight callback (FA2 expects a higher value
     *    ⇒ STRONGER attraction ⇒ shorter equilibrium distance — matching
     *    our "high weight ⇒ close" semantics).
     * 4. Run graphology-layout-noverlap so adjacent nodes don't overlap.
     *    The ratio parameter controls how much of the canvas the graph
     *    spans — we pin it to ~85% of the container width so the graph
     *    uses the available screen real estate without touching the edges.
     * 5. Strip the temporary _fd2_weight attribute from every edge so it
     *    doesn't leak into further layout runs.
     */
    function runForceDirected2(graph) {
        if (!graphology || !graphologyLayoutForceAtlas2 || !graphologyLayoutNoverlap) {
            log('runForceDirected2: required layout libraries missing');
            return;
        }
        var maxLog = 0;
        var validEdges = 0;
        graph.forEachEdge(function (edge) {
            var w = graph.getEdgeAttribute(edge, 'weight') || 1;
            var lw = Math.log10(Number(w) + 1);
            if (lw > maxLog) maxLog = lw;
            validEdges++;
        });
        if (maxLog <= 0) maxLog = 1;
        graph.forEachEdge(function (edge) {
            var w = graph.getEdgeAttribute(edge, 'weight') || 1;
            var lw = Math.log10(Number(w) + 1);
            // 0.05 + 0.95 * normalized; even zero-weight edges get a small
            // pull so the layout doesn't tear them apart.
            graph.setEdgeAttribute(edge, '_fd2_weight', 0.05 + 0.95 * (lw / maxLog));
        });

        // Seed positions if missing — graphology-layout-forceatlas2 in
        // a MultiDirectedGraph without any pre-set positions occasionally
        // produces NaN coordinates for nodes that lack incoming edges.
        // Random-initialise every node to a unit-grid coordinate so the
        // FA2 simulation has a stable starting state.
        graph.forEachNode(function (n) {
            var x = graph.getNodeAttribute(n, 'x');
            var y = graph.getNodeAttribute(n, 'y');
            if (typeof x !== 'number' || typeof y !== 'number'
                    || !isFinite(x) || !isFinite(y)) {
                graph.setNodeAttribute(n, 'x', Math.random() - 0.5);
                graph.setNodeAttribute(n, 'y', Math.random() - 0.5);
            }
        });

        try {
            graphologyLayoutForceAtlas2.assign(graph, {
                iterations: 200,
                settings: {
                    gravity: 1,
                    scalingRatio: 10,
                    slowDown: 5,
                    strongGravityMode: false,
                    edgeWeight: function (edge) {
                        return graph.getEdgeAttribute(edge, '_fd2_weight') || 0.1;
                    }
                }
            });
        } catch (err) {
            log('runForceDirected2: FA2 failed: ' + err.message);
        }

        // After FA2, sweep the graph for any NaN/Inf positions and seed
        // them with the centroid of the valid positions so the NoOverlap
        // pass has a sane input.
        var cx = 0, cy = 0, valid = 0, bad = 0;
        graph.forEachNode(function (n) {
            var x = graph.getNodeAttribute(n, 'x');
            var y = graph.getNodeAttribute(n, 'y');
            if (typeof x === 'number' && isFinite(x)
                    && typeof y === 'number' && isFinite(y)) {
                cx += x; cy += y; valid++;
            } else { bad++; }
        });
        if (valid > 0) {
            cx /= valid; cy /= valid;
        }
        graph.forEachNode(function (n) {
            var x = graph.getNodeAttribute(n, 'x');
            var y = graph.getNodeAttribute(n, 'y');
            if (typeof x !== 'number' || !isFinite(x)
                    || typeof y !== 'number' || !isFinite(y)) {
                graph.setNodeAttribute(n, 'x', cx + (Math.random() - 0.5) * 0.01);
                graph.setNodeAttribute(n, 'y', cy + (Math.random() - 0.5) * 0.01);
            }
        });

        // NoOverlap post-processing — prevents node overlap and pins the
        // graph spread to ~85% of the container width so the graph uses
        // the available screen real estate without touching the edges.
        var container = $('sigma-container');
        var containerW = container ? container.clientWidth : 800;
        var spread = Math.max(1, maxAxisSpread(graph));
        var ratio = Math.max(20, containerW * 0.85 / spread);
        try {
            graphologyLayoutNoverlap.assign(graph, {
                maxIterations: 50,
                gridSize: 8,
                ratio: ratio,
                margin: 4
            });
        } catch (err) {
            log('runForceDirected2: NoOverlap failed: ' + err.message);
        }

        // Final NaN sweep — if NoOverlap introduced any, restore from centroid.
        bad = 0;
        graph.forEachNode(function (n) {
            var x = graph.getNodeAttribute(n, 'x');
            var y = graph.getNodeAttribute(n, 'y');
            if (typeof x !== 'number' || !isFinite(x)
                    || typeof y !== 'number' || !isFinite(y)) {
                graph.setNodeAttribute(n, 'x', cx + (Math.random() - 0.5) * 0.01);
                graph.setNodeAttribute(n, 'y', cy + (Math.random() - 0.5) * 0.01);
                bad++;
            }
        });
        if (bad > 0) log('runForceDirected2: ' + bad + ' nodes required post-NaN rescue');

        // Cleanup temp attribute
        graph.forEachEdge(function (edge) {
            try { graph.removeEdgeAttribute(edge, '_fd2_weight'); } catch (e) {}
        });
    }

    function maxAxisSpread(graph) {
        var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        graph.forEachNode(function (n) {
            var x = graph.getNodeAttribute(n, 'x');
            var y = graph.getNodeAttribute(n, 'y');
            if (typeof x === 'number') {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
            }
            if (typeof y === 'number') {
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        });
        if (minX === Infinity) return 1;
        return Math.max(maxX - minX, maxY - minY, 1);
    }

    /* ---- NodeConfig / Leiden / Legend ---- */

    window.vg_applyNodeConfig = function (config) {
        if (!config) return;
        currentNodeConfig = config;
        if (!renderer) return;
        renderer.setSetting('nodeReducer', buildNodeReducer(config));
        renderer.setSetting('edgeReducer', buildEdgeReducer());
        try { renderer.refresh(); } catch (e) {}
    };

    /**
     * Push the engine-agnostic "effective per-node color" map produced
     * by {@code NodeColorResolver} (Java side). Pairs with
     * {@code SigmaJsBridge.applyNodeColors} so the dialog's Tag-Colors
     * and Leiden-Colors buttons apply identically to all engines.
     *
     * <p>The map takes precedence over both {@code currentNodeConfig}
     * (label/tag colors) and {@code currentLeidenColors} in
     * {@code buildNodeReducer} — same precedence as the resolver — so
     * a click on "Apply Tag Colors" lands on the canvas immediately.</p>
     */
    window.vg_applyNodeColors = function (effective) {
        currentEffectiveColors = (effective && typeof effective === 'object') ? effective : {};
        if (!renderer) return;
        renderer.setSetting('nodeReducer', buildNodeReducer(currentNodeConfig));
        renderer.setSetting('edgeReducer', buildEdgeReducer());
        try { renderer.refresh(); } catch (e) {}
    };

    function buildNodeReducer(config) {
        var showTitle = config.showTitle !== false;
        var labelColors = config.labelColors || {};
        var tagColors = config.tagColors || {};
        var globalTagColors = config.globalTagColors || {};
        var leidenColors = currentLeidenColors || {};
        var effectiveColors = currentEffectiveColors || {};
        return function (node, data) {
            var id = data.id || node;
            var color = effectiveColors[id]
                    || leidenColors[id]
                    || (data && data.color)
                    || (labelColors[data.nodeType] || null);
            var attrs = Object.assign({}, data);
            attrs.color = color || '#4A90E2';
            if (!showTitle) attrs.label = '';
            return attrs;
        };
    }

    /**
     * Mirror of {@link buildNodeReducer} for edges. Derives the edge
     * color from the source node's effective / Leiden color so the
     * canvas renders cluster-coloured edges (matches the visual
     * contract of the Cytoscape community-aggregation view, where the
     * edge carries the source community colour).
     *
     * <p>Precedence (high → low):</p>
     * <ol>
     *   <li>{@code currentEffectiveColors[sourceId]} — the resolver map
     *       pushed by {@code vg_applyNodeColors}; same precedence as
     *       buildNodeReducer.</li>
     *   <li>{@code currentLeidenColors[sourceId]} — the Leiden-cluster
     *       fallback used when no effective color has been pushed.</li>
     * </ol>
     * When neither map has an entry, the edge keeps its existing color
     * attribute (typically the Java-set default or the simple grey
     * fallback).
     */
    function buildEdgeReducer() {
        var leidenColors = currentLeidenColors || {};
        var effectiveColors = currentEffectiveColors || {};
        function nodeColor(nodeId) {
            if (!nodeId) return null;
            return (effectiveColors && effectiveColors[nodeId])
                    || (leidenColors && leidenColors[nodeId])
                    || null;
        }
        return function (edge, data) {
            var out = Object.assign({}, data);
            if (out.type && out.type !== 'arrow' && out.type !== 'line') {
                delete out.type;
            }
            if (!out.type) out.type = 'arrow';
            out.size = out.size || 1.2;
            try {
                var srcKey = graph && graph.source && graph.source(edge);
                var sc = nodeColor(srcKey);
                if (sc) out.color = sc;
            } catch (e) { /* keep inherited color */ }
            out.label = out.label || '';
            return out;
        };
    }

    window.vg_applyLeidenColors = function (colorMap) {
        currentLeidenColors = colorMap || {};
        if (!renderer) return;
        renderer.setSetting('nodeReducer', buildNodeReducer(currentNodeConfig));
        renderer.setSetting('edgeReducer', buildEdgeReducer());
        try { renderer.refresh(); } catch (e) {}
    };

    /**
     * Render the Color Palette panel. Pairs with
     * {@code SigmaJsBridge.refreshPalette} which pushes one of these per
     * non-empty color map push. {@code enabled} controls visibility —
     * when false the panel hides but the entries are kept so toggling
     * back on restores the prior state.
     *
     * <p>Replaces the legacy {@code vg_applyLegend} (manually-driven by
     * {@code SwitchingViewer.setLegend}) — the panel is now auto-managed
     * from {@code applyNodeColors} / {@code setLeidenClusterColors} on
     * the Java side.</p>
     */
    window.vg_applyColorPalette = function (entries, enabled) {
        legendEntries = Array.isArray(entries) ? entries : [];
        legendEnabled = !!enabled && legendEntries.length > 0;
        if (!legendEnabled) {
            activeLegendColor = null;
        }
        renderLegendPanel();
    };

    /**
     * Hide the Color Palette panel. Pairs with {@code clear()} on the
     * Java side — clears the cached color maps and the panel state.
     */
    window.vg_hideColorPalette = function () {
        legendEntries = [];
        legendEnabled = false;
        activeLegendColor = null;
        renderLegendPanel();
    };

    function renderLegendPanel() {
        var panel = $('vg-legend');
        var body = panel ? panel.querySelector('.vg-legend-body') : null;
        if (!panel || !body) return;
        if (!legendEnabled || legendEntries.length === 0) {
            panel.style.display = 'none';
            body.innerHTML = '';
            return;
        }
        panel.style.display = 'block';
        body.innerHTML = '';
        legendEntries.forEach(function (entry) {
            var row = document.createElement('div');
            row.className = 'vg-legend-item';
            row.setAttribute('data-color', entry.colorHex || '');
            var sw = document.createElement('span');
            sw.className = 'vg-legend-swatch';
            sw.style.background = entry.colorHex || '#ccc';
            row.appendChild(sw);
            var lbl = document.createElement('span');
            lbl.className = 'vg-legend-label';
            lbl.textContent = entry.label || '';
            row.appendChild(lbl);
            if (typeof entry.count === 'number') {
                var cnt = document.createElement('span');
                cnt.className = 'vg-legend-count';
                cnt.textContent = '(' + entry.count + ')';
                row.appendChild(cnt);
            }
            row.addEventListener('click', function () {
                if (activeLegendColor === entry.colorHex) {
                    activeLegendColor = null;
                } else {
                    activeLegendColor = entry.colorHex;
                }
                highlightLegend(activeLegendColor);
                renderLegendPanel();
            });
            if (activeLegendColor === entry.colorHex) row.classList.add('vg-legend-active');
            body.appendChild(row);
        });
    }

    function highlightLegend(color) {
        if (!renderer || !graph) return;
        if (!color) {
            renderer.setSetting('nodeReducer', buildNodeReducer(currentNodeConfig));
        } else {
            renderer.setSetting('nodeReducer', function (node, data) {
                var id = data.id || node;
                // Match against effective colors first, then fall back
                // to Leiden colors so the legend highlight stays in
                // sync with whatever the user is currently looking at.
                var nodeColor = (currentEffectiveColors && currentEffectiveColors[id])
                        || (currentLeidenColors && currentLeidenColors[id]);
                var match = nodeColor && nodeColor.toLowerCase() === color.toLowerCase();
                var attrs = Object.assign({}, data);
                attrs.color = match ? (nodeColor || '#4A90E2') : '#cccccc';
                attrs.opacity = match ? 1 : 0.25;
                if (!currentNodeConfig.showTitle) attrs.label = '';
                return attrs;
            });
        }
        try { renderer.refresh(); } catch (e) {}
    }

    var legendToggle = document.querySelector('#vg-legend .vg-legend-toggle');
    if (legendToggle) {
        legendToggle.addEventListener('click', function () {
            legendCollapsed = !legendCollapsed;
            var panel = $('vg-legend');
            if (panel) {
                if (legendCollapsed) panel.classList.add('vg-legend-collapsed');
                else panel.classList.remove('vg-legend-collapsed');
            }
        });
    }

    /* ---- Misc canvas handlers ---- */

    window.vg_clear = function () {
        if (graph) graph.clear();
        if (renderer) {
            try { renderer.refresh(); } catch (e) {}
        }
    };

    window.vg_fitToScreen = function () {
        fitToViewport();
    };

    window.vg_resize = function () {
        var container = $('sigma-container');
        if (!container || !renderer) return;
        var w = container.clientWidth;
        var h = container.clientHeight;
        if (w <= 0 || h <= 0) return;
        // Canvas renderer needs resize() before refresh() so the backing
        // canvas pixel buffer matches the new container size; the sigma
        // renderer reads container dimensions lazily so refresh() alone
        // is enough for it.
        if (renderer._isCanvas && typeof renderer.resize === 'function') {
            try { renderer.resize(); } catch (e) { /* ignore */ }
        }
        try { renderer.refresh(); } catch (e) {}
        fitToViewport();
    };

    window.vg_dispose = function () {
        try { hideTooltip(); } catch (e) {}
        activeLegendColor = null;
        var tip = $('vg-tooltip');
        if (tip && tip.parentNode) tip.parentNode.removeChild(tip);
        var sidePanel = $('vg-side-panel');
        if (sidePanel && sidePanel.parentNode) sidePanel.parentNode.removeChild(sidePanel);
        var legend = $('vg-legend');
        if (legend && legend.parentNode) legend.parentNode.removeChild(legend);
    };

    window.vg_showContextMenu = function (snapshot, x, y) {
        var menu = $('vg-context-menu');
        if (!menu) return;
        menu.innerHTML = '';
        if (snapshot && Array.isArray(snapshot.entries)) {
            snapshot.entries.forEach(function (entry) {
                if (!entry || entry.separator) {
                    var sep = document.createElement('div');
                    sep.className = 'vg-menu-separator';
                    menu.appendChild(sep);
                    return;
                }
                var item = document.createElement('div');
                item.className = 'vg-menu-item';
                item.textContent = entry.label || entry.id || '';
                item.setAttribute('data-entry-id', entry.id || '');
                item.addEventListener('click', function () {
                    javaCall('vg_invokeContextMenuAction', entry.id || '');
                    hideContextMenu();
                });
                menu.appendChild(item);
            });
        }
        var container = $('sigma-container');
        var rect = container ? container.getBoundingClientRect() : { left: 0, top: 0 };
        menu.style.left = Math.round(rect.left + x + 4) + 'px';
        menu.style.top = Math.round(rect.top + y - 4) + 'px';
        menu.style.display = 'block';
    };

    window.vg_hideContextMenu = function () { hideContextMenu(); };

    function hideContextMenu() {
        var menu = $('vg-context-menu');
        if (menu) menu.style.display = 'none';
    }

    // Document-level click hides the context menu (matches cytoscape behavior).
    document.addEventListener('click', function () { hideContextMenu(); }, true);

    /* ---- boot ---- */
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
