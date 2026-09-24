package heaplab

private fun bytes(n: Long): String {
    val kib = "%.2f".format(n / 1024.0)
    return "$n byte（$kib KiB）"
}

private fun page(title: String, active: String, body: String): String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${esc(title)} · 堆影取证室</title>
<style>
:root { --ink:#1f2430; --mut:#6b7280; --line:#d8dce6; --bg:#f5f6f9; --card:#fff; --acc:#2b57d6; --weak:#9a6b00; --warn:#8a5a00; --err:#b42318; --ok:#0f7b3f; }
* { box-sizing:border-box; }
body { margin:0; font:14px/1.55 -apple-system,"PingFang SC","Hiragino Sans GB",sans-serif; color:var(--ink); background:var(--bg); }
nav { background:#101828; color:#fff; padding:10px 22px; display:flex; gap:14px; align-items:center; flex-wrap:wrap; }
nav a { color:#cdd5e6; text-decoration:none; }
nav a.on, nav b { color:#fff; }
.wrap { max-width:1180px; margin:18px auto; padding:0 18px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 18px; margin:14px 0; }
h1 { font-size:20px; margin:0 0 6px; } h2 { font-size:16px; margin:20px 0 8px; } h3 { font-size:14px; margin:14px 0 6px; }
table { border-collapse:collapse; width:100%; font-size:13px; }
th,td { border:1px solid var(--line); padding:5px 8px; text-align:left; vertical-align:top; }
th { background:#eef1f7; }
.mut { color:var(--mut); font-size:12px; }
.tag { display:inline-block; padding:0 7px; border-radius:99px; font-size:12px; border:1px solid var(--line); background:#f3f4f8; }
.tag.strong { border-color:#b9c4e8; background:#e8edfc; }
.tag.weak { border-color:#e3c98d; background:#fbf1da; color:var(--weak); }
.tag.internal { border-color:#bcd7c4; background:#e7f4eb; }
.ok { color:var(--ok); } .err { color:var(--err); } .warn { color:var(--warn); }
.steps { display:flex; gap:8px; flex-wrap:wrap; }
.steps span { background:#eef1f7; border:1px solid var(--line); border-radius:8px; padding:4px 10px; font-size:12px; }
textarea,input,select,button { font:inherit; }
textarea { width:100%; height:220px; }
button { background:var(--acc); color:#fff; border:0; border-radius:7px; padding:6px 14px; cursor:pointer; }
code { background:#f0f2f6; padding:1px 4px; border-radius:4px; }
.banner { border-left:4px solid var(--acc); background:#eef2ff; padding:8px 12px; border-radius:0 8px 8px 0; margin:10px 0; }
.banner.bad { border-color:var(--err); background:#fdeceb; }
.banner.warn { border-color:var(--warn); background:#fdf5e3; }
.banner.gone { border-color:#8a8f98; background:#f0f1f3; }
.paths { font-family:ui-monospace,Menlo,monospace; font-size:12px; }
label { margin-right:12px; }
.small { font-size:12px; }
</style>
</head>
<body>
<nav>
  <b>堆影取证室</b>
  <a href="/" class="${if (active=="home")"on" else ""}">审阅首页</a>
  <a href="/archive" class="${if (active=="archive")"on" else ""}">反例档案</a>
  <a href="/events" class="${if (active=="events")"on" else ""}">事件日志</a>
  <a href="/diff" class="${if (active=="diff")"on" else ""}">类群差异</a>
  <a href="/import" class="${if (active=="import")"on" else ""}">导入</a>
  <a href="/export">导出</a>
</nav>
<div class="wrap">
$body
</div>
</body></html>
"""

fun renderHome(store: Store): String {
    val snaps = store.listSnapshots()
    val runs = store.listRuns()
    val cases = store.cases()
    val body = buildString {
        append("<h1>内存快照审阅工作台</h1>")
        append("""<p class="mut">完整工作流：导入不可变材料 → 服务端计算（可达图/支配树/保留大小/root 路径）→ 接受或否定并留痕 → 导出 → 删库 → 再导入恢复身份与历史。业务规则在服务端与存储层，可直接被测试调用。</p>""")
        append("""<div class="steps"><span>① 导入原始材料</span><span>② 显式弱引用策略计算</span><span>③ 审阅对象图/支配父级/保留大小/root 路径</span><span>④ 类群差异复核</span><span>⑤ 接受/否定/参数修订事件</span><span>⑥ 导出往返</span></div>""")
        append("<div class='card'><h2 style='margin-top:4px'>快照（不可变材料，稳定身份=snapshotId/objectId）</h2><table>")
        append("<tr><th>snapshotId</th><th>名称</th><th>解析器版本</th><th>采集时间基准(UTC)</th><th>对象/边/root/不完整块</th><th></th></tr>")
        for (s in snaps) {
            append("<tr><td><code>${esc(s.snapshotId)}</code></td><td>${esc(s.name)}</td><td><code>${esc(s.parserVersion)}</code></td><td>${esc(s.capturedAt)}</td><td>${s.objects.size} / ${s.edges.size} / ${s.roots.size} / ${s.incompleteBlocks.size}</td><td><a href='/snapshot/${enc(s.snapshotId)}'>核对材料并计算</a></td></tr>")
        }
        append("</table></div>")
        append("<div class='card'><h2 style='margin-top:4px'>冻结运行（输入摘要+参数版本+父级依赖）</h2><table>")
        append("<tr><th>runId</th><th>快照</th><th>状态</th><th>参数版本</th><th>弱引用保活</th><th>可达对象数</th><th></th></tr>")
        for (r in runs) {
            val weak = when (r.params.weakRefsKeepAlive) { true -> "保活"; false -> "不保活"; null -> "<span class='err'>未指定(语义无解)</span>" }
            val cls = when (r.status) { RunStatus.OK -> "ok"; else -> "err" }
            append("<tr><td><code>${esc(r.runId)}</code></td><td><code>${esc(r.snapshotId)}</code></td><td class='$cls'>${r.status}</td><td>v${r.params.paramsVersion}</td><td>$weak</td><td>${r.result?.reachable?.size ?: "-"}</td><td><a href='/run/${enc(r.runId)}'>查看结论</a></td></tr>")
        }
        append("</table></div>")
        if (snaps.size >= 2) {
            val pairs = snaps.take(6).flatMap { a -> snaps.filter { it.snapshotId != a.snapshotId }.take(3).map { b -> a to b } }.take(3)
            append("<div class='card'><h2 style='margin-top:4px'>类群差异快速入口</h2>")
            for ((a, b) in pairs) append("<a class='tag' href='/diff?a=${enc(a.snapshotId)}&b=${enc(b.snapshotId)}'>${esc(a.snapshotId)} ↔ ${esc(b.snapshotId)}</a> ")
            append("</div>")
        }
        append("<div class='card'><h2 style='margin-top:4px'>反例档案</h2><p>${cases.size} 个案例（循环图 / 弱引用策略 / 重复地址 / 不完整对象 / 多 root 等长路径 / 类改名）。<a href='/archive'>进入档案</a></p></div>")
    }
    return page("审阅首页", "home", body)
}

fun notFound(what: String) = page("未找到", "", "<div class='card'><h1>未找到</h1><p>${esc(what)} 不存在。</p><p><a href='/'>返回首页</a></p></div>")

fun renderSnapshot(store: Store, s: Snapshot): String {
    val diags = SnapshotIO.validate(s)
    val runs = store.listRuns().filter { it.snapshotId == s.snapshotId }
    val body = buildString {
        append("<h1>材料核对 · <code>${esc(s.snapshotId)}</code></h1>")
        append("<p class='mut'>单位：大小以原始字符串保留并附 byte 规范化值；地址原始表示与十六进制值并列；时间基准 UTC（${esc(s.capturedAt)}）；解析器版本 <code>${esc(s.parserVersion)}</code>。身份列为 objectId，地址不作为身份。</p>")
        if (diags.isNotEmpty()) {
            append("<h2>导入诊断（WARNING 不阻止导入；原始值留在原位置）</h2>")
            append("<table><tr><th>位置(JSON path)</th><th>级别</th><th>说明</th></tr>")
            for (d in diags) append("<tr><td><code>${esc(d.path)}</code></td><td class='${if (d.severity==Severity.ERROR)"err" else "warn"}'>${d.severity}</td><td>${esc(d.message)}</td></tr>")
            append("</table>")
        }
        append("<h2>类元数据</h2><table><tr><th>classId（快照内稳定身份）</th><th>类名</th><th>父类 classId</th><th>loader</th></tr>")
        for (c in s.classes) append("<tr><td><code>${esc(c.classId)}</code></td><td>${esc(c.name)}</td><td>${esc(c.superClassId ?: "—")}</td><td>${esc(c.loaderLabel ?: "—")}</td></tr>")
        append("</table>")

        append("<h2>对象（原始 object id / 地址 / 大小）</h2><table><tr><th>objectId</th><th>地址原始表示</th><th>规范化地址</th><th>classId</th><th>大小原始表示</th><th>byte</th><th>allocation site</th><th>不完整</th></tr>")
        for (o in s.objects) append(
            "<tr><td><code>${esc(o.objectId)}</code></td><td>${esc(o.addressRaw)}</td><td>${o.address?.let { "0x%x".format(it) } ?: "<span class='err'>未转换</span>"}</td><td><code>${esc(o.classId)}</code></td><td>${esc(o.sizeRaw)}</td><td>${o.sizeBytes ?: "<span class='err'>未转换</span>"}</td><td>${esc(o.allocSiteId ?: "—")}</td><td>${if (o.incomplete) "<span class='warn'>是</span>" else "—"}</td></tr>"
        )
        append("</table>")

        append("<h2>边：强 / 弱 / 内部（原始分类保留）</h2><table><tr><th>edgeId</th><th>from</th><th>to</th><th>分类</th><th>字段</th></tr>")
        for (e in s.edges) {
            val cls = when (e.kind) { EdgeKind.STRONG -> "strong"; EdgeKind.WEAK -> "weak"; EdgeKind.INTERNAL -> "internal" }
            append("<tr><td><code>${esc(e.edgeId)}</code></td><td><code>${esc(e.fromId)}</code></td><td><code>${esc(e.toId)}</code></td><td><span class='tag $cls'>${e.kind}</span></td><td>${esc(e.field ?: "—")}</td></tr>")
        }
        append("</table>")

        append("<h2>Root 分类</h2><table><tr><th>objectId</th><th>root 类型</th><th>标注</th></tr>")
        for (r in s.roots) append("<tr><td><code>${esc(r.objectId)}</code></td><td><span class='tag'>${r.rootType}</span></td><td>${esc(r.label ?: "—")}</td></tr>")
        append("</table>")

        append("<h2>不完整块（按原始记录保留）</h2>")
        if (s.incompleteBlocks.isEmpty()) append("<p class='mut'>无</p>") else {
            append("<table><tr><th>blockId</th><th>地址</th><th>长度（原始表示）</th><th>原因</th></tr>")
            for (b in s.incompleteBlocks) append("<tr><td><code>${esc(b.blockId)}</code></td><td>${esc(b.addressRaw)}</td><td>${esc(b.lengthRaw)}</td><td>${esc(b.reason)}</td></tr>")
            append("</table>")
        }

        append("<h2>Allocation sites</h2><table><tr><th>siteId</th><th>类</th><th>方法</th><th>文件</th><th>行</th></tr>")
        for (a in s.allocationSites) append("<tr><td><code>${esc(a.siteId)}</code></td><td>${esc(a.className)}</td><td>${esc(a.methodName)}</td><td>${esc(a.file ?: "—")}</td><td>${a.line ?: "—"}</td></tr>")
        append("</table>")

        append("""
        <div class='card'><h2 style='margin-top:0'>服务端计算（弱引用策略必须显式选择）</h2>
        <form method='post' action='/snapshot/${enc(s.snapshotId)}/analyze'>
          <p><label><input type='radio' name='weakPolicy' value='ignore' checked> 弱引用不保活</label>
             <label><input type='radio' name='weakPolicy' value='keep'> 弱引用保活</label>
             <label><input type='radio' name='weakPolicy' value='unset'> 不指定（语义无解证据）</label>
             <label><input type='checkbox' name='internalKeep' checked> 内部边保活</label></p>
          <p><label>参数版本 <input name='paramsVersion' value='1' style='width:60px'></label>
             <label>节点预算 <input name='maxVisited' value='200000' style='width:90px'></label>
             <label>每对象路径上限 <input name='maxPaths' value='64' style='width:60px'></label>
             <button type='submit'>运行可达性 / 支配树 / 保留大小</button></p>
        </form></div>
        """.trimIndent())
        if (runs.isNotEmpty()) {
            append("<h2>该快照的历史运行</h2><ul>")
            for (r in runs) append("<li><a href='/run/${enc(r.runId)}'><code>${esc(r.runId)}</code></a> ${r.status} · 参数 v${r.params.paramsVersion} · 弱引用${when (r.params.weakRefsKeepAlive) { true -> "保活"; false -> "不保活"; null -> "未指定" }}</li>")
            append("</ul>")
        }
    }
    return page("材料核对", "", body)
}

fun renderRun(store: Store, run: StoredRun, historical: HistoricalView?): String {
    val snap = store.getSnapshot(run.snapshotId)!!
    val classOf = snap.objects.associate { it.objectId to it.classId }
    val className = snap.classes.associate { it.classId to it.name }
    val body = buildString {
        append("<h1>计算结论 · <code>${esc(run.runId)}</code></h1>")
        append("<div class='banner'><b>有效区间与依据：</b> 快照 <a href='/snapshot/${enc(run.snapshotId)}'><code>${esc(run.snapshotId)}</code></a>；")
        append("输入摘要 SHA-256 <code>${esc(run.inputDigest.take(20))}…</code>；参数版本 v${run.params.paramsVersion}（弱引用${when (run.params.weakRefsKeepAlive) { true -> "保活"; false -> "不保活"; null -> "<b>未指定</b>" }}，内部边${if (run.params.internalEdgesKeepAlive) "保活" else "不保活"}）；节点预算 ${run.params.maxVisitedNodes}；")
        append("时间基准 UTC ${esc(run.createdAt)}。")
        if (historical == null) append("状态：<span class='ok'>当前有效，未被取代</span>。")
        else append("状态：历史快照（来自事件 ${esc(historical.event.eventId)}），结论冻结，不用最新计算覆盖；${historical.invalidationReason?.let { "<span class='err'>失效原因：${esc(it)}</span>" } ?: "<span class='ok'>该结论未失效</span>"}。")
        append("</div>")

        if (run.diagnostics.isNotEmpty()) {
            append("<h2>诊断</h2><table><tr><th>位置</th><th>级别</th><th>说明</th></tr>")
            for (d in run.diagnostics) append("<tr><td><code>${esc(d.path)}</code></td><td class='${if (d.severity==Severity.ERROR)"err" else "warn"}'>${d.severity}</td><td>${esc(d.message)}</td></tr>")
            append("</table>")
        }

        val result = run.result?.toResult()
        if (result != null) {
            append("<h2>对象图（SVG；可达节点按 root 深度分层，弱边虚线/内部边点线）</h2>")
            append(graphSvg(snap, result))
            append("<p class='mut'>坐标基准：SVG viewBox，原点左上，1 单位=1 px；横向节点间距 220 px、纵向层级间距 110 px；y 层级 = 到任意 root 的最短边数（BFS 深度，单位：跳）。未被可达图覆盖的对象（如弱引用不保活时的 <code>obj-buffer</code>）渲染在右栏“不可达”区，支配树不在其上构建。</p>")

            append("<h2>支配父级与保留大小（单位：byte，规范化自原始大小表示）</h2>")
            append("<table><tr><th>objectId（稳定身份）</th><th>类</th><th>深度(跳)</th><th>支配父级</th><th>自身 byte</th><th>保留大小 byte</th><th>保留大小(可读)</th></tr>")
            for (id in result.reachable) {
                val obj = snap.objects.first { it.objectId == id }
                val parent = result.idomParent[id]
                val parentLabel = if (parent == AnalysisResult.VIRTUAL_ROOT) "<span class='tag'>虚拟根（多 root 汇聚）</span>" else "<code>${esc(parent)}</code>"
                append("<tr><td><code>${esc(id)}</code></td><td>${esc(className[classOf[id]] ?: "?")}</td><td>${result.depth[id]}</td><td>$parentLabel</td><td>${obj.sizeBytes ?: "<span class='warn'>未转换</span>"}</td><td><b>${result.retainedBytes[id]}</b></td><td>${bytes(result.retainedBytes[id] ?: 0)}</td></tr>")
            }
            append("</table>")

            append("<h2>Root 路径（等长最短路径全部保留；root 分类保留）</h2>")
            for (id in result.reachable.toList().filter { result.rootPaths[it].orEmpty().isNotEmpty() }) {
                val paths = result.rootPaths.getValue(id)
                val trunc = if (id in result.pathsTruncated) " <span class='warn'>超过 ${run.params.maxPathsPerObject} 条，已截断（诊断在案）</span>" else ""
                append("<h3><code>${esc(id)}</code> · ${paths.size} 条等长候选$trunc</h3><ol>")
                for (p in paths) {
                    append("<li class='paths'>")
                    val rootType = snap.roots.firstOrNull { it.objectId == p.firstOrNull()?.fromId }?.rootType
                    append("<span class='tag'>root:${rootType ?: "?"}</span> ")
                    append((listOf(p.firstOrNull()?.fromId).filterNotNull() + p.map { it.toId }).joinToString(" → ") { "<code>${esc(it)}</code>" })
                    val kinds = p.joinToString(" ") { "<span class='tag ${if (it.kind==EdgeKind.STRONG)"strong" else if (it.kind==EdgeKind.WEAK)"weak" else "internal"}'>${it.kind}</span>" }
                    append(" <span>$kinds</span>")
                    append("</li>")
                }
                append("</ol>")
            }
        }

        if (historical == null) {
            append("""
            <div class='card'><h2 style='margin-top:0'>复核决定（生成新事件，不覆盖历史）</h2>
            <form method='post' action='/run/${enc(run.runId)}/decide'>
              <input type='hidden' name='back' value='/run/${enc(run.runId)}'>
              <p><label>关联反例（可选） <input name='caseId' value='' style='width:260px' placeholder='例如 case-weak-policy'></label></p>
              <p><textarea name='rationale' placeholder='判断依据：为什么接受/否定这组保留路径候选？' style='height:70px'></textarea></p>
              <p><button name='verdict' value='accept'>接受</button> <button name='verdict' value='reject' style='background:#b42318'>否定</button></p>
            </form></div>
            """.trimIndent())
        } else {
            append("<p><a href='/event/${store.eventSeqOf(historical.event.eventId) ?: ""}'>← 返回历史事件 ${esc(historical.event.eventId)}</a></p>")
        }
    }
    return page("计算结论", "", body)
}

private fun graphSvg(snap: Snapshot, result: AnalysisResult): String {
    val w = 1080
    val reach = result.reachable
    val byDepth = reach.groupBy { result.depth[it] ?: 0 }.toSortedMap()
    val nodePos = HashMap<String, Pair<Int, Int>>()
    byDepth.forEach { (depth, ids) ->
        ids.sorted().forEachIndexed { i, id ->
            nodePos[id] = (40 + i * 220) to (60 + depth * 110)
        }
    }
    val unreachable = snap.objects.filter { it.objectId !in reach }.sortedBy { it.objectId }
    val unreachX = 40 + (byDepth.values.maxOfOrNull { it.size }?.let { (it - 1) } ?: 0) * 220
    val height = maxOf(
        60 + ((byDepth.keys.lastOrNull() ?: 0) + 1) * 110,
        60 + unreachable.size * 64,
    )
    val edgeById = snap.edges.associateBy { it.edgeId }
    val usedEdges = result.rootPaths.values.flatten().flatten().map { it.edgeId }.toSet()
    val sb = StringBuilder()
    sb.append("<svg width='100%' viewBox='0 0 $w $height' style='border:1px solid var(--line);border-radius:8px;background:#fbfcfe'>")
    sb.append("<defs><marker id='ar' markerWidth='8' markerHeight='8' refX='7' refY='3' orient='auto'><path d='M0,0 L7,3 L0,6' fill='#8a93a6'/></marker></defs>")
    for (e in snap.edges) {
        val a = nodePos[e.fromId]; val b = nodePos[e.toId]
        if (a == null || b == null) continue
        val onPath = e.edgeId in usedEdges
        val style = when (e.kind) {
            EdgeKind.STRONG -> "stroke:#46536b"
            EdgeKind.WEAK -> "stroke:#b9851f;stroke-dasharray:6 4"
            EdgeKind.INTERNAL -> "stroke:#3f8f5c;stroke-dasharray:2 3"
        }
        sb.append("<line x1='${a.first}' y1='${a.second}' x2='${b.first}' y2='${b.second}' $style stroke-width='${if (onPath) 2.2 else 1}' opacity='${if (onPath) 1 else 0.55}' marker-end='url(#ar)'/>")
    }
    for ((id, p) in nodePos) {
        val obj = snap.objects.first { it.objectId == id }
        val fill = if (id in snap.roots.map { it.objectId }) "#dce8ff" else "#ffffff"
        sb.append("<g><rect x='${p.first - 70}' y='${p.second - 22}' width='140' height='44' rx='7' fill='$fill' stroke='#8a93a6'/>")
        sb.append("<text x='${p.first}' y='${p.second - 5}' text-anchor='middle' font-size='11' font-family='monospace'>${esc(id)}</text>")
        sb.append("<text x='${p.first}' y='${p.second + 11}' text-anchor='middle' font-size='10' fill='#6b7280'>ret=${result.retainedBytes[id]} B</text></g>")
    }
    if (unreachable.isNotEmpty()) {
        val panelW = 260
        sb.append("<rect x='$unreachX' y='20' width='${panelW + 100}' height='${40 + unreachable.size * 64}' rx='8' fill='#f2f3f5' stroke='#c4c9d4'/>")
        sb.append("<text x='${unreachX + 14}' y='42' font-size='12' fill='#6b7280'>不可达对象（不参与支配树）</text>")
        unreachable.forEachIndexed { i, o ->
            val y = 66 + i * 64
            sb.append("<rect x='${unreachX + 14}' y='$y' width='${panelW}' height='46' rx='7' fill='#ffffff' stroke='#c4c9d4' stroke-dasharray='4 3'/>")
            sb.append("<text x='${unreachX + 24}' y='${y + 19}' font-size='11' font-family='monospace'>${esc(o.objectId)}</text>")
            sb.append("<text x='${unreachX + 24}' y='${y + 36}' font-size='10' fill='#6b7280'>${esc(o.sizeRaw)} · ${esc(o.addressRaw)} · 不保留</text>")
        }
    }
    sb.append("</svg>")
    return sb.toString()
}

fun renderDiff(store: Store, aId: String?, bId: String?): String {
    val snaps = store.listSnapshots()
    val body = buildString {
        append("<h1>两个快照的类群差异</h1>")
        append("<form method='get' action='/diff'><p><select name='a'>")
        for (s in snaps) append("<option value='${esc(s.snapshotId)}' ${if (s.snapshotId == aId) "selected" else ""}>${esc(s.snapshotId)}</option>")
        append("</select> ↔ <select name='b'>")
        for (s in snaps) append("<option value='${esc(s.snapshotId)}' ${if (s.snapshotId == bId) "selected" else ""}>${esc(s.snapshotId)}</option>")
        append("""</select> <button>对比</button> <label class='small mut'>差异基于弱引用保活策略 run 的可达集合；默认全部对象</label></p></form>""")

        val a = aId?.let { store.getSnapshot(it) }; val b = bId?.let { store.getSnapshot(it) }
        if (a != null && b != null) {
            val runA = store.listRuns().lastOrNull { it.snapshotId == a.snapshotId && it.status == RunStatus.OK }
            val runB = store.listRuns().lastOrNull { it.snapshotId == b.snapshotId && it.status == RunStatus.OK }
            val reachA = runA?.result?.toResult()?.reachable
            val reachB = runB?.result?.toResult()?.reachable
            append("<p class='mut'>范围：<a href='/run/${runA?.runId ?: ""}'>${esc(runA?.runId ?: "全部对象")}</a> 与 <a href='/run/${runB?.runId ?: ""}'>${esc(runB?.runId ?: "全部对象")}</a> 的可达集合；单位 byte；类身份是快照内 classId。</p>")
            val diff = Analysis.diffClasses(a, reachA, b, reachB)
            append("<div class='banner'>对应规则：${esc(diff.note)}</div>")
            fun groupTable(title: String, groups: List<ClassGroup>, cls: String) {
                append("<h2>$title</h2><table><tr><th>classId</th><th>类名</th><th>对象数</th><th>总 byte（null=存在无法转换大小，拒绝伪造数值）</th></tr>")
                if (groups.isEmpty()) append("<tr><td colspan='4' class='mut'>无</td></tr>")
                for (g in groups) append("<tr class='$cls'><td><code>${esc(g.classId)}</code></td><td>${esc(g.name)}</td><td>${g.count}</td><td>${g.totalBytes ?: "<span class='warn'>未转换</span>"}</td></tr>")
                append("</table>")
            }
            groupTable("只在快照 A（${esc(a.snapshotId)}）出现（类改名时旧名出现在这里）", diff.onlyInA, "err")
            groupTable("只在快照 B（${esc(b.snapshotId)}）出现（类改名时新名出现在这里）", diff.onlyInB, "ok")
            append("<h2>同一 classId 上的数量/大小变化</h2><table><tr><th>classId</th><th>类名</th><th>数量 A→B</th><th>byte A→B</th></tr>")
            if (diff.changed.isEmpty()) append("<tr><td colspan='4' class='mut'>无</td></tr>")
            for (c in diff.changed) append("<tr><td><code>${esc(c.classIdA)} / ${esc(c.classIdB)}</code></td><td>${esc(c.name)}</td><td>${c.countA} → ${c.countB}</td><td>${c.bytesA ?: "?"} → ${c.bytesB ?: "?"}</td></tr>")
            append("</table>")
        }
    }
    return page("类群差异", "diff", body)
}

fun renderEvents(store: Store): String {
    val events = store.events()
    val body = buildString {
        append("<h1>机器可读事件日志</h1><p class='mut'>每条事件携带冻结的 runId/快照/参数版本与判断依据；点击 seq 回到当时可见结论，历史不被最新结果覆盖。</p>")
        append("<table><tr><th>seq</th><th>eventId</th><th>类型</th><th>UTC 时间</th><th>快照</th><th>run</th><th>要点</th></tr>")
        events.forEachIndexed { i, e ->
            val seq = i + 1L
            val key = e.payload["ruleId"] ?: e.payload["caseId"] ?: e.payload["note"] ?: e.payload["rationale"] ?: e.payload["changedField"] ?: e.payload["status"] ?: ""
            append("<tr><td><a href='/event/$seq'>#$seq</a></td><td><code>${esc(e.eventId)}</code></td><td>${esc(e.kind)}</td><td class='small'>${esc(e.at)}</td><td>${esc(e.snapshotId ?: "—")}</td><td>${esc(e.runId ?: "—")}</td><td class='small'>${esc(key.toString())}</td></tr>")
        }
        append("</table>")
    }
    return page("事件日志", "events", body)
}

fun renderHistorical(store: Store, view: HistoricalView): String {
    val body = buildString {
        append("<h1>历史视图 · ${esc(view.event.eventId)}</h1>")
        append("<div class='banner ${if (view.invalidationReason != null) "gone" else ""}'><b>依赖版本：</b> ")
        append("run = <code>${esc(view.runId ?: "（无）")}</code>；参数版本 v${view.paramsVersion ?: "—"}；有效从 seq ${view.validFromSeq ?: "?"} 起；")
        append(if (view.invalidationReason != null) "<span class='err'>${esc(view.invalidationReason)}</span>（seq ${view.invalidAtSeq} 失效）" else "<span class='ok'>未失效</span>")
        append("</div>")
        append("<h2>事件原始 payload（机器可读 JSON）</h2>")
        append("<pre class='card'>${esc(heapJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), view.event.payload))}</pre>")
    }
    val base = page("历史视图", "events", body)
    val run = view.runId?.let { store.getRun(it) }
    return if (run != null) base + renderRun(store, run, view) else base
}

fun renderArchive(store: Store): String {
    val body = buildString {
        append("<h1>反例档案 · 堆影取证室：内存快照、弱引用边与保留路径候选</h1>")
        append("<p class='mut'>每个案例先保存“原始对象 id、地址、类元数据、强/弱/内部边、root 分类、不完整块和解析器版本”的基线，再只改动一个直接来源；采纳与未采纳结果均保留；关键判断以 rule.judgment 事件机器可读留痕。</p>")
        for (c in store.cases()) {
            val verdict = store.caseVerdict(c.caseId)
            append("<div class='card'>")
            append("<h2 style='margin-top:2px'>${esc(c.title)} <code>${esc(c.caseId)}</code></h2>")
            append("<p><b>规则：</b><code>${esc(c.ruleId)}</code>；<b>基线：</b><a href='/snapshot/${enc(c.baselineSnapshotId)}'><code>${esc(c.baselineSnapshotId)}</code></a>")
            if (c.mutatedSnapshotId != null) append("；<b>改动后：</b><a href='/snapshot/${enc(c.mutatedSnapshotId)}'><code>${esc(c.mutatedSnapshotId)}</code></a>")
            append("<br><b>单一直接来源改动：</b>${esc(c.directSourceChange)}<br><b>预期影响范围：</b>${c.expectedAffectedScope.joinToString { "<code>${esc(it)}</code>" }}")
            append("<br><b>采纳结果：</b> ${c.acceptedRunId?.let { "<a href='/run/${enc(it)}'><code>${esc(it)}</code></a>" } ?: "（无）"}；<b>未采纳结果：</b> ${c.rejectedRunId?.let { "<a href='/run/${enc(it)}'><code>${esc(it)}</code></a>" } ?: "（无）"}</p>")
            when (verdict) {
                is Store.VerdictState.Open -> append("<p><span class='tag'>待决定</span></p>")
                is Store.VerdictState.Resolved -> append("<p><span class='tag ok'>已采纳 ${esc(verdict.acceptedRunId)}</span></p>")
                is Store.VerdictState.Conflict -> append("<p><span class='tag err'>互相冲突的人工决定（语义无解，保留全部候选）</span> ${verdict.candidateRunIds.joinToString { "<a href='/run/${enc(it)}'><code>${esc(it)}</code></a>" }}</p>")
            }
            if (c.rejectedImportDiagnostics.isNotEmpty()) {
                append("<h3>被拒绝导入的带位置诊断（FORMAT_CORRUPT）</h3><table><tr><th>位置</th><th>级别</th><th>说明</th></tr>")
                for (d in c.rejectedImportDiagnostics.filter { it.severity == Severity.ERROR }) append("<tr><td><code>${esc(d.path)}</code></td><td class='err'>${d.severity}</td><td>${esc(d.message)}</td></tr>")
                append("</table>")
            }
            if (c.note.isNotBlank()) append("<p class='mut'>${esc(c.note)}</p>")
            append("</div>")
        }
    }
    return page("反例档案", "archive", body)
}

fun renderImport(message: String? = null): String {
    val body = """
<h1>导出 / 删除本地数据库 / 再导入</h1>
<p class='mut'>导出 JSON 携带 SHA-256 校验和；导入只允许进入空库（防止身份被覆盖），全部表在一个事务内写入，校验失败或中途崩溃整体回滚（半写恢复）。导入后 snapshotId / objectId / edgeId / runId / eventId / event seq 与来源关系保持不变。</p>
${if (message != null) "<div class='banner ${if (message.startsWith("导入成功")) "" else "bad"}'>${esc(message)}</div>" else ""}
<div class='card'>
  <p><a href='/export'><button type='button'>下载导出文件 heaplab-export.json</button></a></p>
  <form method='post' action='/import' enctype='multipart/form-data'>
    <p>选择导出文件：<input type='file' name='file' accept='application/json'></p>
    <p>或粘贴 JSON：</p>
    <textarea name='document' placeholder='{ "schemaVersion": 1, ... }'></textarea>
    <p><button>导入（空库）</button></p>
  </form>
</div>
"""
    return page("导入", "import", body)
}
