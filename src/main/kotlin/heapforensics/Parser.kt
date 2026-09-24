package heapforensics

/**
 * Line-oriented heap snapshot format ("heapdump-lite"). Raw text is always
 * preserved on the snapshot; this parser only derives structured rows.
 *
 *   # heap-snapshot v1
 *   parser: heapdump-lite/1.2.0
 *   class C1 name=com.acme.Foo
 *   site S1 class=com.acme.FooLoader line=42
 *   object O1 addr=0x1000 class=C1 size=32 site=S1
 *   object O9 addr=0x9000 class=C2 size=?? incomplete=truncated-block
 *   edge O1 -> O2 kind=strong name=items
 *   root O1 type=JNI_GLOBAL
 *
 * Structural corruption (bad header / malformed line) -> CorruptFormat.
 * Unconvertible field values stay in place with a positioned diagnostic.
 */
object Parser {

    fun parse(snapshotId: String, name: String, text: String): Outcome<SnapshotData> {
        val lines = text.lines()
        val header = lines.firstOrNull { it.isNotBlank() }
            ?: return Outcome.CorruptFormat(listOf(Diagnostic("line:0", "error", "empty snapshot")))
        if (!header.trim().startsWith("# heap-snapshot"))
            return Outcome.CorruptFormat(listOf(Diagnostic("line:1", "error", "missing '# heap-snapshot' header")))

        var parserVersion = "unknown"
        val classes = mutableListOf<ClassRec>()
        val objects = mutableListOf<ObjectRec>()
        val edges = mutableListOf<EdgeRec>()
        val roots = mutableListOf<RootRec>()
        val sites = mutableListOf<SiteRec>()
        val diags = mutableListOf<Diagnostic>()
        val corrupt = mutableListOf<Diagnostic>()

        lines.forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val pos = "line:$lineNo"
            val tokens = line.split(Regex("\\s+"))
            when (tokens[0]) {
                "parser:" -> parserVersion = tokens.getOrElse(1) { "unknown" }
                "class" -> {
                    val kv = keyValues(tokens.drop(1))
                    val id = tokens.getOrNull(1)
                    val cname = kv["name"]
                    if (id == null || cname == null) corrupt += Diagnostic(pos, "error", "malformed class line: '$line'")
                    else classes += ClassRec(id, cname)
                }
                "site" -> {
                    val kv = keyValues(tokens.drop(1))
                    val id = tokens.getOrNull(1)
                    val cn = kv["class"]; val ln = kv["line"]?.toIntOrNull()
                    if (id == null || cn == null || ln == null) corrupt += Diagnostic(pos, "error", "malformed site line: '$line'")
                    else sites += SiteRec(id, cn, ln)
                }
                "object" -> {
                    val kv = keyValues(tokens.drop(1))
                    val id = tokens.getOrNull(1)
                    val addr = kv["addr"]; val cls = kv["class"]; val sizeRaw = kv["size"]
                    if (id == null || addr == null || cls == null || sizeRaw == null) {
                        corrupt += Diagnostic(pos, "error", "malformed object line: '$line'")
                    } else {
                        val size = sizeRaw.toLongOrNull()
                        if (size == null)
                            diags += Diagnostic(pos, "warning", "object $id size '$sizeRaw' not convertible to bytes; left in place")
                        val incomplete = kv["incomplete"]
                        if (incomplete != null)
                            diags += Diagnostic(pos, "info", "object $id kept as incomplete block: $incomplete")
                        objects += ObjectRec(id, addr, cls, sizeRaw, size, kv["site"],
                            incomplete = incomplete != null, incompleteReason = incomplete)
                    }
                }
                "edge" -> {
                    // edge O1 -> O2 kind=strong name=items
                    if (tokens.size < 4 || tokens[2] != "->") {
                        corrupt += Diagnostic(pos, "error", "malformed edge line: '$line'")
                    } else {
                        val kv = keyValues(tokens.drop(4))
                        val kind = when (kv["kind"]) {
                            "strong" -> EdgeKind.STRONG
                            "weak" -> EdgeKind.WEAK
                            "internal" -> EdgeKind.INTERNAL
                            else -> null
                        }
                        if (kind == null) corrupt += Diagnostic(pos, "error", "unknown edge kind in: '$line'")
                        else edges += EdgeRec(tokens[1], tokens[3], kind, kv["name"] ?: "")
                    }
                }
                "root" -> {
                    val kv = keyValues(tokens.drop(1))
                    val id = tokens.getOrNull(1); val type = kv["type"]
                    if (id == null || type == null) corrupt += Diagnostic(pos, "error", "malformed root line: '$line'")
                    else roots += RootRec(id, type)
                }
                else -> corrupt += Diagnostic(pos, "error", "unrecognized statement: '$line'")
            }
        }
        if (corrupt.isNotEmpty()) return Outcome.CorruptFormat(corrupt + diags)
        return Outcome.Ok(SnapshotData(snapshotId, name, parserVersion, text,
            classes, objects, edges, roots, sites, diags))
    }

    private fun keyValues(tokens: List<String>): Map<String, String> =
        tokens.mapNotNull { t ->
            val i = t.indexOf('=')
            if (i <= 0) null else t.substring(0, i) to t.substring(i + 1)
        }.toMap()
}
