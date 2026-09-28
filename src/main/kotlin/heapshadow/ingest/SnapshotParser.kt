package heapshadow.ingest

import heapshadow.model.Diagnostic
import heapshadow.model.Outcome
import heapshadow.model.Quantity
import heapshadow.model.RawAllocationSiteRecord
import heapshadow.model.RawClassRecord
import heapshadow.model.RawEdgeRecord
import heapshadow.model.RawIncompleteBlockRecord
import heapshadow.model.RawObjectRecord
import heapshadow.model.RawRootRecord
import heapshadow.model.RawSnapshot
import heapshadow.model.Severity
import heapshadow.model.edgeKindFromRaw
import heapshadow.model.RootType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 解析器：负责把一个堆快照文件读入不可变原始层。
 *
 * 结果三分：
 *  - FormatCorrupt：JSON 无法解析，或记录类型错误（对象不是对象/数组等结构性问题）。
 *  - SemanticUnresolvable：结构可解析但身份冲突（同表内重复 objectId/classId/...）。
 * 字段缺失、单位不可转换、未知枚举等均作为记录级诊断保留，记录仍留在原始层。
 */
class SnapshotParser {

    private val compactJson = Json { prettyPrint = false }

    fun parse(jsonText: String, sourceFileName: String?): Outcome<ParsedSnapshot> {
        val root: JsonObject = try {
            val element = Json.parseToJsonElement(jsonText)
            element as? JsonObject
                ?: return Outcome.FormatCorrupt(listOf(Diagnostic(
                    "FORMAT_ROOT_NOT_OBJECT", Severity.ERROR,
                    "snapshot root must be a JSON object, got ${element::class.simpleName}")))
        } catch (e: Exception) {
            val position = extractPositionFromJsonException(jsonText, e)
            return Outcome.FormatCorrupt(listOf(Diagnostic(
                "FORMAT_INVALID_JSON", Severity.ERROR,
                "invalid JSON: ${e.message?.take(200)}", position = position)))
        }

        val diagnostics = mutableListOf<Diagnostic>()
        var fatalStructural = false

        fun arrayAt(key: String): JsonArray? {
            val node = root[key]
            if (node == null || node is JsonPrimitive) return JsonArray(emptyList())
            if (node !is JsonArray) {
                fatalStructural = true
                diagnostics += Diagnostic("FORMAT_WRONG_TYPE", Severity.ERROR,
                    "'$key' must be an array", position = "/$key")
                return JsonArray(emptyList())
            }
            return node
        }

        fun recordObject(element: kotlinx.serialization.json.JsonElement, base: String): JsonObject? {
            if (element is JsonObject) return element
            diagnostics += Diagnostic("FORMAT_RECORD_NOT_OBJECT", Severity.ERROR,
                "record at $base is ${if (element is JsonPrimitive) "a scalar" else "an array"}",
                position = base)
            return null
        }

        val snapshotId = root.strOrNull("snapshotId")
        if (snapshotId == null) {
            diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                "snapshot.snapshotId is required for stable identity", position = "/snapshotId")
        }

        val classes = mutableListOf<RawClassRecord>()
        val objects = mutableListOf<RawObjectRecord>()
        val edges = mutableListOf<RawEdgeRecord>()
        val roots = mutableListOf<RawRootRecord>()
        val sites = mutableListOf<RawAllocationSiteRecord>()
        val blocks = mutableListOf<RawIncompleteBlockRecord>()

        fun collectIdDupes(items: List<Pair<String, String>>, table: String, idLabel: String) {
            val seen = HashSet<String>()
            for ((id, pos) in items) {
                if (!seen.add(id)) {
                    diagnostics += Diagnostic("SEMANTIC_DUPLICATE_ID", Severity.ERROR,
                        "duplicate $idLabel '$id' in /$table; identity must be unique within a snapshot",
                        position = pos, recordId = id)
                }
            }
        }

