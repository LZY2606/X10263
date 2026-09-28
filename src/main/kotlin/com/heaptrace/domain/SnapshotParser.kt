package com.heaptrace.domain

import kotlinx.serialization.json.*

/**
 * 材料层入口：把原始文本解析成不可变快照材料。
 * - 格式损坏（非 JSON / 结构不符 / magic 错误）=> FormatCorrupted
 * - 语义无解（稳定 id 冲突且元数据不一致）=> SemanticUnsolvable
 * - 超过资源预算 => BudgetExhausted
 * - 其余不完整/重复地址/悬空引用 => 诊断保留在 Ok 中
 */
object SnapshotParser {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(raw: String, sourceId: String, budget: Budget = Budget()): ImportOutcome {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return ImportOutcome.FormatCorrupted(
                listOf(
                    Diagnostic(
                        "FORMAT_INVALID_JSON", "FATAL",
                        "材料不是合法 JSON：${e.message ?: "parse error"}",
                        Location(sourceId, "char-offset", "0", 0)
                    )
                )
            )
        }
        val root = element as? JsonObject
            ?: return ImportOutcome.FormatCorrupted(
                listOf(
                    Diagnostic(
                        "FORMAT_ROOT_NOT_OBJECT", "FATAL",
                        "材料根节点必须是对象",
                        Location(sourceId)
                    )
                )
            )

        val magicErrors = checkMagic(root, sourceId)
        if (magicErrors.isNotEmpty()) return ImportOutcome.FormatCorrupted(magicErrors)

        val decodeErrors = mutableListOf<Diagnostic>()
        val meta = decodeElement<SnapshotMeta>(root["meta"], sourceId, "meta", decodeErrors)
            ?: return ImportOutcome.FormatCorrupted(decodeErrors)
        val classes = decodeList<Klass>(root["classes"], sourceId, "classes", decodeErrors)
        val objects = decodeList<Obj>(root["objects"], sourceId, "objects", decodeErrors)
        val edges = decodeList<Edge>(root["edges"], sourceId, "edges", decodeErrors)
        val roots = decodeList<Root>(root["roots"], sourceId, "roots", decodeErrors)
        val sites = decodeListOrEmpty<AllocationSite>(root["allocationSites"], sourceId, "allocationSites", decodeErrors)
        val blocks = decodeListOrEmpty<IncompleteBlock>(root["incompleteBlocks"], sourceId, "incompleteBlocks", decodeErrors)
        if (decodeErrors.isNotEmpty()) return ImportOutcome.FormatCorrupted(decodeErrors)

        val diagnostics = mutableListOf<Diagnostic>()
        fun warn(code: String, message: String, ordinal: Int, section: String,
                 rawValue: String? = null, objectId: String? = null) {
            diagnostics += Diagnostic(
                code, "WARN", message,
                Location(sourceId, "input-ordinal", section, ordinal),
                rawValue, objectId
            )
        }

        // 资源预算：材料过大直接拒绝（与格式/语义问题是不同结果）。
        if (objects.size > budget.maxObjects)
            return ImportOutcome.BudgetExhausted(
                listOf(
                    Diagnostic(
                        "BUDGET_OBJECTS", "FATAL",
                        "对象数 ${objects.size} 超过预算 ${budget.maxObjects}",
                        Location(sourceId, "count", "objects", objects.size),
                        rawValue = objects.size.toString()
                    )
                )
            )
        if (edges.size > budget.maxEdges)
            return ImportOutcome.BudgetExhausted(
                listOf(
                    Diagnostic(
                        "BUDGET_EDGES", "FATAL",
                        "边数 ${edges.size} 超过预算 ${budget.maxEdges}",
                        Location(sourceId, "count", "edges", edges.size),
                        rawValue = edges.size.toString()
                    )
                )
            )

