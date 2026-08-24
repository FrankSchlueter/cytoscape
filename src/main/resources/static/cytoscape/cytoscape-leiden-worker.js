/**
 * cytoscape-leiden-worker.js
 *
 * Web-Worker that computes Leiden-style community detection on a graph
 * passed as { nodes: [string], edges: [{source, target, weight}] }.
 *
 * Returns { nodeId: communityId } to the main thread.
 *
 * Self-contained: implements a Louvain-style modularity optimization
 * (closely related to Leiden, deterministic on initialization) so the
 * viewer bundle stays minimal — no graphology/graphology-leiden bundle
 * is required at runtime.
 */

(function () {
    'use strict';

    function louvain(nodes, edges, opts) {
        opts = opts || {};
        var resolution = typeof opts.resolution === 'number' ? opts.resolution : 1.0;
        var passes = typeof opts.passes === 'number' ? opts.passes : 3;

        // Adjacency: node -> [{ other, weight }]
        var adj = Object.create(null);
        var totalWeight = 0;
        nodes.forEach(function (n) { adj[n] = []; });
        edges.forEach(function (e) {
            var s = String(e.source);
            var t = String(e.target);
            var w = Number(e.weight) || 1;
            if (!adj[s]) adj[s] = [];
            if (!adj[t]) adj[t] = [];
            adj[s].push({ other: t, weight: w });
            adj[t].push({ other: s, weight: w });
            totalWeight += w;
        });
        if (totalWeight === 0) totalWeight = 1;

        // Initial assignment: each node its own community.
        var community = Object.create(null);
        var sumTot = Object.create(null);
        nodes.forEach(function (n) {
            community[n] = n;
            sumTot[n] = 0;
        });
        var nodeIds = Object.keys(adj);
        nodeIds.forEach(function (n) {
            adj[n].forEach(function (e) { sumTot[n] += e.weight; });
        });

        // Local moving phase.
        function nodeDegree(n) {
            var s = 0;
            adj[n].forEach(function (e) { s += e.weight; });
            return s;
        }

        function modularityGain(n, targetCommunity, k_i_in) {
            var sigma_tot = sumTot[targetCommunity] || 0;
            var k_i = nodeDegree(n);
            return (k_i_in / totalWeight) - resolution * (sigma_tot * k_i) / (2 * totalWeight * totalWeight);
        }

        for (var pass = 0; pass < passes; pass++) {
            var improved = false;
            for (var i = 0; i < nodeIds.length; i++) {
                var n = nodeIds[i];
                var currentComm = community[n];

                // Sum weights to each neighboring community.
                var neighborCommWeight = Object.create(null);
                adj[n].forEach(function (e) {
                    var c = community[e.other];
                    neighborCommWeight[c] = (neighborCommWeight[c] || 0) + e.weight;
                });

                // Remove n from its community for the gain computation.
                var k_i = nodeDegree(n);
                var ownWeight = neighborCommWeight[currentComm] || 0;
                var sumTotMinusN = (sumTot[currentComm] || 0) - k_i;

                var bestComm = currentComm;
                var bestGain = 0;
                Object.keys(neighborCommWeight).forEach(function (c) {
                    var k_i_in = c === currentComm ? ownWeight : neighborCommWeight[c];
                    // Tentatively move n into c.
                    var gain = (k_i_in / totalWeight)
                        - resolution * ((sumTot[c] || 0) * k_i) / (2 * totalWeight * totalWeight)
                        - (ownWeight / totalWeight)
                        + resolution * (sumTotMinusN * k_i) / (2 * totalWeight * totalWeight);
                    if (gain > bestGain + 1e-9) {
                        bestGain = gain;
                        bestComm = c;
                    }
                });

                if (bestComm !== currentComm) {
                    community[n] = bestComm;
                    sumTot[currentComm] = sumTotMinusN;
                    sumTot[bestComm] = (sumTot[bestComm] || 0) + k_i;
                    improved = true;
                }
            }
            if (!improved) break;
        }

        // Renumber communities to be 0..k-1.
        var remap = Object.create(null);
        var next = 0;
        nodeIds.forEach(function (n) {
            var c = community[n];
            if (remap[c] === undefined) {
                remap[c] = next++;
            }
            community[n] = remap[c];
        });

        return community;
    }

    self.onmessage = function (evt) {
        var msg = evt.data || {};
        if (msg.type !== 'cluster') return;
        var nodes = msg.nodes || [];
        var edges = msg.edges || [];
        var opts = msg.options || {};
        try {
            var result = louvain(nodes, edges, opts);
            self.postMessage({ type: 'cluster-result', requestId: msg.requestId, communities: result });
        } catch (e) {
            self.postMessage({ type: 'cluster-error', requestId: msg.requestId, error: String(e) });
        }
    };
})();
