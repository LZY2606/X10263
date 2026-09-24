"use strict";
const state = { project: null, snapshot: null, result: null, selected: null, events: [], branch: "baseline", archiveBranch: "boundary-cases" };

async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) throw new Error(path + " -> " + r.status + " " + await r.text());
  return r.json();
}
function esc(s) { return String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c])); }
function fmtBytes(v, unit, inc) { return (inc ? "~" : "") + (v == null ? "—" : (Math.round(v * 100) / 100).toLocaleString()) + " " + unit; }

async function init() {
  state.project = await api("/api/project");
  document.getElementById("projMeta").textContent =
    `${state.project.projectId} · 分支 ${state.project.branches.join(", ")} · 解析器版本 ${JSON.stringify(state.project.parserVersions)} · 当前参数 ${state.project.activePolicyVersion}`;
  fillSelect("snapshotSel", state.project.snapshotIds);
  fillSelect("resultSel", state.project.resultIds);
  await loadEvents();
  if (state.project.snapshotIds[0]) { document.getElementById("snapshotSel").value = state.project.snapshotIds[0]; await loadSnapshot(); }
  const v2 = state.project.resultIds.find(r => r.includes("policy-v2")) || state.project.resultIds[0];
  if (v2) { document.getElementById("resultSel").value = v2; await loadResult(); }
}
function fillSelect(id, vals) {
  document.getElementById(id).innerHTML = vals.map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join("");
}

async function loadSnapshot() {
  const id = document.getElementById("snapshotSel").value;
  state.snapshot = await api(`/api/snapshot/${encodeURIComponent(id)}`);
  renderRaw();
}

async function loadResult(rid) {
  const id = rid || document.getElementById("resultSel").value;
  state.result = await api(`/api/analysis/${encodeURIComponent(id)}`);
  renderResult();
  renderNodes();
}

async function reanalyze() {
  const snapshotId = document.getElementById("snapshotSel").value;
  const policyVersion = document.getElementById("policySel").value;
  const r = await fetch("/api/analysis", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ snapshotId, policyVersion, branch: "baseline" })
  }).then(r => r.json());
  await refreshAfterMutation();
  document.getElementById("resultSel").value = r.resultId;
  await loadResult(r.resultId);
}

function renderResult() {
  const { result, svg, policy } = state.result;
  document.getElementById("svg").innerHTML = svg;
  const b = result.basis;
  document.getElementById("basis").innerHTML =
    `<b>结果身份</b> <span class="ref">${esc(result.resultId)}</span><br/>
     <b>输入摘要</b> objects=${result.inputSummary.objectCount} edges=${result.inputSummary.edgeCount} roots=${result.inputSummary.rootCount} classes=${result.inputSummary.classCount}<br/>
     <b>材料哈希</b> <span class="ref">${esc(result.inputSummary.materialHash.slice(0, 16))}…</span>（材料不变则哈希一致）<br/>
     <b>参数版本</b> ${esc(result.policyVersion)}（weakKeepsAlive=${b.weakKeepsAlive}, internalKeepsAlive=${b.internalKeepsAlive}）<br/>
     <b>单位/坐标/时间基准</b> 大小=${b.sizeUnit}；坐标=${esc(b.coordinateBasis)} (${esc(b.coordinateUnit)})；时间=${esc(b.timeBasis)} ${esc(b.takenAtEpochMs ?? "")}<br/>
     <b>有效区间</b> 自事件 <span class="ref">${esc(result.validFromEventId)}</span> 起${result.validUntilEventId ? `，至 ${esc(result.validUntilEventId)} 止` : "（当前有效）"}<br/>
     <b>父级依赖</b> ${result.parentDependencyIds.map(d => `<span class="ref">${esc(d)}</span>`).join(" ")}<br/>
     <b>可达/不可达</b> ${result.reachableObjectIds.length}/${result.unreachableObjectIds.length}`;
  const inv = document.getElementById("invalid");
  if (result.invalidationReason) {
    inv.style.display = "block";
    inv.innerHTML = `<b>失效原因：</b>${esc(result.invalidationReason)}（旧结论保留，不被最新结果覆盖）`;
  } else inv.style.display = "none";
}

