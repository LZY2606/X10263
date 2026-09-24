package heapforensics

import kotlinx.serialization.json.*

/**
 * Import layer: preserves the raw representation of every domain quantity.
 * Normalization is deferred to the derived layer; values that cannot be
 * converted stay in place with a diagnostic attached.
 */
object Importer {

    class Budget(val maxObjects: Int = 1_000_000, val maxEdges: Int = 4_000_000)

    private fun rawNumber(raw: JsonElement, unit: String, path: String, diags: MutableList<Diagnostic>): RawValue {
        val text = when (raw) {
            is JsonPrimitive -> raw.content
            else -> raw.toString()
        }
        val parsed = text.removePrefix("0x").toLongOrNull(16)?.takeIf { text.startsWith("0x") }
            ?: text.toLongOrNull()
        return if (parsed != null) {
            RawValue(raw = text, unit = unit, normalized = parsed.toDouble())
        } else {
            val msg = "cannot normalize '$text' as $unit"
            diags.add(Diagnostic(path, msg))
            RawValue(raw = text, unit = unit, normalized = null, diagnostic = msg)
        }
    }

    fun import(jsonText: String, snapshotId: String, budget: Budget = Budget()): AnalysisOutcome<HeapSnapshot> {
        val root: JsonObject = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            return AnalysisOutcome.Failed(
                FailureKind.FORMAT_CORRUPT,
                listOf(Diagnostic("$", "snapshot is not valid JSON object: ${e.message}"))
            )
        }

