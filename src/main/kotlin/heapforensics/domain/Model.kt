package heapforensics.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 当前解析器版本；导入时若快照未声明则以此记录。 */
const val CURRENT_PARSER_VERSION = "hs-parser/1.2.0"

@Serializable
enum class Severity { INFO, WARNING, ERROR }

/** 带位置的诊断：不完整的输入不允许用空值/默认值伪装成功。 */
@Serializable
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val position: String
) {
    fun render(): String = "[${severity} $code] $position: $message"
}

// ---------- 不可变原始材料层：导入时保留原始表示 ----------

@Serializable
data class RawClass(
    val id: String,
    val name: String,
    val superId: String? = null,
    val loader: String? = null
)

@Serializable
data class AllocSite(
    val file: String? = null,
    val line: Int? = null,
    val function: String? = null
) {
    fun render(): String = listOfNotNull(file, line?.toString(), function).joinToString(":")
}

@Serializable
data class RawObject(
    val id: String,
    val address: String,
    val classId: String,
    val sizeRaw: String,
    val allocSite: AllocSite? = null
)

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class RawEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val name: String? = null
)

@Serializable
enum class RootKind { JNI_GLOBAL, THREAD, SYSTEM_CLASS, MONITOR, OTHER }

@Serializable
data class RawRoot(
    val objectId: String,
    val kind: RootKind
)

@Serializable
data class IncompleteBlock(
    val offset: String,
    val lengthRaw: String,
    val reason: String
)

@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val capturedAt: String,
    val parserVersion: String,
    val sourceName: String,
    val classes: List<RawClass>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val incompleteBlocks: List<IncompleteBlock>
)

// ---------- 派生层：单位规范化（原始表示不动） ----------

private val SIZE_RE = Regex("""^\s*(\d+(?:\.\d+)?)\s*([A-Za-z]*)\s*$""")

/** 解析带单位的大小。无法转换时返回 null 字节数并附诊断，原值留在原位置。 */
fun parseSize(raw: String, position: String): Pair<Long?, Diagnostic?> {
    val m = SIZE_RE.matchEntire(raw) ?: return null to Diagnostic(
        Severity.ERROR, "size.unparsable", "无法解析的大小表示，原值保留: '$raw'", position
    )
    val value = m.groupValues[1].toDouble()
    return when (val unit = m.groupValues[2]) {
        "B" -> value.toLong() to null
        "KiB" -> (value * 1024).toLong() to null
        "MiB" -> (value * 1024 * 1024).toLong() to null
        "GiB" -> (value * 1024 * 1024 * 1024).toLong() to null
        "KB" -> (value * 1000).toLong() to null
        "MB" -> (value * 1000 * 1000).toLong() to null
        "GB" -> (value * 1000 * 1000 * 1000).toLong() to null
        "" -> value.toLong() to Diagnostic(
            Severity.WARNING, "size.unit.missing", "缺少单位，按字节(B)解释: '$raw'", position
        )
        else -> null to Diagnostic(
            Severity.ERROR, "size.unit.unknown", "未知单位 '$unit'，原值保留: '$raw'", position
        )
    }
}

fun humanSize(bytes: Long): String = when {
    bytes % (1024 * 1024) == 0L && bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    bytes % 1024 == 0L && bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

// ---------- 稳定身份：等价输入得到相同摘要与稳定顺序 ----------

/** 规范化视图：集合按稳定键排序，等价输入（顺序不同）得到相同摘要。 */
fun canonicalJson(s: RawSnapshot): JsonObject = buildJsonObject {
    put("snapshotId", s.snapshotId)
    put("capturedAt", s.capturedAt)
    put("parserVersion", s.parserVersion)
    put("classes", buildJsonArray {
        s.classes.sortedBy { it.id }.forEach { c ->
            add(buildJsonObject {
                put("id", c.id); put("name", c.name)
                c.superId?.let { put("superId", it) }; c.loader?.let { put("loader", it) }
            })
        }
    })
    put("objects", buildJsonArray {
        s.objects.sortedBy { it.id }.forEach { o ->
            add(buildJsonObject {
                put("id", o.id); put("address", o.address); put("classId", o.classId)
                put("sizeRaw", o.sizeRaw)
                o.allocSite?.let { a ->
                    put("allocSite", buildJsonObject {
                        a.file?.let { put("file", it) }; a.line?.let { put("line", it) }
                        a.function?.let { put("function", it) }
                    })
                }
            })
        }
    })
    put("edges", buildJsonArray {
        s.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }, { it.name ?: "" })).forEach { e ->
            add(buildJsonObject {
                put("from", e.from); put("to", e.to); put("kind", e.kind.name)
                e.name?.let { put("name", it) }
            })
        }
    })
    put("roots", buildJsonArray {
        s.roots.sortedWith(compareBy({ it.objectId }, { it.kind.name })).forEach { r ->
            add(buildJsonObject { put("objectId", r.objectId); put("kind", r.kind.name) })
        }
    })
    put("incompleteBlocks", buildJsonArray {
        s.incompleteBlocks.sortedBy { it.offset }.forEach { b ->
            add(buildJsonObject {
                put("offset", b.offset); put("lengthRaw", b.lengthRaw); put("reason", b.reason)
            })
        }
    })
}

/** 快照内容摘要：对象 id 是稳定身份，地址不参与身份。 */
fun digestOf(s: RawSnapshot): String = sha256(canonicalJson(s).toString())
