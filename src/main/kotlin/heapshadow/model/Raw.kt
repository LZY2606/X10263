package heapshadow.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 不可变原始层：堆快照里每一类记录都保留原始 JSON 文本与来源位置，
 * 任何规范化只发生在派生层。位置使用 JSON Pointer（如 /objects/3）。
 */

@Serializable
data class RawSnapshotDto(
    val formatVersion: JsonElement? = null,
    val parserVersion: JsonElement? = null,
    val snapshotId: JsonElement? = null,
    val capturedAt: JsonElement? = null,
    val timeBasis: JsonElement? = null,
    val classes: JsonElement? = null,
    val objects: JsonElement? = null,
    val edges: JsonElement? = null,
    val roots: JsonElement? = null,
    val allocationSites: JsonElement? = null,
    val incompleteBlocks: JsonElement? = null,
)

data class RawSnapshot(
    val snapshotId: String,
    val formatVersion: String?,
    val parserVersion: String?,
    val capturedAt: String?,
    val timeBasis: String?,
    val classes: List<RawClassRecord>,
    val objects: List<RawObjectRecord>,
    val edges: List<RawEdgeRecord>,
    val roots: List<RawRootRecord>,
    val allocationSites: List<RawAllocationSiteRecord>,
    val incompleteBlocks: List<RawIncompleteBlockRecord>,
    val rawJson: String,
    val sourceFileName: String?,
)

data class RawClassRecord(
    val classId: String,
    val name: String,
    val classLoaderObjectId: String?,
    val rawJson: String,
    val location: String,
)

data class RawObjectRecord(
    val objectId: String,
    val classId: String?,
    val addressRaw: String?,
    val size: Quantity?,
    val allocationSiteId: String?,
    val complete: Boolean,
    val rawJson: String,
    val location: String,
)

data class RawEdgeRecord(
    val edgeId: String,
    val fromObjectId: String?,
    val toObjectId: String?,
    val kindRaw: String?,
    val label: String?,
    val fieldName: String?,
    val rawJson: String,
    val location: String,
)

data class RawRootRecord(
    val rootId: String,
    val objectId: String?,
    val rootTypeRaw: String?,
    val rawJson: String,
    val location: String,
)

data class RawAllocationSiteRecord(
    val siteId: String,
    val classId: String?,
    val method: String?,
    val sourceFile: String?,
    val lineRaw: String?,
    val rawJson: String,
    val location: String,
)

data class RawIncompleteBlockRecord(
    val blockId: String,
    val offset: Quantity?,
    val length: Quantity?,
    val coversIdPattern: String?,
    val reason: String?,
    val rawJson: String,
    val location: String,
)
