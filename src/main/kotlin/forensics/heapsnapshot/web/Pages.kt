package forensics.heapsnapshot.web

import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.storage.EventPayload
import forensics.heapsnapshot.storage.Reducer

/** 服务端渲染审阅页（纯字符串，无前端框架）。展示值全部带稳定身份。 */
object Pages {

    fun latestResult(ctx: WebContext, projectId: String, snapshotHint: String?, atEventId: String?): AnalysisResult {
        val events = ctx.repo.events(projectId)
        val lineageIds = Reducer.lineage(events, atEventId ?: ctx.repo.tipEventId(projectId)).map { it.eventId }.toSet()
        val visible = events
            .filter { it.eventId in lineageIds && it.type == "AnalysisComputed" }
            .mapNotNull { ctx.repo.analysisResult(it.eventId) }
        val hintIdentity = snapshotHint?.removePrefix("snap-")?.substringBefore("@")
        return visible.lastOrNull { it.snapshotIdentity == hintIdentity }
            ?: visible.firstOrNull()
            ?: ctx.repo.allAnalysis(projectId).map { it.second }.firstOrNull()
            ?: error("项目尚无分析结果")
    }

    @Suppress("UNUSED_VARIABLE")
    fun reviewPage(ctx: WebContext, projectId: String, atEventId: String?): String {
        val events = ctx.repo.events(projectId)
        val project = ctx.repo.listProjects().first { it.id == projectId }
        val tip = atEventId ?: ctx.repo.tipEventId(projectId)
        val state = ctx.service.stateAt(projectId, tip)
        val snapshots = ctx.repo.listSnapshots(projectId)
        val lineageIds = Reducer.lineage(events, tip).map { it.eventId }.toSet()

        val visibleResults = events
            .filter { it.eventId in lineageIds && it.type == "AnalysisComputed" }
            .mapNotNull { ctx.repo.analysisResult(it.eventId) }
        val primary = visibleResults.firstOrNull()

        fun h(s: Any?) = s.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val timeline = buildString {
            append("<p class='muted'>点击任意事件回到当时的依赖版本与可见结论；绿色为祖先链，历史不会被最新结果覆盖。</p>")
            events.forEach { e ->
                val cls = if (e.eventId in lineageIds) "evt ancestor" else "evt off"
                append("<a class='$cls' href='/projects/$projectId/review?at=${h(e.eventId)}'>#${e.seq} ${h(e.type)} · ${h(e.actor)}</a>")
            }
            append("<p><a href='/projects/$projectId/review'>回到最新</a></p>")
        }

        val materials = buildString {
            snapshots.forEach { s ->
                append("<p>快照身份 <b>${h(s.identity)}</b> / <code>${h(s.snapshotId)}</code><br/>")
                append("<a target='_blank' href='/api/projects/$projectId/snapshot/${h(s.snapshotId)}'>查看原始 HSP</a> · ")
                append("<a target='_blank' href='/projects/$projectId/graph.svg?snapshot=${h(s.snapshotId)}'>SVG 对象图</a></p>")
            }
        }

        val graphSection = buildString {
            val graphSnap = snapshots.firstOrNull { s -> primary?.snapshotIdentity == s.identity }?.snapshotId
                ?: snapshots.firstOrNull()?.snapshotId
            if (graphSnap != null) {
                append("<img src='/projects/$projectId/graph.svg?snapshot=${h(graphSnap)}&at=${h(tip ?: "")}' style='max-width:100%;border:1px solid #cbd5e1;border-radius:8px'/>")
                append("<p class='muted'>坐标基准 svg-pixel@layout-v1；大小单位 bytes；实线 strong、紫虚线 weak（策略决定）、青点线 internal、橙粗线支配树边。</p>")
            }
            if (primary != null) {
                append("<table><thead><tr><th>对象身份</th><th>类(身份/名)</th><th>地址原值</th><th>reachable</th><th>self(原值/bytes)</th><th>retained bytes</th><th>支配父</th><th>root路径数</th><th>状态/失效原因</th></tr></thead><tbody>")
                primary.nodes.sortedBy { it.id }.forEach { n ->
                    append("<tr><td><b>${h(n.id)}</b></td><td>${h(n.classId)} / ${h(n.className)}</td><td>${h(n.addressRaw)}</td>")
                    append("<td>${if (n.reachable) "yes" else "no"}</td><td>${h(n.selfSizeRaw)} / ${n.selfSizeBytes ?: "—"}</td>")
                    append("<td>${n.retainedSizeBytes?.toString() ?: "—（${h(n.retainedStatus)}）"}</td>")
                    append("<td>${h(n.dominator ?: "VROOT/excluded")}</td><td>${n.rootPathCount}</td><td>${h(n.retainedStatus)}</td></tr>")
                }
                append("</tbody></table>")
            }
        }

        val pathsSection = buildString {
            visibleResults.firstOrNull()?.rootPaths?.forEach { (objId, paths) ->
                append("<div><p><b>${h(objId)}</b> — ${paths.size} 条最短路径（hops=${paths.firstOrNull()?.hops}，全部等长候选保留）</p><ul>")
                paths.take(16).forEach { rp ->
                    append("<li>root=${h(rp.rootRef)}(${h(rp.rootKind)}) :: ${h(rp.objectIds.joinToString(" → "))} [${h(rp.edgeKinds.joinToString(","))}]</li>")
                }
                append("</ul></div>")
            }
        }

        val candidateSection = buildString {
            val r = visibleResults.lastOrNull()
            if (r != null) {
                append("<table><thead><tr><th>候选身份</th><th>对象</th><th>判定理由码</th><th>retained</th><th>支配父</th><th>等长路径数</th><th>人工决定（新事件）</th></tr></thead><tbody>")
                r.leakCandidates.forEach { c ->
                    val decision = state.decisions[c.id]?.decision ?: "待定"
                    append("<tr><td>${h(c.id)}</td><td><b>${h(c.objectId)}</b></td><td>")
                    c.reasonCodes.forEach { append("<span class='tag'>${h(it)}</span>") }
                    append("</td><td>${c.retainedSizeBytes ?: "—"}</td><td>${h(c.dominator ?: "—")}</td><td>${c.rootPathCount}</td>")
                    append("<td>$decision <a href='#' data-adjudicate='${h(c.id)}|ACCEPTED'>接受</a> / <a href='#' data-adjudicate='${h(c.id)}|REJECTED'>否定</a></td></tr>")
                }
                append("</tbody></table>")
            }
        }

        val diffSection = buildString {
            append("<p class='muted'>差异生成 DiffComputed 事件；身份依据 identityBasis（CORRESPONDENCE_DIRECTIVE / STABLE_CLASS_KEY）。</p>")
            if (snapshots.size >= 2) {
                append("<form method='post' action='/projects/$projectId/diff?at=${h(tip ?: "")}'>")
                append("<input type='hidden' name='beforeId' value='${h(snapshots.first().snapshotId)}'/>")
                append("<input type='hidden' name='afterId' value='${h(snapshots.last().snapshotId)}'/>")
                append("<button type='submit'>计算两快照类群差异（写入事件）</button></form>")
                append("<div id='diff-out'></div>")
            }
        }

        val notes = primary?.notes?.joinToString("<br/>") { h(it) } ?: ""

        return """<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>
<title>堆影取证室 · ${h(project.name)}</title><style>
body{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:0;background:#f1f5f9;color:#0f172a}
header{background:#0f172a;color:#f8fafc;padding:14px 22px}
main{display:grid;grid-template-columns:320px 1fr;gap:16px;padding:16px}
section{background:#fff;border:1px solid #cbd5e1;border-radius:10px;padding:14px;margin-bottom:14px}
table{border-collapse:collapse;width:100%;font-size:12px}
th,td{border:1px solid #e2e8f0;padding:4px 7px;text-align:left;vertical-align:top}
th{background:#f8fafc}.muted{color:#64748b;font-size:12px}
.tag{display:inline-block;background:#e0e7ff;color:#3730a3;border-radius:6px;padding:1px 6px;margin:1px;font-size:11px}
a{color:#1d4ed8}.evt{padding:5px 7px;border-radius:6px;font-size:12px;display:block;margin:2px 0;border:1px solid #e2e8f0;text-decoration:none}
.evt.ancestor{background:#ecfdf5;border-color:#86efac}.evt.off{background:#f8fafc;color:#94a3b8}
input,select,textarea,button{font-family:inherit;font-size:12px;padding:3px 6px;margin:3px 0}
pre{background:#0b1020;color:#d7e0ee;padding:10px;border-radius:8px;overflow:auto;max-height:340px;font-size:11px}
</style></head><body>
<header><h2 style='margin:0'>堆影取证室：内存快照、弱引用边与保留路径候选</h2>
<p class='muted' style='color:#cbd5e1'>项目身份 ${h(project.id)} · 案例包 ${h(project.casePackage ?: "（手动）")} · 视图事件=${h(tip ?: "（无）")} · 分支=${h(state.branch)} · 参数版本=v${state.currentParams.version}（weak保活=${state.currentParams.weakEdgesKeepAlive}）</p></header>
<main><div>
<section><h3>事件时间线</h3>$timeline</section>
<section><h3>原始材料（不可变）</h3>$materials<p class='muted'>保留：原始对象 id、地址、类元数据、强/弱/内部边、root 分类、不完整块、解析器版本；单位/坐标基准随材料保留。</p></section>
<section><h3>导入（HSP 文本）</h3>
<textarea id='hsp-in' rows='6' cols='36' placeholder='粘贴 SNAPSHOT ... HSP 文本'></textarea><br/>
<button id='hsp-btn'>导入为新快照（新事件）</button><pre id='hsp-out'></pre>
<p><a href='/projects/$projectId/export'>导出项目 JSON（导出后可删库再导入）</a></p>
</section></div><div>
<section><h3>计算说明（有效区间 / 失效原因）</h3><div class='muted'>$notes</div></section>
<section><h3>对象图 / 支配父级 / 保留大小</h3>$graphSection</section>
<section><h3>Root 路径（多 root 等长路径全部保留）</h3>$pathsSection</section>
<section><h3>泄漏路径候选</h3>$candidateSection</section>
<section><h3>两快照类群差异（类改名身份对应）</h3>$diffSection</section>
</div></main>
<script>
const P='$projectId';
document.querySelectorAll('[data-adjudicate]').forEach(a=>a.addEventListener('click',async e=>{
  e.preventDefault();const [cid,d]=a.dataset.adjudicate.split('|');
  const r=await fetch('/api/projects/'+P+'/adjudicate',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({candidateId:cid,decision:d,rationaleCode:'MANUAL_REVIEW_'+d,rationaleText:'审阅页人工决定'})});
  if(r.ok) location.reload(); else alert('决定失败: '+await r.text());
}));
document.getElementById('hsp-btn').onclick=async()=>{
  const body=document.getElementById('hsp-in').value;
  const r=await fetch('/api/projects/'+P+'/import?name=manual.hsp',{method:'POST',headers:{'Content-Type':'text/plain'},body});
  document.getElementById('hsp-out').textContent=r.status+' '+JSON.stringify(await r.json(),null,2);
  if(r.ok) location.reload();
};
const diffForm=document.querySelector('form[action$="/diff?at='+""+'"]');
</script></body></html>"""
    }
}