function renderNodes() {
  const { result } = state.result;
  const rows = result.nodes.map(n => {
    const o = state.snapshot.snapshot.objects.find(x => x.objectId === n.objectId);
    const c = state.snapshot.snapshot.classes.find(x => x.classId === (o && o.classId));
    return `<tr>
      <td><button class="small" onclick="selectObject('${esc(n.objectId)}')">${esc(n.objectId)}</button></td>
      <td>${esc(c ? c.name : o ? o.classId : "")}</td>
      <td class="ref">${esc(n.idomParent ? n.idomParent.localId : "—")}</td>
      <td>${fmtBytes(n.retainedBytes, result.basis.sizeUnit, n.retainedIncomplete)}</td>
      <td>${n.rootDistance}</td>
      <td>${n.rootPaths.length}</td>
      <td>${n.leakCandidate ? '<span class="tag WARNING">LEAK候选</span>' : ""}${n.selfIncomplete ? '<span class="tag ERROR">不完整</span>' : ""}${n.retainedIncomplete ? '<span class="tag WARNING">保留=下界</span>' : ""}</td>
    </tr>`;
  }).join("");
  document.getElementById("nodes").innerHTML =
    `<table><thead><tr><th>稳定身份 objectId</th><th>类</th><th>支配父级</th><th>保留大小</th><th>root距离</th><th>等长root路径数</th><th>标记</th></tr></thead><tbody>${rows}</tbody></table>`;
}

function selectObject(id) {
  state.selected = id;
  const { result } = state.result;
  const n = result.nodes.find(x => x.objectId === id);
  const o = state.snapshot.snapshot.objects.find(x => x.objectId === id);
  const c = state.snapshot.snapshot.classes.find(x => x.classId === o.classId);
  const paths = n.rootPaths.map((p, i) => `路径${i + 1} (len=${p.length}): ` + p.join(" → ")).join("\n");
  const edgesIn = state.snapshot.snapshot.edges.filter(e => e.toObjectId === id);
  const edgesOut = state.snapshot.snapshot.edges.filter(e => e.fromObjectId === id);
  const roots = state.snapshot.snapshot.roots.filter(r => r.objectId === id);
  const site = o.allocationSiteId && state.snapshot.snapshot.allocationSites.find(s => s.siteId === o.allocationSiteId);
  document.getElementById("detail").innerHTML =
    `<b>${esc(id)}</b> <span class="ref">${esc(state.snapshot.snapshot.header.snapshotId)}/obj:${esc(id)}</b></span><br/>
     类：${esc(c ? c.name : o.classId)} <span class="muted">（classId=${esc(o.classId)}）</span><br/>
     地址（仅坐标，非身份）：<span class="ref">${esc(o.address.raw)}</span> base=${esc(o.address.base)}<br/>
     shallow：${esc(o.shallowSize.raw)} → ${o.shallowSize.isConvertible ? esc(o.shallowSize.value) + " " + esc(o.shallowSize.unit) : "<b>不可转换（原值保留）</b>"}<br/>
     allocation site：${site ? esc(site.locationRaw) + "（帧基准 " + esc(site.frameBasis || "") + ` 行 ${site.line} 列 ${site.column}）` : "—"}<br/>
     支配父级：<span class="ref">${esc(n.idomParent ? n.idomParent.localId : "(virtual root)")}</span><br/>
     保留大小：${fmtBytes(n.retainedBytes, result.basis.sizeUnit, n.retainedIncomplete)}<br/>
     root 记录：${roots.map(r => `${esc(r.rootId)}(${esc(r.kindRaw)})`).join(", ") || "—"}<br/>
     入边：${edgesIn.map(edgeTag).join(" ") || "—"}<br/>
     出边：${edgesOut.map(edgeTag).join(" ") || "—"}<br/>
     <div class="paths">${esc(paths || "(不可达)")}</div>
     <div class="row" style="margin-top:8px">
       <button class="small primary" onclick="decide('ACCEPTED')">接受泄漏候选</button>
       <button class="small" onclick="decide('REJECTED')">否定</button>
       <button class="small" onclick="decide('ADJUSTED')">参数调整</button>
     </div>`;
}
function edgeTag(e) {
  const k = e.kind.toLowerCase();
  return `<span class="tag ${k}">${esc(e.edgeId)}: ${esc(e.fromObjectId)}→${esc(e.toObjectId)} ${k}${e.label ? " " + esc(e.label) : ""}</span>`;
}

async function decide(verdict) {
  const resultId = state.result.result.resultId;
  await fetch("/api/decision", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ resultId, verdict, policyVersion: state.result.result.policyVersion,
      rationale: `${verdict} @ UI for ${state.selected || ""}` })
  });
  await refreshAfterMutation();
}

