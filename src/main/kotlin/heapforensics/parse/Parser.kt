package heapforensics.parse

import heapforensics.model.*
import kotlinx.serialization.json.*

/** 快照解析器：保留原始表示，派生层才规范化；无法转换的值留在原位置并附诊断。 */
object Parser {

    data class ParseResult(
        val snapshot: Snapshot?,
        val diagnostics: List<Diagnostic>,
        val fatal: FailureKind? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ParseResult {
        val root: JsonObject = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return ParseResult(null, listOf(Diagnostic("$", "FORMAT_CORRUPT",
                "JSON 解析失败: ${e.message}")), FailureKind.FORMAT_CORRUPT)
        }
        val diags = mutableListOf<Diagnostic>()
        val format = root.str("format") ?: run {
            diags += Diagnostic("format", "MISSING_FIELD", "缺少 format 字段"); "unknown"
        }
        if (format != "heap-snapshot/1") {
            return ParseResult(null, diags + Diagnostic("format", "FORMAT_CORRUPT",
                "不支持的格式 '$format'，期望 heap-snapshot/1"), FailureKind.FORMAT_CORRUPT)
        }
        val parserVersion = root.str("parserVersion") ?: run {
            diags += Diagnostic("parserVersion", "MISSING_FIELD", "缺少解析器版本"); "unknown"
        }
        val snapshotId = root.str("snapshotId") ?: run {
            diags += Diagnostic("snapshotId", "MISSING_FIELD", "缺少 snapshotId"); "unknown"
        }

        val classes = root.arr("classes").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.str("id") ?: run { diags += Diagnostic("classes[$i].id", "MISSING_FIELD", "类缺少 id"); return@mapIndexedNotNull null }
            val name = o.str("name") ?: run { diags += Diagnostic("classes[$i].name", "MISSING_FIELD", "类缺少 name"); return@mapIndexedNotNull null }
            ClassInfo(id, name, o.str("superId"))
        }

        val objects = root.arr("objects").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val pos = "objects[$i]"
            val id = o.str("id") ?: run { diags += Diagnostic("$pos.id", "MISSING_FIELD", "对象缺少 id"); return@mapIndexedNotNull null }
            val address = o.str("address") ?: run { diags += Diagnostic("$pos.address", "MISSING_FIELD", "对象缺少地址"); "?" }
            val classId = o.str("classId") ?: run { diags += Diagnostic("$pos.classId", "MISSING_FIELD", "对象缺少 classId"); "?" }
            val sizeEl = o["selfSize"]
            val selfSize = when (sizeEl) {
                null -> { diags += Diagnostic("$pos.selfSize", "MISSING_FIELD", "缺少 selfSize，不做默认值伪装"); Quantity("?", "bytes", null) }
                is JsonPrimitive -> {
                    val rawText = sizeEl.content
                    val v = parseSize(rawText)
                    if (v == null) diags += Diagnostic("$pos.selfSize", "UNIT_UNKNOWN",
                        "无法将 '$rawText' 规范化为 bytes，原值保留在原地")
                    Quantity(rawText, "bytes", v)
                }
                else -> { diags += Diagnostic("$pos.selfSize", "TYPE_ERROR", "selfSize 必须是数字或字符串"); Quantity("?", "bytes", null) }
            }
            val site = (o["allocSite"] as? JsonObject)?.let { s ->
                AllocSite(s.str("file") ?: "?", s["line"]?.jsonPrimitive?.intOrNull ?: -1, s.str("function") ?: "?")
            }
            HeapObject(id, address, classId, selfSize, site, o["incomplete"]?.jsonPrimitive?.booleanOrNull ?: false)
        }

        val edges = root.arr("edges").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val pos = "edges[$i]"
            val from = o.str("from") ?: run { diags += Diagnostic("$pos.from", "MISSING_FIELD", "边缺少 from"); return@mapIndexedNotNull null }
            val to = o.str("to") ?: run { diags += Diagnostic("$pos.to", "MISSING_FIELD", "边缺少 to"); return@mapIndexedNotNull null }
            val kind = when (o.str("kind")?.uppercase()) {
                "STRONG" -> EdgeKind.STRONG
                "WEAK" -> EdgeKind.WEAK
                "INTERNAL" -> EdgeKind.INTERNAL
                else -> { diags += Diagnostic("$pos.kind", "ENUM_UNKNOWN", "未知边类型"); return@mapIndexedNotNull null }
            }
            Edge(from, to, kind, o.str("name") ?: "")
        }

        val roots = root.arr("roots").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val pos = "roots[$i]"
            val oid = o.str("objectId") ?: run { diags += Diagnostic("$pos.objectId", "MISSING_FIELD", "root 缺少 objectId"); return@mapIndexedNotNull null }
            val rt = o.str("rootType")?.let { runCatching { RootType.valueOf(it.uppercase()) }.getOrNull() }
                ?: run { diags += Diagnostic("$pos.rootType", "ENUM_UNKNOWN", "未知 root 类型"); RootType.STACK }
            RootRef(oid, rt)
        }

        val chunks = root.arr("chunks").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            Chunk(o.str("id") ?: "chunk-$i", o.str("status") ?: "complete",
                o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L, o.str("reason") ?: "")
        }

        return ParseResult(Snapshot(format, parserVersion, snapshotId, classes, objects, edges, roots, chunks), diags)
    }

    /** 规范化尺寸：支持 64、"64"、"64B"、"0x40"、"1K"。失败返回 null，原值留在原地。 */
    fun parseSize(raw: String): Long? {
        val t = raw.trim()
        t.toLongOrNull()?.let { return it }
        if (t.startsWith("0x")) return t.drop(2).toLongOrNull(16)
        val m = Regex("""^(\d+)([BKMG])?$""").matchEntire(t.uppercase()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2]) {
            "", "B" -> n; "K" -> n * 1024; "M" -> n * 1024 * 1024; "G" -> n * 1024L * 1024 * 1024
            else -> null
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray)?.toList() ?: emptyList()
}
