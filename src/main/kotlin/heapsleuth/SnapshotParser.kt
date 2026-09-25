package heapsleuth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

val CanonicalJson = Json {
    prettyPrint = false
    explicitNulls = false
    encodeDefaults = true
}

fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun bundleDigest(bundle: SnapshotBundle): String =
    sha256Hex(CanonicalJson.encodeToString(SnapshotBundle.serializer(), bundle.canonical()))

/**
 * 解析堆快照 JSON。导入时保留原始表示（sizeRaw / addressRaw），
 * 派生层（normalizeSize）才做单位规范化；无法转换的值留在原位置并附诊断。
 */
object SnapshotParser {

    fun parse(jsonText: String): ParseOutcome {
        val root: JsonObject = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            return ParseOutcome.Corrupt(
                listOf(Diagnostic("/", "FORMAT_CORRUPT", "JSON 解析失败: ${e.message}", Severity.ERROR))
            )
        }
        val diags = mutableListOf<Diagnostic>()
        fun err(path: String, code: String, msg: String) {
            diags += Diagnostic(path, code, msg, Severity.ERROR)
        }
        fun warn(path: String, code: String, msg: String) {
            diags += Diagnostic(path, code, msg, Severity.WARNING)
        }

        val snapshotId = reqString(root, "snapshotId", "/snapshotId", diags)
        val name = optString(root, "name") ?: snapshotId ?: "?"
        val parserVersion = reqString(root, "parserVersion", "/parserVersion", diags)
        if (snapshotId == null || parserVersion == null) {
            return ParseOutcome.Corrupt(diags)
        }

        val classes = mutableListOf<ClassMeta>()
        arr(root, "classes")?.forEachIndexed { i, el ->
            val path = "/classes/$i"
            val o = el as? JsonObject ?: return@forEachIndexed err(path, "BAD_TYPE", "class 条目必须是对象")
            val id = optString(o, "classId")
            val nm = optString(o, "name")
            if (id == null || nm == null) err(path, "MISSING_FIELD", "class 缺少 classId/name")
            else classes += ClassMeta(
                classId = id,
                name = nm,
                superName = optString(o, "superName"),
                metadata = (o["metadata"] as? JsonObject)?.entries
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap()
                    ?: emptyMap(),
            )
        }

        val objects = mutableListOf<HeapObject>()
        arr(root, "objects")?.forEachIndexed { i, el ->
            val path = "/objects/$i"
            val o = el as? JsonObject ?: return@forEachIndexed err(path, "BAD_TYPE", "object 条目必须是对象")
            val id = optString(o, "objectId")
            val address = optString(o, "address")
            val classId = optString(o, "classId")
            val sizeRaw = optString(o, "size")
            if (id == null || address == null || classId == null || sizeRaw == null) {
                err(path, "MISSING_FIELD", "object 缺少 objectId/address/classId/size")
                return@forEachIndexed
            }
            val sizeBytes = normalizeSize(sizeRaw)
            if (sizeBytes == null) {
                warn("$path/size", "SIZE_UNPARSEABLE", "大小 '$sizeRaw' 无法规范化为 byte，原值保留在 sizeRaw")
            }
            objects += HeapObject(
                objectId = id,
                addressRaw = address,
                classId = classId,
                sizeRaw = sizeRaw,
                sizeBytes = sizeBytes,
                allocSite = optString(o, "allocationSite"),
                incomplete = (o["incomplete"] as? JsonPrimitive)?.content == "true",
            )
        }

        val edges = mutableListOf<HeapEdge>()
        arr(root, "edges")?.forEachIndexed { i, el ->
            val path = "/edges/$i"
            val o = el as? JsonObject ?: return@forEachIndexed err(path, "BAD_TYPE", "edge 条目必须是对象")
            val from = optString(o, "from")
            val to = optString(o, "to")
            val kindRaw = optString(o, "kind")
            if (from == null || to == null || kindRaw == null) {
                err(path, "MISSING_FIELD", "edge 缺少 from/to/kind")
                return@forEachIndexed
            }
            val kind = EdgeKind.entries.firstOrNull { it.name == kindRaw }
            if (kind == null) {
                warn("$path/kind", "EDGE_KIND_UNKNOWN", "未知边类型 '$kindRaw'，按 INTERNAL 保留")
            }
            edges += HeapEdge(
                edgeId = optString(o, "edgeId") ?: "e$i",
                from = from, to = to,
                kind = kind ?: EdgeKind.INTERNAL,
                name = optString(o, "name"),
            )
        }

