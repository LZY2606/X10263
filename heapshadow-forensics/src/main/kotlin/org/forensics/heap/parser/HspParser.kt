package org.forensics.heap.parser

import org.forensics.heap.model.*
import java.security.MessageDigest

/** 格式损坏（硬性失败）：带行号与原始片段，与语义诊断、预算耗尽严格区分。 */
class FormatCorruptException(
    message: String,
    val line: Int,
    val fragment: String,
    cause: Throwable? = null
) : Exception("第 ${line} 行格式损坏：$message（片段：$fragment）", cause)

/**
 * HSP（Heap Shadow Profile，v1）行格式：
 *   RECORD key=value key="quoted value" ...
 * 以 # 开头为注释；BEGIN_INCOMPLETE/END_INCOMPLETE 之间的行原样保留且不解析。
 *
 * 设计约束：
 *  - 不就地覆写任何原始表示；addr/size 原文一律保留。
 *  - 硬性损坏（未知记录、缺必填字段、枚举非法、id 冲突）抛 [FormatCorruptException]。
 *  - 软性语义问题（地址/尺寸不可解析、重复地址、悬空边、缺类、未知 root 分类）记为诊断，材料保留。
 */
class HspParser(private val text: String) {

    private val diagnostics = mutableListOf<Diagnostic>()
    private val sourceLines = text.replace("\r\n", "\n").split("\n")

