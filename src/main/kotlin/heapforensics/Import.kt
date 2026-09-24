package heapforensics

import kotlinx.serialization.json.Json

/** Three distinct failure outcomes plus success. Never silently defaults. */
sealed interface ImportResult {
    data class Imported(val snapshot: HeapSnapshot, val rawJson: String, val diagnostics: List<Diagnostic>) : ImportResult
    data class FormatCorrupt(val position: String, val detail: String) : ImportResult
    data class BudgetExhausted(val needed: Int, val limit: Int, val stage: String) : ImportResult
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : ImportResult
}

object Importer {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(rawJson: String, objectBudget: Int = 1_000_000): ImportResult {
        val snapshot: HeapSnapshot = try {
            json.decodeFromString(HeapSnapshot.serializer(), rawJson)
        } catch (e: Exception) {
            val pos = e.message?.let { msg ->
                Regex("""offset: (\d+)""").find(msg)?.let { "byte offset ${it.groupValues[1]}" }
            } ?: "unknown position"
            return ImportResult.FormatCorrupt(pos, e.message ?: "unparseable snapshot")
        }
        if (snapshot.objects.size > objectBudget) {
            return ImportResult.BudgetExhausted(snapshot.objects.size, objectBudget, "objects")
        }
        if (snapshot.edges.size > objectBudget * 8) {
            return ImportResult.BudgetExhausted(snapshot.edges.size, objectBudget * 8, "edges")
        }
        val diagnostics = mutableListOf<Diagnostic>()
        val semanticErrors = mutableListOf<Diagnostic>()

        val classIds = snapshot.classes.map { it.classId }.toSet()
        val objectIds = snapshot.objects.map { it.objectId }.toSet()

        // Hard semantic errors: object -> unknown class (sizes/fields cannot be derived).
        snapshot.objects.forEachIndexed { i, o ->
            if (o.classId !in classIds) {
                semanticErrors += Diagnostic("objects[$i].classId", "UNKNOWN_CLASS",
                    "object ${o.objectId} references missing class ${o.classId}")
            }
        }
        // Duplicate object ids are a semantic error: identity would be ambiguous.
        snapshot.objects.groupingBy { it.objectId }.eachCount()
            .filter { it.value > 1 }.keys.forEach { dup ->
                semanticErrors += Diagnostic("objects", "DUPLICATE_OBJECT_ID",
                    "object id $dup appears more than once; identity is ambiguous")
            }
        if (semanticErrors.isNotEmpty()) return ImportResult.SemanticUnsolvable(semanticErrors)

        // Soft diagnostics: retained in place, never dropped.
        snapshot.edges.forEachIndexed { i, e ->
            if (e.fromId !in objectIds) diagnostics += Diagnostic("edges[$i].fromId", "DANGLING_EDGE",
                "edge from missing object ${e.fromId}; edge kept in raw layer, excluded from graph")
            if (e.toId !in objectIds) diagnostics += Diagnostic("edges[$i].toId", "DANGLING_EDGE",
                "edge to missing object ${e.toId}; edge kept in raw layer, excluded from graph")
        }
        snapshot.roots.forEachIndexed { i, r ->
            if (r.objectId !in objectIds) diagnostics += Diagnostic("roots[$i]", "DANGLING_ROOT",
                "root references missing object ${r.objectId}; kept in raw layer")
        }
        snapshot.objects.forEachIndexed { i, o ->
            if (o.incomplete) diagnostics += Diagnostic("objects/${o.objectId}", "INCOMPLETE_OBJECT",
                "object ${o.objectId} is a partial block; raw bytes retained, edges treated as unknown")
        }
        snapshot.incompleteChunks.forEach { c ->
            diagnostics += Diagnostic("incompleteChunks[${c.chunkId}]", "INCOMPLETE_CHUNK",
                "chunk at ${c.offsetHex} (${c.lengthBytes} bytes): ${c.reason}")
        }
        return ImportResult.Imported(snapshot, rawJson, diagnostics)
    }
}
