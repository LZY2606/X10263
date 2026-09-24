package org.heapshadows.forensics.hsp

import org.heapshadows.forensics.json.*
import org.heapshadows.forensics.model.*
import java.security.MessageDigest

/**
 * Importer for the Heap Shadow Profile format (.hsp.json).
 *
 * Rules enforced here:
 *  - raw object id, address, class metadata, strong/weak/internal edges, root
 *    category, incomplete blocks and parser version are preserved verbatim;
 *  - every domain quantity keeps its raw representation and declared unit;
 *  - malformed input yields FORMAT_CORRUPT diagnostics with source positions;
 *  - duplicate object ids / dangling edges yield SEMANTIC_UNRESOLVABLE;
 *  - oversize inputs yield RESOURCE_EXHAUSTED; the three never collapse.
 */
object HspParser {

    private const val FORMAT = "heap-shadow-profile"
    private const val SUPPORTED_MAJOR = 1

    fun parse(text: String, maxBytes: Int = 8 * 1024 * 1024): HeapSnapshot {
        if (text.length > maxBytes) {
            throw ResourceExhaustedException(
                "snapshot byte budget exhausted", "snapshot.bytes",
                maxBytes.toLong(), text.length.toLong(),
            )
        }
        val root = try {
            JsonParser.parse(text)
        } catch (e: JsonParseException) {
            val d = Diagnostic.of(
                "HSP_JSON_SYNTAX", "JSON syntax error: ${e.message}", "$", e.pos,
            )
            throw FormatFailureException("snapshot is not well-formed JSON", listOf(d), e)
        }
        if (root !is JsonObject) {
            throw FormatFailureException(
                "snapshot root must be an object",
                listOf(Diagnostic("HSP_ROOT_OBJECT", "top-level JSON value is not an object", "$", null, null, null)),
            )
        }

        val format = requireString(root, "format")
        if (format != FORMAT) {
            throw SemanticFailureException(
                "unknown snapshot format '$format', expected '$FORMAT'",
                diagnostics = listOf(
                    Diagnostic("HSP_FORMAT", "format must be '$FORMAT'", "$.format", null, null, null),
                ),
            )
        }

        val versionRaw = requireString(root, "parserVersion")
        val major = versionRaw.substringBefore('.').toIntOrNull()
        if (major == null) {
            throw SemanticFailureException(
                "parserVersion '$versionRaw' is not comparable",
                diagnostics = listOf(
                    Diagnostic("HSP_PARSER_VERSION", "parserVersion must start with a numeric major version",
                        "$.parserVersion", null, null, null),
                ),
            )
        }
        val versionWarnings = if (major > SUPPORTED_MAJOR) listOf(
            Diagnostic(
                "HSP_PARSER_VERSION_NEWER",
                "snapshot produced by parser major version $major, reader understands $SUPPORTED_MAJOR; fields are preserved but not interpreted",
                "$.parserVersion", null, null, null, Diagnostic.Severity.WARNING,
            ),
        ) else emptyList()

        val metaNode = requireObject(root, "meta")
        val meta = parseMeta(metaNode)

        val classes = root.find("classes")?.let { parseClasses(it) } ?: emptyList()
        val objects = root.find("objects")?.let { parseObjects(it) } ?: emptyList()
        val edges = root.find("edges")?.let { parseEdges(it) } ?: emptyList()
        val roots = root.find("roots")?.let { parseRoots(it) } ?: emptyList()
        val sites = root.find("allocationSites")?.let { parseSites(it) } ?: emptyList()
        val incomplete = root.find("incompleteBlocks")?.let { parseIncomplete(it) } ?: emptyList()

        enforceBudgets(objects.size, edges.size, meta.snapshotId)
        val diagnostics = versionWarnings.toMutableList()
        semanticChecks(classes, objects, edges, roots, sites, incomplete, diagnostics)

        val sha = sha256(text)
        return HeapSnapshot(
            meta = meta,
            classes = classes.sortedBy { it.classId },
            objects = objects.sortedWith(compareBy({ it.objectId }, { it.addressRaw })),
            edges = edges.sortedBy { it.edgeId },
            roots = roots.sortedBy { it.rootId },
            allocationSites = sites.sortedBy { it.siteId },
            incompleteBlocks = incomplete.sortedBy { it.blockId },
            rawText = text,
            rawSha256 = sha,
            importWarnings = diagnostics.sortedWith(compareBy({ it.pointer }, { it.code })),
        )
    }

