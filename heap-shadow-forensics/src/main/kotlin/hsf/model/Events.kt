package hsf.model

import kotlinx.serialization.Serializable

/**
 * Event-sourced decisions. Material is immutable; every mutation is an append.
 * Replaying events restores any historical view with the dependency versions
 * and visible conclusions that existed then - newer analysis never overwrites
 * history.
 */

@Serializable
sealed class CaseEvent {
    abstract val eventId: String
    abstract val seq: Long
    abstract val atEpochMillis: Long
    abstract val branch: String
    abstract val summary: String

    @Serializable
    data class CaseCreated(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val caseId: String,
        val title: String
    ) : CaseEvent()

    @Serializable
    data class SnapshotImported(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val snapshotId: String,
        val materialHash: String,
        val sourceName: String,
        val journalId: String
    ) : CaseEvent()

    /** Only a direct source material changes; impact is tracked by dependency. */
    @Serializable
    data class SourcePatched(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val snapshotId: String,
        val newMaterialHash: String,
        val replacesEventId: String,
        val changedObjectIds: List<String>,
        val journalId: String
    ) : CaseEvent()

    /** Parameter revision (weak-reference policy, budgets, units, thresholds). */
    @Serializable
    data class ParamsRevised(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val params: AnalysisParams,
        val replacesParamsVersion: String?
    ) : CaseEvent()

    @Serializable
    data class AnalysisComputed(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val resultId: String,
        val inputs: List<AnalysisInputDigest>,
        val paramsVersion: String,
        val parentResultId: String?
    ) : CaseEvent()

    @Serializable
    data class DecisionRecorded(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val targetIdentity: String,
        val verdict: DecisionVerdict,
        val rationale: String,
        val basedOnResultId: String,
        /** Conflicts with a prior decision on the same target. */
        val conflictsWithEventId: String? = null
    ) : CaseEvent()

    @Serializable
    data class BranchCreated(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val newBranch: String,
        val fromEventId: String
    ) : CaseEvent()

    /** Machine-readable judgment for the tricky invariants (see JudgmentKey). */
    @Serializable
    data class JudgmentEmitted(
        override val eventId: String,
        override val seq: Long,
        override val atEpochMillis: Long,
        override val branch: String,
        override val summary: String,
        val key: String,
        val verdict: String,
        val evidence: Map<String, String>,
        val adopted: Boolean
    ) : CaseEvent()
}

@Serializable
enum class DecisionVerdict { ACCEPTED, REJECTED, PARAM_ADJUSTED }

object JudgmentKey {
    const val DOMINATOR_ON_REACHABLE = "dominator_tree_built_only_on_reachable_graph"
    const val WEAK_POLICY_EXPLICIT = "weak_reference_keeps_alive_only_under_explicit_policy"
    const val EQUAL_LENGTH_PATHS_KEPT = "multiple_equal_length_root_paths_all_retained"
    const val ADDRESS_NOT_IDENTITY = "address_not_used_as_stable_identity"
}

@Serializable
data class DecisionView(
    val eventId: String,
    val seq: Long,
    val atEpochMillis: Long,
    val branch: String,
    val targetIdentity: String,
    val verdict: DecisionVerdict,
    val rationale: String,
    val basedOnResultId: String,
    val conflictsWithEventId: String?
)
