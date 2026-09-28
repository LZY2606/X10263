package com.heaptrace.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 不可变材料层：堆快照导入后原样保留。
 * 所有领域量携带单位/坐标基准；原始表示保留在 *Raw 字段，派生层才做规范化。
 */

@Serializable
data class Klass(val id: String, val name: String)

@Serializable
data class AllocationSite(val id: String, val className: String, val method: String, val line: Int)

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class Edge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val label: String = "",
    val ord: Int = 0
)

@Serializable
data class Obj(
    /** 稳定身份：原始对象 id（快照内唯一），地址不是身份。 */
    val id: String,
    /** 原始地址表示（如 0x7f1a0001），仅观测值，不参与身份判定。 */
    val address: String,
    val classId: String,
    /** 尺寸原始表示：十进制/十六进制/unknown 等，原样保留。 */
    val sizeRaw: String,
    val sizeUnit: String = "byte",
    val allocationSiteId: String? = null,
    val incomplete: Boolean = false,
    val notes: String? = null
)

@Serializable
data class Root(
    val id: String,
    /** THREAD / JAVA_STATIC / STICKY_CLASS / NATIVE_STACK / JNI_GLOBAL ... */
    val type: String,
    val objectId: String,
    val label: String = ""
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    /** 坐标基准：块内偏移的原始表示。 */
    val offset: String,
    val reason: String,
    val partialObjectIds: List<String> = emptyList()
)

@Serializable
data class SnapshotMeta(
    val id: String,
    val label: String,
    val parserVersion: String,
    /** 原始采集时间表示。 */
    val capturedAt: String,
    val timeBase: String = "epoch-millis",
    val addressBase: String = "heap-virtual-addr",
    /** 快照身份对应链：修订快照指回来源快照 id。 */
    val derivedFrom: String? = null,
    val changeNote: String? = null
)

@Serializable
data class SnapshotBundle(
    val format: String = "heap-shadow-snapshot",
    val formatVersion: Int = 1,
    val meta: SnapshotMeta,
    val classes: List<Klass>,
    val objects: List<Obj>,
    val edges: List<Edge>,
    val roots: List<Root>,
    val allocationSites: List<AllocationSite> = emptyList(),
    val incompleteBlocks: List<IncompleteBlock> = emptyList()
)

/** 带位置的诊断：输入不完整时绝不以空值/默认值伪装成功。 */
@Serializable
data class Diagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val location: Location,
    val rawValue: String? = null,
    val objectId: String? = null
)

@Serializable
data class Location(
    val sourceId: String,
    val coordinateBase: String = "input-ordinal",
    val offset: String = "",
    val ordinal: Int = -1
)

@Serializable
data class Budget(
    val maxObjects: Int = 5_000,
    val maxEdges: Int = 20_000,
    val maxPathsPerObject: Int = 10_000,
    val maxLeakCandidates: Int = 50
)

/** 可修改决定层：弱引用是否保活等由显式策略参数决定。 */
@Serializable
data class AnalysisPolicy(
    val version: Int = 1,
    val weakRetains: Boolean = false,
    val internalRetains: Boolean = true,
    val leakThresholdBytes: Long = 64,
    val pathLimit: Int = 64
)

@Serializable
data class UnitBasis(
    val sizeUnit: String = "byte",
    val addressBase: String = "heap-virtual-addr",
    val timeBase: String = "epoch-millis",
    val layoutCoordinate: String = "bfs-layer,unitless"
)

/** 导入的四种结果；预算耗尽/格式损坏/语义无解互相不同。 */
sealed class ImportOutcome {
    data class Ok(val bundle: SnapshotBundle, val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class FormatCorrupted(val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : ImportOutcome()
}

@Serializable
data class DominatorNode(val objectId: String, val parent: String?, val depth: Int)

@Serializable
data class ObjectAnalysis(
    val objectId: String,
    val reachable: Boolean,
    /** 规范化后的浅尺寸（字节）；null 表示原始值无法转换，原值仍留在材料层并附诊断。 */
    val shallowSizeBytes: Long?,
    val retainedSizeBytes: Long,
    /** 保留集合中存在无法转换尺寸的成员时为 false，图形必须展示不精确而非丢弃。 */
    val retainedExact: Boolean,
    val unknownInRetainedSet: List<String> = emptyList(),
    val dominatorParent: String?,
    val dominatorDepth: Int,
    /** 全部最短（等长）root 路径；无法唯一决定时保留全部合法候选。 */
    val rootPaths: List<List<String>>,
    val unreachableReason: String? = null
)

@Serializable
data class ClassGroup(
    val classId: String,
    val name: String,
    val instanceCount: Int,
    val reachableCount: Int,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val unknownSizeObjects: List<String> = emptyList()
)

@Serializable
data class ClassGroupRow(
    val classId: String,
    val name: String,
    val instanceCount: Int,
    val reachableCount: Int,
    val shallowBytes: Long,
    val retainedBytes: Long
)

@Serializable
data class ClassRename(val classId: String, val nameFrom: String, val nameTo: String)

@Serializable
data class ClassMetricDelta(
    val classId: String,
    val name: String,
    val deltaInstances: Int,
    val deltaReachable: Int,
    val deltaShallowBytes: Long,
    val deltaRetainedBytes: Long
)

@Serializable
data class ClassGroupDiff(
    val mapping: String = "by-stable-class-id",
    val snapshotA: String,
    val snapshotB: String,
    val renamed: List<ClassRename> = emptyList(),
    val added: List<ClassGroupRow> = emptyList(),
    val removed: List<ClassGroupRow> = emptyList(),
    val changed: List<ClassMetricDelta> = emptyList()
)

@Serializable
data class LeakCandidate(
    val objectId: String,
    val retainedSizeBytes: Long,
    val retainedExact: Boolean,
    val equalPathCount: Int,
    val representativePath: List<String>,
    val reason: String
)

@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val policy: AnalysisPolicy,
    val inputDigest: String,
    val dependencyEventIds: List<String> = emptyList(),
    val reachable: List<String>,
    val unreachable: List<String>,
    val objects: List<ObjectAnalysis>,
    val dominator: List<DominatorNode>,
    val classGroups: List<ClassGroup>,
    val leakCandidates: List<LeakCandidate>,
    val diagnostics: List<Diagnostic>,
    val units: UnitBasis,
    val budget: Budget,
    val status: String = "OK"
)

sealed class AnalysisOutcome {
    data class Ok(val result: AnalysisResult) : AnalysisOutcome()
    data class BudgetExhausted(val diagnostics: List<Diagnostic>, val stage: String) : AnalysisOutcome()
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : AnalysisOutcome()
}

/** 事件负载基类（JSON 多态，type 鉴别字段由序列化器写入）。 */
@Serializable
data class Event(
    val seq: Int,
    val id: String,
    val parentId: String?,
    val kind: String,
    val at: String,
    val snapshotId: String? = null,
    val payload: JsonObject,
    /** 链式摘要：canonical(payload) + 父事件 digest，保证父级依赖可校验。 */
    val digest: String
)
