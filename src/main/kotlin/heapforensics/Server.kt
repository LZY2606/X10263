package heapforensics

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

class HeapForensicsServer(private val store: Store) {
    private val primarySnapshotId = "heap-a-r2"
    private val diffB = "heap-b"

    private fun paramsFor(seq: Long): Pair<AnalysisParams, DomainEvent?> {
        val ev = store.eventsUpTo(seq).filter { it.type == "PARAMS_SET" }.maxByOrNull { it.seq }
        return store.paramsAsOf(seq) to ev
    }

    private fun page(title: String, body: String): String = """
<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><title>${Views.esc(title)}</title>
<style>
 body{font-family:-apple-system,'PingFang SC',sans-serif;margin:20px;color:#1a1a1a;background:#fff}
 h1{font-size:20px} h2{font-size:16px;margin-top:28px;border-left:4px solid #0369a1;padding-left:8px}
 nav a{margin-right:12px} table{border-collapse:collapse;font-size:12px;background:#fff}
 th{background:#f1f5f9} td,th{text-align:left} .mono{font-family:Menlo,monospace}
 .basis{background:#f8fafc;border:1px solid #e2e8f0;padding:10px;font-size:12px;margin:16px 0}
 .evt{font-family:Menlo,monospace;font-size:12px}
 .accepted{color:#15803d}.rejected{color:#b91c1c}
 form{margin:8px 0} input,select{margin:0 6px}
</style></head><body>
<nav><a href="/">复核页</a><a href="/events">事件档案</a><a href="/export">导出项目</a></nav>
<h1>${Views.esc(title)}</h1>
$body
</body></html>"""

