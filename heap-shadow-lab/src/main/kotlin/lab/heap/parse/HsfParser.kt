package lab.heap.parse

import lab.heap.domain.*

/**
 * HSF（Heap Shadow Forensics）离线快照格式。行导向、UTF-8：
 *
 *   @VERSION parser=hpf/3.1 format=hpf/1.0 dump-time-base=epoch_ms dump-time=...
 *   @META    snapshot-identity=... address-unit=byte size-unit=byte origin=...
 *   SITE     <id> | <coordinate> | <rawText> | <timeBase>
 *   CLASS    <classObjectId> | <name>
 *   OBJ      <objectId> | <address> | <className> | <classObjectId?> | <sizeRaw> [| site=<id>] [| incomplete=<reason>]
 *   EDGE     <edgeId> | <fromObjectId> -> <toObjectId> | <label> | STRONG|WEAK|INTERNAL
 *   ROOT     <objectId> | <rawType> [| <location>]
 *   BLOCK    offset=<n> length=<n?> hex=<...> reason=<...>
 *
 * 设计约束：
 *  - 原始表示一律保留（地址、size 原文）；规范化只在派生层。
 *  - size 无法转换时 sizeBytes=null，值留在 sizeBytesRaw 并附诊断，绝不丢弃。
 *  - 重复地址允许存在（地址不是稳定身份）；对象 id 冲突是语义无解。
 *  - 边/root 指向不存在对象 = 语义无解；结构损坏（截断/未知头/坏列数）= 格式损坏。
 */
object HsfParser {

    private val sizePattern = Regex("^\\s*(\\d+)\\s*(B|KB|MB)?\\s*$", RegexOption.IGNORE_CASE)

