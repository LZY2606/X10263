package forensics.parse

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import forensics.model.*

/** 资源预算：解析时可拒绝无限/超大输入，且结果必须与格式损坏/语义无解区分。 */
data class ParseBudget(
    val maxObjects: Int = 200_000,
    val maxEdges: Int = 1_000_000,
    val maxBytes: Int = 64 * 1024 * 1024,
)

class SnapshotParser(private val budget: ParseBudget = ParseBudget()) {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun parse(sourceName: String, text: String): ImportOutcome {
        if (text.length > budget.maxBytes) {
            return ImportOutcome.BudgetExhausted(
                listOf(Diagnostic("B001", "ERROR", "input:bytes", "输入 ${text.length} 字节超过预算 ${budget.maxBytes}"))
            )
        }
        val root: JsonNode = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            return ImportOutcome.FormatCorrupt(
                listOf(Diagnostic("F001", "ERROR", "input:json", "JSON 无法解析: ${e.message ?: "语法错误"}"))
            )
        }
        if (!root.isObject) {
            return failFormat("input", "顶层必须是对象")
        }
        val formatErrors = mutableListOf<Diagnostic>()
        for (key in listOf("snapshotId", "parserVersion", "objects", "edges", "roots", "classes")) {
            if (!root.has(key)) formatErrors += Diagnostic("F002", "ERROR", "input:$key", "缺少必需字段 '$key'")
        }
        if (formatErrors.isNotEmpty()) return ImportOutcome.FormatCorrupt(formatErrors)

        val warnings = mutableListOf<Diagnostic>()
        val semanticErrors = mutableListOf<Diagnostic>()