    private fun reviewBody(seq: Long): String {
        val maxSeq = store.maxSeq()
        val all = store.listSnapshots().sortedBy { it.snapshotId }
        val snapId = if (seq == maxSeq && all.any { it.snapshotId == primarySnapshotId }) primarySnapshotId
        else all.filter { store.eventsUpTo(seq).any { e -> e.type == "SNAPSHOT_IMPORTED" && e.snapshotId == it.snapshotId } }
            .maxByOrNull { it.snapshotId }?.snapshotId ?: all.first().snapshotId
        val snapshot = store.getSnapshot(snapId)!!
        val (params, paramEvent) = paramsFor(seq)
        val result = Analysis.analyze(snapshot, params, Instant.now().toString())
        val decisions = store.effectiveDecisions(seq)
        val snapB = store.getSnapshot(diffB)?.takeIf {
            store.eventsUpTo(seq).any { it.type == "SNAPSHOT_IMPORTED" && it.snapshotId == diffB }
        }
        val diff = if (snapB != null) Analysis.classGroupDiff(snapshot, snapB) else emptyList()

        val sb = StringBuilder()
        if (seq != maxSeq) {
            sb.append("""<div class="basis">⏪ 正在重放：有效区间为事件序号 <b>1..$seq</b>（当前最新 $maxSeq）。以下结论按当时参数版本与决定恢复，不使用最新结果覆盖历史。</div>""")
        }
        sb.append("""<div class="basis">
时间基准：UTC ISO-8601（事件）/ 快照 capturedAt；大小单位：<b>字节(B)</b>；图坐标基准：见 SVG 标注。<br/>
快照身份：<b>${snapshot.snapshotId}</b>（parser ${snapshot.parserVersion}，capturedAt ${snapshot.capturedAt}）；
参数版本：v${params.version} weakKeepAlive=${params.weakKeepAlive}${paramEvent?.let { "（来源事件 #" + it.seq + " " + it.eventId + "）" } ?: "（默认）"}<br/>
有效区间：事件序号 1..$seq；分析输入摘要：<span class="mono">${Analysis.inputDigest(snapshot, params).take(16)}</span>
</div>""")

        sb.append("<h2>1 · 对象图（可达子图，SVG）</h2>")
        sb.append(Views.graphSvg(snapshot, result, snapId))
        sb.append("<p style='font-size:12px'>边类型：黑色实线=强引用，橙色虚线=弱引用（保活策略=${params.weakKeepAlive}），灰色点线=内部边。蓝色节点为 root；不完整对象标注「不完整」。</p>")

        sb.append("<h2>2 · 支配父级与保留大小</h2><p style='font-size:12px'>支配树仅在可达图上构建；保留大小单位字节。</p>")
        sb.append(Views.dominatorTable(result, snapshot))

        sb.append("<h2>3 · Root 路径（多个等长候选全部保留）</h2>")
        sb.append(Views.rootPathTable(result))

        if (diff.isNotEmpty()) {
            sb.append("<h2>4 · 两个快照的类群差异（$snapId vs $diffB）</h2>")
            sb.append("<p style='font-size:12px'>类群以稳定 classId 对应，不以名称对应；改名单独标出。</p>")
            sb.append(Views.classDiffTable(diff))
        }

        sb.append("<h2>5 · 判定状态与泄漏路径候选</h2>")
        val findingEvents = result.events.filter { it.type in setOf("CYCLE_DETECTED", "MULTI_EQUAL_ROOT_PATHS", "WEAK_NOT_KEPT", "WEAK_KEPT_ALIVE") }
        sb.append("<table border='1' cellpadding='6' cellspacing='0'><thead><tr><th>稳定事件 id</th><th>类型</th><th>受影响对象（稳定身份）</th><th>当前决定</th></tr></thead><tbody>")
        for (e in findingEvents) {
            val d = decisions.values.filter { it.attributes["target"] == e.eventId || it.affected.any { a -> a in e.affected } }
                .maxByOrNull { it.seq }
            val dText = d?.let {
                val cls = if (it.attributes["kind"] == "ACCEPT") "accepted" else "rejected"
                "<span class='$cls'>${it.attributes["kind"]} @ #${it.seq}</span>"
            } ?: "未决定"
            sb.append("<tr><td class='mono'>${e.eventId}</td><td>${e.type}</td>")
            sb.append("<td class='mono'>${e.affected.joinToString(", ") { it + "@" + snapId }}</td><td>$dText</td></tr>")
        }
        sb.append("</tbody></table>")

        // accepted + not-adopted results both visible
        sb.append("""<h2>6 · 沿证据往返（接受 / 否定 / 参数调整）</h2>
<form method='post' action='/decisions'>
 目标事件：<input name='target' size='44' placeholder='evt-xxxxxxxxxxxx'/>
 决定：<select name='kind'><option>ACCEPT</option><option>REJECT</option></select>
 理由：<input name='snapshotId' type='hidden' value='$snapId'/><input name='rationale' size='50'/>
 <button type='submit'>生成新事件</button></form>""")

        sb.append("<h2>7 · 失效原因（被取代的分析运行）</h2>")
        sb.append("<table border='1' cellpadding='6' cellspacing='0'><thead><tr><th>run</th><th>快照</th><th>参数版本</th><th>状态</th><th>失效原因</th><th>取代者</th></tr></thead><tbody>")
        for (r in store.runs()) {
            sb.append("<tr><td class='mono'>${r.runId}</td><td>${r.snapshotId}</td><td>v${r.paramsVersion}</td>")
            sb.append("<td>${if (r.supersededBy != null) "<s>${r.status}</s>" else r.status}</td>")
            sb.append("<td>${r.invalidationReason ?: "—"}</td><td class='mono'>${r.supersededBy ?: "—"}</td></tr>")
        }
        sb.append("</tbody></table>")

        sb.append("<h2>8 · 原始材料（不可变层）</h2><p style='font-size:12px'>原始对象 id / 地址 / 类元数据 / 强·弱·内部边 / root 分类 / 不完整块 / 解析器版本均保留：<a href='/materials/$snapId'>查看 $snapId 原始层与诊断</a></p>")
        return sb.toString()
    }

