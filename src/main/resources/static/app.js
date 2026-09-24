"use strict";
const $ = (s) => document.querySelector(s);
const api = async (path, opts) => {
  const r = await fetch(path, opts);
  const ct = r.headers.get("content-type") || "";
  const body = ct.includes("json") ? await r.json() : await r.text();
  if (!r.ok) throw body;
  return body;
};
const postJson = (path, obj) => api(path, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj),
});
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

let STATE = null;
let SELECTED = { snapshot: null, version: 1, object: null, analysis: null, snapshotDetail: null };
let DECISION_SUBJECT = null;

async function refresh() {
  STATE = await api("/api/state");
  renderAll();
}

function versionOptions() {
  let html = "";
  for (let v = 1; v <= STATE.currentParamsVersion; v++) html += `<option value="${v}">v${v}</option>`;
  return html;
}

function renderAll() {
  const ss = STATE.snapshots;
  if (!SELECTED.snapshot || !ss.find((s) => s.snapshotId === SELECTED.snapshot)) {
    SELECTED.snapshot = ss[0] ? ss[0].snapshotId : null;
  }
  SELECTED.version = Math.min(SELECTED.version || 1, STATE.currentParamsVersion);
  $("#snapshotSelect").innerHTML = ss.map((s) =>
    `<option value="${s.snapshotId}"${s.snapshotId === SELECTED.snapshot ? " selected" : ""}>
      ${s.snapshotId}（${s.objectCount}对象）${s.parentSnapshotId ? " ←补丁自 " + s.parentSnapshotId : ""}</option>`).join("");
  $("#versionSelect").innerHTML = versionOptions();
  $("#versionSelect").value = SELECTED.version;
  $("#weakKeepAlive").checked = STATE.currentParams.weakKeepAlive;
  $("#internalKeepAlive").checked = STATE.currentParams.internalKeepAlive;
  $("#metaBar").textContent =
    `当前参数 v${STATE.currentParams.version}: weakKeepAlive=${STATE.currentParams.weakKeepAlive}, ` +
    `internalKeepAlive=${STATE.currentParams.internalKeepAlive}, 预算 maxVisits=${STATE.currentParams.maxVisits} · ` +
    `快照 ${ss.length} · 事件 ${STATE.events.length} · 计算 ${STATE.computations.length}`;
  const opts = ss.map((s) => `<option value="${s.snapshotId}">${s.snapshotId}</option>`).join("");
  $("#diffA").innerHTML = opts; $("#diffB").innerHTML = opts;
  renderEvents();
  if (SELECTED.snapshot) { loadAnalysis(); loadMaterials(); }
}

function renderEvents() {
  $("#events").innerHTML = STATE.events.map((e) => {
    const p = e.payload;
    let detail = "";
    if (e.kind === "IMPORT_SNAPSHOT") detail = `导入 ${p.snapshotId}（${p.parserVersion}，对象 ${p.objectCount}，边 ${p.edgeCount}，不完整块 ${p.incompleteBlockCount}）`;
    else if (e.kind === "COMPUTED") detail = `${p.kind} ${p.snapshotId} @v${p.paramsVersion} => ${p.status}`;
    else if (e.kind === "REVISE_PARAMS") detail = `参数 v${p.fromVersion} → v${p.toVersion}：${p.reason}`;
    else if (e.kind === "APPLY_SOURCE_PATCH") detail = `补丁 ${p.patchName}: ${p.parentSnapshotId} → ${p.snapshotId}`;
    else if (e.kind === "RECORD_DECISION") detail = `${p.verdict} ${p.subject}（@v${p.paramsVersion}）: ${p.rationale}`;
    return `<div class="ev ${e.kind}" data-stable-id="event:${e.eventId}" data-seq="${e.seq}">
      <span class="id">${e.eventId}</span> <span class="tag kind">${e.kind}</span><br/>${esc(detail)}
      <div style="color:#9ca3af">${e.createdAt} · ${e.actor}${e.parentSeq ? " · 父 E" + String(e.parentSeq).padStart(4, "0") : ""}</div></div>`;
  }).join("");
  document.querySelectorAll(".ev").forEach((el) =>
    el.addEventListener("click", () => timeline(parseInt(el.dataset.seq, 10))));
}

