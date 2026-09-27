package hsf.parse

import hsf.model.*
import java.security.MessageDigest

/**
 * Parser for HSP - "Heap Shadow Profile", the offline case format of the
 * 堆影取证室. Line-oriented so every diagnostic carries a 1-based line number.
 *
 * Grammar (one record per line):
 *   snapshot <id> format=<v> parser=<v> captured=<epoch-ms|null> basis=<text> addrunit=byte
 *   class <id> name=<text> [renameKey=<k>] [super=<id>] [loader=<id>]
 *   object <id> addr=<0x..|none> class=<id> size=<number|raw> unit=byte
 *          [site=<id>] [complete=false] [chunk=<id>] [note=<text>]
 *   edge <id> from=<id> to=<id> kind=strong|weak|internal [field=<text>] [chunk=<id>]
 *   root <id> kind=<enum> [detail=<text>] [chunk=<id>]
 *   alloc <id> at=<file:line|text> [class=<id>]
 *   incomplete <chunkId> reason=<text> [lines=a-b]
 *
 * '#' starts a comment. Values may contain spaces only when written as
 * name="Some Name"; otherwise the tokenizer splits on whitespace.
 */
class HspParser(
    private val maxLines: Int = 5_000_000,
    private val maxRecordLength: Int = 100_000
) {
    private val rootKinds = RootKind.entries.associateBy { it.name.lowercase().replace('_', '-') }
    private val edgeKinds = EdgeKind.entries.associateBy { it.name.lowercase() }

    fun parse(sourceName: String, text: String): ImportOutcome {
        val diags = mutableListOf<Diagnostic>()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        var formatVersion: String? = null
        var parserVersion: String? = null
        var snapshotId: String? = null
        var captured: Long? = null
        var timeBasis: String? = null
        var basis = "runtime-virtual-heap"
        var addrUnit = "byte"
        val classes = linkedMapOf<String, ClassMeta>()
        val objects = linkedMapOf<String, HeapObject>()
        val edges = linkedMapOf<String, Edge>()
        val roots = mutableListOf<RootRecord>()
        val sites = linkedMapOf<String, AllocationSite>()
        val chunks = linkedMapOf<String, IncompleteChunk>()

        val lines = text.split('\n')
        if (lines.size > maxLines) {
            return ImportOutcome(
                OutcomeKind.RESOURCE_BUDGET_EXHAUSTED, null, digest,
                listOf(Diagnostic("budget.lines", Severity.ERROR,
                    "line count ${lines.size} exceeds budget $maxLines"))
            )
        }

        for ((idx0, rawLine) in lines.withIndex()) {
            val lineNo = idx0 + 1
            if (rawLine.length > maxRecordLength) {
                return ImportOutcome(
                    OutcomeKind.RESOURCE_BUDGET_EXHAUSTED, null, digest,
                    listOf(Diagnostic("budget.record", Severity.ERROR,
                        "line $lineNo length ${rawLine.length} exceeds $maxRecordLength", line = lineNo))
                )
            }
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            val tokens = tokenize(line, lineNo, diags) ?: continue
            when (tokens.first()) {
                "snapshot" -> {
                    if (tokens.size < 2) {
                        diags += err("snapshot.missing-id", lineNo, "snapshot requires an id")
                        continue
                    }
                    snapshotId = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    formatVersion = kv["format"] ?: alsoErr(diags, "snapshot.format", lineNo)
                    parserVersion = kv["parser"] ?: alsoErr(diags, "snapshot.parser", lineNo)
                    captured = kv["captured"]?.takeIf { it != "null" }?.toLongOrNull()
                        ?: kv["captured"]?.let {
                            diags += warn("snapshot.captured.unconvertible", lineNo, raw = it); null
                        }
                    timeBasis = kv["timebasis"]
                    basis = kv["basis"] ?: basis
                    addrUnit = kv["addrunit"] ?: addrUnit
                }
                "class" -> {
                    if (tokens.size < 2) { diags += err("class.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val nameRaw = kv["name"] ?: alsoErr(diags, "class.name", lineNo) ?: continue
                    if (classes.containsKey(id)) diags += err("class.duplicate", lineNo, "duplicate class id $id", id = id)
                    val prev = classes[id]
                    classes[id] = ClassMeta(
                        classId = id, name = nameRaw, nameRaw = nameRaw,
                        superclassId = kv["super"], classLoaderObjectId = kv["loader"],
                        renameKey = kv["renamekey"],
                        nameHistory = (prev?.nameHistory ?: emptyList()) + listOfNotNull(prev?.nameRaw)
                    )
                }
                "object" -> {
                    if (tokens.size < 2) { diags += err("object.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val classId = kv["class"] ?: alsoErr(diags, "object.class", lineNo, id) ?: continue
                    val sizeRaw = kv["size"] ?: alsoErr(diags, "object.size", lineNo, id) ?: continue
                    if (sizeRaw.toLongOrNull() == null)
                        diags += Diagnostic("object.size.unconvertible", Severity.WARNING,
                            "size '$sizeRaw' kept at original position; excluded from numeric totals",
                            line = lineNo, objectId = id, field = "size", rawValue = sizeRaw)
                    val addr = kv["addr"]?.takeIf { it != "none" }
                    if (addr != null && !addr.matches(Regex("0[xX][0-9a-fA-F]+")))
                        diags += Diagnostic("object.addr.unconvertible", Severity.WARNING,
                            "address '$addr' kept verbatim, not parsed", line = lineNo,
                            objectId = id, field = "addr", rawValue = addr)
                    if (objects.containsKey(id)) diags += err("object.duplicate", lineNo, "duplicate object id $id", id)
                    objects[id] = HeapObject(
                        objectId = id, addressRaw = addr, classId = classId, sizeRaw = sizeRaw,
                        unit = kv["unit"] ?: "byte", allocationSiteId = kv["site"],
                        complete = kv["complete"]?.let { it != "false" } ?: true,
                        chunkId = kv["chunk"], notes = kv["note"]
                    )
                }
                "edge" -> {
                    if (tokens.size < 2) { diags += err("edge.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val from = kv["from"] ?: alsoErr(diags, "edge.from", lineNo) ?: continue
                    val to = kv["to"] ?: alsoErr(diags, "edge.to", lineNo) ?: continue
                    val kindText = (kv["kind"] ?: alsoErr(diags, "edge.kind", lineNo) ?: continue).lowercase()
                    val kind = edgeKinds[kindText]
                        ?: run { diags += err("edge.kind.unknown", lineNo, "unknown edge kind '$kindText'"); continue }
                    if (edges.containsKey(id)) diags += err("edge.duplicate", lineNo, "duplicate edge id $id")
                    edges[id] = Edge(id, from, to, kind, kv["field"], kv["chunk"])
                }
                "root" -> {
                    if (tokens.size < 2) { diags += err("root.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val kindText = (kv["kind"] ?: alsoErr(diags, "root.kind", lineNo, id) ?: continue)
                    val kind = rootKinds[kindText.lowercase().replace('_', '-')]
                        ?: run { diags += err("root.kind.unknown", lineNo, "unknown root kind '$kindText'", id); continue }
                    roots += RootRecord(id, kind, kv["detail"], kv["chunk"])
                }
                "alloc" -> {
                    if (tokens.size < 2) { diags += err("alloc.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val at = kv["at"] ?: alsoErr(diags, "alloc.at", lineNo) ?: continue
                    val (loc, lineNum) = parseLocation(at)
                    sites[id] = AllocationSite(id, loc, lineNum, kv["class"])
                }
                "incomplete" -> {
                    if (tokens.size < 2) { diags += err("incomplete.missing-id", lineNo); continue }
                    val id = tokens[1]
                    val kv = kv(tokens, 2, lineNo, diags) ?: continue
                    val reason = kv["reason"] ?: alsoErr(diags, "incomplete.reason", lineNo) ?: continue
                    val range = kv["lines"]?.split('-')
                    chunks[id] = IncompleteChunk(
                        id, reason,
                        range?.getOrNull(0)?.toIntOrNull(),
                        range?.getOrNull(1)?.toIntOrNull()
                    )
                }
                else -> diags += err("record.unknown", lineNo, "unknown record '${tokens.first()}'")
            }
        }

        if (snapshotId == null)
            diags += err("snapshot.missing", null, "no snapshot header found")
        if (formatVersion == null) diags += err("snapshot.no-format", null, "snapshot format version absent")
        if (parserVersion == null) diags += err("snapshot.no-parser", null, "parser version absent")

        // Semantic integrity checks at the material boundary.
        val addrSeen = HashMap<String, String>()
        for (o in objects.values) {
            if (o.classId !in classes)
                diags += Diagnostic("object.class.unresolved", Severity.ERROR,
                    "object ${o.objectId} references unknown class ${o.classId}", objectId = o.objectId)
            if (o.addressRaw != null) {
                val prior = addrSeen[o.addressRaw]
                if (prior != null)
                    diags += Diagnostic("object.address.duplicate", Severity.WARNING,
                        "address ${o.addressRaw} reused by $prior and ${o.objectId}; address is not identity",
                        objectId = o.objectId, field = "addr", rawValue = o.addressRaw)
                addrSeen[o.addressRaw] = o.objectId
            }
        }
        for (e in edges.values) {
            if (e.fromObjectId !in objects) diags += err("edge.from.unresolved", null, "edge ${e.edgeId} from missing object ${e.fromObjectId}")
            if (e.toObjectId !in objects) diags += err("edge.to.unresolved", null, "edge ${e.edgeId} to missing object ${e.toObjectId}")
        }
        for (r in roots) if (r.objectId !in objects)
            diags += err("root.object.unresolved", null, "root points to missing object ${r.objectId}")

        val fatal = diags.any { it.severity == Severity.ERROR }
        val snap = if (snapshotId != null && formatVersion != null && parserVersion != null)
            HeapSnapshot(
                formatVersion, parserVersion, snapshotId, captured,
                timeBasisRaw = timeBasis, addressBasis = basis, addressUnit = addrUnit,
                classes = classes.values.toList(), objects = objects.values.toList(),
                edges = edges.values.toList(), roots = roots, allocationSites = sites.values.toList(),
                incompleteChunks = chunks.values.toList(), diagnostics = diags
            )
        else null
        return ImportOutcome(if (fatal) OutcomeKind.FORMAT_CORRUPT else OutcomeKind.OK, snap, digest, diags)
    }

    private fun parseLocation(at: String): Pair<String, Int?> {
        val m = Regex("^(.*):(\\d+)$").matchEntire(at)
        return if (m != null) m.groupValues[1] to m.groupValues[2].toInt() else at to null
    }

    private fun stripComment(line: String): String {
        var inStr = false
        val sb = StringBuilder()
        for (c in line) {
            if (c == '"') inStr = !inStr
            if (c == '#' && !inStr) break
            sb.append(c)
        }
        return sb.toString()
    }

    private fun tokenize(line: String, lineNo: Int, diags: MutableList<Diagnostic>): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '"') {
                val end = line.indexOf('"', i + 1)
                if (end < 0) {
                    diags += err("token.unterminated-string", lineNo, "unterminated quoted value")
                    return null
                }
                out += line.substring(i + 1, end)
                i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                out += line.substring(start, i)
            }
        }
        return out
    }

    /** key=value tail tokens; '=' inside quoted strings already stripped by tokenizer. */
    private fun kv(tokens: List<String>, from: Int, lineNo: Int, diags: MutableList<Diagnostic>): Map<String, String>? {
        val map = mutableMapOf<String, String>()
        for (t in tokens.drop(from)) {
            val eq = t.indexOf('=')
            if (eq <= 0) {
                diags += err("token.kv", lineNo, "malformed token '$t' (expected key=value)")
                return null
            }
            map[t.substring(0, eq).lowercase()] = t.substring(eq + 1)
        }
        return map
    }

    private fun err(code: String, line: Int?, msg: String? = null, id: String? = null) =
        Diagnostic(code, Severity.ERROR, msg ?: code, line = line, objectId = id)

    private fun warn(code: String, line: Int?, msg: String? = null, raw: String? = null) =
        Diagnostic(code, Severity.WARNING, msg ?: code, line = line, rawValue = raw)

    private fun alsoErr(diags: MutableList<Diagnostic>, code: String, line: Int?, id: String? = null): String? {
        diags += err(code, line, id = id); return null
    }
}
