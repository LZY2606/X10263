package heapsnapshot.server

import heapsnapshot.domain.*
import kotlinx.serialization.Serializable

@Serializable
data class ProjectSummary(
    val projectId: String,
    val name: String,
    val branches: List<String>,
    val snapshotIds: List<String>,
    val resultIds: List<String>,
    val activePolicyVersion: String,
    val parserVersions: Map<String, String>
)

@Serializable
data class SnapshotView(
    val snapshot: RawSnapshot,
    val rawPreserved: Boolean,
    val rawSizeChars: Int
)

@Serializable
data class ResultView(
    val result: AnalysisResult,
    val svg: String,
    val policy: AnalysisPolicyView
)

@Serializable
data class AnalysisPolicyView(
    val policyVersion: String,
    val weakKeepsAlive: Boolean,
    val internalKeepsAlive: Boolean,
    val unknownKeepsAlive: Boolean,
    val leakRetainedMinBytes: Double,
    val maxRootPathsPerObject: Int,
    val note: String
)

@Serializable
data class EventView(val event: LedgerEvent, val atPoint: HistoricalView? = null)

@Serializable
data class ArchiveView(
    val branch: String,
    val counterExamples: List<EventPayload.CounterExample>
)

@Serializable
data class DiffView(val diff: SnapshotClassDiff, val svgLegend: String)

@Serializable
data class ImportResponse(val ok: Boolean, val failure: FailureOutcome? = null, val eventId: String? = null, val snapshotId: String? = null)

@Serializable
data class DecisionRequest(val resultId: String, val verdict: String, val policyVersion: String? = null, val rationale: String, val conflictsWithEventId: String? = null)

@Serializable
data class ReviseRequest(val oldVersion: String, val newVersion: String, val note: String, val weakKeepsAlive: Boolean)

@Serializable
data class ReanalyzeRequest(val snapshotId: String, val policyVersion: String, val branch: String = "baseline")
