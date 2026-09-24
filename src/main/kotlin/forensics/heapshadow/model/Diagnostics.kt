package forensics.heapshadow.model

enum class Severity { ERROR, WARNING, INFO }

/** 三种失败模式必须互不相同：格式损坏 / 语义无解 / 资源预算耗尽。 */
enum class DiagnosticCategory {
    /** JSON/词法结构损坏，无法定位到合法材料。 */
    FORMAT_CORRUPT,
    /** 结构可解析但语义矛盾（如边指向不存在对象且无法恢复）。 */
    SEMANTIC_UNRESOLVABLE,
    /** 路径枚举等超出资源预算；输入本身合法。 */
    BUDGET_EXHAUSTED,
    /** 可恢复问题：重复地址、未知单位、不完整块等。 */
    RECOVERABLE,
}

data class Diagnostic(
    val diagnosticId: String,
    val category: DiagnosticCategory,
    val severity: Severity,
    val code: String,
    val message: String,
    val location: SourcePos?,
    /** 受影响对象/边/root 的稳定身份，供 UI 高亮与证据往返。 */
    val affectedIds: List<String> = emptyList(),
)

/**
 * 导入结果三分支：
 *  - Ok：材料完整或仅有可恢复诊断；
 *  - FormatCorrupt：结构损坏；
 *  - SemanticUnresolvable：结构合法但语义矛盾。
 * 预算耗尽不属于导入期（属于分析期），在 AnalysisResult 中表达。
 */
sealed class ImportResult {
    data class Ok(val material: SnapshotMaterial, val diagnostics: List<Diagnostic>) : ImportResult()
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ImportResult()
    data class SemanticUnresolvable(val diagnostics: List<Diagnostic>) : ImportResult()
}

/** 弱引用保活策略（显式参数，不允许藏在前端）。 */
enum class WeakPolicy {
    /** 弱引用不保活：支配树/可达集忽略 WEAK 边（默认语义）。 */
    WEAK_DOES_NOT_KEEP_ALIVE,
    /** 弱引用视为保活：WEAK 边按 STRONG 参与可达与支配。 */
    WEAK_KEEPS_ALIVE,
    /** 双视图：同时给出两套结论，人工决定采纳哪套。 */
    BOTH_VIEWS,
}

/**
 * 参数版本：每个计算结果都记录它，保证重放与时间旅行。
 */
data class AnalysisParameters(
    val parameterVersion: String,
    val weakPolicy: WeakPolicy,
    /** 泄漏候选保留大小阈值（字节）；null 表示不按阈值过滤。 */
    val leakRetainedThresholdBytes: Long?,
    /** 每个对象枚举 root 路径的最大条数；超出即 BUDGET_EXHAUSTED 诊断。 */
    val maxRootPathsPerObject: Int,
    /** 参与枚举的最大总路径数。 */
    val maxTotalPaths: Int,
    /** INTERNAL 边是否保活（显式策略，内部边默认保活但可关）。 */
    val internalKeepsAlive: Boolean,
) {
    companion object {
        const val INITIAL_VERSION = "params/v1"
        fun v1() = AnalysisParameters(
            parameterVersion = INITIAL_VERSION,
            weakPolicy = WeakPolicy.WEAK_DOES_NOT_KEEP_ALIVE,
            leakRetainedThresholdBytes = 64L,
            maxRootPathsPerObject = 64,
            maxTotalPaths = 4096,
            internalKeepsAlive = true,
        )
    }
}

/** 输入摘要：与参数版本、父依赖一起构成计算结果出处三要素。 */
data class InputDigest(
    val snapshotId: String,
    /** 材料规范化序列化后的稳定哈希（仅材料，不含派生结论）。 */
    val materialSha256: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
)