    private fun parseMeta(node: JsonValue): SnapshotMeta {
        val o = asObject(node, "$.meta")
        val snapshotId = requireString(o, "snapshotId", "$.meta")
        requireId(snapshotId, "$.meta.snapshotId")
        val revision = (o.find("revision") as? JsonNumber)?.asLongOrNull?.toInt()
            ?: throw SemanticFailureException(
                "meta.revision missing",
                diagnostics = listOf(missing("$.meta.revision")),
            )
        if (revision < 1) throw SemanticFailureException(
            "meta.revision must be >= 1",
            diagnostics = listOf(Diagnostic("HSP_REVISION", "revision must be >= 1", "$.meta.revision", null, null, null)),
        )
        val captured = optionalString(o, "capturedAtIso") ?: ""
        if (captured.isNotEmpty() && !VALID_ISO.matches(captured)) {
            throw SemanticFailureException(
                "capturedAtIso '$captured' is not an ISO-8601 instant",
                diagnostics = listOf(Diagnostic(
                    "HSP_TIME_BASIS", "capturedAtIso must look like 2026-09-24T08:00:00Z",
                    "$.meta.capturedAtIso", null, null, null,
                )),
            )
        }
        return SnapshotMeta(
            snapshotId = snapshotId,
            revision = revision,
            capturedAtIso = captured,
            timeBasis = optionalString(o, "timeBasis") ?: "UTC",
            addressBasis = optionalString(o, "addressBasis") ?: "absolute-virtual",
            parserName = optionalString(o, "parserName") ?: "unknown",
            parserVersion = requireString(o, "parserVersion", "$.meta"),
            producer = optionalString(o, "producer") ?: "unknown",
        )
    }

