package hf

/** 解析成功：快照 + 非致命警告（如重复地址提示）。 */
data class ParsedSnapshot(val snapshot: Snapshot, val warnings: List<Diagnostic>)

/**
 * 堆快照行格式解析器。
 *
 * 格式（# 开头为注释）：
 *   snapshot <id> name="..." parser=<ver> captured=<epochMillis>
 *   class <classId> name=<fqcn> size=<bytes> [super=<classId>]
 *   site <siteId> sig="<signature>"
 *   object <objectId> addr=0x<hex> class=<classId> size=<bytes> [site=<siteId>] [incomplete]
 *   edge <src> -> <dst> kind=strong|weak|internal
 *   root <rootId> kind=<kind> object=<objectId>
 *   block <blockId> offset=<n> length=<n> reason="..."
 *
 * 语法错误 -> Corrupt；悬空引用/缺 root/重复 id 等 -> Unsolvable。
 * 诊断一律带行列位置。原始文本原样保留在 Snapshot.rawText。
 */
object SnapshotParser {

    fun parse(text: String): Outcome<ParsedSnapshot> {
        val errors = mutableListOf<Diagnostic>()
        val warnings = mutableListOf<Diagnostic>()

        var header: SnapshotHeader? = null
        val classes = mutableListOf<ClassMeta>()
        val sites = mutableListOf<AllocSite>()
        val objects = mutableListOf<HeapObject>()
        val edges = mutableListOf<HeapEdge>()
        val roots = mutableListOf<Root>()
        val blocks = mutableListOf<IncompleteBlock>()

        text.lines().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val tokens = tokenize(line, lineNo, errors) ?: return@forEachIndexed
            when (tokens[0]) {
                "snapshot" -> {
                    if (header != null) {
                        errors += Diagnostic(lineNo, 1, "error", "duplicate snapshot header")
                    } else {
                        header = parseHeader(tokens, lineNo, errors)
                    }
                }
                "class" -> parseClass(tokens, lineNo, errors)?.let(classes::add)
                "site" -> parseSite(tokens, lineNo, errors)?.let(sites::add)
                "object" -> parseObject(tokens, lineNo, errors)?.let(objects::add)
                "edge" -> parseEdge(tokens, lineNo, errors)?.let(edges::add)
                "root" -> parseRoot(tokens, lineNo, errors)?.let(roots::add)
                "block" -> parseBlock(tokens, lineNo, errors)?.let(blocks::add)
                else -> errors += Diagnostic(lineNo, 1, "error", "unknown directive '${tokens[0]}'")
            }
        }

        if (errors.isNotEmpty()) return Outcome.Corrupt(errors)
        val head = header
            ?: return Outcome.Corrupt(listOf(Diagnostic(1, 1, "error", "missing snapshot header")))

        // ---- 语义校验：失败为 Unsolvable，不得静默修补 ----
        val semantic = mutableListOf<Diagnostic>()
        val classIds = classes.map { it.classId }
        classIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            .forEach { semantic += Diagnostic(0, 0, "error", "duplicate class id '$it'") }
        val objectIds = objects.map { it.objectId }
        objectIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            .forEach { semantic += Diagnostic(0, 0, "error", "duplicate object id $it") }
        val knownClasses = classIds.toSet()
        val knownObjects = objectIds.toSet()
        val knownSites = sites.map { it.siteId }.toSet()
        objects.forEach { obj ->
            if (obj.classId !in knownClasses)
                semantic += Diagnostic(0, 0, "error", "object ${obj.objectId} references unknown class '${obj.classId}'")
            if (obj.allocSiteId != null && obj.allocSiteId !in knownSites)
                semantic += Diagnostic(0, 0, "error", "object ${obj.objectId} references unknown alloc site '${obj.allocSiteId}'")
        }
        edges.forEach { e ->
            if (e.src !in knownObjects) semantic += Diagnostic(0, 0, "error", "edge source ${e.src} is not a known object")
            if (e.dst !in knownObjects) semantic += Diagnostic(0, 0, "error", "edge target ${e.dst} is not a known object")
        }
        roots.forEach { r ->
            if (r.objectId !in knownObjects)
                semantic += Diagnostic(0, 0, "error", "root '${r.rootId}' references unknown object ${r.objectId}")
        }
        if (roots.isEmpty())
            semantic += Diagnostic(0, 0, "error", "snapshot has no roots; reachability is undefined")
        if (semantic.isNotEmpty())
            return Outcome.Unsolvable("semantic validation failed (${semantic.size} problem(s))", semantic)

