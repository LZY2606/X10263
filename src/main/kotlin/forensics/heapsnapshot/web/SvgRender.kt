package forensics.heapsnapshot.web

import forensics.heapsnapshot.domain.EdgeKind
import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.engine.NodeView

/**
 * 纯 Kotlin SVG 渲染（业务计算不在浏览器）。
 * 坐标基准 svg-pixel@layout-v1 与有效区间随图输出；弱边虚线、内部边点划线、
 * 支配树边加粗；每个显示值带稳定身份（对象 id / 地址原值 / 类 id）。
 */
object SvgRender {
    fun graph(result: AnalysisResult, highlightObject: String? = null): String {
        val nodes = result.nodes
        val maxX = (nodes.maxOfOrNull { it.x } ?: 0) + 260
        val maxY = (nodes.maxOfOrNull { it.y } ?: 0) + 160
        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="$maxX" height="$maxY" viewBox="0 0 $maxX $maxY" """,
        )
        sb.append("""font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="11">""")
        sb.append(
            """<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
            |<path d="M0,0 L7,3 L0,6 Z" fill="#5b6472"/></marker>
            |<marker id="arrowDom" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
            |<path d="M0,0 L7,3 L0,6 Z" fill="#b45309"/></marker></defs>""".trimMargin(),
        )
        sb.append("""<rect width="100%" height="100%" fill="#f8fafc"/>""")
        sb.append(
            """<text x="12" y="20" fill="#334155">快照身份=${h(result.snapshotIdentity)} · 参数版本=v${result.paramsVersion} """ +
                """· 坐标基准=svg-pixel@layout-v1 · 大小单位=bytes · 可达对象=${result.reachableCount}</text>""",
        )

        val nodeById: Map<String, NodeView> = nodes.associateBy { it.id }
        result.edges.forEach { e ->
            val a = nodeById[e.from]; val b = nodeById[e.to]
            if (a == null || b == null || a.x < 0 || b.x < 0) return@forEach
            val (stroke, dash, marker, width) = when {
                e.treeEdge -> Quad("#b45309", "", "url(#arrowDom)", "2.4")
                e.kind == EdgeKind.WEAK -> Quad("#7c3aed", "6 4", "url(#arrow)", "1.2")
                e.kind == EdgeKind.INTERNAL -> Quad("#0891b2", "2 3", "url(#arrow)", "1.2")
                !e.activeForReachability -> Quad("#94a3b8", "2 4", "url(#arrow)", "1")
                else -> Quad("#475569", "", "url(#arrow)", "1.4")
            }
            sb.append(
                """<line x1="${a.x + 150}" y1="${a.y + 24}" x2="${b.x}" y2="${b.y + 24}" """ +
                    """stroke="$stroke" stroke-width="$width" stroke-dasharray="$dash" marker-end="$marker"/>""",
            )
            val mx = (a.x + 150 + b.x) / 2
            val my = (a.y + 24 + b.y + 24) / 2 - 4
            sb.append("""<text x="$mx" y="$my" fill="$stroke">${e.kind.name.lowercase()}${if (e.treeEdge) "/dom" else ""}</text>""")
        }

        nodes.filter { it.x >= 0 }.forEach { n ->
            val fill = when {
                highlightObject == n.id -> "#fef3c7"
                !n.complete -> "#fee2e2"
                else -> "#ffffff"
            }
            sb.append("""<g><a xlink:href="#" data-obj="${h(n.id)}">""")
            sb.append(
                """<rect x="${n.x}" y="${n.y}" width="170" height="70" rx="8" fill="$fill" stroke="#334155"/>""",
            )
            sb.append(
                """<text x="${n.x + 8}" y="${n.y + 18}" font-weight="700" fill="#0f172a">id=${h(n.id)}</text>""",
            )
            sb.append("""<text x="${n.x + 8}" y="${n.y + 34}" fill="#334155">class=${h(n.classId)} ${h(n.className)}</text>""")
            sb.append("""<text x="${n.x + 8}" y="${n.y + 48}" fill="#64748b">addr=${h(n.addressRaw)}</text>""")
            val retained = n.retainedSizeBytes?.let { "$it bytes" } ?: "${n.retainedStatus}"
            sb.append("""<text x="${n.x + 8}" y="${n.y + 62}" fill="#b45309">retained=$retained</text>""")
            sb.append("</a></g>")
        }

        // 图例：显式单位、坐标与有效区间说明
        sb.append(
            """<g transform="translate(12,${maxY - 92})">
            <rect width="520" height="80" rx="8" fill="#ffffff" stroke="#cbd5e1"/>
            <text x="10" y="20" fill="#334155">实线=strong(保活) 紫色虚线=weak(策略决定) 青色点线=internal 橙色加粗=支配树边</text>
            <text x="10" y="38" fill="#334155">有效区间：仅 root 可达对象出现在坐标平面；不可达对象保留在材料层（UNREACHABLE_EXCLUDED）。</text>
            <text x="10" y="56" fill="#334155">失效原因：UNCONVERTIBLE_* 表示大小/地址无法规范化，原值保留且不进入数值聚合。</text>
            <text x="10" y="72" fill="#334155">红色=不完整对象(INCOMPLETE)，黄色=当前审阅焦点；身份=原始对象 id，地址仅为观察值。</text>
            </g></svg>""",
        )
        return sb.toString()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun h(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