    private fun parseClasses(node: JsonValue): List<RawClass> {
        val arr = asArray(node, "$.classes")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.classes[$idx]"
            val o = asObject(el, p)
            val classId = requireString(o, "classId", p)
            requireId(classId, "$p.classId")
            val fields = (o.find("fields") as? JsonArray)?.elements?.mapIndexed { fIdx, fEl ->
                val fo = asObject(fEl, "$p.fields[$fIdx]")
                RawField(
                    name = requireString(fo, "name", "$p.fields[$fIdx]"),
                    type = optionalString(fo, "type") ?: "object",
                )
            } ?: emptyList()
            RawClass(
                classId = classId,
                name = requireString(o, "name", p),
                superClassId = optionalString(o, "superClassId"),
                classLoaderId = optionalString(o, "classLoaderId"),
                renamedFrom = optionalString(o, "renamedFrom"),
                metaKey = optionalString(o, "metaKey") ?: classId,
                fields = fields,
            )
        }
    }

    private fun parseObjects(node: JsonValue): List<RawObject> {
        val arr = asArray(node, "$.objects")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.objects[$idx]"
            val o = asObject(el, p)
            val id = requireString(o, "id", p)
            requireId(id, "$p.id")
            val sizeRaw = requireString(o, "shallowSize", p)
            val unit = optionalString(o, "sizeUnit") ?: "B"
            RawObject(
                objectId = id,
                addressRaw = requireString(o, "address", p),
                classId = requireString(o, "classId", p),
                shallowSizeRaw = sizeRaw,
                sizeUnit = unit,
                allocationSiteId = optionalString(o, "allocationSite"),
                threadRaw = optionalString(o, "thread"),
                incomplete = (o.find("incomplete") as? JsonBoolean)?.value ?: false,
                notes = optionalString(o, "notes"),
            )
        }
    }

    private fun parseEdges(node: JsonValue): List<RawEdge> {
        val arr = asArray(node, "$.edges")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.edges[$idx]"
            val o = asObject(el, p)
            val strengthRaw = requireString(o, "strength", p)
            val strength = runCatching { EdgeStrength.valueOf(strengthRaw) }.getOrElse {
                throw SemanticFailureException(
                    "edge strength '$strengthRaw' unknown",
                    diagnostics = listOf(Diagnostic(
                        "HSP_EDGE_STRENGTH", "strength must be STRONG, WEAK or INTERNAL",
                        "$p.strength", el.start.line, el.start.col, el.start.offset,
                    )),
                )
            }
            RawEdge(
                edgeId = requireString(o, "id", p),
                fromId = requireString(o, "from", p),
                toId = requireString(o, "to", p),
                strength = strength,
                fieldName = optionalString(o, "field"),
                weakPolicyTag = optionalString(o, "weakPolicyTag"),
            )
        }
    }

    private fun parseRoots(node: JsonValue): List<RawRoot> {
        val arr = asArray(node, "$.roots")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.roots[$idx]"
            val o = asObject(el, p)
            val category = requireString(o, "category", p)
            RawRoot(
                rootId = requireString(o, "id", p),
                objectId = requireString(o, "object", p),
                categoryRaw = category,
                categoryValid = RootCategory.parse(category) != null,
            )
        }
    }

    private fun parseSites(node: JsonValue): List<RawAllocationSite> {
        val arr = asArray(node, "$.allocationSites")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.allocationSites[$idx]"
            val o = asObject(el, p)
            RawAllocationSite(
                siteId = requireString(o, "id", p),
                classId = requireString(o, "classId", p),
                methodName = requireString(o, "method", p),
                file = optionalString(o, "file"),
                line = (o.find("line") as? JsonNumber)?.asLongOrNull?.toInt(),
            )
        }
    }

    private fun parseIncomplete(node: JsonValue): List<IncompleteBlock> {
        val arr = asArray(node, "$.incompleteBlocks")
        return arr.elements.mapIndexed { idx, el ->
            val p = "$.incompleteBlocks[$idx]"
            val o = asObject(el, p)
            IncompleteBlock(
                blockId = requireString(o, "id", p),
                pointer = optionalString(o, "pointer") ?: p,
                reason = requireString(o, "reason", p),
                rawText = optionalString(o, "rawText") ?: "",
            )
        }
    }

    private fun enforceBudgets(objectCount: Int, edgeCount: Int, snapshotId: String) {
        val params = AnalysisParams.initial()
        if (objectCount > params.maxNodes) throw ResourceExhaustedException(
            "object budget exhausted while importing $snapshotId", "snapshot.objects",
            params.maxNodes.toLong(), objectCount.toLong(),
        )
        if (edgeCount > params.maxEdges) throw ResourceExhaustedException(
            "edge budget exhausted while importing $snapshotId", "snapshot.edges",
            params.maxEdges.toLong(), edgeCount.toLong(),
        )
    }

    private fun semanticChecks(
        classes: List<RawClass>,
        objects: List<RawObject>,
        edges: List<RawEdge>,
        roots: List<RawRoot>,
        sites: List<RawAllocationSite>,
        incomplete: List<IncompleteBlock>,
        warnings: MutableList<Diagnostic>,
    ) {
        val errors = mutableListOf<Diagnostic>()
        val classIds = classes.map { it.classId }.toMutableSet()
        val objectIds = objects.map { it.objectId }.toMutableSet()

        fun err(code: String, message: String, pointer: String) {
            errors += Diagnostic(code, message, pointer, null, null, null)
        }
        fun warn(code: String, message: String, pointer: String) {
            warnings += Diagnostic(code, message, pointer, null, null, null, Diagnostic.Severity.WARNING)
        }

        val dupClasses = classes.groupingBy { it.classId }.eachCount().filterValues { it > 1 }.keys
        dupClasses.forEach { err("HSP_DUPLICATE_CLASS", "duplicate class id '$it'", "$.classes[*].classId") }
        val dupObjects = objects.groupingBy { it.objectId }.eachCount().filterValues { it > 1 }.keys
        val affected = dupObjects.toList().sorted()
        dupObjects.forEach { err("HSP_DUPLICATE_OBJECT", "duplicate object id '$it'; raw id is the stable identity",
            "$.objects[*].id") }

        objects.forEach { o ->
            if (o.classId !in classIds) {
                err("HSP_DANGLING_CLASS", "object '${o.objectId}' references unknown class '${o.classId}'",
                    "$.objects[*].classId")
            }
            o.allocationSiteId?.let { site ->
                if (sites.none { it.siteId == site }) {
                    warn("HSP_DANGLING_SITE", "object '${o.objectId}' references unknown allocation site '$site'",
                        "$.objects[*].allocationSite")
                }
            }
        }
        edges.forEach { e ->
            if (e.fromId !in objectIds) err(
                "HSP_DANGLING_EDGE_ENDPOINT", "edge '${e.edgeId}' leaves unknown object '${e.fromId}'",
                "$.edges[*].from",
            )
            if (e.toId !in objectIds) err(
                "HSP_DANGLING_EDGE_ENDPOINT", "edge '${e.edgeId}' points at unknown object '${e.toId}'",
                "$.edges[*].to",
            )
        }
        edges.groupingBy { it.edgeId }.eachCount().filterValues { it > 1 }.keys.forEach {
            err("HSP_DUPLICATE_EDGE", "duplicate edge id '$it'", "$.edges[*].id")
        }
        roots.forEach { r ->
            if (r.objectId !in objectIds) err(
                "HSP_DANGLING_ROOT", "root '${r.rootId}' references unknown object '${r.objectId}'",
                "$.roots[*].object",
            )
            if (!r.categoryValid) warn(
                "HSP_UNKNOWN_ROOT_CATEGORY", "root '${r.rootId}' uses unrecognized category '${r.categoryRaw}'",
                "$.roots[*].category",
            )
        }
        sites.forEach { s ->
            if (s.classId !in classIds) warn(
                "HSP_SITE_CLASS", "allocation site '${s.siteId}' references unknown class '${s.classId}'",
                "$.allocationSites[*].classId",
            )
        }

        val duplicateAddresses = objects
            .groupingBy { it.addressRaw }
            .eachCount()
            .filterValues { it > 1 }
        duplicateAddresses.keys.sorted().forEach { addr ->
            warn(
                "HSP_DUPLICATE_ADDRESS",
                "address '$addr' is shared by ${duplicateAddresses[addr]} objects; address must not be used as a stable identity",
                "$.objects[*].address",
            )
        }

        objects.forEach { o ->
            if (SizeUnits.parse(o.shallowSizeRaw, o.sizeUnit) == null) {
                warn(
                    "HSP_UNCONVERTIBLE_SIZE",
                    "object '${o.objectId}' size '${o.shallowSizeRaw} ${o.sizeUnit}' cannot be normalized; value stays in place",
                    "$.objects[*].shallowSize",
                )
            }
        }
        if (objects.any { it.incomplete } || incomplete.isNotEmpty()) {
            warn(
                "HSP_INCOMPLETE_INPUT",
                "snapshot contains incomplete objects/blocks; retained totals involving them stay uncomputable",
                "$.incompleteBlocks",
            )
        }

        if (errors.isNotEmpty()) {
            throw SemanticFailureException(
                "snapshot fails semantic validation with ${errors.size} error(s)",
                affectedIds = affected,
                diagnostics = errors.sortedWith(compareBy({ it.pointer }, { it.code })),
            )
        }
    }

    private val VALID_ISO = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?""")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun requireString(o: JsonObject, key: String, pointer: String = "$"): String {
        return when (val v = o.find(key)) {
            is JsonString -> v.value
            else -> throw SemanticFailureException(
                "field '$key' missing or not a string",
                diagnostics = listOf(missing("$pointer.$key")),
            )
        }
    }

    private fun optionalString(o: JsonObject, key: String): String? = (o.find(key) as? JsonString)?.value

    private fun requireObject(o: JsonObject, key: String): JsonObject = when (val v = o.find(key)) {
        is JsonObject -> v
        else -> throw SemanticFailureException(
            "field '$key' missing or not an object",
            diagnostics = listOf(missing("$.$key")),
        )
    }

    private fun asObject(v: JsonValue, pointer: String): JsonObject = (v as? JsonObject)
        ?: throw FormatFailureException(
            "expected object at $pointer",
            listOf(Diagnostic("HSP_TYPE_OBJECT", "expected JSON object", pointer,
                v.start.line, v.start.col, v.start.offset)),
        )

    private fun asArray(v: JsonValue, pointer: String): JsonArray = (v as? JsonArray)
        ?: throw FormatFailureException(
            "expected array at $pointer",
            listOf(Diagnostic("HSP_TYPE_ARRAY", "expected JSON array", pointer,
                v.start.line, v.start.col, v.start.offset)),
        )

    private fun missing(pointer: String) =
        Diagnostic("HSP_MISSING_FIELD", "required field missing", pointer, null, null, null)

    private fun requireId(id: String, pointer: String) {
        if (!ID_PATTERN.matches(id)) throw SemanticFailureException(
            "stable id '$id' is invalid",
            diagnostics = listOf(Diagnostic(
                "HSP_ID", "id must match $ID_PATTERN and must not be derived from an address",
                pointer, null, null, null,
            )),
        )
    }

    private val ID_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_.@\-/]{0,127}""")
}