        // 语义无解：同一稳定 id 出现两条不能调和的元数据。
        val idConflicts = mutableListOf<Diagnostic>()
        objects.groupingBy { it.id }.eachCount().filterValues { it > 1 }.forEach { (id, _) ->
            val variants = objects.filter { it.id == id }
            val conflicting = variants.any { b ->
                b.address != variants[0].address || b.classId != variants[0].classId
            }
            val ordinal = variants.indexOfFirst { it.id == id }
            if (conflicting) {
                idConflicts += Diagnostic(
                    "SEMANTIC_ID_CONFLICT", "FATAL",
                    "稳定对象 id 重复且地址/类元数据不一致，无法调和：$id",
                    Location(sourceId, "input-ordinal", "objects", ordinal),
                    rawValue = variants.joinToString { it.address }, objectId = id
                )
            } else {
                warn("DUPLICATE_ID_DUP", "对象 id 出现重复（元数据一致），以首次出现为准：$id", ordinal, "objects", objectId = id)
            }
        }
        if (idConflicts.isNotEmpty()) return ImportOutcome.SemanticUnsolvable(idConflicts)

        // 重复地址：合法但必须诊断，地址不得充当稳定身份。
        objects.filter { it.address.isNotBlank() }.groupingBy { it.address }
            .eachCount().filterValues { it > 1 }.forEach { (addr, _) ->
                val ids = objects.filter { it.address == addr }.map { it.id }
                warn(
                    "DUPLICATE_ADDRESS",
                    "地址 $addr 被多个对象 ${ids.joinToString()} 共享；地址仅为观测值，身份以对象 id 为准",
                    objects.indexOfFirst { it.address == addr }, "objects", rawValue = addr, objectId = ids.first()
                )
            }

        // 不完整对象：保留在材料里并显式诊断，绝不填空值。
        objects.forEachIndexed { i, o ->
            if (o.incomplete) {
                warn(
                    "INCOMPLETE_OBJECT",
                    "对象 ${o.id} 来自不完整块，部分字段缺失，派生层不得用默认值替代",
                    i, "objects", objectId = o.id
                )
            }
            val size = SizeUnits.parseBytes(o.sizeRaw)
            if (size == null) {
                warn(
                    "SIZE_UNCONVERTIBLE",
                    "对象 ${o.id} 尺寸原始值 '${o.sizeRaw}'（单位 ${o.sizeUnit}）无法转换为字节；保留原值并标记为未知",
                    i, "objects", rawValue = o.sizeRaw, objectId = o.id
                )
            }
        }

        blocks.forEachIndexed { i, b ->
            warn(
                "INCOMPLETE_BLOCK",
                "不完整块 ${b.blockId} @${b.offset}：${b.reason}；影响对象 ${b.partialObjectIds.joinToString()}",
                i, "incompleteBlocks", rawValue = b.reason
            )
        }

        val classIds = classes.map { it.id }.toHashSet()
        val objectIds = objects.map { it.id }.toHashSet()
        val siteIds = sites.map { it.id }.toHashSet()

        objects.forEachIndexed { i, o ->
            if (o.classId !in classIds) {
                warn(
                    "DANGLING_CLASS",
                    "对象 ${o.id} 引用不存在的类元数据 ${o.classId}",
                    i, "objects", rawValue = o.classId, objectId = o.id
                )
            }
            if (o.allocationSiteId != null && o.allocationSiteId !in siteIds) {
                warn(
                    "DANGLING_ALLOC_SITE",
                    "对象 ${o.id} 引用不存在的 allocation site ${o.allocationSiteId}",
                    i, "objects", rawValue = o.allocationSiteId, objectId = o.id
                )
            }
        }
        edges.forEachIndexed { i, e ->
            if (e.from !in objectIds) warn(
                "DANGLING_EDGE_FROM", "边起点 ${e.from} 不存在（来源片段 #$i ${e.label}）",
                i, "edges", rawValue = e.from
            )
            if (e.to !in objectIds) warn(
                "DANGLING_EDGE_TO", "边终点 ${e.to} 不存在（来源片段 #$i ${e.label}）",
                i, "edges", rawValue = e.to
            )
        }
        roots.forEachIndexed { i, r ->
            if (r.objectId !in objectIds) warn(
                "DANGLING_ROOT", "root ${r.id}(${r.type}) 指向不存在的对象 ${r.objectId}",
                i, "roots", rawValue = r.objectId
            )
        }

