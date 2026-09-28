package hs.domain

import kotlinx.serialization.Serializable

/**
 * 不可变材料层。所有字段都保留导入时的原始表示；派生（规范化）只发生在分析层。
 */

@Serializable
data class SourcePos(val line: Int, val col: Int, val offset: Int, val source: String = "payload") {
    override fun toString(): String = "$source:$line:$col"
}

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

@Serializable
enum class RootKind {
    JNI_GLOBAL, JNI_LOCAL, STATIC_FIELD, STICKY_CLASS, THREAD_OBJ, THREAD_STACK,
    INTERNED_STRING, FINALIZING, DEBUGGER, UNKNOWN;

    companion object {
        fun parse(raw: String): RootKind? =
            entries.firstOrNull { it.name.equals(raw.replace('-', '_'), ignoreCase = true) }
    }
}

/** 类元数据：classId 为稳定身份；name 是可改名的展示名。 */
@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val classLoaderObjectId: String? = null,
    val declaredAt: SourcePos? = null
)

@Serializable
data class AllocationSite(
    val siteId: String,
    val objectId: String,
    val methodName: String,
    val classId: String?,
    val lineNumber: Int? = null,
    val sourceName: String? = null,
    val declaredAt: SourcePos? = null
)

/** 堆对象。originalId/address 保留原始字符串表示；address 不充当稳定身份。 */
@Serializable
data class HeapObject(
    val objectId: String,
    val originalId: String,
    val address: String?,
    val classId: String,
    val shallowSize: String,
    val unit: String,
    val complete: Boolean,
    val declaredAt: SourcePos
)

@Serializable
data class Edge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val label: String?,
    val fieldIndex: String? = null,
    val declaredAt: SourcePos
)

@Serializable
data class RootRecord(
    val objectId: String,
    val kind: RootKind,
    val rawKind: String,
    val threadSerial: String? = null,
    val declaredAt: SourcePos
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val reason: String,
    val rawLine: String,
    val declaredAt: SourcePos
)

@Serializable
enum class Severity { ERROR, WARNING, INFO }

@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    val pos: SourcePos?,
    val rawValue: String? = null,
    val objectId: String? = null
)

@Serializable
data class SnapshotMeta(
    val snapshotId: String,
    val parserVersion: String,
    val formatVersion: String?,
    val recordedAtMillis: Long?,
    val unitHint: String?,
    val importedAtMillis: Long,
    val label: String
)

@Serializable
data class RawSnapshot(
    val meta: SnapshotMeta,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<Edge>,
    val roots: List<RootRecord>,
    val sites: List<AllocationSite>,
    val incomplete: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
    val rawText: String
)

/** 解析/导入的三种互斥结果，外加 OK。 */
@Serializable
sealed class IngestResult {
    @Serializable
    data class Ok(val snapshot: RawSnapshot) : IngestResult()
    @Serializable
    data class Corrupt(val diagnostics: List<Diagnostic>, val parserVersion: String) : IngestResult()
    @Serializable
    data class BudgetExhausted(val diagnostics: List<Diagnostic>, val budgetName: String, val limit: Long, val used: Long) : IngestResult()
}

/** 弱引用是否保活由显式策略决定。策略带版本，所有计算结果都记录参数版本。 */
@Serializable
data class WeakPolicy(
    val version: String,
    val weakKeepsAlive: Boolean,
    val internalKeepsAlive: Boolean = true,
    val notes: String = ""
) {
    companion object {
        val V1 = WeakPolicy("weak-policy/v1", weakKeepsAlive = true, notes = "初始策略：弱引用计为保活边")
        val V2 = WeakPolicy("weak-policy/v2", weakKeepsAlive = false, notes = "修订策略：弱引用不保活，仅强/内部边保活")
    }
}

/** 资源预算：超限必须产出 BUDGET_EXHAUSTED，而不是截断后伪装成功。 */
@Serializable
data class IngestBudget(
    val maxObjects: Long = 100_000,
    val maxEdges: Long = 1_000_000,
    val maxChars: Long = 50_000_000,
    val maxDiagnostics: Long = 500,
    val maxEqualPaths: Int = 10_000
)

@Serializable
enum class AnalysisStatus { OK, CORRUPT, BUDGET_EXHAUSTED, SEMANTIC_UNRESOLVABLE }

@Serializable
data class DominatorInfo(val objectId: String, val idomObjectId: String?, val depth: Int)

@Serializable
data class RootPath(
    val rootObjectId: String,
    val rootKind: RootKind,
    val path: List<String>,
    val edgeKinds: List<EdgeKind>,
    val length: Int,
    val strength: Int
)

@Serializable
data class RetainedResult(
    val objectId: String,
    val retainedSize: Long,
    val unit: String,
    val reachable: Boolean,
    val dominatorParentId: String?,
    val shortestRootLength: Int?,
    val rootPathCount: Int,
    val equalLengthRootPaths: List<RootPath>,
    val shallowSize: Long,
    val unconvertible: Boolean,
    val retainedBasis: String
)

@Serializable
data class LeakCandidate(
    val objectId: String,
    val retainedSize: Long,
    val unit: String,
    val rootCount: Int,
    val shortestRootLength: Int,
    val onlyWeaklyRooted: Boolean,
    val status: String,
    val reasons: List<String>,
    val equalLengthRootPaths: List<RootPath>
)

@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val policyVersion: String,
    val status: AnalysisStatus,
    val reachableObjectIds: List<String>,
    val dominators: List<DominatorInfo>,
    val retained: List<RetainedResult>,
    val leakCandidates: List<LeakCandidate>,
    val inputDigest: String,
    val inputSummary: InputSummary,
    val diagnostics: List<Diagnostic>,
    val ambiguities: List<Ambiguity> = emptyList(),
    val computedAtMillis: Long,
    val sizeUnit: String,
    val valueBasis: String
)

@Serializable
data class InputSummary(
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val classCount: Int,
    val incompleteCount: Int,
    val weakEdgeCount: Int,
    val strongEdgeCount: Int,
    val internalEdgeCount: Int,
    val duplicateAddressGroups: Int,
    val unconvertibleSizes: Int
)

/** 类群（按类分组的对象集合），用于两个快照间的差异。身份用 classId，不用改名后的名字。 */
@Serializable
data class ClassGroup(
    val classId: String,
    val className: String,
    val objectCount: Int,
    val shallowTotal: Long,
    val retainedTotal: Long,
    val unit: String
)

@Serializable
data class SnapshotDiff(
    val snapshotAId: String,
    val snapshotBId: String,
    val analysisAId: String,
    val analysisBId: String,
    val renamed: List<RenamedClass>,
    val addedClasses: List<ClassGroup>,
    val removedClasses: List<ClassGroup>,
    val changed: List<ClassGroupChange>,
    val identityBasis: String
)

@Serializable
data class RenamedClass(val classId: String, val nameA: String, val nameB: String)

@Serializable
data class ClassGroupChange(
    val classId: String,
    val nameA: String,
    val nameB: String,
    val objectCountA: Int,
    val objectCountB: Int,
    val retainedA: Long,
    val retainedB: Long,
    val unit: String
)

/** 无法唯一决定时保留的全部合法候选。 */
@Serializable
data class Ambiguity(
    val code: String,
    val objectId: String?,
    val candidates: List<String>,
    val message: String
)
