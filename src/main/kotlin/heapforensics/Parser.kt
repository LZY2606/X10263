package heapforensics

import kotlinx.serialization.json.*

/** 导入结果：格式损坏 / 语义无解 / 成功（可带警告诊断）是三种不同结果。 */
sealed interface ImportResult {
    data class Ok(val snapshot: HeapSnapshot, val digest: String, val diagnostics: List<Diagnostic>) : ImportResult
    data class Corrupt(val diagnostics: List<Diagnostic>) : ImportResult
    data class Unsatisfiable(val diagnostics: List<Diagnostic>) : ImportResult
}

object HeapParser {
    const val PARSER_VERSION = "heapfmt-parser/1.2.0"
    const val FORMAT = "heapfmt"

    private class Cursor(val el: JsonElement?, val path: String) {
        fun asObj(): JsonObject? = el as? JsonObject
        fun field(name: String) = Cursor(asObj()?.get(name), "$path.$name")
        fun str(): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun long(): Long? = (el as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        fun bool(): Boolean? = (el as? JsonPrimitive)?.contentOrNull?.let {
            when (it) { "true" -> true; "false" -> false; else -> null }
        }
        fun arr(): List<JsonElement>? = (el as? JsonArray)
    }

    fun parse(text: String): ImportResult {
        val rootEl: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            val msg = e.message ?: "JSON 解析失败"
            val offset = Regex("offset (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            val (line, col) = if (offset != null) lineCol(text, offset) else (null to null)
            return ImportResult.Corrupt(listOf(Diagnostic("$", "格式损坏：$msg", line = line, column = col)))
        }
        val diags = mutableListOf<Diagnostic>()
        val root = Cursor(rootEl, "$")
        val obj = root.asObj() ?: return ImportResult.Corrupt(listOf(Diagnostic("$", "格式损坏：顶层必须是 JSON 对象")))

        val format = root.field("format").str()
        if (format != FORMAT) {
            return ImportResult.Corrupt(listOf(Diagnostic("$.format", "格式损坏：format 应为 \"$FORMAT\"，实际为 ${format ?: "缺失"}")))
        }

        fun reqStr(c: Cursor, name: String): String? {
            val v = c.field(name).str()
            if (v == null) diags += Diagnostic("${c.path}.$name", "缺失或类型错误：需要字符串")
            return v
        }

        val snapshotId = reqStr(root, "snapshotId")
        val parserVersion = root.field("parserVersion").str() ?: "unknown"

        val classes = mutableListOf<ClassMeta>()
        root.field("classes").arr()?.forEachIndexed { i, el ->
            val c = Cursor(el, "$.classes[$i]")
            val id = reqStr(c, "id"); val name = reqStr(c, "name")
            if (id != null && name != null) classes += ClassMeta(id, name, c.field("superId").str())
        }
        val allocSites = mutableListOf<AllocSite>()
        root.field("allocSites").arr()?.forEachIndexed { i, el ->
            val c = Cursor(el, "$.allocSites[$i]")
            val id = reqStr(c, "id"); val cn = reqStr(c, "className"); val m = reqStr(c, "method")
            if (id != null && cn != null && m != null) allocSites += AllocSite(id, cn, m, c.field("line").long()?.toInt())
        }
        val objects = mutableListOf<HeapObject>()
        root.field("objects").arr()?.forEachIndexed { i, el ->
            val c = Cursor(el, "$.objects[$i]")
            val id = reqStr(c, "id")
            val addrRaw = c.field("address").str()
            if (addrRaw == null) {
                diags += Diagnostic("${c.path}.address", "缺失地址；对象保留在原位置", severity = "warning")
            } else if (!addrRaw.matches(Regex("0x[0-9a-fA-F]+"))) {
                // 无法转换的值留在原位置并附诊断，不为画图丢掉
                diags += Diagnostic("${c.path}.address", "地址无法按十六进制转换：\"$addrRaw\"，原始值保留", severity = "warning")
            }
            val sizeEl = c.field("sizeBytes")
            val size = sizeEl.long()
            if (sizeEl.el != null && size == null) {
                diags += Diagnostic("${c.path}.sizeBytes", "sizeBytes 无法转换为整数（单位：字节），原始值保留", severity = "warning")
            }
            if (size != null && size < 0) {
                diags += Diagnostic("${c.path}.sizeBytes", "sizeBytes 为负数（$size B），语义无效")
            }
            val incomplete = c.field("incomplete").bool() ?: (size == null || c.field("classId").str() == null)
            if (id != null) objects += HeapObject(
                id = id,
                address = addrRaw ?: "<missing>",
                classId = c.field("classId").str(),
                sizeBytes = size,
                allocSiteId = c.field("allocSiteId").str(),
                incomplete = incomplete,
            )
        }
        val edges = mutableListOf<HeapEdge>()
        root.field("edges").arr()?.forEachIndexed { i, el ->
            val c = Cursor(el, "$.edges[$i]")
            val from = reqStr(c, "from"); val to = reqStr(c, "to")
            val kindRaw = c.field("kind").str()
            val kind = kindRaw?.let { runCatching { EdgeKind.valueOf(it.uppercase()) }.getOrNull() }
            if (kindRaw != null && kind == null) diags += Diagnostic("${c.path}.kind", "未知边类型 \"$kindRaw\"（应为 STRONG/WEAK/INTERNAL）")
            if (from != null && to != null && kind != null) edges += HeapEdge(from, to, kind, c.field("name").str())
        }
        val roots = mutableListOf<HeapRoot>()
        root.field("roots").arr()?.forEachIndexed { i, el ->
            val c = Cursor(el, "$.roots[$i]")
            val id = reqStr(c, "id"); val oid = reqStr(c, "objectId")
            val kindRaw = c.field("kind").str()
            val kind = kindRaw?.let { runCatching { RootKind.valueOf(it.uppercase()) }.getOrNull() } ?: RootKind.OTHER
            if (id != null && oid != null) roots += HeapRoot(id, oid, kind)
        }
        val incompleteBlocks = root.field("incompleteBlocks").arr()
            ?.mapIndexedNotNull { i, el -> Cursor(el, "$.incompleteBlocks[$i]").str() } ?: emptyList()

        if (snapshotId == null) return ImportResult.Corrupt(diags)

        // ---- 语义校验：失败即“语义无解”，与格式损坏区分 ----
        val semantic = mutableListOf<Diagnostic>()
        val ids = objects.map { it.id }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (id, n) ->
            semantic += Diagnostic("$.objects", "语义无解：对象 id \"$id\" 重复 $n 次，无法建立唯一身份")
        }
        val idSet = ids.toSet()
        edges.forEachIndexed { i, e ->
            if (e.from !in idSet) semantic += Diagnostic("$.edges[$i].from", "语义无解：边起点 \"${e.from}\" 不存在于对象表")
            if (e.to !in idSet) semantic += Diagnostic("$.edges[$i].to", "语义无解：边终点 \"${e.to}\" 不存在于对象表")
        }
        roots.forEachIndexed { i, r ->
            if (r.objectId !in idSet) semantic += Diagnostic("$.roots[$i].objectId", "语义无解：root \"${r.id}\" 指向不存在的对象 \"${r.objectId}\"")
        }
        if (semantic.isNotEmpty()) return ImportResult.Unsatisfiable(diags + semantic)

        val snap = HeapSnapshot(snapshotId, parserVersion, classes, allocSites, objects, edges, roots, incompleteBlocks)
        return ImportResult.Ok(snap, canonicalDigest(snap), diags)
    }

    private fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        var line = 1; var col = 1
        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') { line++; col = 1 } else col++
        }
        return line to col
    }
}
