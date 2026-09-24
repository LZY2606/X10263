package heapproom.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 不可变领域材料层。
 *
 * 约定：所有领域量都保留原始表示(raw)，规范化值(normalized)只在派生层使用；
 * 无法转换的值 normalized 为 null，并在 diagnostic 中给出带位置的诊断，
 * 绝不为画图而丢值，也绝不用空值/默认值伪装成功。
 */

@Serializable
data class Quant(
    /** 导入时的原始表示，永不重写 */
    val raw: String,
    /** 派生层规范化后的数值；无法转换时为 null */
    val normalized: Long? = null,
    /** 单位或坐标基准，例如 byte / hex-byte-address / count */
    val unit: String = "",
    /** 无法规范化时的诊断 */
    val diagnostic: String? = null,
)

@Serializable
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    /** 片段 id + JSON 指针/行列等位置 */
    val location: String,
) {
    enum class Severity { ERROR, WARNING, INFO }
}

@Serializable
data class InputFragment(
    val fragmentId: String,
    val ordinal: Int,
    val rawText: String,
    val sha256: String,
)

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val classLoaderId: String? = null,
    val nameRaw: String = name,
    val rawRecord: JsonObject? = null,
)

@Serializable
data class HeapObject(
    /** 原始对象 id，唯一稳定身份；地址永远不是身份 */
    val id: String,
    /** 跨快照逻辑身份（显式对应），缺省时回退到 id */
    val identityKey: String? = null,
    val address: Quant,
    val classId: String?,
    val shallowSize: Quant,
    val incomplete: Boolean = false,
    val allocationSiteId: String? = null,
    val rawRecord: JsonObject? = null,
)

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

@Serializable
data class Edge(
    val edgeId: String,
    val kind: EdgeKind,
    /** 边类型的原始记号（soft/weak/$internal...），原样保留 */
    val kindRaw: String,
    val fromObjectId: String,
    val toObjectId: String,
    val fieldName: String? = null,
    val rawRecord: JsonObject? = null,
)

@Serializable
enum class RootType { JNI, STICKY, JAVA_FRAME, NATIVE_STACK, GLOBAL, SYSTEM, UNKNOWN }

@Serializable
data class RootRecord(
    val rootId: String,
    val objectId: String,
    val rootType: RootType,
    val rootTypeRaw: String,
    val label: String? = null,
    val rawRecord: JsonObject? = null,
)

@Serializable
data class AllocationSite(
    val siteId: String,
    val classId: String?,
    val location: String,
    val allocationCount: Quant,
    val rawRecord: JsonObject? = null,
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val offset: Quant,
    val length: Quant,
    val reason: String,
    /** 该不完整块是否可能影响对象图（解析器元数据，原样保留） */
    val affectsHeapGraph: Boolean = true,
    val rawRecord: JsonObject? = null,
)

@Serializable
data class TimeMark(
    val raw: String,
    val epochMs: Long? = null,
    /** 时间基准，例如 UTC / frame-counter */
    val base: String? = null,
    val diagnostic: String? = null,
)

@Serializable
data class Snapshot(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val capturedAt: TimeMark,
    /** 地址坐标基准，例如 0x0 */
    val addressBase: String,
    val addressUnit: String,
    val sizeUnit: String,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<Edge>,
    val roots: List<RootRecord>,
    val allocationSites: List<AllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val fragments: List<InputFragment>,
    val diagnostics: List<Diagnostic>,
)

/** 弱引用是否保活等可达性参数，版本化、显式决定。 */
@Serializable
data class ReachPolicy(
    val version: String,
    val weakKeepsAlive: Boolean,
    val candidateThresholdBytes: Long,
)

/** 两个快照之间的显式身份对应：类改名映射 + 对象逻辑身份。地址不参与对应。 */
@Serializable
data class Correspondence(
    val version: String,
    val leftSnapshotId: String,
    val rightSnapshotId: String,
    /** classId(left) -> classId(right) */
    val classRenames: Map<String, String> = emptyMap(),
    /** 逻辑身份 key -> (left objectId, right objectId) */
    val objectPairs: Map<String, Pair<String, String>> = emptyMap(),
)