    fun parse(): RawSnapshot {
        var header: SnapHeader? = null
        var units = UnitBasis()
        val sites = linkedMapOf<String, AllocationSite>()
        val classes = linkedMapOf<String, RawClass>()
        val objects = linkedMapOf<String, RawObject>()
        val edges = mutableListOf<RawEdge>()
        val roots = mutableListOf<RawRoot>()
        val blocks = mutableListOf<IncompleteBlock>()
        val seenAddresses = HashMap<Long, String>()

        var lineNo = 0
        val lines = sourceLines
        while (lineNo < lines.size) {
            val rawLine = lines[lineNo]
            val current = lineNo + 1
            lineNo++
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("BEGIN_INCOMPLETE")) {
                val fields = fields(trimmed, current)
                val blockId = fields.require("id", current, trimmed)
                val reason = fields.require("reason", current, trimmed)
                val start = current
                val body = StringBuilder()
                var endLine: Int? = null
                while (lineNo < lines.size) {
                    val inner = lines[lineNo]
                    lineNo++
                    if (inner.trim().startsWith("END_INCOMPLETE")) {
                        endLine = lineNo
                        break
                    }
                    body.append(inner).append('\n')
                }
                if (endLine == null) {
                    diagnostics += Diagnostic(
                        Diagnostic.INCOMPLETE_BLOCK, Severity.ERROR,
                        "不完整块 $blockId 在文件结束前未闭合（reason=$reason）",
                        start, fragment = trimmed,
                    )
                }
                blocks += IncompleteBlock(blockId, start, endLine, body.toString().trimEnd('\n'), reason)
                continue
            }

            val sp = trimmed.indexOf(' ')
            val keyword = if (sp < 0) trimmed else trimmed.substring(0, sp)
            val rest = if (sp < 0) "" else trimmed.substring(sp + 1)
            val f = fields(rest, current)

            when (keyword) {
                "SNAPSHOT" -> {
                    if (header != null) throw FormatCorruptException("出现重复 SNAPSHOT 头", current, trimmed)
                    val parserVersion = f.require("parser", current, trimmed)
                    header = SnapHeader(
                        id = f.require("id", current, trimmed),
                        identity = f.require("identity", current, trimmed),
                        name = f.getOrElse("name") { f.require("id", current, trimmed) },
                        parserVersion = parserVersion,
                        capturedAtRaw = f.require("captured-at", current, trimmed)
                    )
                    f["time"]?.let { /* 时间基准在 UNIT 行处理 */ }
                }
                "UNIT" -> units = UnitBasis(
                    sizeUnit = f.getOrElse("size") { "byte" },
                    addressBase = f.getOrElse("address") { "hex-runtime-heap" },
                    timeBasis = f.getOrElse("time") { "ISO-8601/UTC millis" },
                    coordinateSystem = f.getOrElse("coordinate") { "runtime-heap-offset" }
                )
                "SITE" -> {
                    val id = f.require("id", current, trimmed)
                    sites[id] = AllocationSite(
                        id = id,
                        className = f.require("class", current, trimmed),
                        method = f.require("method", current, trimmed),
                        lineRaw = f.require("line", current, trimmed),
                        fileName = f["file"]
                    )
                }
                "CLASS" -> {
                    val id = f.require("id", current, trimmed)
                    if (classes.containsKey(id)) throw FormatCorruptException("类 id 重复：$id", current, trimmed)
                    classes[id] = RawClass(
                        classId = id,
                        rawName = f.require("name", current, trimmed),
                        superclassId = f["super"]?.ifBlank { null },
                        classLoaderObjectId = f["cls-loader"]?.ifBlank { null },
                        instanceSizeRaw = f["instance-size"],
                        parserVersion = header?.parserVersion ?: "unknown",
                        siteId = f["site"]
                    )
                }
                "OBJECT", "UNREACHABLE" -> {
                    // 允许 "UNREACHABLE OBJECT ..." 跨行首关键字写法
                    val (kw2, fields2) = if (keyword == "UNREACHABLE") {
                        val sp2 = rest.indexOf(' ')
                        val k2 = if (sp2 < 0) rest else rest.substring(0, sp2)
                        if (k2 != "OBJECT") throw FormatCorruptException("UNREACHABLE 后必须是 OBJECT", current, trimmed)
                        k2 to fields(if (sp2 < 0) "" else rest.substring(sp2 + 1), current)
                    } else keyword to f
                    val obj = parseObject(fields2, current, trimmed, header, unreachable = keyword == "UNREACHABLE")
                    if (objects.containsKey(obj.objectId)) {
                        throw FormatCorruptException("对象 id 冲突：${obj.objectId}（稳定身份必须唯一）", current, trimmed)
                    }
                    objects[obj.objectId] = obj
                    normalizeAddress(obj)?.let { addr ->
                        val prev = seenAddresses.put(addr, obj.objectId)
                        if (prev != null) {
                            diagnostics += Diagnostic(
                                Diagnostic.DUPLICATE_ADDRESS, Severity.WARNING,
                                "地址 ${obj.addressRaw} 同时被对象 $prev 与 ${obj.objectId} 使用；地址不作为稳定身份，二者均保留",
                                current, fragment = trimmed, objectId = obj.objectId
                            )
                        }
                    }
                }
                "ROOT" -> {
                    val oid = f.require("object", current, trimmed)
                    val kindRaw = f.require("kind", current, trimmed)
                    val kind = RootKind.parse(kindRaw)
                    if (kind == RootKind.UNKNOWN) {
                        diagnostics += Diagnostic(
                            Diagnostic.ROOT_UNKNOWN_KIND, Severity.WARNING,
                            "对象 $oid 的 root 分类 '$kindRaw' 无法识别，按 UNKNOWN 保留原文",
                            current, fragment = trimmed, objectId = oid
                        )
                    }
                    roots += RawRoot(oid, kind, kindRaw, current)
                }
                "EDGE" -> edges += parseEdge(f, current, trimmed, header)
                else -> throw FormatCorruptException("未知记录类型 '$keyword'", current, trimmed)
            }
        }

        val head = header ?: throw FormatCorruptException("缺少 SNAPSHOT 头", 1, sourceLines.firstOrNull() ?: "")

