package heaproom

object Views {
    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    const val BASIS_NOTE = "坐标基准 px；原点 top-left；x=depth×180+40；y=slot×72+40；时间基准 capturedAt(UTC)；大小派生单位 byte(B)"

    fun page(title: String, body: String): String = """<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><title>${esc(title)} · 堆影取证室</title>
<style>
body{font:14px/1.5 -apple-system,"PingFang SC",sans-serif;margin:0;background:#f6f7f9;color:#1c2330}
header{background:#182234;color:#fff;padding:10px 20px}
header a{color:#9fc4ff;margin-right:14px;text-decoration:none}
main{padding:18px 20px}
h1{font-size:18px;margin:0 0 12px}
h2{font-size:15px;margin:18px 0 8px}
table{border-collapse:collapse;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.08)}
th,td{border:1px solid #d9dee8;padding:4px 10px;text-align:left;vertical-align:top}
th{background:#eef2f8}
.id{font-family:ui-monospace,Menlo,monospace;font-size:12px;color:#31508a}
.warn{color:#9a6700}.err{color:#b3261e}.ok{color:#1e7a3c}.mut{color:#66707d}
.card{background:#fff;border:1px solid #d9dee8;border-radius:6px;padding:12px 14px;margin-bottom:14px}
.basis{font-size:12px;color:#5a6472;margin:6px 0 12px}
form{display:inline;margin-right:8px}
input,select,textarea,button{font:inherit;padding:3px 8px}
button{cursor:pointer}
svg{background:#fff;border:1px solid #d9dee8;border-radius:6px}
.tag{display:inline-block;border-radius:3px;padding:0 6px;font-size:12px;margin-left:6px}
.t-strong{background:#e6ebf5;color:#28436e}.t-weak{background:#fdecec;color:#b3261e}.t-internal{background:#eef1f4;color:#555}
.t-root{background:#e7f6ec;color:#1e7a3c}
.badge{font-size:12px;border:1px solid #c3ccd9;border-radius:3px;padding:0 6px;background:#f2f4f8}
</style></head><body>
<header><b>堆影取证室</b>
&nbsp; <a href="/">总览</a><a href="/events">事件流 / 历史回放</a><a href="/export">导出包</a>
<span style="margin-left:12px"><form method="post" action="/reset" style="display:inline">
<button type="submit">删除本地数据库并重放案例包</button></form></span></header>
<main><h1>${esc(title)}</h1>
$body
</main></body></html>"""

    fun home(snapshots: List<StoredSnapshot>, st: ReviewState): String {
        val sb = StringBuilder()
        sb.append("""<div class="card"><b>当前审阅视角：</b>
            |事件 ≤ <span class="id">#${st.atSeq}</span>　参数版本：<span class="id">${esc(st.param?.version ?: "（无）")}</span>
            |（弱引用策略 ${esc(st.param?.weakPolicy?.name ?: "未声明")}，节点预算 ${st.param?.nodeBudget ?: "—"}）
            |<form method="post" action="/param" style="margin-left:12px">
            |<select name="weakPolicy"><option>WEAK_IGNORED</option><option>WEAK_KEEP_ALIVE</option></select>
            |预算 <input name="nodeBudget" size="6" value="${st.param?.nodeBudget ?: 10000}">
            |<button>参数修订</button></form></div>""".trimMargin())
        if (st.conflicts.isNotEmpty()) {
            sb.append("""<div class="card err"><b>语义无解（冲突）：</b><br>${
                st.conflicts.joinToString("<br>") { esc(it) }
            }<br><span class="mut">互相冲突的人工决定均已保留；撤回其中一条后才能结案。</span></div>""")
        }
        sb.append("<h2>快照（不可变材料层）</h2><table><tr><th>稳定身份 snapshotId</th><th>解析器版本</th>" +
            "<th>采集时间基准</th><th>来源</th><th>证据页面</th></tr>")
        for (s in snapshots) {
            val id = esc(s.doc.snapshotId)
            sb.append("<tr><td class=\"id\">$id</td><td class=\"id\">${esc(s.doc.parserVersion)}</td>" +
                "<td>${esc(s.doc.capturedAt)}</td><td class=\"mut\">${esc(s.sourceLabel)}</td>" +
                "<td><a href=\"/snapshot/$id/graph\">对象图</a> · " +
                "<a href=\"/snapshot/$id/dominators\">支配父级</a> · " +
                "<a href=\"/snapshot/$id/retained\">保留大小</a> · " +
                "<a href=\"/snapshot/$id/paths\">root 路径</a> · " +
                "<a href=\"/snapshot/$id/diagnostics\">不完整块/诊断</a></td></tr>")
        }
        sb.append("</table>")
        if (snapshots.size >= 2) {
            val a = esc(snapshots[0].doc.snapshotId)
            val b = esc(snapshots[1].doc.snapshotId)
            sb.append("<h2>双快照类群差异</h2><div class=\"card\"><a href=\"/diff/$a/$b\">" +
                "$a → $b 类群差异（含改名识别）</a></div>")
        }
        sb.append("""<h2>导入 / 还原工作流</h2><div class="card">
<form method="post" action="/import" enctype="multipart/form-data">
上传快照 JSON：<input type="file" name="snapshot" accept="application/json">
来源标签 <input name="sourceLabel" size="18" value="manual-upload">
<button>导入</button></form>
<form method="post" action="/restore" enctype="multipart/form-data">
导入导出包（删库后恢复身份/顺序/决定）：<input type="file" name="exportFile" accept="application/json">
<button>导出包再导入</button></form>
<a class="badge" href="/export">下载当前数据库导出包</a></div>""")
        return page("审阅总览", sb.toString())
    }