    fun parse(snapshotId: String, text: String): SnapshotMaterial {
        val lines = text.replace("\r\n", "\n").split("\n")
        val objects = mutableListOf<RawObject>()
        val edges = mutableListOf<RawEdge>()
        val roots = mutableListOf<RawRoot>()
        val sites = LinkedHashMap<String, AllocationSite>()
        val blocks = mutableListOf<IncompleteBlock>()
        val diags = mutableListOf<Diagnostic>()

        var parserVersion = "unknown"
        var formatVersion = "unknown"
        var dumpTimeBase = "unknown"
        var dumpTime = "unknown"
        var snapshotIdentity = snapshotId
        var addressUnit = "byte"
        var sizeUnit = "byte"
        var origin = "embedded"

        var objectOrdinal = 0
        var edgeOrdinal = 0
        var rootOrdinal = 0
        var corrupt = false
        val semanticErrors = mutableListOf<String>()
        val headerSeen = booleanArrayOf(false, false)

        for ((zeroBased, rawLine) in lines.withIndex()) {
            val lineNo = zeroBased + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val head = line.substringBefore(' ').trim()
            val body = line.substringAfter(' ', "").trim()
            try {
                when (head) {
                    "@VERSION" -> {
                        headerSeen[0] = true
                        val kv = parseKv(body)
                        parserVersion = kv["parser"] ?: parserVersion
                        formatVersion = kv["format"] ?: formatVersion
                        dumpTimeBase = kv["dump-time-base"] ?: dumpTimeBase
                        dumpTime = kv["dump-time"] ?: dumpTime
                    }
                    "@META" -> {
                        headerSeen[1] = true
                        val kv = parseKv(body)
                        snapshotIdentity = kv["snapshot-identity"] ?: snapshotIdentity
                        addressUnit = kv["address-unit"] ?: addressUnit
                        sizeUnit = kv["size-unit"] ?: sizeUnit
                        origin = kv["origin"] ?: origin
                    }
                    "SITE" -> {
                        val p = splitCols(body, 4, lineNo, rawLine, diags) ?: continue
                        sites[p[0]] = AllocationSite(p[0], p[1], p[2], p[3])
                    }
                    "OBJ" -> {
                        val cols = body.split("|").map { it.trim() }
                        if (cols.size < 5) {
                            diags += diag(lineNo, rawLine, "CORRUPT_COLUMN_COUNT",
                                "OBJ 需要至少 5 列，实际 ${cols.size}", fatal = true)
                            corrupt = true
                            continue
                        }
                        val (id, address, className, classId, sizeRaw) = cols
                        var site: String? = null
                        var incomplete: String? = null
                        for (extra in cols.drop(5)) {
                            when {
                                extra.startsWith("site=") -> site = extra.removePrefix("site=")
                                extra.startsWith("incomplete=") ->
                                    incomplete = extra.removePrefix("incomplete=")
                                else -> {
                                    diags += diag(lineNo, rawLine, "UNKNOWN_OBJ_OPTION",
                                        "无法识别的 OBJ 选项 '$extra'", fatal = false)
                                }
                            }
                        }
                        val sizeNorm = normalizeSize(sizeRaw, lineNo, rawLine, diags, sizeUnit)
                        objects += RawObject(
                            ordinal = objectOrdinal++, objectId = id, address = address,
                            className = className,
                            classObjectId = classId.ifBlank { null },
                            sizeBytesRaw = sizeRaw, sizeBytes = sizeNorm,
                            allocationSite = site, complete = incomplete == null,
                            incompleteReason = incomplete
                        )
                    }
                    "EDGE" -> {
                        val cols = body.split("|").map { it.trim() }
                        if (cols.size != 4) {
                            diags += diag(lineNo, rawLine, "CORRUPT_COLUMN_COUNT",
                                "EDGE 需要 4 列，实际 ${cols.size}", fatal = true)
                            corrupt = true
                            continue
                        }
                        val arrow = Regex("(.+?)\\s*->\\s*(.+)").matchEntire(cols[1])
                        if (arrow == null) {
                            diags += diag(lineNo, rawLine, "CORRUPT_EDGE_ARROW",
                                "EDGE 缺少 'from -> to'", fatal = true)
                            corrupt = true
                            continue
                        }
                        val kind = runCatching { EdgeKind.valueOf(cols[3]) }.getOrNull()
                        if (kind == null) {
                            diags += diag(lineNo, rawLine, "CORRUPT_EDGE_KIND",
                                "未知边类型 '${cols[3]}'（允许 STRONG/WEAK/INTERNAL）", fatal = true)
                            corrupt = true
                            continue
                        }
                        edges += RawEdge(edgeOrdinal++, cols[0],
                            arrow.groupValues[1], arrow.groupValues[2], cols[2], kind)
                    }
                    "ROOT" -> {
                        val cols = body.split("|").map { it.trim() }
                        if (cols.size < 2 || cols.size > 3) {
                            diags += diag(lineNo, rawLine, "CORRUPT_COLUMN_COUNT",
                                "ROOT 需要 2~3 列，实际 ${cols.size}", fatal = true)
                            corrupt = true
                            continue
                        }
                        val kind = mapRootKind(cols[1])
                        if (kind == RootKind.UNKNOWN) {
                            diags += diag(lineNo, rawLine, "UNKNOWN_ROOT_KIND",
                                "未识别 root 类型 '${cols[1]}'，按 UNKNOWN 保留", fatal = false)
                        }
                        roots += RawRoot(rootOrdinal++, cols[0], kind, cols[1],
                            cols.getOrNull(2)?.ifBlank { null })
                    }
                    "BLOCK" -> {
                        val kv = parseKv(body)
                        val offset = kv["offset"]?.toLongOrNull()
                        if (offset == null) {
                            diags += diag(lineNo, rawLine, "CORRUPT_BLOCK_OFFSET",
                                "BLOCK 缺少数值 offset", fatal = true)
                            corrupt = true
                            continue
                        }
                        blocks += IncompleteBlock(
                            offset = offset,
                            length = kv["length"]?.toLongOrNull(),
                            hexPrefix = (kv["hex"] ?: "").take(64),
                            reason = kv["reason"] ?: "unspecified"
                        )
                    }
                    else -> {
                        diags += diag(lineNo, rawLine, "CORRUPT_UNKNOWN_RECORD",
                            "未知记录头 '$head'（文件可能截断或非 HSF）", fatal = true)
                        corrupt = true
                    }
                }
            } catch (e: Exception) {
                diags += diag(lineNo, rawLine, "CORRUPT_PARSE_EXCEPTION",
                    "解析异常: ${e.message}", fatal = true)
                corrupt = true
            }
        }

        if (!headerSeen[0] || !headerSeen[1]) {
            diags += diag(null, "", "CORRUPT_HEADER",
                "缺少 @VERSION/@META 头（文件可能截断）", fatal = true, offset = null)
            corrupt = true
        }

        // 语义校验（结构已能读出，但图无解）
        val ids = objects.map { it.objectId }
        val dup = ids.groupingBy { it }.eachCount().filter { it.value > 1 }
        dup.keys.forEach { id ->
            diags += Diagnostic(null, null, null, "SEMANTIC_DUPLICATE_OBJECT_ID",
                "对象 id 重复 '$id'：快照内身份必须唯一", rawFragment = id, fatal = true)
            semanticErrors += "duplicate object id $id"
        }
        val idSet = ids.toHashSet()
        edges.forEach { e ->
            if (e.fromObjectId !in idSet) semanticErrors += "edge ${e.edgeId} 源对象缺失 ${e.fromObjectId}"
                .also { diags += Diagnostic(null, null, null, "SEMANTIC_DANGLING_EDGE",
                    "边 ${e.edgeId} 的源对象 ${e.fromObjectId} 不存在", e.edgeId, true) }
            if (e.toObjectId !in idSet) semanticErrors += "edge ${e.edgeId} 目标缺失 ${e.toObjectId}"
                .also { diags += Diagnostic(null, null, null, "SEMANTIC_DANGLING_EDGE",
                    "边 ${e.edgeId} 的目标对象 ${e.toObjectId} 不存在", e.edgeId, true) }
        }
        roots.forEach { r ->
            if (r.objectId !in idSet) semanticErrors += "root 指向缺失对象 ${r.objectId}"
                .also { diags += Diagnostic(null, null, null, "SEMANTIC_DANGLING_ROOT",
                    "root 指向不存在对象 ${r.objectId}", r.objectId, true) }
        }

        val status = when {
            corrupt -> ImportStatus.CORRUPT
            semanticErrors.isNotEmpty() -> ImportStatus.SEMANTIC_UNRESOLVABLE
            else -> ImportStatus.OK
        }

        return SnapshotMaterial(
            snapshotId = snapshotId,
            parser = ParserInfo(parserVersion, formatVersion, dumpTimeBase, dumpTime,
                addressUnit, sizeUnit, snapshotIdentity, origin),
            objects = objects.sortedWith(compareBy({ it.ordinal }, { it.objectId })),
            edges = edges.sortedWith(compareBy({ it.ordinal }, { it.edgeId })),
            roots = roots.sortedWith(compareBy({ it.ordinal }, { it.objectId })),
            allocationSites = sites.values.toList(),
            incompleteBlocks = blocks.sortedBy { it.offset },
            diagnostics = diags,
            rawLines = lines,
            status = status
        )
    }

