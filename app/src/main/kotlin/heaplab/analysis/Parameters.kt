package heaplab.analysis

import kotlinx.serialization.Serializable

/** 弱引用是否保活由显式策略决定，绝不依赖隐式行为。 */
@Serializable
enum class WeakPolicy {
    /** 弱边与强边一样参与可达性与支配。 */
    KEEP_WEAK,
    /** 弱边不参与可达性/支配（常见运行时语义）。 */
    DROP_WEAK,
    /** 弱边参与可达性但永不支配（遍历可达，保留归因走强/内部边）。 */
    TRAVERSE_NO_DOMINATE
}

@Serializable
data class Parameters(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val internalEdgesCount: Boolean,
    val sizeMetric: SizeMetric,
    val rootCategoriesIncluded: Set<String>,   // 空集合表示全部已解析 root
    val note: String
) {
    companion object {
        fun v1() = Parameters(
            version = 1,
            weakPolicy = WeakPolicy.DROP_WEAK,
            internalEdgesCount = true,
            sizeMetric = SizeMetric.SHALLOW_BYTES,
            rootCategoriesIncluded = emptySet(),
            note = "初始参数：弱引用不保活，内部边参与，保留大小按浅字节。"
        )
    }
}

@Serializable
enum class SizeMetric { SHALLOW_BYTES, SHALLOW_OBJECT_COUNT }

/** 输入摘要：分析结果只通过它与材料绑定，避免悄悄拿最新材料重算历史。 */
@Serializable
data class InputDigest(
    val snapshotId: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val errorDiagnosticCount: Int,
    val contentHash: String
)