        val objectsNode = root.path("objects")
        val edgesNode = root.path("edges")
        if (!objectsNode.isArray) return failFormat("objects", "objects 必须是数组")
        if (!edgesNode.isArray) return failFormat("edges", "edges 必须是数组")
        if (objectsNode.size() > budget.maxObjects) {
            return ImportOutcome.BudgetExhausted(
                listOf(Diagnostic("B002", "ERROR", "objects", "对象数 ${objectsNode.size()} 超过预算 ${budget.maxObjects}"))
            )
        }
        if (edgesNode.size() > budget.maxEdges) {
            return ImportOutcome.BudgetExhausted(
                listOf(Diagnostic("B003", "ERROR", "edges", "边数 ${edgesNode.size()} 超过预算 ${budget.maxEdges}"))
            )
        }
        return parseBody(sourceName, root, objectsNode, edgesNode, warnings, semanticErrors)
    }

    private fun parseBody(
        sourceName: String,
        root: JsonNode,
        objectsNode: JsonNode,
        edgesNode: JsonNode,
        warnings: MutableList<Diagnostic>,
        semanticErrors: MutableList<Diagnostic>,
    ): ImportOutcome {
        val sizeBasis = root.path("sizeUnitBasis").asText(null)
        val addressBasis = root.path("addressBasis").asText(null)

        val objects = mutableListOf<RawObject>()
        val seenObjectIds = HashSet<String>()
        objectsNode.forEachIndexed { i, n ->
            val pos = "objects[$i]"
            val id = n.path("objectId").asText(null)
            if (id == null) { semanticErrors += Diagnostic("S001", "ERROR", "$pos.objectId", "对象缺少 objectId"); return@forEachIndexed }
            if (!seenObjectIds.add(id)) {
                semanticErrors += Diagnostic("S002", "ERROR", "$pos.objectId", "重复 objectId '$id'（objectId 是稳定身份，不可重复）")
            }
            val addrNode = n.path("address")
            var addrRaw: String? = null
            var addr: Long? = null
            if (!addrNode.isMissingNode && !addrNode.isNull) {
                addrRaw = addrNode.asText()
                addr = parseAddress(addrRaw)
                if (addr == null) warnings += Diagnostic(
                    "W101", "WARNING", "$pos.address", "地址 '$addrRaw' 无法规范化为整数，保留原始表示"
                )
            }
            val shallow = if (n.has("shallowSize")) parseMeasure(n.path("shallowSize"), "objects#$id.shallowSize", sizeBasis, warnings) else null
            val incomplete = n.path("incomplete").asBoolean(false)
            objects += RawObject(
                objectId = id,
                addressRaw = addrRaw,
                address = addr,
                addressBasis = addressBasis,
                classId = n.path("classId").asText(null),
                shallowSize = shallow,
                allocationSiteId = n.path("allocationSiteId").asText(null),
                incomplete = incomplete,
                incompleteReason = n.path("incompleteReason").asText(null),
                raw = if (n.isObject) mapper.convertValue(n, Map::class.java) as Map<String, Any?> else emptyMap(),
            )
        }

        val classes = mutableListOf<RawClass>()
        root.path("classes").takeIf { it.isArray }?.forEachIndexed { i, n ->
            val cid = n.path("classId").asText(null)
            if (cid == null) { semanticErrors += Diagnostic("S003", "ERROR", "classes[$i].classId", "类缺少 classId"); return@forEachIndexed }
            val name = n.path("name").asText(null) ?: "<unnamed:$cid>"
            classes += RawClass(
                classId = cid, name = name,
                classLoaderId = n.path("classLoaderId").asText(null),
                superClassId = n.path("superClassId").asText(null),
                allocationSiteId = n.path("allocationSiteId").asText(null),
                instanceSize = if (n.has("instanceSize")) parseMeasure(n.path("instanceSize"), "classes[$i].instanceSize", sizeBasis, warnings) else null,
            )
        }
        if (!root.path("classes").isArray) semanticErrors += Diagnostic("F003", "ERROR", "classes", "classes 必须是数组")

        val sites = LinkedHashMap<String, AllocationSite>()
        root.path("allocationSites").takeIf { it.isArray }?.forEachIndexed { i, n ->
            val sid = n.path("siteId").asText(null) ?: return@forEachIndexed
            sites[sid] = AllocationSite(
                siteId = sid,
                rawLocation = n.path("rawLocation").asText(""),
                coordinateBasis = n.path("coordinateBasis").asText(null),
                coordinate = n.path("coordinate").asText(null),
                method = n.path("method").asText(null),
            )
        }

        val edges = mutableListOf<RawEdge>()
        val seenEdgeIds = HashSet<String>()
        edgesNode.forEachIndexed { i, n ->
            val pos = "edges[$i]"
            val eid = n.path("edgeId").asText(null) ?: "edge@$i"
            if (!seenEdgeIds.add(eid)) semanticErrors += Diagnostic("S004", "ERROR", "$pos.edgeId", "重复 edgeId '$eid'")
            val from = n.path("fromId").asText(null)
            val to = n.path("toId").asText(null)
            if (from == null || to == null) {
                semanticErrors += Diagnostic("S005", "ERROR", pos, "边缺少 fromId/toId")
                return@forEachIndexed
            }
            if (from !in seenObjectIds) semanticErrors += Diagnostic("S006", "ERROR", "$pos.fromId", "边起点 '$from' 不在对象表中")
            if (to !in seenObjectIds) semanticErrors += Diagnostic("S007", "ERROR", "$pos.toId", "边终点 '$to' 不在对象表中")
            val kind = parseEdgeKind(n.path("kind").asText(null))
            if (kind == EdgeKind.UNKNOWN) warnings += Diagnostic("W102", "WARNING", "$pos.kind", "未知边类型 '${n.path("kind").asText("")}'，按 UNKNOWN 保留")
            edges += RawEdge(edgeId = eid, fromId = from, toId = to, kind = kind,
                field = n.path("field").asText(null), order = n.path("order").asInt(i),
                raw = if (n.isObject) mapper.convertValue(n, Map::class.java) as Map<String, Any?> else emptyMap())
        }

        val roots = mutableListOf<RawRoot>()
        root.path("roots").takeIf { it.isArray }?.forEachIndexed { i, n ->
            val pos = "roots[$i]"
            val oid = n.path("objectId").asText(null)
            if (oid == null) { semanticErrors += Diagnostic("S008", "ERROR", "$pos.objectId", "root 缺少 objectId"); return@forEachIndexed }
            if (oid !in seenObjectIds) semanticErrors += Diagnostic("S009", "ERROR", "$pos.objectId", "root 对象 '$oid' 不在对象表中")
            roots += RawRoot(rootId = n.path("rootId").asText(null) ?: "root@$i", objectId = oid,
                kind = RootKind.parse(n.path("kind").asText(null)),
                origin = n.path("origin").asText(null), order = n.path("order").asInt(i))
        }
        if (!root.path("roots").isArray) semanticErrors += Diagnostic("F004", "ERROR", "roots", "roots 必须是数组")

        val chunks = mutableListOf<IncompleteChunk>()
        root.path("incompleteChunks").takeIf { it.isArray }?.forEachIndexed { i, n ->
            chunks += IncompleteChunk(
                chunkId = n.path("chunkId").asText(null) ?: "chunk@$i",
                position = n.path("position").asText("unknown"),
                byteOffset = n.path("byteOffset").takeIf { it.isNumber }?.asLong(),
                reason = n.path("reason").asText(""),
                rawSnippet = n.path("rawSnippet").asText(""),
            )
        }

        if (semanticErrors.isNotEmpty()) return ImportOutcome.SemanticUnresolved(semanticErrors)

        val addressDups = findAddressDuplicates(objects)
        addressDups.forEach { d ->
            warnings += Diagnostic("W103", "WARNING", "objects#address=${d.address}",
                "地址 0x${d.address.toString(16)} 被多个对象共享 ${d.objectIds}；地址不作为稳定身份，使用 objectId")
        }
        if (chunks.isNotEmpty()) {
            warnings += Diagnostic("W201", "WARNING", "incompleteChunks",
                "存在 ${chunks.size} 个不完整块；依赖其内容的派生结果带有效区间与失效原因")
        }

        val snapshot = Snapshot(
            snapshotId = root.path("snapshotId").asText(),
            sourceName = root.path("sourceName").asText(null) ?: sourceName,
            parserVersion = root.path("parserVersion").asText(),
            formatVersion = root.path("formatVersion").asText(null),
            capturedAtBasis = root.path("capturedAtBasis").asText(null),
            capturedAtRaw = root.path("capturedAtRaw").asText(null),
            addressBasis = addressBasis,
            sizeUnitBasis = sizeBasis,
            objects = objects, classes = classes, edges = edges, roots = roots,
            allocationSites = sites.values.toList(),
            incompleteChunks = chunks,
            diagnostics = warnings,
            addressDuplicates = addressDups,
            rawEnvelope = mapper.convertValue(root, Map::class.java) as Map<String, Any?>,
        )
        return ImportOutcome.Ok(snapshot, warnings)
    }

    private fun failFormat(position: String, message: String): ImportOutcome =
        ImportOutcome.FormatCorrupt(listOf(Diagnostic("F002", "ERROR", position, message)))

    companion object {
        fun parseAddress(raw: String): Long? = try {
            val t = raw.trim()
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong()
        } catch (e: Exception) { null }

        fun parseEdgeKind(raw: String?): EdgeKind = when (raw?.trim()?.lowercase()) {
            "strong", "s", "ref" -> EdgeKind.STRONG
            "weak", "w", "soft", "phantom" -> EdgeKind.WEAK
            "internal", "i" -> EdgeKind.INTERNAL
            null, "" -> EdgeKind.UNKNOWN
            else -> EdgeKind.UNKNOWN
        }

        fun parseMeasure(node: JsonNode, position: String, defaultBasis: String?,
                         warnings: MutableList<Diagnostic>): Measure {
            if (node.isNumber) {
                return Measure(raw = node.asText(), unit = "byte", basis = defaultBasis, value = node.asDouble())
            }
            val raw = node.path("raw").asText(node.asText(""))
            val unit = node.path("unit").asText(null)
            val basis = node.path("basis").asText(defaultBasis)
            val valueNode = node.path("value")
            if (valueNode.isNumber) {
                return Measure(raw = raw, unit = unit, basis = basis, value = valueNode.asDouble())
            }
            val parsed = parseByteSize(raw)
            if (parsed != null) return Measure(raw = raw, unit = unit ?: "byte", basis = basis, value = parsed)
            warnings += Diagnostic("W100", "WARNING", position, "量 '$raw' 无法转换为字节，原值与诊断保留在原位置")
            return Measure(raw = raw, unit = unit, basis = basis, value = null,
                convertDiagnostic = "无法规范化为 byte（单位=$unit，基准=$basis）")
        }

        private val UNIT_FACTOR = mapOf(
            "byte" to 1.0, "bytes" to 1.0, "b" to 1.0,
            "kb" to 1024.0, "kib" to 1024.0,
            "mb" to 1024.0 * 1024, "mib" to 1024.0 * 1024,
            "gb" to 1024.0 * 1024 * 1024,
        )

        fun parseByteSize(raw: String): Double? {
            val m = Regex("""^\s*(-?[0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]*)\s*$""").matchEntire(raw) ?: return null
            val num = m.groupValues[1].toDoubleOrNull() ?: return null
            val unit = m.groupValues[2].lowercase().ifEmpty { "byte" }
            val f = UNIT_FACTOR[unit] ?: return null
            return num * f
        }

        fun findAddressDuplicates(objects: List<RawObject>): List<AddressDuplicate> =
            objects.filter { it.address != null }
                .groupBy { it.address!! }
                .filterValues { it.size > 1 }
                .map { (addr, group) ->
                    AddressDuplicate(addr, group.first().addressRaw, group.map { it.objectId })
                }
    }
}
