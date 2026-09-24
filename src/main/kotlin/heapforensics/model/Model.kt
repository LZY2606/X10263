package heapforensics.model

import kotlinx.serialization.Serializable

/** 边类型：强引用 / 弱引用 / 内部引用（内部引用视为强可达，但单独标注以便审查）。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootType { THREAD_LOCAL, GLOBAL, JNI, STACK, NATIVE }

/** 三种不可混淆的失败结果：资源预算耗尽 / 格式损坏 / 语义无解。 */
@Serializable
enum class FailureKind { BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

@Serializable
enum class Verdict { ACCEPT, REJECT, ADJUST }

/** 带位置的诊断：pos 指向原始 JSON 中的位置（如 objects[3].address）。 */
@Serializable
data class Diagnostic(
    val pos: String,
    val code: String,
    val message: String,
    val severity: String = "error",
)

/** 带单位的量。原始表示保留在 raw，派生层才规范化为 value。 */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String,
    val value: Long? = null,
)

@Serializable
data class AllocSite(val file: String, val line: Int, val function: String)

@Serializable
data class ClassInfo(
    val id: String,
    val name: String,
    val superId: String? = null,
)

@Serializable
data class HeapObject(
    val id: String,
    val address: String,          // 原始地址文本；地址不作为稳定身份
    val classId: String,
    val selfSize: Quantity,       // 单位：bytes
    val allocSite: AllocSite? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class Edge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val name: String = "",
)

@Serializable
data class RootRef(val objectId: String, val rootType: RootType)

@Serializable
data class Chunk(val id: String, val status: String, val bytes: Long, val reason: String = "")

/** 一份堆快照材料（不可变层）。rawJson 由存储层另行保留。 */
@Serializable
data class Snapshot(
    val format: String,
    val parserVersion: String,
    val snapshotId: String,
    val classes: List<ClassInfo> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val roots: List<RootRef> = emptyList(),
    val chunks: List<Chunk> = emptyList(),
)

/** 分析参数（版本化）。weakKeepAlive 显式决定弱引用是否保活。 */
@Serializable
data class AnalysisParams(
    val version: Int,
    val weakKeepAlive: Boolean,
    val maxVisited: Int = 100_000,
    val maxPathsPerObject: Int = 8,
) {
    companion object {
        val VALID_RANGES = mapOf(
            "maxVisited" to (1..1_000_000),
            "maxPathsPerObject" to (1..64),
        )
    }
}

@Serializable
data class RootPath(val objectId: String, val hops: List<String>, val lengthHops: Int)

@Serializable
data class DomEntry(val objectId: String, val domParent: String?, val retainedBytes: Long)

@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val renamed: Boolean,
    val countA: Int,
    val countB: Int,
)

/** 分析报告：所有展示值都带稳定身份（对象 id / 类 id）。 */
@Serializable
data class Report(
    val snapshotId: String,
    val paramVersion: Int,
    val reachableIds: List<String>,
    val unreachableIds: List<String>,
    val dominators: List<DomEntry>,
    val rootPaths: List<RootPath>,          // 等长多路径全部保留
    val duplicateAddresses: Map<String, List<String>>, // address -> objectIds
    val incompleteExcluded: List<String>,
    val classDiff: List<ClassGroupDiff> = emptyList(),
    val compareSnapshotId: String? = null,
    val basis: List<String> = emptyList(),  // 机器可读的判定依据
)

@Serializable
sealed interface AnalysisOutcome {
    @Serializable
    data class Ok(val report: Report) : AnalysisOutcome
    @Serializable
    data class Failed(val kind: FailureKind, val diagnostics: List<Diagnostic>) : AnalysisOutcome
}

@Serializable
data class Decision(
    val id: String,
    val analysisId: String,
    val targetObjectId: String,
    val verdict: Verdict,
    val note: String = "",
    val supersededBy: String? = null,
    val createdSeq: Long = 0,
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val kind: String,
    val payloadJson: String,
    val materialHash: String? = null,
    val paramVersion: Int? = null,
    val analysisId: String? = null,
)