        // 内联 root：补登记为正式 ROOT 记录（对象行 root=... 与独立 ROOT 行等价）
        for (obj in objects.values) {
            if (obj.rootRaw != null) {
                val kind = obj.rootKind ?: RootKind.UNKNOWN
                if (kind == RootKind.UNKNOWN) {
                    diagnostics += Diagnostic(
                        Diagnostic.ROOT_UNKNOWN_KIND, Severity.WARNING,
                        "对象 ${obj.objectId} 的内联 root 分类 '${obj.rootRaw}' 无法识别，按 UNKNOWN 保留原文",
                        obj.sourceLine, fragment = sourceLines[obj.sourceLine - 1], objectId = obj.objectId
                    )
                }
                roots += RawRoot(obj.objectId, kind, obj.rootRaw, obj.sourceLine)
            }
        }

        // 悬空边 / 缺类 / 内联 root 的语义检查（材料保留，仅出诊断）
        for (edge in edges) {
            if (objects[edge.fromObjectId] == null || objects[edge.toObjectId] == null) {
                diagnostics += Diagnostic(
                    Diagnostic.DANGLING_EDGE, Severity.ERROR,
                    "边 ${edge.edgeId} 引用了不存在的对象（from=${edge.fromObjectId}, to=${edge.toObjectId}），边保留但不进入图计算",
                    edge.sourceLine, objectId = edge.fromObjectId
                )
            }
        }
        for (root in roots) {
            if (objects[root.objectId] == null) {
                diagnostics += Diagnostic(
                    Diagnostic.DANGLING_EDGE, Severity.ERROR,
                    "ROOT 记录引用了不存在的对象 ${root.objectId}",
                    root.sourceLine, objectId = root.objectId
                )
            }
        }
        for (obj in objects.values) {
            if (classes[obj.classId] == null) {
                diagnostics += Diagnostic(
                    Diagnostic.MISSING_CLASS, Severity.WARNING,
                    "对象 ${obj.objectId} 引用的类 ${obj.classId} 不存在，类元数据缺失但对象保留",
                    obj.sourceLine, fragment = sourceLines[obj.sourceLine - 1], objectId = obj.objectId
                )
            }
        }

