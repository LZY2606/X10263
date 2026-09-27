package hsf.model

import kotlinx.serialization.Serializable

/**
 * 反例档案 (counterexample archive). A frozen baseline of raw material, plus
 * variants that each change exactly ONE direct source. Both adopted and
 * rejected outcomes are retained. The whole archive is itself a replayable,
 * machine-readable artifact.
 */
@Serializable
data class CounterArchive(
    val archiveId: String,
    val caseId: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val baseline: Baseline,
    val runs: List<CounterRun>
)

@Serializable
data class Baseline(
    val materialHash: String,
    val parserVersion: String,
    val objectIds: List<String>,
    val addresses: List<String?>,
    val classMeta: List<ClassMeta>,
    val edges: List<Edge>,
    val rootClassifications: List<RootRecord>,
    val incompleteChunks: List<IncompleteChunk>,
    val allocationSites: List<AllocationSite>
)

@Serializable
enum class CounterScenario {
    CYCLIC_GRAPH,
    WEAK_POLICY,
    DUPLICATE_ADDRESS,
    INCOMPLETE_OBJECT,
    MULTI_ROOT_EQUAL_PATHS,
    CLASS_RENAME_AND_IDENTITY
}

@Serializable
data class CounterRun(
    val runId: String,
    val scenario: CounterScenario,
    /** The single direct source that was changed. */
    val changedSource: String,
    val inputFragment: String,
    val paramsVersion: String,
    val judgmentKey: String,
    val verdict: String,
    val adopted: Boolean,
    val adoptedReason: String,
    val affectedObjectIdentities: List<String>,
    val expectedImpactScope: List<String>,
    val impactContained: Boolean,
    val outcomeKind: OutcomeKind,
    val diagnostics: List<Diagnostic>,
    val evidence: Map<String, String>
)