        val dedupedObjects = objects.distinctBy { it.id }
        return ImportOutcome.Ok(
            SnapshotBundle(
                format = root["format"]?.jsonPrimitive?.contentOrNull() ?: "heap-shadow-snapshot",
                formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull() ?: 1,
                meta = meta, classes = classes, objects = dedupedObjects,
                edges = edges, roots = roots, allocationSites = sites, incompleteBlocks = blocks
            ),
            diagnostics
        )
    }

    private fun checkMagic(root: JsonObject, sourceId: String): List<Diagnostic> {
        val errors = mutableListOf<Diagnostic>()
        val format = root["format"]?.jsonPrimitive?.contentOrNull
        if (format != null && format != "heap-shadow-snapshot") {
            errors += Diagnostic(
                "FORMAT_BAD_MAGIC", "FATAL",
                "材料格式标记 '$format' 与 heap-shadow-snapshot 不符",
                Location(sourceId, "magic", "format", 0), rawValue = format
            )
        }
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull
        if (version != null && version != 1) {
            errors += Diagnostic(
                "FORMAT_BAD_VERSION", "FATAL",
                "不支持的材料格式版本 $version（仅支持 1）",
                Location(sourceId, "version", "formatVersion", 0), rawValue = version.toString()
            )
        }
        return errors
    }

    private inline fun <reified T> decodeElement(
        el: JsonElement?, sourceId: String, section: String, sink: MutableList<Diagnostic>
    ): T? {
        if (el == null || el is JsonNull) {
            sink += Diagnostic(
                "FORMAT_MISSING_SECTION", "FATAL", "缺少必需片段 '$section'",
                Location(sourceId, "section", section)
            )
            return null
        }
        return try {
            json.decodeFromJsonElement<T>(el)
        } catch (e: Exception) {
            sink += Diagnostic(
                "FORMAT_SECTION_SHAPE", "FATAL", "片段 '$section' 结构无法识别：${e.message}",
                Location(sourceId, "section", section)
            )
            null
        }
    }

    private inline fun <reified T> decodeList(
        el: JsonElement?, sourceId: String, section: String, sink: MutableList<Diagnostic>
    ): List<T> {
        if (el == null || el is JsonNull) {
            sink += Diagnostic(
                "FORMAT_MISSING_SECTION", "FATAL", "缺少必需片段 '$section'",
                Location(sourceId, "section", section)
            )
            return emptyList()
        }
        if (el !is JsonArray) {
            sink += Diagnostic(
                "FORMAT_SECTION_SHAPE", "FATAL", "片段 '$section' 必须是数组",
                Location(sourceId, "section", section)
            )
            return emptyList()
        }
        return el.mapIndexedNotNull { i, item ->
            try {
                json.decodeFromJsonElement<T>(item)
            } catch (e: Exception) {
                sink += Diagnostic(
                    "FORMAT_ITEM_SHAPE", "FATAL",
                    "'$section[$i]' 结构无法识别：${e.message}",
                    Location(sourceId, "input-ordinal", section, i)
                )
                null
            }
        }
    }

    private inline fun <reified T> decodeListOrEmpty(
        el: JsonElement?, sourceId: String, section: String, sink: MutableList<Diagnostic>
    ): List<T> = if (el == null || el is JsonNull) emptyList() else decodeList(el, sourceId, section, sink)
}

object SizeUnits {
    private val suffix = mapOf(
        "b" to 1L, "byte" to 1L, "bytes" to 1L,
        "kb" to 1024L, "kib" to 1024L,
        "mb" to 1024L * 1024L, "mib" to 1024L * 1024L
    )

    /** 把原始尺寸表示转换为字节；无法转换返回 null（调用方保留原值 + 诊断）。 */
    fun parseBytes(raw: String): Long? {
        val text = raw.trim().lowercase()
        if (text.isEmpty() || text == "unknown" || text == "?" || text == "-") return null
        val unit = suffix.keys.firstOrNull { text.endsWith(it) }
        val numberText = if (unit != null) text.removeSuffix(unit).trim() else text
        val n = numberText.toLongOrNull() ?: return null
        if (n < 0) return null
        return n * (suffix[unit] ?: 1L)
    }
}