async function loadAnalysis() {
  try {
    const [a, snap] = await Promise.all([
      api(`/api/analyze?snapshot=${encodeURIComponent(SELECTED.snapshot)}&v=${SELECTED.version}`),
      api(`/api/snapshots/${encodeURIComponent(SELECTED.snapshot)}`),
    ]);
    SELECTED.analysis = a;
    SELECTED.snapshotDetail = snap;
    renderAnalysis(a, snap);
  } catch (e) {
    renderFailure(e);
  }
}

function renderFailure(e) {
  const type = e && e.type ? e.type : "ERROR";
  let detail = "";
  if (type === "BUDGET_EXHAUSTED") detail = `预算耗尽 ${e.budget}: ${e.detail}`;
  else if (type === "FORMAT_CORRUPT") detail = "格式损坏：<br/>" + e.diagnostics.map((d) =>
    `第 ${d.line} 行 [${d.severity}] ${esc(d.message)}`).join("<br/>");
  else if (type === "SEMANTICALLY_UNSOLVABLE") detail = "语义无解：" + e.reasons.map(esc).join("；");
  else detail = esc(typeof e === "string" ? e : JSON.stringify(e));
  $("#graphWrap").innerHTML = `<div style="padding:16px"><span class="status-chip ${type}">${type}</span><p class="invalid">${detail}</p></div>`;
  $("#domTable").innerHTML = $("#leakList").innerHTML = $("#paths").innerHTML =
    $("#objectDetail").innerHTML = `<span class="status-chip ${type}">${type}</span>`;
}

function renderAnalysis(a, snap) {
  const an = a.analysis;
  fetch(`/api/graph.svg?snapshot=${encodeURIComponent(SELECTED.snapshot)}&v=${SELECTED.version}`)
    .then((r) => r.text())
    .then((svgText) => {
      $("#graphWrap").innerHTML = svgText;
      $("#graphWrap").querySelectorAll(".node").forEach((g) =>
        g.addEventListener("click", () => selectObject(g.getAttribute("data-object-id"))));
    });
  renderDom(an, snap);
  renderLeaks(an);
  renderPaths(an, snap);
}

function renderDom(an, snap) {
  const rows = Object.keys(an.dominators.idom).sort().map((id) => {
    const ret = an.dominators.retained[id];
    const retCell = ret.valid
      ? `<span class="tag ok">精确</span> ${ret.exactBytes} B`
      : `<span class="tag warn">下界/失效</span> ≥ ${ret.lowerBoundBytes} B
         <div class="invalid">${ret.invalidReasons.map(esc).join("<br/>")}</div>`;
    return `<tr data-stable-id="dom:${SELECTED.snapshot}:v${SELECTED.version}:${id}">
      <td class="mono"><a href="#" data-obj="${id}">${id}</a></td>
      <td class="mono">${an.dominators.idom[id] ? esc(an.dominators.idom[id]) : "&lt;roots&gt;"}</td>
      <td>${ret.dominated.length}</td><td>${retCell}</td></tr>`;
  }).join("");
  $("#domTable").innerHTML = `<table><thead><tr><th>对象身份</th><th>支配父级</th><th>支配对象数</th><th>保留大小（单位 B）</th></tr></thead><tbody>${rows}</tbody></table>`;
  $("#domTable").querySelectorAll("a[data-obj]").forEach((a) =>
    a.addEventListener("click", (e) => { e.preventDefault(); selectObject(a.dataset.obj); }));
}

function renderLeaks(an) {
  if (!an.leakCandidates.length) { $("#leakList").innerHTML = "<p>无候选。</p>"; return; }
  $("#leakList").innerHTML = an.leakCandidates.map((c) => {
    const size = c.retainedExact === null ? `≥ ${c.retainedLowerBound} B ⚠` : `${c.retainedExact} B`;
    return `<div class="pathbox" data-stable-id="leak:${SELECTED.snapshot}:v${SELECTED.version}:${c.objectId}">
      <a href="#" data-obj="${c.objectId}"><b>${c.objectId}</b></a> 保留 ${size}，支配 ${c.dominatedCount} 个，root 深度 ${c.shortestRootDepth}
      <button data-decide="snap-leak:${c.objectId}" style="margin-left:8px">记录决定</button></div>`;
  }).join("");
  bindDecideButtons("#leakList");
  $("#leakList").querySelectorAll("a[data-obj]").forEach((a) =>
    a.addEventListener("click", (e) => { e.preventDefault(); selectObject(a.dataset.obj); }));
}

