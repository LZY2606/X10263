package forensics.parse

import forensics.model.*

/** Semantic impossibility: the file was syntactically readable but has no legal graph meaning. */
@Serializable
data class SemanticViolation(val code: String, val message: String, val position: Position)

sealed interface IngestResult {
    data class Accepted(val snapshot: HeapSnapshot, val warnings: List<ParseDiagnostic>) : IngestResult
    data class SemanticImpossible(val violations: List<SemanticViolation>) : IngestResult
}

object IngestSemantics {
    fun validate(snapshot: HeapSnapshot): IngestResult {
        val violations = mutableListOf<SemanticViolation>()

        val oids = mutableMapOf<String, Int>()
        snapshot.objects.forEach { o ->
            val first = oids.putIfAbsent(o.objectId, o.line)
            if (first != null) violations += SemanticViolation(
                "DUPLICATE_OBJECT_ID",
                "object id '${o.objectId}' first seen at line $first and again at ${o.line}; " +
                    "stable identity cannot be inferred from id reuse",
                Position(o.line),
            )
        }
        val classIds = snapshot.classes.map { it.stableId }.toHashSet()
        snapshot.objects.forEach { o ->
            if (o.classId !in classIds) violations += SemanticViolation(
                "UNKNOWN_CLASS", "object ${o.objectId} references unknown class stableId '${o.classId}'", Position(o.line))
        }
        val ids = snapshot.objects.map { it.objectId }.toHashSet()
        snapshot.edges.forEach { e ->
            if (e.fromObjectId !in ids) violations += SemanticViolation(
                "DANGLING_EDGE_FROM", "edge source '${e.fromObjectId}' does not exist", Position(e.line))
            if (e.toObjectId !in ids) violations += SemanticViolation(
                "DANGLING_EDGE_TO", "edge target '${e.toObjectId}' does not exist; graph meaning undecidable", Position(e.line))
        }
        snapshot.roots.forEach { r ->
            if (r.objectId !in ids) violations += SemanticViolation(
                "DANGLING_ROOT", "root '${r.objectId}' does not exist", Position(r.line))
        }
        snapshot.objects.mapNotNull { it.allocSiteId }.toHashSet().forEach { site ->
            if (snapshot.allocSites.none { it.stableId == site }) violations += SemanticViolation(
                "UNKNOWN_ALLOC_SITE", "object references unknown allocation site '$site'", Position(0))
        }
        return if (violations.isEmpty()) IngestResult.Accepted(snapshot, snapshot.diagnostics)
        else IngestResult.SemanticImpossible(violations)
    }
}
