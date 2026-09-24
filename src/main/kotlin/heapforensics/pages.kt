package heapforensics

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun layout(title: String, body: String) = """<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><title>${esc(title)} · 堆影取证室</title>
<style>
body{font-family:system-ui,-apple-system,"PingFang SC",sans-serif;margin:0;background:#f4f5f7;color:#222}
header{background:#1f2933;color:#fff;padding:10px 20px;display:flex;gap:18px;align-items:baseline}
header a{color:#9fd3ff;text-decoration:none} header h1{font-size:17px;margin:0}
main{padding:18px 24px;max-width:1180px;margin:auto}
table{border-collapse:collapse;width:100%;background:#fff;margin:10px 0}
th,td{border:1px solid #dde1e6;padding:5px 9px;font-size:13px;text-align:left;vertical-align:top}
th{background:#eef1f4}
.card{background:#fff;border:1px solid #dde1e6;border-radius:8px;padding:14px 16px;margin:12px 0}
.tag{display:inline-block;padding:1px 7px;border-radius:9px;font-size:11px;background:#e2e8f0;margin-right:4px}
.ok{background:#c8f0d0}.warn{background:#fdeec8}.err{background:#f8d0d0}.info{background:#d6e6fb}
code,pre{font-family:ui-monospace,Menlo,monospace;font-size:12px}
pre{background:#0f1720;color:#cfe3ff;padding:10px;border-radius:6px;overflow:auto}
.muted{color:#788}
a{color:#175cd3}
input,select,textarea{font-size:13px;padding:4px 6px}
.btn{padding:4px 12px;border:1px solid #175cd3;background:#175cd3;color:#fff;border-radius:5px;cursor:pointer}
</style></head><body>
<header><h1>堆影取证室</h1>
<a href="/">审阅页</a><a href="/events">事件流</a><a href="/diff">类群差异</a><a href="/export">导出档案</a>
</header><main>$body</main></body></html>"""

fun diagBadge(sev: String) = when (sev) {
    "ERROR" -> """<span class="tag err">ERROR</span>"""
    else -> """<span class="tag warn">WARNING</span>"""
}

fun diagnosticsBlock(diags: List<Diagnostic>): String {
    if (diags.isEmpty()) return """<p class="muted">无诊断。所有原始值均可规范化。</p>"""
    val rows = diags.joinToString("") {
        "<tr><td>${diagBadge(it.severity)}</td><td><code>${esc(it.position)}</code></td><td>${esc(it.message)}</td></tr>"
    }
    return """<table><tr><th>级别</th><th>位置</th><th>说明（原值保留在原位置）</th></tr>$rows</table>"""
}

fun snapshotDetailPage(raw: RawSnapshot): String {
    val d = derive(raw)
    val classes = raw.classes.joinToString("") {
        "<tr><td><code>${it.classId}</code></td><td>${esc(it.name)}</td><td>${it.superClassId ?: "-"}</td><td>${it.classLoaderId ?: "-"}</td><td><code>${esc(it.metadata.toString())}</code></td></tr>"
    }
    val objects = d.objects.values.joinToString("") { o ->
        val site = o.raw.allocationSite?.let { "${it.file}:${it.line} ${it.method}" } ?: "-"
        "<tr><td><code>${o.raw.objectId}</code></td><td><code>${esc(o.raw.address)}</code></td>" +
        "<td>${o.raw.classId ?: "<span class='tag err'>缺失</span>"}</td>" +
        "<td><code>${o.raw.sizeBytes ?: "缺失"}</code></td><td>${o.sizeBytes?.let { "$it B" } ?: "<span class='tag err'>未规范化</span>"}</td>" +
        "<td>${esc(site)}</td><td>${if (o.raw.incomplete) "<span class='tag warn'>incomplete</span>" else ""}</td></tr>"
    }
    val edges = d.edges.joinToString("") { (e, k) ->
        "<tr><td><code>${e.from}</code></td><td><code>${e.to}</code></td><td>${k?.name ?: "<span class='tag err'>${esc(e.kind)}</span>"}</td><td>${esc(e.label ?: "")}</td></tr>"
    }
    val roots = d.roots.joinToString("") { (r, t) ->
        "<tr><td><code>${r.objectId}</code></td><td>${t?.name ?: "<span class='tag warn'>${esc(r.rootType)}</span>"}</td></tr>"
    }
    val chunks = raw.incompleteChunks.joinToString("") {
        "<tr><td>${it.offset}</td><td>${it.length} B</td><td>${esc(it.reason)}</td></tr>"
    }
    return layout("快照 ${raw.snapshotId}", """
<div class="card"><h2>快照 <code>${raw.snapshotId}</code></h2>
<p>解析器版本 <code>${raw.parserVersion}</code> · 采集时间基准 epoch ms (UTC): <code>${raw.capturedAtEpochMs}</code> · 来源 <code>${esc(raw.source)}</code><br>
<a href="/snapshots/${raw.snapshotId}/graph.svg">对象图 SVG</a> ·
<a href="/snapshots/${raw.snapshotId}/analysis">支配树 / 保留大小 / root 路径</a></p></div>
<div class="card"><h3>类元数据（${raw.classes.size}）</h3>
<table><tr><th>classId</th><th>名称</th><th>super</th><th>classLoader</th><th>metadata</th></tr>$classes</table></div>
<div class="card"><h3>对象（${raw.objects.size}）— 原始 id / 地址 / 尺寸原样保留</h3>
<table><tr><th>objectId</th><th>地址(hex)</th><th>classId</th><th>sizeBytes 原始</th><th>规范化</th><th>allocation site</th><th>状态</th></tr>$objects</table></div>
<div class="card"><h3>边（${raw.edges.size}）— 强 / 弱 / 内部</h3>
<table><tr><th>from</th><th>to</th><th>kind</th><th>label</th></tr>$edges</table></div>
<div class="card"><h3>Root 分类（${raw.roots.size}）</h3>
<table><tr><th>objectId</th><th>rootType</th></tr>$roots</table></div>
<div class="card"><h3>不完整块（${raw.incompleteChunks.size}）</h3>
<table><tr><th>offset</th><th>length</th><th>原因</th></tr>$chunks</table></div>
<div class="card"><h3>诊断</h3>${diagnosticsBlock(d.diagnostics)}</div>
""")
}

