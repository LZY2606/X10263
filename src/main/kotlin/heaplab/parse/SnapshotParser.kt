package heaplab.parse

import heaplab.model.*

/**
 * 堆影取证室离线快照格式（HSPL, Heap Shadow Proof Lines）。
 *
 * 这是面向内存分析的领域格式，不是通用表格。行结构：
 *   META parser=<ver> dialect=<dialect> taken-at=<iso|RAW:..> size-unit=BYTE
 *   CLASS <classId> name=<原始类名> [object=<classObjectId>] [extends=<superId>] [loader=<loaderObjId>]
 *   SITE  <siteId> location=<原始坐标> [class=..] [method=..] [line=..]
 *   OBJ   <rawObjectId> addr=<原始地址> class=<classId> size=<原始大小> [site=<siteId>] root=<ROOTKIND|NON_ROOT> [incomplete=<chunkId>]
 *   EDGE  <edgeId> <fromObjectId> -> <toObjectId> kind=STRONG|WEAK|INTERNAL|SOFT [field=<原始字段>] [index=<i>] [incomplete]
 *   CHUNK_BEGIN <chunkId> reason=<原因>
 *   ... 不完整原文（无法解析的 OBJ/EDGE 行整体保留）
 *   CHUNK_END <chunkId>
 *
 * 规则：
 *  - 无法转换的 size/address 保留原始表示，sizeBytes/address=null 并产出带行号的诊断；不抛异常、不丢行。
 *  - CHUNK_BEGIN..CHUNK_END 内的行原文保留到 IncompleteChunk，其中能解析出的 OBJ 标记 incomplete=true。
 *  - 语法损坏（截断的 CHUNK、未知 section、字段缺失）产出 CorruptFormatException（带 line/chunkId）。
 */