        val roots = mutableListOf<HeapRoot>()
        arr(root, "roots")?.forEachIndexed { i, el ->
            val path = "/roots/$i"
            val o = el as? JsonObject ?: return@forEachIndexed err(path, "BAD_TYPE", "root 条目必须是对象")
            val obj = optString(o, "objectId")
            val type = optString(o, "rootType")
            if (obj == null || type == null) {
                err(path, "MISSING_FIELD", "root 缺少 objectId/rootType")
                return@forEachIndexed
            }
            roots += HeapRoot(rootId = optString(o, "rootId") ?: "r$i", objectId = obj, rootType = type)
        }

        val blocks = mutableListOf<IncompleteBlock>()
        arr(root, "incompleteBlocks")?.forEachIndexed { i, el ->
            val path = "/incompleteBlocks/$i"
            val o = el as? JsonObject ?: return@forEachIndexed err(path, "BAD_TYPE", "block 条目必须是对象")
            blocks += IncompleteBlock(
                blockId = optString(o, "blockId") ?: "b$i",
                offsetRaw = optString(o, "offset") ?: "?",
                length = (o["length"] as? JsonPrimitive)?.content?.toLongOrNull(),
                reason = optString(o, "reason") ?: "unspecified",
                raw = optString(o, "raw"),
            )
        }

        // 重复地址：允许存在，但记录诊断 —— 地址不是稳定身份。
        val byAddress = objects.groupBy { it.addressRaw }.filterValues { it.size > 1 }
        byAddress.forEach { (addr, objs) ->
            warn("/objects", "DUPLICATE_ADDRESS",
                "地址 $addr 被 ${objs.size} 个对象共享 (${objs.joinToString(",") { it.objectId }})；地址不作为稳定身份")
        }
        // 重复对象 id：格式损坏。
        objects.groupBy { it.objectId }.filterValues { it.size > 1 }.keys.forEach { id ->
            err("/objects", "DUPLICATE_OBJECT_ID", "对象 id '$id' 重复，身份冲突")
        }

        if (diags.any { it.severity == Severity.ERROR }) {
            return ParseOutcome.Corrupt(diags)
        }
        return ParseOutcome.Ok(
            SnapshotBundle(
                snapshotId = snapshotId,
                name = name,
                parserVersion = parserVersion,
                classes = classes,
                objects = objects,
                edges = edges,
                roots = roots,
                incompleteBlocks = blocks,
                diagnostics = diags,
            )
        )
    }

    /** 派生层单位规范化：支持 "4096"、"4096B"、"4 KiB"、"1.5 MiB"、"0x1000"。 */
    fun normalizeSize(raw: String): Long? {
        val t = raw.trim()
        t.toLongOrNull()?.let { return it }
        t.removePrefix("0x").toLongOrNull(16)?.let { if (t.startsWith("0x")) return it }
        val m = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*(B|KB|KiB|MB|MiB|GB|GiB)$""").find(t) ?: return null
        val v = m.groupValues[1].toDouble()
        return when (m.groupValues[2]) {
            "B" -> v.toLong()
            "KB" -> (v * 1_000).toLong()
            "KiB" -> (v * 1024).toLong()
            "MB" -> (v * 1_000_000).toLong()
            "MiB" -> (v * 1024 * 1024).toLong()
            "GB" -> (v * 1_000_000_000).toLong()
            "GiB" -> (v * 1024L * 1024 * 1024).toLong()
            else -> null
        }
    }

    private fun arr(root: JsonObject, key: String): JsonArray? = root[key] as? JsonArray

    private fun optString(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun reqString(o: JsonObject, key: String, path: String, diags: MutableList<Diagnostic>): String? {
        val v = optString(o, key)
        if (v == null) diags += Diagnostic(path, "MISSING_FIELD", "缺少必填字段 '$key'", Severity.ERROR)
        return v
    }
}