fun analysisPage(raw: RawSnapshot, analysisId: String?, outcome: AnalysisOutcome, decisions: List<Decision>): String {
    val head = """
<div class="card"><h2>分析 · 快照 <code>${raw.snapshotId}</code></h2>
<form method="get" action="/snapshots/${raw.snapshotId}/analysis">
弱引用策略（显式）: <select name="weakPolicy">
<option value="weak-ignore">weak-ignore（弱引用不保活）</option>
<option value="weak-keep-alive">weak-keep-alive（弱引用保活）</option></select>
泄漏阈值 B: <input name="leakThresholdBytes" value="200" size="6">
路径预算/对象: <input name="maxPathsPerObject" value="8" size="3">
<button class="btn">重新计算并存档</button></form>
<p class="muted">支配树只在可达图上构建 · 多个等长 root 路径全部保留 · 地址不是稳定身份 · 单位：字节 B，时间：epoch ms (UTC)</p></div>"""
    val body = when (outcome) {
        is AnalysisOutcome.BudgetExhausted -> """<div class="card"><span class="tag warn">BUDGET_EXHAUSTED</span> ${esc(outcome.detail)}</div>"""
        is AnalysisOutcome.SemanticallyUnsolvable -> """<div class="card"><span class="tag err">SEMANTICALLY_UNSOLVABLE</span><h3>带位置诊断</h3>${diagnosticsBlock(outcome.diagnostics)}</div>"""
        is AnalysisOutcome.Ok -> {
            val r = outcome.result
            val rows = r.objects.joinToString("") { o ->
                val paths = o.rootPaths.joinToString("<br>") { p ->
                    "<code>${p.hops.joinToString(" → ")}</code> <span class='tag info'>${p.rootType}</span>" }
                "<tr><td><code>${o.objectId}</code></td><td><code>${esc(o.address)}</code></td>" +
                "<td>${esc(o.className ?: "?")}</td><td>${o.shallowBytes?.let { "$it B" } ?: "?"}</td>" +
                "<td><b>${o.retainedBytes} B</b></td><td><code>${o.dominatorParent ?: "—(root/汇点)"}</code></td>" +
                "<td>$paths</td><td>${if (o.reachableViaWeakOnly) "<span class='tag warn'>仅弱可达</span>" else ""}</td></tr>"
            }
            val leaks = r.leakCandidates.joinToString("") { l ->
                val decs = decisions.filter { it.objectId == l.objectId }.joinToString("<br>") {
                    "<span class='tag ${if (it.verdict == "ACCEPTED") "ok" else if (it.verdict == "REJECTED") "err" else "info"}'>${it.verdict}</span> ${esc(it.note)}"
                }
                """<div class="card"><b><code>${l.objectId}</code></b> retained <b>${l.retainedBytes} B</b> — ${esc(l.reason)}<br>
                ${l.rootPaths.joinToString("<br>") { "<code>${it.hops.joinToString(" → ")}</code>" }}
                <form method="post" action="/decisions" style="margin-top:6px">
                <input type="hidden" name="analysisId" value="$analysisId">
                <input type="hidden" name="objectId" value="${l.objectId}">
                <select name="verdict"><option>ACCEPTED</option><option>REJECTED</option><option>PARAM_ADJUST</option></select>
                <input name="note" placeholder="判定理由" size="40"><button class="btn">记录决定</button></form>
                <div>$decs</div></div>"""
            }
            val unreachable = if (r.unreachable.isEmpty()) "无" else r.unreachable.joinToString(" ") { "<code>$it</code>" }
            """
<div class="card"><span class="tag ok">OK</span> 输入摘要 <code>${r.inputDigest.take(16)}…</code> · 参数版本 <code>${r.params.version}</code> · 弱引用策略 <code>${r.params.weakPolicy}</code> · 分析 id <code>$analysisId</code><br>
有效区间：仅对本输入摘要与参数版本有效；失效原因：输入材料变更、弱引用策略或阈值调整、分析器版本升级。<br>
不可达对象（不参与支配树）: $unreachable</div>
<div class="card"><h3>支配父级 / 保留大小 / root 路径（可达 ${r.reachable.size} 个对象）</h3>
<table><tr><th>objectId</th><th>地址</th><th>类</th><th>浅层</th><th>保留</th><th>支配父级</th><th>root 路径（全部等长候选）</th><th>标记</th></tr>$rows</table></div>
<div class="card"><h3>泄漏路径候选（≥ ${r.params.leakThresholdBytes} B）</h3>$leaks</div>
<div class="card"><h3>诊断</h3>${diagnosticsBlock(r.diagnostics)}</div>"""
        }
    }
    return layout("分析 ${raw.snapshotId}", head + body)
}

