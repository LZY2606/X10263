package heaplab.graph

import heaplab.model.*
import kotlinx.serialization.Serializable

/** Explicit policy: whether WEAK edges keep referents alive. */
enum class WeakPolicy(val wire: String) {
    /** Weakly referenced objects are still considered reachable/live. */
    KEEP_ALIVE("keep_alive"),

    /** Weak edges do not keep objects alive (matches typical GC semantics). */
    DROP("drop"),
    ;

    companion object {
        fun fromWire(w: String): WeakPolicy =
            entries.firstOrNull { it.wire == w }
                ?: throw IllegalArgumentException("unknown weak policy: $w")
    }
}

@Serializable
data class DerivationParams(
    val weakPolicy: String = WeakPolicy.DROP.wire,
    val treatInternalAsRootEdge: Boolean = true,
    val maxPathCandidates: Int = 64,
    val paramsVersion: String = "rules-v1",
) {
    fun policy(): WeakPolicy = WeakPolicy.fromWire(weakPolicy)
}

@Serializable
data class RootPath(
    val rootId: String,
    val rootCategory: String,
    val edgeIds: List<String>,
    val objectIds: List<String>,
    val length: Int,
)

@Serializable
data class NodeDerivation(
    val objectId: String,
    val reachable: Boolean,
    /** Number of edges from any root; null when unreachable. */
    val distanceFromRoot: Int? = null,
    /** Every shortest root path; ties are all retained. */
    val rootPaths: List<RootPath> = emptyList(),
    /** Dominator parent object id; null for unreachable and for direct-root nodes (virtual root). */
    val dominatorParentId: String? = null,
    val retainedSizeBytes: Long? = null,
    val retainedSizeUnit: String = "bytes",
    val shallowSizeBytes: Long? = null,
    val sizeNormalized: Boolean = true,
)

@Serializable
data class DerivedSnapshot(
    val snapshotId: String,
    val params: DerivationParams,
    val nodes: List<NodeDerivation>,
    val reachableCount: Int,
    val totalRetainedBytes: Long,
    val inputSha256: String,
    val parserVersion: String,
    val warnings: List<Diagnostic>,
)
