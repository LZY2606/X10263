package forensics.parse

import forensics.model.*

/** Three structurally different outcomes. They must never collapse into one. */
sealed interface ParseOutcome {
    data class Ok(val snapshot: HeapSnapshot) : ParseOutcome
    data class Corrupt(val diagnostic: ParseDiagnostic) : ParseOutcome
    data class BudgetExhausted(val diagnostic: ParseDiagnostic, val consumedLines: Int) : ParseOutcome
}

class Budget(
    val maxLines: Int = 200_000,
    val maxObjects: Int = 100_000,
    val maxEdges: Int = 500_000,
)

class HsfParser(private val budget: Budget = Budget()) {

    fun parse(text: String): ParseOutcome {
        val lines = text.split("\r\n", "\n", "\r")
        var lineNo = 0
        fun pos(block: String? = null) = Position(lineNo, block)
        fun fail(code: String, msg: String, block: String?, token: String? = null): ParseOutcome.Corrupt =
            ParseOutcome.Corrupt(ParseDiagnostic(code, msg, pos(block), token))

        var snapId: String? = null
        var parserVersion: String? = null
        var formatVersion: String? = null
        var capturedAt: String? = null
        var timeBase: String? = null
        var addressBase: String? = null
        var unitBase: String = "bytes"

        val classes = mutableListOf<HeapClass>()
        val objects = mutableListOf<HeapObject>()
        val edges = mutableListOf<HeapEdge>()
        val roots = mutableListOf<HeapRoot>()
        val sites = mutableListOf<AllocationSite>()
        val incomplete = mutableListOf<IncompleteBlock>()
        val diags = mutableListOf<ParseDiagnostic>()

        var seq = 0
        fun sid(prefix: String) = "${prefix}-${"%05d".format(++seq)}"

        var block: String? = null
        var blockStart = 0
        val blockRaw = StringBuilder()
        var currentClassId: String? = null
        var currentSiteId: String? = null

        fun closeIncomplete(endLine: Int, reason: String) {
            val b = block ?: return
            incomplete += IncompleteBlock(b, blockStart, endLine, reason, blockRaw.toString())
            diags += ParseDiagnostic("INCOMPLETE_BLOCK", reason, Position(blockStart, b))
            block = null; blockRaw.clear(); currentClassId = null; currentSiteId = null
        }

        for (raw in lines) {
            lineNo++
            if (lineNo > budget.maxLines)
                return ParseOutcome.BudgetExhausted(
                    ParseDiagnostic("BUDGET_LINES", "line budget ${budget.maxLines} exhausted", pos()), lineNo
                )
            if (objects.size > budget.maxObjects)
                return ParseOutcome.BudgetExhausted(
                    ParseDiagnostic("BUDGET_OBJECTS", "object budget ${budget.maxObjects} exhausted", pos()), lineNo)
            if (edges.size > budget.maxEdges)
                return ParseOutcome.BudgetExhausted(
                    ParseDiagnostic("BUDGET_EDGES", "edge budget ${budget.maxEdges} exhausted", pos()), lineNo)

            val line = raw.trim()
            if (block != null) blockRaw.append(raw).append('\n')
            if (line.isEmpty() || line.startsWith("#")) continue

            if (line.startsWith("BEGIN ")) {
                if (block != null) return fail("NESTED_BLOCK", "block '$block' not closed before '$line'", block)
                block = line.removePrefix("BEGIN ").trim()
                blockStart = lineNo
                blockRaw.clear().append(raw).append('\n')
                continue
            }
            if (line.startsWith("END ")) {
                val name = line.removePrefix("END ").trim()
                if (block == null) return fail("END_WITHOUT_BEGIN", "'$line' without BEGIN", null)
                if (block == name) { block = null; blockRaw.clear(); currentClassId = null; currentSiteId = null }
                else closeIncomplete(lineNo, "block '$block' ended with mismatched END $name")
                continue
            }

            val key = line.substringBefore(' ', "").ifEmpty { line.substringBefore('\t') }
            val value = line.substringAfter(' ', missingDelimiterValue = "")
                .let { if (it == line) line.substringAfter('\t', "") else it }.trim()

            when {
                key == "SNAPSHOT" && value.isNotEmpty() -> snapId = value
                key == "PARSER" -> parserVersion = value
                key == "FORMAT" -> formatVersion = value
                key == "CAPTURED_AT" -> capturedAt = value
                key == "TIME_BASE" -> timeBase = value
                key == "ADDRESS_BASE" -> addressBase = value
                key == "UNIT_BASE" -> unitBase = value.ifEmpty { "bytes" }
                key == "CLASS" -> {
                    val parts = value.split(';').map { it.trim() }
                    val id = parts.getOrNull(0).orEmpty()
                    val name = parts.getOrNull(1).orEmpty()
                    val loader = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    val renamed = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                    if (id.isEmpty() || name.isEmpty())
                        return fail("BAD_CLASS", "CLASS requires id;name[;loader[;renamedFrom]]", block, raw)
                    classes += HeapClass(sid("cls"), id, name, loader, meta(parts.drop(4)), renamed)
                }
                key == "CLASS_BODY" -> {
                    val id = value.substringBefore(';').trim()
                    val body = value.substringAfter(';', "").trim()
                    if (classes.none { it.stableId == id })
                        return fail("BAD_CLASS_BODY", "CLASS_BODY references unknown class stableId '$id'", block, raw)
                    val target = classes.first { it.stableId == id }
                    val idx = classes.indexOf(target)
                    classes[idx] = target.copy(meta = target.meta + mapOf("body@$lineNo" to body))
                }
                key == "OBJECT" -> {
                    if (objects.size >= budget.maxObjects)
                        return ParseOutcome.BudgetExhausted(
                            ParseDiagnostic("BUDGET_OBJECTS", "object budget exhausted", pos(block)), lineNo)
                    val parts = splitSemicolon(raw).map { it.trim() }
                    val oid = parts.getOrNull(0).orEmpty()
                    val address = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    val classId = parts.getOrNull(2).orEmpty()
                    if (oid.isEmpty() || classId.isEmpty())
                        return fail("BAD_OBJECT", "OBJECT requires id;address;classId[;size[;siteId]]", block, raw)
                    objects += HeapObject(sid("obj"), oid, address, classId, quantity(parts.getOrNull(3), diags, lineNo, block),
                        parts.getOrNull(4)?.takeIf { it.isNotEmpty() }, lineNo, raw)
                }
                key == "EDGE" -> {
                    if (edges.size >= budget.maxEdges)
                        return ParseOutcome.BudgetExhausted(
                            ParseDiagnostic("BUDGET_EDGES", "edge budget exhausted", pos(block)), lineNo)
                    val parts = splitSemicolon(raw).map { it.trim() }
                    val from = parts.getOrNull(0).orEmpty()
                    val to = parts.getOrNull(1).orEmpty()
                    val strength = parts.getOrNull(2)?.uppercase().orEmpty()
                    val kind = parts.getOrNull(3).orEmpty()
                    val field = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
                    if (from.isEmpty() || to.isEmpty() || strength !in setOf("STRONG", "WEAK", "INTERNAL"))
                        return fail("BAD_EDGE", "EDGE requires from;to;STRONG|WEAK|INTERNAL;kind[;field]", block, raw)
                    edges += HeapEdge(sid("edge"), from, to, EdgeStrength.valueOf(strength), kind, field, lineNo, raw)
                }
                key == "ROOT" -> {
                    val parts = splitSemicolon(raw).map { it.trim() }
                    val oid = parts.getOrNull(0).orEmpty()
                    val rootType = parts.getOrNull(1).orEmpty()
                    if (oid.isEmpty() || rootType.isEmpty())
                        return fail("BAD_ROOT", "ROOT requires objectId;rootType[;category]", block, raw)
                    roots += HeapRoot(sid("root"), oid, rootType, parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: rootType, lineNo, raw)
                }
                key == "SITE" -> {
                    val parts = splitSemicolon(raw).map { it.trim() }
                    val siteId = parts.getOrNull(0).orEmpty()
                    val className = parts.getOrNull(1).orEmpty()
                    val location = parts.getOrNull(2).orEmpty()
                    if (siteId.isEmpty() || className.isEmpty())
                        return fail("BAD_SITE", "SITE requires id;className;location[;coordinateBase]", block, raw)
                    sites += AllocationSite(sid("site"), siteId, className, location,
                        parts.getOrNull(3)?.takeIf { it.isNotEmpty() }, lineNo, raw)
                }
                else -> return fail("UNKNOWN_RECORD", "unrecognized record '$key'", block, raw)
            }
        }

        if (block != null) {
            closeIncomplete(lines.size, "EOF reached while block '$block' was open")
        }
        val header = SnapshotHeader(
            snapId ?: return fail("MISSING_HEADER", "SNAPSHOT id is required", null),
            parserVersion ?: return fail("MISSING_HEADER", "PARSER version is required", null),
            formatVersion ?: "unknown", capturedAt, timeBase, addressBase, unitBase,
        )
        return ParseOutcome.Ok(
            HeapSnapshot(header, classes, objects, edges, roots, sites, incomplete, diags,
                text.length, lines.size)
        )
    }

