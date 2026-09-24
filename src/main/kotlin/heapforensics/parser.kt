package heapforensics

import kotlinx.serialization.json.Json

val snapshotJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    explicitNulls = false
}

/** Parse a raw snapshot document. Format corruption is a distinct failure. */
sealed class ParseOutcome {
    data class Ok(val snapshot: RawSnapshot) : ParseOutcome()
    data class FormatCorrupt(val message: String, val position: String) : ParseOutcome()
}

fun parseSnapshot(text: String): ParseOutcome = try {
    ParseOutcome.Ok(snapshotJson.decodeFromString(RawSnapshot.serializer(), text))
} catch (e: Exception) {
    ParseOutcome.FormatCorrupt(e.message ?: "unparseable", "$")
}

private fun parseLongRaw(raw: String): Long? {
    val t = raw.trim().lowercase()
    return try {
        when {
            t.startsWith("0x") -> t.drop(2).toLong(16)
            t.startsWith("#") -> t.drop(1).toLong(16)
            else -> t.toLong()
        }
    } catch (e: NumberFormatException) { null }
}

/**
 * Build the derived (normalized) layer. Raw values are never rewritten:
 * unconvertible values stay in place and get a positioned diagnostic.
 */
fun derive(raw: RawSnapshot): DerivedSnapshot {
    val diagnostics = mutableListOf<Diagnostic>()
    val seenIds = mutableSetOf<String>()
    val objects = LinkedHashMap<String, DerivedObject>()

    raw.objects.forEachIndexed { i, o ->
        val pos = "\$.objects[$i]"
        val objDiags = mutableListOf<Diagnostic>()
        if (!seenIds.add(o.objectId)) {
            diagnostics += Diagnostic("ERROR", "$pos.objectId", "duplicate object id '${o.objectId}'")
        }
        val size = when {
            o.sizeBytes == null -> {
                objDiags += Diagnostic("ERROR", "$pos.sizeBytes", "missing sizeBytes (incomplete object)")
                null
            }
            else -> parseLongRaw(o.sizeBytes).also {
                if (it == null) objDiags += Diagnostic("ERROR", "$pos.sizeBytes",
                    "unconvertible sizeBytes '${o.sizeBytes}' kept raw")
                else if (it < 0) objDiags += Diagnostic("ERROR", "$pos.sizeBytes",
                    "negative sizeBytes ${o.sizeBytes} kept raw")
            }.takeIf { it != null && it >= 0 }
        }
        val addr = parseLongRaw(o.address).also {
            if (it == null) objDiags += Diagnostic("WARNING", "$pos.address",
                "unconvertible address '${o.address}' kept raw; address is not an identity")
        }
        if (o.classId == null) {
            objDiags += Diagnostic("ERROR", "$pos.classId", "missing classId (incomplete object)")
        } else if (raw.classes.none { it.classId == o.classId }) {
            objDiags += Diagnostic("WARNING", "$pos.classId", "classId '${o.classId}' has no class metadata")
        }
        diagnostics += objDiags
        objects[o.objectId] = DerivedObject(o, size, addr, objDiags)
    }

    val edges = raw.edges.mapIndexed { i, e ->
        val kind = EdgeKind.entries.firstOrNull { it.name == e.kind }
        if (kind == null) diagnostics += Diagnostic("ERROR", "\$.edges[$i].kind",
            "unknown edge kind '${e.kind}' kept raw")
        if (objects[e.from] == null) diagnostics += Diagnostic("ERROR", "\$.edges[$i].from",
            "edge from unknown object '${e.from}'")
        if (objects[e.to] == null) diagnostics += Diagnostic("ERROR", "\$.edges[$i].to",
            "edge to unknown object '${e.to}'")
        e to kind
    }

    val roots = raw.roots.mapIndexed { i, r ->
        val rt = RootType.entries.firstOrNull { it.name == r.rootType }
        if (rt == null) diagnostics += Diagnostic("WARNING", "\$.roots[$i].rootType",
            "unknown root type '${r.rootType}' kept raw")
        if (objects[r.objectId] == null) diagnostics += Diagnostic("ERROR", "\$.roots[$i].objectId",
            "root references unknown object '${r.objectId}'")
        r to rt
    }

    // Duplicate addresses are legal (reused memory); they are reported, never merged.
    raw.objects.groupBy { it.address }.filter { it.value.size > 1 }.forEach { (addr, list) ->
        diagnostics += Diagnostic("WARNING", "\$.objects",
            "address $addr shared by ${list.size} objects (${list.joinToString(",") { it.objectId }}); identity stays objectId")
    }

    return DerivedSnapshot(raw, objects, edges, roots, diagnostics)
}
