package heaproom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFmt: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

val CanonicalJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

enum class EdgeKind { STRONG, WEAK, INTERNAL }
enum class RootType { THREAD, JNI_GLOBAL, JNI_LOCAL, SYSTEM_CLASS, MONITOR, NATIVE_STACK, OTHER }
enum class WeakPolicy { WEAK_IGNORED, WEAK_KEEP_ALIVE }

@Serializable
data class Quantity(val raw: String, val unit: String)

@Serializable
data class AllocSite(val file: String, val line: Int, val function: String)

@Serializable
data class ClassRec(
    val classId: String,
    val name: String,
    val superName: String? = null,
    val renamedFrom: String? = null,
    val fields: List<String> = emptyList()
)

@Serializable
data class ObjectRec(
    val objectId: String,
    val address: String,
    val classId: String,
    val size: Quantity,
    val allocationSite: AllocSite? = null
)

@Serializable
data class EdgeRec(val from: String, val to: String, val kind: EdgeKind, val name: String)

@Serializable
data class RootRec(val objectId: String, val rootType: RootType)

@Serializable
data class IncompleteBlock(val position: String, val reason: String, val rawHex: String)

@Serializable
data class SnapshotDoc(
    val format: String,
    val formatVersion: Int,
    val snapshotId: String,
    val parserVersion: String,
    val capturedAt: String,
    val classes: List<ClassRec>,
    val objects: List<ObjectRec>,
    val edges: List<EdgeRec>,
    val roots: List<RootRec>,
    val incompleteBlocks: List<IncompleteBlock> = emptyList()
)

@Serializable
data class Diagnostic(val position: String, val severity: String, val message: String)

@Serializable
data class LayoutNode(
    val objectId: String,
    val x: Int,
    val y: Int,
    val depth: Int,
    val slot: Int,
    val basis: String = "px;origin=top-left;x=depth*180;y=slot*72;timeBasis=capturedAt(UTC)"
)

@Serializable
data class AnalysisResult(
    val snapshotId: String,
    val inputDigest: String,
    val paramVersion: String,
    val weakPolicy: WeakPolicy,
    val reachable: List<String>,
    val unreachable: List<String>,
    val idom: Map<String, String>,
    val retainedBytes: Map<String, Long>,
    val unpricedObjects: List<String>,
    val rootPaths: Map<String, List<List<String>>>,
    val layout: Map<String, LayoutNode>,
    val diagnostic: List<Diagnostic> = emptyList()
)

@Serializable
data class ClassGroupDiff(
    val classIdentity: String,
    val nameA: String?,
    val nameB: String?,
    val renamedFrom: String?,
    val countA: Int,
    val countB: Int,
    val retainedA: Long?,
    val retainedB: Long?,
    val change: String
)

sealed class ComputeOutcome<out T> {
    data class Ok<T>(val value: T, val diagnostics: List<Diagnostic> = emptyList()) : ComputeOutcome<T>()
    data class BudgetExhausted(val scope: String, val limit: Int, val consumed: Int) : ComputeOutcome<Nothing>()
    data class SemanticUnsolvable(val reason: String, val candidates: List<String> = emptyList()) : ComputeOutcome<Nothing>()
}

sealed class ImportOutcome {
    data class Ok(val snapshotId: String, val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class CorruptFormat(val position: String, val message: String) : ImportOutcome()
}
