package forensics.heapsnapshot.engine

import kotlinx.serialization.Serializable

/**
 * 显式策略参数。弱引用是否保活绝不能隐含决定，必须由参数版本固定。
 * internal 边默认保活，但同样可以通过策略关掉。
 */
@Serializable
data class AnalysisParams(
    val version: Int = 1,
    val weakEdgesKeepAlive: Boolean = false,
    val internalEdgesKeepAlive: Boolean = true,
    val retainIncompleteObjects: Boolean = true,
    val maxPathCandidatesPerObject: Int = 16,
    val retainedSizeUnit: String = "bytes",
    val graphCoordinateBase: String = "svg-pixel@layout-v1",
    val note: String = "",
)
