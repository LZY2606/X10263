package heapsnapshot.analysis

import kotlinx.serialization.Serializable

/**
 * 显式计算参数。弱引用是否保活不写死、不藏浏览器，由本策略版本决定。
 * 每次参数修订产生新版本；派生结果记录所用版本。
 */
@Serializable
data class AnalysisPolicy(
    val policyVersion: String,
    val weakKeepsAlive: Boolean,
    val internalKeepsAlive: Boolean = true,
    val unknownKeepsAlive: Boolean = false,
    val leakRetainedMinBytes: Double = 1024.0,
    val maxRootPathsPerObject: Int = 128,
    val createdAtEpochMs: Long = 0L,
    val note: String = ""
) {
    companion object {
        const val UNIT_BYTES = "B"
        fun v1(now: Long = 0L) = AnalysisPolicy("policy-v1", weakKeepsAlive = false, createdAtEpochMs = now,
            note = "初始参数：弱引用不保活，内部边保活，泄漏阈值 1024 B")
        fun v2(now: Long = 0L) = AnalysisPolicy("policy-v2", weakKeepsAlive = true, createdAtEpochMs = now,
            note = "参数修订：弱引用显式保活")
    }
}
