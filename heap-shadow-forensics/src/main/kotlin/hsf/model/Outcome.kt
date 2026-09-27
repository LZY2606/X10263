package hsf.model

import kotlinx.serialization.Serializable

/**
 * The three distinct terminal outcomes the spec requires us never to conflate.
 */
@Serializable
enum class OutcomeKind { OK, RESOURCE_BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTICALLY_UNRESOLVABLE }

@Serializable
data class ImportOutcome(
    val kind: OutcomeKind,
    val snapshot: HeapSnapshot?,
    val materialHash: String?,
    val diagnostics: List<Diagnostic>
)