function renderRaw() {
  const s = state.snapshot.snapshot;
  const diags = s.diagnostics.map(d =>
    `<tr><td><span class="tag ${d.severity.name}">${d.severity.name}</span></td><td>${esc(d.code)}</td><td class="ref">${esc(d.location)}</td><td>${esc(d.message)}</td></tr>`).join("");
  document.getElementById("rawPanel").innerHTML =
    `<div class="muted">原始 JSON 字符数 ${state.snapshot.rawSizeChars}，原样保留=${state.snapshot.rawPreserved}</div>
     <b>header</b>：snapshotId=<span class="ref">${esc(s.header.snapshotId)}</span>
     parser=<span class="ref">${esc(s.header.parserVersion)}</span>
     format=<span class="ref">${esc(s.header.formatVersion)}</span>
     takenAt=<span class="ref">${esc(s.header.takenAtRaw)}</span> (${esc(s.header.timeBasis)})
     <details><summary>对象（${s.objects.length}）</summary><pre class="raw">${esc(JSON.stringify(s.objects, null, 1))}</pre></details>
     <details><summary>边（${s.edges.length}）</summary><pre class="raw">${esc(JSON.stringify(s.edges, null, 1))}</pre></details>
     <details><summary>roots（${s.roots.length}）</summary><pre class="raw">${esc(JSON.stringify(s.roots, null, 1))}</pre></details>
     <details><summary>allocation sites（${s.allocationSites.length}）/ 不完整块（${s.incompleteBlocks.length}）</summary>
       <pre class="raw">${esc(JSON.stringify({sites: s.allocationSites, blocks: s.incompleteBlocks}, null, 1))}</pre></details>
     <table><thead><tr><th>级别</th><th>代码</th><th>位置</th><th>诊断</th></tr></thead><tbody>${diags}</tbody></table>`;
}

async function loadEvents() {
  const q = state.branch === "__all__" ? "" : `?branch=${encodeURIComponent(state.branch)}`;
  state.events = (await api(`/api/events${q}`)).events;
  const curResult = state.result ? state.result.result.resultId : null;
  document.getElementById("events").innerHTML = state.events.map(e => {
    const p = e.payload;
    let label = p.type;
    if (p.type === "Analysis") label = `计算 ${esc(p.policyVersion)} → ${esc(p.resultId).slice(0, 52)}`;
    if (p.type === "ImportSnapshot") label = `导入 ${esc(p.materialRef)} (${esc(p.parserVersion)})`;
    if (p.type === "ParamRevision") label = `参数修订 ${esc(p.oldPolicyVersion)} → ${esc(p.newPolicyVersion)}`;
    if (p.type === "Decision") label = `人工决定 ${esc(p.verdict)} @ ${esc(p.targetResultId).slice(0, 36)}`;
    if (p.type === "CounterExample") label = `反例 ${esc(p.caseId)} ${esc(p.mutation)}`;
    if (p.type === "SourceChange") label = `局部源改动 ${esc(p.materialRef)} [${p.changedLocalIds.length} ids]`;
    return `<div class="event" onclick="travelTo('${esc(e.eventId)}','${esc(e.branch)}')">
      <div><span class="seq">#${e.seq}</span> ${esc(e.branch)} · ${label}</div>
      <div class="muted">${esc(e.eventId)}${p.type === "Decision" && p.conflictsWithEventId ? " · 冲突于 " + esc(p.conflictsWithEventId) : ""}</div>
    </div>`;
  }).join("");
}

async function travelTo(eventId, branch) {
  const view = await api(`/api/events/${encodeURIComponent(eventId)}/view?branch=${encodeURIComponent(branch)}`);
  const details = view.events.map(e => {
    const p = e.payload;
    return p.type === "Analysis" ? `<li>计算 <span class="ref">${esc(p.resultId)}</span> (${esc(p.policyVersion)}) 输入hash=${esc(p.inputSummary.materialHash.slice(0,12))}…</li>` :
      p.type === "Decision" ? `<li>决定 ${esc(p.verdict)} → <span class="ref">${esc(p.targetResultId)}</span></li>` :
      `<li>${esc(p.type)}</li>`;
  }).join("");
  document.getElementById("detail").innerHTML =
    `<b>时间旅行到 ${esc(eventId)}</b><br/>
     当时生效参数：<b>${esc(view.policyVersionAtPoint)}</b><br/>
     当时可见结果：<br/><ul class="muted">${details}</ul>
     可见结果 id：${view.visibleResultIds.map(x => `<span class="ref">${esc(x)}</span>`).join(" ")}<br/>
     <span class="muted">此视图由事件前缀重放得到，不使用最新结果覆盖。</span>`;
  document.querySelectorAll(".event").forEach(el => el.classList.remove("active"));
}

