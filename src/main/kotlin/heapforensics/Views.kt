package heapforensics

/** HTML/SVG rendering. Every displayed value carries a stable identity. */
object Views {
    private const val CELL_W = 200
    private const val CELL_H = 72
    private const val PAD = 40

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    data class Layout(val x: Map<String, Int>, val y: Map<String, Int>, val width: Int, val height: Int, val depth: Map<String, Int>)

    private fun layout(snapshot: HeapSnapshot, reachable: Set<String>,
                       adjacency: Map<String, List<String>>, roots: List<String>): Layout {
        val depth = mutableMapOf<String, Int>()
        val q = ArrayDeque<String>()
        roots.forEach { if (it in reachable && depth.put(it, 0) == null) q.add(it) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            (adjacency[n] ?: emptyList()).forEach { m ->
                if (m in reachable && m !in depth) { depth[m] = depth[n]!! + 1; q.add(m) }
            }
        }
        val rows = reachable.groupBy { depth[it] ?: 0 }.toSortedMap()
        val x = mutableMapOf<String, Int>(); val y = mutableMapOf<String, Int>()
        var maxRows = 1
        rows.forEach { (d, nodes) ->
            maxRows = maxOf(maxRows, nodes.size)
            nodes.sorted().forEachIndexed { i, n ->
                x[n] = PAD + d * CELL_W
                y[n] = PAD + i * CELL_H
            }
        }
        return Layout(x, y, PAD * 2 + ((rows.keys.maxOrNull() ?: 0) + 1) * CELL_W, PAD * 2 + maxRows * CELL_H, depth)
    }

    fun graphSvg(snapshot: HeapSnapshot, result: AnalysisResult, snapshotLabel: String): String {
        val byId = snapshot.objects.associateBy { it.objectId }
        val className = snapshot.classes.associate { it.classId to it.name }
        val policyEdges = result.events.any { it.type == "WEAK_KEPT_ALIVE" }
        val traversal = snapshot.edges.filter {
            it.toId in result.reachable && it.fromId in result.reachable &&
                (it.kind != EdgeKind.WEAK || policyEdges)
        }
        val adjacency = traversal.groupBy({ it.fromId }, { it.toId })
        val roots = snapshot.roots.map { it.objectId }.filter { it in result.reachable }.sorted()
        val lay = layout(snapshot, result.reachable, adjacency, roots)
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${lay.width}\" height=\"${lay.height}\" ")
        sb.append("viewBox=\"0 0 ${lay.width} ${lay.height}\" font-family=\"sans-serif\" font-size=\"11\">")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#fafafa\"/>")
        // coordinate basis annotation
        sb.append("<text x=\"$PAD\" y=\"18\" fill=\"#666\">坐标基准：列=BFS深度 × ${CELL_W}px（自 root 起），行=层内稳定序号 × ${CELL_H}px，原点左上(0,0)；单位：像素</text>")
        // edges
        for (e in traversal) {
            val x1 = (lay.x[e.fromId] ?: 0) + 170; val y1 = (lay.y[e.fromId] ?: 0) + 28
            val x2 = lay.x[e.toId] ?: 0; val y2 = (lay.y[e.toId] ?: 0) + 28
            val (stroke, dash, arrow) = when (e.kind) {
                EdgeKind.STRONG -> Triple("#222", "", "marker-end=\"url(#aStrong)\"")
                EdgeKind.WEAK -> Triple("#d97706", "stroke-dasharray=\"6 4\"", "marker-end=\"url(#aWeak)\"")
                EdgeKind.INTERNAL -> Triple("#888", "stroke-dasharray=\"2 3\"", "marker-end=\"url(#aInternal)\"")
            }
            sb.append("<line x1=\"$x1\" y1=\"$y1\" x2=\"$x2\" y2=\"$y2\" stroke=\"$stroke\" stroke-width=\"1.5\" $dash $arrow/>")
            e.label?.let { if ((e.kind == EdgeKind.WEAK)) sb.append("<text x=\"${(x1 + x2) / 2}\" y=\"${(y1 + y2) / 2 - 3}\" fill=\"#d97706\">${esc(it)}</text>") }
        }
        sb.append("""<defs><marker id="aStrong" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#222"/></marker><marker id="aWeak" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#d97706"/></marker><marker id="aInternal" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#888"/></marker></defs>""")
        // nodes
        for (n in result.reachable.sorted()) {
            val o = byId[n]!!
            val x = lay.x[n]!!; val y = lay.y[n]!!
            val isRoot = n in roots
            sb.append("<g>")
            sb.append("<rect x=\"$x\" y=\"$y\" width=\"170\" height=\"50\" rx=\"6\" fill=\"${if (isRoot) "#e0f2fe" else "#fff"}\" stroke=\"${if (isRoot) "#0369a1" else "#999"}\"/>")
            sb.append("<text x=\"${x + 6}\" y=\"${y + 16}\" font-weight=\"bold\">${esc(n)}</text>")
            sb.append("<text x=\"${x + 6}\" y=\"${y + 30}\" fill=\"#444\">${esc(className[o.classId] ?: o.classId)}</text>")
            sb.append("<text x=\"${x + 6}\" y=\"${y + 44}\" fill=\"#666\">${o.sizeBytes} B · ${esc(o.address)}${if (o.incomplete) " · 不完整" else ""}</text>")
            sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun table(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\"><thead><tr>")
        headers.forEach { sb.append("<th>$it</th>") }; sb.append("</tr></thead><tbody>")
        rows.forEach { r ->
            sb.append("<tr>"); r.forEach { sb.append("<td>$it</td>") }; sb.append("</tr>")
        }
        return sb.append("</tbody></table>").toString()
    }

    fun dominatorTable(result: AnalysisResult, snapshot: HeapSnapshot): String {
        val rows = result.reachable.sorted().map { n ->
            val parent = result.idom[n] ?: Analysis.SUPER_ROOT
            listOf(esc("$n@snap=${snapshot.snapshotId}"), esc("$parent@snap=${snapshot.snapshotId}"),
                (result.retainedBytes[n] ?: 0).toString() + " B")
        }
        return table(listOf("对象（稳定身份：objectId@snapshotId）", "支配父级", "保留大小（单位：字节）"), rows)
    }

    fun rootPathTable(result: AnalysisResult): String {
        val rows = result.rootPaths.entries.sortedBy { it.key }.flatMap { (obj, paths) ->
            paths.map { p ->
                listOf(esc(obj), if (paths.size > 1) "<b>等长候选</b>" else "唯一",
                    esc(p.joinToString(" → ")),
                    (p.size - 1).toString() + " 跳")
            }
        }
        return table(listOf("对象", "判定", "root 路径（从 root 出发）", "长度（单位：跳）"), rows)
    }

    fun classDiffTable(diff: List<ClassGroupDiff>): String {
        val rows = diff.map { d ->
            val flag = when { d.renamed -> "改名"; d.added -> "新增"; d.removed -> "移除"; else -> "—" }
            listOf(esc(d.classId), esc(d.nameA ?: "∅"), esc(d.nameB ?: "∅"),
                "${d.countA} → ${d.countB}", "${d.bytesA} B → ${d.bytesB} B", flag)
        }
        return table(listOf("classId（稳定身份，非名称）", "名称 A", "名称 B", "实例数 A→B", "字节数 A→B（字节）", "标记"), rows)
    }
}