    fun graphPage(s: StoredSnapshot, result: AnalysisResult, st: ReviewState): String {
        val doc = s.doc
        val byId = doc.objects.associateBy { it.objectId }
        val clsName = doc.classes.associate { it.classId to it.name }
        val reachable = result.reachable.toHashSet()
        val nodeMap = result.layout
        val maxDepth = nodeMap.values.maxOfOrNull { it.depth } ?: 0
        val maxSlot = nodeMap.values.groupBy { it.depth }.maxOfOrNull { it.value.size } ?: 1
        val w = (maxDepth + 1) * 180 + 40
        val h = maxSlot * 72 + 40
        val sb = StringBuilder()
        sb.append("""<div class="basis">$BASIS_NOTE<br>弱引用策略：<b>${result.weakPolicy}</b>
（参数版本 <span class="id">${esc(result.paramVersion)}</span>）；输入摘要 <span class="id">${esc(result.inputDigest)}</span>；
有效区间：事件 ≤ #${st.atSeq}；支配树只在可达图上构建。</div>""")
        sb.append("<svg width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">")
        sb.append("<defs><marker id=\"arr\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"3\" " +
            "orient=\"auto\"><path d=\"M0,0 L7,3 L0,6 z\" fill=\"#42506a\"/></marker></defs>")
        for (e in doc.edges) {
            if (e.from !in nodeMap || e.to !in nodeMap) continue
            val a = nodeMap[e.from]!!; val b = nodeMap[e.to]!!
            val (style, color, dash) = when (e.kind) {
                EdgeKind.STRONG -> Triple("stroke:#33415c;stroke-width:1.5", "#33415c", "")
                EdgeKind.WEAK -> Triple("stroke:#b3261e;stroke-width:1.3;stroke-dasharray:5 4", "#b3261e", "5 4")
                EdgeKind.INTERNAL -> Triple("stroke:#8b95a5;stroke-width:1", "#8b95a5", "2 3")
            }
            val mid = ((a.depth + b.depth) / 2) * 180 + 40
            sb.append("<path d=\"M${a.x + 104},${a.y} C$mid,${a.y} $mid,${b.y} ${b.x - 4},${b.y}\" " +
                "fill=\"none\" style=\"$style\" marker-end=\"url(#arr)\" data-identity=\"edge:${e.from}->${e.to}:${e.kind}:${esc(e.name)}\"/>")
        }
        for ((id, node) in nodeMap) {
            val o = byId[id]
            val fill = if (id in s.doc.roots.map { it.objectId }.toHashSet()) "#e7f6ec" else "#eef3fb"
            val rootTag = doc.roots.firstOrNull { it.objectId == id }?.let {
                "<tspan class=\"t-root\" fill=\"#1e7a3c\"> [ROOT ${it.rootType}]</tspan>" } ?: ""
            val dom = result.idom[id]
            sb.append("""<g data-identity="obj:$id">
<rect x="${node.x}" y="${node.y - 18}" width="108" height="40" rx="5" fill="$fill" stroke="#7b8aa3"/>
<text x="${node.x + 6}" y="${node.y - 3}" font-size="11" font-family="Menlo,monospace">$id$rootTag</text>
<text x="${node.x + 6}" y="${node.y + 12}" font-size="10" fill="#555">${esc(clsName[o?.classId] ?: "?")} · dom: ${esc(dom ?: "—")}</text>
</g>""")
        }
        sb.append("</svg>")
        val unreachable = result.unreachable
        if (unreachable.isNotEmpty())
            sb.append("<p class=\"mut\">不可达对象（不进入支配树，保留原始身份）：${
                unreachable.joinToString(", ") { "<span class=\"id\">$it</span>" }
            }<br>弱引用策略为 WEAK_IGNORED 时弱引用目标会出现在这里。</p>")
        sb.append("<p class=\"mut\">图例：实线=STRONG，红虚线=WEAK，灰点线=INTERNAL；节点与边的 data-identity 为稳定身份。</p>")
        return page("对象图 · ${s.doc.snapshotId}", sb.toString())
    }

    fun dominatorsPage(s: StoredSnapshot, r: AnalysisResult, st: ReviewState): String {
        val doc = s.doc
        val cls = doc.classes.associate { it.classId to it.name }
        val byId = doc.objects.associateBy { it.objectId }
        val sb = StringBuilder()
        sb.append("""<div class="basis">$BASIS_NOTE<br>仅列可达对象（${r.reachable.size} 个）；
不可达对象 ${r.unreachable.size} 个不建支配关系。输入摘要 <span class="id">${esc(r.inputDigest)}</span>，
参数版本 <span class="id">${esc(r.paramVersion)}</span>，有效区间：事件 ≤ #${st.atSeq}。</div>""")
        sb.append("<table><tr><th>稳定身份 objectId</th><th>地址（非身份）</th><th>类元数据</th>" +
            "<th>支配父级 idom</th><th>深度</th></tr>")
        val depth = r.rootPaths.mapValues { it.value.first().size - 1 }
        for (id in r.reachable) {
            val o = byId[id]
            sb.append("<tr data-identity=\"obj:$id\"><td class=\"id\">$id</td>" +
                "<td class=\"mut\">${esc(o?.address ?: "—")}</td>" +
                "<td>${esc(cls[o?.classId] ?: "?")} <span class=\"id\">(${esc(o?.classId ?: "?")})</span></td>" +
                "<td class=\"id\">${esc(r.idom[id] ?: "—")}</td><td>${depth[id]}</td></tr>")
        }
        sb.append("</table>")
        return page("支配父级 · ${s.doc.snapshotId}", sb.toString())
    }

    fun retainedPage(s: StoredSnapshot, r: AnalysisResult, st: ReviewState): String {
        val doc = s.doc
        val cls = doc.classes.associate { it.classId to it.name }
        val byId = doc.objects.associateBy { it.objectId }
        val sb = StringBuilder()
        sb.append("""<div class="basis">$BASIS_NOTE<br>保留大小 = 支配子树 shallow size 之和（派生单位 byte）；
无法转换的原始大小不计入并列入诊断。输入摘要 <span class="id">${esc(r.inputDigest)}</span>，
参数版本 <span class="id">${esc(r.paramVersion)}</span>，有效区间：事件 ≤ #${st.atSeq}。</div>""")
        sb.append("<table><tr><th>稳定身份 objectId</th><th>类</th><th>原始大小（保留）</th>" +
            "<th>保留大小 [byte]</th><th>分配点</th></tr>")
        for (id in r.reachable.sortedByDescending { r.retainedBytes[it] ?: 0L }) {
            val o = byId[id]
            val raw = o?.size
            val unpriced = id in r.unpricedObjects
            val rawCell = if (raw == null) "—"
            else "${esc(raw.raw)} ${esc(raw.unit)}" + if (unpriced) " <span class=\"warn\">⚠ 无法转换，留在原位</span>" else ""
            val site = o?.allocationSite?.let { "${esc(it.file)}:${it.line} ${esc(it.function)}" } ?: "—"
            sb.append("<tr data-identity=\"obj:$id\"><td class=\"id\">$id</td>" +
                "<td>${esc(cls[o?.classId] ?: "?")}</td><td>$rawCell</td>" +
                "<td>${if (unpriced) "<span class=\"mut\">未计入</span>" else (r.retainedBytes[id] ?: 0)}</td>" +
                "<td class=\"mut\">$site</td></tr>")
        }
        sb.append("</table>")
        sb.append(decisionForm(s.doc.snapshotId, st))
        return page("保留大小 · ${s.doc.snapshotId}", sb.toString())
    }