    fun start(host: String, port: Int) {
        embeddedServer(Netty, port = port, host = host) {
            routing {
                get("/") { call.respondText(page("堆影取证室 · 内存快照 / 弱引用边 / 保留路径候选", reviewBody(store.maxSeq())), ContentType.Text.Html) }

                get("/replay/{seq}") {
                    val seq = call.parameters["seq"]?.toLongOrNull()?.coerceAtMost(store.maxSeq()) ?: store.maxSeq()
                    call.respondText(page("重放视图 · 事件 #$seq", reviewBody(seq)), ContentType.Text.Html)
                }

                get("/events") {
                    val sb = StringBuilder("<p>只追加事件流；机器可读字段：eventId / type / paramsVersion / affected / attributes。</p>")
                    sb.append(
                        "<h2>导出 → 删除本地数据库 → 再导入（身份/顺序/来源关系往返）</h2>" +
                        "<form method='post' action='/import'><textarea name='payload' rows='6' cols='120' placeholder='粘贴 /export 下载的 JSON 归档'></textarea><br/>" +
                        "<button type='submit'>导入归档</button></form>" +
                        "<p style='font-size:12px'>校验和不匹配（截断/半写）得到「格式损坏」结果且数据库不改动；身份冲突被拒绝。</p>"
                    )
                    sb.append("<table border='1' cellpadding='6' cellspacing='0'><thead><tr><th>#</th><th>eventId</th><th>type</th><th>快照</th><th>参数版本</th><th>摘要</th><th>受影响</th><th>属性</th><th>重放</th></tr></thead><tbody>")
                    for (e in store.allEvents()) {
                        sb.append("<tr><td>${e.seq}</td><td class='mono'>${e.eventId}</td><td>${e.type}</td>")
                        sb.append("<td>${e.snapshotId ?: "—"}</td><td>${e.paramsVersion?.let { "v$it" } ?: "—"}</td>")
                        sb.append("<td>${e.summary}</td><td class='mono'>${e.affected.joinToString(", ")}</td>")
                        sb.append("<td class='evt'>${e.attributes.entries.joinToString(" ") { "${it.key}=${it.value}" }}</td>")
                        sb.append("<td><a href='/replay/${e.seq}'>回到 #${e.seq}</a></td></tr>")
                    }
                    sb.append("</tbody></table>")
                    call.respondText(page("事件档案（被采纳与未采纳结果均保留）", sb.toString()), ContentType.Text.Html)
                }

                get("/materials/{id}") {
                    val id = call.parameters["id"]!!
                    val raw = store.getSnapshotRaw(id) ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
                    val snap = store.getSnapshot(id)!!
                    val sb = StringBuilder()
                    sb.append("<div class='basis'>parserVersion=<b>${snap.parserVersion}</b>；capturedAt=${snap.capturedAt}；不完整块 ${snap.incompleteChunks.size} 个。地址为导入原文（十六进制字符串），不作稳定身份。</div>")
                    sb.append("<h2>不完整块（保留在原位置）</h2><pre>${Views.esc(snap.incompleteChunks.joinToString("\n") { "${it.chunkId} @ ${it.offsetHex} ${it.lengthBytes}B ${it.reason}" })}</pre>")
                    sb.append("<h2>分配点</h2><pre>${Views.esc(snap.objects.joinToString("\n") { it.objectId + "  " + (it.allocationSite ?: "") })}</pre>")
                    sb.append("<h2>原始 JSON（原始表示，未规范化）</h2><pre>${Views.esc(raw)}</pre>")
                    call.respondText(page("原始材料 · $id", sb.toString()), ContentType.Text.Html)
                }

                get("/export") {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"heap-forensics-export.json\"")
                    call.respondText(ExportImport.exportAll(store), ContentType.Application.Json)
                }

                post("/import") {
                    val raw = call.receiveText()
                    val body = raw.substringBefore('&').takeIf { it.startsWith("payload=") }
                        ?.let { java.net.URLDecoder.decode(it.removePrefix("payload="), "UTF-8") } ?: raw
                    val outcome = ExportImport.importAll(store, body)
                    val msg = when (outcome) {
                        is ImportOutcome.Ok -> "导入成功：${outcome.snapshots} 快照 / ${outcome.events} 新事件 / ${outcome.runs} 运行，身份与顺序保持。"
                        is ImportOutcome.FormatCorrupt -> "格式损坏（半写/截断）：${outcome.detail}；数据库未改动。"
                        is ImportOutcome.Conflict -> "身份冲突：${outcome.detail}；导入拒绝。"
                    }
                    call.respondText(page("导入结果", "<p>$msg</p><p><a href='/'>返回复核页</a></p>")), ContentType.Text.Html)
                }

                post("/decisions") {
                    val form = call.receiveParameters()
                    val target = form["target"]?.trim().orEmpty()
                    val kind = form["kind"] ?: "REJECT"
                    val rationale = form["rationale"]?.trim().orEmpty()
                    val snapId = form["snapshotId"]
                    val params = store.currentParams()
                    val prev = store.allEvents().filter { it.type == "DECISION" && it.attributes["target"] == target }
                    val id = "evt-decision-" + Analysis.sha("DECISION|$target|$kind|$rationale|${store.maxSeq() + 1}").take(12)
                    val attrs = buildMap {
                        put("target", target); put("kind", kind); put("rationale", rationale)
                        prev.maxByOrNull { it.seq }?.let { put("conflictsWith", it.eventId) }
                    }
                    store.appendEvent(DomainEvent(id, 0, "DECISION", snapId, params.version,
                        "$kind decision on $target", emptyList(), attrs, Instant.now().toString()))
                    call.respondRedirect("/", permanent = false)
                }
            }
        }.start(wait = true)
    }
}
