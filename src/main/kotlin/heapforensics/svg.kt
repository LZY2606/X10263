package heapforensics

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * Render the object graph as SVG. Layout is a deterministic layered walk from roots,
 * so equivalent inputs always produce the same drawing. Coordinates are in px.
 */
fun renderGraphSvg(raw: RawSnapshot, result: AnalysisResult?): String {
    val derived = derive(raw)
    val reachable = result?.reachable?.toSet() ?: derived.objects.keys.toSet()
    val depthOf = LinkedHashMap<String, Int>()
    if (result != null) {
        for (o in result.objects) depthOf[o.objectId] = o.rootPaths.minOfOrNull { it.hops.size }?.minus(1) ?: 0
    } else {
        raw.roots.forEach { depthOf[it.objectId] = 0 }
    }
    val unreachable = derived.objects.keys.filter { it !in reachable }
    unreachable.forEachIndexed { i, id -> depthOf[id] = (depthOf.values.maxOrNull() ?: 0) + 1 + (i % 2) }

    val layers = depthOf.entries.groupBy({ it.value }, { it.key }).toSortedMap()
    val pos = HashMap<String, Pair<Double, Double>>()
    val xStep = 170.0; val yStep = 90.0
    layers.forEach { (depth, ids) ->
        ids.sorted().forEachIndexed { i, id -> pos[id] = 90.0 + depth * xStep to 70.0 + i * yStep }
    }
    val w = ((layers.keys.lastOrNull() ?: 0) + 2) * xStep + 80
    val h = (layers.values.maxOfOrNull { it.size } ?: 1) * yStep + 120

    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${w.toInt()}" height="${h.toInt()}" viewBox="0 0 ${w.toInt()} ${h.toInt()}" font-family="monospace" font-size="12">""")
    sb.append("""<defs><marker id="arr" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#555"/></marker>""")
    sb.append("""<marker id="arrW" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#a0a"/></marker></defs>""")
    sb.append("""<rect width="100%" height="100%" fill="#fbfbfd"/>""")

    for ((e, kind) in derived.edges) {
        val a = pos[e.from] ?: continue; val b = pos[e.to] ?: continue
        val (style, marker) = when (kind) {
            EdgeKind.WEAK -> "stroke:#a0a;stroke-width:1.2;stroke-dasharray:5,4" to "arrW"
            EdgeKind.INTERNAL -> "stroke:#07a;stroke-width:1.4;stroke-dasharray:2,2" to "arr"
            else -> "stroke:#555;stroke-width:1.6" to "arr"
        }
        val mx = (a.first + b.first) / 2; val my = (a.second + b.second) / 2 - 6
        sb.append("""<line x1="${a.first}" y1="${a.second}" x2="${b.first}" y2="${b.second}" style="$style" marker-end="url(#$marker)"/>""")
        if (e.label != null) sb.append("""<text x="$mx" y="$my" fill="#888" font-size="10">${esc(e.label)}</text>""")
    }

    val rootIds = raw.roots.map { it.objectId }.toSet()
    val ana = result?.objects?.associateBy { it.objectId }
    for ((id, p) in pos.toSortedMap()) {
        val d = derived.objects[id] ?: continue
        val isRoot = id in rootIds
        val isReach = id in reachable
        val fill = when {
            !isReach -> "#eee"
            isRoot -> "#ffe9b3"
            else -> "#dff0ff"
        }
        val stroke = if (isRoot) "#c80" else "#468"
        sb.append("""<g><rect x="${p.first - 55}" y="${p.second - 22}" rx="8" width="110" height="44" fill="$fill" stroke="$stroke" stroke-width="1.5"/>""")
        sb.append("""<text x="${p.first}" y="${p.second - 6}" text-anchor="middle" font-weight="bold">${esc(id)}</text>""")
        val cls = d.raw.classId ?: "incomplete"
        val size = d.sizeBytes?.let { "$it B" } ?: "? B"
        sb.append("""<text x="${p.first}" y="${p.second + 8}" text-anchor="middle" fill="#555" font-size="10">${esc(cls)} · $size</text>""")
        val ret = ana?.get(id)?.retainedBytes
        if (ret != null) sb.append("""<text x="${p.first}" y="${p.second + 19}" text-anchor="middle" fill="#a33" font-size="10">retained $ret B</text>""")
        sb.append("</g>")
    }
    sb.append("""<text x="10" y="${h - 12}" fill="#999" font-size="10">units: bytes (B) · coordinates: px, origin top-left · solid=STRONG dashed-purple=WEAK dotted-blue=INTERNAL · grey=unreachable</text>""")
    sb.append("</svg>")
    return sb.toString()
}