async function loadArchive(branch) {
  state.archiveBranch = branch;
  const a = await api(`/api/archive?branch=${encodeURIComponent(branch)}`);
  document.getElementById("archive").innerHTML = a.counterExamples.map(ce => {
    const j = ce.judgement;
    return `<details><summary>${esc(ce.caseId)} · ${esc(ce.mutation)} · 只改 <span class="ref">${esc(ce.directSource)}</span>
        ${j ? (j.holds ? ' <span class="tag SAME">原则成立</span>' : ' <span class="tag REMOVED">原则不成立</span>') : ""}
        采纳 <span class="ref">${esc(ce.adoptedResultId || "").slice(0,30)}</span>
        ${ce.rejectedResultId ? ` 未采纳 <span class="ref">${esc(ce.rejectedResultId).slice(0,30)}</span>` : ""}
      </summary>
      <p class="muted">${esc(ce.description || ce.scopeClaim)}</p>
      <div>输入片段：<pre class="raw">${esc(JSON.stringify(ce.inputFragment, null, 1))}</pre></div>
      <div>受影响对象：${ce.affectedObjectIds.map(x => `<span class="ref">${esc(x)}</span>`).join(" ")}</div>
      <div>影响范围主张：${esc(ce.scopeClaim)} → <b>${ce.scopeHolds ? "限制在正确范围" : "范围越界"}</b></div>
      ${j ? `<div>机器可读判定：<pre class="raw">${esc(JSON.stringify(j, null, 1))}</pre></div>` : ""}
    </details>`;
  }).join("") || "<span class='muted'>该分支无反例</span>";
}

async function loadDiff() {
  const d = await api("/api/diff");
  const rows = d.diff.rows.map(r => `<tr>
      <td class="ref">${esc(r.baseClassRef ? r.baseClassRef.localId : "—")}</td>
      <td>${esc(r.baseClassName || "—")}</td>
      <td class="ref">${esc(r.otherClassRef ? r.otherClassRef.localId : "—")}</td>
      <td>${esc(r.otherClassName || "—")}</td>
      <td><span class="tag ${esc(r.correspondence)}">${esc(r.correspondence)}</span></td>
      <td>${r.baseObjectCount} → ${r.otherObjectCount}</td>
      <td>${r.baseRetainedBytes == null ? "—" : Math.round(r.baseRetainedBytes)} → ${r.otherRetainedBytes == null ? "—" : Math.round(r.otherRetainedBytes)} ${esc(r.unit)}</td>
      <td class="muted">改名提示=${r.evidence.explicitRenameHint} 同对象=${r.evidence.sharedObjectStableIds.length} 同站点=${r.evidence.sharedAllocationSiteIds.length}</td>
    </tr>`).join("");
  document.getElementById("diff").innerHTML =
    `<div class="muted">${esc(d.diff.snapshotIdentityNote)}</div>
     <table><thead><tr><th>base类身份</th><th>base名称</th><th>other类身份</th><th>other名称</th><th>对应</th><th>对象数</th><th>保留大小</th><th>证据</th></tr></thead>
     <tbody>${rows}</tbody></table>
     ${d.diff.diagnostics.length ? `<div>${d.diff.diagnostics.map(x => `<span class="tag ${x.severity.name}">${esc(x.code)}</span> ${esc(x.message)}`).join("<br/>")}</div>` : ""}`;
}

async function exportProject() {
  const r = await fetch("/api/export");
  const blob = await r.blob();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "heap-shadow-export.zip";
  a.click();
}

async function reimport(input) {
  const file = input.files[0];
  if (!file) return;
  const buf = new Uint8Array(await file.arrayBuffer());
  const r = await fetch("/api/import-export", { method: "POST", body: buf });
  const report = await r.json();
  alert(`再导入完成：materials=${report.materials} events=${report.events} analyses=${report.analyses}\n` +
    (report.corruptFrame ? `损坏帧：${report.corruptFrame.entryName} ${report.corruptFrame.reason}（完整前缀 ${report.framesAppliedPrefix} 已应用）` : "全部帧 CRC 通过"));
  await refreshAfterMutation();
}

async function refreshAfterMutation() {
  state.project = await api("/api/project");
  fillSelect("snapshotSel", state.project.snapshotIds);
  fillSelect("resultSel", state.project.resultIds);
  await loadEvents();
}

init().catch(e => {
  document.getElementById("projMeta").textContent = "初始化失败: " + e.message;
  console.error(e);
});
