package heaplab.parse

import heaplab.model.*

internal class ParserState(
    private val source: String,
    private val budget: ParseBudget,
    private val byteCount: Long,
    private val sha: String,
) {
    private val objects = linkedMapOf<String, RawObject>()
    private val classes = linkedMapOf<String, RawClass>()
    private val edges = mutableListOf<RawEdge>()
    private val roots = mutableListOf<RawRoot>()
    private val sites = linkedMapOf<String, RawAllocationSite>()
    private val diagnostics = mutableListOf<Diagnostic>()
    private val incomplete = mutableListOf<IncompleteBlock>()
    private val fieldAccumulator = mutableMapOf<String, MutableList<Field>>()

    private var snapshotId: String? = null
    private var capturedAt: String? = null
    private var formatVersion: String? = null
    private var seenEnd = false
    private var partial: PartialBlock? = null

    private data class PartialBlock(
        val blockId: String,
        val kind: String,
        val startLine: Int,
        val raw: StringBuilder,
    )

    fun run(): ParseOutcome {
        val lines = source.split("\n")
        for ((idx0, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd('\r')
            val offset = lineOffset(lines, idx0)
            val lineNo = idx0 + 1
            if (line.isBlank()) continue

            if (partial != null) {
                if (line.trim() == "END") finishIncomplete(lineNo)
                else partial!!.raw.append('\n').append(line)
                continue
            }

            if (idx0 == 0) {
                val magic = parseMagic(line)
                if (magic == null) {
                    addDiag(
                        DiagnosticKind.FORMAT_CORRUPT, "BAD_MAGIC",
                        "first line must be '#!heap-snapshot v<version>'", lineNo, 1, offset,
                    )
                } else {
                    formatVersion = magic
                }
                continue
            }
            if (line.startsWith("#")) continue

            val head = line.substringBefore(' ').trim()
            val body = line.substringAfter(' ', "")
            when (head) {
                "SNAPSHOT" -> readSnapshot(body, lineNo, offset)
                "CLASS" -> readClass(body, lineNo, offset)
                "SITE" -> readSite(body, lineNo, offset)
                "ROOT" -> readRoot(body, lineNo, offset)
                "OBJECT" -> readObject(body, lineNo, offset)
                "PARTIAL_OBJECT", "PARTIAL_CLASS" ->
                    startPartial(head.removePrefix("PARTIAL_"), body, lineNo, line)
                "FIELD" -> readField(body, lineNo, offset)
                "EDGE" -> readEdge(body, lineNo, offset)
                "END" -> seenEnd = true
                else -> addDiag(
                    DiagnosticKind.FORMAT_CORRUPT, "UNKNOWN_KEYWORD",
                    "unknown record keyword '$head'", lineNo, 1, offset,
                )
            }
            if (objects.size > budget.maxObjects) {
                return ParseOutcome.BudgetExhausted(
                    listOf(
                        Diagnostic(
                            DiagnosticKind.BUDGET_EXHAUSTED, "BUDGET_OBJECTS",
                            "object count exceeds budget ${budget.maxObjects}",
                            lineNo, 1, offset,
                        ),
                    ),
                    byteCount, budget.maxBytes,
                )
            }
        }

        partial?.let { finishIncomplete(null) }
        return buildSuccess(lines.size)
    }

    private fun buildSuccess(totalLines: Int): ParseOutcome {
        val id = snapshotId
        if (id == null) {
            return ParseOutcome.FormatCorrupt(
                (diagnostics + Diagnostic(
                    DiagnosticKind.FORMAT_CORRUPT, "MISSING_SNAPSHOT_ID",
                    "snapshot id is required", 1, 1, 0L,
                )).sortedWith(posOrder),
            )
        }
        if (!seenEnd && incomplete.isEmpty()) {
            addDiag(
                DiagnosticKind.FORMAT_CORRUPT, "MISSING_END",
                "snapshot has no END marker and no declared partial block",
                totalLines, 1, source.length.toLong(),
            )
        }
        attachFields()
        resolveReferences()
        val ordered = diagnostics.sortedWith(posOrder)
        if (ordered.any { it.kind == DiagnosticKind.FORMAT_CORRUPT }) {
            return ParseOutcome.FormatCorrupt(ordered)
        }
        val accepted = ordered.none { it.kind == DiagnosticKind.SEMANTIC_UNSOLVABLE }
        val snap = ParsedSnapshot(
            snapshotId = id,
            capturedAt = capturedAt ?: "unknown",
            formatVersion = formatVersion ?: "unknown",
            parserName = PARSER_NAME,
            parserVersion = PARSER_VERSION,
            sourceSha256 = sha,
            sourceByteCount = byteCount,
            objects = objects.values.sortedBy { it.objectId },
            classes = classes.values.sortedBy { it.classId },
            edges = edges.sortedWith(
                compareBy({ it.fromObjectId }, { it.toObjectId }, { it.edgeId }),
            ),
            roots = roots.sortedBy { it.rootId },
            sites = sites.values.sortedBy { it.siteId },
            incomplete = incomplete.sortedBy { it.startedLine },
            diagnostics = ordered,
            accepted = accepted,
        )
        return if (!accepted) ParseOutcome.SemanticUnsolvable(ordered)
        else ParseOutcome.Success(snap)
    }

    private fun startPartial(kind: String, body: String, lineNo: Int, rawLine: String) {
        val kv = parseKv(body)
        val blockId = (kv["id"] ?: "?")
        val diag = Diagnostic(
            DiagnosticKind.WARNING, "INCOMPLETE_BLOCK",
            "incomplete $kind block '$blockId' was preserved but excluded from derivation",
            lineNo, 1, 0L, blockId = blockId,
        )
        partial = PartialBlock(blockId, kind, lineNo, StringBuilder(rawLine))
        incomplete.add(
            IncompleteBlock(
                blockId = blockId,
                kind = kind,
                startedLine = lineNo,
                endedLine = null,
                rawText = rawLine,
                diagnostic = diag,
            ),
        )
        addDiag(diag)
    }

    private fun finishIncomplete(endLine: Int?) {
        val p = partial ?: return
        val idx = incomplete.indexOfLast { it.blockId == p.blockId && it.startedLine == p.startLine }
        if (idx >= 0) {
            val old = incomplete[idx]
            incomplete[idx] = old.copy(endedLine = endLine, rawText = p.raw.toString())
        }
        partial = null
    }

    private fun readSnapshot(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "SNAPSHOT") ?: return
        if (snapshotId != null) {
            addDiag(
                DiagnosticKind.SEMANTIC_UNSOLVABLE, "DUP_SNAPSHOT",
                "more than one SNAPSHOT record", lineNo, 1, offset,
            )
            return
        }
        snapshotId = id
        capturedAt = kv["captured_at"] ?: kv["captured"]
    }

    private fun readClass(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "CLASS") ?: return
        val name = requireKv(kv, "name", lineNo, offset, "CLASS") ?: return
        if (classes.containsKey(id)) {
            addSemantic(
                "DUP_CLASS_ID", "duplicate class id '$id'", lineNo, offset, blockId = id,
            )
            return
        }
        classes[id] = RawClass(
            classId = id,
            name = name,
            superClassId = kv["super"],
            classLoaderId = kv["loader"],
            nameAtCapture = name,
        )
    }

    private fun readSite(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "SITE") ?: return
        val location = kv["location"] ?: "<unknown>"
        val line = kv["line"]?.toIntOrNull()
        if (kv["line"] != null && line == null) {
            addWarning(
                "BAD_SITE_LINE", "site line is not an integer: ${kv["line"]}",
                lineNo, offset, blockId = id, fieldKey = "line", rawValue = kv["line"],
            )
        }
        sites[id] = RawAllocationSite(siteId = id, location = location, line = line)
    }

    private fun readRoot(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "ROOT") ?: return
        val category = requireKv(kv, "category", lineNo, offset, "ROOT") ?: return
        val objectId = requireKv(kv, "object", lineNo, offset, "ROOT") ?: return
        if (roots.any { it.rootId == id }) {
            addSemantic("DUP_ROOT_ID", "duplicate root id '$id'", lineNo, offset, blockId = id)
            return
        }
        if (category !in ROOT_CATEGORIES) {
            addWarning(
                "UNKNOWN_ROOT_CATEGORY",
                "unknown root category '$category', preserved verbatim",
                lineNo, offset, blockId = id, fieldKey = "category", rawValue = category,
            )
        }
        roots.add(RawRoot(rootId = id, category = category, objectId = objectId))
    }

    private fun readObject(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "OBJECT") ?: return
        val address = requireKv(kv, "address", lineNo, offset, "OBJECT") ?: return
        val classId = requireKv(kv, "class", lineNo, offset, "OBJECT") ?: return
        val sizeRaw = requireKv(kv, "size", lineNo, offset, "OBJECT") ?: return
        val unitRaw = kv["unit"] ?: ""
        if (objects.containsKey(id)) {
            addSemantic(
                "DUP_OBJECT_ID", "duplicate object id '$id'", lineNo, offset, blockId = id,
            )
            return
        }
        val objDiags = mutableListOf<Diagnostic>()
        if (sizeRaw.toLongOrNull() == null) {
            val d = Diagnostic(
                DiagnosticKind.WARNING, "UNCONVERTIBLE_SIZE",
                "size '$sizeRaw' cannot be normalized; raw value retained",
                lineNo, columnOf(body, "size"), offset + columnOf(body, "size"),
                blockId = id, fieldKey = "size", rawValue = sizeRaw,
            )
            objDiags.add(d); addDiag(d)
        }
        if (addressesSeen(address)) {
            val d = Diagnostic(
                DiagnosticKind.WARNING, "DUP_ADDRESS",
                "address '$address' reused by object '$id'; address is not a stable identity",
                lineNo, columnOf(body, "address"), offset + columnOf(body, "address"),
                blockId = id, fieldKey = "address", rawValue = address,
            )
            objDiags.add(d); addDiag(d)
        }
        registerAddress(address)
        objects[id] = RawObject(
            objectId = id,
            address = address,
            classId = classId,
            sizeRaw = sizeRaw,
            unitRaw = unitRaw,
            siteId = kv["site"],
            diagnostics = objDiags,
        )
    }

    private fun readField(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val objectId = requireKv(kv, "object", lineNo, offset, "FIELD") ?: return
        val key = requireKv(kv, "key", lineNo, offset, "FIELD") ?: return
        val value = kv["value"] ?: ""
        fieldAccumulator.getOrPut(objectId) { mutableListOf() }.add(Field(key, value))
    }

    private fun readEdge(body: String, lineNo: Int, offset: Long) {
        val kv = parseKv(body)
        val id = requireKv(kv, "id", lineNo, offset, "EDGE") ?: return
        val kind = requireKv(kv, "kind", lineNo, offset, "EDGE") ?: return
        val from = requireKv(kv, "from", lineNo, offset, "EDGE") ?: return
        val to = requireKv(kv, "to", lineNo, offset, "EDGE") ?: return
        if (edges.any { it.edgeId == id }) {
            addSemantic("DUP_EDGE_ID", "duplicate edge id '$id'", lineNo, offset, blockId = id)
            return
        }
        if (kind !in EDGE_KINDS) {
            addDiag(
                DiagnosticKind.FORMAT_CORRUPT, "BAD_EDGE_KIND",
                "edge kind must be one of $EDGE_KINDS but was '$kind'",
                lineNo, columnOf(body, "kind"), offset + columnOf(body, "kind"),
                blockId = id, fieldKey = "kind", rawValue = kind,
            )
            return
        }
        edges.add(RawEdge(edgeId = id, kind = kind, fromObjectId = from, toObjectId = to, label = kv["label"]))
    }

    private fun attachFields() {
        for ((oid, fields) in fieldAccumulator) {
            val existing = objects[oid]
            if (existing == null) {
                addDiag(
                    DiagnosticKind.WARNING, "DANGLING_FIELD",
                    "FIELD references unknown object '$oid'; retained on diagnostics, object not invented",
                    0, 0, 0L, blockId = oid,
                )
                continue
            }
            objects[oid] = existing.copy(fields = fields.sortedBy { it.key })
        }
    }

    private fun resolveReferences() {
        for (r in roots.toList()) {
            if (!objects.containsKey(r.objectId)) {
                addDiag(
                    DiagnosticKind.SEMANTIC_UNSOLVABLE, "DANGLING_ROOT",
                    "root '${r.rootId}' points at missing object '${r.objectId}'",
                    0, 0, 0L, blockId = r.rootId, fieldKey = "object", rawValue = r.objectId,
                )
            }
        }
        for (c in classes.values) {
            if (c.superClassId != null && !classes.containsKey(c.superClassId)) {
                addWarning(
                    "DANGLING_SUPERCLASS",
                    "class '${c.classId}' super '${c.superClassId}' unknown",
                    0, 0L, blockId = c.classId, fieldKey = "super", rawValue = c.superClassId,
                )
            }
            if (c.classLoaderId != null && !objects.containsKey(c.classLoaderId)) {
                addWarning(
                    "DANGLING_LOADER",
                    "class '${c.classId}' loader '${c.classLoaderId}' unknown",
                    0, 0L, blockId = c.classId, fieldKey = "loader", rawValue = c.classLoaderId,
                )
            }
        }
        for (o in objects.values.toList()) {
            if (!classes.containsKey(o.classId)) {
                addDiag(
                    DiagnosticKind.SEMANTIC_UNSOLVABLE, "UNKNOWN_CLASS_REF",
                    "object '${o.objectId}' references unknown class '${o.classId}'",
                    0, 0, 0L, blockId = o.objectId, fieldKey = "class", rawValue = o.classId,
                )
            }
            if (o.siteId != null && !sites.containsKey(o.siteId)) {
                addWarning(
                    "DANGLING_SITE",
                    "object '${o.objectId}' allocation site '${o.siteId}' unknown",
                    0, 0L, blockId = o.objectId, fieldKey = "site", rawValue = o.siteId,
                )
            }
        }
        for (e in edges) {
            if (!objects.containsKey(e.fromObjectId) || !objects.containsKey(e.toObjectId)) {
                addDiag(
                    DiagnosticKind.SEMANTIC_UNSOLVABLE, "DANGLING_EDGE",
                    "edge '${e.edgeId}' endpoint missing (from='${e.fromObjectId}', to='${e.toObjectId}')",
                    0, 0, 0L, blockId = e.edgeId,
                )
            }
        }
    }

    private val addressSet = mutableSetOf<String>()
    private fun addressesSeen(a: String) = addressSet.contains(a)
    private fun registerAddress(a: String) { addressSet.add(a) }

    private fun addDiag(d: Diagnostic) { diagnostics.add(d) }
    private fun addSemantic(code: String, msg: String, line: Int, offset: Long, blockId: String?) =
        addDiag(Diagnostic(DiagnosticKind.SEMANTIC_UNSOLVABLE, code, msg, line, 1, offset, blockId))
    private fun addWarning(
        code: String, msg: String, line: Int, offset: Long,
        blockId: String? = null, fieldKey: String? = null, rawValue: String? = null,
    ) = addDiag(
        Diagnostic(DiagnosticKind.WARNING, code, msg, line, 1, offset, blockId, fieldKey, rawValue),
    )

    private fun requireKv(kv: Map<String, String>, key: String, lineNo: Int, offset: Long, rec: String): String? {
        val v = kv[key]
        if (v == null) {
            addDiag(
                DiagnosticKind.FORMAT_CORRUPT, "MISSING_FIELD",
                "$rec record missing required key '$key'", lineNo, 1, offset, fieldKey = key,
            )
            return null
        }
        return v
    }

    companion object {
        val ROOT_CATEGORIES = setOf(
            "STICKY", "JNI_LOCAL", "JNI_GLOBAL", "SYSTEM_CLASS", "THREAD", "MONITOR", "UNKNOWN",
        )
        val EDGE_KINDS = setOf("STRONG", "WEAK", "INTERNAL")

        val posOrder = compareBy<Diagnostic>({ it.line }, { it.column }, { it.code })

        private val kvRegex = Regex("""(\w+)=("((?:[^"\\]|\\.)*)"|\S+)""")

        fun parseKv(body: String): Map<String, String> =
            kvRegex.findAll(body).associate { mr ->
                val raw = mr.groupValues[2]
                val value = if (raw.startsWith("\"")) {
                    raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                } else raw
                mr.groupValues[1] to value
            }

        fun columnOf(body: String, key: String): Int {
            val m = Regex("""(?:^|\s)${Regex.escape(key)}=""").find(body) ?: return 1
            return m.range.first + 2
        }

        fun lineOffset(lines: List<String>, idx0: Int): Long {
            var off = 0L
            for (i in 0 until idx0) off += lines[i].length + 1
            return off
        }

        fun parseMagic(line: String): String? {
            val expectedPrefix = "#!heap-snapshot"
            if (!line.startsWith(expectedPrefix)) return null
            val rest = line.removePrefix(expectedPrefix).trim()
            val version = rest.removePrefix("v").trim()
            return version.ifEmpty { null }
        }
    }
}