    private fun splitCols(body: String, expected: Int, lineNo: Int,
                         rawLine: String, diags: MutableList<Diagnostic>): List<String>? {
        val cols = body.split("|").map { it.trim() }
        if (cols.size != expected) {
            diags += diag(lineNo, rawLine, "CORRUPT_COLUMN_COUNT",
                "需要 $expected 列，实际 ${cols.size}", fatal = true)
            return null
        }
        return cols
    }

    private fun normalizeSize(raw: String, lineNo: Int, rawLine: String,
                              diags: MutableList<Diagnostic>, declaredUnit: String): Long? {
        val m = sizePattern.matchEntire(raw)
        if (m == null) {
            diags += diag(lineNo, rawLine, "UNCONVERTIBLE_SIZE",
                "大小 '$raw' 无法规范化为字节；原值保留在 sizeBytesRaw", fatal = false)
            return null
        }
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].uppercase()) {
            "KB" -> n * 1024
            "MB" -> n * 1024 * 1024
            else -> if (declaredUnit == "byte") n else {
                diags += diag(lineNo, rawLine, "UNIT_MISMATCH",
                    "声明单位 '$declaredUnit' 与裸数字并存，按字节规范化", fatal = false)
                n
            }
        }
    }

    private fun parseKv(body: String): Map<String, String> =
        body.split(Regex("\\s+")).mapNotNull { tok ->
            val i = tok.indexOf('=')
            if (i <= 0) null else tok.substring(0, i) to tok.substring(i + 1)
        }.toMap()

    private fun diag(line: Int?, raw: String, code: String, msg: String, fatal: Boolean,
                     offset: Long? = null) =
        Diagnostic(line, null, offset, code, msg, raw.take(120), fatal)

    private fun mapRootKind(raw: String): RootKind = when (raw.uppercase()) {
        "JNI_GLOBAL", "GLOBAL" -> RootKind.JNI_GLOBAL
        "JNI_LOCAL", "LOCAL" -> RootKind.JNI_LOCAL
        "JAVA_FRAME" -> RootKind.JAVA_FRAME
        "NATIVE_STACK" -> RootKind.NATIVE_STACK
        "STICKY_CLASS", "SYSTEM_CLASS" -> RootKind.STICKY_CLASS
        "THREAD" -> RootKind.THREAD_OBJ
        "MONITOR" -> RootKind.MONITOR_USED
        "INTERNED_STRING" -> RootKind.INTERNED_STRING
        "FINALIZING" -> RootKind.FINALIZING
        else -> RootKind.UNKNOWN
    }
}
