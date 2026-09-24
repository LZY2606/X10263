package heapsleuth.ingest

import heapsleuth.model.*
import kotlinx.serialization.json.*

/**
 * Parse outcomes are three DISTINCT results, never conflated:
 *  - BudgetExhausted   : resource budget (maxObjects/maxEdges) ran out
 *  - FormatCorrupt     : input is not well-formed JSON / wrong shape
 *  - SemanticUnsolvable: well-formed but semantically contradictory (e.g. duplicate object ids)
 * Success may still carry diagnostics (incomplete input is preserved, not faked with defaults).
 */
sealed interface ParseResult {
    data class Success(val snapshot: HeapSnapshot, val diagnostics: List<Diagnostic>) : ParseResult
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : ParseResult
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ParseResult
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : ParseResult
}

object HeapSnapshotParser {
    const val FORMAT = "heap-sleuth-snapshot"
    const val SUPPORTED_PARSER_VERSION = "1.0"

    fun parse(text: String, params: AnalysisParams = AnalysisParams("v1")): ParseResult {
        val root: JsonObject = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ParseResult.FormatCorrupt(
                listOf(Diagnostic("$", "FORMAT_CORRUPT", "not well-formed JSON: ${e.message}"))
            )
        }
        val format = root["format"]?.jsonPrimitive?.contentOrNull
        if (format != FORMAT) {
            return ParseResult.FormatCorrupt(
                listOf(Diagnostic("$.format", "FORMAT_CORRUPT", "expected format='$FORMAT', got '${format ?: "<missing>"}'"))
            )
        }
        val diagnostics = mutableListOf<Diagnostic>()
        val parserVersion = root["parserVersion"]?.jsonPrimitive?.contentOrNull ?: run {
            diagnostics += Diagnostic("$.parserVersion", "MISSING_FIELD", "parserVersion missing; recorded as 'unknown'")
            "unknown"
        }
        val snapshotId = root["snapshotId"]?.jsonPrimitive?.contentOrNull
            ?: return ParseResult.FormatCorrupt(listOf(Diagnostic("$.snapshotId", "FORMAT_CORRUPT", "snapshotId is required")))

        fun arr(key: String): JsonArray {
            val el = root[key] ?: return JsonArray(emptyList())
            val a = el as? JsonArray
            if (a == null) diagnostics += Diagnostic("$.$key", "FORMAT_CORRUPT", "expected array, treated as empty")
            return a ?: JsonArray(emptyList())
        }

        val classes = mutableListOf<ClassMeta>()
        arr("classes").forEachIndexed { i, el ->
            val o = el as? JsonObject
            if (o == null) { diagnostics += Diagnostic("$.classes[$i]", "FORMAT_CORRUPT", "class entry is not an object; skipped"); return@forEachIndexed }
            val id = o["id"]?.jsonPrimitive?.contentOrNull
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            if (id == null || name == null) {
                diagnostics += Diagnostic("$.classes[$i]", "FORMAT_CORRUPT", "class requires id and name; skipped")
                return@forEachIndexed
            }
            val sizeRaw = o["instanceSizeBytes"]?.jsonPrimitive?.contentOrNull
            val size = sizeRaw?.toLongOrNull()
            if (sizeRaw != null && size == null) {
                diagnostics += Diagnostic("$.classes[$i].instanceSizeBytes", "UNCONVERTIBLE_VALUE", "value '$sizeRaw' kept raw; not converted")
            }
            classes += ClassMeta(
                id = id,
                classKey = o["classKey"]?.jsonPrimitive?.contentOrNull ?: id,
                name = name,
                superName = o["superName"]?.jsonPrimitive?.contentOrNull,
                instanceSizeBytes = size,
                instanceSizeRaw = sizeRaw
            )
        }

