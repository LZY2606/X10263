package heapproom.parser

import heapproom.domain.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * 解析器：把多个 JSON 片段合并成不可变快照材料。
 *
 * 规则：
 *  1) 原始片段文本、sha256、解析器版本、原始字段 rawRecord 全部保留；
 *  2) 领域量(raw string) 原样进 Quant，规范化只在派生层，无法转换则留 null + 诊断；
 *  3) 重复对象 id / 悬空引用 => SEMANTIC_UNRESOLVABLE；
 *     重复地址是合法但可疑的（如压缩指针/重复块），只产生 WARNING；
 *  4) 语法损坏 => FORMAT_CORRUPTED（带片段序号与 JSON 位置）；
 *  5) 超过预算 => RESOURCE_BUDGET_EXHAUSTED，与前两种严格区分。
 */
object SnapshotParser {

    data class Budget(val maxObjects: Int = 5_000, val maxEdges: Int = 20_000)

    private val json = Json { ignoreUnknownKeys = false }

    fun import(snapshotId: String, name: String, fragments: List<String>, budget: Budget = Budget()): ImportOutcome {
        val diag = mutableListOf<Diagnostic>()
        if (fragments.isEmpty()) {
            return ImportOutcome.FormatCorrupted(
                listOf(err("EMPTY_BUNDLE", "没有任何输入片段", "fragments[]")), 0
            )
        }

        val roots = mutableListOf<JsonElement>()
        var merged = JsonObject(emptyMap())
        fragments.forEachIndexed { ordinal, raw ->
            val element = try {
                json.parseToJsonElement(raw)
            } catch (e: Exception) {
                val loc = "fragment[$ordinal]" + positionHint(e.message)
                return ImportOutcome.FormatCorrupted(
                    listOf(err("JSON_SYNTAX", "片段不是合法 JSON：${e.message?.take(160)}", loc)),
                    ordinal,
                )
            }
            if (element !is JsonObject) {
                return ImportOutcome.FormatCorrupted(
                    listOf(err("JSON_SHAPE", "片段顶层必须是对象", "fragment[$ordinal]")),
                    ordinal,
                )
            }
            merged = deepMerge(merged, element, "fragment[$ordinal]", diag)
            roots.add(element)
        }

        val accepted = buildSnapshot(snapshotId, name, fragments, merged, diag, budget) ?: return diag.toOutcome()
        return accepted
    }

