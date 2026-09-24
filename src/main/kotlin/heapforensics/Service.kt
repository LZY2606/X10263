package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SnapshotRef(val digest: String, val snapshotId: String, val eventSeq: Long, val source: String)

@Serializable
data class DecisionView(val target: String, val verdict: String, val rationale: String, val seq: Long, val superseded: Boolean)

@Serializable
data class ViewState(
    val asOfSeq: Long,
    val snapshot: SnapshotRef?,
    val params: Params,
    val analysis: AnalysisResult?,
    // null => 当时没有可用计算
    val computationId: String?,
    val decisions: List<DecisionView>,
    val validUntilSeq: Long?,           // 失效边界：下一个使其失效的事件
    val invalidationReason: String?,
)

@Serializable
data class Patch(
    val patchId: String,
    val note: String = "",
    val addObjects: List<HeapObject> = emptyList(),
    val removeObjects: List<String> = emptyList(),
    val addEdges: List<HeapEdge> = emptyList(),
    val removeEdges: List<HeapEdge> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * 业务服务层：服务端与测试共用同一入口，业务规则不藏在浏览器里。
 */
class ForensicsService(val store: Store) {

    // ---------- 状态读取 ----------
    fun events(): List<Event> = store.events()

    fun currentParams(asOf: Long = Long.MAX_VALUE): Params {
        val evs = store.eventsUpTo(asOf).filter { it.kind == "PARAMS" }
        if (evs.isEmpty()) return Params(1, WeakPolicy.DROP)
        val last = evs.last()
        val p = json.parseToJsonElement(last.payload).jsonObject
        return Params(
            version = p["version"]!!.jsonPrimitive.content.toInt(),
            weakPolicy = WeakPolicy.valueOf(p["weakPolicy"]!!.jsonPrimitive.content),
            maxRootPaths = p["maxRootPaths"]!!.jsonPrimitive.content.toInt(),
        )
    }

    fun snapshots(asOf: Long = Long.MAX_VALUE): List<SnapshotRef> =
        store.eventsUpTo(asOf).filter { it.kind == "IMPORT" || it.kind == "PATCH" }.map { e ->
            val p = json.parseToJsonElement(e.payload).jsonObject
            SnapshotRef(
                digest = p["digest"]!!.jsonPrimitive.content,
                snapshotId = p["snapshotId"]!!.jsonPrimitive.content,
                eventSeq = e.seq,
                source = p["source"]!!.jsonPrimitive.content,
            )
        }

    fun currentSnapshot(asOf: Long = Long.MAX_VALUE): SnapshotRef? = snapshots(asOf).lastOrNull()

    fun loadSnapshot(digest: String): HeapSnapshot? {
        val m = store.material(digest) ?: return null
        return (HeapParser.parse(m.content) as? ImportResult.Ok)?.snapshot
    }

    fun computationId(digest: String, p: Params) = "analysis:$digest:v${p.version}:${p.weakPolicy}:${p.maxRootPaths}"

    /** 取（或算并缓存）一次分析；结果记录输入摘要、参数版本、父级依赖。 */
    fun analysis(digest: String, p: Params, eventSeq: Long, parents: List<Long> = emptyList()): AnalysisResult {
        val id = computationId(digest, p)
        store.computation(id)?.let {
            return json.decodeFromString(AnalysisResult.serializer(), it.result)
        }
        val snap = loadSnapshot(digest) ?: return AnalysisResult(
            ComputeStatus.UNSATISFIABLE,
            listOf(Diagnostic("$", "语义无解：材料 $digest 不存在或不可解析")), emptyList(), 0L, emptyList(),
        )
        val result = Analyzer.analyze(snap, p)
        store.putComputation(
            Computation(
                id = id, kind = "analysis", inputDigest = digest, paramVersion = p.version,
                parents = parents.joinToString(",", "[", "]"), status = result.status.name,
                result = json.encodeToString(AnalysisResult.serializer(), result), eventSeq = eventSeq,
            )
        )
        return result
    }

    // ---------- 决策（可修改层）----------
    fun decisions(asOf: Long = Long.MAX_VALUE): List<DecisionView> {
        val evs = store.eventsUpTo(asOf).filter { it.kind == "DECISION" }
        val byTarget = evs.groupBy { json.parseToJsonElement(it.payload).jsonObject["target"]!!.jsonPrimitive.content }
        return evs.map { e ->
            val p = json.parseToJsonElement(e.payload).jsonObject
            val target = p["target"]!!.jsonPrimitive.content
            val latest = byTarget[target]!!.maxOf { it.seq }
            DecisionView(
                target = target,
                verdict = p["verdict"]!!.jsonPrimitive.content,
                rationale = p["rationale"]!!.jsonPrimitive.content,
                seq = e.seq,
                superseded = e.seq != latest,
            )
        }.sortedBy { it.seq }
    }

    // ---------- 写操作（每个都产生机器可读事件）----------
    fun importSnapshot(raw: String, source: String): Pair<Long?, ImportResult> {
        return when (val r = HeapParser.parse(raw)) {
            is ImportResult.Ok -> {
                val seq = store.appendEvent("IMPORT", json.encodeToString(JsonObject.serializer(), json.parseToJsonElement(
                    """{"digest":"${r.digest}","snapshotId":"${r.snapshot.snapshotId}","source":"$source","parserVersion":"${r.snapshot.parserVersion}","objects":${r.snapshot.objects.size},"edges":${r.snapshot.edges.size},"roots":${r.snapshot.roots.size},"diagnostics":${r.diagnostics.size}}"""
                ).jsonObject))
                store.putMaterial(r.digest, "snapshot", raw, seq)
                // 立即按当前参数计算，留下判断依据事件
                val p = currentParams()
                val result = analysis(r.digest, p, seq, parents = listOf(seq))
                store.appendEvent("ANALYSIS", json.encodeToString(JsonObject.serializer(), json.parseToJsonElement(
                    buildString {
                        append("""{"digest":"${r.digest}","paramVersion":${p.version},"status":"${result.status}","judgments":""")
                        append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Judgment.serializer()), result.judgments))
                        append("}")
                    }
                ).jsonObject), parents = listOf(seq))
                seq to r
            }
            else -> {
                // 失败也留事件：三种结果可区分
                val kind = if (r is ImportResult.Corrupt) "IMPORT_CORRUPT" else "IMPORT_UNSATISFIABLE"
                val diags = when (r) {
                    is ImportResult.Corrupt -> r.diagnostics
                    is ImportResult.Unsatisfiable -> r.diagnostics
                    else -> emptyList()
                }
                val seq = store.appendEvent(kind, json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), diags))
                seq to r
            }
        }
    }

    fun setParams(weakPolicy: WeakPolicy, maxRootPaths: Int, rationale: String): Long {
        val prev = currentParams()
        val v = prev.version + 1
        val seq = store.appendEvent("PARAMS", """{"version":$v,"weakPolicy":"$weakPolicy","maxRootPaths":$maxRootPaths,"rationale":${Json.encodeToString(kotlinx.serialization.builtins.serializer<String>(), rationale)},"previousVersion":${prev.version}}""")
        // 用新参数重算当前快照，旧版本结果保留不覆盖
        currentSnapshot()?.let { analysis(it.digest, currentParams(), seq, parents = listOf(seq)) }
        return seq
    }

    fun applyPatch(patchRaw: String): Pair<Long, String?> {
        val patch = try {
            json.decodeFromString(Patch.serializer(), patchRaw)
        } catch (e: Exception) {
            val seq = store.appendEvent("PATCH_REJECTED", """{"error":${Json.encodeToString(kotlinx.serialization.builtins.serializer<String>(), e.message ?: "patch 格式错误")}}""")
            return seq to null
        }
        val base = currentSnapshot() ?: return store.appendEvent("PATCH_REJECTED", """{"error":"无当前快照"}""") to null
        val baseSnap = loadSnapshot(base.digest)!!
        val removed = patch.removeObjects.toSet()
        val newObjects = baseSnap.objects.filter { it.id !in removed } + patch.addObjects
        val remEdges = patch.removeEdges.map { Triple(it.from, it.to, it.kind) }.toSet()
        val newEdges = baseSnap.edges.filter { Triple(it.from, it.to, it.kind) !in remEdges } + patch.addEdges
        val patched = baseSnap.copy(
            snapshotId = "${baseSnap.snapshotId}+${patch.patchId}",
            objects = newObjects, edges = newEdges,
        )
        val raw = json.encodeToString(HeapSnapshot.serializer(), patched)
        val digest = canonicalDigest(patched)
        val p = currentParams()
        val before = analysis(base.digest, p, base.eventSeq)
        val seq = store.appendEvent("PATCH", """{"patchId":"${patch.patchId}","note":${Json.encodeToString(kotlinx.serialization.builtins.serializer<String>(), patch.note)},"baseDigest":"${base.digest}","digest":"$digest","snapshotId":"${patched.snapshotId}","source":"patch:${patch.patchId}"}""", parents = listOf(base.eventSeq))
        store.putMaterial(digest, "snapshot", raw, seq)
        val after = analysis(digest, p, seq, parents = listOf(seq, base.eventSeq))
        // 受影响对象：支配父或保留大小发生变化者
        val b = before.objects.associateBy { it.objectId }
        val affected = after.objects.filter { a ->
            val o = b[a.objectId]
            o == null || o.dominatorParent != a.dominatorParent || o.retainedBytes != a.retainedBytes || o.reachable != a.reachable
        }.map { it.objectId }
        store.appendEvent("PATCH_EFFECT", """{"patchId":"${patch.patchId}","digest":"$digest","affectedObjects":${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), affected)}}""", parents = listOf(seq))
        return seq to digest
    }

    fun decide(target: String, verdict: String, rationale: String): Long {
        val snap = currentSnapshot()
        val p = currentParams()
        val basis = if (snap != null) {
            val a = analysis(snap.digest, p, snap.eventSeq)
            val oa = a.objects.firstOrNull { it.objectId == target }
            """"basis":{"dominator":${oa?.dominatorParent?.let { "\"$it\"" } ?: "null"},"retainedBytes":${oa?.retainedBytes ?: 0},"rootPaths":${oa?.rootPaths?.size ?: 0},"weakPolicy":"${p.weakPolicy}","paramVersion":${p.version}}"""
        } else """"basis":null"""
        return store.appendEvent("DECISION", """{"target":"$target","verdict":"$verdict","rationale":${Json.encodeToString(kotlinx.serialization.builtins.serializer<String>(), rationale)},$basis}""")
    }

    // ---------- 历史视图 ----------
    fun stateAsOf(seq: Long): ViewState {
        val snap = currentSnapshot(seq)
        val p = currentParams(seq)
        val later = store.events().filter { it.seq > seq }
        val invalidator = later.firstOrNull { it.kind == "IMPORT" || it.kind == "PATCH" || it.kind == "PARAMS" }
        var analysis: AnalysisResult? = null
        var compId: String? = null
        if (snap != null) {
            compId = computationId(snap.digest, p)
            analysis = store.computation(compId)?.let { json.decodeFromString(AnalysisResult.serializer(), it.result) }
        }
        return ViewState(
            asOfSeq = seq, snapshot = snap, params = p, analysis = analysis, computationId = compId,
            decisions = decisions(seq),
            validUntilSeq = invalidator?.seq?.minus(1),
            invalidationReason = invalidator?.let { "事件 #${it.seq} ${it.kind} 使该视图失效" },
        )
    }

    fun exportJson(): String = store.exportJson()

    fun importJson(text: String) = store.importJson(text)
}