    fun pathsPage(s: StoredSnapshot, r: AnalysisResult, st: ReviewState): String {
        val sb = StringBuilder()
        sb.append("""<div class="basis">$BASIS_NOTE<br>列出从任意 root 出发的全部最短路径；
多个等长 root 路径全部保留。对任意对象“要求唯一结论”在多候选时返回语义无解。</div>""")
        sb.append("<table><tr><th>稳定身份 objectId</th><th>等长路径数</th><th>全部 root 路径（身份序列）</th></tr>")
        for (id in r.reachable.sortedBy { r.rootPaths[it]?.first()?.size ?: 0 }) {
            val paths = r.rootPaths[id].orEmpty()
            val multi = if (paths.size > 1) " <span class=\"warn\">⚠ ${paths.size} 条等长候选，无法唯一决定</span>" else ""
            sb.append("<tr data-identity=\"obj:$id\" style=\"vertical-align:top\"><td class=\"id\">$id</td>" +
                "<td>${paths.size}$multi</td><td>" +
                paths.joinToString("<br>") { p -> p.joinToString(" <span class=\"mut\">→</span> ") { "<span class=\"id\">$it</span>" } } +
                "</td></tr>")
        }
        sb.append("</table>")
        val unsolvable = r.rootPaths.filterKeys { it in r.reachable }
            .filterValues { it.size > 1 }.keys
        if (unsolvable.isNotEmpty())
            sb.append("<p class=\"err\">语义无解样例（uniqueLeakPath 不任选）：${
                unsolvable.joinToString(", ") { "<span class=\"id\">$it</span>" }
            }</p>")
        sb.append(decisionForm(s.doc.snapshotId, st))
        return page("root 路径 · ${s.doc.snapshotId}", sb.toString())
    }

