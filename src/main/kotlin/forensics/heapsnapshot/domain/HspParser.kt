package forensics.heapsnapshot.domain

/**
 * HSP v1（Heap Shadow Protocol，离线纯文本堆快照）解析器。
 *
 * 三类失败必须可区分：
 *  - 格式损坏（CORRUPT_FORMAT）：结构上无法解析
 *  - 语义无解（SEMANTIC_AMBIGUITY）：可解析但指向不存在的身份/重复身份冲突
 *  - 资源预算耗尽（BUDGET_EXHAUSTED）：超过显式预算
 *
 * 无法转换的值留在原位置（RawQuantity.convertible=false）并附诊断，不丢字段。
 */
class HspParser(
    private val maxLines: Int = 2_000_000,
    private val maxEntities: Int = 500_000,
) {
    sealed interface Result {
        data class Ok(val snapshot: ParsedSnapshot) : Result
        data class Corrupt(val diagnostics: List<Diagnostic>, val rawLines: List<String>) : Result
        data class SemanticAmbiguity(
            val snapshot: ParsedSnapshot,
            val diagnostics: List<Diagnostic>,
        ) : Result
        data class BudgetExhausted(val limit: Int, val observed: Int, val line: Int) : Result
    }

    fun parse(content: String): Result {
        val rawLines = content.split("\n").let {
            if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it
        }
        if (rawLines.size > maxLines) {
            return Result.BudgetExhausted(maxLines, rawLines.size, maxLines + 1)
        }

        var meta: SnapshotMeta? = null
        var baseUnit = "bytes"
        val classes = mutableListOf<ClassRecord>()
        val sites = mutableListOf<AllocationSiteRecord>()
        val objects = mutableListOf<ObjectRecord>()
        val edges = mutableListOf<EdgeRecord>()
        val roots = mutableListOf<RootRecord>()
        val marks = mutableListOf<IncompleteMark>()
        val correspondences = mutableListOf<CorrespondenceRecord>()
        val diags = mutableListOf<Diagnostic>()

        val classIds = HashSet<String>()
        val objectIds = HashSet<String>()
        var entityCount = 0

        fun budget(n: Int, line: Int): Result.BudgetExhausted? {
            if (entityCount + n > maxEntities) {
                return Result.BudgetExhausted(maxEntities, entityCount + n, line)
            }
            entityCount += n
            return null
        }

        rawLines.forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val parts = line.split(Regex("\\s+"))
            val head = parts[0]
            fun err(code: String, msg: String, col: Int = 1, level: Diagnostic.Level = Diagnostic.Level.ERROR) {
                diags.add(Diagnostic(code, level, lineNo, col, "line $lineNo", msg, raw))
            }
            fun need(n: Int): Boolean {
                if (parts.size < n) {
                    err("CORRUPT_FORMAT", "记录参数不足：期望 $n，实际 ${parts.size}")
                    return false
                }
                return true
            }

            when (head) {
                "SNAPSHOT" -> {
                    if (!need(4)) return@forEachIndexed
                    if (meta != null) err("CORRUPT_FORMAT", "存在多个 SNAPSHOT 头")
                    baseUnit = parts.getOrElse(3) { "bytes" }
                    meta = SnapshotMeta(
                        identity = parts[1],
                        displayName = parts[2].replace('_', ' '),
                        parserVersion = parts[3],
                        takenAt = parts.getOrElse(4) { "unknown" },
                        baseUnit = baseUnit,
                    )
                }
                "ALLOCATION_SITE" -> {
                    if (!need(4)) return@forEachIndexed
                    budget(1, lineNo)?.let { return it }
                    sites.add(AllocationSiteRecord(parts[1], parts[2], parts.drop(3).joinToString(" "), lineNo, raw))
                }
                "CLASS" -> {
                    if (!need(3)) return@forEachIndexed
                    budget(1, lineNo)?.let { return it }
                    val id = parts[1]
                    if (!classIds.add(id)) {
                        err("SEMANTIC_AMBIGUITY", "重复的类 id：$id"); return@forEachIndexed
                    }
                    val keyPart = parts.getOrNull(4)
                    classes.add(
                        ClassRecord(
                            id = id,
                            key = if (keyPart != null && parts.getOrNull(3) == "KEY") keyPart else id,
                            name = parts[2],
                            allocationSiteId = if (keyPart == null && parts.size > 3) parts[3] else parts.getOrNull(5),
                            line = lineNo,
                            rawLine = raw,
                        ),
                    )
                }
                "OBJECT" -> {
                    if (!need(5)) return@forEachIndexed
                    budget(1, lineNo)?.let { return it }
                    val id = parts[1]
                    if (!objectIds.add(id)) {
                        err("SEMANTIC_AMBIGUITY", "重复的对象 id：$id"); return@forEachIndexed
                    }
                    val sizeTok = parts[4]
                    val parsedSize = parseSize(sizeTok, baseUnit, lineNo, raw, diags)
                    val incomplete = parts.contains("INCOMPLETE")
                    val site = optionValue(parts, "SITE")
                    val fieldsRaw = if (line.contains("FIELDS=")) line.substringAfter("FIELDS=").substringBefore(" ").ifEmpty {
                        line.substringAfter("FIELDS=")
                    } else null
                    val addrRaw = parts[3]
                    objects.add(
                        ObjectRecord(
                            id = id,
                            classId = parts[2],
                            addressRaw = addrRaw,
                            address = parseAddress(addrRaw, lineNo, raw, diags),
                            size = parsedSize,
                            complete = !incomplete,
                            allocationSiteId = site,
                            fieldsRaw = fieldsRaw,
                            line = lineNo,
                            rawLine = raw,
                        ),
                    )
                }
                "EDGE" -> {
                    if (!need(4)) return@forEachIndexed
                    val kind = when (parts[3].lowercase()) {
                        "strong" -> EdgeKind.STRONG
                        "weak" -> EdgeKind.WEAK
                        "internal" -> EdgeKind.INTERNAL
                        else -> {
                            err("CORRUPT_FORMAT", "未知边类型：${parts[3]}（允许 strong/weak/internal）"); return@forEachIndexed
                        }
                    }
                    edges.add(
                        EdgeRecord(
                            from = parts[1],
                            to = parts[2],
                            kind = kind,
                            field = optionValue(parts, "FIELD"),
                            line = lineNo,
                            rawLine = raw,
                        ),
                    )
                }
                "ROOT" -> {
                    if (!need(3)) return@forEachIndexed
                    roots.add(RootRecord(parts[1], parts[2], parts.getOrNull(3), lineNo, raw))
                }
                "INCOMPLETE" -> {
                    marks.add(
                        IncompleteMark(
                            block = parts[1],
                            kind = parts.getOrElse(2) { "unknown" },
                            detail = parts.drop(3).joinToString(" "),
                            line = lineNo,
                            rawLine = raw,
                        ),
                    )
                }
                "CORRESPOND" -> {
                    if (!need(5) || parts[3] != "=") {
                        err("CORRUPT_FORMAT", "CORRESPOND 语法应为：CORRESPOND <kind> <fromKey> = <toKey>")
                        return@forEachIndexed
                    }
                    correspondences.add(
                        CorrespondenceRecord(
                            kind = parts[1],
                            fromKey = parts[2],
                            toKey = parts[4],
                            note = parts.getOrNull(5),
                            line = lineNo,
                            rawLine = raw,
                        ),
                    )
                }
                else -> err("CORRUPT_FORMAT", "无法识别的记录类型：$head")
            }
        }

        val head = meta
        if (head == null) {
            diags.add(Diagnostic("CORRUPT_FORMAT", Diagnostic.Level.ERROR, 1, 1, "header", "缺少 SNAPSHOT 头", null))
        }

        // 悬空引用：可解析但语义无解 —— 记 ERROR 诊断，材料仍然完整保留。
        edges.forEach { e ->
            if (e.from !in objectIds) diags.add(
                Diagnostic("SEMANTIC_AMBIGUITY", Diagnostic.Level.ERROR, e.line, 6, "edge.from",
                    "边的源对象不存在：${e.from}", e.rawLine),
            )
            if (e.to !in objectIds) diags.add(
                Diagnostic("SEMANTIC_AMBIGUITY", Diagnostic.Level.ERROR, e.line, 6, "edge.to",
                    "边的目标对象不存在：${e.to}", e.rawLine),
            )
        }
        roots.forEach { r ->
            if (r.ref !in objectIds) diags.add(
                Diagnostic("SEMANTIC_AMBIGUITY", Diagnostic.Level.ERROR, r.line, 6, "root.ref",
                    "root 指向不存在的对象：${r.ref}", r.rawLine),
            )
        }
        objects.forEach { o ->
            if (o.classId !in classIds) diags.add(
                Diagnostic("SEMANTIC_AMBIGUITY", Diagnostic.Level.WARNING, o.line, 10, "object.classId",
                    "对象引用了未知类：${o.classId}（该对象的类群归为 __unknown__）", o.rawLine),
            )
        }

        val snapshot = ParsedSnapshot(
            meta = head ?: SnapshotMeta("__unknown__", "未知快照", "unknown", "unknown"),
            classes = classes, allocationSites = sites, objects = objects, edges = edges,
            roots = roots, incompleteMarks = marks, correspondences = correspondences,
            diagnostics = diags, rawLines = rawLines,
        )

        val hardErrors = diags.filter { it.level == Diagnostic.Level.ERROR }
        val corrupt = hardErrors.any { it.code == "CORRUPT_FORMAT" }
        val semantic = hardErrors.any { it.code == "SEMANTIC_AMBIGUITY" }
        return when {
            corrupt -> Result.Corrupt(hardErrors.filter { it.code == "CORRUPT_FORMAT" }, rawLines)
            semantic -> Result.SemanticAmbiguity(snapshot, diags)
            else -> {
                if (diags.isNotEmpty()) snapshot.copy(diagnostics = diags).let(Result::Ok)
                else Result.Ok(snapshot)
            }
        }
    }

    private fun optionValue(parts: List<String>, key: String): String? {
        val i = parts.indexOf(key)
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else null
    }

    private fun parseAddress(raw: String, line: Int, rawLine: String, diags: MutableList<Diagnostic>): Long? {
        val cleaned = raw.removePrefix("0x").removePrefix("0X").replace("_", "")
        return cleaned.toLongOrNull(16) ?: cleaned.toLongOrNull(10) ?: run {
            diags.add(
                Diagnostic(
                    "UNCONVERTIBLE_ADDRESS", Diagnostic.Level.WARNING, line, raw.indexOf(cleaned).coerceAtLeast(1) + 1,
                    "object.address", "地址无法转换为整数，保留原值：$raw", rawLine,
                ),
            )
            null
        }
    }

    /** 大小规范化：派生层才换算；无法转换留在原位置并附诊断。 */
    private fun parseSize(token: String, baseUnit: String, line: Int, rawLine: String, diags: MutableList<Diagnostic>): RawQuantity {
        val match = Regex("^(\\d+(?:\\.\\d+)?)([a-zA-Z]*)$").matchEntire(token)
        if (match == null) {
            diags.add(
                Diagnostic("UNCONVERTIBLE_SIZE", Diagnostic.Level.WARNING, line, 1, "object.size",
                    "大小无法转换：$token（按基单位 $baseUnit 保留原值）", rawLine),
            )
            return RawQuantity(token, baseUnit, null, false, "UNCONVERTIBLE_SIZE")
        }
        val num = match.groupValues[1]
        val unit = match.groupValues[2].ifEmpty { baseUnit }
        val factor = when (unit.lowercase()) {
            "b", "byte", "bytes" -> 1L
            "kb", "kib" -> 1024L
            "mb", "mib" -> 1024L * 1024
            "gb", "gib" -> 1024L * 1024 * 1024
            else -> null
        }
        if (factor == null) {
            diags.add(
                Diagnostic("UNKNOWN_UNIT", Diagnostic.Level.WARNING, line, 1, "object.size.unit",
                    "未知单位：$unit，值保留原位", rawLine),
            )
            return RawQuantity(token, unit, null, false, "UNKNOWN_UNIT")
        }
        val bytes = if (num.contains('.')) (num.toDouble() * factor).toLong() else num.toLong() * factor
        return RawQuantity(token, unit, bytes, true)
    }
}