    private fun MutableList<Diagnostic>.toOutcome(): ImportOutcome {
        val first = firstOrNull { it.severity == Diagnostic.Severity.ERROR }
            ?: error("内部错误：无 ERROR 却中断解析")
        return when (first.code) {
            "BUDGET_OBJECTS" -> ImportOutcome.ResourceBudgetExhausted(
                toList(), "objects", first.message.substringAfterLast('=').trim().toIntOrNull() ?: -1, -1
            )
            "BUDGET_EDGES" -> ImportOutcome.ResourceBudgetExhausted(toList(), "edges", -1, -1)
            else -> ImportOutcome.SemanticUnresolvable(toList())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildSnapshot(
        snapshotId: String,
        name: String,
        fragments: List<String>,
        root: JsonObject,
        diag: MutableList<Diagnostic>,
        budget: Budget,
    ): ImportOutcome.Accepted? {
        fun reqString(key: String): String? = (root[key] as? JsonPrimitive)?.let {
            if (it.isString || it.contentOrNull != null) it.content else null
        }

        val parserVersion = reqString("parserVersion")
            ?: run {
                diag += err("MISSING_PARSER_VERSION", "parserVersion 是强制保留字段", "/parserVersion")
                return null
            }
        val capturedRaw = (root["capturedAt"] as? JsonPrimitive)?.content
            ?: run {
                diag += err("MISSING_TIME_MARK", "capturedAt 时间基准缺失", "/capturedAt")
                return null
            }
        val timeBase = (root["timeBase"] as? JsonPrimitive)?.content
        val timeMark = TimeMark(capturedRaw, parseEpoch(capturedRaw), timeBase, if (parseEpoch(capturedRaw) == null) "无法规范化为 epoch ms，保留原始表示" else null)
        if (timeMark.diagnostic != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_TIME", timeMark.diagnostic, "/capturedAt")

        val addressBase = (root["addressBase"] as? JsonPrimitive)?.content ?: "0x0"
        val addressUnit = (root["addressUnit"] as? JsonPrimitive)?.content ?: "byte"
        val sizeUnit = (root["sizeUnit"] as? JsonPrimitive)?.content ?: "byte"

        val classArr = root["classes"] as? JsonArray ?: JsonArray(emptyList())
        val objArr = root["objects"] as? JsonArray ?: JsonArray(emptyList())
        val edgeArr = root["edges"] as? JsonArray ?: JsonArray(emptyList())
        val rootArr = root["roots"] as? JsonArray ?: JsonArray(emptyList())
        val siteArr = root["allocationSites"] as? JsonArray ?: JsonArray(emptyList())
        val blockArr = root["incompleteBlocks"] as? JsonArray ?: JsonArray(emptyList())

        if (objArr.size > budget.maxObjects) {
            diag += err("BUDGET_OBJECTS", "对象数 ${objArr.size} 超过预算 ${budget.maxObjects}; observed=${objArr.size} limit=${budget.maxObjects}", "/objects")
            return null
        }
        if (edgeArr.size > budget.maxEdges) {
            diag += err("BUDGET_EDGES", "边数 ${edgeArr.size} 超过预算 ${budget.maxEdges}; observed=${edgeArr.size} limit=${budget.maxEdges}", "/edges")
            return null
        }

        val classes = classArr.mapIndexed { i, el ->
            val o = el.asObj("/classes[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/classes[$i].id", diag) ?: return@mapIndexed null
            val nm = o.req("name", "/classes[$i].name", diag) ?: return@mapIndexed null
            ClassMeta(
                classId = id,
                name = nm,
                superClassId = o.str("superClassId"),
                classLoaderId = o.str("classLoaderId"),
                nameRaw = o.str("nameRaw") ?: nm,
                rawRecord = o,
            )
        }.filterNotNull()

        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val objects = objArr.mapIndexed { i, el ->
            val o = el.asObj("/objects[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/objects[$i].id", diag) ?: return@mapIndexed null
            val addrRaw = o.req("address", "/objects[$i].address", diag) ?: return@mapIndexed null
            val (addrNorm, addrDiag) = parseAddress(addrRaw)
            if (addrDiag != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_ADDRESS", addrDiag, "/objects[$i].address")
            val sizeRaw = o.str("shallowSize") ?: "0"
            val (sizeNorm, sizeDiag) = parseSize(sizeRaw, sizeUnit)
            if (sizeDiag != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_SIZE", sizeDiag, "/objects[$i].shallowSize")
            HeapObject(
                id = id,
                identityKey = o.str("identityKey"),
                address = Quant(addrRaw, addrNorm, addressUnit, addrDiag),
                classId = o.str("classId"),
                shallowSize = Quant(sizeRaw, sizeNorm ?: 0L, sizeUnit, sizeDiag),
                incomplete = (o["incomplete"] as? JsonPrimitive)?.booleanOrNull == true,
                allocationSiteId = o.str("allocationSiteId"),
                rawRecord = o,
            )
        }.filterNotNull()

        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val seenObj = HashSet<String>()
        for (o in objects) {
            if (!seenObj.add(o.id)) {
                diag += err("DUPLICATE_OBJECT_ID", "对象 id '${o.id}' 重复，无法唯一确定", "/objects (id=${o.id})")
            }
        }
        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val addrGroups = objects.groupBy { it.address.normalized }.filterKeys { it != null }.filterValues { it.size > 1 }
        for (g in addrGroups) {
            diag += Diagnostic(
                Diagnostic.Severity.WARNING,
                "DUPLICATE_ADDRESS",
                "规范化地址 ${g.key} 被 ${g.value.size} 个对象共享(${g.value.joinToString { it.id }})；地址不得作为稳定身份，按原始 id 区分",
                "/objects (address=${g.key})",
            )
        }
        if (objects.any { it.classId != null && classes.none { c -> c.classId == it.classId } }) {
            diag += Diagnostic(
                Diagnostic.Severity.WARNING,
                "DANGLING_CLASS_REF",
                "存在指向未声明类元数据的对象",
                "/objects[].classId",
            )
        }

        val edges = edgeArr.mapIndexed { i, el ->
            val o = el.asObj("/edges[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/edges[$i].id", diag) ?: return@mapIndexed null
            val from = o.req("from", "/edges[$i].from", diag) ?: return@mapIndexed null
            val to = o.req("to", "/edges[$i].to", diag) ?: return@mapIndexed null
            val kindRaw = o.str("kind") ?: "strong"
            Edge(
                edgeId = id,
                kind = edgeKind(kindRaw),
                kindRaw = kindRaw,
                fromObjectId = from,
                toObjectId = to,
                fieldName = o.str("field"),
                rawRecord = o,
            )
        }.filterNotNull()

        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val seenEdge = HashSet<String>()
        for (e in edges) {
            if (!seenEdge.add(e.edgeId)) diag += err("DUPLICATE_EDGE_ID", "边 id '${e.edgeId}' 重复", "/edges (id=${e.edgeId})")
            if (objects.none { it.id == e.fromObjectId }) diag += err("DANGLING_EDGE_FROM", "边 ${e.edgeId} 的源对象 ${e.fromObjectId} 不存在", "/edges[$e.edgeId].from")
            if (objects.none { it.id == e.toObjectId }) diag += err("DANGLING_EDGE_TO", "边 ${e.edgeId} 的目标对象 ${e.toObjectId} 不存在", "/edges[$e.edgeId].to")
            if (e.kind == EdgeKind.UNKNOWN) diag += Diagnostic(
                Diagnostic.Severity.WARNING, "UNKNOWN_EDGE_KIND",
                "边 ${e.edgeId} 的原始类型 '${e.kindRaw}' 未识别，按强边参与图结构但保留原始记号",
                "/edges (id=${e.edgeId}).kind",
            )
        }
        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val rootsList = rootArr.mapIndexed { i, el ->
            val o = el.asObj("/roots[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/roots[$i].id", diag) ?: return@mapIndexed null
            val obj = o.req("object", "/roots[$i].object", diag) ?: return@mapIndexed null
            val typeRaw = o.str("type") ?: "system"
            RootRecord(
                rootId = id,
                objectId = obj,
                rootType = rootType(typeRaw),
                rootTypeRaw = typeRaw,
                label = o.str("label"),
                rawRecord = o,
            )
        }.filterNotNull()

        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        for (r in rootsList) {
            if (objects.none { it.id == r.objectId }) diag += err("DANGLING_ROOT", "root ${r.rootId} 指向不存在的对象 ${r.objectId}", "/roots (id=${r.rootId}).object")
            if (r.rootType == RootType.UNKNOWN) diag += Diagnostic(
                Diagnostic.Severity.WARNING, "UNKNOWN_ROOT_TYPE",
                "root ${r.rootId} 的原始分类 '${r.rootTypeRaw}' 未识别，保留原始记号",
                "/roots (id=${r.rootId}).type",
            )
        }
        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val sites = siteArr.mapIndexed { i, el ->
            val o = el.asObj("/allocationSites[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/allocationSites[$i].id", diag) ?: return@mapIndexed null
            val countRaw = o.str("allocationCount") ?: "0"
            val (countNorm, countDiag) = parseSize(countRaw, "count")
            if (countDiag != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_COUNT", countDiag, "/allocationSites[$i].allocationCount")
            AllocationSite(id, o.str("classId"), o.str("location") ?: "?", Quant(countRaw, countNorm ?: 0L, "count", countDiag), o)
        }.filterNotNull()

        val blocks = blockArr.mapIndexed { i, el ->
            val o = el.asObj("/incompleteBlocks[$i]", diag) ?: return@mapIndexed null
            val id = o.req("id", "/incompleteBlocks[$i].id", diag) ?: return@mapIndexed null
            val offsetRaw = o.str("offset") ?: "?"
            val lengthRaw = o.str("length") ?: "?"
            val (offNorm, offDiag) = parseSize(offsetRaw, addressUnit)
            val (lenNorm, lenDiag) = parseSize(lengthRaw, addressUnit)
            if (offDiag != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_OFFSET", offDiag, "/incompleteBlocks[$i].offset")
            if (lenDiag != null) diag += Diagnostic(Diagnostic.Severity.WARNING, "UNCONVERTIBLE_LENGTH", lenDiag, "/incompleteBlocks[$i].length")
            IncompleteBlock(
                blockId = id,
                offset = Quant(offsetRaw, offNorm, addressUnit, offDiag),
                length = Quant(lengthRaw, lenNorm, addressUnit, lenDiag),
                reason = o.str("reason") ?: "unspecified",
                affectsHeapGraph = (o["affectsHeapGraph"] as? JsonPrimitive)?.booleanOrNull ?: true,
                rawRecord = o,
            )
        }.filterNotNull()

        if (diag.any { it.severity == Diagnostic.Severity.ERROR }) return null

        val frags = fragments.mapIndexed { i, raw ->
            InputFragment("frag-$snapshotId-$i", i, raw, sha256(raw))
        }

        return ImportOutcome.Accepted(
            Snapshot(
                snapshotId = snapshotId,
                name = name,
                parserVersion = parserVersion,
                capturedAt = timeMark,
                addressBase = addressBase,
                addressUnit = addressUnit,
                sizeUnit = sizeUnit,
                classes = classes.stableSorted { it.classId },
                objects = objects.stableSorted { it.id },
                edges = edges.stableSorted { it.edgeId },
                roots = rootsList.stableSorted { it.rootId },
                allocationSites = sites.stableSorted { it.siteId },
                incompleteBlocks = blocks.stableSorted { it.blockId },
                fragments = frags,
                diagnostics = diag,
            ),
            diag,
        )
    }

    private fun <T> List<T>.stableSorted(key: (T) -> String): List<T> =
        sortedWith(compareBy(key).thenBy { System.identityHashCode(it) })
