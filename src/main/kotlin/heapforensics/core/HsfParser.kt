package heapforensics.core

/**
 * HSF (Heap Shadow Forensics) 行式快照格式解析器。
 *
 * 原始表示全部保留：对象 id、地址原文、类元数据、强/弱/内部边、root 分类、
 * 不完整块原文与解析器版本都进入 [HeapSnapshot]；无法解析的行不会丢，
 * 而是成为带行号的 [Diagnostic]。
 *
 * 三类失败严格区分：
 *  - 预算耗尽   -> [AnalysisResult.BudgetExhausted]
 *  - 格式损坏   -> [AnalysisResult.FormatCorrupt]（坏魔数、未闭合不完整块等）
 *  - 语义无解   -> 计算阶段产生（见 GraphAnalysis），解析器不冒充成功
 */
object HsfParser {

    const val MAGIC = "HSF/1"

    data class ParseInput(
        val text: String,
        val fallbackSnapshotId: String,
        val maxLines: Int = 200_000,
        val maxObjects: Int = 100_000,
    )

    data class ParseOutput(
        val snapshot: HeapSnapshot,
        val rawText: String,
    )

    fun parse(input: ParseInput): AnalysisResult<ParseOutput> {
        val lines = input.text.replace("\r\n", "\n").split("\n")
        if (lines.size > input.maxLines) {
            return AnalysisResult.BudgetExhausted(
                budget = "maxLines=${input.maxLines}",
                detail = "快照行数 ${lines.size} 超出预算",
            )
        }
        val first = lines.firstOrNull()?.trim()
        if (first != MAGIC) {
            return AnalysisResult.FormatCorrupt(
                listOf(Diagnostic(1, Severity.ERROR, "缺少魔数 $MAGIC（实际: ${first ?: "<empty>"}）", first ?: ""))
            )
        }

        var parserVersion = "unknown"
        var capturedAt = "unknown"
        var epochMillis = 0L
        var snapshotId: String? = null
        val classes = mutableListOf<HeapClass>()
        val objects = mutableListOf<HeapObject>()
        val edges = mutableListOf<HeapEdge>()
        val roots = mutableListOf<RootRef>()
        val blocks = mutableListOf<IncompleteBlock>()
        val diagnostics = mutableListOf<Diagnostic>()

        var inBlock: String? = null
        var blockReason = ""
        var blockStartLine = 0
        val blockRaw = StringBuilder()

        fun malformed(lineNo: Int, raw: String, why: String) {
            diagnostics += Diagnostic(lineNo, Severity.ERROR, "无法解析: $why", raw)
        }

        for (idx in 1 until lines.size) {
            val lineNo = idx + 1
            val raw = lines[idx]
            val line = raw.trim()
            if (inBlock != null) {
                if (line == "end-incomplete $inBlock") {
                    blocks += IncompleteBlock(inBlock!!, blockReason, blockStartLine, blockRaw.toString())
                    inBlock = null
                    blockRaw.clear()
                } else if (line.startsWith("end-incomplete")) {
                    return AnalysisResult.FormatCorrupt(
                        diagnostics + Diagnostic(lineNo, Severity.ERROR, "不完整块闭合标签与 $inBlock 不匹配", raw)
                    )
                } else {
                    blockRaw.append(raw).append("\n")
                }
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(Regex("\\s+"))
            val kv = tokens.drop(1).mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }.toMap()

            when (tokens[0]) {
                "parser" -> parserVersion = tokens.getOrElse(1) { "unknown" }
                "captured" -> {
                    capturedAt = tokens.getOrElse(1) { "unknown" }
                    epochMillis = kv["epoch"]?.toLongOrNull() ?: run {
                        diagnostics += Diagnostic(lineNo, Severity.WARN, "captured 缺少合法 epoch=，保留原始时间串", raw)
                        0L
                    }
                }
                "snapshot" -> snapshotId = tokens.getOrNull(1)
                "class" -> {
                    val id = tokens.getOrNull(1)
                    val name = kv["name"]
                    val size = kv["size"]?.toLongOrNull()
                    if (id == null || name == null || size == null) malformed(lineNo, raw, "class 需要 id、name=、size=")
                    else classes += HeapClass(
                        classId = id, name = name,
                        superClassId = kv["super"]?.takeIf { it != "-" },
                        loaderId = kv["loader"]?.takeIf { it != "-" },
                        instanceSize = size,
                    )
                }
                "object" -> {
                    val id = tokens.getOrNull(1)
                    val addr = kv["addr"]
                    val cls = kv["class"]
                    val size = kv["size"]?.toLongOrNull()
                    if (id == null || addr == null || cls == null || size == null) {
                        malformed(lineNo, raw, "object 需要 id、addr=、class=、size=")
                    } else {
                        objects += HeapObject(
                            objectId = id, address = addr, classId = cls, sizeBytes = size,
                            allocSite = kv["site"]?.takeIf { it != "-" },
                            incomplete = tokens.any { it == "incomplete" },
                        )
                        if (objects.size > input.maxObjects) {
                            return AnalysisResult.BudgetExhausted(
                                budget = "maxObjects=${input.maxObjects}",
                                detail = "对象数超出预算（第 $lineNo 行）",
                            )
                        }
                    }
                }
                "edge" -> {
                    val from = tokens.getOrNull(1)
                    val arrow = tokens.getOrNull(2)
                    val to = tokens.getOrNull(3)
                    val kind = kv["kind"]?.let { runCatching { EdgeKind.valueOf(it) }.getOrNull() }
                    if (from == null || arrow != "->" || to == null || kind == null) {
                        malformed(lineNo, raw, "edge 需要 <from> -> <to> kind=STRONG|WEAK|INTERNAL")
                    } else {
                        edges += HeapEdge(from, to, kind, kv["field"]?.takeIf { it != "-" })
                    }
                }
                "root" -> {
                    val id = tokens.getOrNull(1)
                    val kind = kv["kind"]?.let { runCatching { RootKind.valueOf(it) }.getOrNull() }
                    if (id == null || kind == null) malformed(lineNo, raw, "root 需要 id 与 kind=<RootKind>")
                    else roots += RootRef(id, kind, kv["note"]?.takeIf { it != "-" })
                }
                "begin-incomplete" -> {
                    val id = tokens.getOrNull(1)
                    if (id == null) malformed(lineNo, raw, "begin-incomplete 需要块 id")
                    else {
                        inBlock = id
                        blockReason = kv["reason"] ?: "unspecified"
                        blockStartLine = lineNo
                        blockRaw.clear()
                    }
                }
                else -> diagnostics += Diagnostic(lineNo, Severity.WARN, "未知指令 '${tokens[0]}'，原始行保留", raw)
            }
        }

        if (inBlock != null) {
            return AnalysisResult.FormatCorrupt(
                diagnostics + Diagnostic(lines.size, Severity.ERROR, "不完整块 $inBlock 未闭合（起始于第 $blockStartLine 行）", blockRaw.toString())
            )
        }

        val dupObjects = objects.groupingBy { it.objectId }.eachCount().filter { it.value > 1 }
        for ((id, n) in dupObjects) {
            diagnostics += Diagnostic(0, Severity.ERROR, "对象 id '$id' 重复出现 $n 次（id 是稳定身份，必须唯一）", id)
        }
        if (dupObjects.isNotEmpty()) {
            return AnalysisResult.FormatCorrupt(diagnostics)
        }

        val snap = HeapSnapshot(
            snapshotId = snapshotId ?: input.fallbackSnapshotId,
            parserVersion = parserVersion,
            capturedAt = capturedAt,
            epochMillis = epochMillis,
            classes = classes,
            objects = objects,
            edges = edges,
            roots = roots,
            incompleteBlocks = blocks,
            diagnostics = diagnostics,
        )
        return AnalysisResult.Ok(ParseOutput(snap, input.text))
    }
}
