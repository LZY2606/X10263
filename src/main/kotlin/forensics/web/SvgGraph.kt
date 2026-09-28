package forensics.web

import forensics.graph.AnalysisResult
import forensics.graph.SUPER_ROOT

/** 纯服务端 SVG：确定性层级布局，业务语义（边类型、支配、可达性）不依赖浏览器。 */
object SvgGraph {

    fun render(result: AnalysisResult, width: Int = 980): String {
        val reachable = result.nodes.filter { it.reachable }.map { it.nodeId }.toMutableSet()
        // BFS 层（按 root 距离）
        val depth = HashMap<String, Int>()
        val q = ArrayDeque<String>()
        result.rootPaths.values.flatten().flatMap { it.nodeIds.take(1) }.distinct().sorted().forEach {
            if (depth.putIfAbsent(it, 0) == null) q.addLast(it)
        }
        val adj = result.edges.filter { it.keepsAlive }.groupBy { it.fromNode }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            adj[n].orEmpty().forEach { e ->
                if (e.toNode in reachable && e.toNode !in depth) { depth[e.toNode] = depth[n]!! + 1; q.addLast(e.toNode) }
            }
        }
        val byDepth = depth.keys.groupBy { depth[it]!! }.toSortedMap()
        val layerGap = 150
        val nodeW = 190; val nodeH = 64
        val positions = HashMap<String, Pair<Int, Int>>()
        byDepth.forEach { (d, nodes) ->
            nodes.sorted().forEachIndexed { i, n ->
                positions[n] = (60 + d * layerGap) to (40 + i * 90)
            }
        }
        val height = maxOf(260, (byDepth.values.maxOfOrNull { it.size } ?: 1) * 90 + 80)
        val realWidth = maxOf(width, 60 + (byDepth.size) * layerGap)

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$realWidth" height="$height" viewBox="0 0 $realWidth $height" font-family="sans-serif">""")
        sb.append("""<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
            |<path d="M0,0 L7,3 L0,6 Z" fill="#555"/></marker>
            |<marker id="arrowWeak" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
            |<path d="M0,0 L7,3 L0,6 Z" fill="#b08900"/></marker></defs>""".trimMargin())

        for (e in result.edges.sortedWith(compareBy({ it.fromNode }, { it.toNode }, { it.edgeId }))) {
            val a = positions[e.fromNode] ?: continue
            val b = positions[e.toNode] ?: continue
            val (color, dash, marker) = when (e.kind) {
                "WEAK" -> Triple("#b08900", "6,4", "url(#arrowWeak)")
                "INTERNAL" -> Triple("#7b4bb0", "2,3", "url(#arrow)")
                else -> Triple("#555", "none", "url(#arrow)")
            }
            val opacity = if (e.keepsAlive) "1" else "0.55"
            sb.append("""<line x1="${a.first + nodeW}" y1="${a.second + nodeH / 2}" x2="${b.first}" y2="${b.second + nodeH / 2}"
                | stroke="$color" stroke-width="1.6" stroke-dasharray="$dash" marker-end="$marker" opacity="$opacity"/>""".trimMargin())
        }

        for ((id, pos) in positions.toSortedMap()) {
            val node = result.nodes.first { it.nodeId == id }
            val retained = result.retainedBytes[id] ?: 0L
            val dom = result.dominator[id]
            val fill = if (node.incomplete) "#fdecea" else "#eef4ff"
            val stroke = if (node.isRoot) "#0a7d33" else "#8aa6d8"
            sb.append("""<g data-stable-id="${esc(id)}">""")
            sb.append("""<rect x="${pos.first}" y="${pos.second}" width="$nodeW" height="$nodeH" rx="8"
                | fill="$fill" stroke="$stroke" stroke-width="1.5"/>""".trimMargin())
            sb.append("""<text x="${pos.first + 8}" y="${pos.second + 18}" font-size="12" font-weight="bold">${esc(node.className ?: "?")}</text>""")
            sb.append("""<text x="${pos.first + 8}" y="${pos.second + 34}" font-size="10" fill="#333">id=${esc(node.objectId)}</text>""")
            sb.append("""<text x="${pos.first + 8}" y="${pos.second + 48}" font-size="10" fill="#666">${esc(node.addressDisplay ?: "地址缺失")}</text>""")
            sb.append("""<text x="${pos.first + 8}" y="${pos.second + 60}" font-size="10" fill="#064">保留 $retained byte${if (node.unit != null) "（浅 ${esc(node.shallowRaw ?: "?")}）" else ""}</text>""")
            if (dom != null) {
                sb.append("""<text x="${pos.first + nodeW - 8}" y="${pos.second + 16}" text-anchor="end" font-size="9" fill="#900">dom:${esc(dom.substringAfter(':'))}</text>""")
            }
            if (node.isRoot) sb.append("""<circle cx="${pos.first + nodeW - 10}" cy="${pos.second + 8}" r="4" fill="#0a7d33"/>""")
            sb.append("</g>")
        }

        val unreachableCount = result.nodes.count { !it.reachable }
        sb.append("""<text x="8" y="$height" font-size="10" fill="#888">快照 ${esc(result.snapshotId)} · 解析器 ${esc(result.inputSummary.parserVersion)} · 不可达对象 $unreachableCount 个不进入支配树 · 弱边黄色虚线 · 内部边紫色点线${if (result.validity.valid) "" else " · 有效区间受限: ${esc(result.validity.invalidReason ?: "")}"}</text>""")
        sb.append("</svg>")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