function pathStr(p) {
  const start = p.steps.length ? p.steps[0].fromId : "";
  const chain = p.steps.map((s) =>
    `${s.fromId} —${s.kind}${s.fieldName ? "[" + s.fieldName + "]" : ""}→ ${s.toId}`).join("<br/>");
  return `<div class="pathbox"><span class="tag kind">${p.rootKind}</span> ${chain}</div>`;
}

function renderPaths(an, snap) {
  const boxes = Object.values(an.paths).sort((x, y) => x.objectId.localeCompare(y.objectId)).map((op) =>
    `<details ${op.objectId === SELECTED.object ? "open" : ""} data-stable-id="paths:${SELECTED.snapshot}:v${SELECTED.version}:${op.objectId}">
      <summary><b>${op.objectId}</b> 最短深度 ${op.shortestDepth}，等长路径 ${op.paths.length} 条${op.truncated ? "（已达上限截断）" : ""}</summary>
      ${op.paths.map(pathStr).join("")}</details>`).join("");
  const extra = an.addressCollisions.length
    ? `<p class="invalid">地址冲突（不作身份合并）：${an.addressCollisions.map((c) =>
        `${c.address}: ${c.objectIds.join(" / ")}`).join("；")}</p>` : "";
  $("#paths").innerHTML = boxes + extra;
}

function selectObject(id) {
  SELECTED.object = id;
  const an = SELECTED.analysis.analysis, snap = SELECTED.snapshotDetail;
  const o = snap.objects.find((x) => x.objectId === id);
  if (!o) return;
  const cls = snap.classes.find((c) => c.classId === o.classId);
  const ret = an.dominators.retained[id];
  const paths = an.paths[id];
  const rootsIn = snap.roots.filter((r) => r.objectId === id).map((r) => r.kind).join(", ") || "—";
  $("#objectDetail").innerHTML = `
    <div data-stable-id="object:${SELECTED.snapshot}:${id}">
      <b>稳定身份 ${id}</b> · 地址（原文，仅展示）<span class="mono">${esc(o.address)}</span>
      · 类 <span class="mono">${esc(o.classId)} ${esc(cls ? cls.name : "<缺失>")}</span>
      · 自身大小 ${o.sizeBytes} B${o.incomplete ? ' · <span class="tag bad">不完整对象</span>' : ""}
      <div>allocation site: <span class="mono">${esc(o.allocSite || "—")}</span></div>
      <div>root 分类: ${rootsIn} · 支配父级: <span class="mono">${an.dominators.idom[id] ?? "&lt;roots&gt;"}</span></div>
      <div>保留大小: ${ret.valid ? ret.exactBytes + " B（有效）" : `≥ ${ret.lowerBoundBytes} B（失效：${ret.invalidReasons.map(esc).join("；")}）`}</div>
      <div>等长 root 路径 ${paths ? paths.paths.length : 0} 条${paths && paths.truncated ? "（截断）" : ""}</div>
      <button data-decide="snap-obj:${id}:retention">对保留结论做决定</button>
    </div>`;
  bindDecideButtons("#objectDetail");
  renderPaths(an, snap); renderDom(an, snap);
}