    fun diagnosticsPage(s: StoredSnapshot, stored: List<Diagnostic>, r: AnalysisResult?): String {
        val sb = StringBuilder()
        sb.append("<h2>导入层诊断（带位置；空值/默认值不伪装成功）</h2>")
        sb.append("<table><tr><th>位置</th><th>级别</th><th>消息</th></tr>")
        for (d in stored) {
            val cls = if (d.severity == "error") "err" else "warn"
            sb.append("<tr><td class=\"id\">${esc(d.position)}</td><td class=\"$cls\">${esc(d.severity)}</td>" +
                "<td>${esc(d.message)}</td></tr>")
        }
        sb.append("</table>")
        sb.append("<h2>不完整块（原始块原样保留）</h2><table><tr><th>位置</th><th>原因</th><th>rawHex</th></tr>")
        for (b in s.doc.incompleteBlocks) {
            sb.append("<tr data-identity=\"block:${esc(b.position)}\"><td class=\"id\">${esc(b.position)}</td>" +
                "<td>${esc(b.reason)}</td><td class=\"id\">${esc(b.rawHex)}</td></tr>")
        }
        sb.append("</table>")
        sb.append("<h2>解析器版本与原始身份</h2><div class=\"card\">解析器版本：<span class=\"id\">" +
            "${esc(s.doc.parserVersion)}</span>；格式版本 ${s.doc.formatVersion}；" +
            "采集时间基准 ${esc(s.doc.capturedAt)}（UTC）。</div>")
        return page("诊断 · ${s.doc.snapshotId}", sb.toString())
    }

    private fun decisionForm(snapshotId: String, st: ReviewState): String {
        val mine = st.activeDecisions.filter { it.snapshotId == snapshotId }
        val sb = StringBuilder("<h2>人工决定（可修改决定层）</h2>")
        if (mine.isNotEmpty()) {
            sb.append("<table><tr><th>决定 id</th><th>候选</th><th>动作</th><th>理由</th><th>操作</th></tr>")
            for (d in mine) {
                val cls = if (d.action == "ACCEPT") "ok" else "err"
                sb.append("<tr data-identity=\"dec:${esc(d.decisionId)}\"><td class=\"id\">${esc(d.decisionId)}</td>" +
                    "<td class=\"id\">${esc(d.candidateId)}</td><td class=\"$cls\">${esc(d.action)}</td>" +
                    "<td>${esc(d.rationale)}</td><td><form method=\"post\" action=\"/decision/${esc(d.decisionId)}/retract\">" +
                    "<input type=\"hidden\" name=\"at\" value=\"${st.atSeq}\"><button>撤回（生成新事件）</button></form></td></tr>")
            }
            sb.append("</table>")
        }
        sb.append("""<form method="post" action="/decision" class="card">
候选 objectId <input name="objectId" size="12" value="obj-0016">
<select name="action"><option value="ACCEPT">接受 ACCEPT</option><option value="REJECT">否定 REJECT</option></select>
理由 <input name="rationale" size="46">
<input type="hidden" name="snapshotId" value="${esc(snapshotId)}">
<input type="hidden" name="at" value="${st.atSeq}"><button>提交决定（新事件，不改写历史）</button></form>""")
        return sb.toString()
    }

