package heapsleuth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---------- 不可变材料层：导入时保留原始表示 ----------

enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootType { JNI_GLOBAL, THREAD_STACK, CLASS_LOADER, SYSTEM_CLASS, OTHER }

enum class WeakPolicy { IGNORE, KEEP_ALIVE }

@Serializable
data class HeapClass(
    val id: String,
    val name: String,
    val superName: String? = null,
)

@Serializable
data class HeapObject(
    val id: String,            // 稳定身份：原始对象 id（不是地址）
    val address: String,       // 原始地址，仅作材料保留，不作身份
    val classId: String,
    val sizeRaw: String,       // 原始大小表示，派生层才规范化为 bytes
    val allocationSite: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class HeapEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val label: String = "",
)

@Serializable
data class RootRef(
    val objectId: String,
    val rootType: RootType,
    val detail: String = "",
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val offset: Long,
    val length: Long,
    val reason: String,
)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val capturedAt: String, // ISO-8601 原文保留
    val classes: List<HeapClass> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<HeapEdge> = emptyList(),
    val roots: List<RootRef> = emptyList(),
    val incompleteBlocks: List<IncompleteBlock> = emptyList(),
)

// ---------- 派生层：参数、诊断、计算结果（均随事件持久化） ----------

enum class AnalysisStatus { OK, BUDGET_EXHAUSTED, SEMANTIC_UNSOLVABLE }

@Serializable
data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val budget: Int = 10_000,
    val maxPathsPerNode: Int = 32,
)

@Serializable
data class Diagnostic(
    val code: String,
    val message: String,
    val position: String,
    val objectId: String? = null,
)

@Serializable
data class AnalysisResult(
    val status: AnalysisStatus,
    val snapshotId: String,
    val paramVersion: Int,
    val inputDigest: String,
    val reachable: Set<String> = emptySet(),
    val weakKeptAlive: Set<String> = emptySet(),
    val dominatorParent: Map<String, String> = emptyMap(),
    val shallowBytes: Map<String, Long> = emptyMap(),
    val retainedBytes: Map<String, Long> = emptyMap(),
    val rootPaths: Map<String, List<List<String>>> = emptyMap(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

enum class DiffStatus { UNCHANGED, CHANGED, RENAMED, ADDED, REMOVED }

@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String? = null,
    val nameB: String? = null,
    val countA: Int = 0,
    val countB: Int = 0,
    val bytesA: Long = 0,
    val bytesB: Long = 0,
    val status: DiffStatus,
)

// ---------- 可修改决定层：机器可读取的判定与人工决定 ----------

@Serializable
data class RuleJudgment(
    val ruleId: String,
    val subject: String,
    val basis: String,
    val verdict: String, // ADOPTED / REJECTED
    val affectedObjects: List<String> = emptyList(),
)

@Serializable
data class DecisionRecord(
    val decisionId: String,
    val subject: String,
    val verdict: String, // ACCEPT / REJECT / ADJUST
    val note: String,
)