        val objects = mutableListOf<HeapObject>()
        arr("objects").forEachIndexed { i, el ->
            if (objects.size >= params.maxObjects) return@forEachIndexed
            val o = el as? JsonObject
            if (o == null) { diagnostics += Diagnostic("$.objects[$i]", "FORMAT_CORRUPT", "object entry is not an object; skipped"); return@forEachIndexed }
            val id = o["id"]?.jsonPrimitive?.contentOrNull
            val address = o["address"]?.jsonPrimitive?.contentOrNull
            if (id == null || address == null) {
                diagnostics += Diagnostic("$.objects[$i]", "FORMAT_CORRUPT", "object requires id and address; skipped")
                return@forEachIndexed
            }
            val sizeRaw = o["sizeBytes"]?.jsonPrimitive?.contentOrNull ?: "0"
            val size = sizeRaw.toLongOrNull()
            if (size == null) {
                diagnostics += Diagnostic("$.objects[$i].sizeBytes", "UNCONVERTIBLE_VALUE", "value '$sizeRaw' kept raw at original position; excluded from byte sums")
            }
            objects += HeapObject(
                id = id,
                address = address,
                classId = o["classId"]?.jsonPrimitive?.contentOrNull,
                sizeBytes = size,
                sizeRaw = sizeRaw,
                allocationSite = o["allocationSite"]?.jsonPrimitive?.contentOrNull,
                incomplete = o["incomplete"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
        val declaredObjects = (root["objects"] as? JsonArray)?.size ?: 0
        if (declaredObjects > params.maxObjects) {
            return ParseResult.BudgetExhausted(listOf(Diagnostic("$.objects", "BUDGET_EXHAUSTED",
                "object budget ${params.maxObjects} exhausted by $declaredObjects entries")))
        }

        val edges = mutableListOf<HeapEdge>()
        arr("edges").forEachIndexed { i, el ->
            if (edges.size >= params.maxEdges) return@forEachIndexed
            val o = el as? JsonObject
            val from = (o?.get("from") as? JsonPrimitive)?.contentOrNull
            val to = (o?.get("to") as? JsonPrimitive)?.contentOrNull
            val kindRaw = (o?.get("kind") as? JsonPrimitive)?.contentOrNull
            val kind = kindRaw?.let { runCatching { EdgeKind.valueOf(it) }.getOrNull() }
            if (o == null || from == null || to == null || kind == null) {
                diagnostics += Diagnostic("$.edges[$i]", "FORMAT_CORRUPT", "edge requires from/to and kind in {STRONG,WEAK,INTERNAL}; skipped")
                return@forEachIndexed
            }
            edges += HeapEdge(from, to, kind, o["name"]?.jsonPrimitive?.contentOrNull)
        }
        val declaredEdges = (root["edges"] as? JsonArray)?.size ?: 0
        if (declaredEdges > params.maxEdges) {
            return ParseResult.BudgetExhausted(listOf(Diagnostic("$.edges", "BUDGET_EXHAUSTED",
                "edge budget ${params.maxEdges} exhausted by $declaredEdges entries")))
        }

        val roots = mutableListOf<HeapRoot>()
        arr("roots").forEachIndexed { i, el ->
            val o = el as? JsonObject
            val obj = (o?.get("object") as? JsonPrimitive)?.contentOrNull
            val typeRaw = (o?.get("rootType") as? JsonPrimitive)?.contentOrNull
            val type = typeRaw?.let { runCatching { RootType.valueOf(it) }.getOrNull() }
            if (o == null || obj == null || type == null) {
                diagnostics += Diagnostic("$.roots[$i]", "FORMAT_CORRUPT", "root requires object and known rootType; skipped")
                return@forEachIndexed
            }
            roots += HeapRoot(obj, type)
        }

        val blocks = mutableListOf<IncompleteBlock>()
        arr("incompleteBlocks").forEachIndexed { i, el ->
            val o = el as? JsonObject
            val offset = (o?.get("offset") as? JsonPrimitive)?.contentOrNull
            if (o == null || offset == null) {
                diagnostics += Diagnostic("$.incompleteBlocks[$i]", "FORMAT_CORRUPT", "incomplete block requires offset; skipped")
                return@forEachIndexed
            }
            val lenRaw = o["lengthBytes"]?.jsonPrimitive?.contentOrNull ?: "0"
            val len = lenRaw.toLongOrNull()
            if (len == null) diagnostics += Diagnostic("$.incompleteBlocks[$i].lengthBytes", "UNCONVERTIBLE_VALUE", "value '$lenRaw' kept raw")
            blocks += IncompleteBlock(offset, len, lenRaw, o["reason"]?.jsonPrimitive?.contentOrNull ?: "unspecified")
        }

        // Semantic validation: contradictions that make the snapshot unsolvable.
        val semanticErrors = mutableListOf<Diagnostic>()
        val seenIds = mutableSetOf<String>()
        objects.forEachIndexed { i, obj ->
            if (!seenIds.add(obj.id)) {
                semanticErrors += Diagnostic("$.objects[$i].id", "SEMANTIC_UNSOLVABLE",
                    "duplicate object id '${obj.id}': identity conflict cannot be resolved")
            }
        }
        if (semanticErrors.isNotEmpty()) return ParseResult.SemanticUnsolvable(semanticErrors)

        // Soft semantic findings: preserved in place with diagnostics, never faked.
        val objIds = seenIds
        edges.forEachIndexed { i, e ->
            if (e.from !in objIds) diagnostics += Diagnostic("$.edges[$i].from", "DANGLING_EDGE", "edge from unknown object '${e.from}'; kept as dangling")
            if (e.to !in objIds) diagnostics += Diagnostic("$.edges[$i].to", "DANGLING_EDGE", "edge to unknown object '${e.to}'; kept as dangling")
        }
        roots.forEachIndexed { i, r ->
            if (r.objectId !in objIds) diagnostics += Diagnostic("$.roots[$i].object", "DANGLING_ROOT", "root references unknown object '${r.objectId}'; kept with diagnostic")
        }
        val classIds = classes.map { it.id }.toSet()
        objects.forEachIndexed { i, obj ->
            if (obj.classId != null && obj.classId !in classIds)
                diagnostics += Diagnostic("$.objects[$i].classId", "UNKNOWN_CLASS", "classId '${obj.classId}' not declared; raw value preserved")
        }
        val addrGroups = objects.groupBy { it.address }.filter { it.value.size > 1 }
        addrGroups.forEach { (addr, objs) ->
            diagnostics += Diagnostic("$.objects", "DUPLICATE_ADDRESS",
                "address $addr shared by objects ${objs.map { it.id }}: address is NOT a stable identity; ids kept distinct")
        }

        return ParseResult.Success(
            HeapSnapshot(snapshotId, parserVersion, classes, objects, edges, roots, blocks),
            diagnostics
        )
    }
}
