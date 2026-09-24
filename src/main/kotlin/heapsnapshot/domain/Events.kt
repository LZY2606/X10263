package heapsnapshot.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 分支：档案中的线性可重放分支（baseline / boundary / dominator-principles）。 */
@Serializable
data class BranchId(val name: String) {
    companion object {
        val BASELINE = BranchId("baseline")
        val BOUNDARY = BranchId("boundary-cases")
        val PRINCIPLES = BranchId("dominator-principles")
    }
}

/**
 * 判断依据（机器可读取的事件）。对应四条硬规则：
 * 支配树只在可达图上构建；弱引用保活由显式策略决定；
 * 多个等长 root 路径保留；地址不是稳定身份。
 */
@Serializable
enum class PrincipleCode {
    DOMINATOR_REACHABLE_ONLY,
    WEAK_KEEPALIVE_EXPLICIT_POLICY,
    ALL_EQUAL_ROOT_PATHS_KEPT,
    ADDRESS_NOT_STABLE_IDENTITY
}

@Serializable
data class PrincipleJudgement(
    val code: PrincipleCode,
    val holds: Boolean,
    val evidenceObjectIds: List<String>,
    val rationale: String,
    val observed: JsonObject? = null
)

@Serializable
sealed class EventPayload {
    @Serializable
    data class ImportSnapshot(val materialRef: String, val parserVersion: String, val diagnostics: List<ParseDiagnostic>) : EventPayload()

    /** 反例：只改动一个直接来源（directSource），并记录影响范围判定。 */
    @Serializable
    data class CounterExample(
        val caseId: String,
        val branch: String,
        val directSource: String,
        val mutation: String,
        val inputFragment: JsonObject,
        val affectedObjectIds: List<String>,
        val scopeClaim: String,
        val scopeHolds: Boolean,
        val judgement: PrincipleJudgement? = null,
        val adoptedResultId: String?,
        val rejectedResultId: String?
    ) : EventPayload()

    /** 参数修订：产生新的策略版本。 */
    @Serializable
    data class ParamRevision(val oldPolicyVersion: String, val newPolicyVersion: String, val patch: JsonObject, val note: String) : EventPayload()

    /** 只影响局部依赖的源数据改动。 */
    @Serializable
    data class SourceChange(val materialRef: String, val changedLocalIds: List<String>, val note: String) : EventPayload()

    /** 计算：记录输入摘要、参数版本、父级依赖。 */
    @Serializable
    data class Analysis(
        val resultId: String,
        val snapshotId: String,
        val policyVersion: String,
        val inputSummary: InputSummary,
        val dependencyIds: List<String>,
        val valid: Boolean
    ) : EventPayload()

    /** 互相冲突的人工决定：接受 / 否定 / 参数调整都产生新事件，不覆盖历史。 */
    @Serializable
    data class Decision(
        val targetResultId: String,
        val verdict: String, // ACCEPTED / REJECTED / ADJUSTED
        val policyVersion: String?,
        val rationale: String,
        val conflictsWithEventId: String?
    ) : EventPayload()
}

@Serializable
data class LedgerEvent(
    val seq: Long,
    val eventId: String,
    val parentEventId: String?,
    val branch: String,
    val epochMs: Long,
    val payload: EventPayload
)