        val classIdPositions = mutableListOf<Pair<String, String>>()
        arrayAt("classes")?.forEachIndexed { index, element ->
            val base = "/classes/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val classId = obj.strOrNull("classId")
            if (classId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "class record requires classId", position = "$base/classId")
            } else {
                classIdPositions += classId to base
                classes += RawClassRecord(
                    classId = classId,
                    name = obj.strOrNull("name") ?: "?",
                    classLoaderObjectId = obj.strOrNull("classLoaderObjectId"),
                    rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                    location = base,
                )
            }
        }

        val objectIdPositions = mutableListOf<Pair<String, String>>()
        arrayAt("objects")?.forEachIndexed { index, element ->
            val base = "/objects/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val objectId = obj.strOrNull("objectId")
            if (objectId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "object record requires objectId", position = "$base/objectId")
                return@forEachIndexed
            }
            objectIdPositions += objectId to base
            val (size, sizeDiagnostics) = QuantityParser.size(obj, "size", base, objectId)
            diagnostics += sizeDiagnostics
            objects += RawObjectRecord(
                objectId = objectId,
                classId = obj.strOrNull("classId"),
                addressRaw = obj.strOrNull("address"),
                size = size,
                allocationSiteId = obj.strOrNull("allocationSiteId"),
                complete = obj.boolOr("complete", default = true),
                rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                location = base,
            )
        }

        arrayAt("edges")?.forEachIndexed { index, element ->
            val base = "/edges/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val edgeId = obj.strOrNull("edgeId")
            if (edgeId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "edge record requires edgeId", position = "$base/edgeId")
                return@forEachIndexed
            }
            val kindRaw = obj.strOrNull("kind")
            if (kindRaw == null) {
                diagnostics += Diagnostic("EDGE_KIND_MISSING", Severity.ERROR,
                    "edge '$edgeId' has no kind (strong/weak/internal)", position = "$base/kind", recordId = edgeId)
            } else if (edgeKindFromRaw(kindRaw) == heapshadow.model.EdgeKind.UNKNOWN) {
                diagnostics += Diagnostic("EDGE_KIND_UNKNOWN", Severity.WARNING,
                    "edge '$edgeId' has unrecognized kind '$kindRaw'; kept raw, not used as a live edge",
                    position = "$base/kind", recordId = edgeId)
            }
            if (obj.strOrNull("fromObjectId") == null) {
                diagnostics += Diagnostic("EDGE_ENDPOINT_MISSING", Severity.ERROR,
                    "edge '$edgeId' has no fromObjectId", position = "$base/fromObjectId", recordId = edgeId)
            }
            if (obj.strOrNull("toObjectId") == null) {
                diagnostics += Diagnostic("EDGE_ENDPOINT_MISSING", Severity.ERROR,
                    "edge '$edgeId' has no toObjectId", position = "$base/toObjectId", recordId = edgeId)
            }
            edges += RawEdgeRecord(
                edgeId = edgeId,
                fromObjectId = obj.strOrNull("fromObjectId"),
                toObjectId = obj.strOrNull("toObjectId"),
                kindRaw = kindRaw,
                label = obj.strOrNull("label"),
                fieldName = obj.strOrNull("fieldName"),
                rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                location = base,
            )
        }

        val rootIdPositions = mutableListOf<Pair<String, String>>()
        arrayAt("roots")?.forEachIndexed { index, element ->
            val base = "/roots/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val rootId = obj.strOrNull("rootId")
            if (rootId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "root record requires rootId", position = "$base/rootId")
                return@forEachIndexed
            }
            rootIdPositions += rootId to base
            val rootTypeRaw = obj.strOrNull("rootType")
            if (rootTypeRaw == null) {
                diagnostics += Diagnostic("ROOT_TYPE_MISSING", Severity.ERROR,
                    "root '$rootId' has no rootType", position = "$base/rootType", recordId = rootId)
            } else if (RootType.fromRaw(rootTypeRaw) == RootType.UNKNOWN) {
                diagnostics += Diagnostic("ROOT_TYPE_UNKNOWN", Severity.WARNING,
                    "root '$rootId' has unrecognized rootType '$rootTypeRaw'; kept raw as OTHER/UNKNOWN",
                    position = "$base/rootType", recordId = rootId)
            }
            if (obj.strOrNull("objectId") == null) {
                diagnostics += Diagnostic("ROOT_TARGET_MISSING", Severity.ERROR,
                    "root '$rootId' has no objectId target", position = "$base/objectId", recordId = rootId)
            }
            roots += RawRootRecord(
                rootId = rootId,
                objectId = obj.strOrNull("objectId"),
                rootTypeRaw = rootTypeRaw,
                rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                location = base,
            )
        }

        arrayAt("allocationSites")?.forEachIndexed { index, element ->
            val base = "/allocationSites/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val siteId = obj.strOrNull("siteId")
            if (siteId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "allocation site requires siteId", position = "$base/siteId")
                return@forEachIndexed
            }
            val (line, lineDiag) = QuantityParser.line(obj, "line", base)
            diagnostics += lineDiag
            sites += RawAllocationSiteRecord(
                siteId = siteId,
                classId = obj.strOrNull("classId"),
                method = obj.strOrNull("method"),
                sourceFile = obj.strOrNull("sourceFile"),
                lineRaw = line?.raw ?: obj.strOrNull("line"),
                rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                location = base,
            )
        }

        arrayAt("incompleteBlocks")?.forEachIndexed { index, element ->
            val base = "/incompleteBlocks/$index"
            val obj = recordObject(element, base) ?: return@forEachIndexed
            val blockId = obj.strOrNull("blockId")
            if (blockId == null) {
                diagnostics += Diagnostic("FORMAT_MISSING_FIELD", Severity.ERROR,
                    "incomplete block requires blockId", position = "$base/blockId")
                return@forEachIndexed
            }
            val (offset, offsetDiag) = QuantityParser.offset(obj, "offset", base)
            val (length, lengthDiag) = QuantityParser.size(obj, "length", base, blockId)
            diagnostics += offsetDiag + lengthDiag
            blocks += RawIncompleteBlockRecord(
                blockId = blockId,
                offset = offset,
                length = length,
                coversIdPattern = obj.strOrNull("coversIdPattern"),
                reason = obj.strOrNull("reason"),
                rawJson = compactJson.encodeToString(JsonObject.serializer(), obj),
                location = base,
            )
        }

        collectIdDupes(classIdPositions, "classes", "classId")
        collectIdDupes(objectIdPositions, "objects", "objectId")
        val edgeIds = edges.map { it.edgeId to it.location }
        val rootIds = rootIdPositions
        collectIdDupes(edgeIds, "edges", "edgeId")
        collectIdDupes(rootIds, "roots", "rootId")
        collectIdDupes(sites.map { it.siteId to it.location }, "allocationSites", "siteId")
        collectIdDupes(blocks.map { it.blockId to it.location }, "incompleteBlocks", "blockId")

        if (fatalStructural) return Outcome.FormatCorrupt(diagnostics)

        val semanticErrors = diagnostics.filter {
            it.severity == Severity.ERROR && it.code.startsWith("SEMANTIC_")
        }
        val snapshot = RawSnapshot(
            snapshotId = snapshotId ?: "UNKNOWN@${sha256Hex(jsonText).take(8)}",
            formatVersion = root.strOrNull("formatVersion"),
            parserVersion = root.strOrNull("parserVersion"),
            capturedAt = root.strOrNull("capturedAt"),
            timeBasis = root.strOrNull("timeBasis"),
            classes = classes,
            objects = objects,
            edges = edges,
            roots = roots,
            allocationSites = sites,
            incompleteBlocks = blocks,
            rawJson = jsonText,
            sourceFileName = sourceFileName,
        )
        return if (semanticErrors.isNotEmpty()) Outcome.SemanticUnresolvable(semanticErrors)
        else Outcome.Ok(ParsedSnapshot(snapshot, diagnostics))
    }

    private fun extractPositionFromJsonException(text: String, e: Exception): String? {
        // kotlinx 异常信息通常带 "at offset: N"
        val offset = Regex("offset:\\s*(\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        var line = 1
        var col = 1
        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') { line++; col = 1 } else col++
        }
        return "line $line, column $col (char offset $offset)"
    }
}

/** 解析成功产物：原始快照 + 全部非致命诊断（未知枚举、不可转换单位等）。 */
data class ParsedSnapshot(val snapshot: RawSnapshot, val diagnostics: List<Diagnostic>)