    private fun splitSemicolon(raw: String): List<String> {
        val payload = raw.trim().substringAfter(' ', "")
        return payload.split(';')
    }

    private fun meta(tail: List<String>): Map<String, String> =
        tail.mapIndexed { i, v -> "m$i" to v }.toMap()

    private fun quantity(token: String?, diags: MutableList<ParseDiagnostic>, lineNo: Int, block: String?): RawQuantity? {
        if (token == null || token.isEmpty()) return null
        val m = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*([A-Za-z]+)?$").matchEntire(token)
        if (m == null) {
            diags += ParseDiagnostic("UNCONVERTIBLE_SIZE", "cannot normalize size '$token'", Position(lineNo, block), token)
            return RawQuantity(token, "?", null)
        }
        val num = m.groupValues[1]
        val unit = m.groupValues[2]?.ifEmpty { null } ?: "B"
        val mult = when (unit.uppercase()) {
            "B", "BYTE", "BYTES" -> 1L
            "KB", "KIB" -> 1024L
            "MB", "MIB" -> 1024L * 1024
            else -> null
        }
        val normalized = mult?.let { (num.toDouble() * it).toLong() }
        if (normalized == null)
            diags += ParseDiagnostic("UNKNOWN_UNIT", "no conversion for unit '$unit' in '$token'", Position(lineNo, block), token)
        return RawQuantity(token, unit, normalized)
    }
}
