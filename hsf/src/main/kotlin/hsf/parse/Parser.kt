package hsf.parse

import hsf.model.*

/**
 * HPSF（Heap Proof Snapshot Format）解析器。
 * 行式、带位置信息。原则：
 *  - 不完整块内的坏行保留为不完整块，绝不静默丢材料；
 *  - 数值无法转换时留在原位置并挂 UnconvertibleValue 诊断；
 *  - 预算耗尽 / 格式损坏 / 语义无解是三种互斥结果。
 */
class SnapshotParser {

    private class Lexer(private val text: String) {
        private var idx = 0
        var line = 1; private var col = 1
        private fun peek(): Char? = text.getOrNull(idx)
        private fun advance(): Char? {
            val c = text.getOrNull(idx) ?: return null
            idx++; if (c == '\n') { line++; col = 1 } else col++
            return c
        }
        fun tokens(): Sequence<Tok> = sequence {
            while (true) {
                while (peek() != null && peek()!!.isWhitespace()) advance()
                val c = peek() ?: break
                val (l, colStart) = line to col
                if (c == '#') {
                    while (peek() != null && peek() != '\n') advance()
                    continue
                }
                val sb = StringBuilder()
                if (c == '"') {
                    advance()
                    while (true) {
                        val q = peek() ?: error("unterminated")
                        if (q == '"') { advance(); break }
                        if (q == '\\') {
                            advance(); val e = advance()
                            when (e) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t')
                                '"' -> sb.append('"'); '\\' -> sb.append('\\')
                                else -> sb.append(e ?: "")
                            }
                        } else { sb.append(advance() ?: break) }
                    }
                    yield(Tok(sb.toString(), l, colStart))
                } else {
                    while (peek() != null && !peek()!!.isWhitespace()) sb.append(advance()!!)
                    yield(Tok(sb.toString(), l, colStart))
                }
            }
        }
        data class Tok(val text: String, val line: Int, val col: Int)
    }

    fun parse(input: ImportInput): ImportOutcome {
        if (input.text.length > input.maxBytes)
            return ImportOutcome.BudgetExceeded(
                listOf(Diagnostic.Semantic("BUDGET-BYTES", null,
                    "输入 ${input.text.length}B 超过字节预算 ${input.maxBytes}B")), 0, 0)

        val lines = input.text.split('\n')
        val lexer = Lexer(input.text)
        val toks = lexer.tokens().toList()
        if (toks.none { it.line == 1 } && input.text.isNotBlank())
            return ImportOutcome.Corrupted(
                listOf(Diagnostic.ParseError("CORRUPT-EMPTY", Pos(1, 1, input.name), "首行无任何词法单元", input.text.take(200))))

        val byLine = LinkedHashMap<Int, MutableList<Lexer.Tok>>()
        for (t in toks) byLine.getOrPut(t.line) { mutableListOf() }.add(t)

        val parseErrors = mutableListOf<Diagnostic>()
        var directiveCount = 0
        var sawFormat = false
        var formatName = FORMAT_NAME; var formatVersion = FORMAT_VERSION
        var parserVersion: String? = null
        var snapshotId: String? = null; var label = ""
        var capturedIso: String? = null
        val classes = LinkedHashMap<String, RawClass>()
        val objects = LinkedHashMap<String, RawObject>()
        val edges = mutableListOf<RawEdge>()
        val roots = mutableListOf<RawRoot>()
        val sites = LinkedHashMap<String, RawAllocationSite>()
        val warnings = mutableListOf<Diagnostic>()
        val rawDirectives = mutableListOf<String>()
        val incompleteStarts = LinkedHashMap<String, Triple<Pos, Pos, String>>()
        val incompleteEnds = mutableSetOf<String>()
        var addressUnit = "bytes"; var sizeUnit = "bytes"
        var timeBasis = "snapshot-local"

        var objCount = 0; var edgeCount = 0

        fun rawLineOf(line: Int): String = lines.getOrNull(line - 1)?.trim() ?: ""
        fun pos(t: Lexer.Tok) = Pos(t.line, t.col, input.name)
        fun err(id: String, line: Int, col: Int, msg: String) {
            parseErrors += Diagnostic.ParseError(id, Pos(line, col, input.name), msg, rawLineOf(line))
        }

        for (lineNo in 1..lines.size) {
            val ts = byLine[lineNo] ?: continue
            val head = ts[0]
            rawDirectives.add(rawLineOf(lineNo))
            directiveCount++
            fun need(n: Int): Boolean {
                if (ts.size - 1 < n) {
                    err("ARG-COUNT", lineNo, ts.lastOrNull()?.col ?: 1,
                        "指令 ${head.text} 需要 $n 个参数，实得 ${ts.size - 1}")
                    return false
                }
                return true
            }
            try {
                when (head.text) {
                    "FORMAT" -> {
                        sawFormat = true
                        if (!need(1)) continue
                        val v = ts[1].text
                        val parts = v.split('/')
                        if (parts.size != 2 || parts[0] != FORMAT_NAME) {
                            err("FORMAT-NAME", lineNo, ts[1].col, "无法识别的格式标识 '$v'")
                            continue
                        }
                        formatVersion = parts[1].toIntOrNull() ?: run {
                            err("FORMAT-VERSION", lineNo, ts[1].col, "格式版本不是整数: '${parts[1]}'"); continue
                        }
                        if (formatVersion != FORMAT_VERSION)
                            err("FORMAT-UNSUPPORTED", lineNo, ts[1].col,
                                "只支持 $FORMAT_NAME/$FORMAT_VERSION，读到 $formatVersion")
                    }
                    "PARSER_VERSION" -> { if (need(1)) parserVersion = ts[1].text }
                    "SNAPSHOT" -> {
                        if (!need(2)) continue
                        snapshotId = ts[1].text; label = ts[2].text
                        if (ts.size >= 4) { capturedIso = ts[3].text; timeBasis = ts[4].text.takeIf { it.isNotBlank() } ?: timeBasis }
                    }
                    "UNITS" -> {
                        if (ts.size >= 2) addressUnit = ts[1].text
                        if (ts.size >= 3) sizeUnit = ts[2].text
                    }
                    "CLASS" -> {
                        if (!need(3)) continue
                        val (idTok, nameTok) = ts[1] to ts[2]
                        val fields = if (ts.size >= 4)
                            ts[3].text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        else emptyList()
                        val name = unescape(nameTok.text)
                        if (classes.containsKey(idTok.text) && classes[idTok.text]!!.name != name) {
                            warnings += Diagnostic.Semantic("CLASS-ID-REDEFINE", pos(idTok),
                                "类 id ${idTok.text} 绑定了不同名称: '${classes[idTok.text]!!.name}' vs '$name'",
                                listOf(idTok.text))
                        }
                        if (classes.containsKey(idTok.text)) continue
                        classes[idTok.text] = RawClass(idTok.text, nameTok.text, name, fields, pos(idTok), rawLineOf(lineNo))
                    }
                    "SITE" -> {
                        if (!need(3)) continue
                        val id = ts[1].text
                        if (sites.containsKey(id)) {
                            warnings += Diagnostic.Semantic("SITE-DUP", pos(ts[1]), "allocation site id 重复: $id", listOf(id)); continue
                        }
                        sites[id] = RawAllocationSite(id, unescape(ts[2].text), unescape(ts.getOrNull(3)?.text ?: ""), pos(ts[1]), rawLineOf(lineNo))
                    }
                    "OBJECT" -> {
                        if (++objCount > input.maxObjects)
                            return ImportOutcome.BudgetExceeded(
                                listOf(Diagnostic.Semantic("BUDGET-OBJECTS", pos(head),
                                    "对象数超过预算 ${input.maxObjects}")), objCount, edgeCount)
                        if (!need(4)) continue
                        val id = ts[1]; val addrTok = ts[2]; val clsTok = ts[3]; val sizeTok = ts[4]
                        val addr = parseAddress(addrTok.text)
                        if (addr == null) warnings += Diagnostic.UnconvertibleValue(
                            "ADDR-UNPARSEABLE", pos(addrTok), "地址无法按 $addressUnit 解析，原始值保留", addrTok.text, addressUnit)
                        val shallow = parseLong(sizeTok.text)
                        if (shallow == null) warnings += Diagnostic.UnconvertibleValue(
                            "SIZE-UNPARSEABLE", pos(sizeTok), "shallow 大小无法转换为 $sizeUnit，原始值保留", sizeTok.text, sizeUnit)
                        val siteRef = ts.getOrNull(5)?.text?.takeIf { it != "-" }
                        if (siteRef != null && !sites.containsKey(siteRef))
                            warnings += Diagnostic.Semantic("SITE-MISSING", pos(head),
                                "对象 ${id.text} 引用了未知 allocation site $siteRef", listOf(id.text, siteRef))
                        val clsMissing = !classes.containsKey(clsTok.text)
                        if (clsMissing) warnings += Diagnostic.Semantic("CLASS-MISSING", pos(clsTok),
                            "对象 ${id.text} 引用未知类 ${clsTok.text}", listOf(id.text, clsTok.text))
                        if (objects.containsKey(id.text)) {
                            warnings += Diagnostic.Semantic("OBJECT-ID-DUP", pos(id),
                                "对象 id 重复: ${id.text}", listOf(id.text)); continue
                        }
                        objects[id.text] = RawObject(id.text, addrTok.text, addr, clsTok.text,
                            sizeTok.text, shallow, siteRef, false, pos(id), rawLineOf(lineNo), clsMissing)
                    }
                    "EDGE" -> {
                        if (++edgeCount > input.maxEdges)
                            return ImportOutcome.BudgetExceeded(
                                listOf(Diagnostic.Semantic("BUDGET-EDGES", pos(head),
                                    "边数超过预算 ${input.maxEdges}")), objCount, edgeCount)
                        if (!need(4)) continue
                        val kindTok = ts[1]; val from = ts[2]; val to = ts[3]
                        val kind = runCatching { EdgeKind.valueOf(kindTok.text.uppercase()) }.getOrNull()
                        if (kind == null) { err("EDGE-KIND", lineNo, kindTok.col, "未知边种类 '${kindTok.text}'（STRONG/WEAK/INTERNAL）"); continue }
                        val missingFrom = !objects.containsKey(from.text)
                        val missingTo = !objects.containsKey(to.text)
                        if (missingFrom) warnings += Diagnostic.Semantic("EDGE-SRC-MISSING", pos(from),
                            "边起点不存在: ${from.text}", listOf(from.text))
                        if (missingTo) warnings += Diagnostic.Semantic("EDGE-DST-MISSING", pos(to),
                            "边终点不存在: ${to.text}", listOf(to.text))
                        edges += RawEdge("e${edges.size + 1}", kind, from.text, to.text,
                            unescape(ts.getOrNull(4)?.text ?: ""), pos(from), rawLineOf(lineNo), missingTo, missingFrom)
                    }
