package org.heapshadows.forensics.model

import org.heapshadows.forensics.json.JsonPos

/** Three mutually distinct failure outcomes required by the case spec. */
enum class FailureKind { FORMAT_CORRUPT, RESOURCE_EXHAUSTED, SEMANTIC_UNRESOLVABLE }

/**
 * Base of every structured failure. Positioned diagnostics carry the exact
 * location inside the imported source; semantic failures carry affected
 * stable ids; resource failures carry the exhausted budget.
 */
sealed class ForensicsException(
    message: String,
    val kind: FailureKind,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    open val diagnostics: List<Diagnostic> = emptyList()
}

class FormatFailureException(
    message: String,
    override val diagnostics: List<Diagnostic> = emptyList(),
    cause: Throwable? = null,
) : ForensicsException(message, FailureKind.FORMAT_CORRUPT, cause)

class ResourceExhaustedException(
    message: String,
    val budget: String,
    val limit: Long,
    val observed: Long,
) : ForensicsException(message, FailureKind.RESOURCE_EXHAUSTED)

class SemanticFailureException(
    message: String,
    val affectedIds: List<String> = emptyList(),
    override val diagnostics: List<Diagnostic> = emptyList(),
) : ForensicsException(message, FailureKind.SEMANTIC_UNRESOLVABLE)

data class Diagnostic(
    val code: String,
    val message: String,
    val pointer: String,
    val line: Int?,
    val column: Int?,
    val offset: Int?,
    val severity: Severity = Severity.ERROR,
) {
    enum class Severity { ERROR, WARNING, INFO }

    companion object {
        fun of(code: String, message: String, pointer: String, pos: JsonPos?, severity: Severity = Severity.ERROR) =
            Diagnostic(code, message, pointer, pos?.line, pos?.col, pos?.offset, severity)
    }
}

/* ----------------------------- Raw material ----------------------------- */

enum class EdgeStrength { STRONG, WEAK, INTERNAL }
enum class RootCategory {
    JNI_GLOBAL, JNI_LOCAL, STATIC_FIELD, THREAD_STACK, NATIVE_STACK, STICKY_CLASS,
    MONITOR, INTERNED_STRING, FINALIZING, UNREACHABLE, UNKNOWN;

    companion object {
        fun parse(raw: String): RootCategory? = entries.firstOrNull { it.name == raw }
    }
}

data class RawClass(
    val classId: String,
    val name: String,
    val superClassId: String?,
    val classLoaderId: String?,
    val renamedFrom: String?,
    val metaKey: String,
    val fields: List<RawField>,
)

data class RawField(val name: String, val type: String)

data class RawAllocationSite(
    val siteId: String,
    val classId: String,
    val methodName: String,
    val file: String?,
    val line: Int?,
)

/**
 * Imported exactly as written. [shallowSizeRaw]/[addressRaw]/[threadRaw] keep
 * the original textual representation with unit or time basis; normalization
 * happens only in the derived layer.
 */
data class RawObject(
    val objectId: String,
    val addressRaw: String,
    val classId: String,
    val shallowSizeRaw: String,
    val sizeUnit: String,
    val allocationSiteId: String?,
    val threadRaw: String?,
    val incomplete: Boolean,
    val notes: String?,
)

data class RawEdge(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val strength: EdgeStrength,
    val fieldName: String?,
    val weakPolicyTag: String?,
)

data class RawRoot(
    val rootId: String,
    val objectId: String,
    val categoryRaw: String,
    val categoryValid: Boolean,
)

data class IncompleteBlock(
    val blockId: String,
    val pointer: String,
    val reason: String,
    val rawText: String,
)

data class SnapshotMeta(
    val snapshotId: String,
    val revision: Int,
    val capturedAtIso: String,
    val timeBasis: String,
    val addressBasis: String,
    val parserName: String,
    val parserVersion: String,
    val producer: String,
)

/** Immutable source material. Nothing here is rewritten by analysis. */
data class HeapSnapshot(
    val meta: SnapshotMeta,
    val classes: List<RawClass>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: List<RawAllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val rawText: String,
    val rawSha256: String,
    val importWarnings: List<Diagnostic>,
)

/* --------------------------- Analysis parameters ------------------------ */

enum class WeakPolicy {
    /** Weak/internal edges never keep a target alive. */
    IGNORE,
    /** Weak/internal edges are treated as strong for reachability only. */
    KEEP_ALIVE,
}

data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val maxRootPathsPerObject: Int,
    val maxNodes: Int,
    val maxEdges: Int,
    val retainedUnit: String = "B",
) {
    companion object {
        const val INITIAL_VERSION = 1
        fun initial() = AnalysisParams(INITIAL_VERSION, WeakPolicy.IGNORE, 8, 200_000, 400_000, "B")
    }
}

