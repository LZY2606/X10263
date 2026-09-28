package forensics.heapsnapshot.storage

import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.engine.ClassGroupDiff
import kotlinx.serialization.Serializable

@Serializable
data class ExportEnvelope(
    val format: String = "heap-shadow-lab-export/v1",
    val project: ProjectRow,
    val events: List<StoredEvent>,
    val snapshots: List<StoredSnapshot>,
    val analysisBlobs: List<AnalysisBlobEntry>,
    val diffBlobs: List<DiffBlobEntry>,
)

@Serializable
data class AnalysisBlobEntry(val eventId: String, val snapshotId: String, val paramsVersion: Int, val result: AnalysisResult)

@Serializable
data class DiffBlobEntry(val id: String, val beforeId: String, val afterId: String, val diff: ClassGroupDiff)
