package hsf.model

import kotlinx.serialization.Serializable

/**
 * Layer 1 - derived analysis. Everything here is reproducible from immutable
 * material + a parameter version + explicit parent dependencies.
 */

@Serializable
enum class WeakPolicy {
    /** Weak edges are ignored for reachability (reference may be collected). */
    IGNORE,
    /** Weak edges keep their target alive until cleared (explicit opt-in). */
    KEEP_ALIVE
}

@Serializable
data class AnalysisParams(
    val paramsVersion: String,
    val weakPolicy: WeakPolicy = WeakPolicy.IGNORE,
    val unit: String = "byte",
    val maxNodes: Int = 200_000,
    val maxEdges: Int = 1_000_000,
    val leakMinRetained: Long? = null,
    val leakKindAllowList: List<RootKind> = emptyList(),
    val note: String? = null
)

@Serializable
data class ValueWithUnit(val raw: String, val value: Long?, val unit: String, val convertible: Boolean)

@Serializable
data class ObjectView(
    /** Global stable identity: "<snapshotId>#<objectId>". */
    val identity: String,
    val snapshotId: String,
    val objectId: String,
    val address: String?,
    val classId: String,
    val className: String,
    val size: ValueWithUnit,
    val reachable: Boolean,
    /** Dominator parent identity; null for virtual super-root's children. */
    val domParent: String?,
    val retained: ValueWithUnit,
    val shallowSizeUnconvertible: Boolean,
    val allocationSite: String?,
    val rootKinds: List<RootKind>,
    val complete: Boolean
)

@Serializable
data class EdgeView(
    val identity: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val field: String?,
    val participatesInReachability: Boolean
)

@Serializable
data class RootPath(
    val rootObject: String,
    val rootKind: RootKind,
    val edges: List<String>,
    val length: Int
)

@Serializable
data class LeakCandidate(
    val target: String,
    val retained: ValueWithUnit,
    val paths: List<RootPath>,
    val reason: String
)

@Serializable
data class ClassGroup(
    val classId: String,
    val className: String,
    val renameKey: String?,
    val objectCount: Int,
    val shallow: ValueWithUnit,
    val retained: ValueWithUnit
)

@Serializable
data class ClassGroupDiff(
    /** Stable across renames when a rename key is available. */
    val groupKey: String,
    val classNameBefore: String?,
    val classNameAfter: String?,
    val renamed: Boolean,
    val countDelta: Int?,
    val shallowDelta: Long?,
    val retainedDelta: Long?,
    val onlyInBefore: Boolean,
    val onlyInAfter: Boolean,
    val matchBasis: String
)

/**
 * One legal root->object path family. Multiple equal-length shortest paths are
 * ALL retained; analysis never picks an arbitrary winner.
 */
@Serializable
data class ObjectPaths(
    val identity: String,
    val paths: List<RootPath>,
    val ambiguous: Boolean,
    val reachable: Boolean
)

@Serializable
data class AnalysisInputDigest(
    val snapshotId: String,
    val materialHash: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int
)

@Serializable
data class AnalysisResult(
    val resultId: String,
    val params: AnalysisParams,
    val inputs: List<AnalysisInputDigest>,
    val parentResultId: String?,
    val createdAtEpochMillis: Long,
    val reachableCount: Int,
    val objects: List<ObjectView>,
    val edges: List<EdgeView>,
    val groups: List<ClassGroup>,
    val leaks: List<LeakCandidate>,
    val timeBasis: String?,
    val validity: Validity,
    val diagnostics: List<Diagnostic>
)

@Serializable
data class Validity(
    val validFromEventId: String,
    /** Event after which this result is stale; null while current. */
    val invalidatedByEventId: String? = null,
    val reason: String? = null
)