async function loadMaterials() {
  const snap = SELECTED.snapshotDetail || await api(`/api/snapshots/${encodeURIComponent(SELECTED.snapshot)}`);
  SELECTED.snapshotDetail = snap;
  const diagRows = snap.diagnostics.map((d) =>
    `<tr><td>${d.line}</td><td>${d.severity}</td><td>${esc(d.message)}</td><td class="mono">${esc(d.raw)}</td></tr>`).join("");
  const blocks = snap.incompleteBlocks.map((b) =>
    `<details><summary>${b.blockId}（第 ${b.line} 行，${esc(b.reason)}）</summary><pre>${esc(b.rawText)}</pre></details>`).join("");
  $("#materials").innerHTML = `
    <div data-stable-id="material:${snap.snapshotId}:meta">
      <b>${snap.snapshotId}</b> · parser ${esc(snap.parserVersion)}<br/>
      时间基准: ${esc(snap.capturedAt)} (epoch=${snap.epochMillis} ms)<br/>
      原始对象 ${snap.objects.length} · 边 ${snap.edges.length}（强 ${snap.edges.filter((e)=>e.kind==="STRONG").length}
      /弱 ${snap.edges.filter((e)=>e.kind==="WEAK").length}/内部 ${snap.edges.filter((e)=>e.kind==="INTERNAL").length}）
      · root ${snap.roots.length} · 不完整块 ${snap.incompleteBlocks.length}
    </div>
    <details><summary>不完整块原始内容（保留，不丢）</summary>${blocks || "无"}</details>
    <details><summary>诊断（带行号/原始行）</summary>
      <table><thead><tr><th>行</th><th>级别</th><th>信息</th><th>原始行</th></tr></thead><tbody>${diagRows || "<tr><td colspan=4>无</td></tr>"}</tbody></table>
    </details>
    <details><summary>原始 HSF 文本</summary><pre>${esc(await api("/api/snapshots/" + encodeURIComponent(snap.snapshotId) + "/raw"))}</pre></details>`;
}

async function timeline(seq) {
  const tv = await api(`/api/events/${seq}`);
  const e = tv.at;
  let body = `<p><b>${e.eventId} ${e.kind}</b> · ${e.createdAt} · ${e.actor}</p>
    <pre>${esc(JSON.stringify(e.payload, null, 2))}</pre><p>${esc(tv.note)}</p>`;
  if (tv.pinnedComputation) {
    const c = tv.pinnedComputation;
    body += `<p>钉住快照 <b>${c.snapshotId}</b> @v${c.paramsVersion}，输入摘要 <span class="mono">${c.inputDigest.slice(0, 18)}…</span>，状态 <span class="status-chip ${c.status}">${c.status}</span></p>`;
    if (c.status === "OK") {
      $("#snapshotSelect").value = c.snapshotId; SELECTED.snapshot = c.snapshotId;
      SELECTED.version = c.paramsVersion; $("#versionSelect").value = c.paramsVersion;
      await loadAnalysis();
    }
  }
  $("#objectDetail").innerHTML = `<div data-stable-id="timeline:${e.eventId}">${body}</div>`;
}

function bindDecideButtons(sel) {
  document.querySelectorAll(sel + " [data-decide]").forEach((b) =>
    b.addEventListener("click", () => openDecision(b.dataset.decide)));
}
function openDecision(subject) {
  DECISION_SUBJECT = subject;
  $("#decisionSubject").textContent = "对象: " + subject + " · 参数版本 v" + SELECTED.version;
  $("#dlgDecision").showModal();
}

$("#btnDiff").addEventListener("click", async () => {
  try {
    const d = await api(`/api/diff?a=${encodeURIComponent($("#diffA").value)}&b=${encodeURIComponent($("#diffB").value)}`);
    const rows = d.diff.groups.map((g) => `<tr data-stable-id="diff:${g.classId}">
      <td class="mono">${g.classId}</td><td><span class="tag ${g.status === "RENAMED" ? "warn" : "ok"}">${g.status}</span></td>
      <td>${esc(g.nameA || "—")}</td><td>${esc(g.nameB || "—")}</td>
      <td>${g.countA}→${g.countB} (${g.countDelta >= 0 ? "+" : ""}${g.countDelta})</td>
      <td>${g.bytesA}→${g.bytesB} B (${g.bytesDelta >= 0 ? "+" : ""}${g.bytesDelta})</td></tr>`).join("");
    const notes = d.diff.identityNotes.map((n) =>
      `<p><b>${esc(n.note)}</b><br/>${n.examples.map(esc).join("<br/>")}</p>`).join("");
    $("#diffPanel").innerHTML = `<p>输入摘要 <span class="mono">${d.inputDigest.slice(0, 18)}…</span> @v${d.paramsVersion}</p>
      ${notes}<table><thead><tr><th>classId 身份</th><th>状态</th><th>名(前)</th><th>名(后)</th><th>对象数</th><th>字节</th></tr></thead><tbody>${rows}</tbody></table>`;
  } catch (e) { $("#diffPanel").innerHTML = `<span class="invalid">${esc(JSON.stringify(e))}</span>`; }
});

