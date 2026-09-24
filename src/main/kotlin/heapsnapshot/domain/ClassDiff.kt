package heapsnapshot.domain

import kotlinx.serialization.Serializable

/** 两个快照之间的类群差异（带稳定身份）。 */
@Serializable
data class ClassGroupRow(
    val baseClassRef: SnapshotRef?,
    val otherClassRef: SnapshotRef?,
    val baseClassName: String?,
    val otherClassName: String?,
    val correspondence: String, // RENAMED / SAME / ADDED / REMOVED / UNMATCHED
    val baseObjectCount: Int,
    val otherObjectCount: Int,
    val baseRetainedBytes: Double?,
    val otherRetainedBytes: Double?,
    val unit: String,
    val evidence: ClassCorrespondenceEvidence
)

@Serializable
data class ClassCorrespondenceEvidence(
    val explicitRenameHint: Boolean,
    val sharedAllocationSiteIds: List<String>,
    val sharedObjectStableIds: List<String>,
    val snapshotIdsAligned: Boolean
)

@Serializable
data class SnapshotClassDiff(
    val baseSnapshotId: String,
    val otherSnapshotId: String,
    val rows: List<ClassGroupRow>,
    val snapshotIdentityNote: String,
    val diagnostics: List<ParseDiagnostic>
)