        // 重复地址：合法，仅警告 —— 地址不当稳定身份。
        objects.groupingBy { it.address }.eachCount()
            .filter { it.value > 1 }.keys.sorted()
            .forEach { addr ->
                val ids = objects.filter { it.address == addr }.map { it.objectId }
                warnings += Diagnostic(0, 0, "warning",
                    "address 0x${addr.toString(16)} shared by objects $ids; address is not a stable identity")
            }

        val snapshot = Snapshot(
            snapshotId = head.id, name = head.name, parserVersion = head.parserVersion,
            capturedAt = head.capturedAt, rawText = text,
            classes = classes, allocSites = sites, objects = objects,
            edges = edges, roots = roots, incompleteBlocks = blocks,
        )
        return Outcome.Ok(ParsedSnapshot(snapshot, warnings))
    }

    private data class SnapshotHeader(val id: String, val name: String, val parserVersion: String, val capturedAt: Long)

    /** 支持双引号含空格 token；非法引号 -> 语法错误。 */
    private fun tokenize(line: String, lineNo: Int, errors: MutableList<Diagnostic>): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            if (line[i] == '"') {
                val end = line.indexOf('"', i + 1)
                if (end < 0) {
                    errors += Diagnostic(lineNo, i + 1, "error", "unterminated quoted string")
                    return null
                }
                out += line.substring(i, end + 1)
                i = end + 1
            } else {
                var j = i
                while (j < line.length && !line[j].isWhitespace()) j++
                out += line.substring(i, j)
                i = j
            }
        }
        return if (out.isEmpty()) null else out
    }

    private fun unquote(token: String): String =
        if (token.length >= 2 && token.first() == '"' && token.last() == '"') token.substring(1, token.length - 1) else token

    private fun kv(token: String): Pair<String, String>? {
        val eq = token.indexOf('=')
        if (eq <= 0) return null
        return token.substring(0, eq) to unquote(token.substring(eq + 1))
    }

    private fun parseLong(value: String, lineNo: Int, errors: MutableList<Diagnostic>, what: String): Long? =
        value.toLongOrNull() ?: run {
            errors += Diagnostic(lineNo, 1, "error", "invalid $what '$value' (expected integer)")
            null
        }

    private fun parseAddress(value: String, lineNo: Int, errors: MutableList<Diagnostic>): Long? =
        if (value.startsWith("0x") || value.startsWith("0X"))
            value.substring(2).toULongOrNull(16)?.toLong() ?: run {
                errors += Diagnostic(lineNo, 1, "error", "invalid address '$value' (expected 0x<hex>)")
                null
            }
        else run {
            errors += Diagnostic(lineNo, 1, "error", "invalid address '$value' (expected 0x<hex>)")
            null
        }

    private fun parseHeader(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): SnapshotHeader? {
        if (tokens.size < 2) {
            errors += Diagnostic(lineNo, 1, "error", "snapshot header requires an id")
            return null
        }
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val name = attrs["name"] ?: tokens[1]
        val parser = attrs["parser"] ?: run {
            errors += Diagnostic(lineNo, 1, "error", "snapshot header missing parser=<version>")
            return null
        }
        val captured = attrs["captured"]?.let { parseLong(it, lineNo, errors, "captured epoch millis") }
            ?: run {
                errors += Diagnostic(lineNo, 1, "error", "snapshot header missing captured=<epochMillis>")
                return null
            }
        return SnapshotHeader(tokens[1], name, parser, captured)
    }

    private fun parseClass(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): ClassMeta? {
        if (tokens.size < 2) {
            errors += Diagnostic(lineNo, 1, "error", "class directive requires a class id")
            return null
        }
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val name = attrs["name"] ?: run {
            errors += Diagnostic(lineNo, 1, "error", "class '${tokens[1]}' missing name=<fqcn>")
            return null
        }
        val size = attrs["size"]?.let { parseLong(it, lineNo, errors, "class size") } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "class '${tokens[1]}' missing size=<bytes>")
            return null
        }
        return ClassMeta(tokens[1], name, size, attrs["super"])
    }

    private fun parseSite(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): AllocSite? {
        if (tokens.size < 3) {
            errors += Diagnostic(lineNo, 1, "error", "site directive requires: site <id> sig=\"<signature>\"")
            return null
        }
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val sig = attrs["sig"] ?: run {
            errors += Diagnostic(lineNo, 1, "error", "site '${tokens[1]}' missing sig=\"...\"")
            return null
        }
        return AllocSite(tokens[1], sig)
    }

    private fun parseObject(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): HeapObject? {
        if (tokens.size < 2) {
            errors += Diagnostic(lineNo, 1, "error", "object directive requires an object id")
            return null
        }
        val id = parseLong(tokens[1], lineNo, errors, "object id") ?: return null
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val flags = tokens.drop(2).filter { kv(it) == null }.toSet()
        val addr = attrs["addr"]?.let { parseAddress(it, lineNo, errors) } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "object $id missing addr=0x<hex>")
            return null
        }
        val cls = attrs["class"] ?: run {
            errors += Diagnostic(lineNo, 1, "error", "object $id missing class=<classId>")
            return null
        }
        val size = attrs["size"]?.let { parseLong(it, lineNo, errors, "object size") } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "object $id missing size=<bytes>")
            return null
        }
        return HeapObject(id, addr, cls, size, attrs["site"], incomplete = "incomplete" in flags)
    }

    private fun parseEdge(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): HeapEdge? {
        // edge <src> -> <dst> kind=<strong|weak|internal>
        if (tokens.size < 5 || tokens[2] != "->") {
            errors += Diagnostic(lineNo, 1, "error", "edge syntax: edge <src> -> <dst> kind=<kind>")
            return null
        }
        val src = parseLong(tokens[1], lineNo, errors, "edge source") ?: return null
        val dst = parseLong(tokens[3], lineNo, errors, "edge target") ?: return null
        val attrs = tokens.drop(4).mapNotNull { kv(it) }.toMap()
        val kind = when (attrs["kind"]) {
            "strong" -> EdgeKind.STRONG
            "weak" -> EdgeKind.WEAK
            "internal" -> EdgeKind.INTERNAL
            else -> {
                errors += Diagnostic(lineNo, 1, "error", "edge $src -> $dst has unknown kind '${attrs["kind"]}'")
                return null
            }
        }
        return HeapEdge(src, dst, kind)
    }

    private fun parseRoot(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): Root? {
        if (tokens.size < 2) {
            errors += Diagnostic(lineNo, 1, "error", "root directive requires a root id")
            return null
        }
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val kind = attrs["kind"] ?: run {
            errors += Diagnostic(lineNo, 1, "error", "root '${tokens[1]}' missing kind=<kind>")
            return null
        }
        val obj = attrs["object"]?.let { parseLong(it, lineNo, errors, "root object") } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "root '${tokens[1]}' missing object=<objectId>")
            return null
        }
        return Root(tokens[1], kind, obj)
    }

    private fun parseBlock(tokens: List<String>, lineNo: Int, errors: MutableList<Diagnostic>): IncompleteBlock? {
        if (tokens.size < 2) {
            errors += Diagnostic(lineNo, 1, "error", "block directive requires a block id")
            return null
        }
        val attrs = tokens.drop(2).mapNotNull { kv(it) }.toMap()
        val offset = attrs["offset"]?.let { parseLong(it, lineNo, errors, "block offset") } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "block '${tokens[1]}' missing offset=<bytes>")
            return null
        }
        val length = attrs["length"]?.let { parseLong(it, lineNo, errors, "block length") } ?: run {
            errors += Diagnostic(lineNo, 1, "error", "block '${tokens[1]}' missing length=<bytes>")
            return null
        }
        return IncompleteBlock(tokens[1], offset, length, attrs["reason"] ?: "unspecified")
    }
}