class SnapshotParser(
    private val snapshotId: String,
    private val label: String
) {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val classes = mutableListOf<ClassMeta>()
    private val sites = mutableListOf<AllocationSite>()
    private val objects = mutableListOf<HeapObject>()
    private val edges = mutableListOf<HeapEdge>()
    private val chunks = mutableListOf<IncompleteChunk>()

    private var parserVersion = "unknown"
    private var dialect = "hspl/1"
    private var takenAtRaw: String? = null
    private var takenAtEpoch: Long? = null
    private var sizeUnit = "BYTE"

    fun parse(raw: String, sha256: String, rawSize: Long): Snapshot {
        val lines = raw.replace("\r\n", "\n").split("\n")
        var i = 0
        var activeChunk: String? = null
        var chunkReason = ""
        val chunkLines = mutableListOf<Pair<Int, String>>()
        var chunkStart = 0

        fun closeChunk(endLine: Int) {
            val cid = activeChunk ?: fail("CHUNK_END 没有匹配的 CHUNK_BEGIN", endLine)
            chunks += IncompleteChunk(
                chunkId = cid,
                rawText = chunkLines.joinToString("\n") { it.second },
                startLine = chunkStart,
                endLine = endLine,
                reason = chunkReason
            )
            activeChunk = null
            chunkLines.clear()
        }

        while (i < lines.size) {
            val lineNo = i + 1
            val rawLine = lines[i]
            val line = rawLine.trim()
            i++
            if (line.isEmpty() || line.startsWith("#")) continue

            if (activeChunk != null) {
                if (line.startsWith("CHUNK_END")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2 || parts[1] != activeChunk)
                        fail("CHUNK_END id 与 CHUNK_BEGIN 不匹配", lineNo, activeChunk)
                    closeChunk(lineNo)
                    continue
                }
                chunkLines += lineNo to rawLine
                if (line.startsWith("OBJ")) {
                    parseObjLine(line, lineNo, activeChunk)
                } else if (line.startsWith("EDGE")) {
                    parseEdgeLine(line, lineNo, incompleteChunk = activeChunk)
                }
                continue
            }

            when {
                line.startsWith("CHUNK_BEGIN") -> {
                    val p = tokens(line)
                    val cid = p.firstValueOrNull("CHUNK_BEGIN")
                        ?: fail("CHUNK_BEGIN 缺少 chunkId", lineNo)
                    activeChunk = cid
                    chunkReason = p.value("reason") ?: "未注明的不完整块"
                    chunkStart = lineNo
                    chunkLines.clear()
                }
                line.startsWith("META") -> parseMeta(line, lineNo)
                line.startsWith("CLASS") -> parseClass(line, lineNo)
                line.startsWith("SITE") -> parseSite(line, lineNo)
                line.startsWith("OBJ") -> parseObjLine(line, lineNo, null)
                line.startsWith("EDGE") -> parseEdgeLine(line, lineNo, null)
                else -> fail("未知 section 或损坏行: ${line.take(40)}", lineNo)
            }
        }
        if (activeChunk != null) fail("CHUNK_BEGIN '$activeChunk' 未闭合（截断）", lines.size, activeChunk)

        validateReferences(lines.size)

        return Snapshot(
            snapshotId = snapshotId,
            label = label,
            coordinate = SnapshotCoordinate(
                takenAtRaw = takenAtRaw,
                takenAtEpochMs = takenAtEpoch,
                sizeUnit = sizeUnit
            ),
            parser = ParserInfo(parserVersion, dialect, takenAtRaw),
            objects = objects.sortedWith(compareBy({ it.rawObjectId }, { it.addressRaw })),
            edges = edges.sortedWith(compareBy({ it.fromObjectId }, { it.toObjectId }, { it.edgeId })),
            classes = classes.sortedBy { it.classId },
            sites = sites.sortedBy { it.siteId },
            incompleteChunks = chunks.sortedBy { it.startLine },
            diagnostics = diagnostics.sortedWith(compareBy({ it.line ?: 0 }, { it.code })),
            rawBytesSha256 = sha256,
            rawSizeBytes = rawSize
        )
    }

    private fun parseMeta(line: String, ln: Int) {
        val p = tokens(line)
        parserVersion = p.value("parser") ?: fail("META 缺少 parser 版本", ln)
        dialect = p.value("dialect") ?: "hspl/1"
        sizeUnit = p.value("size-unit") ?: "BYTE"
        takenAtRaw = p.value("taken-at")
        takenAtEpoch = takenAtRaw?.let { raw ->
            if (raw.startsWith("RAW:")) null
            else raw.toLongOrNull() ?: run {
                diag("TIME_UNPARSEABLE", "taken-at 无法转换为 ${"EPOCH_MS"}: $raw", ln)
                null
            }
        }
    }

    private fun parseClass(line: String, ln: Int) {
        val p = tokens(line)
        val id = p.positional(1) ?: fail("CLASS 缺少 classId", ln)
        val name = p.value("name") ?: fail("CLASS $id 缺少 name", ln)
        classes += ClassMeta(
            classId = id,
            nameRaw = name,
            classObjectId = p.value("object"),
            superClassId = p.value("extends"),
            classLoaderObjectId = p.value("loader")
        )
    }

    private fun parseSite(line: String, ln: Int) {
        val p = tokens(line)
        val id = p.positional(1) ?: fail("SITE 缺少 siteId", ln)
        sites += AllocationSite(
            siteId = id,
            locationRaw = p.value("location") ?: fail("SITE $id 缺少 location", ln),
            classNameRaw = p.value("class"),
            methodRaw = p.value("method"),
            line = p.value("line")?.toIntOrNull()
        )
    }

    private fun parseObjLine(line: String, ln: Int, chunk: String?) {
        val p = tokens(line)
        val id = p.positional(1) ?: fail("OBJ 缺少 rawObjectId", ln, chunk)
        val addrRaw = p.value("addr") ?: fail("OBJ $id 缺少 addr", ln, chunk)
        val classId = p.value("class") ?: fail("OBJ $id 缺少 class", ln, chunk)
        val sizeRaw = p.value("size") ?: fail("OBJ $id 缺少 size", ln, chunk)
        val rootRaw = p.value("root") ?: "NON_ROOT"
        val root = runCatching { RootKind.valueOf(rootRaw) }.getOrElse {
            diag("UNKNOWN_ROOT_KIND", "未知 root 分类 '$rootRaw'，按 UNKNOWN 处理", ln, objectId = id)
            RootKind.UNKNOWN
        }
        val addr = parseAddr(addrRaw, ln, id)
        val size = parseSize(sizeRaw, ln, id)
        objects += HeapObject(
            rawObjectId = id,
            addressRaw = addrRaw,
            address = addr,
            classId = classId,
            sizeRaw = sizeRaw,
            sizeBytes = size,
            allocationSiteId = p.value("site"),
            rootKind = root,
            correspondenceKey = p.value("corr"),
            incomplete = chunk != null,
            incompleteChunkId = chunk
        )
    }

    private fun parseEdgeLine(line: String, ln: Int, incompleteChunk: String?) {
        val p = tokens(line)
        val eid = p.positional(1) ?: fail("EDGE 缺少 edgeId", ln, incompleteChunk)
        val from = p.positional(2) ?: fail("EDGE $eid 缺少 from", ln, incompleteChunk)
        val arrow = p.positional(3)
        if (arrow != "->") fail("EDGE $eid 缺少 '->'", ln, incompleteChunk)
        val to = p.positional(4) ?: fail("EDGE $eid 缺少 to", ln, incompleteChunk)
        val kindRaw = p.value("kind") ?: fail("EDGE $eid 缺少 kind", ln, incompleteChunk)
        val kind = runCatching { EdgeKind.valueOf(kindRaw) }.getOrElse {
            fail("EDGE $eid 未知边类型 '$kindRaw'", ln, incompleteChunk)
        }
        edges += HeapEdge(
            edgeId = eid,
            fromObjectId = from,
            toObjectId = to,
            kind = kind,
            fieldRaw = p.value("field"),
            index = p.value("index")?.toIntOrNull(),
            incomplete = incompleteChunk != null
        )
    }

    private fun parseAddr(raw: String, ln: Int, objId: String): Long? {
        val v = raw.removePrefix("0x").removePrefix("0X")
        return v.toLongOrNull(16) ?: run {
            diag("ADDRESS_UNPARSEABLE", "地址无法转换（保留原始表示）: $raw", ln, objectId = objId)
            null
        }
    }

    private fun parseSize(raw: String, ln: Int, objId: String): Long? =
        raw.toLongOrNull() ?: run {
            diag("SIZE_UNPARSEABLE", "大小无法转换为字节（保留原始表示）: $raw", ln, objectId = objId)
            null
        }

    private fun validateReferences(ln: Int) {
        val objIds = objects.map { it.rawObjectId }.toSet()
        val classIds = classes.map { it.classId }.toSet()
        val siteIds = sites.map { it.siteId }.toSet()
        val seenObj = mutableSetOf<String>()
        for (o in objects) {
            if (!seenObj.add(o.rawObjectId))
                diag("DUPLICATE_OBJECT_ID", "重复的原始对象 id（材料层保留两条；身份以 snapshotId+rawObjectId+序号区分需上层裁决）: ${o.rawObjectId}",
                    null, objectId = o.rawObjectId)
            if (o.classId !in classIds)
                diag("DANGLING_CLASS_REF", "对象 ${o.rawObjectId} 引用缺失类 ${o.classId}", null, objectId = o.rawObjectId)
            o.allocationSiteId?.let { if (it !in siteIds) diag("DANGLING_SITE_REF", "对象 ${o.rawObjectId} 引用缺失 allocation site $it", null, objectId = o.rawObjectId) }
        }
        // 重复地址：合法但必须诊断——地址不能当稳定身份。
        val byAddr = objects.mapNotNull { it.address?.let { a -> it.rawObjectId to a } }
            .groupBy({ it.second }, { it.first }).filter { it.value.size > 1 }
        for ((addr, ids) in byAddr) {
            diag("DUPLICATE_ADDRESS", "地址 0x${addr.toString(16)} 被多个对象使用: ${ids.sorted().joinToString()}；地址不作为身份",
                null, objectId = ids.first())
        }
        for (e in edges) {
            if (e.fromObjectId !in objIds) diag("DANGLING_EDGE_FROM", "边 ${e.edgeId} 的源对象缺失: ${e.fromObjectId}", null)
            if (e.toObjectId !in objIds) diag("DANGLING_EDGE_TO", "边 ${e.edgeId} 的目标对象缺失: ${e.toObjectId}", null)
        }
    }

    private fun diag(code: String, msg: String, ln: Int? = null, chunk: String? = null, objectId: String? = null) {
        diagnostics += Diagnostic(code, msg, line = ln, chunkId = chunk, objectId = objectId)
    }

    private fun fail(msg: String, ln: Int? = null, chunk: String? = null): Nothing =
        throw CorruptFormatException(msg, line = ln, chunkId = chunk)

    private class Tokens(private val items: List<String>, private val named: Map<String, String>) {
        fun positional(i: Int): String? = items.getOrNull(i)
        fun value(k: String): String? = named[k]
        fun firstValueOrNull(k: String): String? = items.drop(1).firstOrNull()
    }

    private fun tokens(line: String): Tokens {
        val parts = line.split(Regex("\\s+"))
        val named = mutableMapOf<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq > 0) named[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return Tokens(parts, named)
    }
}
