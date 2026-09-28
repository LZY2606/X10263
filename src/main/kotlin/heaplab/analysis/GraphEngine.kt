package heaplab.analysis

import heaplab.domain.*
import heaplab.parse.SizeCodec
import java.security.MessageDigest

/**
 * 核心业务规则（服务端，可被测试直接调用）：
 *  1. 支配树只在"可达图"上构建（按显式弱引用策略决定哪些边保活）。
 *  2. 弱引用是否保活由显式策略决定（KEEP_ALIVE / CLEAR / MARK_ONLY）。
 *  3. 多个等长 root 路径全部保留；无法唯一决定时保留全部合法候选。
 *  4. 地址不当稳定身份；稳定身份 = urn:snapshotId/objectId。
 */
object GraphEngine {

    const val VIRTUAL_ROOT = "urn:virtual-root"

    data class EngineInput(
        val snapshot: RawSnapshot,
        val params: AnalysisParams,
        val identity: IdentityCorrespondence,
        val parentResultId: String?
    )

    fun analyze(input: EngineInput): AnalysisResult {
        val snap = input.snapshot
        val p = input.params
        val diags = mutableListOf<Diagnostic>()

        val classById = snap.classes.associateBy { it.classId }
        fun className(classId: String) = classById[classId]?.name ?: classId

        // 规范化浅大小（派生层）；无法转换留在原位，返回 null，不伪装。
        data class Obj(val raw: RawObject, val bytes: Long?)
        val objs = snap.objects.map { Obj(it, SizeCodec.toBytesOrNull(it.shallowSize, it.sizeUnit)) }
        val objById = objs.associateBy { it.raw.objectId }

        val rootKindsByObj = snap.roots.groupBy { it.objectId }.mapValues { e -> e.value.map { it.kind } }
        val rootObjs = snap.roots.map { it.objectId }.filter { objById.containsKey(it) }.toSet()

        // 依据策略决定边是否保活（进入可达图）。
        fun edgeKeepsAlive(kind: EdgeKind): Boolean = when (kind) {
            EdgeKind.STRONG -> true
            EdgeKind.INTERNAL -> p.treatInternalAsStrong
            EdgeKind.WEAK -> when (p.weakPolicy) {
                WeakPolicy.KEEP_ALIVE -> true
                WeakPolicy.CLEAR -> false
                WeakPolicy.MARK_ONLY -> false
            }
        }

        val outgoing = HashMap<String, MutableList<RawEdge>>()
        snap.edges.filter { it.from in objById && it.to in objById }.forEach { e ->
            outgoing.getOrPut(e.from) { mutableListOf() } += e
        }

        // ---- 可达性（策略保活边），同时记录"是否仅经弱边到达" ----
        val reachable = HashSet<String>()
        val weakOnly = HashSet<String>()
        run {
            val strongQueue = ArrayDeque<String>()
            val strongSeen = HashSet<String>()
            rootObjs.forEach { strongSeen += it; strongQueue.add(it) }
            while (strongQueue.isNotEmpty()) {
                val cur = strongQueue.removeFirst()
                for (e in outgoing[cur].orEmpty()) {
                    val alive = edgeKeepsAlive(e.kind)
                    if (alive && strongSeen.add(e.to)) strongQueue.add(e.to)
                }
            }
            // MARK_ONLY：弱边不续命，但标记仅弱可达对象。
            val anySeen = HashSet<String>().also { it.addAll(rootObjs) }
            val q = ArrayDeque<String>().also { qq -> rootObjs.forEach { qq.add(it) } }
            while (q.isNotEmpty()) {
                val cur = q.removeFirst()
                for (e in outgoing[cur].orEmpty()) {
                    if (anySeen.add(e.to)) q.add(e.to)
                }
            }
            reachable += strongSeen
            weakOnly += anySeen.subtract(strongSeen)
            when (p.weakPolicy) {
                WeakPolicy.KEEP_ALIVE -> { reachable += anySeen; weakOnly.clear() }
                WeakPolicy.CLEAR -> Unit
                WeakPolicy.MARK_ONLY -> Unit
            }
        }

        // ---- 在可达图上构建支配关系（含虚拟根） ----
        val aliveEdge: (RawEdge) -> Boolean = { e ->
            e.from in reachable && e.to in reachable &&
                (edgeKeepsAlive(e.kind) || (p.weakPolicy == WeakPolicy.MARK_ONLY && e.kind == EdgeKind.WEAK && e.to in weakOnly))
        }
        // MARK_ONLY 下支配树仍只在"强保活可达图"上：弱边仅用于标记，不入支配树。
        val domEdge: (RawEdge) -> Boolean = { e ->
            e.from in reachable && e.to in reachable && edgeKeepsAlive(e.kind)
        }

        val pred = HashMap<String, MutableSet<String>>()
        reachable.forEach { pred[it] = mutableSetOf() }
        snap.edges.forEach { e ->
            if (domEdge(e)) pred[e.to]!!.add(e.from)
        }
        rootObjs.forEach { pred[it]!!.add(VIRTUAL_ROOT) }

        val dominator = computeDominators(VIRTUAL_ROOT, reachable + VIRTUAL_ROOT, pred)
