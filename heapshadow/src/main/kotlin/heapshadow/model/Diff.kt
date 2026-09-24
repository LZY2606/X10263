package heapshadow.model

import kotlinx.serialization.Serializable

@Serializable
data class ClassGroupKey(
    val matchMode: String,
    /** classId（来自材料，不可变）。 */
    val classId: String,
    /** 重命名时 classId 相同但名称不同。 */
    val nameInBase: String,
    val nameInOther: String? = null,
    val renamed: Boolean = false
)

@Serializable
data class ClassGroupDiffRow(
    val key: ClassGroupKey,
    val objectCountBase: Int,
    val objectCountOther: Int,
    val shallowBytesBase: Long?,
    val shallowBytesOther: Long?,
    val unit: String = "bytes",
    val status: String,
    /** identity 对应检查：objectId / 地址复用情况。 */
    val identityNotes: List<String>
)

@Serializable
data class ObjectIdentityRow(
    val objectId: String,
    val addressBase: String?,
    val addressOther: String?,
    val classIdBase: String?,
    val classIdOther: String?,
    val presentInBase: Boolean,
    val presentInOther: Boolean,
    val note: String
)

@Serializable
data class SnapshotDiff(
    val baseSnapshotId: String,
    val otherSnapshotId: String,
    val rows: List<ClassGroupDiffRow>,
    val identityRows: List<ObjectIdentityRow>,
    val diagnostics: List<Diagnostic>,
    val summary: String
)
