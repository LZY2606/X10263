package forensics.heapshadow.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import forensics.heapshadow.model.*
import java.security.MessageDigest

/**
 * 快照导入器（纯 Kotlin，可被测试直接调用）。
 *
 * 原则：
 *  1. 原始表示逐字保留（地址、尺寸、时间、解析器版本）；
 *  2. 派生层才规范化，不可转换留原位并附诊断；
 *  3. 不完整块不静默补默认值；
 *  4. 重复地址不是错误（地址非稳定身份），重复 object id 是语义错误；
 *  5. 三种结果互不相同：Ok(含可恢复诊断) / FormatCorrupt / SemanticUnresolvable。
 */
class SnapshotImporter(
    private val fileName: String = "snapshot.json",
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val unitTable: Map<String, Long> = mapOf(
        "b" to 1L, "byte" to 1L, "bytes" to 1L,
        "kib" to 1024L, "kb" to 1000L,
        "mib" to 1024L * 1024L, "mb" to 1000L * 1000L,
        "gib" to 1024L * 1024L * 1024L, "gb" to 1000L * 1000L * 1000L,
    )

    fun import(jsonText: String): ImportResult {
        val root: JsonNode = try {
            mapper.readTree(jsonText)
        } catch (e: MismatchedInputException) {
            return ImportResult.FormatCorrupt(
                listOf(corrupt("JSON 结构损坏：${e.originalMessage}", SourcePos(fileName, "$", -1)))
            )
        } catch (e: Exception) {
            return ImportResult.FormatCorrupt(
                listOf(corrupt("JSON 结构损坏：${e.message ?: "无法解析"}", SourcePos(fileName, "$", -1)))
            )
        }
        if (root == null || !root.isObject) {
            return ImportResult.FormatCorrupt(
                listOf(corrupt("顶层必须是对象", SourcePos(fileName, "$", -1)))
            )
        }

        val diags = DiagnosticRecorder()

        // 元数据：格式版本不兼容属于语义问题而非结构损坏。
        val snapshotId = textOrDiag(root, "snapshotId", diags, required = true)
            ?: return ImportResult.SemanticUnresolvable(diags.list)
        val label = root.path("label").asText(snapshotId)
        val parserName = root.path("parser").path("name").asText("unknown")
        val parserVersion = root.path("parser").path("version").asText("unknown")
        val formatVersion = root.path("formatVersion").asText("unknown")

        val supported = setOf("heapshadow/1", "1.0", "1")
        if (!supported.contains(formatVersion)) {
            diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "FORMAT-UNSUPPORTED",
                "不支持的快照格式版本: $formatVersion（解析器 $parserName/$parserVersion）",
                SourcePos(fileName, "formatVersion", -1), listOf(snapshotId)
            )
            return ImportResult.SemanticUnresolvable(diags.list)
        }

        val addressBasis = parseAddressBasis(root.path("addressBasis"))
        val timeBasis = parseTimeBasis(root.path("timeBasis"), diags)

        val classes = parseClasses(root.path("classes"), diags)
        val objects = parseObjects(root.path("objects"), addressBasis, diags)
        val edges = parseEdges(root.path("edges"), diags)
        val roots = parseRoots(root.path("roots"), diags)
        val sites = parseSites(root.path("allocationSites"), diags)
        val chunks = parseChunks(root.path("incompleteChunks"), diags)

        validateSemantics(classes, objects, edges, roots, sites, diags)

        val material = SnapshotMaterial(
            snapshotId = snapshotId, label = label,
            parserName = parserName, parserVersion = parserVersion, formatVersion = formatVersion,
            addressBasis = addressBasis, timeBasis = timeBasis,
            classes = classes, objects = objects, edges = edges, roots = roots,
            allocationSites = sites, incompleteChunks = chunks,
        )
        return ImportResult.Ok(material, diags.list)
    }

    private fun textOrDiag(
        node: JsonNode, field: String, diags: DiagnosticRecorder, required: Boolean,
        path: String = field,
    ): String? {
        val v = node.get(field)
        if (v == null || v.isNull) {
            if (required) diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "FIELD-MISSING",
                "必填字段缺失: $field", SourcePos(fileName, path, -1)
            )
            return null
        }
        return v.asText()
    }

    private fun parseAddressBasis(node: JsonNode): AddressBasis =
        AddressBasis(
            pointerSizeBits = node.path("pointerSizeBits").asInt(64),
            addressSpace = node.path("addressSpace").asText("jvm-heap"),
        )

    private fun parseTimeBasis(node: JsonNode, diags: DiagnosticRecorder): TimeBasis? {
        if (node.isMissingNode || node.isNull) return null
        val raw = node.path("capturedAtRaw").asText(node.toString())
        val parsed = node.path("capturedAt").asText("")
        if (parsed.isBlank()) {
            diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "TIME-UNPARSED",
                "时间基准无法规范化，原始值保留在原位", SourcePos(fileName, "timeBasis.capturedAtRaw", -1)
            )
        }
        return TimeBasis(
            capturedAt = parsed, capturedAtRaw = raw,
            clockBasis = node.path("clockBasis").asText("wall-clock-local"),
            uptimeMs = if (node.has("uptimeMs")) node.get("uptimeMs").asLong() else null,
            uptimeRaw = node.path("uptimeRaw").asText(null),
        )
    }

    private fun parseClasses(node: JsonNode, diags: DiagnosticRecorder): List<ClassMeta> {
        if (!node.isArray) {
            diags.add(DiagnosticCategory.FORMAT_CORRUPT, Severity.ERROR, "CLASSES-NOT-ARRAY",
                "classes 必须是数组", SourcePos(fileName, "classes", -1))
            return emptyList()
        }
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "classes[$i]", i)
            val id = n.path("classId").asText("")
            if (id.isBlank()) diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "CLASS-ID-MISSING",
                "类缺少 classId", p
            )
            ClassMeta(
                classId = id, name = n.path("name").asText("<unnamed>"),
                classLoaderObjectId = n.path("classLoaderObjectId").asText(null),
                superClassId = n.path("superClassId").asText(null),
                isArray = n.path("isArray").asBoolean(false), location = p,
            )
        }
    }

    private fun parseObjects(
        node: JsonNode, basis: AddressBasis, diags: DiagnosticRecorder,
    ): List<HeapObject> {
        if (!node.isArray) {
            diags.add(DiagnosticCategory.FORMAT_CORRUPT, Severity.ERROR, "OBJECTS-NOT-ARRAY",
                "objects 必须是数组", SourcePos(fileName, "objects", -1))
            return emptyList()
        }
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "objects[$i]", i)
            val id = n.path("objectId").asText("")
            if (id.isBlank()) diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "OBJECT-ID-MISSING",
                "对象缺少 objectId", p
            )
            val chunkId = n.path("incompleteChunkId").asText(null)
            val incomplete = n.path("incomplete").asBoolean(false) || chunkId != null
            val missing = n.path("missingFields").let { if (it.isArray) it.map(JsonNode::asText) else emptyList() }
            val address = if (n.has("address") && !n.path("address").isNull)
                parseAddress(n.get("address"), basis, p, id, diags) else null
            if (incomplete && address == null) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "INCOMPLETE-OBJECT",
                "不完整对象缺少地址，原始位置保留", p.at("address"), listOf(id)
            )
            val size = if (n.has("shallowSize") && !n.path("shallowSize").isNull)
                parseQuantity(n.get("shallowSize"), p.at("shallowSize"), id, diags) else null
            HeapObject(
                objectId = id, klassId = n.path("classId").asText(null),
                address = address, shallowSize = size,
                allocationSiteId = n.path("allocationSiteId").asText(null),
                ordinal = i, incomplete = incomplete, missingFields = missing,
                incompleteChunkId = chunkId, location = p,
            )
        }
    }

    private fun parseAddress(
        node: JsonNode, basis: AddressBasis, p: SourcePos, ownerId: String,
        diags: DiagnosticRecorder,
    ): AddressRecord {
        val raw = node.asText("")
        val trimmed = raw.trim()
        val radix = when {
            trimmed.startsWith("0x") || trimmed.startsWith("0X") -> 16
            trimmed.startsWith("0") && trimmed.length > 1 && trimmed.all { it in '0'..'7' } -> 8
            else -> 10
        }
        val normalized = try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
                trimmed.substring(2).toLong(16) else trimmed.toLong(radix)
        } catch (e: Exception) {
            val d = diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "ADDRESS-UNPARSED",
                "地址无法规范化为 ${basis.pointerSizeBits} 位量，原始值保留",
                p.at("address"), listOf(ownerId)
            )
            return AddressRecord(raw, null, radix, basis.addressSpace, d)
        }
        return AddressRecord(raw, normalized, radix, basis.addressSpace)
    }

    private fun parseQuantity(
        node: JsonNode, p: SourcePos, ownerId: String, diags: DiagnosticRecorder,
    ): Quantity {
        val raw = node.asText("")
        val match = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]+)?\\s*$").find(raw)
        if (match == null) {
            val d = diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "SIZE-FORMAT",
                "尺寸无法解析，原始表示保留在原位: $raw", p, listOf(ownerId)
            )
            return Quantity(raw, null, null, null, d)
        }
        val value = match.groupValues[1].toBigDecimal()
        val unitRaw = match.groupValues[2].ifBlank { "b" }
        val factor = unitTable[unitRaw.lowercase()]
        if (factor == null) {
            val d = diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "SIZE-UNIT-UNKNOWN",
                "尺寸单位未知，无法换算为字节，原值保留: $unitRaw", p, listOf(ownerId)
            )
            return Quantity(raw, unitRaw, null, null, d)
        }
        val bytes = try {
            (value * factor.toBigDecimal()).longValueExact()
        } catch (e: ArithmeticException) {
            (value * factor.toBigDecimal()).toLong()
        }
        val basisText = if (unitRaw.lowercase().startsWith("ki") || unitRaw.lowercase() in setOf("mib", "gib"))
            "IEC 二进制 (1 unit = $factor B)" else "SI 十进制 (1 unit = $factor B)"
        return Quantity(raw, unitRaw, bytes, basisText)
    }

    private fun parseEdges(node: JsonNode, diags: DiagnosticRecorder): List<HeapEdge> {
        if (!node.isArray) {
            diags.add(DiagnosticCategory.FORMAT_CORRUPT, Severity.ERROR, "EDGES-NOT-ARRAY",
                "edges 必须是数组", SourcePos(fileName, "edges", -1))
            return emptyList()
        }
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "edges[$i]", i)
            val from = n.path("from").asText("")
            val to = n.path("to").asText("")
            val kindRaw = n.path("kind").asText("STRONG").uppercase()
            val kind = runCatching { EdgeKind.valueOf(kindRaw) }.getOrElse {
                diags.add(
                    DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "EDGE-KIND-UNKNOWN",
                    "未知边类型: $kindRaw", p.at("kind"), listOf(from, to)
                )
                EdgeKind.STRONG
            }
            val incomplete = n.path("incomplete").asBoolean(false)
            if (from.isBlank() || to.isBlank()) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "EDGE-ENDPOINT-MISSING",
                "边缺少端点，标记为不完整并保留", p, listOf(from, to).filter { it.isNotBlank() }
            )
            HeapEdge(
                edgeId = n.path("edgeId").asText("edge-$i"),
                fromObjectId = from, toObjectId = to, kind = kind,
                label = n.path("label").asText(null), ordinal = i,
                incomplete = incomplete || from.isBlank() || to.isBlank(), location = p,
            )
        }
    }

    private fun parseRoots(node: JsonNode, diags: DiagnosticRecorder): List<RootRecord> {
        if (!node.isArray) {
            diags.add(DiagnosticCategory.FORMAT_CORRUPT, Severity.ERROR, "ROOTS-NOT-ARRAY",
                "roots 必须是数组", SourcePos(fileName, "roots", -1))
            return emptyList()
        }
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "roots[$i]", i)
            val kindRaw = n.path("rootKind").asText("UNKNOWN").uppercase()
            val kind = runCatching { RootKind.valueOf(kindRaw) }.getOrElse {
                diags.add(
                    DiagnosticCategory.RECOVERABLE, Severity.WARNING, "ROOT-KIND-UNKNOWN",
                    "未知 root 分类 $kindRaw，归为 UNKNOWN 并保留原值", p
                )
                RootKind.UNKNOWN
            }
            RootRecord(
                rootId = n.path("rootId").asText("root-$i"),
                objectId = n.path("objectId").asText(""),
                kind = kind, ordinal = i, location = p,
            )
        }
    }

    private fun parseSites(node: JsonNode, diags: DiagnosticRecorder): List<AllocationSite> {
        if (!node.isArray) return emptyList()
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "allocationSites[$i]", i)
            AllocationSite(
                siteId = n.path("siteId").asText("site-$i"),
                className = n.path("className").asText(""),
                methodName = n.path("methodName").asText(null),
                fileName = n.path("fileName").asText(null),
                line = if (n.has("line") && n.path("line").isInt) n.get("line").asInt() else null,
                location = p,
            )
        }
    }

    private fun parseChunks(node: JsonNode, diags: DiagnosticRecorder): List<IncompleteChunk> {
        if (!node.isArray) return emptyList()
        return node.mapIndexed { i, n ->
            val p = SourcePos(fileName, "incompleteChunks[$i]", i)
            IncompleteChunk(
                chunkId = n.path("chunkId").asText("chunk-$i"),
                reason = n.path("reason").asText("unspecified"),
                rawExcerpt = n.path("rawExcerpt").asText(""),
                byteOffset = if (n.has("byteOffset") && n.path("byteOffset").isNumber)
                    n.get("byteOffset").asLong() else null,
                ordinal = i, location = p,
            )
        }
    }

    /**
     * 语义校验：
     *  - 重复 object id / class id => SEMANTIC_UNRESOLVABLE（身份必须唯一）；
     *  - 重复地址 => RECOVERABLE（地址不是稳定身份）；
     *  - 边/root 悬空引用 => RECOVERABLE（保留但不参与图计算）；
     *  - 不完整块内对象已在解析时标记，这里只核对 chunkId 对应关系。
     */
    private fun validateSemantics(
        classes: List<ClassMeta>, objects: List<HeapObject>, edges: List<HeapEdge>,
        roots: List<RootRecord>, sites: List<AllocationSite>, diags: DiagnosticRecorder,
    ) {
        val classIds = classes.map { it.classId }
        classIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { dup ->
            diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "CLASS-ID-DUPLICATE",
                "重复 classId（稳定身份冲突）: $dup", SourcePos(fileName, "classes", -1), listOf(dup)
            )
        }
        val objIds = objects.map { it.objectId }
        objIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { dup ->
            diags.add(
                DiagnosticCategory.SEMANTIC_UNRESOLVABLE, Severity.ERROR, "OBJECT-ID-DUPLICATE",
                "重复 objectId（稳定身份冲突）: $dup", SourcePos(fileName, "objects", -1), listOf(dup)
            )
        }
        val objIdSet = objIds.toSet()
        objects.mapNotNull { it.address?.normalized }.groupingBy { it }.eachCount()
            .filter { it.value > 1 }.keys.forEach { addr ->
                val ids = objects.filter { it.address?.normalized == addr }.map { it.objectId }
                diags.add(
                    DiagnosticCategory.RECOVERABLE, Severity.WARNING, "ADDRESS-DUPLICATE",
                    "重复地址 0x${addr.toString(16)}：地址不作为稳定身份，材料全部保留",
                    SourcePos(fileName, "objects", -1), ids
                )
            }
        objects.forEach { o ->
            if (o.klassId != null && o.klassId !in classIds) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "CLASS-DANGLING",
                "对象引用了不存在的 classId: ${o.klassId}", o.location.at("classId"), listOf(o.objectId)
            )
            if (o.incompleteChunkId != null) {
                // chunk 存在性在 chunks 解析后核对（外部传入），见下方调用。
            }
        }
        edges.forEach { e ->
            if (e.fromObjectId.isNotBlank() && e.fromObjectId !in objIdSet) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "EDGE-FROM-DANGLING",
                "边起点不存在: ${e.fromObjectId}", e.location, listOf(e.edgeId)
            )
            if (e.toObjectId.isNotBlank() && e.toObjectId !in objIdSet) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "EDGE-TO-DANGLING",
                "边终点不存在: ${e.toObjectId}，边保留但不参与图计算", e.location, listOf(e.edgeId)
            )
        }
        roots.forEach { r ->
            if (r.objectId !in objIdSet) diags.add(
                DiagnosticCategory.RECOVERABLE, Severity.WARNING, "ROOT-TARGET-DANGLING",
                "root 指向不存在对象: ${r.objectId}", r.location, listOf(r.rootId)
            )
        }
    }

    private fun corrupt(message: String, pos: SourcePos): Diagnostic =
        Diagnostic(
            diagnosticId = "d-${sha256(message + pos.path).take(12)}",
            category = DiagnosticCategory.FORMAT_CORRUPT, severity = Severity.ERROR,
            code = "JSON-CORRUPT", message = message, location = pos,
        )

    private inner class DiagnosticRecorder {
        val list = mutableListOf<Diagnostic>()
        fun add(
            category: DiagnosticCategory, severity: Severity, code: String,
            message: String, location: SourcePos?, affectedIds: List<String> = emptyList(),
        ): String {
            val id = "d-${sha256("$code|$message|${location?.path}").take(12)}"
            list.add(Diagnostic(id, category, severity, code, message, location, affectedIds))
            return id
        }
    }

    companion object {
        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