fun diffPage(diff: SnapshotDiff?): String {
    if (diff == null) return layout("类群差异", """<div class="card">需要两个快照。</div>""")
    val rows = diff.groups.joinToString("") { g ->
        val cls = when (g.status) { "RENAMED" -> "warn"; "ADDED" -> "ok"; "REMOVED" -> "err"; "CHANGED" -> "info"; else -> "" }
        "<tr><td><code>${g.classId}</code></td><td>${esc(g.nameA ?: "—")}</td><td>${esc(g.nameB ?: "—")}</td>" +
        "<td>${g.countA} → ${g.countB}</td><td>${g.bytesA} B → ${g.bytesB} B</td><td><span class='tag $cls'>${g.status}</span></td></tr>"
    }
    return layout("类群差异", """
<div class="card"><h2>类群差异：<code>${diff.snapshotA}</code> → <code>${diff.snapshotB}</code></h2>
<p class="muted">类身份 = classId（稳定身份），名字改动记为 RENAMED 而非增删；尺寸单位 B。</p>
<table><tr><th>classId</th><th>名称 A</th><th>名称 B</th><th>对象数</th><th>总字节</th><th>状态</th></tr>$rows</table></div>""")
}

fun eventsPage(events: List<StoredEvent>): String {
    val rows = events.joinToString("") { e ->
        "<tr><td>${e.seq}</td><td><a href='/events/${e.seq}'>${esc(e.type)}</a></td><td><code>${e.createdAtEpochMs}</code></td>" +
        "<td><code>${e.inputDigest?.take(12) ?: "—"}</code></td><td><code>${e.paramsVersion ?: "—"}</code></td>" +
        "<td>${e.parents.joinToString(", ") { esc(it) }.ifEmpty { "—" }}</td></tr>"
    }
    return layout("事件流", """
<div class="card"><h2>机器可读取事件（${events.size}）</h2>
<p class="muted">每条事件携带输入摘要、参数版本与父级依赖；回到旧事件可看到当时的可见结论，不被最新计算覆盖。时间基准 epoch ms (UTC)。</p>
<table><tr><th>seq</th><th>类型</th><th>时间</th><th>输入摘要</th><th>参数版本</th><th>父级依赖</th></tr>$rows</table></div>""")
}

fun eventDetailPage(e: StoredEvent): String = layout("事件 #${e.seq}", """
<div class="card"><h2>事件 #${e.seq} · <code>${esc(e.type)}</code></h2>
<p>eventId <code>${esc(e.eventId)}</code> · 时间 <code>${e.createdAtEpochMs}</code> (epoch ms UTC)<br>
输入摘要 <code>${e.inputDigest ?: "—"}</code> · 参数版本 <code>${e.paramsVersion ?: "—"}</code><br>
父级依赖: ${e.parents.joinToString(", ") { "<code>${esc(it)}</code>" }.ifEmpty { "—" }}</p>
<h3>当时的可见结论（原样读回，不重算）</h3>
<pre>${esc(e.payload)}</pre></div>""")