async function loadCasePack() {
  const p = await api("/api/casepack/expectations");
  $("#casePanel").innerHTML = p.expectations.map((x) => {
    const adopted = (x.adopted || []).map((s) => `<li>✅ ${esc(s)}</li>`).join("");
    const rejected = (x.rejected || []).map((s) => `<li>🚫 ${esc(s)}</li>`).join("");
    return `<details data-stable-id="case:${x.id}"><summary><b>${x.id}</b> @v${x.paramsVersion ?? "-"}
      ${x.scenarioId ? "· " + x.scenarioId : ""}</summary>
      <p>${esc(x.basis)}</p><ul>${adopted}${rejected}</ul></details>`;
  }).join("");
}

$("#snapshotSelect").addEventListener("change", (e) => { SELECTED.snapshot = e.target.value; SELECTED.object = null; loadAnalysis(); loadMaterials(); });
$("#versionSelect").addEventListener("change", (e) => { SELECTED.version = parseInt(e.target.value, 10); SELECTED.object = null; loadAnalysis(); });
$("#btnAnalyze").addEventListener("click", () => { SELECTED.version = parseInt($("#versionSelect").value, 10); loadAnalysis(); });
$("#btnParams").addEventListener("click", async () => {
  await postJson("/api/params", {
    weakKeepAlive: $("#weakKeepAlive").checked, internalKeepAlive: $("#internalKeepAlive").checked,
    reason: $("#paramReason").value || "界面参数修订",
  });
  await refresh();
});
$("#btnImport").addEventListener("click", () => $("#dlgImport").showModal());
$("#dlgImportCancel").addEventListener("click", () => $("#dlgImport").close());
$("#dlgImportGo").addEventListener("click", async () => {
  try {
    await postJson("/api/import", { sourceName: $("#importName").value || "界面导入", text: $("#importText").value });
    $("#dlgImport").close(); $("#importText").value = "";
  } catch (e) { alert("导入失败（三类结果之一）:\n" + JSON.stringify(e, null, 2)); }
  await refresh();
});
$("#btnPatch").addEventListener("click", () => $("#dlgPatch").showModal());
$("#dlgPatchCancel").addEventListener("click", () => $("#dlgPatch").close());
$("#dlgPatchGo").addEventListener("click", async () => {
  try {
    const r = await postJson("/api/patch", {
      parent: SELECTED.snapshot, patchName: $("#patchName").value || "patch",
      patchText: $("#patchText").value, description: $("#patchDesc").value,
    });
    $("#dlgPatch").close(); SELECTED.snapshot = r.snapshotId;
  } catch (e) { alert("补丁失败:\n" + JSON.stringify(e, null, 2)); }
  await refresh();
});
$("#dlgDecisionCancel").addEventListener("click", () => $("#dlgDecision").close());
document.querySelectorAll("#dlgDecision button[data-v]").forEach((b) =>
  b.addEventListener("click", async () => {
    await postJson("/api/decision", {
      subject: DECISION_SUBJECT, verdict: b.dataset.v,
      rationale: $("#decisionRationale").value, reviewer: $("#decisionReviewer").value || "reviewer",
      paramsVersion: SELECTED.version,
    });
    $("#dlgDecision").close(); await refresh();
  }));
$("#btnExport").addEventListener("click", () => { window.location = "/api/export"; });
$("#importProjectFile").addEventListener("change", async (e) => {
  const text = await e.target.files[0].text();
  try {
    const msg = await api("/api/import-project", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: text,
    });
    alert(msg);
  } catch (err) { alert("导入项目失败（已整体回滚）:\n" + JSON.stringify(err, null, 2)); }
  await refresh();
});

(async function init() { await refresh(); await loadCasePack(); })();
