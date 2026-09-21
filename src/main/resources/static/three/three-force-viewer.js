(function () {
  'use strict';

  console.log('[tfgv] three-force-viewer.js: IIFE start');

  // ---------------------------------------------------------------------------
  // Global error handler — surfaces uncaught errors in the inline error div
  // so the user sees something instead of an empty canvas.
  // ---------------------------------------------------------------------------
  window.addEventListener('error', function (e) {
    console.error('[tfgv] window error:', e.error || e.message);
    var errEl = document.getElementById('tfg-error');
    if (errEl) {
      errEl.style.display = 'block';
      errEl.innerHTML = '<h2>3D-Engine Fehler</h2>'
        + '<pre style="white-space:pre-wrap;font-size:11px;color:#222;background:#fff5f5;padding:8px;border:1px solid #fbb">'
        + (e.error ? (e.error.stack || e.error.toString()) : (e.message || 'unknown'))
        + '</pre>';
    }
  });

  // Calls a Java-side BrowserFunction registered on `window` by RAP.
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
      console.warn('[tfgv] javaCall(' + name + ') - no function registered on window');
      return undefined;
    }
    try {
      return fn.apply(target, Array.prototype.slice.call(arguments, 1));
    } catch (e) {
      console.error('javaCall(' + name + ') - call error:', e);
      return undefined;
    }
  }

  // WebGL probe: hard requirement. Hide UI, show error message and abort boot
  // if WebGL is missing.
  function probeWebGL() {
    try {
      var c = document.createElement('canvas');
      var gl = c.getContext('webgl') || c.getContext('experimental-webgl');
      if (!gl) return false;
      // Confirm we can actually compile a trivial shader — some headless
      // browsers report a context that throws on first use.
      var vs = gl.createShader(gl.VERTEX_SHADER);
      gl.shaderSource(vs, 'void main(){gl_Position=vec4(0,0,0,1);}');
      gl.compileShader(vs);
      var ok = gl.getShaderParameter(vs, gl.COMPILE_STATUS);
      gl.deleteShader(vs);
      return !!ok;
    } catch (e) {
      console.error('[tfgv] probeWebGL threw:', e);
      return false;
    }
  }

  var webglOk = probeWebGL();
  console.log('[tfgv] probeWebGL result:', webglOk);

  if (!webglOk) {
    var rootErr = document.getElementById('tfg-root');
    if (rootErr) rootErr.style.display = 'none';
    var errEl = document.getElementById('tfg-error');
    if (errEl) {
      errEl.style.display = 'block';
      errEl.innerHTML = '<h2>WebGL nicht verfügbar</h2>'
        + '<p>3D Force-Directed Graph benötigt eine WebGL-fähige Browser-Engine.</p>'
        + '<p style="font-size:11px;color:#666">Hinweis: in Headless-Chromium ohne GPU fehlt WebGL oft auch mit --disable-gpu-sandbox. Versuchen Sie --use-gl=swiftshader oder --enable-webgl.</p>';
    }
    // Still signal readiness so the Java side can stop waiting
    javaCall('tfgv_viewerReady');
    return;
  }

  if (typeof ForceGraph3D !== 'function') {
    console.error('[tfgv] ForceGraph3D is not defined — vendor bundle failed to load');
    var rootEl2 = document.getElementById('tfg-root');
    if (rootEl2) rootEl2.style.display = 'none';
    var errEl2 = document.getElementById('tfg-error');
    if (errEl2) {
      errEl2.style.display = 'block';
      errEl2.innerHTML = '<h2>3D-Engine: ForceGraph3D fehlt</h2>'
        + '<p>Die Vendor-Bibliothek 3d-force-graph wurde nicht geladen. Prüfen Sie die Network-Antworten für /three/three-force-graph.min.js.</p>';
    }
    javaCall('tfgv_viewerReady');
    return;
  }

  // ---------------------------------------------------------------------------
  // Tooltip (NVL-parity: lazy, position:fixed, properties table)
  // ---------------------------------------------------------------------------
  var tooltipEl = null;
  function ensureTooltip() {
    if (tooltipEl) return tooltipEl;
    tooltipEl = document.createElement('div');
    tooltipEl.id = 'tfg-tooltip';
    document.body.appendChild(tooltipEl);
    return tooltipEl;
  }
  function hideTooltip() {
    if (tooltipEl) tooltipEl.style.display = 'none';
  }

  function escapeHtml(s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function renderTooltipHTML(kind, element) {
    var caption = '';
    var sub = '';
    if (kind === 'node') {
      caption = (element && element.caption) ? String(element.caption) : (element && element.id != null ? String(element.id) : '');
      var labels = (element && element.labels) ? element.labels : [];
      if (labels.length) sub = labels.join(', ');
    } else {
      caption = (element && element.type) ? String(element.type) : '';
      var cap2 = (element && element.caption) ? String(element.caption) : '';
      if (cap2) sub = cap2;
    }
    var props = (element && element.properties) ? element.properties : {};
    var rows = '';
    Object.keys(props).forEach(function (k) {
      var v = props[k];
      rows += '<tr><td class="tfg-tt-key">' + escapeHtml(k) + '</td>'
            + '<td class="tfg-tt-val">' + escapeHtml(v) + '</td></tr>';
    });
    var subHtml = sub ? '<div class="tfg-tt-sub">' + escapeHtml(sub) + '</div>' : '';
    var tableHtml = rows ? '<table class="tfg-tt-table">' + rows + '</table>' : '';
    return '<div class="tfg-tt-caption">' + escapeHtml(caption) + '</div>' + subHtml + tableHtml;
  }

  function updateTooltip(kind, element, evt) {
    if (!element) { hideTooltip(); return; }
    var el = ensureTooltip();
    el.innerHTML = renderTooltipHTML(kind, element);
    var x = (evt && typeof evt.clientX === 'number') ? evt.clientX + 12 : 0;
    var y = (evt && typeof evt.clientY === 'number') ? evt.clientY + 12 : 0;
    el.style.display = 'block';
    el.style.left = x + 'px';
    el.style.top = y + 'px';
    var rect = el.getBoundingClientRect();
    if (rect.right > window.innerWidth) {
      el.style.left = Math.max(0, x - rect.width - 24) + 'px';
    }
    if (rect.bottom > window.innerHeight) {
      el.style.top = Math.max(0, y - rect.height - 24) + 'px';
    }
  }

  // ---------------------------------------------------------------------------
  // Color Palette panel (NVL-parity)
  // ---------------------------------------------------------------------------
  var paletteEntries = [];
  var paletteEnabled = false;
  var paletteCollapsed = false;
  var activeFilterHex = null;
  var paletteEl = null;

  function ensurePaletteEl() {
    if (paletteEl) return paletteEl;
    paletteEl = document.createElement('div');
    paletteEl.id = 'tfg-color-palette';
    paletteEl.innerHTML =
      '<div class="tfg-cp-header">'
      + '<span>Color Palette</span>'
      + '<span class="tfg-cp-toggle" id="tfg-cp-toggle">&minus;</span>'
      + '</div>'
      + '<div class="tfg-cp-body" id="tfg-cp-body"></div>';
    document.getElementById('tfg-root').appendChild(paletteEl);
    paletteEl.querySelector('#tfg-cp-toggle').addEventListener('click', function () {
      paletteCollapsed = !paletteCollapsed;
      paletteEl.classList.toggle('tfg-cp-collapsed', paletteCollapsed);
      paletteEl.querySelector('#tfg-cp-toggle').textContent = paletteCollapsed ? '+' : '\u2212';
    });
    return paletteEl;
  }

  function renderColorPalette() {
    var el = ensurePaletteEl();
    var body = el.querySelector('#tfg-cp-body');
    if (!paletteEnabled || paletteEntries.length === 0) {
      el.style.display = 'none';
      activeFilterHex = null;
      return;
    }
    el.style.display = 'block';
    var html = '';
    paletteEntries.forEach(function (e) {
      html += '<div class="tfg-cp-row" data-hex="' + escapeHtml(e.hex || '') + '">'
            + '<div class="tfg-cp-swatch" style="background:' + escapeHtml(e.hex || '#cccccc') + '"></div>'
            + '<div class="tfg-cp-label">' + escapeHtml(e.label || '') + '</div>'
            + '<div class="tfg-cp-count">' + (e.count || 0) + '</div>'
            + '</div>';
    });
    body.innerHTML = html;
    var rows = body.querySelectorAll('.tfg-cp-row');
    rows.forEach(function (row) {
      row.addEventListener('click', function () {
        var hex = row.getAttribute('data-hex');
        if (activeFilterHex === hex) {
          activeFilterHex = null;
        } else {
          activeFilterHex = hex;
        }
        applyActiveFilter();
        rows.forEach(function (r) {
          r.style.background = (r.getAttribute('data-hex') === activeFilterHex) ? '#e8e8e8' : '';
        });
      });
    });
  }

  // ---------------------------------------------------------------------------
  // State declarations MUST happen BEFORE ForceGraph3D() so the click/hover
  // handlers can capture them. With 'use strict' an undeclared identifier
  // throws a ReferenceError that aborts the whole IIFE.
  // ---------------------------------------------------------------------------
  var wrap = document.createElement('div');
  wrap.id = 'tfg-canvas-wrap';
  document.getElementById('tfg-root').appendChild(wrap);

  var hoverNode = null;
  var hoverLink = null;
  var hoverEvt = null;
  var selectedNodeId = null;
  var selectedLinkId = null;
  var pendingNodes = [];
  var pendingLinks = [];

  // Edge weight styling — caches min/max across the link set so each
  // accessor call is O(1). Updated by recomputeWeightStats().
  var weightStats = { min: 1, max: 1, range: 1 };

  console.log('[tfgv] constructing ForceGraph3D...');
  // 3d-force-graph expects extraRenderers as an ARRAY (iterable). Passing
  // an object {} makes the library throw `extraRenderers.forEach is not
  // a function` during init.
  var graph = ForceGraph3D()(wrap)
    .backgroundColor('#fafafa')
    .showNavInfo(false)
    .nodeRelSize(4)
    .nodeColor(function (n) { return n.color || '#4A90E2'; })
    .nodeLabel(function (n) { return n.caption || n.id; })
    .linkColor(function (l) { return linkColorAccessor(l); })
    .linkWidth(function (l) { return linkWidthAccessor(l); })
    .linkDirectionalParticles(0)
    .onNodeClick(function (node, evt) {
      selectedNodeId = node.id;
      selectedLinkId = null;
      javaCall('tfgv_notifyNodeSelected', String(node.id));
    })
    .onNodeHover(function (node, prev) {
      hoverNode = node || null;
      if (node) updateTooltip('node', node, hoverEvt);
      else hideTooltip();
    })
    .onLinkClick(function (link, evt) {
      selectedLinkId = link.id;
      selectedNodeId = null;
      javaCall('tfgv_notifyLinkSelected', String(link.id));
    })
    .onLinkHover(function (link, prev) {
      hoverLink = link || null;
      if (link) updateTooltip('link', link, hoverEvt);
      else hideTooltip();
    });
  console.log('[tfgv] ForceGraph3D constructed, wrap size:', wrap.clientWidth, 'x', wrap.clientHeight);

  // ---------------------------------------------------------------------------
  // Edge weight styling — heavier weight = thicker edge + higher opacity
  // + closer nodes (shorter forceLink distance). The visual alpha is
  // weight-scaled so the lightest edges are transparent and the heaviest
  // stand out, without flooding the canvas with undifferentiated lines.
  // ---------------------------------------------------------------------------
  function getEffectiveWeight(link) {
    if (typeof link.weight === 'number' && link.weight > 0) return link.weight;
    if (link.properties && typeof link.properties.weight === 'number'
        && link.properties.weight > 0) return link.properties.weight;
    return 1.0;
  }

  function recomputeWeightStats() {
    if (pendingLinks.length === 0) {
      weightStats.min = 1;
      weightStats.max = 1;
      weightStats.range = 1;
      return;
    }
    var wMin = Infinity, wMax = -Infinity;
    for (var i = 0; i < pendingLinks.length; i++) {
      var w = getEffectiveWeight(pendingLinks[i]);
      if (w < wMin) wMin = w;
      if (w > wMax) wMax = w;
    }
    weightStats.min = wMin;
    weightStats.max = wMax;
    // Normalize on sqrt(weight) so very heavy edges don't completely
    // dominate (sqrt dampens the long tail).
    var sMin = Math.sqrt(wMin);
    var sMax = Math.sqrt(wMax);
    weightStats.range = (sMax - sMin) || 1;
  }

  function normalizedT(link) {
    var s = Math.sqrt(getEffectiveWeight(link));
    var t = (s - Math.sqrt(weightStats.min)) / weightStats.range;
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    return t;
  }

  function linkColorAccessor(link) {
    if (!activeFilterHex) {
      var t = normalizedT(link);
      var alpha = 0.15 + 0.55 * t;   // 0.15 (lightest) .. 0.70 (heaviest)
      return 'rgba(120, 120, 120, ' + alpha.toFixed(2) + ')';
    }
    var src = (link.source && link.source.color) ? String(link.source.color) : '';
    var tgt = (link.target && link.target.color) ? String(link.target.color) : '';
    if (src.toLowerCase() === activeFilterHex.toLowerCase()
        || tgt.toLowerCase() === activeFilterHex.toLowerCase()) {
      // Match — pass through weight-scaled color of the underlying link
      // so the matching edge stays visually consistent with non-filter
      // state.
      var t2 = normalizedT(link);
      var alpha2 = 0.4 + 0.55 * t2;   // bumped minimum opacity for visibility
      return 'rgba(120, 120, 120, ' + alpha2.toFixed(2) + ')';
    }
    return 'rgba(180, 180, 180, 0.10)';
  }

  function linkWidthAccessor(link) {
    // 0.5 (lightest) .. 4.0 (heaviest), sqrt-scaled
    return 0.5 + 3.5 * normalizedT(link);
  }

  function applyEdgeWeightStyling() {
    if (pendingLinks.length === 0) return;
    recomputeWeightStats();

    // Width + color: already wired in ForceGraph3D constructor via the
    // accessor functions above. Force them to re-evaluate by re-asserting.
    graph.linkWidth(linkWidthAccessor);
    graph.linkColor(linkColorAccessor);

    // Force-Link distance: log10-scaled, schwere Edges ziehen näher.
    // Range 5..60 px in world coordinates.
    var maxLw = Math.log10(weightStats.max + 1);
    graph.d3Force('link').distance(function (l) {
      var lw = Math.log10(getEffectiveWeight(l) + 1);
      return 60 - 55 * (lw / (maxLw || 1));
    });

    // Schwächere Node-Repulsion, damit schwere Edges die Nodes stärker
    // zusammenziehen können. Default wäre -50 bis -100; -30 lässt mehr
    // Clustering zu.
    graph.d3Force('charge').strength(-30);

    graph.d3ReheatSimulation();
  }

  // Capture mouse coordinates for tooltip positioning
  wrap.addEventListener('mousemove', function (e) {
    hoverEvt = e;
    if (hoverNode) updateTooltip('node', hoverNode, e);
    else if (hoverLink) updateTooltip('link', hoverLink, e);
  });
  wrap.addEventListener('mouseleave', function () {
    hoverNode = null;
    hoverLink = null;
    hideTooltip();
  });

  // Right-click context menu
  wrap.addEventListener('contextmenu', function (e) {
    e.preventDefault();
    if (hoverNode) {
      javaCall('tfgv_requestNodeContextMenu', String(hoverNode.id), e.clientX, e.clientY);
    } else if (hoverLink) {
      javaCall('tfgv_requestLinkContextMenu', String(hoverLink.id), e.clientX, e.clientY);
    }
  });

  // Background click clears selection
  graph.onBackgroundClick(function () {
    selectedNodeId = null;
    selectedLinkId = null;
    javaCall('tfgv_notifySelectionCleared');
  });

  // ---------------------------------------------------------------------------
  // Filter by active palette hex (highlight matching nodes, dim others).
  // The link-color logic lives in linkColorAccessor() so the filter state
  // (activeFilterHex) drives both the node dim/highlight and the link
  // dim/highlight transparently. This function only re-asserts the
  // graph's accessor functions to trigger a refresh.
  // ---------------------------------------------------------------------------
  function applyActiveFilter() {
    var data = graph.graphData();
    if (!data || !data.nodes) return;
    graph.nodeColor(function (n) {
      var c = n.color || '#4A90E2';
      if (!activeFilterHex) return c;
      if (c.toLowerCase() === activeFilterHex.toLowerCase()) return c;
      return '#d8d8d8';
    });
    graph.linkColor(linkColorAccessor);
    graph.refresh();
  }

  // ---------------------------------------------------------------------------
  // Bridge functions exposed to Java (tfgv_*)
  // ---------------------------------------------------------------------------
  function pushDataToGraph() {
    if (pendingNodes.length === 0 && pendingLinks.length === 0) {
      console.log('[tfgv] pushDataToGraph: no data yet, skip');
      return;
    }
    console.log('[tfgv] pushDataToGraph: nodes=' + pendingNodes.length + ' links=' + pendingLinks.length);
    graph.graphData({ nodes: pendingNodes, links: pendingLinks });
    // Edge styling (weight-based width, alpha, force-link distance)
    // must be re-applied after every graphData() so the new edges pick
    // up their weight-driven attributes.
    applyEdgeWeightStyling();
    activeFilterHex = null;
    applyActiveFilter();
    setTimeout(function () {
      try { graph.zoomToFit(400, 80); } catch (e) { /* ignore */ }
    }, 200);
  }

  window.tfgv_setData = function () {
    console.log('[tfgv] tfgv_setData called');
    pendingNodes = window.__tfg_nodes || [];
    pendingLinks = window.__tfg_links || [];
    pushDataToGraph();
  };

  window.tfgv_applyNodeColors = function (map) {
    map = map || {};
    var data = graph.graphData();
    if (!data || !data.nodes) return;
    data.nodes.forEach(function (n) {
      if (map[n.id]) n.color = map[n.id];
    });
    applyActiveFilter();
  };

  window.tfgv_applyLeidenColors = function (map) {
    window.tfgv_applyNodeColors(map);
  };

  window.tfgv_applyColorPalette = function (entries, enabled) {
    paletteEntries = entries || [];
    paletteEnabled = !!enabled;
    activeFilterHex = null;
    renderColorPalette();
  };

  window.tfgv_hideColorPalette = function () {
    paletteEntries = [];
    paletteEnabled = false;
    activeFilterHex = null;
    if (paletteEl) paletteEl.style.display = 'none';
    // Restore weight-based link styling as the baseline (without a
    // filter active the link-color accessor returns the weight-scaled
    // rgba(...) values).
    graph.linkColor(linkColorAccessor);
    graph.linkWidth(linkWidthAccessor);
    graph.refresh();
  };

  window.tfgv_clear = function () {
    pendingNodes = [];
    pendingLinks = [];
    selectedNodeId = null;
    selectedLinkId = null;
    graph.graphData({ nodes: [], links: [] });
  };

  window.tfgv_fitToScreen = function () {
    try { graph.zoomToFit(400, 80); } catch (e) { /* ignore */ }
  };

  window.tfgv_setLayout = function (name) {
    if (name === 'NONE') {
      graph.pauseAnimation();
    } else {
      graph.resumeAnimation();
    }
  };

  window.tfgv_setPhysics = function (enabled) {
    if (enabled) graph.resumeAnimation();
    else graph.pauseAnimation();
  };

  window.tfgv_applyNodeConfig = function () {
    applyActiveFilter();
  };

  window.tfgv_setOption = function (key, value) {
    if (key === 'nodeRelSize') graph.nodeRelSize(value);
    else if (key === 'backgroundColor') graph.backgroundColor(value);
    else if (key === 'linkWidth') graph.linkWidth(value);
  };

  window.tfgv_getOption = function (key) {
    if (key === 'nodeRelSize') return 4;
    if (key === 'backgroundColor') return '#fafafa';
    if (key === 'linkWidth') return 1;
    return null;
  };

  window.tfgv_applyNodeImages = function () {
    // 3D-Force-Graph has no overlayIcon concept; node images are baked into
    // the per-node payload via toThreeForceGraphNode(). No-op.
  };

  // ---------------------------------------------------------------------------
  // Signal readiness to Java. Defer until wrap has a non-zero size (RAP iframe
  // is often 0x0 at first, same as NVL).
  // ---------------------------------------------------------------------------
  function signalReady() {
    console.log('[tfgv] signalReady: wrap=' + wrap.clientWidth + 'x' + wrap.clientHeight);
    pushDataToGraph();
    // Poll window.tfgv_viewerReady until it appears. RAP registers the
    // BrowserFunction synchronously in the Java constructor, but the
    // window-level symbol becomes callable after the browser processes
    // the registration message — typically within a few frames.
    var attempts = 0;
    var fire = function () {
      attempts++;
      if (typeof window.tfgv_viewerReady === 'function') {
        console.log('[tfgv] tfgv_viewerReady fired after ' + attempts + ' attempt(s)');
        javaCall('tfgv_viewerReady');
        return;
      }
      if (attempts < 60) {
        setTimeout(fire, 50);
      } else {
        console.error('[tfgv] tfgv_viewerReady never became available after 3s');
      }
    };
    fire();
  }

  function waitForSize() {
    var w = wrap.clientWidth, h = wrap.clientHeight;
    if (w > 0 && h > 0) {
      signalReady();
      return;
    }
    console.log('[tfgv] wrap is 0x0, deferring via ResizeObserver');
    if (typeof ResizeObserver !== 'undefined') {
      var ro = new ResizeObserver(function (entries) {
        for (var i = 0; i < entries.length; i++) {
          var cr = entries[i].contentRect;
          if (cr.width > 0 && cr.height > 0) {
            ro.disconnect();
            signalReady();
            return;
          }
        }
      });
      ro.observe(wrap);
      setTimeout(function () {
        ro.disconnect();
        signalReady();
      }, 2000);
    } else {
      var attempts = 0;
      var interval = setInterval(function () {
        attempts++;
        if (wrap.clientWidth > 0 && wrap.clientHeight > 0 || attempts > 40) {
          clearInterval(interval);
          signalReady();
        }
      }, 50);
    }
  }
  waitForSize();
})();
