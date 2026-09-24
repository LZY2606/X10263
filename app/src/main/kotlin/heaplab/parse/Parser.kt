package heaplab.parse

import heaplab.model.*

/**
 * HSNAP v1 行式堆快照解析器。
 *
 * 设计约束（见案例验收）：
 *  - 逐字段保留原始表示；规范化只在 SizeValue/AddressValue 内进行；
 *  - 无法转换的值留在原位置并附 ParseDiagnostic，不抛空、不用默认值伪装；
 *  - 每个诊断带 line/col/endCol（基于 1 的行列，col 为 0 基字节列可接受，这里统一 1 基）；
 *  - 资源预算耗尽、格式损坏、语义无解在 AnalysisEngine 中区分；解析层区分
 *    CORRUPT_FORMAT（结构损坏）/ UNCONVERTIBLE_VALUE（值无法规范化）/ DANGLING_REF（语义无解）。
 */
class HSnapParser(private val parserVersion: String = "hslab-parser/1.0.0") {

    companion object {
        const val FORMAT = "HSNAP"
    }

    class Budget(val maxBytes: Int, val maxLines: Int) {
        var bytesUsed = 0
        var linesUsed = 0
        var exhausted = false
        fun charge(line: String) {
            bytesUsed += line.length + 1
            linesUsed += 1
            if (bytesUsed > maxBytes || linesUsed > maxLines) exhausted = true
        }
    }

