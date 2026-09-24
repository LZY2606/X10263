package heapsnapshot.parser

import heapsnapshot.domain.*
import kotlinx.serialization.json.*

/** 解析预算：结构合法但超出预算 -> BudgetExhausted（区别于格式损坏与语义无解）。 */
data class ParseBudget(
    val maxObjects: Long = 200_000,
    val maxEdges: Long = 1_000_000,
    val maxClasses: Long = 100_000,
    val maxDiagnostics: Long = 10_000
)

/**
 * 堆快照导入器。
 * 原始表示全部保留；只有派生层做规范化；三类失败互不混淆。
 */
class SnapshotParser(private val budget: ParseBudget = ParseBudget()) {

    sealed class Result {
        data class Ok(val snapshot: RawSnapshot) : Result()
        data class Failed(val outcome: FailureOutcome) : Result()
    }

    fun parse(text: String): Result {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return failFormat("顶层不是合法 JSON: ${e.message}", "$", null)
        }
        val headerEl = root["header"]
        if (headerEl !is JsonObject) return failFormat("缺少对象型 header", "$.header", null)
        val diags = DiagnosticCollector(budget.maxDiagnostics)
        val header = parseHeader(headerEl, diags)

        val classes = parseClasses(root, diags)
        if (classes.size.toLong() > budget.maxClasses)
            return failBudget("classes", budget.maxClasses, classes.size.toLong(), "$.classes")
        val objects = parseObjects(root, diags, header.sizeUnitRaw)
        if (objects.size.toLong() > budget.maxObjects)
            return failBudget("objects", budget.maxObjects, objects.size.toLong(), "$.objects")
        val edges = parseEdges(root, diags)
        if (edges.size.toLong() > budget.maxEdges)
            return failBudget("edges", budget.maxEdges, edges.size.toLong(), "$.edges")
        val roots = parseRoots(root, diags)
        val sites = parseSites(root, diags)
        val blocks = parseIncompleteBlocks(root, diags, header.sizeUnitRaw)

        crossValidate(objects, classes, edges, roots, diags)

        val unresolved = diags.all().filter { it.severity == Severity.ERROR && it.code in SEMANTIC_CODES }
        if (unresolved.isNotEmpty())
            return Result.Failed(FailureOutcome.SemanticallyUnresolved(unresolved))
        return Result.Ok(RawSnapshot(header, classes, objects, edges, roots, sites, blocks, diags.all()))
    }

    companion object {
        val SEMANTIC_CODES = setOf("DUPLICATE_OBJECT_ID", "DUPLICATE_CLASS_ID", "EDGE_DANGLING", "ROOT_DANGLING")
        private fun failFormat(message: String, location: String, raw: String?) =
            Result.Failed(FailureOutcome.FormatCorrupt(listOf(ParseDiagnostic(Severity.ERROR, "FORMAT_CORRUPT", message, location, raw))))
        private fun failBudget(name: String, limit: Long, observed: Long, location: String) =
            Result.Failed(FailureOutcome.BudgetExhausted(name, limit, observed, location))
    }
}
