package heapsnapshot.server

import heapsnapshot.domain.*
import heapsnapshot.analysis.AnalysisPolicy

/**
 * 服务端生成的对象图 SVG。坐标使用确定性分层布局（坐标基准：layer/index），
 * 边按强/弱/内部着色；支配树用虚线叠加；每个节点带稳定身份 data-ref。
 */
object SvgGraph {

    fun render(snapshot: RawSnapshot, result: AnalysisResult, policy: AnalysisPolicy): String {
        val reachable = result.reachableObjectIds.toSet()
        val nodeById = result.nodes.associateBy { it.objectId }
        val layers = HashMap<Int, MutableList<String>>()
        result.nodes.forEach { layers.getOrPut(it.rootDistance) { mutableListOf() }.add(it.objectId) }
        val pos = HashMap<String, Pair<Int, Int>>()
        val xGap = 190; val yGap = 120; val margin = 70
        val layerOrder = layers.keys.sorted()
        layerOrder.forEach { d ->
            val row = layers[d]!!.sorted()
            row.forEachIndexed { i, id -> pos[id] = margin + i * xGap to margin + d * yGap }
        }
        // 不可达对象放在右侧隔离列。
        snapshot.objects.filter { it.objectId !in reachable }.sortedBy { it.objectId }
            .forEachIndexed { i, o -> pos[o.objectId] = margin + (layerOrder.size) * xGap + 40 to margin + i * yGap }

        val width = (pos.values.maxOfOrNull { it.first } ?: 0) + 260
        val height = (pos.values.maxOfOrNull { it.second } ?: 0) + 160
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" font-family="ui-monospace,Menlo,monospace" font-size="11">""")
        sb.append("""<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#888"/></marker>
            |<marker id="arrowWeak" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#c9a227"/></marker></defs>""".trimMargin())
        sb.append("""<rect width="100%" height="100%" fill="#fafafa"/>""")

        val idomEdges = result.nodes.mapNotNull { n ->
            val p = n.idomParent?.localId?.removePrefix("obj:")?.takeIf { it != SnapshotRef.VIRTUAL_ROOT_LOCAL }
            if (p != null && p != SnapshotRef.VIRTUAL_ROOT_LOCAL) p to n.objectId else null
        }.toSet()

        snapshot.edges.forEach { e ->
            val a = pos[e.fromObjectId]; val b = pos[e.toObjectId]
            if (a == null || b == null) return@forEach
            val style = when (e.kind) {
                EdgeKind.STRONG -> EdgeStyle("#555", "", "url(#arrow)", 0.9)
                EdgeKind.WEAK -> EdgeStyle("#c9a227", "4 3", "url(#arrowWeak)", 0.9)
                EdgeKind.INTERNAL -> EdgeStyle("#2a7f9e", "2 2", "url(#arrow)", 0.7)
                EdgeKind.UNKNOWN -> EdgeStyle("#bbb", "1 2", "url(#arrow)", 0.4)
            }
            val color = style.color; val dash = style.dash; val marker = style.marker; val opacity = style.opacity
            val dominance = (e.fromObjectId to e.toObjectId) in idomEdges
            sb.append("""<line x1="${a.first}" y1="${a.second}" x2="${b.first}" y2="${b.second}" stroke="$color" stroke-width="${if (dominance) 2.4 else 1.2}" stroke-dasharray="$dash" marker-end="$marker" opacity="$opacity"><title>${e.edgeId} ${e.kind}${e.label?.let { " ($it)" } ?: ""}${if (dominance) " [idom]" else ""}</title></line>""")
        }
        // 虚拟 root。
        val roots = snapshot.roots.map { it.objectId }.distinct()
        roots.forEach { rid ->
            val b = pos[rid] ?: return@forEach
            sb.append("""<line x1="${margin - 40}" y1="20" x2="${b.first}" y2="${b.second - 18}" stroke="#7a3e9d" stroke-width="1.4" stroke-dasharray="6 3" marker-end="url(#arrow)"><title>virtual-root -> $rid</title></line>""")
        }
        sb.append("""<rect x="${margin - 110}" y="6" width="74" height="28" rx="6" fill="#7a3e9d"/><text x="${margin - 73}" y="25" fill="white" text-anchor="middle">GC ROOTS</text>""")

        snapshot.objects.forEach { o ->
            val (x, y) = pos[o.objectId] ?: return@forEach
            val n = nodeById[o.objectId]
            val cls = snapshot.classById(o.classId)
            val fill = when {
                o.incomplete -> "#f7e1e1"
                n?.leakCandidate == true -> "#fde9c8"
                o.objectId !in reachable -> "#ececec"
                else -> "#e8f1fb"
            }
            val retained = n?.retainedBytes?.let { "%.0f".format(it) } ?: "-"
            val incMark = if (n?.retainedIncomplete == true) "~" else ""
            sb.append("""<g data-ref="${snapshot.snapshotId}/obj:${o.objectId}"><rect x="${x - 70}" y="${y - 20}" width="140" height="42" rx="7" fill="$fill" stroke="#444"/>""")
            sb.append("""<text x="$x" y="${y - 4}" text-anchor="middle" font-weight="600">${o.objectId}</text>""")
            sb.append("""<text x="$x" y="${y + 9}" text-anchor="middle" fill="#555">${cls?.name?.substringAfterLast('.') ?: "?"}</text>""")
            sb.append("""<text x="$x" y="${y + 20}" text-anchor="middle" fill="#a05a00">ret ${incMark}${retained} ${snapshot.header.sizeUnitCanonical}</text></g>""")
        }
        sb.append("""<text x="10" y="${height - 26}" fill="#666">坐标基准：layer/index（分层=到 root 的保活跳数）；地址基准：${snapshot.header.addressUnit}；时间基准：${snapshot.header.timeBasis} ${snapshot.header.takenAtRaw}</text>""")
        sb.append("""<text x="10" y="${height - 10}" fill="#666">弱引用保活=${policy.weakKeepsAlive} (${policy.policyVersion})；实线=强 黄虚线=弱 蓝点线=内部；粗线=支配边；橙=泄漏候选 红=不完整 灰=当前策略不可达</text>""")
        sb.append("</svg>")
        return sb.toString()
    }

    private data class EdgeStyle(val color: String, val dash: String, val marker: String, val opacity: Double)
}