    fun parse(
        text: String,
        snapshotId: String,
        sourceName: String,
        importedAt: Long,
        budget: Budget = Budget(64 * 1024 * 1024, 2_000_000)
    ): RawSnapshot {
        val diagnostics = mutableListOf<ParseDiagnostic>()
        var nextDiag = 0
        fun diag(sev: String, code: String, line: Int, col: Int, endCol: Int,
                 msg: String, raw: String, invalid: String? = null): String {
            val id = "$snapshotId#d${nextDiag++}"
            diagnostics += ParseDiagnostic(id, sev, code, line, col, endCol, msg, raw, invalid)
            return id
        }

        val lines = text.split("\n")
        var index = 0
        var formatVersion = "unknown"
        val classes = LinkedHashMap<String, RawClass>()
        val objects = LinkedHashMap<String, RawObject>()
        val edges = mutableListOf<RawEdge>()
        val roots = mutableListOf<RawRoot>()
        val sites = LinkedHashMap<String, AllocationSite>()
        val incompletes = mutableListOf<IncompleteBlock>()
        var nextInc = 0

        var budgetExceededLine = -1

        while (index < lines.size) {
            val line = lines[index]
            budget.charge(line)
            if (budget.exhausted && budgetExceededLine < 0) budgetExceededLine = index + 1
            val lineNo = index + 1
            val trimmed = line.trim()
            index++
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            when {
                trimmed.startsWith("HSNAP ") -> {
                    val rest = trimmed.substring(5)
                    val m = Regex("v(\\S+)").find(rest)
                    if (m == null) {
                        diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, trimmed.length,
                            "HSNAP 头缺少版本号", line)
                    } else formatVersion = m.groupValues[1]
                }
                trimmed.startsWith("CLASS ") -> parseClass(trimmed, lineNo, line, classes, ::diag)
                trimmed.startsWith("SITE ") -> parseSite(trimmed, lineNo, line, sites, ::diag)
                trimmed.startsWith("OBJECT ") -> {
                    val block = collectBlock(lines, index - 1, lineNo, line)
                    index = block.nextIndex
                    parseObject(block, objects, sites, incompletes, { nextInc++ }, ::diag)
                }
                trimmed.startsWith("EDGE ") -> parseEdge(trimmed, lineNo, line, edges, ::diag)
                trimmed.startsWith("ROOT ") -> parseRoot(trimmed, lineNo, line, roots, ::diag)
                else -> diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, trimmed.length,
                    "无法识别的记录起始字", line, trimmed.takeWhile { !it.isWhitespace() })
            }
        }

        if (budgetExceededLine > 0) {
            diag("ERROR", "BUDGET_EXHAUSTED", budgetExceededLine, 1, 1,
                "解析资源预算耗尽，输入可能被截断", "")
        }

        resolveRefs(objects, edges, roots, classes, sites, ::diag)
        val dup = findDuplicateAddresses(objects)

        return RawSnapshot(
            snapshotId = snapshotId,
            sourceName = sourceName,
            formatVersion = formatVersion,
            parserVersion = parserVersion,
            importedAtEpoch = importedAt,
            objects = objects,
            classes = classes,
            edges = edges,
            roots = roots,
            allocationSites = sites,
            incompletes = incompletes,
            diagnostics = diagnostics.sortedWith(compareBy({ it.line }, { it.col })),
            duplicateAddresses = dup
        )
    }

    private class Block(
        val startLine: Int, val lines: List<String>, val nextIndex: Int, val complete: Boolean
    )

    private fun collectBlock(all: List<String>, startIndex: Int, startLine: Int, first: String): Block {
        val collected = mutableListOf(first)
        var i = startIndex + 1
        var terminated = false
        while (i < all.size) {
            val l = all[i]
            collected += l
            i++
            if (l.trim() == "END") { terminated = true; break }
        }
        return Block(startLine, collected, i, terminated)
    }

    private fun parseClass(
        t: String, lineNo: Int, raw: String,
        classes: LinkedHashMap<String, RawClass>,
        diag: Diag
    ) {
        // CLASS id=.. name=".." [super=..] [loader=..]
        val id = field(t, "id")
        if (id == null) {
            diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, t.length, "CLASS 缺少 id", raw); return
        }
        val namePair = quotedField(t, "name")
        if (namePair == null) {
            diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, t.length, "CLASS 缺少 name", raw); return
        }
        val (nameRaw, name) = namePair
        if (classes.containsKey(id)) {
            diag("ERROR", "DUP_CLASS_ID", lineNo, 1, t.length, "类 id 重复定义", raw, id)
        }
        classes[id] = RawClass(id, name, nameRaw, field(t, "super"), field(t, "loader"), lineNo)
    }

    private fun parseSite(
        t: String, lineNo: Int, raw: String,
        sites: LinkedHashMap<String, AllocationSite>,
        diag: Diag
    ) {
        val id = field(t, "id") ?: run {
            diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, t.length, "SITE 缺少 id", raw); return
        }
        val loc = quotedField(t, "at")?.second ?: ""
        val epochRaw = field(t, "epoch")
        var epoch: Long? = null
        if (epochRaw != null) {
            epoch = epochRaw.toLongOrNull()
            if (epoch == null) {
                val col = t.indexOf("epoch=")
                diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col + 1, col + 1 + epochRaw.length,
                    "epoch 无法规范化为纪元毫秒，保留原值", raw, epochRaw)
            }
        }
        sites[id] = AllocationSite(id, field(t, "class"), loc, epochRaw, epoch, lineNo)
    }

    private fun parseObject(
        block: Block,
        objects: LinkedHashMap<String, RawObject>,
        sites: Map<String, AllocationSite>,
        incompletes: MutableList<IncompleteBlock>,
        nextInc: () -> Int,
        diag: Diag
    ) {
        val first = block.lines.first()
        val t = first.trim()
        val startLine = block.startLine
        val endLine = startLine + block.lines.size - 1
        val rawText = block.lines.joinToString("\n")
        val id = field(t, "id")
        if (id == null) {
            diag("ERROR", "CORRUPT_FORMAT", startLine, 1, t.length, "OBJECT 缺少 id", first)
            if (!block.complete) recordIncomplete(incompletes, nextInc, startLine, endLine, rawText,
                "对象块缺少 id 且被截断")
            return
        }
        val complete = block.complete
        if (!complete) {
            recordIncomplete(incompletes, nextInc, startLine, endLine, rawText,
                "对象块在 END 之前结束（截断）")
        }
        val addrRaw = field(t, "addr")
        val address = if (addrRaw == null) {
            diag("ERROR", "CORRUPT_FORMAT", startLine, 1, t.length, "OBJECT 缺少 addr", first)
            AddressValue("", null, "unknown", null)
        } else parseAddress(addrRaw, startLine, first, diag)

        val classId = field(t, "class") ?: "".also {
            diag("WARN", "UNCONVERTIBLE_VALUE", startLine, 1, t.length,
                "OBJECT 缺少 class 元数据引用", first)
        }
        val sizeRaw = field(t, "size")
        val size = if (sizeRaw == null) {
            diag("WARN", "UNCONVERTIBLE_VALUE", startLine, 1, t.length,
                "OBJECT 缺少 size，保留空原始表示", first)
            SizeValue("", null, "", false, null)
        } else parseSize(sizeRaw, startLine, first, diag)

        var allocSite: String? = field(t, "site")
        if (allocSite == null) {
            for (l in block.lines.drop(1)) if (l.trim().startsWith("site=")) {
                allocSite = l.trim().removePrefix("site=").trim()
            }
        }

        if (objects.containsKey(id)) {
            diag("ERROR", "DUP_OBJECT_ID", startLine, 1, t.length,
                "原始对象 id 重复定义", rawText, id)
        }
        objects[id] = RawObject(id, address, classId, size, allocSite, complete,
            startLine, endLine, rawText)
    }

    private fun recordIncomplete(
        incompletes: MutableList<IncompleteBlock>, nextInc: () -> Int,
        startLine: Int, endLine: Int, rawText: String, reason: String
    ) {
        val bid = "inc${nextInc()}"
        incompletes += IncompleteBlock(bid, "OBJECT", startLine, endLine, reason, rawText)
    }

    private fun parseEdge(t: String, lineNo: Int, raw: String,
                          edges: MutableList<RawEdge>, diag: Diag) {
        val from = field(t, "from")
        val to = field(t, "to")
        if (from == null || to == null) {
            diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, t.length,
                "EDGE 必须同时提供 from 与 to", raw)
            return
        }
        val strengthRaw = (field(t, "strength") ?: "strong").lowercase()
        val strength = when (strengthRaw) {
            "strong" -> EdgeStrength.STRONG
            "weak" -> EdgeStrength.WEAK
            "internal" -> EdgeStrength.INTERNAL
            else -> {
                val col = t.indexOf("strength=")
                diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col + 1, col + 1 + strengthRaw.length,
                    "未知边强度，按 strong 处理但保留原值", raw, strengthRaw)
                EdgeStrength.STRONG
            }
        }
        val labelPair = quotedField(t, "label")
        val labelRaw = labelPair?.first ?: ""
        val label = labelPair?.second ?: labelRaw
        val uid = "e${edges.size}"
        edges += RawEdge(uid, from, to, strength, label, labelRaw, lineNo, true, null)
    }

    private fun parseRoot(t: String, lineNo: Int, raw: String,
                          roots: MutableList<RawRoot>, diag: Diag) {
        val obj = field(t, "id")
        if (obj == null) {
            diag("ERROR", "CORRUPT_FORMAT", lineNo, 1, t.length, "ROOT 缺少 id", raw)
            return
        }
        val catRaw = field(t, "kind") ?: ""
        val cat = RootClassifier.normalize(catRaw)
        if (catRaw.isNotEmpty() && cat == RootClassifier.UNKNOWN) {
            val col = t.indexOf("kind=")
            diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col + 1, col + 1 + catRaw.length,
                "未知 root 分类，归一为 UNKNOWN 并保留原值", raw, catRaw)
        }
        val labelPair = quotedField(t, "label")
        val uid = "r${roots.size}"
        roots += RawRoot(uid, obj, cat, catRaw, labelPair?.second ?: "", lineNo, true, null)
    }

    private fun parseAddress(raw: String, lineNo: Int, line: String, diag: Diag): AddressValue {
        val col = line.indexOf(raw) + 1
        val r = raw.trim()
        val (value, base) = when {
            r.startsWith("0x") || r.startsWith("0X") ->
                r.substring(2).toLongOrNull(16)?.let { it to "hex16" } ?: (null to "hex16")
            r.startsWith("#hex:") ->
                r.substring(5).toLongOrNull(16)?.let { it to "hex16" } ?: (null to "hex16")
            r.all { it.isDigit() } -> r.toLongOrNull()?.let { it to "dec" } ?: (null to "dec")
            else -> null to "unknown"
        }
        if (value == null) {
            val did = diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col, col + r.length,
                "地址无法规范化，保留原值", line, r)
            return AddressValue(r, null, base, did)
        }
        return AddressValue(r, value, base, null)
    }

    private fun parseSize(raw: String, lineNo: Int, line: String, diag: Diag): SizeValue {
        val col = line.indexOf(raw) + 1
        val r = raw.trim()
        val m = Regex("^([0-9]*\\.?[0-9]+)\\s*([KMG]?i?B|B|b)?$").matchEntire(r)
        if (m == null) {
            val did = diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col, col + r.length,
                "size 无法规范化为字节，保留原值", line, r)
            return SizeValue(r, null, "", false, did)
        }
        val num = m.groupValues[1].toBigDecimalOrNull()
        val unit = m.groupValues[2].ifEmpty { "B" }
        val mult = when (unit.uppercase()) {
            "B" -> 1L
            "KIB" -> 1024L; "MIB" -> 1024L * 1024; "GIB" -> 1024L * 1024 * 1024
            "KB" -> 1000L; "MB" -> 1000L * 1000; "GB" -> 1000L * 1000 * 1000
            else -> null
        }
        if (num == null || mult == null) {
            val did = diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col, col + r.length,
                "size 单位无法识别，保留原值", line, r)
            return SizeValue(r, null, unit, false, did)
        }
        val bytes = try {
            num.multiply(mult.toBigDecimal()).longValueExact()
        } catch (e: ArithmeticException) {
            val did = diag("WARN", "UNCONVERTIBLE_VALUE", lineNo, col, col + r.length,
                "size 含小数字节，无法无损规范化，保留原值", line, r)
            return SizeValue(r, null, unit, false, did)
        }
        return SizeValue(r, bytes, unit.uppercase(), true, null)
    }

    private fun resolveRefs(
        objects: Map<String, RawObject>,
        edges: MutableList<RawEdge>,
        roots: MutableList<RawRoot>,
        classes: Map<String, RawClass>,
        sites: Map<String, AllocationSite>,
        diag: Diag
    ) {
        for (i in edges.indices) {
            val e = edges[i]
            if (e.fromId !in objects || e.toId !in objects) {
                val missing = if (e.fromId !in objects) e.fromId else e.toId
                val did = diag("ERROR", "DANGLING_REF", e.line, 1, 1,
                    "边引用了不存在的对象 id（语义无解）", e.line.toString(), missing)
                edges[i] = e.copy(resolved = false, diagnosticId = did)
            }
        }
        for (i in roots.indices) {
            val rt = roots[i]
            if (rt.objId !in objects) {
                val did = diag("ERROR", "DANGLING_REF", rt.line, 1, 1,
                    "root 引用了不存在的对象 id（语义无解）", rt.line.toString(), rt.objId)
                roots[i] = rt.copy(resolved = false, diagnosticId = did)
            }
        }
        for (o in objects.values) {
            if (o.classIdRaw.isNotEmpty() && o.classIdRaw !in classes) {
                diag("WARN", "DANGLING_REF", o.startLine, 1, 1,
                    "对象引用了未知类元数据 id", o.rawText, o.classIdRaw)
            }
            if (o.allocSiteId != null && o.allocSiteId !in sites) {
                diag("WARN", "DANGLING_REF", o.startLine, 1, 1,
                    "对象引用了未知 allocation site", o.rawText, o.allocSiteId)
            }
        }
    }

    private fun findDuplicateAddresses(objects: Map<String, RawObject>): Map<String, List<String>> {
        val byAddr = LinkedHashMap<String, MutableList<String>>()
        for (o in objects.values) {
            val key = o.address.value?.toString() ?: continue
            byAddr.getOrPut(key) { mutableListOf() }.add(o.objId)
        }
        return byAddr.filterValues { it.size > 1 }
    }
}

typealias Diag = (String, String, Int, Int, Int, String, String, String?) -> String

/** 从 `key=value`（value 不带空格/引号）中取原始值。 */
internal fun field(line: String, key: String): String? {
    val regex = Regex("""(?:^|\s)${Regex.escape(key)}=([^\s"]+|"(?:[^"\\]|\\.)*")""")
    val m = regex.find(line) ?: return null
    var v = m.groupValues[1]
    if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
    return v
}

/** 取引号字段，返回 (raw 含引号, 解码值)。 */
internal fun quotedField(line: String, key: String): Pair<String, String>? {
    val regex = Regex("""(?:^|\s)${Regex.escape(key)}="((?:[^"\\]|\\.)*)"""")
    val m = regex.find(line) ?: return null
    val raw = m.groupValues[0].trim().removePrefix("$key=")
    val decoded = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    return raw to decoded
}