        val checksum = sha256(text)
        return RawSnapshot(
            snapshotId = head.id,
            identityKey = head.identity,
            displayName = head.name,
            parserVersion = head.parserVersion,
            capturedAtRaw = head.capturedAtRaw,
            units = units,
            classes = classes.values.toList(),
            objects = objects.values.toList(),
            edges = edges,
            sites = sites.values.toList(),
            roots = roots,
            incompleteBlocks = blocks,
            diagnostics = diagnostics.sortedWith(compareBy({ it.line ?: 0 }, { it.code })),
            sourceLines = sourceLines,
            checksumSha256 = checksum
        )
    }

    private fun parseObject(
        f: FieldMap, line: Int, fragment: String, header: SnapHeader?, unreachable: Boolean
    ): RawObject {
        val id = f.require("id", line, fragment)
        val incomplete = f["incomplete"]?.equals("true", ignoreCase = true) == true
        val sizeRaw = if (incomplete) f.getOrElse("size") { "?" } else f.require("size", line, fragment)
        val codes = mutableListOf<String>()
        val sizeNorm = parseSize(sizeRaw)
        if (sizeNorm == null) {
            codes += Diagnostic.UNPARSEABLE_SIZE
            diagnostics += Diagnostic(
                Diagnostic.UNPARSEABLE_SIZE, Severity.ERROR,
                "对象 $id 的尺寸 '$sizeRaw' 无法规范化为字节；原文保留，不参与精确保留大小求和",
                line, fragment = fragment, objectId = id
            )
        }
        val addrRaw = f.require("addr", line, fragment)
        if (parseAddress(addrRaw) == null) {
            codes += Diagnostic.UNPARSEABLE_ADDR
            diagnostics += Diagnostic(
                Diagnostic.UNPARSEABLE_ADDR, Severity.ERROR,
                "对象 $id 的地址 '$addrRaw' 无法解析；地址仅用于展示，不影响稳定身份",
                line, fragment = fragment, objectId = id
            )
        }
        if (incomplete) {
            codes += Diagnostic.INCOMPLETE_BLOCK
            diagnostics += Diagnostic(
                Diagnostic.INCOMPLETE_BLOCK, Severity.WARNING,
                "对象 $id 来自不完整记录（incomplete=true），字段可能缺失",
                line, fragment = fragment, objectId = id
            )
        }
        val rootKindRaw = f["root"]
        val rootKind = rootKindRaw?.let { RootKind.parse(it) }
        if (rootKindRaw != null) {
            // 内联 root 由调用方在拿到对象后统一登记；此处先挂到对象上
        }
        return RawObject(
            objectId = id,
            addressRaw = addrRaw,
            classId = f.require("class", line, fragment),
            shallowSizeRaw = sizeRaw,
            siteId = f["site"],
            parserVersion = header?.parserVersion ?: "unknown",
            complete = !incomplete,
            sourceLine = line,
            rootKind = rootKind,
            rootRaw = rootKindRaw,
            unreachable = unreachable,
            diagnosticCodes = codes
        )
    }

    private fun parseEdge(f: FieldMap, line: Int, fragment: String, header: SnapHeader?): RawEdge {
        val kindRaw = f.require("kind", line, fragment)
        val kind = runCatching { EdgeKind.valueOf(kindRaw.uppercase()) }.getOrElse {
            throw FormatCorruptException("边类型非法 '$kindRaw'（允许 STRONG/WEAK/SOFT/INTERNAL）", line, fragment)
        }
        return RawEdge(
            edgeId = f.require("id", line, fragment),
            fromObjectId = f.require("from", line, fragment),
            toObjectId = f.require("to", line, fragment),
            kind = kind,
            label = f["label"],
            sourceLine = line,
            parserVersion = header?.parserVersion ?: "unknown",
            slotRaw = f["slot"]
        )
    }

    private fun normalizeAddress(obj: RawObject): Long? = parseAddress(obj.addressRaw)

    private data class SnapHeader(val id: String, val identity: String, val name: String, val parserVersion: String, val capturedAtRaw: String)

    private class FieldMap(val map: Map<String, String>) {
        fun require(key: String, line: Int, fragment: String): String =
            map[key] ?: throw FormatCorruptException("缺少必填字段 '$key'", line, fragment)
        operator fun get(key: String): String? = map[key]
        fun getOrElse(key: String, default: () -> String): String = map[key] ?: default()
    }

    private fun fields(rest: String, line: Int): FieldMap {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < rest.length) {
            while (i < rest.length && rest[i].isWhitespace()) i++
            if (i >= rest.length) break
            val keyStart = i
            while (i < rest.length && rest[i] != '=' && !rest[i].isWhitespace()) i++
            if (i >= rest.length || rest[i] != '=') {
                throw FormatCorruptException("字段必须是 key=value 形式", line, rest)
            }
            val key = rest.substring(keyStart, i)
            i++ // skip '='
            val value: String
            if (i < rest.length && rest[i] == '"') {
                i++
                val sb = StringBuilder()
                var closed = false
                while (i < rest.length) {
                    val c = rest[i]
                    if (c == '\\' && i + 1 < rest.length) { sb.append(rest[i + 1]); i += 2; continue }
                    if (c == '"') { closed = true; i++; break }
                    sb.append(c); i++
                }
                if (!closed) throw FormatCorruptException("引号未闭合", line, rest)
                value = sb.toString()
            } else {
                val vStart = i
                while (i < rest.length && !rest[i].isWhitespace()) i++
                value = rest.substring(vStart, i)
            }
            out[key] = value
        }
        return FieldMap(out)
    }

    companion object {
        fun parseSize(raw: String): Long? {
            val s = raw.trim()
            if (s.isEmpty() || s == "?") return null
            return runCatching {
                if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLong(16)
                else s.toLong()
            }.getOrNull()?.takeIf { it >= 0 }
        }

        fun parseAddress(raw: String): Long? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return runCatching {
                if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLong(16)
                else s.toLong()
            }.getOrNull()
        }

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
