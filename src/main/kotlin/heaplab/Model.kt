package heaplab

import kotlinx.serialization.Serializable

/**
 * 不可变材料层：堆快照导入后的原始表示。
 * 原始对象 id、地址原文、类元数据、强/弱/内部边、root 分类、
 * 不完整块与解析器版本全部保留；派生层（Engine）才做规范化。
 */

enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class Severity { ERROR, WARNING }

@Serializable
data class Diagnostic(
    val line: Int,          // 1-based；0 表示与具体行无关
    val column: Int,        // 1-based；0 表示未知
    val severity: String,   // ERROR / WARNING
    val code: String,       // 机器可读诊断码
    val message: String,
)

@Serializable
data class ClassInfo(
    val id: String,
    val name: String,
    val superName: String? = null,
    val loader: String? = null,
)

@Serializable
data class HeapObject(
    val id: String,               // 稳定身份：原始对象 id（不是地址）
    val classId: String,
    val addressRaw: String,       // 原始地址表示，原样保留；允许重复
    val sizeBytes: Long?,         // null = 源中缺失/无法转换，留在原位置并附诊断
    val allocSite: String? = null,
)

@Serializable
data class HeapEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val field: String? = null,
)

@Serializable
data class RootRef(
    val id: String,
    val kind: String,             // JNI_GLOBAL / THREAD_LOCAL / SYSTEM_CLASS ...
    val target: String,
)

@Serializable
data class AllocSite(
    val id: String,
    val thread: String? = null,
    val frames: List<String> = emptyList(),
)

@Serializable
data class IncompleteChunk(
    val offset: Long,             // 单位：字节，相对快照文件起点
    val length: Long,             // 单位：字节
    val reason: String,
    val line: Int,
)

@Serializable
data class SnapshotData(
    val id: String,
    val parserVersion: String,    // 解析器版本，随快照保留
    val sourceName: String,
    val classes: List<ClassInfo>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRef>,
    val allocSites: List<AllocSite>,
    val incompletes: List<IncompleteChunk>,
    val diagnostics: List<Diagnostic>,
    val rawSource: String,        // 原始表示整体保留
)

@Serializable
data class AnalysisParams(
    val weakKeepAlive: Boolean,   // 弱引用是否保活：显式策略，无默认值伪装
    val version: String,          // 参数版本，随结果记录
)

@Serializable
data class RootPath(
    val rootId: String,
    val nodeIds: List<String>,    // 从 root 目标到对象的路径（含两端）
)

@Serializable
data class RetainedEntry(
    val objectId: String,
    val retainedBytes: Long,      // 单位：字节（仅可确定部分）
    val unknownCount: Int,        // 保留集合中尺寸未知的对象数
    val subtreeSize: Int,
)

@Serializable
data class LeakCandidate(
    val key: String,              // cand:{snapshotId}:{objectId}，稳定身份
    val objectId: String,
    val retainedBytes: Long,
    val unknownCount: Int,
    val rootPaths: List<RootPath>,
)

@Serializable
data class AnalysisResult(
    val snapshotId: String,
    val params: AnalysisParams,
    val reachable: List<String>,              // 排序后的可达对象 id
    val unreachable: List<String>,
    val idom: Map<String, String>,            // objectId -> 支配父级 id（仅可达图）
    val retained: List<RetainedEntry>,        // 按 retainedBytes 降序、id 升序
    val rootPaths: Map<String, List<RootPath>>,// 每个对象的全部等长最短 root 路径
    val candidates: List<LeakCandidate>,
)

/** 三种不同的失败结果 + 成功，绝不混用。 */
sealed interface AnalysisOutcome {
    @Serializable
    data class Ok(val result: AnalysisResult, val inputDigest: String) : AnalysisOutcome

    @Serializable
    data class BudgetExhausted(val visited: Int, val budget: Int, val stage: String) : AnalysisOutcome

    @Serializable
    data class Unsolvable(val diagnostics: List<Diagnostic>) : AnalysisOutcome
}

sealed interface ParseOutcome {
    data class Parsed(val snapshot: SnapshotData) : ParseOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>) : ParseOutcome
}

@Serializable
data class ClassRename(val objectId: String, val oldClass: String, val newClass: String)

@Serializable
data class ClassDiff(
    val snapshotA: String,
    val snapshotB: String,
    val renames: List<ClassRename>,       // 按稳定对象 id 对应出的类改名
    val addedClasses: List<String>,       // 仅 B 有的类名
    val removedClasses: List<String>,     // 仅 A 有的类名
    val matchedObjects: Int,
    val onlyInA: List<String>,
    val onlyInB: List<String>,
)

@Serializable
data class EventRecord(
    val id: String,
    val seq: Long,
    val type: String,
    val inputDigest: String,
    val paramVersion: String,
    val parents: List<String>,
    val payload: String,                  // JSON，机器可读判断依据
    val createdAt: String,                // ISO-8601，含时区基准
)

@Serializable
data class DecisionRecord(
    val id: String,
    val eventId: String,
    val candidateKey: String,
    val verdict: String,                  // accept / reject / adjust
    val rationale: String,
    val createdAt: String,
)