        val diags = mutableListOf<Diagnostic>()
        val parserEl = root["parser"]
        val parser = if (parserEl is JsonObject) {
            ParserVersion(
                parserEl["name"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                parserEl["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            )
        } else {
            diags.add(Diagnostic("$.parser", "missing parser version block"))
            ParserVersion("unknown", "unknown")
        }

        val capturedAt = root["capturedAt"]?.let { rawNumber(it, "epoch-ms", "$.capturedAt", diags) }
            ?: RawValue(raw = "", unit = "epoch-ms", normalized = null, diagnostic = "missing capturedAt")
                .also { diags.add(Diagnostic("$.capturedAt", "missing capture timestamp")) }

        val classesArr = root["classes"] as? JsonArray
            ?: return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$.classes", "missing classes array")))
        val objectsArr = root["objects"] as? JsonArray
            ?: return AnalysisOutcome.Failed(FailureKind.FORMAT_CORRUPT, listOf(Diagnostic("$.objects", "missing objects array")))
        val edgesArr = root["edges"] as? JsonArray ?: JsonArray(emptyList())
        val rootsArr = root["roots"] as? JsonArray ?: JsonArray(emptyList())
        val blocksArr = root["incompleteBlocks"] as? JsonArray ?: JsonArray(emptyList())

        if (objectsArr.size > budget.maxObjects || edgesArr.size > budget.maxEdges) {
            return AnalysisOutcome.Failed(
                FailureKind.BUDGET_EXHAUSTED,
                listOf(Diagnostic("$", "objects=${objectsArr.size} edges=${edgesArr.size} exceed budget objects=${budget.maxObjects} edges=${budget.maxEdges}"))
            )
        }

        val classes = classesArr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: run { diags.add(Diagnostic("$.classes[$i]", "class entry is not an object")); return@mapIndexedNotNull null }
            val id = o["classId"]?.jsonPrimitive?.contentOrNull
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            if (id == null || name == null) {
                diags.add(Diagnostic("$.classes[$i]", "class missing classId or name")); null
            } else GClass(
                classId = id, name = name,
                superClassId = o["superClassId"]?.jsonPrimitive?.contentOrNull,
                classLoaderId = o["classLoaderId"]?.jsonPrimitive?.contentOrNull,
            )
        }
        val classIds = classes.map { it.classId }.toSet()

        val objects = objectsArr.mapIndexedNotNull { i, el ->
            val p = "$.objects[$i]"
            val o = el as? JsonObject ?: run { diags.add(Diagnostic(p, "object entry is not an object")); return@mapIndexedNotNull null }
            val id = o["objectId"]?.jsonPrimitive?.contentOrNull
            if (id == null) { diags.add(Diagnostic(p, "object missing objectId")); return@mapIndexedNotNull null }
            val addrEl = o["address"]
            val addr = if (addrEl != null) rawNumber(addrEl, "hex-address", "$p.address", diags)
            else RawValue(raw = "", unit = "hex-address", normalized = null, diagnostic = "missing address")
                .also { diags.add(Diagnostic("$p.address", "missing address")) }
            val sizeEl = o["shallowSize"]
            val size = if (sizeEl != null) rawNumber(sizeEl, "bytes", "$p.shallowSize", diags)
            else RawValue(raw = "", unit = "bytes", normalized = null, diagnostic = "missing shallowSize")
                .also { diags.add(Diagnostic("$p.shallowSize", "missing shallowSize")) }
            val classId = o["classId"]?.jsonPrimitive?.contentOrNull ?: ""
            if (classId !in classIds) diags.add(Diagnostic("$p.classId", "references unknown class '$classId'"))
            GObject(
                objectId = id, address = addr, classId = classId, shallowSize = size,
                allocationSite = o["allocationSite"]?.jsonPrimitive?.contentOrNull,
                incomplete = o["incomplete"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        val edges = edgesArr.mapIndexedNotNull { i, el ->
            val p = "$.edges[$i]"
            val o = el as? JsonObject ?: run { diags.add(Diagnostic(p, "edge entry is not an object")); return@mapIndexedNotNull null }
            val from = o["from"]?.jsonPrimitive?.contentOrNull
            val to = o["to"]?.jsonPrimitive?.contentOrNull
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull?.let {
                runCatching { EdgeKind.valueOf(it) }.getOrNull()
            }
            if (from == null || to == null || kind == null) {
                diags.add(Diagnostic(p, "edge missing from/to/kind")); null
            } else GEdge(from, to, kind, o["label"]?.jsonPrimitive?.contentOrNull ?: "")
        }

        val roots = rootsArr.mapIndexedNotNull { i, el ->
            val p = "$.roots[$i]"
            val o = el as? JsonObject ?: run { diags.add(Diagnostic(p, "root entry is not an object")); return@mapIndexedNotNull null }
            val id = o["objectId"]?.jsonPrimitive?.contentOrNull
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull?.let {
                runCatching { RootKind.valueOf(it) }.getOrNull()
            } ?: RootKind.OTHER
            if (id == null) { diags.add(Diagnostic(p, "root missing objectId")); null }
            else GRoot(id, kind, o["description"]?.jsonPrimitive?.contentOrNull ?: "")
        }

        val blocks = blocksArr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: run { diags.add(Diagnostic("$.incompleteBlocks[$i]", "block is not an object")); return@mapIndexedNotNull null }
            IncompleteBlock(
                offset = o["offset"]?.jsonPrimitive?.longOrNull ?: 0L,
                length = o["length"]?.jsonPrimitive?.longOrNull ?: 0L,
                reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: "unspecified",
            )
        }

        // Semantic validation: dangling references are SEMANTIC_UNSOLVABLE, distinct
        // from format corruption and budget exhaustion.
        val objectIds = objects.map { it.objectId }.toSet()
        val semantic = mutableListOf<Diagnostic>()
        edges.forEachIndexed { i, e ->
            if (e.fromId !in objectIds) semantic.add(Diagnostic("$.edges[$i].from", "edge from unknown object '${e.fromId}'"))
            if (e.toId !in objectIds) semantic.add(Diagnostic("$.edges[$i].to", "edge to unknown object '${e.toId}'"))
        }
        roots.forEachIndexed { i, r ->
            if (r.objectId !in objectIds) semantic.add(Diagnostic("$.roots[$i]", "root references unknown object '${r.objectId}'"))
        }
        if (semantic.isNotEmpty()) {
            return AnalysisOutcome.Failed(FailureKind.SEMANTIC_UNSOLVABLE, semantic)
        }

        return AnalysisOutcome.Ok(
            HeapSnapshot(
                snapshotId = snapshotId, capturedAt = capturedAt, parser = parser,
                classes = classes, objects = objects, edges = edges, roots = roots,
                incompleteBlocks = blocks, diagnostics = diags,
            )
        )
    }
}
