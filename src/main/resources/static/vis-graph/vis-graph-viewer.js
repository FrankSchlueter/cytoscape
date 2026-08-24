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
                nodes.add(pendingData.nodes.map(n => applySvgIcon(applySvgImage(wrapTitleAsElement(n)))));
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

    var __vgv_nodeConfig = { showTitle: true, labelColors: {}, tagColors: {} };

    window.vgv_applyNodeConfig = function (configJson) {
        try {
            var cfg = typeof configJson === 'object' ? configJson : JSON.parse(configJson);
            if (cfg && typeof cfg === 'object') {
                __vgv_nodeConfig = {
                    showTitle: cfg.showTitle !== false,
                    labelColors: cfg.labelColors || {},
                    tagColors: cfg.tagColors || {}
                };
            }
        } catch (e) {
            console.error('vgv_applyNodeConfig: invalid config', e);
        }
    };

    /* ----- API: SVG node images ----- */

    /**
     * Build an SVG badge for a node:
     *   - rounded rectangle filled with the node color
     *   - stereotype («Class», «Enum», …) above the name
     *   - bold label centered inside
     * Returns the raw SVG string (caller wraps it as a data URI).
     */
    window.vgv_createSvgNode = function (label, color, type) {
        var safeLabel = (label == null ? '' : String(label));
        var safeColor = (color == null || color === '') ? '#4A90E2' : color;
        var estWidth = Math.max(120, safeLabel.length * 8 + 40);
        var height = 40;

        var typeLabel = '';
        switch (type) {
            case 'class':     typeLabel = '\u00ABClass\u00BB';     break;
            case 'enum':      typeLabel = '\u00ABEnum\u00BB';      break;
            case 'record':    typeLabel = '\u00ABRecord\u00BB';    break;
            case 'controller':typeLabel = '\u00ABController\u00BB';break;
            case 'entity':    typeLabel = '\u00ABEntity\u00BB';    break;
            case 'table':     typeLabel = '\u00ABTable\u00BB';     break;
            default:          typeLabel = '\u00AB' + (type || '') + '\u00BB';
        }

        return [
            '<svg xmlns="http://www.w3.org/2000/svg" width="', estWidth,
            '" height="', height, '" viewBox="0 0 ', estWidth, ' ', height, '">',
            '<rect x="2" y="2" width="', (estWidth - 4), '" height="', (height - 4),
            '" rx="6" ry="6" fill="', safeColor,
            '" stroke="#ffffff" stroke-width="1.5" />',
            '<text x="', (estWidth / 2), '" y="15" ',
            'font-family="Segoe UI, Arial, sans-serif" font-size="10" font-weight="bold" ',
            'fill="rgba(255,255,255,0.85)" text-anchor="middle">', typeLabel, '</text>',
            '<text x="', (estWidth / 2), '" y="29" ',
            'font-family="Segoe UI, Arial, sans-serif" font-size="12" font-weight="bold" ',
            'fill="#ffffff" text-anchor="middle">', safeLabel, '</text>',
            '</svg>'
        ].join('');
    };

    /**
     * If a node carries an {@code svgImage} attribute, materialize it into a
     * data URI {@code image} field (so vis-network renders the SVG) and set
     * the shape to {@code "image"}. Returns the (possibly mutated) node.
     *
     * <p>As a backward-compat safety net, also normalizes an existing
     * {@code image} field when it is an SVG data URI emitted by the server
     * side. {@code GraphNode.setSvgIcon} builds the data URI on the Java
     * side using {@code URLEncoder.encode}, which encodes spaces as
     * {@code "+"} (form-encoding). Browsers do <em>not</em> decode
     * {@code "+"} as space inside data URIs (WHATWG / RFC 3986), so the
     * resulting URI is invalid and vis-network falls back to
     * {@code brokenImage}. {@link vgv_normalizeSvgDataUri} rewrites the
     * body with proper percent-encoding so the browser can decode it.</p>
     */
    function applySvgImage(n) {
        if (!n) return n;
        if (typeof n.image === 'string' && n.image.indexOf('data:image/svg') === 0) {
            n.image = vgv_normalizeSvgDataUri(n.image);
        }
        if (!n.svgImage) return n;
        var info = n.svgImage;
        var color = info.color;
        if (!color) {
            color = (typeof n.color === 'string' && n.color !== '')
                ? n.color
                : (n.color && n.color.background) ? n.color.background
                : '#4A90E2';
        }
        var label = info.label || n.label || n.id || '';
        var type = info.type || 'class';
        var svg = window.vgv_createSvgNode(label, color, type);
        n.image = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
        n.shape = 'image';
        delete n.svgImage;
        return n;
    }

    /**
     * Build a 30×30 SVG icon badge from a raw icon SVG body:
     *   - rewrites the root {@code <svg>} tag with fixed 30×30 dimensions
     *     (stripping any pre-existing width/height)
     *   - recolors all {@code <path>} fill attributes with {@code color},
     *     preserving a {@code fill="none"} sentinel on the root element
     *   - overlays the {@code type} character centered on the icon's
     *     viewBox
     * Returns the raw SVG string. The caller wraps it as a data URI.
     */
    window.vgv_createSvgIcon = function (svgBody, color, type) {
        var size = 30;
        var safeColor = (color == null || color === '') ? '#4A90E2' : color;
        var safeType = (type == null) ? '' : String(type);
        var src = (svgBody == null) ? '' : String(svgBody);
        // Strip any <?xml ... ?> declaration: optional for SVG-as-image,
        // and some embedded webviews (VSCode, Electron) handle it poorly.
        src = src.replace(/<\?xml[^?]*\?>\s*/, '');

        // 1) Determine the viewBox center, then shift the y DOWN by 22% of
        //    the viewBox height so the type character lands on the visible
        //    body of the icon (cup body, folder body, ...) rather than the
        //    geometric center. Most icons in /static/icons/ carry decorative
        //    elements at the top (steam, folder tab, header bar, ...) and
        //    their meaningful body sits below the geometric middle.
        var cx = 8.0;
        var cy = 8.0;
        var vbX = 0.0;
        var vbY = 0.0;
        var vbW = 16.0;
        var vbH = 16.0;
        var vbMatch = /viewBox\s*=\s*"\s*([\d.+\-eE]+)\s+([\d.+\-eE]+)\s+([\d.+\-eE]+)\s+([\d.+\-eE]+)/
            .exec(src);
        if (vbMatch) {
            var x = parseFloat(vbMatch[1]);
            var y = parseFloat(vbMatch[2]);
            var w = parseFloat(vbMatch[3]);
            var h = parseFloat(vbMatch[4]);
            if (!isNaN(x) && !isNaN(y) && !isNaN(w) && !isNaN(h)) {
                cx = x + w / 2.0;
                cy = y + h / 2.0 + h * 0.22;
                vbX = x; vbY = y; vbW = w; vbH = h;
            }
        }

        // 2) Rewrite the root <svg ...> tag: drop any width/height and inject 30×30.
        src = src.replace(/<svg([^>]*)>/i, function (_match, attrs) {
            var stripped = attrs.replace(/\s(?:width|height)\s*=\s*"[^"]*"/gi, '');
            return '<svg width="' + size + '" height="' + size + '"' + stripped + '>';
        });

        // 2b) Inject a background <rect> covering the BODY region of the
        //     viewBox (lower ~56%) so the icon's main shape (cup body,
        //     folder body, document body, ...) renders as a SOLID colored
        //     badge rather than a hollow outline. The path sits on top:
        //       - its filled areas (cup outline, folder edge, steam lines
        //         above the body, ...) draw on top of the rect with the
        //         same color and remain visible as the icon shape
        //       - its unfilled "holes" (cup interior, gap between steam
        //         and cup) now show the colored background instead of
        //         transparency, so the shape reads as a SOLID cup / folder
        //     We deliberately do NOT cover the upper ~44% of the viewBox:
        //     the icons in /static/icons/ (java, folder, table-share,
        //     source-code) carry decorative elements in the upper half
        //     (steam, folder tab, header bar, ...) that should remain on a
        //     transparent background so the icon does not become a plain
        //     filled square.
        //     Empirical body offset: top of body at ~y = vbY + 0.44 * vbH
        //     (matches the cup-body top edge in java-16 and the folder-body
        //     top in folder-svgrepo-com).
        var bodyTop = vbY + vbH * 0.44;
        var bodyHeight = vbH - (bodyTop - vbY);
        var bgRect = '<rect x="' + vbX + '" y="' + bodyTop
            + '" width="' + vbW + '" height="' + bodyHeight + '" fill="' + safeColor + '"/>';
        var svgOpen = src.indexOf('<svg');
        if (svgOpen >= 0) {
            var svgClose = src.indexOf('>', svgOpen);
            if (svgClose >= 0) {
                src = src.substring(0, svgClose + 1)
                    + bgRect
                    + src.substring(svgClose + 1);
            }
        }

        // 3) Replace fill attributes on <path> elements only — this preserves
        //    the root <svg fill="none"> sentinel the icons rely on.
        src = src.replace(/(<path\b[^>]*?)\bfill\s*=\s*"[^"]*"/gi,
            '$1fill="' + safeColor + '"');

        // 4) Overlay the type character centered on the icon.
        //    Uses dy="0.35em" for vertical centering (reliable across Firefox,
        //    Chromium, WebKit, and embedded webviews). dominant-baseline is
        //    NOT used because Firefox and some Chromium-based webviews
        //    (notably VSCode's) honor it inconsistently.
        var textOverlay = '<text x="' + cx + '" y="' + cy
            + '" font-family="Segoe UI, Arial, sans-serif" font-size="6" font-weight="bold"'
            + ' fill="#ffffff" text-anchor="middle" dy="0.35em">'
            + safeType + '</text>';
        var closeIdx = src.lastIndexOf('</svg>');
        if (closeIdx >= 0) {
            src = src.substring(0, closeIdx) + textOverlay + src.substring(closeIdx);
        }
        return src;
    };

    /**
     * If a node carries an {@code svgIcon} attribute, materialize it into a
     * data URI {@code image} field (so vis-network renders the SVG) and set
     * the shape to {@code "image"}. Returns the (possibly mutated) node.
     *
     * <p>Expected {@code svgIcon} shape:
     * <pre>{ body: '&lt;svg&gt;...&lt;/svg&gt;', color: '#4A90E2', type: 'C' }</pre>
     * where {@code body} is the raw icon SVG (NOT a data URI), {@code color}
     * is the fill color (defaults to {@code '#4A90E2'} when falsy), and
     * {@code type} is a single character rendered centered on the icon.</p>
     */
    function applySvgIcon(n) {
        if (!n || !n.svgIcon) return n;
        var info = n.svgIcon;
        var color = info.color;
        if (!color) {
            color = (typeof n.color === 'string' && n.color !== '')
                ? n.color
                : (n.color && n.color.background) ? n.color.background
                : '#4A90E2';
        }
        var type = info.type || '';
        var body = info.body || '';
        var svg = window.vgv_createSvgIcon(body, color, type);
        n.image = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
        n.shape = 'image';
        delete n.svgIcon;
        return n;
    }

    /**
     * Rewrite an SVG data URI that uses form-encoding for spaces
     * ({@code "+"}) so the browser can decode it as percent-encoding.
     *
     * <p>Java's {@code URLEncoder.encode} produces
     * {@code application/x-www-form-urlencoded} output, which is invalid
     * inside a data URI body. This helper decodes and re-encodes the body
     * with proper RFC-3986 percent-encoding.</p>
     *
     * <p>Idempotent: a URI that is already properly percent-encoded is
     * returned unchanged (the decode/re-encode round-trip is a no-op for
     * valid percent-encoding).</p>
     */
    function vgv_normalizeSvgDataUri(uri) {
        if (typeof uri !== 'string') return uri;
        if (uri.indexOf('data:image/svg') !== 0) return uri;
        var commaIdx = uri.indexOf(',');
        if (commaIdx < 0) return uri;
        var meta = uri.substring(5, commaIdx);
        var body = uri.substring(commaIdx + 1);
        // form-encoding → percent-encoding for spaces
        body = body.replace(/\+/g, '%20');
        try {
            var decoded = decodeURIComponent(body);
            return 'data:' + meta + ',' + encodeURIComponent(decoded);
        } catch (e) {
            return 'data:' + meta + ',' + body;
        }
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
        if (window.__vgv_nodes) nodes.add(window.__vgv_nodes.map(n => applySvgIcon(applySvgImage(wrapTitleAsElement(n)))));
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