    fun diffPage(a: StoredSnapshot, b: StoredSnapshot, diffs: List<ClassGroupDiff>): String {
        val sb = StringBuilder()
        sb.append("""<div class="basis">类群身份 = classId（跨快照稳定）；B 中 renamedFrom 指向 A 的 classId 时记为改名。
保留大小为派生值，单位 byte；时间基准 capturedAt(UTC)。
A=${esc(a.doc.snapshotId)}（${esc(a.doc.capturedAt)}）→ B=${esc(b.doc.snapshotId)}（${esc(b.doc.capturedAt)}）。</div>""")
        sb.append("<table><tr><th>稳定身份 classId</th><th>A 名称</th><th>B 名称</th><th>变化</th>" +
            "<th>对象数 A→B</th><th>保留大小 A→B [byte]</th></tr>")
        for (d in diffs) {
            val cls = when (d.change) {
                "RENAMED" -> "warn"; "ADDED", "GROWN" -> "ok"; "REMOVED", "SHRUNK" -> "err"; else -> "mut"
            }
            sb.append("<tr data-identity=\"class:${esc(d.classIdentity)}\"><td class=\"id\">${esc(d.classIdentity)}</td>" +
                "<td>${esc(d.nameA ?: "—")}</td><td>${esc(d.nameB ?: "—")}</td>" +
                "<td class=\"$cls\">${esc(d.change)}</td><td>${d.countA} → ${d.countB}</td>" +
                "<td>${d.retainedA ?: "—"} → ${d.retainedB ?: "—"}</td></tr>")
        }
        sb.append("</table>")
        return page("类群差异", sb.toString())
    }

    fun eventsPage(events: List<EventRow>, st: ReviewState): String {
        val sb = StringBuilder()
        sb.append("""<div class="basis">每个事件记录父级依赖。点击“回到此时”恢复当时的参数版本、决定与可见结论；
计算结果按（输入摘要, 参数版本）缓存，历史不被最新计算覆盖。</div>""")
        if (st.atSeq != st.latestSeq)
            sb.append("""<div class="card warn"><b>历史视角：</b>当前展示事件 ≤ #${st.atSeq} 的状态。
<a href="/events?at=${st.latestSeq}">回到最新 #${st.latestSeq}</a></div>""")
        if (st.conflicts.isNotEmpty())
            sb.append("""<div class="card err">此视角存在互相冲突的人工决定（语义无解）：<br>${
                st.conflicts.joinToString("<br>") { esc(it) }
            }</div>""")
        sb.append("<table><tr><th>#</th><th>事件 id</th><th>类型</th><th>负载</th><th>父级</th><th>时间基准</th><th></th></tr>")
        for (e in events.reversed()) {
            val active = e.seq <= st.atSeq
            val muted = if (active) "" else " class=\"mut\""
            sb.append("<tr$muted data-identity=\"evt:#${e.seq}\"><td>#${e.seq}</td>" +
                "<td class=\"id\">${esc(e.eventId)}</td><td>${esc(e.kind)}</td>" +
                "<td style=\"font-family:Menlo,monospace;font-size:11px\">${esc(e.payload)}</td>" +
                "<td>${e.parents.joinToString(",") { "#$it" }}</td>" +
                "<td class=\"mut\">${esc(e.createdAt)}</td>" +
                "<td><a href=\"/events?at=${e.seq}\">回到此时</a></td></tr>")
        }
        sb.append("</table>")
        return page("事件流", sb.toString())
    }

    fun errorPage(title: String, kind: String, message: String, position: String? = null): String =
        page(title, "<div class=\"card err\"><b>$kind</b><br>${esc(message)}" +
            (position?.let { "<br>位置：<span class=\"id\">${esc(it)}</span>" } ?: "") + "</div>")
}
