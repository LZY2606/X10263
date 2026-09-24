package heapforensics.web

import heapforensics.core.EdgeKind
import heapforensics.core.HeapSnapshot
import heapforensics.core.GraphAnalysis

/**
 * 服务端 SVG：布局完全确定性（root BFS 深度定层、层内 id 定序）。
 * 每个图形元素都带稳定身份 data-object-id；露出单位、坐标基准、时间基准与失效标记。
 */
object Svg {

    private const val W = 200
    private const val H = 104
    private const val PAD_X = 60
    private const val PAD_Y = 90

    fun render(snap: HeapSnapshot, a: GraphAnalysis.FullAnalysis): String {
        val reachable = a.reachability.reachable.toSet()
        val depth = HashMap<String, Int>()
        for (id in a.reachability.visitOrder) {
            if (snap.roots.any { it.objectId == id }) depth[id] = 0
        }
        // BFS 深度（确定性）
        val queue = ArrayDeque(depth.keys.toList().sorted())
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur] ?: continue
            for (e in a.reachability.keptEdges.filter { it.fromId == cur }) {
                if (e.toId !in depth && e.toId in reachable) { depth[e.toId] = d + 1; queue.add(e.toId) }
            }
        }
        val layers = a.reachability.reachable.groupBy { depth[it] ?: 0 }
            .toSortedMap().mapValues { (_, v) -> v.sorted() }
        val unreachable = a.reachability.unreachable
        val unreachableRow = (layers.keys.maxOrNull() ?: 0) + 1

        val pos = HashMap<String, Pair<Int, Int>>()
        var maxRows = 0
        for ((layer, ids) in layers) {
            ids.forEachIndexed { row, id -> pos[id] = layer to row }
            maxRows = maxOf(maxRows, ids.size)
        }
        unreachable.forEachIndexed { i, id -> pos[id] = unreachableRow to i }
        maxRows = maxOf(maxRows, unreachable.size)

        val cols = unreachableRow + 1
        val width = PAD_X * 2 + (cols - 1).coerceAtLeast(0) * W + 180
        val height = PAD_Y * 2 + (maxRows - 1).coerceAtLeast(0) * H + 40
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" font-family="ui-monospace,Menlo,monospace" font-size="11">""")
        sb.append("""<defs>
            <marker id="ar" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#334"/></marker>
            <marker id="arw" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#c77b0a"/></marker>
            <marker id="ari" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#2563eb"/></marker>
            <marker id="ard" markerWidth="9" markerHeight="9" refX="8" refY="3.5" orient="auto"><path d="M0,0 L8,3.5 L0,7 z" fill="#15803d"/></marker>
            <marker id="arg" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" fill="#9aa0a6"/></marker>
        </defs>""")
        sb.append("""<rect width="$width" height="$height" fill="#fbfcfd"/>""")
        sb.append(header(width, snap, a))

        fun positionOf(id: String): Pair<Int, Int> {
            val (l, r) = pos[id]!!
            return PAD_X + l * W to PAD_Y + r * H
        }

        // 层背景
        for (layer in 0 until cols) {
            val x = PAD_X + layer * W - 12
            sb.append("""<rect x="$x" y="${PAD_Y - 26}" width="176" height="${(maxRows - 1) * H + 86}"
                fill="${if (layer == unreachableRow) "#f3f4f6" else "#f7f9fc"}" stroke="#e5e7eb"/>""")
            val label = if (layer == unreachableRow) "不可达（按当前策略）" else "root 深度 $layer"
            sb.append("""<text x="${x + 8}" y="${PAD_Y - 12}" fill="#6b7280">$label</text>""")
        }

        // 丢弃边（当前策略下不保活）
        for (e in a.reachability.droppedEdges) {
            if (pos.containsKey(e.fromId) && pos.containsKey(e.toId)) edge(sb, e.fromId, e.toId, "#9aa0a6", "arg", "4,4", 1.0, e.fieldName, ::positionOf, true)
        }
        // 保留边
        for (e in a.reachability.keptEdges) {
            if (pos.containsKey(e.fromId) && pos.containsKey(e.toId)) {
                val (color, marker, dash) = when (e.kind) {
                    EdgeKind.STRONG -> Triple("#374151", "ar", "none")
                    EdgeKind.WEAK -> Triple("#c77b0a", "arw", "7,5")
                    EdgeKind.INTERNAL -> Triple("#2563eb", "ari", "2,4")
                }
                edge(sb, e.fromId, e.toId, color, marker, dash, 1.3, e.fieldName, ::positionOf, false)
            }
        }
        // 支配父级叠加（绿色粗线）
        for ((child, parent) in a.dominators.idom.entries.sortedBy { it.key }) {
            if (parent != null && pos.containsKey(child) && pos.containsKey(parent)) {
                edge(sb, parent, child, "#15803d", "ard", "none", 2.6, "idom", ::positionOf, false, 6)
            }
        }
        // 节点
        val classOf = snap.classIndex
        val rootKinds = snap.roots.associate { it.objectId to it.kind.name }
        for (id in (snap.objects.map { it.objectId }).sorted()) {
            val obj = snap.objectIndex[id]!!
            val (x, y) = positionOf(id)
            val hue = (obj.classId.hashCode() and 0x7fffffff) % 360
            val fill = "hsl($hue,42%,93%)"
            val stroke = when {
                obj.incomplete -> "#dc2626"
                id !in reachable -> "#9ca3af"
                else -> "hsl($hue,45%,55%)"
            }
            val dashAttr = if (obj.incomplete) " stroke-dasharray='5,3'" else ""
            sb.append("""<g class="node" data-object-id="$id">""")
            sb.append("""<rect x="$x" y="$y" width="168" height="70" rx="8" fill="$fill" stroke="$stroke" stroke-width="2"$dashAttr/>""")
            val retained = a.dominators.retained[id]
            val retText = retained?.let {
                val n = if (it.valid) "${it.exactBytes} B" else "≥ ${it.lowerBoundBytes} B ⚠"
                "ret $n"
            } ?: "ret n/a"
            text(sb, x + 8, y + 17, id, "#111827", true)
            text(sb, x + 8, y + 32, (classOf[obj.classId]?.name?.substringAfterLast('.') ?: "?${obj.classId}"), "#374151")
            text(sb, x + 8, y + 46, "${obj.sizeBytes} B · ${obj.address}", "#4b5563")
            text(sb, x + 8, y + 60, (rootKinds[id]?.let { "$it · " } ?: "") + retText, if (retained?.valid == false) "#b91c1c" else "#4b5563")
            sb.append("<title>${esc(id)} ${esc(classOf[obj.classId]?.name ?: "?")} ${esc(obj.address)}${if (obj.incomplete) " [incomplete]" else ""}</title>")
            sb.append("</g>")
        }
        sb.append(legend(width - 10, height - 12))
        sb.append("</svg>")
        return sb.toString()
    }

    private fun header(width: Int, snap: HeapSnapshot, a: GraphAnalysis.FullAnalysis): String {
        val p = a.params
        return """
        <text x="$PAD_X" y="24" font-size="14" font-weight="bold" fill="#111827">
          对象图 · ${esc(snap.snapshotId)} · parser ${esc(snap.parserVersion)}
        </text>
        <text x="$PAD_X" y="44" fill="#6b7280">
          时间基准: ${esc(snap.capturedAt)} (epoch=${snap.epochMillis} ms)
          · 单位: 字节(B) · 坐标基准: root BFS 深度层，列距 $W px，行距 $H px
        </text>
        <text x="$PAD_X" y="62" fill="#6b7280">
          策略 v${p.version}: weakKeepAlive=${p.weakKeepAlive}, internalKeepAlive=${p.internalKeepAlive}
          · 绿粗线=支配父级 · 实线=强 · 橙虚线=弱 · 蓝点线=内部 · 灰虚线=当前策略丢弃
        </text>
        """.trimIndent()
    }

    private fun legend(x: Int, y: Int): String =
        """<text x="$x" text-anchor="end" y="$y" fill="#9ca3af">身份=objectId（地址仅展示，不作稳定身份）</text>"""

    private fun edge(
        sb: StringBuilder, from: String, to: String, color: String, marker: String, dash: String,
        width: Double, label: String?, positionOf: (String) -> Pair<Int, Int>, dropped: Boolean, bend: Int = 0,
    ) {
        val (x1, y1) = positionOf(from); val (x2, y2) = positionOf(to)
        val sx = x1 + 168; val sy = y1 + 35; val tx = x2; val ty = y2 + 35
        val dashAttr = if (dash == "none") "" else "stroke-dasharray='$dash'"
        if (sx == tx || dropped) {
            val c1x = sx + 30 + bend; val c1y = sy; val c2x = tx - 30 - bend; val c2y = ty
            sb.append("""<path d="M$sx,$sy C$c1x,$c1y $c2x,$c2y $tx,$ty" fill="none"
                stroke="$color" stroke-width="$width" $dashAttr marker-end="url(#$marker)" opacity="${if (dropped) 0.5 else 0.85}"/>""")
        } else {
            val c1x = sx + 30 + bend; val c2x = tx - 30 - bend
            sb.append("""<path d="M$sx,$sy C$c1x,$sy $c2x,$ty $tx,$ty" fill="none"
                stroke="$color" stroke-width="$width" $dashAttr marker-end="url(#$marker)" opacity="0.85"/>""")
        }
        if (label != null) {
            sb.append("""<text x="${(sx + tx) / 2 + bend}" y="${(sy + ty) / 2 - 3}" fill="$color" font-size="9">${esc(label)}</text>""")
        }
    }

    private fun text(sb: StringBuilder, x: Int, y: Int, s: String, color: String, bold: Boolean = false) {
        val weight = if (bold) "font-weight='bold'" else ""
        val clip = if (s.length > 27) s.substring(0, 26) + "…" else s
        sb.append("""<text x="$x" y="$y" fill="$color" $weight>${esc(clip)}</text>""")
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
