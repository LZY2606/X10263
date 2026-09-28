package hs.parser

import hs.domain.*

/**
 * 离线 .hsnap 行格式（领域专用，不是通用表格）：
 *
 *   SNAPSHOT <snapshotId> <parserVersion> [format=...] [recordedAt=epochMillis] [unit=B] [label=...]
 *   CLASS    <classId> <name> [extends=<superId>] [loader=<objectId>]
 *   OBJECT   <originalId> [address=<0x..>] class=<classId> size=<n><unit> [incomplete reason="..."]
 *   EDGE     <fromId> -> <toId> kind=strong|weak|internal|unknown [label=...] [field=...]
 *   ROOT     <objectId> kind=<rootKind> [thread=<serial>]
 *   SITE     <objectId> site=<siteId> method=<m> [class=<id>] [line=<n>] [source=<file>]
 *   TRUNCATED <raw...>          # 不完整块：保留原始行
 *   # 注释
 *
 * 解析器不做规范化：size/address/未知枚举都按原始字符串保留，只附诊断。
 */
class HsnapParser(private val budget: IngestBudget = IngestBudget()) {

    private val sizeRegex = Regex("""^size=(-?\d+(?:\.\d+)?)([A-Za-z]*)$""")

    fun parse(text: String, importedAtMillis: Long = System.currentTimeMillis()): IngestResult {
        val diags = mutableListOf<Diagnostic>()
        fun err(code: String, pos: SourcePos, msg: String, raw: String? = null, sev: Severity = Severity.ERROR, oid: String? = null) {
            if (diags.size < budget.maxDiagnostics) diags += Diagnostic(code, sev, msg, pos, raw, oid)
        }

        if (text.length > budget.maxChars) {
            return IngestResult.BudgetExhausted(
                listOf(Diagnostic("BUDGET_CHARS", Severity.ERROR,
                    "输入超过字符预算 ${budget.maxChars}，停止解析", SourcePos(0, 0, 0), text.length.toString())),
                "maxChars", budget.maxChars, text.length.toLong()
            )
        }

        var meta: SnapshotMeta? = null
        val classes = linkedMapOf<String, ClassMeta>()
        val objects = linkedMapOf<String, HeapObject>()
        val edges = mutableListOf<Edge>()
        val roots = mutableListOf<RootRecord>()
        val sites = mutableListOf<AllocationSite>()
        val incomplete = mutableListOf<IncompleteBlock>()
        var edgeSeq = 0

        data class Tok(val line: String, val pos: SourcePos, val parts: List<String>)

        val lines = text.split('\n')
        var offset = 0
        for ((idx0, rawLine) in lines.withIndex()) {
            val lineNo = idx0 + 1
            val pos = SourcePos(lineNo, 1, offset, "payload")
            offset += rawLine.length + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = tokenize(line)
            if (parts.isEmpty()) continue
            val head = parts[0].uppercase()

            when (head) {
                "SNAPSHOT" -> {
                    if (parts.size < 3) {
                        err("SNAPSHOT_MALFORMED", pos, "SNAPSHOT 需要 snapshotId 与 parserVersion", rawLine)
                        return IngestResult.Corrupt(diags, "unknown")
                    }
                    val args = parseArgs(parts.drop(3))
                    meta = SnapshotMeta(
                        snapshotId = parts[1],
                        parserVersion = parts[2],
                        formatVersion = args["format"],
                        recordedAtMillis = args["recordedAt"]?.toLongOrNull(),
                        unitHint = args["unit"],
                        importedAtMillis = importedAtMillis,
                        label = args["label"] ?: parts[1]
                    )
                }
                "CLASS" -> {
                    if (parts.size < 3) {
                        err("CLASS_MALFORMED", pos, "CLASS 需要 classId 与 name", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val args = parseArgs(parts.drop(3))
                    val cm = ClassMeta(parts[1], unquote(parts[2]), args["extends"], args["loader"], pos)
                    if (classes.containsKey(cm.classId))
                        err("DUP_CLASS_ID", pos, "重复 classId：${cm.classId}（保留首条，后续记为诊断）", rawLine, oid = cm.classId)
                    else classes[cm.classId] = cm
                }
                "OBJECT" -> {
                    if (objects.size.toLong() >= budget.maxObjects)
                        return budgetFail("maxObjects", budget.maxObjects, objects.size.toLong(), diags)
                    if (parts.size < 4) {
                        err("OBJECT_MALFORMED", pos, "OBJECT 需要 originalId、class、size", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val originalId = parts[1]
                    val args = parseArgs(parts.drop(2))
                    val classId = args["class"]
                    val sizeTok = parts.drop(2).firstOrNull { it.startsWith("size=") }
                    if (classId == null || sizeTok == null) {
                        err("OBJECT_MALFORMED", pos, "OBJECT 缺少 class= 或 size=", rawLine, oid = originalId)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val m = sizeRegex.matchEntire(sizeTok)
                    var sizeValue = sizeTok.removePrefix("size=")
                    var unit = "B"
                    if (m != null) {
                        sizeValue = m.groupValues[1]
                        unit = m.groupValues[2].ifEmpty { "B" }
                    } else {
                        err("SIZE_UNCONVERTIBLE", pos, "size 无法规范化：$sizeTok（原始值保留在对象上）", sizeTok, Severity.WARNING, originalId)
                    }
                    if (m != null && m.groupValues[1].toDoubleOrNull()?.let { it < 0 } == true)
                        err("SIZE_NEGATIVE", pos, "size 为负：$sizeTok", sizeTok, Severity.WARNING, originalId)
                    val isIncomplete = parts.drop(2).any { it == "incomplete" }
                    val reason = args["reason"] ?: ""
                    if (isIncomplete) incomplete += IncompleteBlock("blk-$lineNo", reason, rawLine, pos)
                    val addr = args["address"] // 重复地址合法：地址不是稳定身份
                    if (objects.containsKey(originalId))
                        err("DUP_OBJECT_ID", pos, "重复 originalId：$originalId（身份冲突，保留首条）", rawLine, oid = originalId)
                    else objects[originalId] = HeapObject(
                        objectId = originalId, originalId = originalId, address = addr,
                        classId = classId, shallowSize = sizeValue, unit = unit.ifEmpty { "B" },
                        complete = !isIncomplete, declaredAt = pos
                    )
                }
                "EDGE" -> {
                    if (edges.size.toLong() >= budget.maxEdges)
                        return budgetFail("maxEdges", budget.maxEdges, edges.size.toLong(), diags)
                    val arrow = parts.indexOf("->")
                    if (parts.size < 5 || arrow < 2 || arrow >= parts.size - 1) {
                        err("EDGE_MALFORMED", pos, "EDGE 语法：EDGE <from> -> <to> kind=...", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val from = parts[1]; val to = parts[arrow + 1]
                    val args = parseArgs(parts.drop(arrow + 2))
                    val kind = when ((args["kind"] ?: "strong").lowercase()) {
                        "strong" -> EdgeKind.STRONG
                        "weak" -> EdgeKind.WEAK
                        "internal" -> EdgeKind.INTERNAL
                        else -> EdgeKind.UNKNOWN
                    }
                    if (kind == EdgeKind.UNKNOWN)
                        err("EDGE_KIND_UNKNOWN", pos, "未知边类型，原始 kind 保留为 UNKNOWN", args["kind"], Severity.WARNING)
                    edgeSeq++
                    edges += Edge("e%04d@L%d".format(edgeSeq, lineNo), from, to, kind,
                        args["label"]?.let { unquote(it) }, args["field"], pos)
                }
                "ROOT" -> {
                    if (parts.size < 3) {
                        err("ROOT_MALFORMED", pos, "ROOT 需要 objectId 与 kind", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val args = parseArgs(parts.drop(3))
                    val rawKind = args["kind"] ?: parts[2]
                    val kind = RootKind.parse(rawKind) ?: RootKind.UNKNOWN
                    if (kind == RootKind.UNKNOWN)
                        err("ROOT_KIND_UNKNOWN", pos, "未知 root 分类：$rawKind（保留 rawKind）", rawKind, Severity.WARNING, parts[1])
                    roots += RootRecord(parts[1], kind, rawKind, args["thread"], pos)
                }
                "SITE" -> {
                    if (parts.size < 4) {
                        err("SITE_MALFORMED", pos, "SITE 需要 objectId、site、method", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    val args = parseArgs(parts.drop(2))
                    val siteId = args["site"] ?: "site-L$lineNo"
                    val method = args["method"]?.let { unquote(it) } ?: "<unknown>"
                    sites += AllocationSite(siteId, parts[1], method, args["class"],
                        args["line"]?.toIntOrNull(), args["source"]?.let { unquote(it) }, pos)
                }
                "TRUNCATED" -> {
                    incomplete += IncompleteBlock("blk-$lineNo", "truncated", rawLine, pos)
                }
                else -> {
                    if (head.startsWith("OBJ") || head.startsWith("SNAP") || head.startsWith("TRUNC")) {
                        err("TOKEN_DAMAGED", pos, "无法识别的记录头：$head（格式损坏）", rawLine)
                        return IngestResult.Corrupt(diags, meta?.parserVersion ?: "unknown")
                    }
                    err("UNKNOWN_RECORD", pos, "忽略无法识别的行", rawLine, Severity.WARNING)
                }
            }
        }
        if (meta == null) {
            err("NO_SNAPSHOT_HEADER", SourcePos(1, 1, 0), "缺少 SNAPSHOT 头")
            return IngestResult.Corrupt(diags, "unknown")
        }

        // 引用完整性：悬空引用保留为诊断，不静默丢边。
        val ids = objects.keys
        for (e in edges) {
            if (e.fromObjectId !in ids) err("DANGLING_EDGE_FROM", e.declaredAt, "边起点不存在：${e.fromObjectId}", e.edgeId, Severity.WARNING, e.fromObjectId)
            if (e.toObjectId !in ids) err("DANGLING_EDGE_TO", e.declaredAt, "边终点不存在：${e.toObjectId}", e.edgeId, Severity.WARNING, e.toObjectId)
        }
        for (r in roots) if (r.objectId !in ids)
            err("DANGLING_ROOT", r.declaredAt, "root 指向不存在对象：${r.objectId}", r.objectId, Severity.WARNING, r.objectId)

        return IngestResult.Ok(RawSnapshot(meta, classes.values.toList(), objects.values.toList(),
            edges, roots, sites, incomplete, diags, text))
    }

    private fun budgetFail(name: String, limit: Long, used: Long, diags: List<Diagnostic>): IngestResult.BudgetExhausted =
        IngestResult.BudgetExhausted(
            diags + Diagnostic("BUDGET_${name.uppercase()}", Severity.ERROR,
                "资源预算耗尽：$name 达到 $limit", SourcePos(0, 0, 0)),
            name, limit, used
        )

    /** 轻量分词：支持引号，不做 shell 展开。 */
    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        var q: Char? = null
        fun push() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        while (i < line.length) {
            val c = line[i]
            when {
                q != null -> { if (c == q) q = null else sb.append(c) }
                c == '"' || c == '\'' -> q = c
                c == ' ' || c == '\t' -> push()
                c == '#' && i > 0 && (line[i - 1] == ' ') -> { push(); return out }
                else -> sb.append(c)
            }
            i++
        }
        push()
        return out
    }

    private fun parseArgs(parts: List<String>): Map<String, String> =
        parts.mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq) to unquote(p.substring(eq + 1))
        }.toMap()

    private fun unquote(s: String): String =
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\'')))
            s.substring(1, s.length - 1) else s
}
