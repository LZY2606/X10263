package heaplab.web

import heaplab.analysis.AnalysisOutcome
import heaplab.analysis.Analyzer
import heaplab.analysis.Report
import heaplab.model.HeapSnapshot
import heaplab.store.Decision
import heaplab.store.StoredEvent

object Web {

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun page(title: String, body: String) = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<title>${esc(title)}</title><style>
body{font-family:ui-monospace,Menlo,monospace;margin:24px;background:#101418;color:#d8e0e8}
h1{font-size:20px;color:#7fd4ff} h2{font-size:15px;color:#9fd8a8;margin-top:28px;border-bottom:1px solid #2a3340;padding-bottom:4px}
table{border-collapse:collapse;font-size:12px} td,th{border:1px solid #2a3340;padding:3px 8px;text-align:left}
th{background:#1a2230}.id{color:#ffd479}.weak{stroke:#c678dd;stroke-dasharray:5 4}.internal{stroke:#56b6c2;stroke-dasharray:2 3}
.strong{stroke:#98c379}.meta{color:#8a97a8;font-size:11px}.warn{color:#e5c07b}.err{color:#e06c75}
a{color:#7fd4ff}.box{background:#161c26;border:1px solid #2a3340;border-radius:6px;padding:10px 14px;margin:8px 0}
</style></head><body>$body</body></html>"""

    /** SVG object graph. Layout: layers by BFS depth from roots; coordinates in px, origin top-left. */
    fun graphSvg(s: HeapSnapshot, report: Report?): String {
        val depth = mutableMapOf<String, Int>()
        val adj = s.edges.groupBy { it.fromId }.mapValues { (_, es) -> es.map { it.toId } }
        val dq = ArrayDeque<Pair<String, Int>>()
        s.roots.forEach { if (depth.put(it.objectId, 0) == null) dq.addLast(it.objectId to 0) }
        while (dq.isNotEmpty()) {
            val (cur, d) = dq.removeFirst()
            adj[cur].orEmpty().forEach { n -> if (depth.put(n, d + 1) == null) dq.addLast(n to d + 1) }
        }
        s.objects.forEach { if (it.objectId !in depth) depth[it.objectId] = (depth.values.maxOrNull() ?: 0) + 1 }
        val byDepth = depth.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        val pos = mutableMapOf<String, Pair<Int, Int>>()
        byDepth.forEach { (d, ids) -> ids.sorted().forEachIndexed { i, id -> pos[id] = 140 + d * 170 to 60 + i * 90 } }
        val w = ((byDepth.lastKey() + 2) * 170 + 80); val h = ((byDepth.values.maxOf { it.size } + 1) * 90 + 60)
        val rows = report?.rows?.associateBy { it.objectId }
        val sb = StringBuilder()
        sb.append("""<svg width="$w" height="$h" xmlns="http://www.w3.org/2000/svg" style="background:#0c0f14;border:1px solid #2a3340">""")
        sb.append("""<text x="10" y="16" fill="#8a97a8" font-size="10">坐标基准: px, 原点左上; 层 = 距 root 的最短边数; 时间基准: ${esc(s.capturedAt)}</text>""")
        for (e in s.edges.sortedWith(compareBy({ it.fromId }, { it.toId }))) {
            val (x1, y1) = pos[e.fromId] ?: continue; val (x2, y2) = pos[e.toId] ?: continue
            val cls = e.kind.name.lowercase()
            sb.append("""<line x1="${x1 + 46}" y1="${y1 + 16}" x2="${x2}" y2="${y2 + 16}" class="$cls" stroke-width="1.5"/>""")
            sb.append("""<text x="${(x1 + x2) / 2 + 20}" y="${(y1 + y2) / 2 + 12}" fill="#5a6a7d" font-size="9">${esc(e.kind.name)} ${esc(e.field)}</text>""")
        }
        val rootIds = s.roots.map { it.objectId }.toSet()
        for (o in s.objects.sortedBy { it.objectId }) {
            val (x, y) = pos[o.objectId] ?: continue
            val r = rows?.get(o.objectId)
            val fill = when {
                o.incomplete -> "#4a3a1a"
                r?.reachable == false -> "#3a2028"
                o.objectId in rootIds -> "#1d3a2a"
                else -> "#16202e"
            }
            sb.append("""<g><rect x="$x" y="$y" width="46" height="32" rx="5" fill="$fill" stroke="#4a5a70"/>""")
            sb.append("""<text x="${x + 23}" y="${y + 14}" fill="#ffd479" font-size="10" text-anchor="middle">${esc(o.objectId)}</text>""")
            val sub = buildString {
                append(o.size.raw).append(o.size.unit)
                if (r?.retainedBytes != null) append(" R=").append(r.retainedBytes).append("B")
                if (o.incomplete) append(" ⚠")
            }
            sb.append("""<text x="${x + 23}" y="${y + 26}" fill="#9fd8a8" font-size="8" text-anchor="middle">${esc(sub)}</text></g>""")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    fun reportSection(report: Report): String {
        val sb = StringBuilder()
        sb.append("<h2>对象表（稳定身份 = objectId；单位 = bytes；支配树仅构建于可达图）</h2><table>")
        sb.append("<tr><th>objectId</th><th>address(原始,非身份)</th><th>class</th><th>shallow</th><th>retained</th><th>支配父级</th><th>root 路径(全部等长候选)</th><th>allocation site</th><th>状态</th></tr>")
        for (r in report.rows) {
            val paths = if (r.rootPaths.isEmpty()) "—"
            else r.rootPaths.joinToString("<br>") { p -> p.joinToString(" → ") { "<span class='id'>$it</span>" } }
            val st = buildString {
                if (!r.reachable) append("<span class='warn'>不可达</span>")
                if (r.incomplete) append(" <span class='warn'>不完整块</span>")
                if (r.reachable && !r.incomplete) append("ok")
            }
            sb.append("<tr><td class='id'>${esc(r.objectId)}</td><td>${esc(r.address)}</td><td>${esc(r.className)}</td>")
            sb.append("<td>${r.shallowBytes?.let { "${it} B" } ?: "<span class='err'>无法转换(原值保留)</span>"}</td>")
            sb.append("<td>${r.retainedBytes?.let { "${it} B" } ?: "—"}</td>")
            sb.append("<td class='id'>${esc(r.dominatorParent ?: "—(root/入口)")}</td><td>$paths</td>")
            sb.append("<td class='meta'>${esc(r.allocationSite ?: "—")}</td><td>$st</td></tr>")
        }
        sb.append("</table>")
        sb.append("<div class='box meta'>有效区间: ${esc(report.validFrom)} → ${esc(report.validUntil)}；失效原因: ${report.invalidationReasons.joinToString("；")}；输入摘要: ${report.inputDigest.take(16)}…；参数版本: ${esc(report.params.version)}；弱引用策略: ${report.params.weakPolicy}</div>")
        sb.append("<h2>判定依据（机器可读事件）</h2><table><tr><th>type</th><th>detail</th></tr>")
        for (e in report.events)
            sb.append("<tr><td>${esc(e.type)}</td><td class='meta'>${esc(e.detail.entries.joinToString("; ") { "${it.key}=${it.value}" })}</td></tr>")
        sb.append("</table>")
        return sb.toString()
    }

    fun classDiffSection(a: HeapSnapshot, b: HeapSnapshot): String {
        val d = Analyzer.classDiff(a, b)
        fun rows(gs: List<heaplab.analysis.ClassGroup>) =
            gs.joinToString("") { "<tr><td>${esc(it.className)}</td><td>${it.count}</td><td>${it.shallowBytes} B</td></tr>" }
        return """<h2>类群差异：${esc(a.snapshotId)} vs ${esc(b.snapshotId)}（按类名分组；类改名 = 移除+新增）</h2>
<div class="box"><b>仅在 ${esc(a.snapshotId)}</b><table><tr><th>class</th><th>count</th><th>shallow</th></tr>${rows(d.onlyInA)}</table></div>
<div class="box"><b>仅在 ${esc(b.snapshotId)}</b><table><tr><th>class</th><th>count</th><th>shallow</th></tr>${rows(d.onlyInB)}</table></div>
<div class="box"><b>两者共有</b><table><tr><th>class</th><th>count</th><th>shallow</th></tr>${rows(d.inBoth)}</table></div>"""
    }

    fun eventsSection(events: List<StoredEvent>, currentId: String?): String {
        val sb = StringBuilder("<h2>事件链（点击可回到任一历史事件，恢复当时的依赖版本与可见结论）</h2><table>")
        sb.append("<tr><th>seq</th><th>eventId</th><th>type</th><th>params</th><th>digest</th><th>parent</th><th></th></tr>")
        for (e in events) {
            val mark = if (e.eventId == currentId) " ◀ 当前" else ""
            sb.append("<tr><td>${e.seq}</td><td class='id'>${esc(e.eventId)}$mark</td><td>${esc(e.type)}</td>")
            sb.append("<td>${esc(e.paramsVersion)}</td><td class='meta'>${e.inputDigest.take(12)}…</td>")
            sb.append("<td class='meta'>${esc(e.parentEventId ?: "—")}</td>")
            sb.append("<td><a href='/revert/${e.eventId}'>回到此事件</a></td></tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    fun decisionsSection(decisions: List<Decision>, conflicts: Map<Pair<String, String>, List<Decision>>): String {
        val sb = StringBuilder("<h2>人工决定（可修改层，与不可变材料分层）</h2><table>")
        sb.append("<tr><th>decisionId</th><th>analysisEvent</th><th>object</th><th>verdict</th><th>rationale</th></tr>")
        for (d in decisions)
            sb.append("<tr><td class='id'>${esc(d.decisionId)}</td><td class='meta'>${esc(d.analysisEventId)}</td><td class='id'>${esc(d.targetObjectId)}</td><td>${esc(d.verdict)}</td><td>${esc(d.rationale)}</td></tr>")
        sb.append("</table>")
        if (conflicts.isNotEmpty()) {
            sb.append("<div class='box err'>冲突决定：")
            conflicts.forEach { (k, ds) -> sb.append("分析事件 ${esc(k.first)} / 对象 <span class='id'>${esc(k.second)}</span> 存在 ${ds.size} 条互相冲突的决定；") }
            sb.append("</div>")
        }
        return sb.toString()
    }

    fun outcomeSection(outcome: AnalysisOutcome): String = when (outcome) {
        is AnalysisOutcome.Success -> reportSection(outcome.report)
        is AnalysisOutcome.BudgetExhausted ->
            "<div class='box err'>结果类型 = BudgetExhausted：${esc(outcome.detail)}，已用 ${outcome.stepsUsed} / 预算 ${outcome.budget} 步</div>"
        is AnalysisOutcome.CorruptFormat ->
            "<div class='box err'>结果类型 = CorruptFormat：" + outcome.diagnostics.joinToString("；") { "[${it.position}] ${it.code}: ${it.message}" } + "</div>"
        is AnalysisOutcome.SemanticUnresolvable ->
            "<div class='box warn'>结果类型 = SemanticUnresolvable：" + outcome.diagnostics.joinToString("；") { "[${it.position}] ${it.code}: ${it.message}" } + "</div>"
    }
}