/* ----------------------------- Derived view ----------------------------- */

data class NormalizedSize(val bytes: Long?, val raw: String, val unit: String, val convertible: Boolean) {
    val invalidReason: String? get() = if (convertible) null else "size '$raw' cannot be converted to $unit"
}

data class ObjectView(
    val objectId: String,
    val address: String,
    val classId: String,
    val className: String,
    val shallow: NormalizedSize,
    val allocationSiteId: String?,
    val allocationSiteLabel: String?,
    val reachable: Boolean,
    val incomplete: Boolean,
    val sccId: Int?,
)

data class EdgeView(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val strength: EdgeStrength,
    val activeForReachability: Boolean,
    val fieldName: String?,
)

data class RootPath(
    val roots: List<String>,
    val objectIds: List<String>,
    val edges: List<String>,
    val depth: Int,
)

data class RetainedView(
    val objectId: String,
    val idomId: String?,
    val retainedBytes: Long?,
    val retainedRaw: String,
    val computable: Boolean,
    val invalidReason: String?,
    val subtreeCount: Int,
    val rootPaths: List<RootPath>,
    val pathCountTruncated: Boolean,
)

data class LeakCandidate(
    val objectId: String,
    val retainedBytes: Long?,
    val reasonCodes: List<String>,
)

data class ClassGroupRow(
    val classId: String,
    val metaKey: String,
    val displayNameA: String?,
    val displayNameB: String?,
    val renamed: Boolean,
    val countA: Int,
    val countB: Int,
    val shallowBytesA: Long?,
    val shallowBytesB: Long?,
    val unconvertibleCount: Int,
)

data class AnalysisResult(
    val resultId: String,
    val snapshotId: String,
    val revision: Int,
    val paramsVersion: Int,
    val params: AnalysisParams,
    val inputSummary: InputSummary,
    val parentDependencyIds: List<String>,
    val objects: List<ObjectView>,
    val edges: List<EdgeView>,
    val retained: List<RetainedView>,
    val candidates: List<LeakCandidate>,
    val weakKeptAlive: List<String>,
    val duplicateAddresses: List<DuplicateAddress>,
    val diagnostics: List<Diagnostic>,
    val computedAtIso: String,
)

data class InputSummary(
    val snapshotId: String,
    val revision: Int,
    val rawSha256: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val classCount: Int,
    val incompleteBlockCount: Int,
    val unconvertibleSizeCount: Int,
    val duplicateAddressCount: Int,
)

data class DuplicateAddress(val address: String, val objectIds: List<String>)

/* ------------------------------- Events --------------------------------- */

enum class EventType {
    SNAPSHOT_IMPORTED,
    PARAMS_REVISED,
    ANALYSIS_COMPUTED,
    DECISION_RECORDED,
    BUNDLE_IMPORTED,
    RECOVERY_NOTED,
}

enum class DecisionValue { ACCEPTED, REJECTED, PARAMETER_CHANGE_REQUESTED }

data class DecisionRecord(
    val decisionId: String,
    val eventSeq: Long,
    val candidateObjectId: String,
    val value: DecisionValue,
    val rationale: String,
    val paramsVersion: Int,
    val resultId: String,
    val supersedesDecisionId: String?,
    val conflictsWithDecisionId: String?,
)

data class Event(
    val seq: Long,
    val type: EventType,
    val atIso: String,
    val snapshotId: String?,
    val revision: Int?,
    val paramsVersion: Int?,
    val payloadRefId: String?,
    val detail: String,
    val inputSummarySha256: String?,
    val parentEventSeqs: List<Long>,
)

/* ----------------------------- Trial archive ---------------------------- */

enum class TrialKind {
    CYCLE_GRAPH,
    WEAK_POLICY,
    DUPLICATE_ADDRESS,
    INCOMPLETE_OBJECT,
    EQUAL_LENGTH_ROOT_PATHS,
    CLASS_RENAME_IDENTITY,
}

data class TrialFinding(
    val objectId: String,
    val adopted: Boolean,
    val expected: String,
    val observed: String,
    val reasoning: String,
)

data class TrialRun(
    val trialId: String,
    val kind: TrialKind,
    val name: String,
    val baselineSnapshotId: String,
    val mutatedSnapshotId: String?,
    val changedSourceOnly: String,
    val inputFragment: String,
    val affectedObjectIds: List<String>,
    val expectedBlastRadius: String,
    val status: String,
    val resultIds: List<String>,
    val findings: List<TrialFinding>,
    val judgmentEventSeq: Long,
)
